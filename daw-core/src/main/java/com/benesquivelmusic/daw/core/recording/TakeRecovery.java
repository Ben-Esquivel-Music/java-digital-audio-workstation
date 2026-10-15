package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.TakeManifest.SealStatus;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentEntry;
import com.benesquivelmusic.daw.core.recording.TakeManifest.SegmentState;
import com.benesquivelmusic.daw.core.recording.TakeManifest.TrackEntry;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The crash-recovery grammar of Recording Reliability book §4.7 (story 327):
 * rebuilds, from the bytes on disk alone, a take whose recording JVM never
 * sealed it — a crash, a kill, a power loss mid-take — by applying the
 * ordinary seal (book §3.4) to every segment it left streaming, and marks the
 * take {@code aborted}. One rescue grammar for unplug, crash and power loss:
 * the device-loss rescue seals the same segments the same way, on the flush
 * thread, while this class does it afterwards for a take no flush thread
 * owns any more.
 *
 * <p><strong>Thread.</strong> Any non-real-time thread; in the app the
 * project-open recovery pass (story 332) is to call {@link #scan(Path)} on
 * the thread that opens the project. Never for a take a flush thread is
 * still writing: the scan takes over the {@code .part} files it finds, and a
 * writer still appending to one would be writing behind the seal.</p>
 *
 * <p><strong>What is a candidate.</strong> A take directory is recovered
 * when its {@code take.manifest} exists and parses, and either reads
 * {@code seal-status=streaming} (the JVM died while the take streamed) or
 * lists a segment entry still in state {@code streaming} — whether that
 * entry's {@code .part} exists (a seal that never finished), its sealed
 * {@code .wav} exists (a crash, or a manifest write that failed, between
 * the rename and the manifest write), or neither does. A take whose
 * manifest is sealed or aborted and whose every entry is sealed is not a
 * candidate: a second scan over a recovered take returns nothing for it,
 * and a {@code .part} such a manifest does not list does not make the take
 * one, and nothing is registered for it: one that holds no frame (the
 * zero-frame rule below) — a standby the live seal could not delete, or a
 * file a scan dropped from the manifest and then could not delete — is
 * deleted and the deletion logged at {@link Level#WARNING}; one with frames,
 * or one the rule cannot vouch for, is logged at {@link Level#WARNING} once
 * per scan and left as it is.
 * A directory with no manifest is skipped: the manifest is the only record
 * of the take's format and anchor, and a {@code .part} without one is
 * logged and left as it is. No link is ever followed: a take directory
 * that is not a real directory — a symbolic link, a junction, a file — is
 * skipped and logged at {@link Level#WARNING} before its manifest is read,
 * and {@link #scan(Path)} does not list one; a manifest the scan writes
 * lands only in a real take directory.</p>
 *
 * <p><strong>What the seal does.</strong> For every manifest segment entry
 * in state {@code streaming}:</p>
 * <ul>
 *   <li>Its {@code .part} exists and holds no whole frame — the 44-byte
 *       header a loop-record standby or a fresh rotation tail leaves — its
 *       entry is removed from the manifest and the file is deleted, as the
 *       live seal discards an empty tail ({@code RecordingSession.discardEmptyTail},
 *       {@link TakeManifest.Builder#removeSegment}): the ordinary seal never
 *       produces a zero-frame segment. It is neither sealed nor reported.
 *       The delete waits for the manifest write (the ordering paragraph
 *       below): the file is never gone while the manifest on disk still
 *       lists it. A delete that fails after the write is reported, the
 *       manifest standing, and the next scan deletes the file as an
 *       unlisted zero-frame {@code .part}.</li>
 *   <li>Its {@code .part} exists with frames: the frame count is derived
 *       from the file length, {@code (length − 44) / bytesPerFrame} floored
 *       to whole frames ({@link SegmentFile#describe(Path)}; a torn trailing
 *       frame is dropped), with {@code bytesPerFrame} from the manifest's
 *       bit depth and the track's channel count cross-checked against the
 *       file's own header. The file is truncated to those whole frames, its
 *       RIFF and data sizes are patched with the bytes
 *       {@link SegmentWriter#seal()} writes ({@link SegmentWriter#patchSizeFields}),
 *       forced to storage, and atomically renamed to its sealed {@code .wav};
 *       the entry is replaced by {@link SegmentEntry#sealed(long)} with that
 *       count.</li>
 *   <li>Its {@code .part} is gone but its {@code .wav} exists — the crash
 *       or the failed manifest write fell between the rename and the
 *       manifest write — the entry is marked sealed with the frame count
 *       the {@code .wav}'s data size declares.</li>
 *   <li>Anything else is left untouched and reported in
 *       {@link RecoveredTake#unrecoverable()}, never deleted: a {@code .part}
 *       shorter than the 44-byte header, one whose header disagrees with the
 *       manifest, one longer than a segment may be, one whose sealed
 *       {@code .wav} already exists (a segment is never overwritten), a
 *       track the manifest does not list, a segment with neither file, or
 *       one whose track directory — any directory the entry's path names
 *       below the take directory — is a link or not a directory, in which
 *       case neither file is read: {@link SegmentEntry#resolve} is
 *       lexical, and a link there is the one way a listed path leads out
 *       of the take.</li>
 * </ul>
 * <p>A {@code .part} a candidate's manifest does not list and that holds
 * frames — the manifest write lagged the segment's open — is reported as
 * unrecoverable and left as it is: its name gives the track and the index,
 * but not the lane, so no entry can be derived for it safely. An
 * unrecoverable file keeps the take a candidate, and is reported again by
 * every later scan, until it is dealt with. One that holds no frame is
 * deleted, the deletion logged at {@link Level#WARNING}, and not reported:
 * it is what a crash between the manifest write and the delete leaves,
 * what the live seal leaves when its delete of a standby failed, and what
 * a segment opened ahead of the manifest write leaves when the JVM died
 * before a frame was appended to it. <em>Zero-frame</em>, for a
 * {@code .part} no manifest entry describes, is decided from the file, the
 * manifest and the track its directory names — the layout is
 * {@code <take>/<trackId>/segment-NNN.wav.part}, so the directory is the
 * track id, the key {@code sealEntry} resolves a listed entry's track by.
 * The file is zero-frame when its header parses as a canonical segment header
 * ({@link SegmentFile#describe(Path)}) that agrees with the manifest's bit
 * depth and sample rate and with the channel count of that track's entry,
 * checked in that order as for a listed entry, and its length holds no
 * whole frame of that header's own frame size, the count a listed entry is
 * discarded on. A file shorter than the header, one whose header does not
 * parse, one whose header disagrees with the manifest or with its track,
 * and one under a directory naming no track the manifest lists are not
 * zero-frame: none is provably this take's empty file, and each is left,
 * and reported or logged with the reason, as a file with frames is. The
 * channel check is load-bearing: a header claiming more channels than the
 * track widens the frame, so a file holding real frames of the track's
 * width floors to none — the listed-entry rule refuses that disagreement,
 * and so does this one.</p>
 *
 * <p>Then, when the manifest was streaming or any entry changed here, the
 * manifest is written atomically ({@link TakeManifest#write(Path)}) with
 * {@code seal-status=aborted}. The reason is
 * {@code sealed-by=crash-recovery}, and {@code sealed-at} is now, only for
 * a take that was still streaming — the scan's own seal, the take ended
 * here; a take the flush thread had already sealed or aborted keeps the
 * reason it found ({@code write-failure}, {@code device-loss}, …) and the
 * {@code sealed-at} it found (now only when it had none), so the audit
 * trail of why and when the take ended survives the scan that healed its
 * stragglers. The result is playable:
 * {@link SegmentFile#readFrames(Path)} on a sealed segment returns exactly
 * the frames its entry declares. Only once that write has returned — or was
 * not needed — are the zero-frame {@code .part} files deleted, the listed
 * ones whose entries it dropped and the unlisted ones: at no instant does
 * the manifest on disk list a file that is gone (book §2.5). A write that
 * fails leaves them all on disk beside the manifest it found, and the next
 * scan discards them again; a delete that fails after the write is reported,
 * undoes nothing, and is retried by the next scan, which finds an unlisted
 * zero-frame {@code .part}.</p>
 */
public final class TakeRecovery {

    private static final Logger LOG = Logger.getLogger(TakeRecovery.class.getName());

    /** Fault seam (test-only); see {@link #setRecoverFaultForTest(Consumer)}. */
    private static volatile Consumer<Path> recoverFaultForTest;

    private TakeRecovery() {
        // utility class
    }

    /**
     * A segment file the scan left untouched.
     *
     * @param file   the file, as found ({@code .part} or {@code .wav})
     * @param reason why it was not sealed; never blank
     */
    public record Unrecoverable(Path file, String reason) {
        public Unrecoverable {
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    /**
     * One take the scan recovered.
     *
     * @param takeDirectory  the take directory
     * @param manifest       the manifest as it is on disk after the scan:
     *                       {@code aborted} with this scan's {@code sealed-at}
     *                       when it wrote it (reason: see the class note),
     *                       the manifest found when it changed nothing
     *                       (every remaining file was unrecoverable)
     * @param sealedSegments the entries this scan sealed, in manifest order;
     *                       empty when the crash left nothing streaming,
     *                       nothing could be sealed, or every streaming
     *                       file was an empty tail — dropped from the
     *                       manifest and deleted, or, when its delete was
     *                       refused after the write, reported in
     *                       {@code unrecoverable} instead
     * @param unrecoverable  the files left untouched, with why — a
     *                       zero-frame {@code .part} whose delete failed
     *                       after the manifest write among them
     */
    public record RecoveredTake(Path takeDirectory, TakeManifest manifest,
                                List<SegmentEntry> sealedSegments, List<Unrecoverable> unrecoverable) {
        public RecoveredTake {
            Objects.requireNonNull(takeDirectory, "takeDirectory must not be null");
            Objects.requireNonNull(manifest, "manifest must not be null");
            sealedSegments = List.copyOf(Objects.requireNonNull(sealedSegments, "sealedSegments must not be null"));
            unrecoverable = List.copyOf(Objects.requireNonNull(unrecoverable, "unrecoverable must not be null"));
        }

        /** Returns the manifest's sealed segments: what a reader can play. */
        public List<SegmentEntry> playableSegments() {
            return manifest.segments().stream().filter(s -> s.state() == SegmentState.SEALED).toList();
        }
    }

    /** What {@link #sealEntry} did with one streaming entry. */
    private sealed interface Outcome permits Sealed, Discarded, Left {
    }

    /** The entry's file is sealed with exactly {@code frames} frames. */
    private record Sealed(long frames) implements Outcome {
    }

    /**
     * The entry's {@code .part} holds no frame: the entry leaves the
     * manifest, and the file is deleted once the manifest without it is on
     * disk.
     */
    private record Discarded(Path part) implements Outcome {
        private Discarded {
            Objects.requireNonNull(part, "part must not be null");
        }
    }

    /** The entry's files were left as they were; the reason is in the report. */
    private record Left() implements Outcome {
    }

    /** What {@link #inspectUnlisted} found a {@code .part} no manifest entry describes to be (class note). */
    private sealed interface UnlistedPart permits ZeroFrame, WithFrames, NotProvablyZeroFrame {
    }

    /** Provably this take's file and holding no whole frame: deleted, after the manifest write when there is one. */
    private record ZeroFrame() implements UnlistedPart {
    }

    /** Holds {@code frames} whole frames: left, since its lane cannot be derived from its name. */
    private record WithFrames(long frames) implements UnlistedPart {
    }

    /** Not provably this take's zero-frame file, for {@code reason}: left as it is. */
    private record NotProvablyZeroFrame(String reason) implements UnlistedPart {
        private NotProvablyZeroFrame {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * Recovers every candidate take directory under {@code takesDirectory}
     * ({@code <project>/audio/takes}), in directory-name order — the take
     * stamp, so chronological. A take whose recovery fails — with an
     * {@link IOException} (an unreadable or malformed manifest, a write
     * that fails) or a {@link RuntimeException} — is logged at
     * {@link Level#WARNING} and skipped, so one damaged take does not hide
     * the others; a directory that is not a candidate yields nothing, and
     * is not silent about a {@code .part} its manifest does not list: a
     * zero-frame one is deleted and the deletion logged at
     * {@link Level#WARNING}, any other is logged at {@link Level#WARNING}
     * with why it is left (class note). Only real subdirectories are
     * visited: an entry that is a link (symbolic or junction) or a file is
     * not listed, and nothing behind it is read or touched.
     *
     * @param takesDirectory the takes directory; a missing one, or one that
     *                       is a link or not a directory, yields no takes
     * @return the recovered takes, in directory-name order
     * @throws IOException if {@code takesDirectory} cannot be listed
     */
    public static List<RecoveredTake> scan(Path takesDirectory) throws IOException {
        Objects.requireNonNull(takesDirectory, "takesDirectory must not be null");
        if (!Files.isDirectory(takesDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<RecoveredTake> recovered = new ArrayList<>();
        for (Path takeDirectory : subdirectories(takesDirectory)) {
            try {
                recover(takeDirectory).ifPresent(recovered::add);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "take recovery skipped " + takeDirectory + ": " + reasonOf(e), e);
            }
        }
        return List.copyOf(recovered);
    }

    /**
     * Recovers one take directory, if it is a candidate (class note).
     *
     * @param takeDirectory the take directory
     * @return the recovered take, or empty when the directory is not a
     *         candidate: missing, a link or not a directory (logged at
     *         {@link Level#WARNING} unless missing, and nothing behind it
     *         read or touched), no manifest, or a manifest already sealed
     *         or aborted whose every entry is sealed
     * @throws IOException if the manifest cannot be read or parsed, if the
     *                     directory cannot be listed, or if the final
     *                     manifest cannot be written (the segments sealed
     *                     before that stay sealed, and the next scan finds
     *                     their entries still streaming and records them
     *                     from their {@code .wav} files; the zero-frame
     *                     {@code .part} files stay on disk, listed as they
     *                     were, and the next scan discards them)
     */
    public static Optional<RecoveredTake> recover(Path takeDirectory) throws IOException {
        Objects.requireNonNull(takeDirectory, "takeDirectory must not be null");
        Consumer<Path> fault = recoverFaultForTest;
        if (fault != null) {
            fault.accept(takeDirectory);
        }
        if (!Files.isDirectory(takeDirectory, LinkOption.NOFOLLOW_LINKS)) {
            // A link — symbolic, a junction, dangling — or a file where the
            // take directory should be: nothing behind it is this take's, so
            // nothing behind it is read, written or deleted. A missing
            // directory is simply no take.
            if (Files.exists(takeDirectory, LinkOption.NOFOLLOW_LINKS)) {
                LOG.log(Level.WARNING, "take recovery skipped " + takeDirectory
                        + ": a link, or not a directory; nothing behind it is read or touched");
            }
            return Optional.empty();
        }
        Path manifestFile = TakeManifest.manifestPath(takeDirectory);
        List<Path> parts = streamingFilesUnder(takeDirectory);
        if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!parts.isEmpty()) {
                LOG.log(Level.WARNING, "take recovery skipped " + takeDirectory + ": " + parts.size()
                        + " streaming segment(s) but no " + TakeManifest.FILE_NAME + "; left as they are");
            }
            return Optional.empty();
        }
        TakeManifest found = TakeManifest.read(manifestFile);
        boolean streaming = found.sealStatus() == SealStatus.STREAMING;
        boolean entryStreaming = found.segments().stream().anyMatch(s -> s.state() == SegmentState.STREAMING);
        if (!streaming && !entryStreaming) {
            // A healthy take: sealed or aborted by its flush thread, every
            // entry sealed. A .part it does not list is no segment of it: one
            // that provably holds no frame is deleted, any other is left.
            for (Path orphan : parts) {
                switch (inspectUnlisted(orphan, found)) {
                    case ZeroFrame() -> deleteUnlistedZeroFramePart(orphan, takeDirectory)
                            .ifPresent(TakeRecovery::logLeft);
                    case WithFrames(long frames) -> LOG.log(Level.WARNING, "take recovery left " + orphan
                            + " untouched: " + takeDirectory + " is sealed and does not list it; it holds " + frames
                            + " frame(s) and is no segment of the take");
                    case NotProvablyZeroFrame(String reason) -> LOG.log(Level.WARNING, "take recovery left " + orphan
                            + " untouched: " + takeDirectory + " is sealed and does not list it, and it is not"
                            + " provably zero-frame: " + reason);
                }
            }
            return Optional.empty();
        }

        TakeManifest.Builder builder = found.toBuilder();
        List<SegmentEntry> sealedNow = new ArrayList<>();
        List<Unrecoverable> unrecoverable = new ArrayList<>();
        List<Path> emptyParts = new ArrayList<>();
        List<Path> orphanParts = new ArrayList<>();
        Set<Path> listedParts = new HashSet<>();
        boolean manifestChanged = false;
        for (SegmentEntry entry : found.segments()) {
            Path wav = entry.resolve(takeDirectory);
            Path part = SegmentWriter.partPathFor(wav);
            listedParts.add(part.toAbsolutePath().normalize());
            if (entry.state() == SegmentState.SEALED) {
                continue;
            }
            switch (sealEntry(takeDirectory, found, entry, part, wav, unrecoverable)) {
                case Sealed(long frames) -> {
                    SegmentEntry sealed = entry.sealed(frames);
                    builder.putSegment(sealed);
                    sealedNow.add(sealed);
                    manifestChanged = true;
                }
                case Discarded(Path emptyPart) -> {
                    builder.removeSegment(entry.trackId(), entry.lane(), entry.index());
                    emptyParts.add(emptyPart);
                    manifestChanged = true;
                }
                case Left() -> {
                    // reported by sealEntry
                }
            }
        }
        for (Path part : parts) {
            if (listedParts.contains(part.toAbsolutePath().normalize())) {
                continue;
            }
            switch (inspectUnlisted(part, found)) {
                case ZeroFrame() -> orphanParts.add(part);
                case WithFrames(long frames) -> unrecoverable.add(new Unrecoverable(part,
                        "not listed in the manifest; it holds " + frames + " frame(s) and its lane cannot be derived"
                                + " from its name, so it is left as it is"));
                case NotProvablyZeroFrame(String reason) -> unrecoverable.add(new Unrecoverable(part,
                        "not listed in the manifest and not provably zero-frame: " + reason + "; left as it is"));
            }
        }

        TakeManifest manifest = found;
        if (streaming || manifestChanged) {
            SealedBy reason = streaming ? SealedBy.CRASH_RECOVERY : found.sealedBy().orElseThrow();
            Instant sealedAt = streaming ? Instant.now() : found.sealedAt().orElse(Instant.now());
            manifest = builder.aborted(reason).sealedAt(sealedAt).build();
            manifest.write(takeDirectory);
        }
        // Only now, with the manifest on disk listing none of them, do the
        // zero-frame files go: a write that threw above left every one of
        // them beside the manifest that still lists it.
        for (Path emptyPart : emptyParts) {
            deleteZeroFramePart(emptyPart).ifPresent(unrecoverable::add);
        }
        for (Path orphan : orphanParts) {
            deleteUnlistedZeroFramePart(orphan, takeDirectory).ifPresent(unrecoverable::add);
        }
        unrecoverable.forEach(TakeRecovery::logLeft);
        return Optional.of(new RecoveredTake(takeDirectory, manifest, sealedNow, unrecoverable));
    }

    private static void logLeft(Unrecoverable left) {
        LOG.log(Level.WARNING, "take recovery left " + left.file() + " untouched: " + left.reason());
    }

    /**
     * Seals one streaming entry from whichever of its files exists, marks it
     * discarded when its {@code .part} holds no frame (the file is deleted by
     * the caller, after the manifest write), or reports why it cannot be
     * sealed. The pre-checks here — every directory between the take
     * directory and the files must be a real directory, the sealed sibling
     * must not exist, the track must be listed — are the never-overwrite
     * and never-follow-a-link guarantees of {@link #sealPart} and of the
     * caller's delete: {@link SegmentEntry#resolve} is lexical, so a link
     * at the track directory is the one way a listed path leads out of the
     * take, and it is refused ({@link #linkedAncestor}) before either file
     * is so much as stat'd.
     */
    private static Outcome sealEntry(Path takeDirectory, TakeManifest manifest, SegmentEntry entry, Path part,
                                     Path wav, List<Unrecoverable> unrecoverable) {
        Optional<Path> linked = linkedAncestor(takeDirectory, wav);
        if (linked.isPresent()) {
            unrecoverable.add(new Unrecoverable(wav, "its directory " + linked.get()
                    + " is a link, or not a directory of the take; nothing behind it is read, sealed or deleted"));
            return new Left();
        }
        boolean partExists = Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS);
        boolean wavExists = Files.exists(wav, LinkOption.NOFOLLOW_LINKS);
        Optional<TrackEntry> track = listedTrack(manifest, entry.trackId());
        if (partExists) {
            if (wavExists) {
                unrecoverable.add(new Unrecoverable(part, "its sealed sibling " + wav.getFileName()
                        + " already exists; a segment is never overwritten"));
                return new Left();
            }
            if (track.isEmpty()) {
                unrecoverable.add(new Unrecoverable(part, "its track " + entry.trackId()
                        + " is not listed in the manifest"));
                return new Left();
            }
            try {
                SegmentFile.Description description = describeStreaming(part, manifest, track.get());
                if (description.frameCount() == 0) {
                    return new Discarded(part);
                }
                return new Sealed(sealPart(part, wav, description));
            } catch (IOException e) {
                unrecoverable.add(new Unrecoverable(part, reasonOf(e)));
                return new Left();
            }
        }
        if (Files.isRegularFile(wav, LinkOption.NOFOLLOW_LINKS)) {
            if (track.isEmpty()) {
                unrecoverable.add(new Unrecoverable(wav, "its track " + entry.trackId()
                        + " is not listed in the manifest"));
                return new Left();
            }
            try {
                SegmentFile.Description sealed = SegmentFile.describe(wav);
                requireHeaderAgreement(wav, sealed, manifest, track.get());
                return new Sealed(sealed.frameCount());
            } catch (IOException e) {
                unrecoverable.add(new Unrecoverable(wav, reasonOf(e)));
                return new Left();
            }
        }
        unrecoverable.add(new Unrecoverable(wav, "neither the streaming " + part.getFileName()
                + " nor the sealed file exists"));
        return new Left();
    }

    /**
     * The first directory component strictly between {@code takeDirectory}
     * and {@code file} — the track directory of the layout
     * {@code <take>/<trackId>/segment-NNN.wav}, or any deeper name a
     * manifest's relative path carries — that exists but is not a real
     * directory: a symbolic link, a junction, a dangling link or a file. The
     * walk is top-down from the take directory and stops at the first such
     * component, so no name behind a link is ever stat'd; a component that
     * is missing ends the walk empty (nothing below it can exist, and the
     * caller's "neither file exists" outcome stands). The take directory
     * itself is the caller's to check ({@link #recover}), and the final
     * component is checked by the caller with {@code NOFOLLOW_LINKS}.
     */
    private static Optional<Path> linkedAncestor(Path takeDirectory, Path file) {
        Path relativeParent = takeDirectory.relativize(file).getParent();
        if (relativeParent == null) {
            return Optional.empty();
        }
        Path component = takeDirectory;
        for (Path name : relativeParent) {
            component = component.resolve(name);
            if (!Files.exists(component, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            if (!Files.isDirectory(component, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(component);
            }
        }
        return Optional.empty();
    }

    /**
     * Describes a {@code .part} no writer will finish and checks it against
     * the manifest. Throws with a message naming the file when the file is
     * too short, not a canonical segment, or disagrees with the manifest;
     * the file is untouched then.
     */
    private static SegmentFile.Description describeStreaming(Path part, TakeManifest manifest, TrackEntry track)
            throws IOException {
        long length = Files.size(part);
        if (length < SegmentFile.HEADER_BYTES) {
            throw new IOException(part + " is " + length + " bytes, shorter than the "
                    + SegmentFile.HEADER_BYTES + "-byte canonical segment header");
        }
        SegmentFile.Description description = SegmentFile.describe(part);
        requireHeaderAgreement(part, description, manifest, track);
        return description;
    }

    /**
     * The seal of a {@code .part} with frames that no writer will finish:
     * truncate to the whole frames {@code description} counted, patch the
     * sizes as {@link SegmentWriter#seal()} does, force, rename. The rename
     * is an {@code ATOMIC_MOVE}, which would replace an existing target: the
     * never-overwrite guarantee is {@link #sealEntry}'s pre-check that the
     * sealed sibling does not exist, as {@link SegmentWriter#seal()}
     * documents for its own rename. A move that fails leaves a
     * header-consistent {@code .part} the next scan seals again.
     */
    private static long sealPart(Path part, Path wav, SegmentFile.Description description) throws IOException {
        long frames = description.frameCount();
        long dataBytes = frames * description.bytesPerFrame();
        if (dataBytes > SegmentWriter.MAX_DATA_BYTES) {
            throw new IOException(part + " holds " + dataBytes + " data bytes, more than the "
                    + SegmentWriter.MAX_DATA_BYTES + " a segment may hold");
        }
        try (FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE)) {
            // Whole frames only: the torn trailing frame of a crash mid-write
            // goes, never a whole one.
            channel.truncate(SegmentFile.HEADER_BYTES + dataBytes);
            SegmentWriter.patchSizeFields(channel, dataBytes);
            channel.force(true);
        }
        Files.move(part, wav, StandardCopyOption.ATOMIC_MOVE);
        return frames;
    }

    /**
     * What a {@code .part} the manifest does not list is (class note). The
     * track is the one its directory names; when the manifest lists it, the
     * header must agree with the manifest's bit depth and sample rate and
     * with that track's channel count — {@link #requireHeaderAgreement}, the
     * listed-entry check, in that order — and the whole-frame count the
     * header's own frame size derives from the length decides between
     * {@link ZeroFrame} and {@link WithFrames}. A file shorter than the
     * header, one whose header does not parse or disagrees, and one under a
     * directory naming no listed track are {@link NotProvablyZeroFrame}
     * with the reason: only a {@link ZeroFrame} is ever deleted.
     */
    private static UnlistedPart inspectUnlisted(Path part, TakeManifest manifest) {
        String trackId = String.valueOf(part.getParent().getFileName());
        Optional<TrackEntry> track = listedTrack(manifest, trackId);
        if (track.isEmpty()) {
            return new NotProvablyZeroFrame("its directory " + trackId + " names no track the manifest lists");
        }
        try {
            SegmentFile.Description header = SegmentFile.describe(part);
            requireHeaderAgreement(part, header, manifest, track.get());
            return header.frameCount() == 0 ? new ZeroFrame() : new WithFrames(header.frameCount());
        } catch (IOException e) {
            return new NotProvablyZeroFrame(reasonOf(e));
        }
    }

    /** The manifest's entry for {@code trackId}, when it lists the track. */
    private static Optional<TrackEntry> listedTrack(TakeManifest manifest, String trackId) {
        return manifest.tracks().stream().filter(t -> t.trackId().equals(trackId)).findFirst();
    }

    /**
     * Deletes a {@code .part} that holds no whole frame, as the live seal
     * deletes an empty tail, once the manifest on disk lists it no more.
     * Returns the report when the delete fails: the file stays, the manifest
     * stands, and the next scan — which finds an unlisted zero-frame
     * {@code .part} — deletes it.
     */
    private static Optional<Unrecoverable> deleteZeroFramePart(Path part) {
        try {
            Files.deleteIfExists(part);
            return Optional.empty();
        } catch (IOException e) {
            return Optional.of(new Unrecoverable(part, "holds no frame and the manifest on disk does not list it,"
                    + " but it could not be deleted: " + reasonOf(e) + "; the next scan deletes it"));
        }
    }

    /**
     * {@link #deleteZeroFramePart} for a {@code .part} no manifest entry
     * described, logging the deletion at {@link Level#WARNING}: the file is
     * the trace of a delete that failed — the live seal's, or an earlier
     * scan's — or of a segment opened ahead of the manifest write that was
     * to list it, the JVM dead before a frame reached it; the log is where
     * that trace ends.
     */
    private static Optional<Unrecoverable> deleteUnlistedZeroFramePart(Path orphan, Path takeDirectory) {
        Optional<Unrecoverable> left = deleteZeroFramePart(orphan);
        if (left.isEmpty()) {
            LOG.log(Level.WARNING, "take recovery deleted " + orphan + ": " + takeDirectory
                    + " does not list it and it holds no frame");
        }
        return left;
    }

    private static void requireHeaderAgreement(Path file, SegmentFile.Description header,
                                               TakeManifest manifest, TrackEntry track) throws IOException {
        requireFormatAgreement(file, header, manifest);
        if (header.channels() != track.channels()) {
            throw new IOException(file + " declares " + header.channels() + " channel(s) but the manifest lists "
                    + track.channels() + " for track " + track.trackId());
        }
    }

    /** The take-wide half of the header check — bit depth and sample rate; the channel count is the track's. */
    private static void requireFormatAgreement(Path file, SegmentFile.Description header, TakeManifest manifest)
            throws IOException {
        if (header.bitDepth() != manifest.bitDepth()) {
            throw new IOException(file + " declares " + header.bitDepth() + " bits per sample but the manifest says "
                    + manifest.bitDepth());
        }
        int manifestRate = SegmentWriter.headerSampleRate(manifest.sampleRate());
        if ((int) header.sampleRate() != manifestRate) {
            throw new IOException(file + " declares a sample rate of " + (int) header.sampleRate()
                    + " Hz but the manifest says " + manifestRate + " Hz");
        }
    }

    /**
     * The reason an exception gives for a report or a log line: its message,
     * or its simple class name when it has none — {@code ClosedChannelException}
     * and {@code ClosedByInterruptException} carry no message, and
     * {@link Unrecoverable} refuses a blank one.
     */
    static String reasonOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * Fault seam (test-only): {@code fault} runs at the start of every
     * {@link #recover(Path)} with the take directory, before the manifest is
     * read, and may throw to stand in for a failure inside that take's
     * recovery. {@code null} clears it. May be set from any thread; a test
     * that sets it must clear it when it is done.
     */
    static void setRecoverFaultForTest(Consumer<Path> fault) {
        recoverFaultForTest = fault;
    }

    /** The {@code .part} files one level down ({@code <take>/<track>/…}), regular files only, in name order. */
    private static List<Path> streamingFilesUnder(Path takeDirectory) throws IOException {
        List<Path> parts = new ArrayList<>();
        if (!Files.isDirectory(takeDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return parts;
        }
        for (Path trackDirectory : subdirectories(takeDirectory)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(trackDirectory)) {
                for (Path file : files) {
                    if (SegmentFile.isStreamingName(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        parts.add(file);
                    }
                }
            }
        }
        parts.sort(Comparator.comparing(Path::toString));
        return parts;
    }

    /** The subdirectories of {@code directory} (no links followed), in name order. */
    private static List<Path> subdirectories(Path directory) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    result.add(entry);
                }
            }
        }
        result.sort(Comparator.comparing(p -> String.valueOf(p.getFileName())));
        return result;
    }
}
