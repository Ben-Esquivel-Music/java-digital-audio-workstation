package com.benesquivelmusic.daw.core.performance;

import com.benesquivelmusic.daw.core.audio.AudioFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Monitors the performance of the audio engine in real time.
 *
 * <p>The monitor measures CPU utilization of the audio processing thread
 * by comparing the time taken to process each buffer against the available
 * time budget (determined by the buffer size and sample rate). It also
 * detects buffer underruns (when processing exceeds the budget) and fires
 * warnings when CPU load exceeds a configurable threshold.</p>
 *
 * <h3>Thread safety</h3>
 * <p>The {@link #recordProcessingTime(long)} and {@link #recordTrackProcessingTime(String, long)}
 * methods are designed to be called from the audio thread. The
 * {@link #snapshot()} and {@link #getTrackMetrics()} methods can be safely
 * called from the UI thread to read the latest metrics.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * PerformanceMonitor monitor = new PerformanceMonitor(audioFormat);
 * monitor.setWarningThresholdPercent(80.0);
 * monitor.addWarningListener(metrics ->
 *     System.out.println("CPU warning: " + metrics.cpuLoadPercent() + "%"));
 *
 * // In the audio callback:
 * long startNanos = System.nanoTime();
 * engine.processBlock(input, output, numFrames);
 * long elapsedNanos = System.nanoTime() - startNanos;
 * monitor.recordProcessingTime(elapsedNanos);
 * }</pre>
 */
public final class PerformanceMonitor {

    private static final Logger LOGGER = Logger.getLogger(PerformanceMonitor.class.getName());

    /** Default CPU load warning threshold: 80%. */
    public static final double DEFAULT_WARNING_THRESHOLD = 80.0;

    private static final double SMOOTHING_FACTOR = 0.1;

    private final AudioFormat format;
    private final double bufferDurationNanos;
    private final double bufferLatencyMs;

    private volatile double cpuLoadPercent;
    private final AtomicLong underrunCount = new AtomicLong(0);
    private volatile double warningThresholdPercent = DEFAULT_WARNING_THRESHOLD;
    private volatile boolean warningActive;

    // Per-track metrics: written from audio thread, read from UI thread
    private volatile List<TrackPerformanceMetrics> trackMetrics = Collections.emptyList();

    private final CopyOnWriteArrayList<PerformanceWarningListener> warningListeners =
            new CopyOnWriteArrayList<>();

    /**
     * Creates a new performance monitor for the given audio format.
     *
     * @param format the audio format providing sample rate and buffer size
     */
    public PerformanceMonitor(AudioFormat format) {
        this.format = Objects.requireNonNull(format, "format must not be null");
        this.bufferDurationNanos = (format.bufferSize() / format.sampleRate()) * 1_000_000_000.0;
        this.bufferLatencyMs = (format.bufferSize() / format.sampleRate()) * 1000.0;
    }

    /**
     * Returns the audio format this monitor is configured for.
     *
     * @return the audio format
     */
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * Returns the buffer latency in milliseconds.
     *
     * @return the buffer latency
     */
    public double getBufferLatencyMs() {
        return bufferLatencyMs;
    }

    /**
     * Returns the CPU load warning threshold as a percentage (0.0–100.0).
     *
     * @return the warning threshold
     */
    public double getWarningThresholdPercent() {
        return warningThresholdPercent;
    }

    /**
     * Sets the CPU load warning threshold. When the measured CPU load
     * exceeds this value, a warning is triggered and listeners are notified.
     *
     * @param threshold the threshold as a percentage (0.0–100.0)
     * @throws IllegalArgumentException if threshold is out of range
     */
    public void setWarningThresholdPercent(double threshold) {
        if (threshold < 0.0 || threshold > 100.0) {
            throw new IllegalArgumentException(
                    "threshold must be between 0 and 100: " + threshold);
        }
        this.warningThresholdPercent = threshold;
    }

    /**
     * Adds a listener that is notified when a CPU warning is triggered or cleared.
     *
     * @param listener the listener to add
     */
    public void addWarningListener(PerformanceWarningListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        warningListeners.add(listener);
    }

    /**
     * Removes a previously added warning listener.
     *
     * @param listener the listener to remove
     * @return {@code true} if the listener was removed
     */
    public boolean removeWarningListener(PerformanceWarningListener listener) {
        return warningListeners.remove(listener);
    }

    /**
     * Records the time taken to process one audio buffer. This method
     * updates the exponentially smoothed CPU load, detects underruns,
     * and fires warning events as needed.
     *
     * <p>This method is intended to be called from the audio thread
     * after each {@code processBlock} invocation.</p>
     *
     * @param processingTimeNanos the wall-clock time spent processing
     *                            the buffer, in nanoseconds
     */
    public void recordProcessingTime(long processingTimeNanos) {
        double loadPercent = (processingTimeNanos / bufferDurationNanos) * 100.0;

        // Exponential moving average for smooth CPU load display
        double currentLoad = this.cpuLoadPercent;
        double smoothedLoad = currentLoad + SMOOTHING_FACTOR * (loadPercent - currentLoad);
        this.cpuLoadPercent = smoothedLoad;

        // Detect buffer underrun (processing took longer than available budget)
        if (processingTimeNanos > (long) bufferDurationNanos) {
            long count = underrunCount.incrementAndGet();
            LOGGER.log(Level.WARNING,
                    "Buffer underrun detected (#{0}): processing took {1} ns, budget was {2} ns",
                    new Object[]{count, processingTimeNanos, (long) bufferDurationNanos});
        }

        // Check warning threshold
        double threshold = this.warningThresholdPercent;
        boolean nowWarning = smoothedLoad > threshold;
        boolean wasWarning = this.warningActive;
        this.warningActive = nowWarning;

        if (nowWarning != wasWarning) {
            PerformanceMetrics metrics = buildSnapshot(smoothedLoad);
            if (nowWarning) {
                LOGGER.log(Level.WARNING,
                        "CPU load warning: {0,number,#.#}% exceeds threshold of {1,number,#.#}%",
                        new Object[]{smoothedLoad, threshold});
            } else {
                LOGGER.log(Level.INFO,
                        "CPU load returned to normal: {0,number,#.#}% (threshold: {1,number,#.#}%)",
                        new Object[]{smoothedLoad, threshold});
            }
            for (PerformanceWarningListener listener : warningListeners) {
                listener.onPerformanceWarning(metrics);
            }
        }
    }

    /**
     * Records the processing time for an individual track. Call once per
     * track per buffer cycle, then call {@link #commitTrackMetrics()} after
     * all tracks have been recorded.
     *
     * <p>This method accumulates track metrics into a temporary list. The
     * list is published atomically when {@link #commitTrackMetrics()} is
     * called, so UI readers always see a consistent set of track metrics.</p>
     *
     * @param trackName          the display name of the track
     * @param processingTimeNanos the time spent processing this track in nanoseconds
     */
    public void recordTrackProcessingTime(String trackName, long processingTimeNanos) {
        Objects.requireNonNull(trackName, "trackName must not be null");
        double loadPercent = (processingTimeNanos / bufferDurationNanos) * 100.0;
        pendingTrackMetrics.add(new TrackPerformanceMetrics(trackName, loadPercent));
    }

    // Accumulator for per-track metrics within a single buffer cycle
    private final List<TrackPerformanceMetrics> pendingTrackMetrics = new ArrayList<>();

    /**
     * Publishes the accumulated per-track metrics so they are visible to
     * UI readers via {@link #getTrackMetrics()}. Also clears the pending
     * accumulator for the next buffer cycle.
     */
    public void commitTrackMetrics() {
        this.trackMetrics = List.copyOf(pendingTrackMetrics);
        pendingTrackMetrics.clear();
    }

    /**
     * Returns the most recently committed per-track performance metrics.
     *
     * <p>Safe to call from the UI thread.</p>
     *
     * @return an unmodifiable list of per-track metrics
     */
    public List<TrackPerformanceMetrics> getTrackMetrics() {
        return trackMetrics;
    }

    /**
     * Returns an immutable snapshot of the current performance state.
     *
     * <p>Safe to call from the UI thread.</p>
     *
     * @return the current performance metrics
     */
    public PerformanceMetrics snapshot() {
        return buildSnapshot(this.cpuLoadPercent);
    }

    /**
     * Returns the current CPU load as a percentage (0.0–100.0).
     *
     * @return the CPU load
     */
    public double getCpuLoadPercent() {
        return cpuLoadPercent;
    }

    /**
     * Returns the total number of buffer underruns detected.
     *
     * @return the underrun count
     */
    public long getUnderrunCount() {
        return underrunCount.get();
    }

    /**
     * Returns whether the CPU load warning is currently active.
     *
     * @return {@code true} if the warning is active
     */
    public boolean isWarningActive() {
        return warningActive;
    }

    /**
     * Resets the underrun counter and CPU load measurements.
     */
    public void reset() {
        cpuLoadPercent = 0.0;
        underrunCount.set(0);
        warningActive = false;
        pendingTrackMetrics.clear();
        trackMetrics = Collections.emptyList();
    }

    private PerformanceMetrics buildSnapshot(double load) {
        return new PerformanceMetrics(
                load,
                format.bufferSize(),
                bufferLatencyMs,
                format.sampleRate(),
                underrunCount.get(),
                warningThresholdPercent,
                warningActive
        );
    }
}
