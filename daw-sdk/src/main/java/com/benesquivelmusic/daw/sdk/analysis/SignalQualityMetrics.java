package com.benesquivelmusic.daw.sdk.analysis;

/**
 * Basic objective signal quality metrics.
 *
 * <p>Contains fundamental measurements of audio signal quality including
 * signal-to-noise ratio, total harmonic distortion, and crest factor.
 * These metrics correspond to the audio quality metrics toolbox described
 * in AES research on media assets management and content exchange.</p>
 *
 * @param snrDb          signal-to-noise ratio in dB (higher is better)
 * @param thdPercent     total harmonic distortion as a percentage (lower is better)
 * @param thdDb          total harmonic distortion in dB (more negative is better)
 * @param crestFactorDb  crest factor (peak-to-RMS ratio) in dB
 */
public record SignalQualityMetrics(
        double snrDb,
        double thdPercent,
        double thdDb,
        double crestFactorDb
) {

    /** Metrics for a silent signal. */
    public static final SignalQualityMetrics SILENCE =
            new SignalQualityMetrics(0.0, 0.0, -120.0, 0.0);
}
