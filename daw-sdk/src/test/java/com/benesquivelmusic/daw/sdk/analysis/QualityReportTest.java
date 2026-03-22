package com.benesquivelmusic.daw.sdk.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityReportTest {

    @Test
    void shouldPassWhenAllMetricsAboveThresholds() {
        var signal = new SignalQualityMetrics(60.0, 0.1, -60.0, 10.0);
        var spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        var stereo = new StereoQualityMetrics(0.9, 0.8, 0.95);
        var dynamicRange = new DynamicRangeMetrics(10.0, 12.0);

        var report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isTrue();
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void shouldFailWhenSnrBelowThreshold() {
        var signal = new SignalQualityMetrics(10.0, 0.1, -60.0, 10.0);
        var spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        var stereo = new StereoQualityMetrics(0.9, 0.8, 0.95);
        var dynamicRange = new DynamicRangeMetrics(10.0, 12.0);

        var report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("SNR"));
    }

    @Test
    void shouldFailWhenThdAboveThreshold() {
        var signal = new SignalQualityMetrics(60.0, 5.0, -26.0, 10.0);
        var spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        var stereo = new StereoQualityMetrics(0.9, 0.8, 0.95);
        var dynamicRange = new DynamicRangeMetrics(10.0, 12.0);

        var report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("THD"));
    }

    @Test
    void shouldFailWhenCorrelationBelowThreshold() {
        var signal = new SignalQualityMetrics(60.0, 0.1, -60.0, 10.0);
        var spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        var stereo = new StereoQualityMetrics(-0.8, 0.8, 0.1);
        var dynamicRange = new DynamicRangeMetrics(10.0, 12.0);

        var report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("correlation"));
    }

    @Test
    void shouldFailWhenDynamicRangeBelowThreshold() {
        var signal = new SignalQualityMetrics(60.0, 0.1, -60.0, 10.0);
        var spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        var stereo = new StereoQualityMetrics(0.9, 0.8, 0.95);
        var dynamicRange = new DynamicRangeMetrics(10.0, 1.0);

        var report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).anyMatch(f -> f.contains("Dynamic range"));
    }

    @Test
    void shouldCollectMultipleFailures() {
        var signal = new SignalQualityMetrics(10.0, 5.0, -26.0, 1.0);
        var spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        var stereo = new StereoQualityMetrics(-0.8, 0.1, 0.1);
        var dynamicRange = new DynamicRangeMetrics(1.0, 1.0);

        var report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.passed()).isFalse();
        assertThat(report.failures().size()).isGreaterThan(3);
    }

    @Test
    void shouldPreserveMetricValues() {
        var signal = new SignalQualityMetrics(60.0, 0.1, -60.0, 10.0);
        var spectral = new SpectralQualityMetrics(0.5, 3000.0, 0.5);
        var stereo = new StereoQualityMetrics(0.9, 0.8, 0.95);
        var dynamicRange = new DynamicRangeMetrics(10.0, 12.0);

        var report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, QualityThresholds.DEFAULT);

        assertThat(report.signalMetrics()).isEqualTo(signal);
        assertThat(report.spectralMetrics()).isEqualTo(spectral);
        assertThat(report.stereoMetrics()).isEqualTo(stereo);
        assertThat(report.dynamicRangeMetrics()).isEqualTo(dynamicRange);
        assertThat(report.thresholds()).isEqualTo(QualityThresholds.DEFAULT);
    }

    @Test
    void failuresListShouldBeUnmodifiable() {
        var report = QualityReport.evaluate(
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
        var lenient = new QualityThresholds(
                0.0, 100.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0);
        var signal = new SignalQualityMetrics(1.0, 50.0, -6.0, 0.5);
        var spectral = new SpectralQualityMetrics(0.0, 100.0, 0.0);
        var stereo = new StereoQualityMetrics(-0.9, 0.0, 0.05);
        var dynamicRange = new DynamicRangeMetrics(0.5, 0.5);

        var report = QualityReport.evaluate(signal, spectral, stereo,
                dynamicRange, lenient);

        assertThat(report.passed()).isTrue();
    }
}
