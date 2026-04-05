package com.benesquivelmusic.daw.core.analysis.quality;

import com.benesquivelmusic.daw.sdk.analysis.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class DefaultQualityAnalyzerTest {

    private final DefaultQualityAnalyzer analyzer = new DefaultQualityAnalyzer();

    // ----------------------------------------------------------------
    // Signal quality metric tests
    // ----------------------------------------------------------------

    @Test
    void shouldMeasureCrestFactorForSineWave() {
        // Pure sine wave: crest factor = √2 ≈ 3.01 dB
        float[] sine = generateSineWave(440.0, 44100.0, 44100);
        SignalQualityMetrics metrics = analyzer.analyzeSignalQuality(sine, sine.length);

        assertThat(metrics.crestFactorDb()).isCloseTo(3.01, offset(0.5));
    }

    @Test
    void shouldMeasureCrestFactorForSquareWave() {
        // Square wave: crest factor = 1.0 → 0 dB
        float[] square = new float[44100];
        for (int i = 0; i < square.length; i++) {
            square[i] = (i % 100 < 50) ? 0.8f : -0.8f;
        }
        SignalQualityMetrics metrics = analyzer.analyzeSignalQuality(square, square.length);

        assertThat(metrics.crestFactorDb()).isCloseTo(0.0, offset(0.5));
    }

    @Test
    void shouldMeasureLowThdForPureSine() {
        float[] sine = generateSineWave(440.0, 44100.0, 44100);
        SignalQualityMetrics metrics = analyzer.analyzeSignalQuality(sine, sine.length);

        // Pure sine has negligible THD (windowing leakage only)
        assertThat(metrics.thdPercent()).isLessThan(5.0);
    }

    @Test
    void shouldMeasureHigherThdForDistortedSignal() {
        // Clipped sine → introduces harmonics
        float[] clipped = generateSineWave(440.0, 44100.0, 44100);
        for (int i = 0; i < clipped.length; i++) {
            clipped[i] = Math.max(-0.5f, Math.min(0.5f, clipped[i]));
        }
        SignalQualityMetrics metrics = analyzer.analyzeSignalQuality(clipped, clipped.length);

        // Clipped sine should have measurable harmonics
        assertThat(metrics.thdPercent()).isGreaterThan(0.0);
    }

    @Test
    void shouldMeasurePositiveSnrForSignalWithNoise() {
        float[] signal = generateSineWave(440.0, 44100.0, 44100);
        SignalQualityMetrics metrics = analyzer.analyzeSignalQuality(signal, signal.length);

        assertThat(metrics.snrDb()).isGreaterThan(0.0);
    }

    @Test
    void shouldReturnSilenceMetricsForEmptyBuffer() {
        SignalQualityMetrics metrics = analyzer.analyzeSignalQuality(new float[0], 0);
        assertThat(metrics).isEqualTo(SignalQualityMetrics.SILENCE);
    }

    // ----------------------------------------------------------------
    // Spectral quality metric tests
    // ----------------------------------------------------------------

    @Test
    void shouldMeasureLowSpectralFlatnessForPureTone() {
        float[] sine = generateSineWave(1000.0, 44100.0, 44100);
        SpectralQualityMetrics metrics = analyzer.analyzeSpectralQuality(
                sine, sine.length, 44100.0);

        // Pure tone should have low spectral flatness (< 0.1)
        assertThat(metrics.spectralFlatness()).isLessThan(0.1);
    }

    @Test
    void shouldMeasureHighSpectralFlatnessForNoise() {
        // White noise approximation
        float[] noise = new float[44100];
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (rng.nextFloat() * 2.0f - 1.0f) * 0.5f;
        }
        SpectralQualityMetrics metrics = analyzer.analyzeSpectralQuality(
                noise, noise.length, 44100.0);

        // White noise should have high spectral flatness (> 0.5)
        assertThat(metrics.spectralFlatness()).isGreaterThan(0.5);
    }

    @Test
    void shouldMeasureSpectralCentroidForLowFrequency() {
        float[] bass = generateSineWave(100.0, 44100.0, 44100);
        SpectralQualityMetrics metrics = analyzer.analyzeSpectralQuality(
                bass, bass.length, 44100.0);

        // Centroid should be near the fundamental frequency
        assertThat(metrics.spectralCentroidHz()).isCloseTo(100.0, offset(50.0));
    }

    @Test
    void shouldMeasureSpectralCentroidForHighFrequency() {
        float[] treble = generateSineWave(8000.0, 44100.0, 44100);
        SpectralQualityMetrics metrics = analyzer.analyzeSpectralQuality(
                treble, treble.length, 44100.0);

        assertThat(metrics.spectralCentroidHz()).isCloseTo(8000.0, offset(200.0));
    }

    @Test
    void shouldMeasureBandwidthUtilization() {
        float[] sine = generateSineWave(1000.0, 44100.0, 44100);
        SpectralQualityMetrics metrics = analyzer.analyzeSpectralQuality(
                sine, sine.length, 44100.0);

        // Pure tone occupies very little bandwidth
        assertThat(metrics.bandwidthUtilization()).isLessThan(0.1);
    }

    @Test
    void noiseShouldHaveHighBandwidthUtilization() {
        float[] noise = new float[44100];
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (rng.nextFloat() * 2.0f - 1.0f) * 0.5f;
        }
        SpectralQualityMetrics metrics = analyzer.analyzeSpectralQuality(
                noise, noise.length, 44100.0);

        assertThat(metrics.bandwidthUtilization()).isGreaterThan(0.3);
    }

    @Test
    void shouldReturnSilenceSpectralMetricsForEmptyBuffer() {
        SpectralQualityMetrics metrics = analyzer.analyzeSpectralQuality(
                new float[0], 0, 44100.0);
        assertThat(metrics).isEqualTo(SpectralQualityMetrics.SILENCE);
    }

    // ----------------------------------------------------------------
    // Stereo quality metric tests
    // ----------------------------------------------------------------

    @Test
    void shouldMeasureFullCorrelationForMonoSignal() {
        float[] signal = generateSineWave(440.0, 44100.0, 44100);
        StereoQualityMetrics metrics = analyzer.analyzeStereoQuality(
                signal, signal, signal.length);

        assertThat(metrics.correlationCoefficient()).isCloseTo(1.0, offset(0.01));
    }

    @Test
    void shouldMeasureNegativeCorrelationForInvertedSignal() {
        float[] left = generateSineWave(440.0, 44100.0, 44100);
        float[] right = new float[left.length];
        for (int i = 0; i < left.length; i++) {
            right[i] = -left[i];
        }
        StereoQualityMetrics metrics = analyzer.analyzeStereoQuality(
                left, right, left.length);

        assertThat(metrics.correlationCoefficient()).isCloseTo(-1.0, offset(0.01));
    }

    @Test
    void shouldMeasureHighMonoCompatibilityForMonoSignal() {
        float[] signal = generateSineWave(440.0, 44100.0, 44100);
        StereoQualityMetrics metrics = analyzer.analyzeStereoQuality(
                signal, signal, signal.length);

        assertThat(metrics.monoCompatibilityScore()).isCloseTo(1.0, offset(0.01));
    }

    @Test
    void shouldMeasureLowMonoCompatibilityForInvertedSignal() {
        float[] left = generateSineWave(440.0, 44100.0, 44100);
        float[] right = new float[left.length];
        for (int i = 0; i < left.length; i++) {
            right[i] = -left[i];
        }
        StereoQualityMetrics metrics = analyzer.analyzeStereoQuality(
                left, right, left.length);

        assertThat(metrics.monoCompatibilityScore()).isCloseTo(0.0, offset(0.01));
    }

    @Test
    void shouldMeasureHighStereoWidthConsistencyForConstantWidth() {
        float[] left = generateSineWave(440.0, 44100.0, 44100);
        float[] right = generateSineWave(440.0, 44100.0, 44100);
        // Apply constant stereo offset
        for (int i = 0; i < right.length; i++) {
            right[i] *= 0.7f;
        }
        StereoQualityMetrics metrics = analyzer.analyzeStereoQuality(
                left, right, left.length);

        assertThat(metrics.stereoWidthConsistency()).isGreaterThan(0.5);
    }

    @Test
    void shouldReturnSilenceStereoMetricsForEmptyBuffer() {
        StereoQualityMetrics metrics = analyzer.analyzeStereoQuality(
                new float[0], new float[0], 0);
        assertThat(metrics).isEqualTo(StereoQualityMetrics.SILENCE);
    }

    // ----------------------------------------------------------------
    // Dynamic range metric tests
    // ----------------------------------------------------------------

    @Test
    void shouldMeasurePlrForSineWave() {
        // Sine wave PLR ≈ 3.01 dB (same as crest factor)
        float[] sine = generateSineWave(440.0, 44100.0, 44100);
        DynamicRangeMetrics metrics = analyzer.analyzeDynamicRange(sine, sine.length);

        assertThat(metrics.plrDb()).isCloseTo(3.01, offset(0.5));
    }

    @Test
    void shouldMeasureZeroPlrForSquareWave() {
        float[] square = new float[44100];
        for (int i = 0; i < square.length; i++) {
            square[i] = (i % 100 < 50) ? 0.8f : -0.8f;
        }
        DynamicRangeMetrics metrics = analyzer.analyzeDynamicRange(square, square.length);

        assertThat(metrics.plrDb()).isCloseTo(0.0, offset(0.5));
    }

    @Test
    void shouldMeasureHighDrScoreForDynamicSignal() {
        // Signal that alternates between loud and quiet sections
        float[] dynamic = new float[44100];
        for (int i = 0; i < dynamic.length; i++) {
            float amplitude = (i < dynamic.length / 2) ? 0.9f : 0.05f;
            dynamic[i] = (float) (amplitude * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        DynamicRangeMetrics metrics = analyzer.analyzeDynamicRange(dynamic, dynamic.length);

        assertThat(metrics.drScore()).isGreaterThan(10.0);
    }

    @Test
    void shouldMeasureLowDrScoreForConstantLevelSignal() {
        float[] constant = generateSineWave(440.0, 44100.0, 44100);
        DynamicRangeMetrics metrics = analyzer.analyzeDynamicRange(constant, constant.length);

        // Constant level sine should have low DR score
        assertThat(metrics.drScore()).isLessThan(5.0);
    }

    @Test
    void shouldReturnSilenceDynamicMetricsForEmptyBuffer() {
        DynamicRangeMetrics metrics = analyzer.analyzeDynamicRange(new float[0], 0);
        assertThat(metrics).isEqualTo(DynamicRangeMetrics.SILENCE);
    }

    // ----------------------------------------------------------------
    // Full analysis tests
    // ----------------------------------------------------------------

    @Test
    void shouldProduceFullReportForStereoSine() {
        float[] left = generateSineWave(440.0, 44100.0, 44100);
        float[] right = generateSineWave(440.0, 44100.0, 44100);

        QualityReport report = analyzer.analyze(left, right, left.length, 44100.0);

        assertThat(report.signalMetrics()).isNotNull();
        assertThat(report.spectralMetrics()).isNotNull();
        assertThat(report.stereoMetrics()).isNotNull();
        assertThat(report.dynamicRangeMetrics()).isNotNull();
        assertThat(report.thresholds()).isEqualTo(QualityThresholds.DEFAULT);
    }

    @Test
    void shouldAcceptCustomThresholdsInFullAnalysis() {
        float[] left = generateSineWave(440.0, 44100.0, 44100);
        float[] right = generateSineWave(440.0, 44100.0, 44100);
        QualityThresholds lenient = new QualityThresholds(
                0.0, 100.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0);

        QualityReport report = analyzer.analyze(left, right, left.length, 44100.0, lenient);

        assertThat(report.thresholds()).isEqualTo(lenient);
        assertThat(report.passed()).isTrue();
    }

    @Test
    void shouldUseDefaultThresholdsInConvenienceMethod() {
        float[] left = generateSineWave(440.0, 44100.0, 44100);
        float[] right = generateSineWave(440.0, 44100.0, 44100);

        QualityReport report = analyzer.analyze(left, right, left.length, 44100.0);

        assertThat(report.thresholds()).isEqualTo(QualityThresholds.DEFAULT);
    }

    // ----------------------------------------------------------------
    // Helper methods
    // ----------------------------------------------------------------

    private static float[] generateSineWave(double frequency, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequency * i / sampleRate);
        }
        return samples;
    }
}
