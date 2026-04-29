package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;
import java.util.Optional;

/**
 * Driver round-trip latency calibration — plays an impulse from the output,
 * captures it from a designated input (loopback or measurement microphone),
 * measures the actual round-trip in sample frames, and reports the delta
 * versus the driver-reported value.
 *
 * <p>Most professional drivers report latency that is accurate to the
 * sample for a given (buffer-size, sample-rate) pair. A few — especially
 * consumer-grade USB interfaces and class-compliant drivers — under- or
 * over-report. This tool surfaces the mismatch so the application layer
 * can offer a "Driver-reported latency may be off by N samples"
 * notification and apply a per-device override stored in
 * {@code ~/.daw/audio-settings.json}.</p>
 *
 * <p>The calibration algorithm is intentionally simple: it scans the
 * captured buffer for the first sample whose absolute amplitude exceeds
 * a fraction of the impulse peak, taking that sample's index as the
 * round-trip latency in frames. Snap is to the nearest frame —
 * sub-sample compensation is out of scope per the issue's non-goals.</p>
 */
public final class LatencyCalibration {

    /**
     * Default delta threshold (in sample frames) above which the
     * application layer surfaces a "driver-reported latency may be off"
     * notification. The issue specifies 64 frames as the trigger.
     */
    public static final int DEFAULT_NOTIFICATION_THRESHOLD_FRAMES = 64;

    /**
     * Result of a calibration run.
     *
     * @param measuredFrames the actual round-trip the impulse traversed,
     *                       in sample frames (always {@code >= 0})
     * @param reportedFrames the driver's reported total round-trip the
     *                       calibration was compared against
     * @param impulseFound   {@code true} when the captured signal
     *                       contained a detectable impulse; {@code false}
     *                       when the buffer was silent (silent input,
     *                       muted loopback, or wiring mistake) — in which
     *                       case {@code measuredFrames} is {@code 0} and
     *                       callers should not apply any override
     */
    public record CalibrationResult(int measuredFrames,
                                    int reportedFrames,
                                    boolean impulseFound) {
        public CalibrationResult {
            if (measuredFrames < 0) {
                throw new IllegalArgumentException(
                        "measuredFrames must be >= 0: " + measuredFrames);
            }
            if (reportedFrames < 0) {
                throw new IllegalArgumentException(
                        "reportedFrames must be >= 0: " + reportedFrames);
            }
        }

        /**
         * Returns {@code measuredFrames - reportedFrames}. Positive means
         * the driver under-reported (real latency is greater); negative
         * means the driver over-reported.
         *
         * @return signed delta in frames
         */
        public int deltaFrames() {
            return measuredFrames - reportedFrames;
        }

        /**
         * Returns whether the absolute delta exceeds {@code threshold}
         * — the trigger the application layer uses to surface the
         * "Driver-reported latency may be off by N samples" notification.
         *
         * @param thresholdFrames the maximum tolerable delta in frames
         *                        (must be {@code >= 0})
         * @return {@code true} when {@code |deltaFrames()| > thresholdFrames}
         *         and an impulse was detected
         */
        public boolean shouldNotify(int thresholdFrames) {
            if (thresholdFrames < 0) {
                throw new IllegalArgumentException(
                        "thresholdFrames must be >= 0: " + thresholdFrames);
            }
            return impulseFound && Math.abs(deltaFrames()) > thresholdFrames;
        }

        /**
         * Returns the override (in frames) the application layer should
         * persist per device when the user accepts the calibration. Empty
         * when no impulse was detected — callers should not persist a
         * misleading zero in that case.
         *
         * @return optional override frames
         */
        public Optional<Integer> overrideFrames() {
            return impulseFound ? Optional.of(measuredFrames) : Optional.empty();
        }
    }

    private LatencyCalibration() {
        // Utility class — calibrate via the static methods.
    }

