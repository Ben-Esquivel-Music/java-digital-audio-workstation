package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.export.PcmSampleEncoding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Streams one recording segment to disk as a self-describing WAV file and
 * seals it by patch-header + atomic rename (Recording Reliability book
 * §3.4, §4.4, §5.3; story 323).
 *
 * <p><strong>Thread.</strong> {@link #append}, {@link #forceIfDue()} and
 * {@link #seal()} run on the {@code capture-flush} thread. {@link #open}
 * runs on the caller thread for a track's first segment (before the flush
 * thread is started) and on the flush thread for every later one. The
 * rollback / abandon seams
 * ({@link #abandon()}, {@link #close()}) run on the caller thread, before
 * the flush thread is started or once it has terminated
 * ({@code CaptureFlushService.isTerminated()}), and on the flush
 * thread when a seal fails or an empty tail is discarded; the fault seams
 * ({@link #failNextAppend()}, {@link #failNextRename()}) may be set from any
 * thread. One thread at a time writes the file (one writer per byte, book
 * §2.3). The writer is not thread-safe; its counters are plain fields whose
 * cross-thread visibility is the flush service's responsibility.</p>
 *
 * <p><strong>Segment file grammar (context D7).</strong></p>
 * <ul>
 *   <li>While streaming the file is {@code segment-NNN.wav.part}
 *       ({@link #PART_SUFFIX}); the sealed identity is the same name without
 *       the suffix.</li>
 *   <li>The header is the 44-byte canonical layout — {@code RIFF} /
 *       {@code WAVE} / {@code fmt } (16 bytes) / {@code data} — so the
 *       samples always start at {@link #DATA_OFFSET}. Format code 1 (PCM) for
 *       16 and 24 bit, 3 (IEEE float) for 32 bit — the same rule
 *       {@code WavExporter} applies with {@code DitherType.NONE}, encoded by
 *       the shared {@link PcmSampleEncoding} so the data chunk is
 *       bit-identical to a {@code DitherType.NONE} export of the same frames
 *       at the same bit depth.</li>
 *   <li>The RIFF-size field (offset 4) and data-size field (offset 40) hold
 *       {@link #PROVISIONAL_SIZE} while streaming. A reader that finds the
 *       sentinel derives the sample count from the file length:
 *       {@code (length − 44) / (channels × bytesPerSample)}
 *       ({@link SegmentFile}).</li>
 *   <li>Appends go straight through a {@link FileChannel} — no Java-side
 *       buffering that could hide unforced bytes from the cadence
 *       accounting.</li>
 *   <li>Force cadence: the channel is {@code force(false)}d when bytes have
 *       been appended since the last cadence force and {@code now −
 *       lastForce ≥ cadence} ({@code lastForce} is the open time until the
 *       first force). The check runs after every append and in
 *       {@link #forceIfDue()}, which the {@code capture-flush} thread calls,
 *       while the take streams, after every block it applies and at the end
 *       of every drain pass — so a segment that nothing more is appended to
 *       (a punch-out, an absent instrument source, a dry ring) is forced on
 *       cadence as well. The clock is the injected {@code nanoClock} so
 *       tests pin the cadence exactly. While the take streams, a byte
 *       therefore stays un-forced for at most the cadence plus the wait for
 *       the flush thread's next check (book §2.1): while the ring is dry
 *       that thread runs a pass after every park backstop
 *       ({@code CaptureFlushService.PARK_BACKSTOP}, 50 ms); while it drains,
 *       it checks after every block. Nothing is forced when nothing is
 *       un-forced, and the seal's two {@code force(true)} calls are not
 *       cadence forces.</li>
 *   <li>{@link #seal()} = write nothing more → {@code force(true)} → patch
 *       both size fields with exact values → {@code force(true)} → close →
 *       {@code Files.move(part, wav, ATOMIC_MOVE)}. The rename is the commit
 *       point: a reader never observes a {@code .wav} with provisional sizes.
 *       An existing {@code .wav} is never overwritten — sealing onto one
 *       throws and leaves both files. That guarantee rests on the
 *       existence check {@code seal()} (and {@code open}) makes first, not
 *       on the move: {@code ATOMIC_MOVE} replaces an existing target on
 *       POSIX file systems. The check covers what the design itself can
 *       produce (a segment has exactly one writer, and {@code open} refuses
 *       a name whose {@code .wav} already exists); it is not a guard
 *       against another process creating the file between the check and
 *       the move.</li>
 *   <li>{@link #abandon()} closes the channel with no patch and no rename;
 *       the {@code .part} stays recoverable by the grammar above.</li>
 * </ul>
 *
 * <p>Bit depths accepted: 16, 24, 32. {@code AudioFormat} permits any
 * positive bit depth, but 8-bit capture is not a supported take format;
 * anything else is rejected at {@link #open} so a session fails at start,
 * not mid-take.</p>
 *
 * <p>Distinct from {@code WavExporter} on purpose: export is one-shot with a
 * known length; capture is append-with-unknown-length and has partial-file
 * states (book §4.4's rejected alternative).</p>
 */
public final class SegmentWriter implements AutoCloseable {

    /** Byte offset of the first sample in every segment file — the canonical 44-byte header. */
    public static final int DATA_OFFSET = 44;

    /** Value held by the RIFF-size and data-size fields while a segment is streaming. */
    public static final int PROVISIONAL_SIZE = 0xFFFFFFFF;

    /** File-name suffix of a streaming segment. */
    public static final String PART_SUFFIX = ".part";

    /** Default force-to-storage cadence (book §4.3). */
    public static final Duration DEFAULT_FORCE_CADENCE = Duration.ofSeconds(5);

    /**
     * Largest data chunk a segment may hold: {@code Integer.MAX_VALUE − 44},
     * so the whole file — header plus data — is at most
     * {@code Integer.MAX_VALUE} bytes long. Both size fields of a sealed
     * segment are then non-negative signed 32-bit values (the house
     * {@code WavFileReader} reads the data size as a signed {@code int} and
     * the file into one array), and neither can equal the
     * {@link #PROVISIONAL_SIZE} sentinel. A session's byte cap may not
     * exceed this value and the session rotates before an append would
     * cross its cap; the writer itself refuses to exceed the limit rather
     * than corrupt the header.
     */
    public static final long MAX_DATA_BYTES = Integer.MAX_VALUE - (long) DATA_OFFSET;

    static final int RIFF_SIZE_OFFSET = 4;
    static final int DATA_SIZE_OFFSET = 40;
    private static final int RIFF_HEADER_OVERHEAD = 36;
    private static final int CHUNK_FRAMES = 8192;

    private enum State { STREAMING, SEALED, ABANDONED }

    /**
     * Opens the channel a segment (or another capture file) is written
     * through. Production uses {@link #CREATE_NEW_CHANNEL}; tests pass an
     * opener that wraps the real channel to count {@code force} calls or to
     * fail a write. Called on the thread that opens the file.
     */
    @FunctionalInterface
    interface ChannelOpener {
        FileChannel open(Path path) throws IOException;
    }

    /** The production opener: a new file, write-only; an existing file is refused. */
    static final ChannelOpener CREATE_NEW_CHANNEL = path -> FileChannel.open(path,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    private final Path partPath;
    private final Path sealedPath;
    private final double sampleRate;
    private final int channels;
    private final int bitDepth;
    private final int bytesPerFrame;
    private final boolean ieeeFloat;
    private final long cadenceNanos;
    private final Duration forceCadence;
    private final LongSupplier nanoClock;
    private final FileChannel channel;
    private final ByteBuffer encodeBuffer;

    private State state = State.STREAMING;
    private long frameCount;
    private long dataBytes;
    private long bytesSinceForce;
    private long forceCount;
    private long lastForceNanos;
    /** Test seam ({@link #failNextAppend()}); set from a test thread, consumed on the flush thread. */
    private volatile boolean failNextAppend;
    /** Test seam ({@link #failNextRename()}); set from a test thread, consumed by the sealing thread. */
    private volatile boolean failNextRename;

    private SegmentWriter(Path partPath, Path sealedPath, double sampleRate, int channels,
                          int bitDepth, Duration forceCadence, LongSupplier nanoClock,
                          FileChannel channel) {
        this.partPath = partPath;
        this.sealedPath = sealedPath;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitDepth = bitDepth;
        this.bytesPerFrame = channels * (bitDepth / 8);
        this.ieeeFloat = bitDepth == 32;
        this.forceCadence = forceCadence;
        this.cadenceNanos = forceCadence.toNanos();
        this.nanoClock = nanoClock;
        this.channel = channel;
        this.encodeBuffer = ByteBuffer.allocateDirect(CHUNK_FRAMES * bytesPerFrame)
                .order(ByteOrder.LITTLE_ENDIAN);
        this.lastForceNanos = nanoClock.getAsLong();
    }

    /**
     * Creates the streaming file and writes its provisional header. If the
     * header cannot be written the file is closed and deleted again, so a
     * failed open leaves nothing behind.
     *
     * @param partPath     the {@code segment-NNN.wav.part} path; its name must
     *                     end with {@link #PART_SUFFIX}; parent directories are
     *                     created as needed
     * @param sampleRate   frames per second; positive (rounded to the nearest
     *                     integer in the header)
     * @param channels     channel count; positive
     * @param bitDepth     16, 24 or 32
     * @param forceCadence force-to-storage cadence; non-negative (zero =
     *                     force after every append of one or more frames)
     * @param nanoClock    monotonic nanosecond clock; {@code System::nanoTime}
     *                     in production
     * @return the open writer, positioned at frame 0
     * @throws FileAlreadyExistsException if the {@code .part} or its sealed
     *                                    {@code .wav} already exists
     * @throws IOException                on any other I/O failure
     * @throws IllegalArgumentException   for an unsupported bit depth, a
     *                                    non-{@code .part} name, or a
     *                                    non-positive rate/channel count
     */
    public static SegmentWriter open(Path partPath, double sampleRate, int channels, int bitDepth,
                                     Duration forceCadence, LongSupplier nanoClock) throws IOException {
        return open(partPath, sampleRate, channels, bitDepth, forceCadence, nanoClock, CREATE_NEW_CHANNEL);
    }

    /**
     * {@link #open(Path, double, int, int, Duration, LongSupplier)} with the
     * channel supplied by {@code opener} (test seam: a delegating channel
     * makes the {@code force} calls and a failing header write observable).
     * The opener must create the file and refuse an existing one, as
     * {@link #CREATE_NEW_CHANNEL} does.
     */
    static SegmentWriter open(Path partPath, double sampleRate, int channels, int bitDepth,
                              Duration forceCadence, LongSupplier nanoClock,
                              ChannelOpener opener) throws IOException {
        Objects.requireNonNull(partPath, "partPath must not be null");
        Objects.requireNonNull(forceCadence, "forceCadence must not be null");
        Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        Objects.requireNonNull(opener, "opener must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            throw new IllegalArgumentException(
                    "bitDepth must be 16, 24 or 32 for a recording segment: " + bitDepth);
        }
        if (forceCadence.isNegative()) {
            throw new IllegalArgumentException("forceCadence must not be negative: " + forceCadence);
        }
        Path sealed = sealedPathFor(partPath);
        if (Files.exists(sealed)) {
            throw new FileAlreadyExistsException(sealed.toString(), null,
                    "sealed segment already exists; a segment is never overwritten");
        }
        Path parent = partPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel fc = opener.open(partPath);
        try {
            writeFully(fc, provisionalHeader(sampleRate, channels, bitDepth), 0);
        } catch (IOException | RuntimeException e) {
            // The file is ours (the opener refused an existing one) and holds
            // at most a partial header: a failed open leaves nothing behind.
            try {
                fc.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            try {
                Files.deleteIfExists(partPath);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return new SegmentWriter(partPath, sealed, sampleRate, channels, bitDepth,
                forceCadence, nanoClock, fc);
    }

    /**
     * Returns the sealed {@code .wav} identity of a {@code .part} path.
     *
     * @throws IllegalArgumentException if the name does not end with {@link #PART_SUFFIX}
     */
    public static Path sealedPathFor(Path partPath) {
        Objects.requireNonNull(partPath, "partPath must not be null");
        String name = partPath.getFileName() == null ? "" : partPath.getFileName().toString();
        if (!name.endsWith(PART_SUFFIX) || name.length() == PART_SUFFIX.length()) {
            throw new IllegalArgumentException(
                    "streaming segment name must end with '" + PART_SUFFIX + "': " + partPath);
        }
        return partPath.resolveSibling(name.substring(0, name.length() - PART_SUFFIX.length()));
    }

    /** Returns the streaming {@code .part} path of a sealed segment identity. */
    public static Path partPathFor(Path sealedPath) {
        Objects.requireNonNull(sealedPath, "sealedPath must not be null");
        return sealedPath.resolveSibling(sealedPath.getFileName() + PART_SUFFIX);
    }

    /**
     * Appends {@code numFrames} frames of the first {@code channels()} rows of
     * {@code frames} and runs the force-cadence check.
     *
     * @param frames    {@code [channel][frame]} samples in {@code [-1, 1]}
     * @param channels  rows of {@code frames} that are valid; must be at
     *                  least {@link #channels()} (extra rows are ignored —
     *                  the writer never fabricates silence for missing ones)
     * @param numFrames frames to append; non-negative
     * @throws IOException           on a write failure, on a failed cadence
     *                               force, or if the segment would exceed
     *                               {@link #MAX_DATA_BYTES}; see
     *                               {@link #append(float[][], int, int, int)}
     *                               for what is counted then
     * @throws IllegalStateException if the writer is sealed or abandoned
     */
    public void append(float[][] frames, int channels, int numFrames) throws IOException {
        append(frames, channels, 0, numFrames);
    }

    /**
     * Appends {@code numFrames} frames read from {@code frames[ch][frameOffset
     * ..frameOffset + numFrames)} — the same contract as
     * {@link #append(float[][], int, int)} with a starting frame, so a caller
     * holding a larger buffer (the session's RAM mirror) can append its newest
     * region without copying it out first.
     *
     * <p>{@link #frameCount()} and {@link #dataBytes()} advance chunk by
     * chunk (a block is written in chunks of at most 8192 frames), each
     * chunk once its bytes are written, whether or not the append then
     * throws: a cadence force that fails leaves the whole block written and
     * counted; a write that fails leaves counted only the chunks written in
     * full before it; a refusal before writing (the segment limit, the
     * {@link #failNextAppend()} seam) counts nothing.</p>
     *
     * @param frames      {@code [channel][frame]} samples in {@code [-1, 1]}
     * @param channels    rows of {@code frames} that are valid; at least {@link #channels()}
     * @param frameOffset first frame to append; non-negative
     * @param numFrames   frames to append; non-negative
     * @throws IOException           on a write failure, on a failed cadence
     *                               force, or if the segment would exceed
     *                               {@link #MAX_DATA_BYTES}
     * @throws IllegalStateException if the writer is sealed or abandoned
     */
    public void append(float[][] frames, int channels, int frameOffset, int numFrames) throws IOException {
        Objects.requireNonNull(frames, "frames must not be null");
        requireStreaming("append");
        if (channels < this.channels) {
            throw new IllegalArgumentException("block has " + channels
                    + " channel(s) but the segment needs " + this.channels);
        }
        if (channels > frames.length) {
            throw new IllegalArgumentException("channels (" + channels
                    + ") exceeds the block's rows (" + frames.length + ")");
        }
        if (frameOffset < 0) {
            throw new IllegalArgumentException("frameOffset must not be negative: " + frameOffset);
        }
        if (numFrames < 0) {
            throw new IllegalArgumentException("numFrames must not be negative: " + numFrames);
        }
        for (int ch = 0; ch < this.channels; ch++) {
            if (frames[ch] == null || frames[ch].length < frameOffset + numFrames) {
                throw new IllegalArgumentException("channel " + ch + " holds fewer than "
                        + (frameOffset + numFrames) + " frames");
            }
        }
        long incoming = (long) numFrames * bytesPerFrame;
        if (dataBytes + incoming > MAX_DATA_BYTES) {
            throw new IOException("segment " + partPath + " would exceed the segment data limit ("
                    + MAX_DATA_BYTES + " bytes); rotate before appending");
        }
        if (failNextAppend) {
            failNextAppend = false;
            throw new IOException("injected append failure (test seam) on " + partPath);
        }

        int written = 0;
        while (written < numFrames) {
            int count = Math.min(CHUNK_FRAMES, numFrames - written);
            int bytes = PcmSampleEncoding.encodeBlock(frames, frameOffset + written, count, this.channels,
                    bitDepth, ieeeFloat, encodeBuffer, 0);
            encodeBuffer.limit(bytes).position(0);
            writeFully(channel, encodeBuffer, DATA_OFFSET + dataBytes);
            encodeBuffer.clear();
            dataBytes += bytes;
            bytesSinceForce += bytes;
            frameCount += count;
            written += count;
        }
        forceIfCadenceElapsed();
    }

    /**
     * The force-cadence check on its own, with no append (book §2.1, §4.3):
     * if bytes have been appended since the last cadence force and the
     * cadence has elapsed on the injected clock since that force (or since
     * the open, before the first one), the channel is {@code force(false)}d.
     * {@link #append} makes the same check after every append; the
     * {@code capture-flush} thread also makes it between appends, so bytes
     * that nothing more is appended after are forced on cadence too. With
     * nothing un-forced it neither forces nor reads the clock.
     *
     * @return whether the channel was forced
     * @throws IOException           if the force fails; the writer stays
     *                               streaming with its counters untouched
     * @throws IllegalStateException if the writer is sealed or abandoned
     */
    public boolean forceIfDue() throws IOException {
        requireStreaming("forceIfDue");
        return forceIfCadenceElapsed();
    }

    private boolean forceIfCadenceElapsed() throws IOException {
        if (bytesSinceForce == 0) {
            return false;
        }
        long now = nanoClock.getAsLong();
        if (now - lastForceNanos < cadenceNanos) {
            return false;
        }
        channel.force(false);
        lastForceNanos = now;
        bytesSinceForce = 0;
        forceCount++;
        return true;
    }

    /**
     * Seals the segment: forces the data, patches the exact RIFF/data sizes,
     * forces again, closes the channel and atomically renames
     * {@code .part} → {@code .wav}.
     *
     * <p>If the sealed {@code .wav} already exists this throws
     * {@link FileAlreadyExistsException} <em>before</em> touching the file,
     * leaving the writer streaming and both files in place — the guarantee
     * rests on that pre-check (see the class note). If the rename itself
     * fails after the channel is closed the writer becomes abandoned: the
     * {@code .part} remains, with exact sizes patched in, still readable by
     * the streaming grammar.</p>
     *
     * @return the sealed {@code .wav} path
     * @throws FileAlreadyExistsException if the sealed path already exists
     * @throws IOException                on any I/O failure
     * @throws IllegalStateException      if already sealed or abandoned
     */
    public Path seal() throws IOException {
        requireStreaming("seal");
        if (Files.exists(sealedPath)) {
            throw new FileAlreadyExistsException(sealedPath.toString(), null,
                    "sealed segment already exists; a segment is never overwritten");
        }
        channel.force(true);
        ByteBuffer patch = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        patch.putInt(0, (int) (RIFF_HEADER_OVERHEAD + dataBytes));
        writeFully(channel, patch, RIFF_SIZE_OFFSET);
        patch.clear();
        patch.putInt(0, (int) dataBytes);
        writeFully(channel, patch, DATA_SIZE_OFFSET);
        channel.force(true);
        channel.close();
        try {
            if (failNextRename) {
                failNextRename = false;
                throw new IOException("injected rename failure (test seam) on " + partPath);
            }
            Files.move(partPath, sealedPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            state = State.ABANDONED;
            throw e;
        }
        state = State.SEALED;
        bytesSinceForce = 0;
        return sealedPath;
    }

    /**
     * Closes the channel with no size patch and no rename: the {@code .part}
     * keeps every byte written to it, and its size fields keep what they
     * hold — the streaming sentinel, unless a {@link #seal()} that failed
     * had already patched them. Called by {@link #close()}, and by the
     * session for the start-failure rollback, after a seal that throws, to
     * discard an empty tail, and for the test seams that abandon a take
     * without a seal. No-op unless streaming.
     *
     * @throws IOException if closing the channel fails
     */
    void abandon() throws IOException {
        if (state != State.STREAMING) {
            return;
        }
        state = State.ABANDONED;
        channel.close();
    }

    /**
     * Fault seam (test-only): the next {@link #append} throws an
     * {@link IOException} <em>before</em> writing anything, leaving the
     * writer streaming with its counters untouched — the shape of a disk
     * write that failed outright. Consumed by that one append. May be set
     * from any thread.
     */
    void failNextAppend() {
        failNextAppend = true;
    }

    /**
     * Fault seam (test-only): the next {@link #seal()} fails at its rename —
     * after the data was forced, both size fields were patched and forced
     * and the channel was closed, right where {@code Files.move} would run.
     * The writer ends abandoned with the {@code .part} in place. Consumed by
     * that one seal. May be set from any thread.
     */
    void failNextRename() {
        failNextRename = true;
    }

    /**
     * Closes an unsealed writer <em>without</em> sealing (equivalent to
     * {@link #abandon()}); a no-op once sealed. Sealing is an explicit act —
     * the rename is the commit point and must never happen as a side effect
     * of try-with-resources cleanup.
     */
    @Override
    public void close() throws IOException {
        abandon();
    }

    /** Returns the exact number of frames appended so far. */
    public long frameCount() {
        return frameCount;
    }

    /** Returns the exact data-chunk size in bytes so far. */
    public long dataBytes() {
        return dataBytes;
    }

    /** Returns the bytes appended since the last cadence force (0 right after a force or the seal). */
    public long bytesSinceForce() {
        return bytesSinceForce;
    }

    /**
     * Returns how many cadence-driven {@code force(false)} calls have run.
     * The seal's two {@code force(true)} calls are not counted.
     */
    public long forceCount() {
        return forceCount;
    }

    /** Returns the clock reading of the last cadence force (the open time until the first force). */
    public long lastForceNanos() {
        return lastForceNanos;
    }

    /** Returns the streaming {@code .part} path. */
    public Path partPath() {
        return partPath;
    }

    /** Returns the sealed {@code .wav} identity (whether or not the seal has happened yet). */
    public Path sealedPath() {
        return sealedPath;
    }

    /** Returns {@code true} once {@link #seal()} has completed. */
    public boolean isSealed() {
        return state == State.SEALED;
    }

    /** Returns {@code true} while the writer accepts appends. */
    public boolean isStreaming() {
        return state == State.STREAMING;
    }

    /** Returns the sample rate the header describes. */
    public double sampleRate() {
        return sampleRate;
    }

    /** Returns the channel count the header describes. */
    public int channels() {
        return channels;
    }

    /** Returns the bit depth the header describes. */
    public int bitDepth() {
        return bitDepth;
    }

    /** Returns the bytes per interleaved frame. */
    public int bytesPerFrame() {
        return bytesPerFrame;
    }

    /** Returns the configured force cadence. */
    public Duration forceCadence() {
        return forceCadence;
    }

    private void requireStreaming(String operation) {
        if (state != State.STREAMING) {
            throw new IllegalStateException(operation + "() on a " + state.name().toLowerCase()
                    + " segment writer: " + partPath);
        }
    }

    private static ByteBuffer provisionalHeader(double sampleRate, int channels, int bitDepth) {
        int bytesPerSample = bitDepth / 8;
        int rate = (int) Math.round(sampleRate);
        short formatCode = bitDepth == 32
                ? PcmSampleEncoding.FORMAT_IEEE_FLOAT : PcmSampleEncoding.FORMAT_PCM;
        ByteBuffer header = ByteBuffer.allocate(DATA_OFFSET).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(PROVISIONAL_SIZE);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort(formatCode);
        header.putShort((short) channels);
        header.putInt(rate);
        header.putInt(rate * channels * bytesPerSample);
        header.putShort((short) (channels * bytesPerSample));
        header.putShort((short) bitDepth);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(PROVISIONAL_SIZE);
        header.flip();
        return header;
    }

    private static void writeFully(FileChannel fc, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            pos += fc.write(buffer, pos);
        }
    }

    @Override
    public String toString() {
        return "SegmentWriter[" + partPath + ", " + state + ", frames=" + frameCount
                + ", bytes=" + dataBytes + "]";
    }
}
