package com.benesquivelmusic.daw.core.recording;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code take.manifest} sidecar of one take directory (Recording
 * Reliability book §3.3; story 323): an immutable value written by the
 * {@code capture-flush} thread at take start (the take's initialisation),
 * whenever a segment opens, seals or is discarded (rotation, lane seal), on
 * every overflow episode, at the start of every truncation episode, and at
 * the final seal, so a recovery scan can rebuild the take without the
 * project file.
 *
 * <p><strong>Thread.</strong> {@link #write(Path)} is called on the
 * {@code capture-flush} thread only; {@link #read(Path)} from any
 * non-real-time thread.
 * Instances are immutable and safe to share.</p>
 *
 * <p><strong>Grammar</strong> (line-oriented UTF-8, {@code key=value}, lines
 * beginning with {@code #} are comments, unknown keys are ignored, fields
 * within a value are {@code |}-separated):</p>
 * <pre>
 * manifest-version=1
 * take=&lt;take directory name&gt;
 * started-at=&lt;ISO-8601 instant&gt;
 * sample-rate=&lt;double&gt;
 * bit-depth=&lt;int&gt;
 * stream-channels=&lt;int&gt;
 * start-beat=&lt;double&gt;
 * start-frame=&lt;long&gt;
 * force-cadence-millis=&lt;long&gt;
 * ring-slots=&lt;int&gt;
 * ring-frames=&lt;int&gt;
 * track=&lt;trackId&gt;|&lt;channels&gt;|&lt;compensation-frames&gt;                 (one per armed track, armed order)
 * segment=&lt;trackId&gt;|&lt;lane&gt;|&lt;index&gt;|&lt;relative path, '/'&gt;|&lt;frames or -1&gt;|&lt;streaming|sealed&gt;
 * gap=&lt;trackId or *&gt;|&lt;startFrame&gt;|&lt;dropped-blocks&gt;                  (one per overflow episode; dropped-blocks 0 = a truncation episode)
 * overflow-blocks=&lt;long&gt;
 * truncated-frames=&lt;long&gt;                                          (absent in older files: reads as 0)
 * seal-status=streaming|sealed|aborted
 * sealed-by=stop|disk-exhaustion|write-failure|start-failure       (present iff seal-status != streaming)
 * </pre>
 *
 * <p>Segment order in the manifest <em>is</em> the clip's segment order:
 * {@link #segments()} is canonical — grouped by track in {@link #tracks()}
 * order, then lane ascending, then index ascending — and
 * {@link #segmentsFor(String)} returns one track's entries in that order
 * regardless of the order the file listed them. A segment's relative path is
 * one or more names joined by forward slashes, and every name matches
 * {@code [a-z0-9][a-z0-9._-]*}, does not end with {@code .}, and has a stem
 * — the part before its first {@code .} — that is not a Windows device name
 * ({@code con}, {@code prn}, {@code aux}, {@code nul}, {@code com1} to
 * {@code com9}, {@code lpt1} to {@code lpt9}) or {@code com0}/{@code lpt0}.
 * The rule is decided on the string alone, so every platform accepts and
 * refuses the same paths; no accepted name is one that Win32 trims, reads as
 * a generated 8.3 short name or opens as a device, and no two accepted paths
 * differ only in case, so an accepted path names the same entry inside its
 * take directory on Windows and on POSIX, judged lexically
 * ({@link SegmentEntry#resolve(Path)} states what that does and does not
 * guarantee). Any other path is rejected at construction, and
 * {@link #read(Path)} fails on it with an {@code IOException} naming the
 * file and the line. The track ids and the {@code take} name are checked
 * only for {@code null}, blank values, {@code |} and line breaks, so a
 * reader builds a segment's path through {@link SegmentEntry#resolve(Path)}
 * and never from them.</p>
 *
 * <p><strong>Atomic replace.</strong> {@link #write(Path)} writes
 * {@code take.manifest.tmp} in the take directory through a
 * {@code FileChannel}, forces it to storage ({@code force(true)}), closes
 * it, and only then moves it over {@code take.manifest} with
 * {@code ATOMIC_MOVE + REPLACE_EXISTING}, falling back to
 * {@code REPLACE_EXISTING} alone where the filesystem refuses atomic moves
 * — the Persistence Integrity book §4.1 idiom (same-directory sibling →
 * fsync → atomic move), implemented locally here; story 331 centralises it.
 * A write that fails leaves the previous manifest untouched (and possibly a
 * stale {@code .tmp}, which the next write truncates). On Windows a target
 * that another process holds open without delete sharing makes the move
 * fail with a {@code FileSystemException} (a sharing violation or access
 * denial) — not {@code AtomicMoveNotSupportedException}, so the fallback
 * does not engage; the flush service answers such a failure by retrying,
 * not by ending the take.</p>
 */
public final class TakeManifest {

    /** File name of the sidecar inside a take directory. */
    public static final String FILE_NAME = "take.manifest";

    /** Suffix of the temporary sibling {@link #write(Path)} moves into place. */
    public static final String TMP_SUFFIX = ".tmp";

    /** The only grammar version this class reads and writes. */
    public static final int MANIFEST_VERSION = 1;

    /** Sentinel frame count of a segment that is still streaming. */
    public static final long FRAMES_STREAMING = -1;

    /** U+FEFF, written as a number so the source holds no invisible character. */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    /** The characters every name of a segment path is made of, and the ones it may start with. */
    private static final Pattern SEGMENT_PATH_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /**
     * The stems Windows reserves for devices ({@code con}, {@code prn}, {@code aux}, {@code nul},
     * {@code com1} to {@code com9}, {@code lpt1} to {@code lpt9}), plus {@code com0} and {@code lpt0},
     * which are refused as well; Windows 10 opens a reserved device even when an extension follows
     * ({@code nul.wav} reads NUL).
     */
    private static final Pattern WINDOWS_DEVICE_STEM = Pattern.compile("con|prn|aux|nul|com[0-9]|lpt[0-9]");

    /** Opens the staging file of {@link #write(Path)}: created if missing, truncated if a stale one is left over. */
    static final SegmentWriter.ChannelOpener STAGING_CHANNEL = path -> FileChannel.open(path,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

    /** Seal state of the take as a whole. */
    public enum SealStatus {
        /** Capture in progress (or the JVM died mid-take). */
        STREAMING,
        /** Every segment sealed by the normal stop path. */
        SEALED,
        /** Sealed early by a failure; everything captured so far is intact. */
        ABORTED;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        static SealStatus parse(String token) throws IOException {
            for (SealStatus s : values()) {
                if (s.token().equals(token)) {
                    return s;
                }
            }
            throw new IOException("unknown seal-status '" + token + "'");
        }
    }

    /** What ended the take. */
    public enum SealedBy {
        STOP("stop"),
        DISK_EXHAUSTION("disk-exhaustion"),
        WRITE_FAILURE("write-failure"),
        START_FAILURE("start-failure");

        private final String token;

        SealedBy(String token) {
            this.token = token;
        }

        String token() {
            return token;
        }

        static SealedBy parse(String token) throws IOException {
            for (SealedBy s : values()) {
                if (s.token.equals(token)) {
                    return s;
                }
            }
            throw new IOException("unknown sealed-by '" + token + "'");
        }
    }

    /** Per-segment state. */
    public enum SegmentState {
        STREAMING, SEALED;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        static SegmentState parse(String token) throws IOException {
            for (SegmentState s : values()) {
                if (s.token().equals(token)) {
                    return s;
                }
            }
            throw new IOException("unknown segment state '" + token + "'");
        }
    }

    /**
     * A track whose entire routed block was replaced with silence.
     *
     * @param trackId           owning track id (no {@code |} or line breaks)
     * @param device            resolved input device name
     * @param firstChannel      first routed input channel, zero-based; non-negative
     * @param channelCount      number of routed input channels; positive
     * @param availableChannels number of channels actually delivered by the device; non-negative
     */
    public record RoutingFlag(String trackId, String device, int firstChannel, int channelCount, int availableChannels) {
        public RoutingFlag {
            requireField(trackId, "trackId"); Objects.requireNonNull(device);
            if (firstChannel < 0 || channelCount <= 0 || availableChannels < 0) throw new IllegalArgumentException("invalid routing flag");
        }
    }

    /**
     * One armed track.
     *
     * @param trackId            the track id (no {@code |} or line breaks)
     * @param channels           channels captured for the track; positive
     * @param compensationFrames latency compensation applied at clip placement; non-negative
     */
    public record TrackEntry(String trackId, int channels, long compensationFrames) {
        public TrackEntry {
            requireField(trackId, "trackId");
            if (channels <= 0) {
                throw new IllegalArgumentException("channels must be positive: " + channels);
            }
            if (compensationFrames < 0) {
                throw new IllegalArgumentException(
                        "compensationFrames must not be negative: " + compensationFrames);
            }
        }
    }

    /**
     * One segment file of one track lane.
     *
     * @param trackId      owning track id
     * @param lane         loop-take lane index (0 for a plain take); non-negative
     * @param index        segment index within the track directory; non-negative
     * @param relativePath path relative to the take directory, e.g.
     *                     {@code <trackId>/segment-000.wav} (always the sealed identity,
     *                     even while streaming): one or more names joined by {@code /},
     *                     each matching {@code [a-z0-9][a-z0-9._-]*}, not ending with
     *                     {@code .}, and with a stem (the part before its first {@code .})
     *                     that is not {@code con}, {@code prn}, {@code aux}, {@code nul},
     *                     {@code com0} to {@code com9} or {@code lpt0} to {@code lpt9}.
     *                     {@code null} throws {@link NullPointerException}; a blank path
     *                     throws {@link IllegalArgumentException} without naming it; any
     *                     other path that breaks the rule, among them one holding
     *                     {@code |}, a line break or a backslash, throws
     *                     {@link IllegalArgumentException} naming the path
     * @param frames       exact frame count once sealed, {@link #FRAMES_STREAMING} while streaming
     * @param state        streaming or sealed
     */
    public record SegmentEntry(String trackId, int lane, int index, String relativePath,
                               long frames, SegmentState state) {
        public SegmentEntry {
            requireField(trackId, "trackId");
            requireField(relativePath, "relativePath");
            if (relativePath.indexOf('\\') >= 0) {
                throw new IllegalArgumentException(
                        "relativePath must use forward slashes: " + relativePath);
            }
            if (!isNameSequence(relativePath)) {
                throw new IllegalArgumentException("relativePath must be one or more '/'-separated names, "
                        + "each matching [a-z0-9][a-z0-9._-]*, not ending in '.', and with a stem (the part "
                        + "before its first '.') that is not a Windows device name (con, prn, aux, nul, "
                        + "com1-com9, lpt1-lpt9) or com0/lpt0: " + relativePath);
            }
            if (lane < 0) {
                throw new IllegalArgumentException("lane must not be negative: " + lane);
            }
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative: " + index);
            }
            Objects.requireNonNull(state, "state must not be null");
            if (frames < FRAMES_STREAMING) {
                throw new IllegalArgumentException("frames must be -1 (streaming) or >= 0: " + frames);
            }
            if (state == SegmentState.SEALED && frames < 0) {
                throw new IllegalArgumentException("a sealed segment needs an exact frame count");
            }
        }

        /**
         * Resolves this entry against its take directory: a plain
         * {@link Path#resolve(String) join}, with no check of its own. The
         * guarantee is the constructor's, and it is lexical. Every name of
         * {@code relativePath} denotes itself on Windows and on POSIX alike —
         * no root, drive, UNC prefix or stream, no {@code .} or {@code ..}
         * step, no name Win32 trims, no generated 8.3 short name, no device
         * stem — so the result is {@code takeDirectory} followed by exactly
         * those names: an entry strictly inside {@code takeDirectory}, reached
         * by the same names on every platform. This is judged lexically: a
         * case-insensitive file system (NTFS, and APFS by default) can still
         * match an on-disk entry whose name differs only in case. It names an
         * entry, not necessarily a file, and the file system is never
         * consulted: symbolic links, junctions and mount points inside the
         * take directory are not examined, and the entry may be of any type —
         * a directory such as a track directory, the manifest itself, or a
         * FIFO or device node on POSIX — so a reader that needs a regular file
         * must check that it is one and follow no links.
         * Every character the rule admits is legal in a name on Windows and on
         * POSIX, so on their default file systems the join does not throw
         * {@link java.nio.file.InvalidPathException}; a take directory on
         * another {@link java.nio.file.FileSystem} parses the path by that
         * provider's rules.
         */
        public Path resolve(Path takeDirectory) {
            return Objects.requireNonNull(takeDirectory, "takeDirectory must not be null")
                    .resolve(relativePath);
        }

        /** Returns a copy marked sealed with the exact frame count. */
        public SegmentEntry sealed(long exactFrames) {
            return new SegmentEntry(trackId, lane, index, relativePath, exactFrames, SegmentState.SEALED);
        }

        /**
         * Builds the {@code /}-separated relative reference of {@code file}
         * inside {@code takeDirectory} on any platform. Both paths are made
         * absolute and normalised first, and {@code file} must then lie
         * strictly inside the directory. The names are joined as they are,
         * not checked against the constructor's rule, so a file whose names
         * break it — a capital letter, a space — yields a string the
         * constructor refuses; the names production gives a segment,
         * {@code <track UUID>/segment-NNN.wav}, satisfy it.
         *
         * @throws IllegalArgumentException naming both paths if {@code file}
         *         is not strictly inside {@code takeDirectory} (outside it, or
         *         the directory itself). A segment lives at
         *         {@code <take>/<trackId>/segment-NNN.wav}, so in production
         *         this is a programming error, not a condition to recover from.
         */
        public static String relativePathFor(Path takeDirectory, Path file) {
            Objects.requireNonNull(takeDirectory, "takeDirectory must not be null");
            Objects.requireNonNull(file, "file must not be null");
            Path base = takeDirectory.toAbsolutePath().normalize();
            Path target = file.toAbsolutePath().normalize();
            if (!target.startsWith(base) || target.equals(base)) {
                throw new IllegalArgumentException(
                        "file " + file + " is not inside the take directory " + takeDirectory);
            }
            Path relative = base.relativize(target);
            StringBuilder sb = new StringBuilder();
            for (Path element : relative) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(element);
            }
            return sb.toString();
        }
    }

    /**
     * One loss episode in the captured stream.
     *
     * <p>With {@code droppedBlocks > 0} the entry records a ring overflow:
     * that many whole blocks were dropped, and {@code startFrame} is the
     * transport frame at the end of the last block accepted before the drop
     * ({@code CaptureFlushService}'s class note names the fallback). With
     * {@code droppedBlocks == 0} it marks the start of a truncation episode:
     * the block whose kept frames end at {@code startFrame}, and every
     * consecutive block after it until one fits again, was longer than a
     * ring slot and lost its tail; {@link TakeManifest#truncatedFrames()}
     * carries the total.</p>
     *
     * @param trackId       the affected track, or {@link #ALL_TRACKS}
     * @param startFrame    transport frame position the episode is anchored at; non-negative
     * @param droppedBlocks whole blocks dropped in this episode; {@code 0} for a truncation episode
     */
    public record GapEntry(String trackId, long startFrame, long droppedBlocks) {
        /** Track id meaning "every armed track" (a device-block overflow). */
        public static final String ALL_TRACKS = "*";

        public GapEntry {
            requireField(trackId, "trackId");
            if (startFrame < 0) {
                throw new IllegalArgumentException("startFrame must not be negative: " + startFrame);
            }
            if (droppedBlocks < 0) {
                throw new IllegalArgumentException("droppedBlocks must not be negative: " + droppedBlocks);
            }
        }

        /** Returns whether this entry marks a truncation episode rather than dropped blocks. */
        public boolean isTruncation() {
            return droppedBlocks == 0;
        }
    }

    private final String take;
    private final Instant startedAt;
    private final double sampleRate;
    private final int bitDepth;
    private final int streamChannels;
    private final double startBeat;
    private final long startFrame;
    private final long forceCadenceMillis;
    private final int ringSlots;
    private final int ringFrames;
    private final List<TrackEntry> tracks;
    private final List<SegmentEntry> segments;
    private final List<GapEntry> gaps;
    private final List<RoutingFlag> routingFlags;
    private final long overflowBlocks;
    private final long truncatedFrames;
    private final SealStatus sealStatus;
    private final SealedBy sealedBy; // null iff sealStatus == STREAMING

    private TakeManifest(Builder b) {
        this.take = requireField(b.take, "take");
        this.startedAt = Objects.requireNonNull(b.startedAt, "startedAt must not be null");
        if (b.sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + b.sampleRate);
        }
        if (b.bitDepth <= 0) {
            throw new IllegalArgumentException("bitDepth must be positive: " + b.bitDepth);
        }
        if (b.streamChannels <= 0) {
            throw new IllegalArgumentException("streamChannels must be positive: " + b.streamChannels);
        }
        if (b.startFrame < 0) {
            throw new IllegalArgumentException("startFrame must not be negative: " + b.startFrame);
        }
        if (b.forceCadenceMillis < 0) {
            throw new IllegalArgumentException(
                    "forceCadenceMillis must not be negative: " + b.forceCadenceMillis);
        }
        if (b.ringSlots <= 0 || b.ringFrames <= 0) {
            throw new IllegalArgumentException(
                    "ringSlots/ringFrames must be positive: " + b.ringSlots + "/" + b.ringFrames);
        }
        if (b.overflowBlocks < 0) {
            throw new IllegalArgumentException("overflowBlocks must not be negative: " + b.overflowBlocks);
        }
        if (b.truncatedFrames < 0) {
            throw new IllegalArgumentException("truncatedFrames must not be negative: " + b.truncatedFrames);
        }
        Objects.requireNonNull(b.sealStatus, "sealStatus must not be null");
        if (b.sealStatus == SealStatus.STREAMING && b.sealedBy != null) {
            throw new IllegalArgumentException("a streaming take has no sealed-by");
        }
        if (b.sealStatus != SealStatus.STREAMING && b.sealedBy == null) {
            throw new IllegalArgumentException(b.sealStatus + " requires a sealed-by reason");
        }
        this.sampleRate = b.sampleRate;
        this.bitDepth = b.bitDepth;
        this.streamChannels = b.streamChannels;
        this.startBeat = b.startBeat;
        this.startFrame = b.startFrame;
        this.forceCadenceMillis = b.forceCadenceMillis;
        this.ringSlots = b.ringSlots;
        this.ringFrames = b.ringFrames;
        this.tracks = List.copyOf(b.tracks);
        this.segments = canonicalOrder(this.tracks, b.segments);
        this.gaps = List.copyOf(b.gaps);
        this.routingFlags = List.copyOf(b.routingFlags);
        this.overflowBlocks = b.overflowBlocks;
        this.truncatedFrames = b.truncatedFrames;
        this.sealStatus = b.sealStatus;
        this.sealedBy = b.sealedBy;
    }

    /** Returns a builder with no fields set ({@code sealStatus} defaults to STREAMING). */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder pre-populated from this manifest (for rotation / seal rewrites). */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.take = take;
        b.startedAt = startedAt;
        b.sampleRate = sampleRate;
        b.bitDepth = bitDepth;
        b.streamChannels = streamChannels;
        b.startBeat = startBeat;
        b.startFrame = startFrame;
        b.forceCadenceMillis = forceCadenceMillis;
        b.ringSlots = ringSlots;
        b.ringFrames = ringFrames;
        b.tracks.addAll(tracks);
        b.segments.addAll(segments);
        b.gaps.addAll(gaps);
        b.routingFlags.addAll(routingFlags);
        b.overflowBlocks = overflowBlocks;
        b.truncatedFrames = truncatedFrames;
        b.sealStatus = sealStatus;
        b.sealedBy = sealedBy;
        return b;
    }

    /** Returns the take directory name. */
    public String take() {
        return take;
    }

    /** Returns when the take started. */
    public Instant startedAt() {
        return startedAt;
    }

    /** Returns the stream sample rate. */
    public double sampleRate() {
        return sampleRate;
    }

    /** Returns the segment bit depth. */
    public int bitDepth() {
        return bitDepth;
    }

    /** Returns the device stream's channel count. */
    public int streamChannels() {
        return streamChannels;
    }

    /** Returns the transport beat position at take start. */
    public double startBeat() {
        return startBeat;
    }

    /** Returns the transport frame position at take start. */
    public long startFrame() {
        return startFrame;
    }

    /**
     * Returns the force cadence in milliseconds (the book §2.1 risk window):
     * while the take streams, a segment's un-forced bytes are forced at the
     * {@code capture-flush} thread's first check once this long has passed
     * since the segment's last force, or its open ({@link CaptureFlushService}).
     */
    public long forceCadenceMillis() {
        return forceCadenceMillis;
    }

    /** Returns the capture ring's slot count (the other half of the book §2.1 risk window). */
    public int ringSlots() {
        return ringSlots;
    }

    /** Returns the capture ring's frames per slot. */
    public int ringFrames() {
        return ringFrames;
    }

    /** Returns the armed tracks in armed order. */
    public List<TrackEntry> tracks() {
        return tracks;
    }

    /** Returns every segment in canonical order (track order, lane, index). */
    public List<SegmentEntry> segments() {
        return segments;
    }

    /** Returns one track's segments ordered by lane ascending, then index ascending. */
    public List<SegmentEntry> segmentsFor(String trackId) {
        Objects.requireNonNull(trackId, "trackId must not be null");
        List<SegmentEntry> result = new ArrayList<>();
        for (SegmentEntry s : segments) {
            if (s.trackId().equals(trackId)) {
                result.add(s);
            }
        }
        return List.copyOf(result);
    }

    /** Returns the loss episodes (ring overflows and truncation episodes) in the order recorded. */
    public List<RoutingFlag> routingFlags() { return routingFlags; }

    public List<GapEntry> gaps() {
        return gaps;
    }

    /** Returns the total number of blocks dropped by ring overflow. */
    public long overflowBlocks() {
        return overflowBlocks;
    }

    /**
     * Returns the total number of delivered frames cut off because their
     * block was longer than a ring slot, as of the write that produced this
     * manifest: exact once the take is sealed, a lower bound while a
     * truncation episode is still running.
     */
    public long truncatedFrames() {
        return truncatedFrames;
    }

    /** Returns the take's seal status. */
    public SealStatus sealStatus() {
        return sealStatus;
    }

    /** Returns what ended the take; empty while {@link SealStatus#STREAMING}. */
    public Optional<SealedBy> sealedBy() {
        return Optional.ofNullable(sealedBy);
    }

    /** Returns the sidecar path inside {@code takeDirectory}. */
    public static Path manifestPath(Path takeDirectory) {
        return Objects.requireNonNull(takeDirectory, "takeDirectory must not be null")
                .resolve(FILE_NAME);
    }

    /**
     * Atomically writes this manifest as {@code <takeDirectory>/take.manifest}:
     * staging file written and forced to storage, then moved over the
     * target. Called on the {@code capture-flush} thread.
     *
     * @param takeDirectory the take directory (must exist)
     * @return the manifest path
     * @throws IOException if the temporary file cannot be written, forced or
     *                     moved; the previous manifest, if any, is untouched
     */
    public Path write(Path takeDirectory) throws IOException {
        return write(takeDirectory, STAGING_CHANNEL);
    }

    /**
     * {@link #write(Path)} with the staging file's channel supplied by
     * {@code opener} (test seam: a delegating channel makes the
     * {@code force(true)} that precedes the move observable).
     */
    Path write(Path takeDirectory, SegmentWriter.ChannelOpener opener) throws IOException {
        Objects.requireNonNull(opener, "opener must not be null");
        Path target = manifestPath(takeDirectory);
        Path tmp = target.resolveSibling(FILE_NAME + TMP_SUFFIX);
        ByteBuffer bytes = ByteBuffer.wrap(toText().getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = opener.open(tmp)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            // Content first, name second: the rename below must never
            // publish a manifest whose bytes are not on storage yet.
            channel.force(true);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** Renders the manifest text exactly as {@link #write(Path)} emits it. */
    public String toText() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("# DAWG take manifest (story 323)\n");
        line(sb, "manifest-version", Integer.toString(MANIFEST_VERSION));
        line(sb, "take", take);
        line(sb, "started-at", startedAt.toString());
        line(sb, "sample-rate", Double.toString(sampleRate));
        line(sb, "bit-depth", Integer.toString(bitDepth));
        line(sb, "stream-channels", Integer.toString(streamChannels));
        line(sb, "start-beat", Double.toString(startBeat));
        line(sb, "start-frame", Long.toString(startFrame));
        line(sb, "force-cadence-millis", Long.toString(forceCadenceMillis));
        line(sb, "ring-slots", Integer.toString(ringSlots));
        line(sb, "ring-frames", Integer.toString(ringFrames));
        for (TrackEntry t : tracks) {
            line(sb, "track", t.trackId() + "|" + t.channels() + "|" + t.compensationFrames());
        }
        for (SegmentEntry s : segments) {
            line(sb, "segment", s.trackId() + "|" + s.lane() + "|" + s.index() + "|"
                    + s.relativePath() + "|" + s.frames() + "|" + s.state().token());
        }
        for (RoutingFlag flag : routingFlags) {
            line(sb, "routing-unavailable", flag.trackId() + "|" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(flag.device().getBytes(StandardCharsets.UTF_8)) + "|" + flag.firstChannel()
                    + "|" + flag.channelCount() + "|" + flag.availableChannels());
        }
        for (GapEntry g : gaps) {
            line(sb, "gap", g.trackId() + "|" + g.startFrame() + "|" + g.droppedBlocks());
        }
        line(sb, "overflow-blocks", Long.toString(overflowBlocks));
        line(sb, "truncated-frames", Long.toString(truncatedFrames));
        line(sb, "seal-status", sealStatus.token());
        if (sealedBy != null) {
            line(sb, "sealed-by", sealedBy.token());
        }
        return sb.toString();
    }

    /**
     * Parses a manifest file. Unknown keys are ignored; a missing
     * {@code seal-status} reads as {@link SealStatus#STREAMING}; missing
     * {@code track}/{@code segment}/{@code gap}/{@code overflow-blocks}/
     * {@code truncated-frames} read as empty / zero. The format keys are
     * required.
     *
     * @param manifestFile the {@code take.manifest} path
     * @return the parsed manifest
     * @throws IOException if the file cannot be read, the version is not
     *                     {@link #MANIFEST_VERSION}, a required key is
     *                     missing, or a value is malformed
     */
    public static TakeManifest read(Path manifestFile) throws IOException {
        Objects.requireNonNull(manifestFile, "manifestFile must not be null");
        return parse(Files.readString(manifestFile, StandardCharsets.UTF_8), manifestFile.toString());
    }

    /**
     * Parses manifest text; {@code source} names it in error messages. One
     * leading byte-order mark (U+FEFF), as an editor that re-saved the file
     * may have added, is ignored; anywhere else it is part of its line.
     */
    public static TakeManifest parse(String text, String source) throws IOException {
        Objects.requireNonNull(text, "text must not be null");
        String body = !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
        Map<String, String> scalars = new HashMap<>();
        Builder b = new Builder();
        int lineNo = 0;
        for (String raw : body.split("\r?\n")) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IOException(source + ":" + lineNo + ": expected key=value, found '" + line + "'");
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            switch (key) {
                case "track" -> b.addTrack(parseTrack(value, source, lineNo));
                case "segment" -> b.addSegment(parseSegment(value, source, lineNo));
                case "gap" -> b.addGap(parseGap(value, source, lineNo));
                case "routing-unavailable" -> {
                    try {
                        String[] fields = value.split("\\|", -1);
                        if (fields.length != 5) throw new IllegalArgumentException("expected five fields");
                        b.addRoutingFlag(new RoutingFlag(fields[0], new String(java.util.Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8),
                                parseInt(fields[2], "first-channel", source), parseInt(fields[3], "channel-count", source), parseInt(fields[4], "available-channels", source)));
                    } catch (IllegalArgumentException | IOException malformed) {
                        throw new IOException(source + ":" + lineNo + ": malformed routing-unavailable", malformed);
                    }
                }
                default -> scalars.put(key, value);
            }
        }
        int version = parseInt(required(scalars, "manifest-version", source), "manifest-version", source);
        if (version != MANIFEST_VERSION) {
            throw new IOException(source + ": manifest-version " + version
                    + " is not supported (expected " + MANIFEST_VERSION + ")");
        }
        b.take(required(scalars, "take", source));
        try {
            b.startedAt(Instant.parse(required(scalars, "started-at", source)));
        } catch (DateTimeParseException e) {
            throw new IOException(source + ": malformed started-at", e);
        }
        b.sampleRate(parseDouble(required(scalars, "sample-rate", source), "sample-rate", source));
        b.bitDepth(parseInt(required(scalars, "bit-depth", source), "bit-depth", source));
        b.streamChannels(parseInt(required(scalars, "stream-channels", source), "stream-channels", source));
        b.startBeat(parseDouble(required(scalars, "start-beat", source), "start-beat", source));
        b.startFrame(parseLong(required(scalars, "start-frame", source), "start-frame", source));
        b.forceCadenceMillis(parseLong(required(scalars, "force-cadence-millis", source),
                "force-cadence-millis", source));
        b.ringSlots(parseInt(required(scalars, "ring-slots", source), "ring-slots", source));
        b.ringFrames(parseInt(required(scalars, "ring-frames", source), "ring-frames", source));
        String overflow = scalars.get("overflow-blocks");
        b.overflowBlocks(overflow == null ? 0 : parseLong(overflow, "overflow-blocks", source));
        String truncated = scalars.get("truncated-frames");
        b.truncatedFrames(truncated == null ? 0 : parseLong(truncated, "truncated-frames", source));
        String status = scalars.get("seal-status");
        b.sealStatus(status == null ? SealStatus.STREAMING : SealStatus.parse(status));
        String by = scalars.get("sealed-by");
        b.sealedBy(by == null ? null : SealedBy.parse(by));
        try {
            return b.build();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException(source + ": " + e.getMessage(), e);
        }
    }

    /** Mutable assembler for {@link TakeManifest}; not thread-safe. */
    public static final class Builder {
        private String take;
        private Instant startedAt;
        private double sampleRate;
        private int bitDepth;
        private int streamChannels;
        private double startBeat;
        private long startFrame;
        private long forceCadenceMillis;
        private int ringSlots;
        private int ringFrames;
        private final List<TrackEntry> tracks = new ArrayList<>();
        private final List<SegmentEntry> segments = new ArrayList<>();
        private final List<GapEntry> gaps = new ArrayList<>();
        private final List<RoutingFlag> routingFlags = new ArrayList<>();
        public Builder addRoutingFlag(RoutingFlag flag) {
            Objects.requireNonNull(flag);
            if (routingFlags.stream().noneMatch(f -> f.trackId().equals(flag.trackId()))) routingFlags.add(flag);
            return this;
        }
        private long overflowBlocks;
        private long truncatedFrames;
        private SealStatus sealStatus = SealStatus.STREAMING;
        private SealedBy sealedBy;

        private Builder() {
        }

        public Builder take(String take) {
            this.take = take;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder sampleRate(double sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public Builder bitDepth(int bitDepth) {
            this.bitDepth = bitDepth;
            return this;
        }

        public Builder streamChannels(int streamChannels) {
            this.streamChannels = streamChannels;
            return this;
        }

        public Builder startBeat(double startBeat) {
            this.startBeat = startBeat;
            return this;
        }

        public Builder startFrame(long startFrame) {
            this.startFrame = startFrame;
            return this;
        }

        public Builder forceCadenceMillis(long forceCadenceMillis) {
            this.forceCadenceMillis = forceCadenceMillis;
            return this;
        }

        public Builder ringSlots(int ringSlots) {
            this.ringSlots = ringSlots;
            return this;
        }

        public Builder ringFrames(int ringFrames) {
            this.ringFrames = ringFrames;
            return this;
        }

        public Builder addTrack(TrackEntry track) {
            tracks.add(Objects.requireNonNull(track, "track must not be null"));
            return this;
        }

        public Builder tracks(List<TrackEntry> tracks) {
            this.tracks.clear();
            this.tracks.addAll(Objects.requireNonNull(tracks, "tracks must not be null"));
            return this;
        }

        public Builder addSegment(SegmentEntry segment) {
            segments.add(Objects.requireNonNull(segment, "segment must not be null"));
            return this;
        }

        public Builder segments(List<SegmentEntry> segments) {
            this.segments.clear();
            this.segments.addAll(Objects.requireNonNull(segments, "segments must not be null"));
            return this;
        }

        /**
         * Replaces the entry with the same (trackId, lane, index) — or appends
         * when there is none. The flush thread uses this to flip a segment
         * from streaming to sealed.
         */
        public Builder putSegment(SegmentEntry segment) {
            Objects.requireNonNull(segment, "segment must not be null");
            for (int i = 0; i < segments.size(); i++) {
                SegmentEntry existing = segments.get(i);
                if (existing.trackId().equals(segment.trackId())
                        && existing.lane() == segment.lane()
                        && existing.index() == segment.index()) {
                    segments.set(i, segment);
                    return this;
                }
            }
            segments.add(segment);
            return this;
        }

        /**
         * Removes the entry with the same (trackId, lane, index), if any. The
         * flush thread uses this when a zero-frame tail segment is discarded
         * at seal time instead of being sealed (its file is deleted, so the
         * manifest must stop naming it), when a pre-opened loop lane is
         * discarded — also if its empty file could not be deleted: the file
         * is no segment of the take — and when such a lane hands its open,
         * empty file to the lane before it at a rotation: the file is not
         * deleted then, and the same index is listed again under that
         * lane.
         *
         * @return whether an entry was removed
         */
        public boolean removeSegment(String trackId, int lane, int index) {
            Objects.requireNonNull(trackId, "trackId must not be null");
            return segments.removeIf(s -> s.trackId().equals(trackId)
                    && s.lane() == lane && s.index() == index);
        }

        public Builder addGap(GapEntry gap) {
            gaps.add(Objects.requireNonNull(gap, "gap must not be null"));
            return this;
        }

        public Builder gaps(List<GapEntry> gaps) {
            this.gaps.clear();
            this.gaps.addAll(Objects.requireNonNull(gaps, "gaps must not be null"));
            return this;
        }

        public Builder overflowBlocks(long overflowBlocks) {
            this.overflowBlocks = overflowBlocks;
            return this;
        }

        public Builder truncatedFrames(long truncatedFrames) {
            this.truncatedFrames = truncatedFrames;
            return this;
        }

        public Builder sealStatus(SealStatus sealStatus) {
            this.sealStatus = sealStatus;
            return this;
        }

        /** Sets the reason; {@code null} clears it (only valid while streaming). */
        public Builder sealedBy(SealedBy sealedBy) {
            this.sealedBy = sealedBy;
            return this;
        }

        /** Marks the take {@link SealStatus#SEALED} by {@code reason}. */
        public Builder sealed(SealedBy reason) {
            this.sealStatus = SealStatus.SEALED;
            this.sealedBy = Objects.requireNonNull(reason, "reason must not be null");
            return this;
        }

        /** Marks the take {@link SealStatus#ABORTED} by {@code reason}. */
        public Builder aborted(SealedBy reason) {
            this.sealStatus = SealStatus.ABORTED;
            this.sealedBy = Objects.requireNonNull(reason, "reason must not be null");
            return this;
        }

        /** Validates and builds. */
        public TakeManifest build() {
            return new TakeManifest(this);
        }
    }

    // ── parsing helpers ─────────────────────────────────────────────────

    private static TrackEntry parseTrack(String value, String source, int lineNo) throws IOException {
        String[] f = fields(value, 3, "track", source, lineNo);
        return new TrackEntry(f[0], parseInt(f[1], "track.channels", source),
                parseLong(f[2], "track.compensation-frames", source));
    }

    private static SegmentEntry parseSegment(String value, String source, int lineNo) throws IOException {
        String[] f = fields(value, 6, "segment", source, lineNo);
        try {
            return new SegmentEntry(f[0], parseInt(f[1], "segment.lane", source),
                    parseInt(f[2], "segment.index", source), f[3],
                    parseLong(f[4], "segment.frames", source), SegmentState.parse(f[5]));
        } catch (IllegalArgumentException e) {
            throw new IOException(source + ":" + lineNo + ": " + e.getMessage(), e);
        }
    }

    private static GapEntry parseGap(String value, String source, int lineNo) throws IOException {
        String[] f = fields(value, 3, "gap", source, lineNo);
        try {
            return new GapEntry(f[0], parseLong(f[1], "gap.start-frame", source),
                    parseLong(f[2], "gap.dropped-blocks", source));
        } catch (IllegalArgumentException e) {
            throw new IOException(source + ":" + lineNo + ": " + e.getMessage(), e);
        }
    }

    private static String[] fields(String value, int expected, String key, String source, int lineNo)
            throws IOException {
        String[] f = value.split("\\|", -1);
        if (f.length != expected) {
            throw new IOException(source + ":" + lineNo + ": " + key + " needs " + expected
                    + " '|'-separated fields, found " + f.length);
        }
        return f;
    }

    private static String required(Map<String, String> scalars, String key, String source) throws IOException {
        String v = scalars.get(key);
        if (v == null) {
            throw new IOException(source + ": missing required key '" + key + "'");
        }
        return v;
    }

    private static int parseInt(String v, String key, String source) throws IOException {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IOException(source + ": " + key + " is not an integer: '" + v + "'", e);
        }
    }

    private static long parseLong(String v, String key, String source) throws IOException {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IOException(source + ": " + key + " is not an integer: '" + v + "'", e);
        }
    }

    private static double parseDouble(String v, String key, String source) throws IOException {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IOException(source + ": " + key + " is not a number: '" + v + "'", e);
        }
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value).append('\n');
    }

    private static String requireField(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('|') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " must not contain '|' or line breaks: " + value);
        }
        return value;
    }

    /**
     * The segment path rule, decided on the string and never with the
     * {@link Path} semantics of the platform reading the manifest: one or
     * more names joined by {@code '/'}, each of which matches
     * {@code SEGMENT_PATH_NAME} ({@code [a-z0-9][a-z0-9._-]*}), does not end
     * with {@code '.'}, and has a stem — the part before its first
     * {@code '.'}, or the whole name — that {@code WINDOWS_DEVICE_STEM} does
     * not match ({@code con}, {@code prn}, {@code aux}, {@code nul},
     * {@code com0} to {@code com9}, {@code lpt0} to {@code lpt9}; names are
     * lowercase, so the stem is compared as written, and {@code console},
     * {@code com10} and {@code nul-1} are legal). That refuses, on every
     * platform: an empty name (a leading {@code '/'}, POSIX-absolute and
     * root-relative on Windows; {@code //server/share}, a UNC path;
     * {@code a//b}; a trailing {@code '/'}); {@code .}, {@code ..} and every
     * other name made only of dots; a trailing {@code '.'}, which Win32 would
     * trim; a leading {@code '.'} or {@code '-'}; a device stem
     * ({@code nul}, {@code con.wav}, {@code com1.x}); and every character
     * outside {@code [a-z0-9._-]}, among them {@code ':'} (the drive forms
     * {@code C:/…} and {@code C:x}, an NTFS alternate data stream such as
     * {@code segment.wav:stream}), {@code '~'} (a generated 8.3 short name
     * such as {@code segmen~1.wav}), a space, and every capital and non-ASCII
     * letter.
     * The backslash is refused separately, before this rule runs.
     */
    private static boolean isNameSequence(String path) {
        for (String name : path.split("/", -1)) {
            if (!isPortableName(name)) {
                return false;
            }
        }
        return true;
    }

    /** Whether one name of a segment path keeps the {@code isNameSequence} rule. */
    private static boolean isPortableName(String name) {
        if (!SEGMENT_PATH_NAME.matcher(name).matches() || name.endsWith(".")) {
            return false;
        }
        int firstDot = name.indexOf('.');
        String stem = firstDot < 0 ? name : name.substring(0, firstDot);
        return !WINDOWS_DEVICE_STEM.matcher(stem).matches();
    }

    private static List<SegmentEntry> canonicalOrder(List<TrackEntry> tracks, List<SegmentEntry> segments) {
        Map<String, Integer> trackOrder = new HashMap<>();
        for (int i = 0; i < tracks.size(); i++) {
            trackOrder.putIfAbsent(tracks.get(i).trackId(), i);
        }
        Comparator<SegmentEntry> byTrack = Comparator.comparingInt(
                s -> trackOrder.getOrDefault(s.trackId(), Integer.MAX_VALUE));
        List<SegmentEntry> sorted = new ArrayList<>(segments);
        sorted.sort(byTrack.thenComparing(SegmentEntry::trackId)
                .thenComparingInt(SegmentEntry::lane)
                .thenComparingInt(SegmentEntry::index));
        return List.copyOf(sorted);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TakeManifest that)) {
            return false;
        }
        return Double.compare(sampleRate, that.sampleRate) == 0
                && bitDepth == that.bitDepth
                && streamChannels == that.streamChannels
                && Double.compare(startBeat, that.startBeat) == 0
                && startFrame == that.startFrame
                && forceCadenceMillis == that.forceCadenceMillis
                && ringSlots == that.ringSlots
                && ringFrames == that.ringFrames
                && overflowBlocks == that.overflowBlocks
                && truncatedFrames == that.truncatedFrames
                && take.equals(that.take)
                && startedAt.equals(that.startedAt)
                && tracks.equals(that.tracks)
                && segments.equals(that.segments)
                && gaps.equals(that.gaps)
                && routingFlags.equals(that.routingFlags)
                && sealStatus == that.sealStatus
                && sealedBy == that.sealedBy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(take, startedAt, sampleRate, bitDepth, streamChannels, startBeat,
                startFrame, forceCadenceMillis, ringSlots, ringFrames, tracks, segments, gaps, routingFlags,
                overflowBlocks, truncatedFrames, sealStatus, sealedBy);
    }

    @Override
    public String toString() {
        return "TakeManifest[" + take + ", " + sealStatus
                + (sealedBy == null ? "" : "/" + sealedBy)
                + ", tracks=" + tracks.size() + ", segments=" + segments.size()
                + ", gaps=" + gaps.size() + "]";
    }
}
