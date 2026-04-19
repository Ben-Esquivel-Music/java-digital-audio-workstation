package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.analysis.LosslessIntegrityChecker.OriginFormat;
import com.benesquivelmusic.daw.core.analysis.LosslessIntegrityChecker.Report;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LosslessIntegrityCheckerTest {

    private static final double SAMPLE_RATE = 44100.0;

    private final LosslessIntegrityChecker checker = new LosslessIntegrityChecker();

    @Test
    void shouldRejectNullSamples() {
        assertThatThrownBy(() -> checker.analyze(null, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> checker.analyze(new float[1024], 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidContainerBitDepth() {
        assertThatThrownBy(() -> checker.analyze(new float[1024], SAMPLE_RATE, 40))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checker.analyze(new float[1024], SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldClassifyBroadbandNoiseAsTrueLossless() {
        float[] samples = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 1L);

        Report report = checker.analyze(samples, SAMPLE_RATE);

        assertThat(report.originFormat()).isEqualTo(OriginFormat.TRUE_LOSSLESS);
        assertThat(report.isLikelyLossless()).isTrue();
        assertThat(report.spectralCutoffHz()).isLessThan(0);
        assertThat(report.estimatedSourceRateHz()).isLessThan(0);
        assertThat(report.confidence()).isLessThan(0.2);
    }

    @Test
    void shouldDetectMp3StyleSpectralCutoff() {
        // Simulate a 128 kbps MP3 rolloff at ~16 kHz.
        float[] noise = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 2L);
        float[] lowPassed = applyBrickwallLowPass(noise, SAMPLE_RATE, 16000.0);

        Report report = checker.analyze(lowPassed, SAMPLE_RATE);

        assertThat(report.originFormat()).isEqualTo(OriginFormat.UPCONVERTED_FROM_MP3);
        assertThat(report.spectralCutoffHz()).isBetween(14500.0, 17500.0);
        assertThat(report.isLikelyLossless()).isFalse();
        assertThat(report.confidence()).isGreaterThan(0.0);
    }

    @Test
    void shouldDetectAacStyleSpectralCutoff() {
        float[] noise = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 3L);
        float[] lowPassed = applyBrickwallLowPass(noise, SAMPLE_RATE, 18500.0);

        Report report = checker.analyze(lowPassed, SAMPLE_RATE);

        assertThat(report.originFormat()).isEqualTo(OriginFormat.UPCONVERTED_FROM_AAC);
        assertThat(report.spectralCutoffHz()).isBetween(17500.0, 19500.0);
    }

    @Test
    void shouldDetectUpsampling() {
        // Simulate upsampling by generating broadband noise at the
        // container rate and brickwall-lowpassing it at the original
        // Nyquist (22 050 Hz / 2 = 11 025 Hz). This reproduces the
        // spectral fingerprint of a clean resampler: full spectrum below
        // 11 025 Hz, pure silence above.
        float[] noise = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 4L);
        float[] upsampled = applyBrickwallLowPass(noise, SAMPLE_RATE, 11025.0);

        Report report = checker.analyze(upsampled, SAMPLE_RATE);

        assertThat(report.originFormat()).isEqualTo(OriginFormat.UPSAMPLED);
        assertThat(report.estimatedSourceRateHz()).isEqualTo(22050.0);
        assertThat(report.confidence()).isGreaterThan(0.3);
    }

    @Test
    void shouldDetectBitDepthUpscaling() {
        // Produce 16-bit effective samples sitting inside a 24-bit container:
        // quantize to 16 bits, then expose them as float. Every integer
        // sample will have its lowest 8 bits set to zero.
        float[] samples = whiteNoise(SAMPLE_RATE, 0.5, 0.3f, 5L);
        float[] quantized16 = quantizeToBitDepth(samples, 16);

        Report report = checker.analyze(quantized16, SAMPLE_RATE, 24);

        assertThat(report.originFormat()).isEqualTo(OriginFormat.UPSCALED_BIT_DEPTH);
        assertThat(report.effectiveBitDepth()).isEqualTo(16);
        assertThat(report.containerBitDepth()).isEqualTo(24);
        assertThat(report.confidence()).isGreaterThan(0.0);
    }

    @Test
    void shouldReportMatchingEffectiveBitDepthForGenuineLossless() {
        float[] samples = quantizeToBitDepth(
                whiteNoise(SAMPLE_RATE, 0.5, 0.3f, 6L), 24);

        Report report = checker.analyze(samples, SAMPLE_RATE, 24);

        // Genuine 24-bit noise: effective depth is 24 (LSB is set).
        assertThat(report.effectiveBitDepth()).isEqualTo(24);
        assertThat(report.originFormat()).isEqualTo(OriginFormat.TRUE_LOSSLESS);
    }

    @Test
    void shouldClassifyMixedSignaturesAsUpconvertedMixed() {
        // MP3-style lowpass (cutoff ≈ 16 kHz) *and* bit-depth upscaling
        // (16-bit samples inside a 24-bit container) — two independent
        // signatures should produce a MIXED verdict.
        float[] noise = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 7L);
        float[] lowPassed = applyBrickwallLowPass(noise, SAMPLE_RATE, 16000.0);
        float[] quantized = quantizeToBitDepth(lowPassed, 16);

        Report report = checker.analyze(quantized, SAMPLE_RATE, 24);

        assertThat(report.originFormat()).isEqualTo(OriginFormat.UPCONVERTED_MIXED);
        assertThat(report.effectiveBitDepth()).isEqualTo(16);
        assertThat(report.confidence()).isGreaterThan(0.3);
    }

    @Test
    void confidenceShouldBeInUnitInterval() {
        float[] noise = whiteNoise(SAMPLE_RATE, 1.0, 0.2f, 8L);
        float[] lowPassed = applyBrickwallLowPass(noise, SAMPLE_RATE, 8000.0);

        Report report = checker.analyze(lowPassed, SAMPLE_RATE);

        assertThat(report.confidence()).isBetween(0.0, 1.0);
    }

    // -- Helpers ------------------------------------------------------------

    private static float[] whiteNoise(double sampleRate, double seconds,
                                      float amp, long seed) {
        int n = (int) Math.round(sampleRate * seconds);
        float[] out = new float[n];
        Random rng = new Random(seed);
        for (int i = 0; i < n; i++) {
            out[i] = (float) ((rng.nextDouble() * 2 - 1) * amp);
        }
        return out;
    }

    /** Zero-phase brickwall low-pass via FFT → zero high bins → IFFT. */
    private static float[] applyBrickwallLowPass(float[] samples,
                                                 double sampleRate,
                                                 double cutoffHz) {
        int fftSize = Integer.highestOneBit(samples.length - 1) << 1;
        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];
        for (int i = 0; i < samples.length; i++) real[i] = samples[i];

        FftUtils.fft(real, imag);

        double binHz = sampleRate / fftSize;
        int cutoffBin = (int) Math.round(cutoffHz / binHz);
        for (int k = cutoffBin + 1; k < fftSize - cutoffBin; k++) {
            real[k] = 0.0;
            imag[k] = 0.0;
        }

        FftUtils.ifft(real, imag);

        float[] out = new float[samples.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) real[i];
        }
        return out;
    }

    /**
     * Quantize float samples to {@code bitDepth} integer resolution using
     * the standard PCM convention ({@code scale = 2^(bitDepth - 1)}) and
     * return them as floats. Re-quantizing these values into any wider
     * container with the same convention produces bit-exact left-shifted
     * integer values.
     */
    private static float[] quantizeToBitDepth(float[] samples, int bitDepth) {
        long scale = 1L << (bitDepth - 1);
        long maxAbs = scale - 1L;
        float[] out = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            double clamped = Math.max(-1.0, Math.min(1.0, samples[i]));
            long v = Math.round(clamped * scale);
            if (v > maxAbs) v = maxAbs;
            else if (v < -maxAbs) v = -maxAbs;
            out[i] = (float) (v / (double) scale);
        }
        return out;
    }
}
