package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhaseAlignmentAnalyzerTest {

    private static final double SAMPLE_RATE = 44100.0;

    // --- Construction tests ---

    @Test
    void shouldCreateWithValidParameters() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);

        assertThat(analyzer.getSampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(analyzer.getMaxDelaySamples()).isEqualTo(100);
    }

    @Test
    void shouldCreateWithDefaultMaxDelay() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE);

        // Default is 5 ms → 44100 * 0.005 = 220 samples
        assertThat(analyzer.getMaxDelaySamples()).isEqualTo(220);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> new PhaseAlignmentAnalyzer(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseAlignmentAnalyzer(-44100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeMaxDelay() {
        assertThatThrownBy(() -> new PhaseAlignmentAnalyzer(SAMPLE_RATE, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Identical signals (zero offset) ---

    @Test
    void shouldDetectZeroDelayForIdenticalSignals() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        float[] signal = generateSineWave(440.0, SAMPLE_RATE, 4096);

        PhaseAlignmentResult result = analyzer.analyze(signal, signal);

        assertThat(result.delaySamples()).isZero();
        assertThat(result.polarity()).isEqualTo(PhaseAlignmentResult.Polarity.NORMAL);
        assertThat(result.correlationPeak()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void shouldDetectHighCoherenceForIdenticalSignals() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        float[] signal = generateSineWave(440.0, SAMPLE_RATE, 4096);

        PhaseAlignmentResult result = analyzer.analyze(signal, signal);

        assertThat(result.coherenceScore()).isGreaterThan(0.9);
    }

    // --- Delayed signal detection ---

    @Test
    void shouldDetectPositiveDelay() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        int delay = 20;
        int length = 4096;
        float[] trackA = generateSineWave(440.0, SAMPLE_RATE, length + delay);

        // trackB is trackA shifted forward in time by 'delay' samples (trackB is early)
        float[] trackB = new float[length + delay];
        System.arraycopy(trackA, delay, trackB, 0, length);

        PhaseAlignmentResult result = analyzer.analyze(
                trimToLength(trackA, length),
                trimToLength(trackB, length));

        // trackB is early → positive delaySamples to delay it for alignment
        assertThat(Math.abs(result.delaySamples())).isLessThanOrEqualTo(delay + 2);
        assertThat(result.polarity()).isEqualTo(PhaseAlignmentResult.Polarity.NORMAL);
    }

    @Test
    void shouldDetectSmallDelay() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 50);
        int delay = 5;
        int length = 8192;

        // Create a broadband signal for better cross-correlation accuracy
        float[] trackA = generateBroadbandSignal(SAMPLE_RATE, length + delay);
        float[] trackB = new float[length + delay];
        System.arraycopy(trackA, delay, trackB, 0, length);

        PhaseAlignmentResult result = analyzer.analyze(
                trimToLength(trackA, length),
                trimToLength(trackB, length));

        assertThat(Math.abs(result.delaySamples() - delay)).isLessThanOrEqualTo(2);
    }

    // --- Polarity detection ---

    @Test
    void shouldDetectInvertedPolarity() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        float[] trackA = generateSineWave(440.0, SAMPLE_RATE, 4096);
        float[] trackB = new float[4096];
        for (int i = 0; i < 4096; i++) {
            trackB[i] = -trackA[i];
        }

        PhaseAlignmentResult result = analyzer.analyze(trackA, trackB);

        assertThat(result.polarity()).isEqualTo(PhaseAlignmentResult.Polarity.INVERTED);
        assertThat(result.delaySamples()).isZero();
    }

    @Test
    void shouldDetectNormalPolarityForInPhaseSignals() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        float[] signal = generateSineWave(440.0, SAMPLE_RATE, 4096);

        PhaseAlignmentResult result = analyzer.analyze(signal, signal);

        assertThat(result.polarity()).isEqualTo(PhaseAlignmentResult.Polarity.NORMAL);
    }

    // --- Spectral coherence ---

    @Test
    void shouldComputeHighCoherenceForCorrelatedSignals() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        float[] signal = generateSineWave(440.0, SAMPLE_RATE, 4096);

        PhaseAlignmentResult result = analyzer.analyze(signal, signal);

        assertThat(result.coherenceScore()).isGreaterThan(0.8);
    }

    @Test
    void shouldComputeLowCoherenceForUncorrelatedSignals() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        float[] trackA = generateSineWave(440.0, SAMPLE_RATE, 4096);
        float[] trackB = generateSineWave(1175.0, SAMPLE_RATE, 4096);

        PhaseAlignmentResult result = analyzer.analyze(trackA, trackB);

        assertThat(result.coherenceScore()).isLessThan(0.5);
    }

    // --- Band coherence analysis ---

    @Test
    void shouldComputeBandCoherences() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        float[] signal = generateSineWave(440.0, SAMPLE_RATE, 8192);
        double[] bandEdges = {200.0, 2000.0};

        PhaseAlignmentResult result = analyzer.analyze(signal, signal, bandEdges);

        assertThat(result.bandCoherences()).hasSize(3);
        // Band containing 440 Hz (200-2000 Hz) should have high coherence
        assertThat(result.bandCoherences()[1]).isGreaterThan(0.8);
    }

    @Test
    void shouldReturnEmptyBandCoherencesWhenNoBandEdges() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        float[] signal = generateSineWave(440.0, SAMPLE_RATE, 4096);

        PhaseAlignmentResult result = analyzer.analyze(signal, signal);

        assertThat(result.bandCoherences()).isEmpty();
    }

    // --- Edge cases ---

    @Test
    void shouldHandleEmptySignals() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);

        PhaseAlignmentResult result = analyzer.analyze(new float[0], new float[0]);

        assertThat(result.delaySamples()).isZero();
        assertThat(result.polarity()).isEqualTo(PhaseAlignmentResult.Polarity.NORMAL);
        assertThat(result.coherenceScore()).isEqualTo(0.0);
    }

    @Test
    void shouldHandleSilentSignals() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        float[] silence = new float[4096];

        PhaseAlignmentResult result = analyzer.analyze(silence, silence);

        assertThat(result.delaySamples()).isZero();
    }

    @Test
    void shouldHandleDifferentLengthSignals() {
        var analyzer = new PhaseAlignmentAnalyzer(SAMPLE_RATE, 100);
        float[] trackA = generateSineWave(440.0, SAMPLE_RATE, 4096);
        float[] trackB = generateSineWave(440.0, SAMPLE_RATE, 2048);

        // Should not throw; uses minimum length
        PhaseAlignmentResult result = analyzer.analyze(trackA, trackB);

        assertThat(result.polarity()).isEqualTo(PhaseAlignmentResult.Polarity.NORMAL);
    }

    // --- PhaseAlignmentResult record tests ---

    @Test
    void shouldRejectInvalidCorrelationPeak() {
        assertThatThrownBy(() -> new PhaseAlignmentResult(
                0, PhaseAlignmentResult.Polarity.NORMAL, 1.5, 0.5, new double[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseAlignmentResult(
                0, PhaseAlignmentResult.Polarity.NORMAL, -1.5, 0.5, new double[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidCoherenceScore() {
        assertThatThrownBy(() -> new PhaseAlignmentResult(
                0, PhaseAlignmentResult.Polarity.NORMAL, 0.5, -0.1, new double[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhaseAlignmentResult(
                0, PhaseAlignmentResult.Polarity.NORMAL, 0.5, 1.1, new double[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullBandCoherences() {
        assertThatThrownBy(() -> new PhaseAlignmentResult(
                0, PhaseAlignmentResult.Polarity.NORMAL, 0.5, 0.5, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCreateValidResult() {
        double[] bands = {0.9, 0.8, 0.7};
        var result = new PhaseAlignmentResult(
                10, PhaseAlignmentResult.Polarity.INVERTED, 0.95, 0.85, bands);

        assertThat(result.delaySamples()).isEqualTo(10);
        assertThat(result.polarity()).isEqualTo(PhaseAlignmentResult.Polarity.INVERTED);
        assertThat(result.correlationPeak()).isEqualTo(0.95);
        assertThat(result.coherenceScore()).isEqualTo(0.85);
        assertThat(result.bandCoherences()).containsExactly(0.9, 0.8, 0.7);
    }

    // --- Helper methods ---

    private static float[] generateSineWave(double frequency, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequency * i / sampleRate);
        }
        return samples;
    }

    private static float[] generateBroadbandSignal(double sampleRate, int length) {
        float[] samples = new float[length];
        double[] frequencies = {100.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0};
        for (double freq : frequencies) {
            for (int i = 0; i < length; i++) {
                samples[i] += (float) (Math.sin(2.0 * Math.PI * freq * i / sampleRate)
                        / frequencies.length);
            }
        }
        return samples;
    }

    private static float[] trimToLength(float[] signal, int length) {
        if (signal.length == length) {
            return signal;
        }
        float[] trimmed = new float[length];
        System.arraycopy(signal, 0, trimmed, 0, Math.min(signal.length, length));
        return trimmed;
    }
}
