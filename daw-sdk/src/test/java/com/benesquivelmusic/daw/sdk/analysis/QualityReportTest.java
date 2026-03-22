package com.benesquivelmusic.daw.sdk.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityReportTest {

    @Test
    void shouldPassWhenAllMetricsAboveThresholds() {
        SignalQualityMetrics signal = new SignalQualityMetrics(60.0, 0.1, -60.0, 10.0);
        SpectralQualityMetrics spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        StereoQualityMetrics stereo = new StereoQualityMetrics(0.9, 0.8, 0.95);
        DynamicRangeMetrics dynamicRange = new DynamicRangeMetrics(10.0, 12.0);

        QualityReport report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isTrue();
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void shouldFailWhenSnrBelowThreshold() {
        SignalQualityMetrics signal = new SignalQualityMetrics(10.0, 0.1, -60.0, 10.0);
        SpectralQualityMetrics spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        StereoQualityMetrics stereo = new StereoQualityMetrics(0.9, 0.8, 0.95);
        DynamicRangeMetrics dynamicRange = new DynamicRangeMetrics(10.0, 12.0);

        QualityReport report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("SNR"));
    }

    @Test
    void shouldFailWhenThdAboveThreshold() {
        SignalQualityMetrics signal = new SignalQualityMetrics(60.0, 5.0, -26.0, 10.0);
        SpectralQualityMetrics spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        StereoQualityMetrics stereo = new StereoQualityMetrics(0.9, 0.8, 0.95);
        DynamicRangeMetrics dynamicRange = new DynamicRangeMetrics(10.0, 12.0);

        QualityReport report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("THD"));
    }

    @Test
    void shouldFailWhenCorrelationBelowThreshold() {
        SignalQualityMetrics signal = new SignalQualityMetrics(60.0, 0.1, -60.0, 10.0);
        SpectralQualityMetrics spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        StereoQualityMetrics stereo = new StereoQualityMetrics(-0.8, 0.8, 0.1);
        DynamicRangeMetrics dynamicRange = new DynamicRangeMetrics(10.0, 12.0);

        QualityReport report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("correlation"));
    }

    @Test
    void shouldFailWhenDynamicRangeBelowThreshold() {
        SignalQualityMetrics signal = new SignalQualityMetrics(60.0, 0.1, -60.0, 10.0);
        SpectralQualityMetrics spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        StereoQualityMetrics stereo = new StereoQualityMetrics(0.9, 0.8, 0.95);
        DynamicRangeMetrics dynamicRange = new DynamicRangeMetrics(10.0, 1.0);

        QualityReport report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("Dynamic range"));
    }

    @Test
    void shouldCollectMultipleFailures() {
        SignalQualityMetrics signal = new SignalQualityMetrics(10.0, 5.0, -26.0, 1.0);
        SpectralQualityMetrics spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        StereoQualityMetrics stereo = new StereoQualityMetrics(-0.8, 0.1, 0.1);
        DynamicRangeMetrics dynamicRange = new DynamicRangeMetrics(1.0, 1.0);

        QualityReport report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isFalse();
        assertThat(report.failures().size()).isGreaterThan(3);
    }

    @Test
    void shouldPreserveMetricValues() {
        SignalQualityMetrics signal = new SignalQualityMetrics(60.0, 0.1, -60.0, 10.0);
        SpectralQualityMetrics spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        StereoQualityMetrics stereo = new StereoQualityMetrics(0.9, 0.8, 0.95);
        DynamicRangeMetrics dynamicRange = new DynamicRangeMetrics(10.0, 12.0);

        QualityReport report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.signalMetrics()).isEqualTo(signal);
        assertThat(report.spectralMetrics()).isEqualTo(spectral);
        assertThat(report.stereoMetrics()).isEqualTo(stereo);
        assertThat(report.dynamicRangeMetrics()).isEqualTo(dynamicRange);
        assertThat(report.thresholds()).isEqualTo(QualityThresholds.DEFAULT);
    }

    @Test
    void failuresListShouldBeUnmodifiable() {
        QualityReport report = QualityReport.evaluate(
                SignalQualityMetrics.SILENCE,
                SpectralQualityMetrics.SILENCE,
                StereoQualityMetrics.SILENCE,
                DynamicRangeMetrics.SILENCE,
                QualityThresholds.DEFAULT);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> report.failures().add("should fail"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldUseCustomThresholds() {
        QualityThresholds lenient = new QualityThresholds(
                0.0, 100.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0);
        SignalQualityMetrics signal = new SignalQualityMetrics(1.0, 50.0, -6.0, 0.5);
        SpectralQualityMetrics spectral = new SpectralQualityMetrics(0.0, 100.0, 0.0);
        StereoQualityMetrics stereo = new StereoQualityMetrics(-0.9, 0.0, 0.05);
        DynamicRangeMetrics dynamicRange = new DynamicRangeMetrics(0.5, 0.5);

        QualityReport report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, lenient);

        assertThat(report.passed()).isTrue();
    }
}
