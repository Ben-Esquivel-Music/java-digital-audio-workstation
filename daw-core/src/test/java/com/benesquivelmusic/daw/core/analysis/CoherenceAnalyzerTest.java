package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoherenceAnalyzerTest {

    private static final double SAMPLE_RATE = 44100.0;
    private static final int LENGTH = 16384;

    // --- Construction ---

    @Test
    void shouldCreateWithDefaults() {
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE);

        assertThat(analyzer.getSampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(analyzer.getSegmentSize()).isEqualTo(CoherenceAnalyzer.DEFAULT_SEGMENT_SIZE);
        assertThat(analyzer.getHopSize()).isEqualTo(CoherenceAnalyzer.DEFAULT_SEGMENT_SIZE / 2);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> new CoherenceAnalyzer(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoherenceAnalyzer(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPowerOfTwoSegmentSize() {
        assertThatThrownBy(() -> new CoherenceAnalyzer(SAMPLE_RATE, 1000, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoherenceAnalyzer(SAMPLE_RATE, 1, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidOverlap() {
        assertThatThrownBy(() -> new CoherenceAnalyzer(SAMPLE_RATE, 1024, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoherenceAnalyzer(SAMPLE_RATE, 1024, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Linear systems should yield coherence ≈ 1.0 ---

    @Test
    void shouldReportPerfectCoherenceForIdenticalSignals() {
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE);
        float[] signal = generateBroadbandNoise(LENGTH, 1234L);

        CoherenceResult result = analyzer.analyze(signal, signal);

        assertThat(result.meanCoherence()).isGreaterThan(0.99);
        assertThat(result.distortionIndicator()).isLessThan(0.01);
        assertThat(result.numSegments()).isPositive();
    }

    @Test
    void shouldReportPerfectCoherenceForScaledSignal() {
        // Pure linear gain should not reduce coherence.
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE);
        float[] input = generateBroadbandNoise(LENGTH, 42L);
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            output[i] = input[i] * 0.5f;
        }

        CoherenceResult result = analyzer.analyze(input, output);

        assertThat(result.meanCoherence()).isGreaterThan(0.99);
    }

    // --- Nonlinear distortion should drop coherence ---

    @Test
    void shouldReportLowCoherenceForHardClipping() {
        // Hard clipping introduces harmonics not present in the input:
        // the coherence must drop well below 1.0.
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE);
        float[] input = generateBroadbandNoise(LENGTH, 7L);
        float[] output = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            float x = input[i] * 4.0f; // drive into clipping
            if (x > 1.0f) x = 1.0f;
            else if (x < -1.0f) x = -1.0f;
            output[i] = x;
        }

        CoherenceResult coherent = analyzer.analyze(input, input);
        CoherenceResult distorted = analyzer.analyze(input, output);

        assertThat(distorted.meanCoherence()).isLessThan(coherent.meanCoherence());
        assertThat(distorted.distortionIndicator()).isGreaterThan(0.05);
    }

    @Test
    void shouldReportLowCoherenceForUncorrelatedSignals() {
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE);
        float[] input = generateBroadbandNoise(LENGTH, 1L);
        float[] output = generateBroadbandNoise(LENGTH, 2L);

        CoherenceResult result = analyzer.analyze(input, output);

        assertThat(result.meanCoherence()).isLessThan(0.5);
        assertThat(result.distortionIndicator()).isGreaterThan(0.5);
    }

    // --- Result structure ---

    @Test
    void shouldReturnPerBinCoherenceValues() {
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE, 1024, 0.5);
        float[] signal = generateBroadbandNoise(LENGTH, 99L);

        CoherenceResult result = analyzer.analyze(signal, signal);

        // Single-sided spectrum length = segmentSize/2 + 1
        assertThat(result.coherence()).hasSize(513);
        assertThat(result.frequencies()).hasSize(513);

        // Frequencies should span 0..Nyquist
        assertThat(result.frequencies()[0]).isEqualTo(0.0);
        assertThat(result.frequencies()[512]).isEqualTo(SAMPLE_RATE / 2.0);

        // Every coherence value must be in [0, 1]
        for (double c : result.coherence()) {
            assertThat(c).isBetween(0.0, 1.0);
        }
    }

    @Test
    void shouldHandleBufferShorterThanSegment() {
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE, 1024, 0.5);
        float[] shortInput = new float[256];
        float[] shortOutput = new float[256];

        CoherenceResult result = analyzer.analyze(shortInput, shortOutput);

        assertThat(result.numSegments()).isZero();
        assertThat(result.meanCoherence()).isZero();
        assertThat(result.distortionIndicator()).isEqualTo(1.0);
    }

    @Test
    void shouldHandleSilentSignals() {
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE);
        float[] silence = new float[LENGTH];

        CoherenceResult result = analyzer.analyze(silence, silence);

        // With zero energy every bin's denominator is < epsilon → coherence stays 0.
        assertThat(result.meanCoherence()).isZero();
        assertThat(result.numSegments()).isPositive();
    }

    @Test
    void shouldRejectNullBuffers() {
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE);
        assertThatThrownBy(() -> analyzer.analyze(null, new float[LENGTH]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.analyze(new float[LENGTH], null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Band helper ---

    @Test
    void shouldComputeMeanCoherenceInBand() {
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE);
        float[] signal = generateBroadbandNoise(LENGTH, 5L);

        CoherenceResult result = analyzer.analyze(signal, signal);
        double midBand = CoherenceAnalyzer.meanCoherenceInBand(result, 500.0, 4000.0);

        assertThat(midBand).isGreaterThan(0.99);
    }

    @Test
    void shouldRejectInvalidBand() {
        var analyzer = new CoherenceAnalyzer(SAMPLE_RATE);
        CoherenceResult result = analyzer.analyze(
                new float[LENGTH], new float[LENGTH]);

        assertThatThrownBy(() -> CoherenceAnalyzer.meanCoherenceInBand(result, 1000, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CoherenceAnalyzer.meanCoherenceInBand(null, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- CoherenceResult record tests ---

    @Test
    void shouldRejectInvalidResultConstruction() {
        double[] c = new double[4];
        double[] f = new double[4];
        double[] wrongSize = new double[3];

        assertThatThrownBy(() -> new CoherenceResult(null, f, 0.5, 0.5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoherenceResult(c, null, 0.5, 0.5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoherenceResult(c, wrongSize, 0.5, 0.5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoherenceResult(c, f, 1.5, 0.5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoherenceResult(c, f, 0.5, -0.1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoherenceResult(c, f, 0.5, 0.5, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Helpers ---

    private static float[] generateBroadbandNoise(int length, long seed) {
        Random rng = new Random(seed);
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) (rng.nextGaussian() * 0.25);
        }
        return samples;
    }
}
