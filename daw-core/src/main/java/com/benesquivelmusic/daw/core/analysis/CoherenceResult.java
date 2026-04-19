package com.benesquivelmusic.daw.core.analysis;

/**
 * Immutable result of a magnitude-squared coherence analysis between a signal
 * chain's input and output.
 *
 * <p>Coherence drops below 1.0 at frequencies where nonlinear distortion,
 * noise, or other non-correlated components are introduced by the signal
 * path. Values close to 1.0 indicate a linear, distortion-free response at
 * that frequency; values near 0.0 indicate heavy distortion or uncorrelated
 * energy.</p>
 *
 * <p>Based on Hinton &amp; Wagstaff, "Coherence as an Indicator of Distortion
 * for Wide-Band Audio Signals such as M-Noise and Music" (AES, 2019).</p>
 *
 * @param coherence          magnitude-squared coherence per frequency bin;
 *                           values in {@code [0.0, 1.0]}. The array length
 *                           equals {@code segmentSize / 2 + 1} (single-sided
 *                           spectrum including Nyquist).
 * @param frequencies        center frequency (Hz) for each bin in
 *                           {@code coherence}; same length as {@code coherence}.
 * @param meanCoherence      unweighted mean coherence across the analysis
 *                           bandwidth (excluding DC); in {@code [0.0, 1.0]}.
 *                           A value of 1.0 means the output is a perfectly
 *                           linear function of the input.
 * @param distortionIndicator a scalar distortion indicator derived as
 *                           {@code 1.0 - meanCoherence}; 0.0 means perfectly
 *                           linear, 1.0 means fully distorted/uncorrelated.
 * @param numSegments        the number of overlapping Welch segments averaged
 *                           to produce the estimate.
 */
public record CoherenceResult(
        double[] coherence,
        double[] frequencies,
        double meanCoherence,
        double distortionIndicator,
        int numSegments) {

    public CoherenceResult {
        if (coherence == null) {
            throw new IllegalArgumentException("coherence must not be null");
        }
        if (frequencies == null) {
            throw new IllegalArgumentException("frequencies must not be null");
        }
        if (coherence.length != frequencies.length) {
            throw new IllegalArgumentException(
                    "coherence and frequencies must have the same length: "
                            + coherence.length + " vs " + frequencies.length);
        }
        if (meanCoherence < 0.0 || meanCoherence > 1.0) {
            throw new IllegalArgumentException(
                    "meanCoherence must be in [0.0, 1.0]: " + meanCoherence);
        }
        if (distortionIndicator < 0.0 || distortionIndicator > 1.0) {
            throw new IllegalArgumentException(
                    "distortionIndicator must be in [0.0, 1.0]: " + distortionIndicator);
        }
        if (numSegments < 0) {
            throw new IllegalArgumentException(
                    "numSegments must be non-negative: " + numSegments);
        }
    }
}
