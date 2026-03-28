package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.AnalysisMode;
import com.benesquivelmusic.daw.sdk.visualization.CorrelationData;
import com.benesquivelmusic.daw.sdk.visualization.GoniometerData;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationMeterTest {

    @Test
    void shouldInitializeToSilence() {
        CorrelationMeter meter = new CorrelationMeter();
        assertThat(meter.hasData()).isTrue();
        assertThat(meter.getLatestData()).isEqualTo(CorrelationData.SILENCE);
    }

    @Test
    void shouldDetectMonoSignalAsFullyCorrelated() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] signal = generateSineWave(440.0, 44100.0, 1024);
        meter.process(signal, signal, 1024);

        assertThat(meter.getLatestData().correlation()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldDetectInvertedSignalAsAntiCorrelated() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] left = generateSineWave(440.0, 44100.0, 1024);
        float[] right = new float[left.length];
        for (int i = 0; i < left.length; i++) {
            right[i] = -left[i]; // Phase-inverted
        }
        meter.process(left, right, 1024);

        assertThat(meter.getLatestData().correlation()).isCloseTo(-1.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldDetectCenteredBalance() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] signal = generateSineWave(440.0, 44100.0, 1024);
        meter.process(signal, signal, 1024);

        assertThat(meter.getLatestData().stereoBalance()).isCloseTo(0.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldDetectLeftBalance() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] left = generateSineWave(440.0, 44100.0, 1024);
        float[] right = new float[1024]; // Silence on right
        meter.process(left, right, 1024);

        assertThat(meter.getLatestData().stereoBalance()).isLessThan(0.0);
    }

    @Test
    void shouldDetectRightBalance() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] left = new float[1024]; // Silence on left
        float[] right = generateSineWave(440.0, 44100.0, 1024);
        meter.process(left, right, 1024);

        assertThat(meter.getLatestData().stereoBalance()).isGreaterThan(0.0);
    }

    @Test
    void shouldMeasureMidAndSideLevels() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] signal = generateSineWave(440.0, 44100.0, 1024);
        meter.process(signal, signal, 1024);

        CorrelationData data = meter.getLatestData();
        // Mono signal → high mid, low side
        assertThat(data.midLevel()).isGreaterThan(data.sideLevel());
    }

    @Test
    void shouldResetToSilence() {
        CorrelationMeter meter = new CorrelationMeter();
        meter.process(generateSineWave(440, 44100, 512),
                generateSineWave(440, 44100, 512), 512);
        meter.reset();

        assertThat(meter.getLatestData()).isEqualTo(CorrelationData.SILENCE);
    }

    @Test
    void shouldRejectInvalidSmoothingFactor() {
        assertThatThrownBy(() -> new CorrelationMeter(1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Goniometer data tests ---

    @Test
    void shouldGenerateGoniometerData() {
        float[] left = generateSineWave(440.0, 44100.0, 256);
        float[] right = generateSineWave(440.0, 44100.0, 256);

        GoniometerData data = CorrelationMeter.generateGoniometerData(left, right, 256);

        assertThat(data.pointCount()).isEqualTo(256);
        assertThat(data.xPoints()).hasSize(256);
        assertThat(data.yPoints()).hasSize(256);
        assertThat(data.magnitudes()).hasSize(256);
        assertThat(data.angles()).hasSize(256);
    }

    @Test
    void shouldGenerateMonoGoniometerOnYAxis() {
        // Mono signal (L == R) should produce points on the Y axis (X ≈ 0)
        float[] signal = generateSineWave(440.0, 44100.0, 256);

        GoniometerData data = CorrelationMeter.generateGoniometerData(signal, signal, 256);

        for (int i = 0; i < data.pointCount(); i++) {
            assertThat(data.xPoints()[i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(0.0001f));
        }
    }

    @Test
    void shouldGenerateSideOnlyOnXAxis() {
        // Inverted signal (L == -R) should produce points on the X axis (Y ≈ 0)
        float[] left = generateSineWave(440.0, 44100.0, 256);
        float[] right = new float[256];
        for (int i = 0; i < 256; i++) right[i] = -left[i];

        GoniometerData data = CorrelationMeter.generateGoniometerData(left, right, 256);

        for (int i = 0; i < data.pointCount(); i++) {
            assertThat(data.yPoints()[i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(0.0001f));
        }
    }

    @Test
    void shouldGenerateValidPolarCoordinates() {
        float[] left = generateSineWave(440.0, 44100.0, 256);
        float[] right = generateSineWave(880.0, 44100.0, 256);

        GoniometerData data = CorrelationMeter.generateGoniometerData(left, right, 256);

        for (int i = 0; i < data.pointCount(); i++) {
            float x = data.xPoints()[i];
            float y = data.yPoints()[i];
            float expectedMag = (float) Math.sqrt(x * x + y * y);
            float expectedAngle = (float) Math.atan2(x, y);

            assertThat(data.magnitudes()[i]).isCloseTo(expectedMag,
                    org.assertj.core.data.Offset.offset(0.001f));
            assertThat(data.angles()[i]).isCloseTo(expectedAngle,
                    org.assertj.core.data.Offset.offset(0.001f));
        }
    }

    @Test
    void shouldStoreGoniometerDataAfterProcessWithGoniometer() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] left = generateSineWave(440.0, 44100.0, 512);
        float[] right = generateSineWave(880.0, 44100.0, 512);

        meter.processWithGoniometer(left, right, 512);

        GoniometerData data = meter.getLatestGoniometerData();
        assertThat(data.pointCount()).isEqualTo(512);
    }

    @Test
    void shouldResetGoniometerData() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] signal = generateSineWave(440.0, 44100.0, 256);
        meter.processWithGoniometer(signal, signal, 256);
        meter.reset();

        assertThat(meter.getLatestGoniometerData()).isEqualTo(GoniometerData.EMPTY);
    }

    // --- Phase inversion detection tests ---

    @Test
    void shouldDetectPhaseInversion() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] left = generateSineWave(440.0, 44100.0, 1024);
        float[] right = new float[1024];
        for (int i = 0; i < 1024; i++) right[i] = -left[i];

        meter.process(left, right, 1024);

        assertThat(meter.isPhaseInverted()).isTrue();
    }

    @Test
    void shouldNotDetectPhaseInversionForMonoSignal() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] signal = generateSineWave(440.0, 44100.0, 1024);
        meter.process(signal, signal, 1024);

        assertThat(meter.isPhaseInverted()).isFalse();
    }

    @Test
    void shouldNotDetectPhaseInversionForUncorrelatedSignal() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] left = generateSineWave(440.0, 44100.0, 1024);
        float[] right = generateSineWave(880.0, 44100.0, 1024);
        meter.process(left, right, 1024);

        // Uncorrelated signals have correlation near 0, not inverted
        assertThat(meter.isPhaseInverted()).isFalse();
    }

    // --- Mono compatibility score tests ---

    @Test
    void shouldScoreMonoSignalAsFullyCompatible() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] signal = generateSineWave(440.0, 44100.0, 1024);
        meter.process(signal, signal, 1024);

        assertThat(meter.getMonoCompatibilityScore()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldScoreInvertedSignalAsIncompatible() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        float[] left = generateSineWave(440.0, 44100.0, 1024);
        float[] right = new float[1024];
        for (int i = 0; i < 1024; i++) right[i] = -left[i];

        meter.process(left, right, 1024);

        assertThat(meter.getMonoCompatibilityScore()).isCloseTo(0.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldScoreUncorrelatedSignalNearHalf() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        // Two different frequencies → roughly uncorrelated
        float[] left = generateSineWave(440.0, 44100.0, 4096);
        float[] right = generateSineWave(880.0, 44100.0, 4096);
        meter.process(left, right, 4096);

        // Should be near 0.5 (correlation near 0)
        assertThat(meter.getMonoCompatibilityScore())
                .isBetween(0.3, 0.7);
    }

    // --- Frequency-dependent correlation tests ---

    @Test
    void shouldAnalyzeFrequencyCorrelation() {
        float[] left = generateSineWave(440.0, 44100.0, 4096);
        float[] right = generateSineWave(440.0, 44100.0, 4096);

        double[] correlations = CorrelationMeter.analyzeFrequencyCorrelation(
                left, right, 4096, 44100.0,
                new double[]{200.0, 2000.0});

        // 3 bands: [0-200], [200-2000], [2000+]
        assertThat(correlations).hasSize(3);
    }

    @Test
    void shouldShowHighCorrelationForMonoSignalInAllBands() {
        float[] signal = generateSineWave(440.0, 44100.0, 8192);

        double[] correlations = CorrelationMeter.analyzeFrequencyCorrelation(
                signal, signal, 8192, 44100.0,
                new double[]{200.0, 2000.0});

        // The band containing 440 Hz should have correlation close to 1.0
        // Other bands may be silence (correlation defaults to 1.0)
        for (double c : correlations) {
            assertThat(c).isBetween(-1.0, 1.01);
        }
        // Band [200-2000] contains 440 Hz and should be highly correlated
        assertThat(correlations[1]).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void shouldShowNegativeCorrelationForInvertedBand() {
        // Create a signal where the 440 Hz component is inverted
        int frames = 8192;
        float[] left = generateSineWave(440.0, 44100.0, frames);
        float[] right = new float[frames];
        for (int i = 0; i < frames; i++) right[i] = -left[i];

        double[] correlations = CorrelationMeter.analyzeFrequencyCorrelation(
                left, right, frames, 44100.0,
                new double[]{200.0, 2000.0});

        // Band [200-2000] contains the inverted 440 Hz signal
        assertThat(correlations[1]).isLessThan(0.0);
    }

    // --- Analysis mode tests ---

    @Test
    void shouldDefaultToRealTimeMode() {
        CorrelationMeter meter = new CorrelationMeter();
        assertThat(meter.getAnalysisMode()).isEqualTo(AnalysisMode.REAL_TIME);
    }

    @Test
    void shouldSwitchToPostPlaybackMode() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        meter.setAnalysisMode(AnalysisMode.POST_PLAYBACK);
        assertThat(meter.getAnalysisMode()).isEqualTo(AnalysisMode.POST_PLAYBACK);
    }

    @Test
    void shouldRejectNullAnalysisMode() {
        CorrelationMeter meter = new CorrelationMeter();
        assertThatThrownBy(() -> meter.setAnalysisMode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAccumulateInPostPlaybackMode() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        meter.setAnalysisMode(AnalysisMode.POST_PLAYBACK);

        float[] signal = generateSineWave(440.0, 44100.0, 1024);
        meter.process(signal, signal, 1024);
        CorrelationData first = meter.getLatestData();

        meter.process(signal, signal, 1024);
        CorrelationData second = meter.getLatestData();

        // Both should produce correlated results since L==R
        assertThat(first.correlation()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.01));
        assertThat(second.correlation()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldResetAccumulatorOnModeSwitch() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        meter.setAnalysisMode(AnalysisMode.POST_PLAYBACK);

        float[] left = generateSineWave(440.0, 44100.0, 1024);
        float[] right = new float[1024];
        for (int i = 0; i < 1024; i++) right[i] = -left[i];
        meter.process(left, right, 1024);

        // Switch mode — should reset accumulator
        meter.setAnalysisMode(AnalysisMode.REAL_TIME);
        meter.setAnalysisMode(AnalysisMode.POST_PLAYBACK);

        float[] mono = generateSineWave(440.0, 44100.0, 1024);
        meter.process(mono, mono, 1024);

        // After reset + mono signal, correlation should be near 1.0
        assertThat(meter.getLatestData().correlation()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shouldResetAccumulatorOnReset() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        meter.setAnalysisMode(AnalysisMode.POST_PLAYBACK);

        float[] left = generateSineWave(440.0, 44100.0, 1024);
        float[] right = new float[1024];
        for (int i = 0; i < 1024; i++) right[i] = -left[i];
        meter.process(left, right, 1024);

        meter.reset();

        assertThat(meter.getLatestData()).isEqualTo(CorrelationData.SILENCE);
    }

    @Test
    void postPlaybackShouldProduceStableResultAcrossBlocks() {
        CorrelationMeter meter = new CorrelationMeter(0.0);
        meter.setAnalysisMode(AnalysisMode.POST_PLAYBACK);

        // Process several blocks of mono signal
        float[] signal = generateSineWave(440.0, 44100.0, 512);
        for (int block = 0; block < 10; block++) {
            meter.process(signal, signal, 512);
        }

        // Result should be consistent — correlation near 1.0
        assertThat(meter.getLatestData().correlation()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    private static float[] generateSineWave(double frequency, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequency * i / sampleRate);
        }
        return samples;
    }
}
