package com.benesquivelmusic.daw.sdk.audio;

/**
 * Reports audio stream latency measurements.
 *
 * @param inputLatencyMs     the input latency in milliseconds
 * @param outputLatencyMs    the output latency in milliseconds
 * @param roundTripLatencyMs the total round-trip latency (input + output) in milliseconds
 * @param bufferSizeFrames   the buffer size in sample frames
 * @param sampleRateHz       the sample rate in Hz
 */
public record LatencyInfo(
        double inputLatencyMs,
        double outputLatencyMs,
        double roundTripLatencyMs,
        int bufferSizeFrames,
        double sampleRateHz
) {

    public LatencyInfo {
        if (inputLatencyMs < 0) {
            throw new IllegalArgumentException("inputLatencyMs must be >= 0: " + inputLatencyMs);
        }
        if (outputLatencyMs < 0) {
            throw new IllegalArgumentException("outputLatencyMs must be >= 0: " + outputLatencyMs);
        }
        if (roundTripLatencyMs < 0) {
            throw new IllegalArgumentException("roundTripLatencyMs must be >= 0: " + roundTripLatencyMs);
        }
    }

    /**
     * Creates a {@code LatencyInfo} where round-trip latency is computed
     * as the sum of input and output latency.
     *
     * @param inputLatencyMs   the input latency in ms
     * @param outputLatencyMs  the output latency in ms
     * @param bufferSizeFrames the buffer size in sample frames
     * @param sampleRateHz     the sample rate in Hz
     * @return a new latency info
     */
    public static LatencyInfo of(double inputLatencyMs, double outputLatencyMs,
                                 int bufferSizeFrames, double sampleRateHz) {
        return new LatencyInfo(
                inputLatencyMs,
                outputLatencyMs,
                inputLatencyMs + outputLatencyMs,
                bufferSizeFrames,
                sampleRateHz
        );
    }
}
