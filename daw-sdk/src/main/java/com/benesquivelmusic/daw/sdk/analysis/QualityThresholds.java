package com.benesquivelmusic.daw.sdk.analysis;

/**
 * Configurable pass/fail thresholds for audio quality assessment.
 *
 * <p>Defines minimum acceptable values for each quality metric category.
 * Used by {@link QualityReport#passed()} to determine overall pass/fail
 * status. Default thresholds represent professional mastering standards.</p>
 *
 * @param minSnrDb                     minimum acceptable SNR in dB
 * @param maxThdPercent                maximum acceptable THD percentage
 * @param minCrestFactorDb             minimum acceptable crest factor in dB
 * @param minSpectralFlatness          minimum spectral flatness (0 = pure tone acceptable)
 * @param minBandwidthUtilization      minimum bandwidth utilization fraction
 * @param minCorrelationCoefficient    minimum stereo correlation coefficient
 * @param minStereoWidthConsistency    minimum stereo width consistency
 * @param minMonoCompatibilityScore    minimum mono compatibility score
 * @param minPlrDb                     minimum peak-to-loudness ratio in dB
 * @param minDrScore                   minimum dynamic range score in dB
 */
public record QualityThresholds(
        double minSnrDb,
        double maxThdPercent,
        double minCrestFactorDb,
        double minSpectralFlatness,
        double minBandwidthUtilization,
        double minCorrelationCoefficient,
        double minStereoWidthConsistency,
        double minMonoCompatibilityScore,
        double minPlrDb,
        double minDrScore
) {

    /**
     * Default professional mastering thresholds.
     *
     * <p>These represent minimum quality standards for professional
     * audio mastering output.</p>
     */
    public static final QualityThresholds DEFAULT = new QualityThresholds(
            40.0,   // minSnrDb: 40 dB minimum SNR
            1.0,    // maxThdPercent: 1% maximum THD
            3.0,    // minCrestFactorDb: 3 dB minimum crest factor
            0.0,    // minSpectralFlatness: no minimum (tonal content is valid)
            0.1,    // minBandwidthUtilization: at least 10% of spectrum used
            -0.5,   // minCorrelationCoefficient: above phase inversion
            0.3,    // minStereoWidthConsistency: moderate consistency
            0.3,    // minMonoCompatibilityScore: basic mono compatibility
            3.0,    // minPlrDb: 3 dB headroom above loudness
            4.0     // minDrScore: 4 dB minimum dynamic range
    );
}
