package com.benesquivelmusic.daw.sdk.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregated audio quality report containing all metric categories with
 * pass/fail evaluation against configurable thresholds.
 *
 * <p>Produced by {@link QualityAnalyzer#analyze} and used for pre-export
 * quality validation. Each metric category can be individually inspected,
 * and the overall pass/fail is determined by the supplied
 * {@link QualityThresholds}.</p>
 *
 * @param signalMetrics     basic objective signal quality metrics
 * @param spectralMetrics   spectral quality metrics
 * @param stereoMetrics     stereo quality metrics
 * @param dynamicRangeMetrics dynamic range metrics
 * @param thresholds        thresholds used for pass/fail evaluation
 * @param failures          list of human-readable failure descriptions (empty if all pass)
 */
public record QualityReport(
        SignalQualityMetrics signalMetrics,
        SpectralQualityMetrics spectralMetrics,
        StereoQualityMetrics stereoMetrics,
        DynamicRangeMetrics dynamicRangeMetrics,
        QualityThresholds thresholds,
        List<String> failures
) {

    /**
     * Creates a quality report and validates all metrics against the thresholds.
     */
    public QualityReport {
        failures = Collections.unmodifiableList(new ArrayList<>(failures));
    }

    /**
     * Returns {@code true} if all metrics pass the configured thresholds.
     *
     * @return overall pass/fail
     */
    public boolean passed() {
        return failures.isEmpty();
    }

    /**
     * Evaluates the given metrics against the thresholds and returns a report.
     *
     * @param signal          signal quality metrics
     * @param spectral        spectral quality metrics
     * @param stereo          stereo quality metrics
     * @param dynamicRange    dynamic range metrics
     * @param thresholds      pass/fail thresholds
     * @return a quality report with failures populated
     */
    public static QualityReport evaluate(
            SignalQualityMetrics signal,
            SpectralQualityMetrics spectral,
            StereoQualityMetrics stereo,
            DynamicRangeMetrics dynamicRange,
            QualityThresholds thresholds) {

        var failures = new ArrayList<String>();

        if (signal.snrDb() < thresholds.minSnrDb()) {
            failures.add(String.format("SNR %.1f dB below minimum %.1f dB",
                    signal.snrDb(), thresholds.minSnrDb()));
        }
        if (signal.thdPercent() > thresholds.maxThdPercent()) {
            failures.add(String.format("THD %.2f%% exceeds maximum %.2f%%",
                    signal.thdPercent(), thresholds.maxThdPercent()));
        }
        if (signal.crestFactorDb() < thresholds.minCrestFactorDb()) {
            failures.add(String.format("Crest factor %.1f dB below minimum %.1f dB",
                    signal.crestFactorDb(), thresholds.minCrestFactorDb()));
        }
        if (spectral.spectralFlatness() < thresholds.minSpectralFlatness()) {
            failures.add(String.format("Spectral flatness %.3f below minimum %.3f",
                    spectral.spectralFlatness(), thresholds.minSpectralFlatness()));
        }
        if (spectral.bandwidthUtilization() < thresholds.minBandwidthUtilization()) {
            failures.add(String.format("Bandwidth utilization %.1f%% below minimum %.1f%%",
                    spectral.bandwidthUtilization() * 100, thresholds.minBandwidthUtilization() * 100));
        }
        if (stereo.correlationCoefficient() < thresholds.minCorrelationCoefficient()) {
            failures.add(String.format("Stereo correlation %.2f below minimum %.2f",
                    stereo.correlationCoefficient(), thresholds.minCorrelationCoefficient()));
        }
        if (stereo.stereoWidthConsistency() < thresholds.minStereoWidthConsistency()) {
            failures.add(String.format("Stereo width consistency %.2f below minimum %.2f",
                    stereo.stereoWidthConsistency(), thresholds.minStereoWidthConsistency()));
        }
        if (stereo.monoCompatibilityScore() < thresholds.minMonoCompatibilityScore()) {
            failures.add(String.format("Mono compatibility %.2f below minimum %.2f",
                    stereo.monoCompatibilityScore(), thresholds.minMonoCompatibilityScore()));
        }
        if (dynamicRange.plrDb() < thresholds.minPlrDb()) {
            failures.add(String.format("PLR %.1f dB below minimum %.1f dB",
                    dynamicRange.plrDb(), thresholds.minPlrDb()));
        }
        if (dynamicRange.drScore() < thresholds.minDrScore()) {
            failures.add(String.format("Dynamic range %.1f dB below minimum %.1f dB",
                    dynamicRange.drScore(), thresholds.minDrScore()));
        }

        return new QualityReport(signal, spectral, stereo, dynamicRange, thresholds, failures);
    }
}