    /**
     * Measures the round-trip latency of the given captured buffer.
     *
     * <p>The buffer is expected to contain the result of playing a
     * single-sample impulse from the output and capturing it from a
     * designated input. The detector returns the index of the first
     * sample whose magnitude exceeds {@code threshold * peakMagnitude}
     * — i.e. the leading edge of the impulse. This index, in sample
     * frames, is the round-trip latency.</p>
     *
     * <p>If the buffer is silent (peak magnitude below
     * {@code silenceFloor}) the result is reported with
     * {@code impulseFound = false} and the application should treat
     * the calibration as inconclusive — the user typically has the
     * loopback cable disconnected, the input gain at zero, or the wrong
     * input channel selected.</p>
     *
     * @param capturedSamples mono captured buffer (must not be null);
     *                        typically a few hundred ms of audio
     * @param reportedFrames  the driver's reported total round-trip in
     *                        frames at the time of calibration (must
     *                        be {@code >= 0})
     * @param silenceFloor    minimum peak magnitude to consider the
     *                        capture non-silent (in [0,1]); typical
     *                        value: {@code 0.01}
     * @param leadingEdgeRatio fraction of the peak that defines the
     *                        leading edge of the impulse (in (0,1]);
     *                        typical value: {@code 0.5}
     * @return the calibration result
     */
    public static CalibrationResult measure(float[] capturedSamples,
                                            int reportedFrames,
                                            double silenceFloor,
                                            double leadingEdgeRatio) {
        Objects.requireNonNull(capturedSamples, "capturedSamples must not be null");
        if (reportedFrames < 0) {
            throw new IllegalArgumentException(
                    "reportedFrames must be >= 0: " + reportedFrames);
        }
        if (silenceFloor < 0 || silenceFloor > 1) {
            throw new IllegalArgumentException(
                    "silenceFloor must be in [0,1]: " + silenceFloor);
        }
        if (!(leadingEdgeRatio > 0) || leadingEdgeRatio > 1) {
            throw new IllegalArgumentException(
                    "leadingEdgeRatio must be in (0,1]: " + leadingEdgeRatio);
        }

        // First pass: find the peak magnitude. We need the peak so the
        // leading-edge detector knows what fraction of "loud" looks like.
        float peak = 0f;
        for (float s : capturedSamples) {
            float mag = Math.abs(s);
            if (mag > peak) peak = mag;
        }

        if (peak < silenceFloor) {
            return new CalibrationResult(0, reportedFrames, false);
        }

        float threshold = (float) (peak * leadingEdgeRatio);
        // Second pass: leading-edge detection. The first sample whose
        // magnitude crosses `threshold` is the leading edge of the
        // captured impulse, and its index is the round-trip in frames.
        int leadingEdge = 0;
        for (int i = 0; i < capturedSamples.length; i++) {
            if (Math.abs(capturedSamples[i]) >= threshold) {
                leadingEdge = i;
                break;
            }
        }
        return new CalibrationResult(leadingEdge, reportedFrames, true);
    }

    /**
     * Convenience overload using the default detector parameters
     * ({@code silenceFloor = 0.01}, {@code leadingEdgeRatio = 0.5}).
     *
     * @param capturedSamples mono captured buffer
     * @param reportedFrames  the driver's reported total round-trip
     * @return the calibration result
     */
    public static CalibrationResult measure(float[] capturedSamples, int reportedFrames) {
        return measure(capturedSamples, reportedFrames, 0.01, 0.5);
    }

    /**
     * Generates a single-sample impulse buffer of the given length —
     * the signal the calibration tool plays out for the round-trip
     * measurement. Sample {@code 0} is {@code 1.0f} (full-scale
     * positive); all subsequent samples are zero.
     *
     * @param frames length of the impulse buffer in sample frames
     *               (must be positive)
     * @return a new mono impulse buffer
     */
    public static float[] generateImpulse(int frames) {
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        float[] impulse = new float[frames];
        impulse[0] = 1.0f;
        return impulse;
    }
}
