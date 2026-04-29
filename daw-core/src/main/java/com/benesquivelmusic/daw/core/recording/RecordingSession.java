package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.event.RecordingListener;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages a long-running recording session with automatic segmentation.
 *
 * <p>Recording sessions that may last several hours or days are automatically
 * split into segments of configurable maximum duration. Each segment is stored
 * as a separate file, ensuring that:</p>
 * <ul>
 *   <li>Individual file sizes remain manageable</li>
 *   <li>A crash only risks the current segment, not the entire session</li>
 *   <li>Segments can be checkpointed and flushed independently</li>
 * </ul>
 *
 * <p>The session notifies registered {@link RecordingListener}s of lifecycle
 * events and segment transitions.</p>
 */
public final class RecordingSession {

    /** Default maximum segment duration: 30 minutes. */
    public static final Duration DEFAULT_MAX_SEGMENT_DURATION = Duration.ofMinutes(30);

    /** Default maximum segment size: 500 MB. */
    public static final long DEFAULT_MAX_SEGMENT_BYTES = 500L * 1024 * 1024;

    private final AudioFormat format;
    private final Path outputDirectory;
    private final Duration maxSegmentDuration;
    private final long maxSegmentBytes;
    private final List<RecordingSegment> segments = new ArrayList<>();
    private final List<RecordingListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong totalSamplesRecorded = new AtomicLong(0);

    private boolean active;
    private boolean paused;
    private Instant sessionStartTime;
    private RecordingSegment currentSegment;
    private long currentSegmentBytes;

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
    private long compensationFrames;

    // Growing audio capture buffer: [channel][sample]
    private float[][] capturedAudio;
    private int capturedSampleCount;

