package com.benesquivelmusic.daw.core.performance;

/**
 * Immutable snapshot of the current audio engine performance state.
 *
 * <p>Instances are produced by {@link PerformanceMonitor#snapshot()} and can
 * be safely read from the UI thread while the audio thread continues
 * processing.</p>
 *
 * @param cpuLoadPercent        audio thread CPU utilization as a percentage (0.0–100.0).
 *                              Calculated as the ratio of time spent processing a buffer
 *                              to the available time budget for that buffer.
 * @param bufferSizeFrames      the current audio buffer size in sample frames
 * @param bufferLatencyMs       the buffer latency in milliseconds, derived from
 *                              {@code bufferSizeFrames / sampleRate * 1000}
 * @param sampleRate            the audio sample rate in Hz
 * @param underrunCount         the total number of buffer underruns (dropouts) detected
 *                              since the monitor was started or last reset
 * @param warningThresholdPercent the CPU load threshold (0.0–100.0) above which a
 *                              warning state is triggered
 * @param warning               {@code true} if the current CPU load exceeds the
 *                              warning threshold
 */
public record PerformanceMetrics(
        double cpuLoadPercent,
        int bufferSizeFrames,
        double bufferLatencyMs,
        double sampleRate,
        long underrunCount,
        double warningThresholdPercent,
        boolean warning
) {

    public PerformanceMetrics {
        if (cpuLoadPercent < 0.0) {
            throw new IllegalArgumentException("cpuLoadPercent must be >= 0: " + cpuLoadPercent);
        }
        if (bufferSizeFrames <= 0) {
            throw new IllegalArgumentException("bufferSizeFrames must be positive: " + bufferSizeFrames);
        }
        if (bufferLatencyMs < 0.0) {
            throw new IllegalArgumentException("bufferLatencyMs must be >= 0: " + bufferLatencyMs);
        }
        if (sampleRate <= 0.0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (underrunCount < 0) {
            throw new IllegalArgumentException("underrunCount must be >= 0: " + underrunCount);
        }
        if (warningThresholdPercent < 0.0 || warningThresholdPercent > 100.0) {
            throw new IllegalArgumentException(
                    "warningThresholdPercent must be between 0 and 100: " + warningThresholdPercent);
        }
    }
}
