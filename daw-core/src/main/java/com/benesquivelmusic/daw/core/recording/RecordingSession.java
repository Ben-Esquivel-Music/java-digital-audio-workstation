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
