package com.benesquivelmusic.daw.sdk.analysis;

/**
 * Dynamic range metrics for audio analysis.
 *
 * <p>Evaluates the dynamic characteristics of audio including the
 * peak-to-loudness ratio (PLR) and dynamic range (DR) score. These
 * correspond to the dynamics assessment described in AES research on
 * audio quality metrics toolboxes.</p>
 *
 * @param plrDb    peak-to-loudness ratio in dB (difference between peak and integrated loudness)
 * @param drScore  dynamic range score in dB, computed as the difference between
 *                 peak RMS and the RMS of the quietest sections
 */
public record DynamicRangeMetrics(
        double plrDb,
        double drScore
) {

    /** Metrics for a silent signal. */
    public static final DynamicRangeMetrics SILENCE =
            new DynamicRangeMetrics(0.0, 0.0);
}
