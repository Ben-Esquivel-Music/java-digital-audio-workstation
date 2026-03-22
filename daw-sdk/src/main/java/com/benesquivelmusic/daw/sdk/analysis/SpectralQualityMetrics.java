package com.benesquivelmusic.daw.sdk.analysis;

/**
 * Spectral quality metrics for audio analysis.
 *
 * <p>Evaluates the frequency-domain characteristics of audio including
 * spectral flatness (tonality), spectral centroid (brightness), and
 * bandwidth utilization. These metrics support the quality assessment
 * framework described in AES research on perceptual audio quality.</p>
 *
 * @param spectralFlatness        spectral flatness in [0, 1]; 1 = white noise, 0 = pure tone
 * @param spectralCentroidHz      spectral centroid frequency in Hz (brightness indicator)
 * @param bandwidthUtilization    fraction of the audible spectrum with significant energy, in [0, 1]
 */
public record SpectralQualityMetrics(
        double spectralFlatness,
        double spectralCentroidHz,
        double bandwidthUtilization
) {

    /** Metrics for a silent signal. */
    public static final SpectralQualityMetrics SILENCE =
            new SpectralQualityMetrics(0.0, 0.0, 0.0);
}