    /**
     * Creates a new recording session.
     *
     * @param format             the audio format
     * @param outputDirectory    the directory for segment files
     * @param maxSegmentDuration the maximum duration per segment
     * @param maxSegmentBytes    the maximum size per segment in bytes
     */
    public RecordingSession(AudioFormat format, Path outputDirectory,
                            Duration maxSegmentDuration, long maxSegmentBytes) {
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        this.maxSegmentDuration = Objects.requireNonNull(maxSegmentDuration,
                "maxSegmentDuration must not be null");
        if (maxSegmentDuration.isZero() || maxSegmentDuration.isNegative()) {
            throw new IllegalArgumentException("maxSegmentDuration must be positive: " + maxSegmentDuration);
        }
        if (maxSegmentBytes <= 0) {
            throw new IllegalArgumentException("maxSegmentBytes must be positive: " + maxSegmentBytes);
        }
        this.maxSegmentBytes = maxSegmentBytes;
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
     * Starts the recording session.
     *
     * @throws IllegalStateException if already active
     */
    public void start() {
        if (active) {
            throw new IllegalStateException("Recording session is already active");
        }
        active = true;
        paused = false;
        sessionStartTime = Instant.now();

        // Initialize the audio capture buffer with a reasonable initial capacity
        int initialCapacity = (int) format.sampleRate() * 10; // ~10 seconds
        capturedAudio = new float[format.channels()][initialCapacity];
        capturedSampleCount = 0;

        startNewSegment();
        for (RecordingListener listener : listeners) {
            listener.onRecordingStarted();
        }
    }

    /**
     * Pauses the recording session.
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
     * Resumes a paused recording session.
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
     * Stops the recording session and finalizes all segments.
     */
    public void stop() {
        if (!active) {
            return;
        }
        finalizeCurrentSegment();
        active = false;
        paused = false;
        for (RecordingListener listener : listeners) {
            listener.onRecordingStopped();
        }
    }

    /**
     * Processes incoming audio samples. If the current segment exceeds
     * the configured limits, a new segment is automatically started.
     *
     * @param sampleCount the number of new samples
     * @param byteSize    the size in bytes of the new data
     */
    public void recordSamples(long sampleCount, long byteSize) {
        if (!active || paused) {
            return;
        }
        totalSamplesRecorded.addAndGet(sampleCount);
        currentSegmentBytes += byteSize;

        if (shouldRotateSegment()) {
            finalizeCurrentSegment();
            startNewSegment();
        }
    }

    /**
     * Captures actual audio sample data into the growing buffer and
     * updates segment tracking.
     *
     * <p>The audio data is accumulated in memory so that it can be
     * attached to an {@link com.benesquivelmusic.daw.core.audio.AudioClip}
     * when recording stops.</p>
     *
     * @param inputBuffer the input audio data {@code [channel][frame]}
     * @param numFrames   the number of sample frames to capture
     */
    public void recordAudioData(float[][] inputBuffer, int numFrames) {
        if (!active || paused) {
            return;
        }
        if (inputBuffer == null || numFrames <= 0) {
            return;
        }

        // Ensure the capture buffer has enough capacity
        int requiredCapacity = capturedSampleCount + numFrames;
        if (capturedAudio != null && requiredCapacity > capturedAudio[0].length) {
            int newCapacity = Math.max(requiredCapacity, capturedAudio[0].length * 2);
            float[][] expanded = new float[capturedAudio.length][newCapacity];
            for (int ch = 0; ch < capturedAudio.length; ch++) {
                System.arraycopy(capturedAudio[ch], 0, expanded[ch], 0, capturedSampleCount);
            }
            capturedAudio = expanded;
        }

        // Copy input audio into the capture buffer
        if (capturedAudio != null) {
            int channels = Math.min(inputBuffer.length, capturedAudio.length);
            for (int ch = 0; ch < channels; ch++) {
                int framesToCopy = Math.min(numFrames, inputBuffer[ch].length);
                System.arraycopy(inputBuffer[ch], 0, capturedAudio[ch], capturedSampleCount, framesToCopy);
            }
            capturedSampleCount += numFrames;
        }

        // Update segment tracking
        int bytesPerSample = format.bitDepth() / 8;
        long byteSize = (long) numFrames * format.channels() * bytesPerSample;
        totalSamplesRecorded.addAndGet(numFrames);
        currentSegmentBytes += byteSize;

        if (shouldRotateSegment()) {
            finalizeCurrentSegment();
            startNewSegment();
        }
    }

    /**
     * Returns the captured audio data, trimmed to the actual recorded length.
     *
     * <p>Returns {@code null} if no audio has been captured.</p>
     *
     * @return audio data as {@code [channel][sample]} in [-1.0, 1.0], or {@code null}
     */
    public float[][] getCapturedAudio() {
        if (capturedAudio == null || capturedSampleCount == 0) {
            return null;
        }
        float[][] trimmed = new float[capturedAudio.length][capturedSampleCount];
        for (int ch = 0; ch < capturedAudio.length; ch++) {
            System.arraycopy(capturedAudio[ch], 0, trimmed[ch], 0, capturedSampleCount);
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
     * Adds a recording listener.
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

    /** Returns the total number of samples recorded across all segments. */
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
     * Returns an unmodifiable view of the recorded segments.
     *
     * @return the list of segments
     */
    public List<RecordingSegment> getSegments() {
        return Collections.unmodifiableList(segments);
    }

    /** Returns the number of completed and in-progress segments. */
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

    private boolean shouldRotateSegment() {
        if (currentSegment == null) {
            return false;
        }
        // Check duration
        Duration segmentAge = Duration.between(currentSegment.startTime(), Instant.now());
        if (segmentAge.compareTo(maxSegmentDuration) >= 0) {
            return true;
        }
        // Check size
        return currentSegmentBytes > maxSegmentBytes;
    }

    private void startNewSegment() {
        int index = segments.size();
        String fileName = String.format("segment-%03d.wav", index);
        Path segmentPath = outputDirectory.resolve(fileName);
        currentSegment = RecordingSegment.startNew(index, segmentPath);
        currentSegmentBytes = 0;
        segments.add(currentSegment);
        for (RecordingListener listener : listeners) {
            listener.onNewSegmentCreated(index);
        }
    }

    private void finalizeCurrentSegment() {
        if (currentSegment != null && currentSegment.isInProgress()) {
            long segmentSamples = estimateSegmentSamples();
            RecordingSegment finalized = currentSegment.complete(segmentSamples, currentSegmentBytes);
            segments.set(finalized.index(), finalized);
            currentSegment = null;
            currentSegmentBytes = 0;
        }
    }

    private long estimateSegmentSamples() {
        if (currentSegment == null) {
            return 0;
        }
        Duration segmentDuration = Duration.between(currentSegment.startTime(), Instant.now());
        return (long) (segmentDuration.toMillis() / 1000.0 * format.sampleRate());
    }
}
