package com.benesquivelmusic.daw.sdk.analysis;

/**
 * Stereo quality metrics for audio analysis.
 *
 * <p>Evaluates the stereo field characteristics including correlation,
 * stereo width, and mono compatibility. These metrics correspond to the
 * stereo processing quality evaluation described in AES research on the
 * Open Dataset of Audio Quality (ODAQ) framework.</p>
 *
 * @param correlationCoefficient  stereo correlation in [-1, 1]; 1 = mono, -1 = out of phase
 * @param stereoWidthConsistency  consistency of stereo width over the analysis window, in [0, 1]
 * @param monoCompatibilityScore  mono fold-down compatibility score, in [0, 1]; 1 = fully compatible
 */
public record StereoQualityMetrics(
        double correlationCoefficient,
        double stereoWidthConsistency,
        double monoCompatibilityScore
) {

    /** Metrics for a silent signal (perfect mono compatibility). */
    public static final StereoQualityMetrics SILENCE =
            new StereoQualityMetrics(1.0, 1.0, 1.0);
}
