package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The per-track streaming capture of one take lane (Recording Reliability
 * book §4.4, §5.3, Appendix A; story 323).
 *
 * <p>Every frame handed to {@link #recordAudioData(float[][], int)} goes to
 * two places: the RAM mirror that feeds today's post-stop playback attach
 * (retained this stage; story 324 replaces it with a bounded peak mirror)
 * and a {@link SegmentWriter} streaming a real WAV segment under the
 * session's output directory — {@code <take>/<trackId>/segment-NNN.wav.part}
 * while streaming, {@code segment-NNN.wav} once sealed. Long sessions are
 * split into segments by exact counts (context D4): a segment rotates —
 * seal, then open {@code segment-(n+1)} — after the append that takes its
 * frame count to {@link #getMaxSegmentDuration()} worth of audio <em>or</em>
 * its data chunk to {@link #getMaxSegmentBytes()}, and <em>before</em> an
 * append that would take a segment already holding audio past the byte cap.
 * A sealed segment therefore never exceeds the byte cap, except when one
 * block alone is larger than the cap. No wall-clock read decides rotation;
 * the {@link RecordingSegment#startTime()}/{@code endTime()} instants are
 * metadata only.</p>
 *
 * <p><strong>Threads.</strong> A track's first-lane session is constructed
 * on the caller thread (in {@code RecordingPipeline.prepare()}), a later
 * loop lane's on the flush thread. {@link #start()} runs on the
 * {@code capture-flush} thread — inside the take's initialisation for a
 * track's first lane, and when a loop lap opens a later lane — as do
 * {@link #recordAudioData}, the cadence tick
 * ({@code forceIfCadenceElapsed()}) and {@link #stop()}: never on the audio
 * callback; {@link #pause()} and {@link #resume()} have no caller in the
 * pipeline this stage and run on whichever thread drives the session.
 * {@link RecordingListener} callbacks therefore fire on the flush thread. The
 * counters other threads read ({@link #getTotalSamplesRecorded()},
 * {@link #getCapturedSampleCount()}, {@link #isActive()}) are atomic or
 * volatile; a reader that first passes the flush service's
 * {@code awaitFlushed} fence sees every block published before its call.</p>
 *
 * <p><strong>Files and metadata agree.</strong> {@link #getSegments()} never
 * lists a segment without a file on disk (book §9.3): a segment is added when
 * its {@code .part} is created, flipped to sealed with exact counts when its
 * {@code .wav} lands, and a zero-frame tail at {@link #stop()} is deleted and
 * removed rather than sealed — a segment with no frames is a file nobody can
 * play and a reference nobody needs. A write failure surfaces as
 * {@link UncheckedIOException} from {@code recordAudioData}/{@code stop};
 * the flush service turns that into a clean early seal.</p>
 *
 * <p>Capture width this stage is the stream width ({@link AudioFormat#channels()}):
 * the mirror and the segment both carry that many channels, rows the routed
 * block does not provide staying silent — exactly today's routing shape
 * (story 326 narrows capture to the routed width). Bit depths 16, 24 and 32
 * are accepted; any other {@code AudioFormat#bitDepth()} fails at
 * {@link #start()} with {@link IllegalArgumentException} (8-bit capture is
 * not a supported take format).</p>
 */
public final class RecordingSession {

    /** Default maximum segment duration: 30 minutes. */
    public static final Duration DEFAULT_MAX_SEGMENT_DURATION = Duration.ofMinutes(30);

    /** Default maximum segment size: 500 MB. */
    public static final long DEFAULT_MAX_SEGMENT_BYTES = 500L * 1024 * 1024;

    private static final Logger LOG = Logger.getLogger(RecordingSession.class.getName());
    private static final String SEGMENT_NAME_FORMAT = "segment-%03d.wav";

    /**
     * Segment lifecycle observer for the flush service's manifest
     * bookkeeping. Fires on whichever thread drives the session (see the
     * class note): opened when a {@code .part} is created, sealed when its
     * {@code .wav} lands with exact counts, discarded when a zero-frame tail
     * is deleted instead of sealed.
     */
    interface SegmentObserver {
        void onSegmentOpened(RecordingSegment segment);

        void onSegmentSealed(RecordingSegment segment);

        void onSegmentDiscarded(RecordingSegment segment);
    }

    private final AudioFormat format;
    private final Path outputDirectory;
    private final Duration maxSegmentDuration;
    private final long maxSegmentBytes;
    private final Duration forceCadence;
    private final LongSupplier nanoClock;
    private final double maxSegmentSeconds;
    private final List<RecordingSegment> segments = new CopyOnWriteArrayList<>();
    private final List<RecordingListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong totalSamplesRecorded = new AtomicLong(0);

    private volatile boolean active;
    private volatile boolean paused;
    private volatile Instant sessionStartTime;
    private RecordingSegment currentSegment;
    private SegmentWriter writer;
    private SegmentObserver segmentObserver;
    private SegmentWriter.ChannelOpener channelOpener = SegmentWriter.CREATE_NEW_CHANNEL;
    private int firstSegmentIndex;
    private int nextSegmentIndex;
    private boolean createdOutputDirectory;

    /**
     * Driver round-trip latency in sample frames the recording pipeline
     * applies to compensate for the input + output buffer pipelines —
     * the click leaves the DAW, travels through the output buffer to the
     * speakers, the singer hears it and sings, the mic captures it, the
     * signal travels back through the input buffer to the DAW, and the
     * DAW writes it at the *current* sample position which is later than
     * the bar that prompted the singer. The pipeline shifts each captured
     * buffer's start position by {@code -compensationFrames} so the take
     * aligns with the cue the user heard. {@code 0} means "no compensation
     * applied" (driver reports zero, or the user toggled compensation off,
     * or compensation was never configured for this session).
     */
    private volatile long compensationFrames;

    // RAM mirror: [channel][sample]; grown on the flush thread, read after a fence.
    private float[][] capturedAudio;
    private volatile int capturedSampleCount;

    /**
     * Creates a new recording session with the default force cadence and
     * the system clock.
     *
     * @param format             the audio format
     * @param outputDirectory    the directory for segment files
     * @param maxSegmentDuration the maximum duration per segment; positive
     * @param maxSegmentBytes    the maximum data-chunk size per segment in bytes;
     *                           positive and at most {@link SegmentWriter#MAX_DATA_BYTES}
     */
    public RecordingSession(AudioFormat format, Path outputDirectory,
                            Duration maxSegmentDuration, long maxSegmentBytes) {
        this(format, outputDirectory, maxSegmentDuration, maxSegmentBytes,
                SegmentWriter.DEFAULT_FORCE_CADENCE, System::nanoTime);
    }

    /**
     * Creates a session with default segment limits.
     *
     * @param format          the audio format
     * @param outputDirectory the directory for segment files
     */
    public RecordingSession(AudioFormat format, Path outputDirectory) {
        this(format, outputDirectory, DEFAULT_MAX_SEGMENT_DURATION, DEFAULT_MAX_SEGMENT_BYTES);
    }

    /**
     * Creates a session with explicit segment limits, force cadence and clock.
     *
     * @param format             the audio format (bit depth 16, 24 or 32)
     * @param outputDirectory    the directory for segment files (created at {@link #start()})
     * @param maxSegmentDuration the maximum duration per segment; positive
     * @param maxSegmentBytes    the maximum data-chunk size per segment; positive
     *                           and at most {@link SegmentWriter#MAX_DATA_BYTES}
     * @param forceCadence       the writer's force-to-storage cadence; non-negative
     * @param nanoClock          monotonic clock the writer's cadence reads;
     *                           {@code System::nanoTime} in production
     */
    public RecordingSession(AudioFormat format, Path outputDirectory,
                            Duration maxSegmentDuration, long maxSegmentBytes,
                            Duration forceCadence, LongSupplier nanoClock) {
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        this.maxSegmentDuration = Objects.requireNonNull(maxSegmentDuration,
                "maxSegmentDuration must not be null");
        if (maxSegmentDuration.isZero() || maxSegmentDuration.isNegative()) {
            throw new IllegalArgumentException("maxSegmentDuration must be positive: " + maxSegmentDuration);
        }
        this.maxSegmentBytes = requireSegmentByteCap(maxSegmentBytes);
        this.maxSegmentSeconds = maxSegmentDuration.toNanos() / 1_000_000_000.0;
        this.forceCadence = Objects.requireNonNull(forceCadence, "forceCadence must not be null");
        if (forceCadence.isNegative()) {
            throw new IllegalArgumentException("forceCadence must not be negative: " + forceCadence);
        }
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
    }

    /**
     * Validates a segment byte cap: positive, and no larger than
     * {@link SegmentWriter#MAX_DATA_BYTES} — a cap beyond the writer's own
     * limit could never trigger a rotation before the writer refused the
     * append, which would end the take instead of rotating it.
     *
     * @param maxSegmentBytes the requested cap
     * @return {@code maxSegmentBytes}
     * @throws IllegalArgumentException if the cap is not in
     *         {@code [1, SegmentWriter.MAX_DATA_BYTES]}
     */
    static long requireSegmentByteCap(long maxSegmentBytes) {
        if (maxSegmentBytes <= 0) {
            throw new IllegalArgumentException("maxSegmentBytes must be positive: " + maxSegmentBytes);
        }
        if (maxSegmentBytes > SegmentWriter.MAX_DATA_BYTES) {
            throw new IllegalArgumentException("maxSegmentBytes must not exceed SegmentWriter.MAX_DATA_BYTES ("
                    + SegmentWriter.MAX_DATA_BYTES + "): " + maxSegmentBytes);
        }
        return maxSegmentBytes;
    }

    /**
     * Starts the session: creates the output directory, opens the first
     * segment ({@code segment-NNN.wav.part}, NNN = the first segment index)
     * and allocates the RAM mirror.
     *
     * @throws IllegalStateException    if already active
     * @throws UncheckedIOException     if the directory or the segment file
     *                                  cannot be created
     * @throws IllegalArgumentException if the format's bit depth is not 16,
     *                                  24 or 32
     */
    public void start() {
        if (active) {
            throw new IllegalStateException("Recording session is already active");
        }
        try {
            createdOutputDirectory = !Files.isDirectory(outputDirectory);
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create recording directory " + outputDirectory, e);
        }
        sessionStartTime = Instant.now();

        // Initialize the audio capture buffer with a reasonable initial capacity
        int initialCapacity = (int) format.sampleRate() * 10; // ~10 seconds
        capturedAudio = new float[format.channels()][initialCapacity];
        capturedSampleCount = 0;
        totalSamplesRecorded.set(0);
        nextSegmentIndex = firstSegmentIndex;

        openSegment();
        active = true;
        paused = false;
        for (RecordingListener listener : listeners) {
            listener.onRecordingStarted();
        }
    }

    /**
     * Pauses the session: blocks are ignored until {@link #resume()}.
     */
    public void pause() {
        if (!active || paused) {
            return;
        }
        paused = true;
        for (RecordingListener listener : listeners) {
            listener.onRecordingPaused();
        }
    }

    /**
     * Resumes a paused session.
     */
    public void resume() {
        if (!active || !paused) {
            return;
        }
        paused = false;
        for (RecordingListener listener : listeners) {
            listener.onRecordingResumed();
        }
    }

    /**
     * Stops the session and seals the current segment with its exact
     * frame and byte counts (patch header, force, atomic rename). A
     * zero-frame tail is deleted and dropped from {@link #getSegments()}
     * instead. The session is inactive afterwards even if sealing failed;
     * a failed seal leaves the {@code .part} on disk for recovery.
     *
     * @throws UncheckedIOException if sealing fails
     */
    public void stop() {
        if (!active) {
            return;
        }
        try {
            sealCurrentSegment();
        } finally {
            active = false;
            paused = false;
            for (RecordingListener listener : listeners) {
                listener.onRecordingStopped();
            }
        }
    }

    /**
     * Captures one block: copies the block into the RAM mirror, rotates
     * first if the block would take a segment that already holds audio past
     * the byte cap, appends that mirror region to the streaming segment,
     * advances the exact counters and rotates the segment when a limit is
     * reached. Ignored while inactive or paused.
     *
     * <p>Disk first: the counters advance after the append, whether or not
     * it throws, by exactly the frames it added to the writer's
     * {@link SegmentWriter#frameCount() count} — the whole block when only
     * the cadence force after its writes fails, none when it fails before
     * writing — so the session's counters, its RAM mirror and the segments
     * its writers seal hold the same frames.</p>
     *
     * @param inputBuffer the routed audio {@code [channel][frame]}; rows
     *                    beyond the stream width are ignored, missing rows
     *                    stay silent
     * @param numFrames   the number of sample frames to capture
     * @throws UncheckedIOException if the segment append or a rotation seal fails
     */
    public void recordAudioData(float[][] inputBuffer, int numFrames) {
        if (!active || paused) {
            return;
        }
        if (inputBuffer == null || numFrames <= 0) {
            return;
        }

        int at = capturedSampleCount;
        ensureMirrorCapacity(at + numFrames);

        // Copy the routed block into the mirror region [at, at + numFrames);
        // that region is beyond the counted frames; after a failed append
        // it may hold the failed block's samples, which a narrower retry
        // would not overwrite.
        int channels = Math.min(inputBuffer.length, capturedAudio.length);
        for (int ch = 0; ch < channels; ch++) {
            int framesToCopy = Math.min(numFrames, inputBuffer[ch].length);
            System.arraycopy(inputBuffer[ch], 0, capturedAudio[ch], at, framesToCopy);
        }

        // Byte cap, checked BEFORE the append: a segment that already holds
        // audio rotates rather than grow past the cap, so no sealed segment
        // exceeds it (a single block larger than the cap is the one
        // exception: it goes into the empty segment, which then rotates).
        long incomingBytes = (long) numFrames * writer.bytesPerFrame();
        if (writer.frameCount() > 0 && writer.dataBytes() + incomingBytes > maxSegmentBytes) {
            sealCurrentSegment();
            openSegment();
        }

        SegmentWriter w = writer;
        long framesBefore = w.frameCount();
        try {
            w.append(capturedAudio, capturedAudio.length, at, numFrames);
        } catch (IOException e) {
            throw new UncheckedIOException("write failed on " + w.partPath(), e);
        } finally {
            // Disk first, however the append ends: count exactly the frames
            // it added to the writer's count — the whole block when only the
            // cadence force after its writes failed, none when it failed
            // before writing.
            int appended = (int) (w.frameCount() - framesBefore);
            capturedSampleCount = at + appended;
            totalSamplesRecorded.addAndGet(appended);
        }

        if (shouldRotateSegment()) {
            sealCurrentSegment();
            openSegment();
        }
    }

    /**
     * The flush service's cadence tick for this session (book §2.1, §4.3):
     * forces the current segment's un-forced bytes if the force cadence has
     * elapsed since its last force ({@link SegmentWriter#forceIfDue()}),
     * whether or not a block was appended — so the last bytes before a
     * stretch with no appends are forced on cadence as well. Runs while the
     * session is active, paused or not (a pause stops the appends, not the
     * risk to the bytes already written); an inactive session, or one with
     * no open segment, has nothing to force. {@code capture-flush} thread.
     *
     * @return whether a force ran
     * @throws UncheckedIOException  if the force fails; the flush service
     *                               answers it as it answers a failed append
     * @throws IllegalStateException if the session is active but its current
     *                               writer is no longer streaming — a state
     *                               the session never leaves itself in; the
     *                               throw makes it loud instead of a quietly
     *                               skipped force
     */
    boolean forceIfCadenceElapsed() {
        SegmentWriter w = writer;
        if (!active || w == null) {
            return false;
        }
        try {
            return w.forceIfDue();
        } catch (IOException e) {
            throw new UncheckedIOException("force failed on " + w.partPath(), e);
        }
    }

    private void ensureMirrorCapacity(int requiredCapacity) {
        if (requiredCapacity <= capturedAudio[0].length) {
            return;
        }
        int newCapacity = Math.max(requiredCapacity, capturedAudio[0].length * 2);
        float[][] expanded = new float[capturedAudio.length][newCapacity];
        int count = capturedSampleCount;
        for (int ch = 0; ch < capturedAudio.length; ch++) {
            System.arraycopy(capturedAudio[ch], 0, expanded[ch], 0, count);
        }
        capturedAudio = expanded;
    }

    /**
     * Returns the captured audio data, trimmed to the actual recorded length.
     *
     * <p>Returns {@code null} if no audio has been captured.</p>
     *
     * @return audio data as {@code [channel][sample]} in [-1.0, 1.0], or {@code null}
     */
    public float[][] getCapturedAudio() {
        int count = capturedSampleCount;
        float[][] mirror = capturedAudio;
        if (mirror == null || count == 0) {
            return null;
        }
        float[][] trimmed = new float[mirror.length][count];
        for (int ch = 0; ch < mirror.length; ch++) {
            System.arraycopy(mirror[ch], 0, trimmed[ch], 0, count);
        }
        return trimmed;
    }

    /**
     * Returns the number of audio sample frames captured so far.
     *
     * @return the captured sample count
     */
    public int getCapturedSampleCount() {
        return capturedSampleCount;
    }

    /**
     * Adds a recording listener. Callbacks fire on the thread driving the
     * session — the {@code capture-flush} thread once capture is running.
     *
     * @param listener the listener to add
     */
    public void addListener(RecordingListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    /**
     * Removes a recording listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(RecordingListener listener) {
        listeners.remove(listener);
    }

    /** Returns whether the session is currently active. */
    public boolean isActive() {
        return active;
    }

    /** Returns whether the session is paused. */
    public boolean isPaused() {
        return paused;
    }

    /** Returns the audio format. */
    public AudioFormat getFormat() {
        return format;
    }

    /** Returns the directory the session's segment files live in. */
    public Path getOutputDirectory() {
        return outputDirectory;
    }

    /** Returns the total number of frames recorded across all segments (exact). */
    public long getTotalSamplesRecorded() {
        return totalSamplesRecorded.get();
    }

    /**
     * Returns the total recording duration based on samples and sample rate.
     *
     * @return the total duration
     */
    public Duration getTotalDuration() {
        long samples = totalSamplesRecorded.get();
        long millis = (long) (samples / format.sampleRate() * 1000.0);
        return Duration.ofMillis(millis);
    }

    /** Returns when the session started (null if not yet started). */
    public Instant getSessionStartTime() {
        return sessionStartTime;
    }

    /**
     * Returns an unmodifiable, iteration-safe view of the segments: every
     * entry corresponds to a file on disk — the in-progress one at its
     * {@link RecordingSegment#streamingPath()}, sealed ones at their
     * {@link RecordingSegment#filePath()}.
     *
     * @return the list of segments
     */
    public List<RecordingSegment> getSegments() {
        return Collections.unmodifiableList(segments);
    }

    /** Returns the number of sealed and in-progress segments. */
    public int getSegmentCount() {
        return segments.size();
    }

    /** Returns the current (in-progress) segment, or {@code null}. */
    public RecordingSegment getCurrentSegment() {
        return currentSegment;
    }

    /** Returns the maximum segment duration. */
    public Duration getMaxSegmentDuration() {
        return maxSegmentDuration;
    }

    /** Returns the maximum segment size in bytes. */
    public long getMaxSegmentBytes() {
        return maxSegmentBytes;
    }

    /** Returns the writer's force-to-storage cadence. */
    public Duration getForceCadence() {
        return forceCadence;
    }

    /**
     * Returns the driver round-trip compensation in sample frames the
     * recording pipeline configured for this session — the value the
     * pipeline subtracts from each captured-block sample position so the
     * recorded take aligns with the cue the user heard. Returns
     * {@code 0} when compensation is disabled (user toggle) or the
     * driver reports {@link com.benesquivelmusic.daw.sdk.audio.RoundTripLatency#UNKNOWN}.
     *
     * @return compensation frames (never negative)
     */
    public long getCompensationFrames() {
        return compensationFrames;
    }

    /**
     * Sets the driver round-trip compensation in sample frames. Called
     * once by {@code RecordingPipeline} at session start.
     *
     * @param compensationFrames the compensation amount; must be {@code >= 0}
     * @throws IllegalArgumentException if {@code compensationFrames < 0}
     */
    public void setCompensationFrames(long compensationFrames) {
        if (compensationFrames < 0) {
            throw new IllegalArgumentException(
                    "compensationFrames must be >= 0: " + compensationFrames);
        }
        this.compensationFrames = compensationFrames;
    }

    /**
     * Sets the index of the first segment this session opens (default 0).
     * A later loop lane continues the track directory's numbering from
     * where the previous lane stopped. Must be called before {@link #start()}.
     */
    void setFirstSegmentIndex(int firstSegmentIndex) {
        if (active) {
            throw new IllegalStateException("cannot change the segment index of an active session");
        }
        if (firstSegmentIndex < 0) {
            throw new IllegalArgumentException("firstSegmentIndex must not be negative: " + firstSegmentIndex);
        }
        this.firstSegmentIndex = firstSegmentIndex;
        this.nextSegmentIndex = firstSegmentIndex;
    }

    /** Returns the index the next opened segment will take. */
    int getNextSegmentIndex() {
        return nextSegmentIndex;
    }

    /** Installs the segment observer (flush-service bookkeeping); {@code null} clears it. */
    void setSegmentObserver(SegmentObserver observer) {
        this.segmentObserver = observer;
    }

    /**
     * Replaces how every segment this session opens gets its channel (test
     * seam: a delegating channel makes the writer's {@code force} calls
     * observable). Must be called before {@link #start()}.
     */
    void setChannelOpener(SegmentWriter.ChannelOpener opener) {
        if (active) {
            throw new IllegalStateException("cannot change the channel opener of an active session");
        }
        this.channelOpener = Objects.requireNonNull(opener, "opener must not be null");
    }

    /** Returns the active segment writer, or {@code null} when no segment is open. */
    SegmentWriter getCurrentWriter() {
        return writer;
    }

    /**
     * Test seam: closes the current writer with no seal and no rename
     * ({@link SegmentWriter#abandon()}) and marks the session inactive; a
     * segment that was streaming stays a {@code .part} holding every frame
     * appended to it, with the streaming sentinel in its size fields.
     */
    void abandonWithoutSeal() {
        active = false;
        paused = false;
        if (writer != null) {
            try {
                writer.abandon();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "abandon failed on " + writer.partPath(), e);
            }
        }
    }

    /**
     * Start-failure rollback: abandons the writer, deletes the files this
     * session created (streaming and sealed), forgets the segments and
     * removes the output directory if this session created it and it is
     * now empty (best-effort: what an I/O error keeps from being deleted is
     * left and the error logged). Idempotent.
     */
    void discardAllFiles() {
        active = false;
        paused = false;
        if (writer != null) {
            try {
                writer.abandon();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "abandon failed on " + writer.partPath(), e);
            }
            writer = null;
        }
        for (RecordingSegment segment : segments) {
            deleteQuietly(segment.streamingPath());
            deleteQuietly(segment.filePath());
        }
        segments.clear();
        currentSegment = null;
        nextSegmentIndex = firstSegmentIndex;
        if (createdOutputDirectory) {
            try {
                Files.deleteIfExists(outputDirectory);
            } catch (DirectoryNotEmptyException notEmpty) {
                // Someone else's files: leave them.
            } catch (IOException e) {
                LOG.log(Level.WARNING, "could not remove " + outputDirectory, e);
            }
        }
    }

    private boolean shouldRotateSegment() {
        if (writer == null) {
            return false;
        }
        double seconds = writer.frameCount() / format.sampleRate();
        return seconds >= maxSegmentSeconds || writer.dataBytes() >= maxSegmentBytes;
    }

    private void openSegment() {
        int index = nextSegmentIndex;
        Path sealedPath = outputDirectory.resolve(String.format(Locale.ROOT, SEGMENT_NAME_FORMAT, index));
        try {
            writer = SegmentWriter.open(SegmentWriter.partPathFor(sealedPath), format.sampleRate(),
                    format.channels(), format.bitDepth(), forceCadence, nanoClock, channelOpener);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open segment " + sealedPath, e);
        }
        nextSegmentIndex = index + 1;
        currentSegment = RecordingSegment.startNew(index, sealedPath);
        segments.add(currentSegment);
        if (segmentObserver != null) {
            segmentObserver.onSegmentOpened(currentSegment);
        }
        for (RecordingListener listener : listeners) {
            listener.onNewSegmentCreated(index);
        }
    }

    private void sealCurrentSegment() {
        SegmentWriter w = writer;
        RecordingSegment segment = currentSegment;
        if (w == null || segment == null) {
            return;
        }
        writer = null;
        currentSegment = null;
        if (w.frameCount() == 0) {
            discardEmptyTail(w, segment);
            return;
        }
        try {
            w.seal();
        } catch (IOException e) {
            try {
                w.abandon();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new UncheckedIOException("cannot seal segment " + w.sealedPath(), e);
        }
        RecordingSegment sealed = segment.complete(w.frameCount(), w.dataBytes());
        int position = segments.indexOf(segment);
        if (position >= 0) {
            segments.set(position, sealed);
        } else {
            segments.add(sealed);
        }
        if (segmentObserver != null) {
            segmentObserver.onSegmentSealed(sealed);
        }
    }

    private void discardEmptyTail(SegmentWriter w, RecordingSegment segment) {
        try {
            w.abandon();
            Files.deleteIfExists(w.partPath());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot discard empty segment " + w.partPath(), e);
        }
        segments.remove(segment);
        nextSegmentIndex = segment.index();
        if (segmentObserver != null) {
            segmentObserver.onSegmentDiscarded(segment);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not delete " + path, e);
        }
    }
}
