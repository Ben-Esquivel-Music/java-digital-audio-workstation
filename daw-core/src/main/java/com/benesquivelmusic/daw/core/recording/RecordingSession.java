package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
 * one place: a {@link SegmentWriter} streaming a real WAV segment under the
 * session's output directory — {@code <take>/<trackId>/segment-NNN.wav.part}
 * while streaming, {@code segment-NNN.wav} once sealed. The session keeps no
 * copy of the audio: what it holds in memory is one staging block of
 * {@link #STAGING_FRAMES} frames, allocated at {@link #start()}, whose size
 * does not depend on the length of the take (Recording Reliability book
 * §4.5). The segment files are the take's audio; {@link SegmentFile} reads
 * them back. Long sessions are
 * split into segments by exact counts (context D4): a segment rotates —
 * seal, then open {@code segment-(n+1)} — after the append that takes its
 * frame count to {@link #getMaxSegmentDuration()} worth of audio <em>or</em>
 * its data chunk to {@link #getMaxSegmentBytes()}, and <em>before</em> an
 * append that would take a segment already holding audio past the byte cap.
 * A sealed segment therefore never exceeds the byte cap, except when one
 * block alone is larger than the cap (a block longer than
 * {@link #STAGING_FRAMES} frames counts as several, see
 * {@link #recordAudioData}). No wall-clock read decides rotation;
 * the {@link RecordingSegment#startTime()}/{@code endTime()} instants are
 * metadata only.</p>
 *
 * <p><strong>Threads.</strong> A track's first-lane session is constructed
 * on the caller thread (in {@code RecordingPipeline.prepare()}), a later
 * loop lane's on the flush thread. {@link #start()} runs on the
 * {@code capture-flush} thread — inside the take's initialisation for a
 * track's first lane, and for a later loop lane ahead of its lap, or at the
 * loop wrap when no lane was started ahead — as do
 * {@link #recordAudioData}, the cadence tick
 * ({@code forceIfCadenceElapsed()}) and {@link #stop()}: never on the audio
 * callback; {@link #pause()} and {@link #resume()} have no caller in the
 * pipeline this stage and run on whichever thread drives the session.
 * {@link RecordingListener} callbacks therefore fire on the flush thread. The
 * counters other threads read ({@link #getTotalSamplesRecorded()},
 * {@link #isActive()}) are atomic or volatile; a reader that first passes the flush service's
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
 * <p>Capture width is this track session's format ({@link AudioFormat#channels()}):
 * physical tracks use their frozen routed width, and graph instruments use the render width.
 * Rows absent from the routed block are zeroed. Bit depths 16, 24 and 32
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
     * Frames of the staging block: a block handed to
     * {@link #recordAudioData(float[][], int)} is widened to the stream
     * width and appended in chunks of at most this many frames.
     */
    static final int STAGING_FRAMES = 16_384;

    /**
     * Segment lifecycle observer for the flush service's manifest
     * bookkeeping. Fires on whichever thread drives the session (see the
     * class note): opened when a {@code .part} is created — or an open,
     * empty one is taken over from another session — sealed when its
     * {@code .wav} lands with exact counts, discarded when a zero-frame tail
     * is deleted instead of sealed, or handed to another session
     * ({@link #surrenderEmptySegment()}). {@code onSegmentSealed} at a
     * rotation fires before the next segment is opened.
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
    /** An open, empty writer the next {@code openSegment()} uses instead of creating the file; see {@link #adoptAsNextSegment}. */
    private SegmentWriter adoptedWriter;
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

    // Staging block [format.channels()][STAGING_FRAMES]: the routed block is
    // widened to the stream width here on its way to the writer. Allocated
    // by start(), never grown; touched only by the thread driving the session.
    private float[][] staging;

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
     * @param outputDirectory    the directory for segment files (created at
     *                           {@link #start()} if it is missing)
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
     * Starts the session: creates the output directory if it is missing,
     * opens the first segment ({@code segment-NNN.wav.part}, NNN = the first
     * segment index) and allocates the staging block.
     *
     * <p>The session owns the output directory only if this start created
     * it (missing parent directories are created first; the session never
     * removes those), and only then does {@link #discardAllFiles()} remove
     * it ({@link #createdOutputDirectory()}). A directory already at that
     * path, or a symbolic link to one, is used as it is and stays someone
     * else's; anything else there — a regular file, a dangling link, a link
     * to a file — fails the start and is left as it was. Each start decides
     * afresh: one that finds the directory in place owns nothing, even if an
     * earlier start of this session created it.</p>
     *
     * @throws IllegalStateException    if already active
     * @throws UncheckedIOException     if the directory or the segment file
     *                                  cannot be created; its cause is a
     *                                  {@link FileAlreadyExistsException}
     *                                  when something other than a directory
     *                                  is at the directory's path
     * @throws IllegalArgumentException if the format's bit depth is not 16,
     *                                  24 or 32
     */
    public void start() {
        if (active) {
            throw new IllegalStateException("Recording session is already active");
        }
        createdOutputDirectory = false;
        try {
            Path parent = outputDirectory.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                Files.createDirectory(outputDirectory);
                createdOutputDirectory = true;
            } catch (FileAlreadyExistsException existing) {
                // A directory, or a link to one, is used and stays someone
                // else's; anything else at that name is refused, untouched.
                if (!Files.isDirectory(outputDirectory)) {
                    throw existing;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create recording directory " + outputDirectory, e);
        }
        sessionStartTime = Instant.now();

        staging = new float[format.channels()][STAGING_FRAMES];
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
     * Captures one block: widens it to the stream width in the staging
     * block, rotates first if it would take a segment that already holds
     * audio past the byte cap, appends it to the streaming segment, advances
     * the exact counter and rotates the segment when a limit is reached.
     * Ignored while inactive or paused.
     *
     * <p>A block longer than {@link #STAGING_FRAMES} frames is captured as
     * consecutive chunks of at most that many frames, each handled as a
     * block of its own — so a rotation can fall between two chunks of one
     * call, and a failure ends the call at the chunk that failed, with the
     * chunks before it written and counted.</p>
     *
     * <p>Disk first: the counter advances after each append, whether or not
     * it throws, by exactly the frames it added to the writer's
     * {@link SegmentWriter#frameCount() count} — the whole chunk when only
     * the cadence force after its writes fails, none when it fails before
     * writing — so the session's counter and the segments its writers seal
     * hold the same frames.</p>
     *
     * @param inputBuffer the routed audio {@code [channel][frame]}; rows
     *                    beyond the stream width are ignored, missing rows
     *                    stay silent, and a row shorter than
     *                    {@code numFrames} is silent past its end
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
        int offset = 0;
        while (offset < numFrames) {
            int chunk = Math.min(STAGING_FRAMES, numFrames - offset);
            stage(inputBuffer, offset, chunk);
            appendStaged(chunk);
            offset += chunk;
        }
    }

    /**
     * Fills {@code staging[ch][0..frames)} from {@code inputBuffer[ch][offset..)}
     * for every stream channel: silence for a channel the routed block does
     * not provide and for the frames a provided row is too short to hold, so
     * nothing of an earlier chunk is appended again.
     */
    private void stage(float[][] inputBuffer, int offset, int frames) {
        for (int ch = 0; ch < staging.length; ch++) {
            int provided = 0;
            if (ch < inputBuffer.length) {
                float[] row = inputBuffer[ch];
                provided = Math.max(0, Math.min(frames, row.length - offset));
                if (provided > 0) {
                    System.arraycopy(row, offset, staging[ch], 0, provided);
                }
            }
            if (provided < frames) {
                Arrays.fill(staging[ch], provided, frames, 0f);
            }
        }
    }

    /** Appends {@code staging[..][0..frames)} to the current segment, rotating around it as the limits ask. */
    private void appendStaged(int frames) {
        // Byte cap, checked BEFORE the append: a segment that already holds
        // audio rotates rather than grow past the cap, so no sealed segment
        // exceeds it (a single block larger than the cap is the one
        // exception: it goes into the empty segment, which then rotates).
        long incomingBytes = (long) frames * writer.bytesPerFrame();
        if (writer.frameCount() > 0 && writer.dataBytes() + incomingBytes > maxSegmentBytes) {
            sealCurrentSegment();
            openSegment();
        }

        SegmentWriter w = writer;
        long framesBefore = w.frameCount();
        try {
            w.append(staging, staging.length, 0, frames);
        } catch (IOException e) {
            throw new UncheckedIOException("write failed on " + w.partPath(), e);
        } finally {
            // Disk first, however the append ends: count exactly the frames
            // it added to the writer's count — the whole chunk when only the
            // cadence force after its writes failed, none when it failed
            // before writing.
            totalSamplesRecorded.addAndGet(w.frameCount() - framesBefore);
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

    /**
     * Gives up this session's open segment, which holds no frames, without
     * closing or deleting its file: the segment leaves {@link #getSegments()},
     * the observer is told it was discarded, the session becomes inactive
     * (its listeners are told it stopped), and the writer is returned for
     * another session of the same directory to continue
     * ({@link #adoptAsNextSegment}). A pre-opened loop lane hands its file
     * to the lane before it this way when that lane rotates.
     * {@code capture-flush} thread.
     *
     * @return the open writer, at frame 0
     * @throws IllegalStateException if the session is not active, or its
     *                               current segment holds frames
     */
    SegmentWriter surrenderEmptySegment() {
        SegmentWriter w = writer;
        RecordingSegment segment = currentSegment;
        if (!active || w == null || segment == null || w.frameCount() != 0) {
            throw new IllegalStateException("only an active session's empty segment can be surrendered");
        }
        writer = null;
        currentSegment = null;
        segments.remove(segment);
        nextSegmentIndex = segment.index();
        active = false;
        paused = false;
        if (segmentObserver != null) {
            segmentObserver.onSegmentDiscarded(segment);
        }
        for (RecordingListener listener : listeners) {
            listener.onRecordingStopped();
        }
        return w;
    }

    /**
     * Makes {@code preOpened} the writer of the next segment this session
     * opens, instead of creating the file: it must be streaming, hold no
     * frames, and sit at exactly the path that segment takes. Called from
     * the segment observer between a rotation's seal and its open
     * ({@link SegmentObserver#onSegmentSealed}), so the rotation opens no
     * file. {@code capture-flush} thread.
     *
     * @throws IllegalArgumentException if the writer holds frames or is at another path
     */
    void adoptAsNextSegment(SegmentWriter preOpened) {
        Objects.requireNonNull(preOpened, "preOpened must not be null");
        Path expected = SegmentWriter.partPathFor(sealedPathOf(nextSegmentIndex));
        if (preOpened.frameCount() != 0 || !preOpened.partPath().equals(expected)) {
            throw new IllegalArgumentException("the next segment is " + expected + ", empty; cannot adopt "
                    + preOpened.partPath() + " holding " + preOpened.frameCount() + " frame(s)");
        }
        adoptedWriter = preOpened;
    }

    /**
     * Restarts the force cadence of the current segment's writer
     * ({@link SegmentWriter#restartForceCadence()}): a loop lane that was
     * started ahead of its lap counts the cadence from the moment the lap
     * begins. Does nothing when no segment is open. {@code capture-flush}
     * thread.
     */
    void restartForceCadence() {
        SegmentWriter w = writer;
        if (w != null) {
            w.restartForceCadence();
        }
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
     * Returns whether this session's most recent {@link #start()} created
     * the output directory: set once that start has created it, and kept if
     * the start then fails (at its first segment open, say); clear when it
     * found a directory, or a link to one, already there, when it failed
     * before creating it, and before any start. A start refused because the
     * session is already active changes nothing. Reads a field and touches
     * no storage: {@code TrackCapture} reads it after each lane start to
     * decide whether the track directory is its to remove.
     */
    boolean createdOutputDirectory() {
        return createdOutputDirectory;
    }

    /**
     * Start-failure rollback: abandons the writer, deletes the files this
     * session created, forgets the segments and removes the output
     * directory if this session's most recent start created it
     * ({@link #createdOutputDirectory()}) and it is now empty (best-effort:
     * what an I/O error keeps from being deleted is left and the error
     * logged). Idempotent.
     *
     * <p>Of each listed segment it deletes the one name this session wrote:
     * the {@code .part} of a segment still in progress, which the writer
     * created, refusing an existing file, and the {@code .wav} of a sealed
     * one, which its seal's rename created. Never the other name: a sealed
     * segment's {@code .part} was renamed away by its seal, and the writer
     * never writes an in-progress segment's {@code .wav} — it refuses to
     * open, or to seal, onto an existing one — so whatever is at that name
     * is someone else's.</p>
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
            deleteQuietly(segment.isInProgress() ? segment.streamingPath() : segment.filePath());
        }
        segments.clear();
        currentSegment = null;
        nextSegmentIndex = firstSegmentIndex;
        if (createdOutputDirectory) {
            try {
                Files.deleteIfExists(outputDirectory);
            } catch (DirectoryNotEmptyException notEmpty) {
                // Someone else's files, or ones a failed delete left: leave them.
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

    private Path sealedPathOf(int index) {
        return outputDirectory.resolve(String.format(Locale.ROOT, SEGMENT_NAME_FORMAT, index));
    }

    private void openSegment() {
        int index = nextSegmentIndex;
        Path sealedPath = sealedPathOf(index);
        SegmentWriter adopted = adoptedWriter;
        adoptedWriter = null;
        if (adopted != null) {
            // Already open, empty, at this segment's path: nothing to create.
            adopted.restartForceCadence();
            writer = adopted;
        } else {
            try {
                writer = SegmentWriter.open(SegmentWriter.partPathFor(sealedPath), format.sampleRate(),
                        format.channels(), format.bitDepth(), forceCadence, nanoClock, channelOpener);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open segment " + sealedPath, e);
            }
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
