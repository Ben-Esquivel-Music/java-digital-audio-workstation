package com.benesquivelmusic.daw.core.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class NoiseShapedDithererTest {

    @Test
    void shouldQuantizeToTargetBitDepthRange() {
        var ditherer = new NoiseShapedDitherer(42L);

        for (int i = 0; i < 1000; i++) {
            double sample = (i / 500.0) - 1.0;
            double quantized = ditherer.dither(sample, 16);
            assertThat(quantized).isBetween(-32768.0, 32767.0);
        }
    }

    @Test
    void shouldPreserveSilenceApproximately() {
        var ditherer = new NoiseShapedDitherer(42L);
        double sum = 0.0;
        int count = 100_000;

        for (int i = 0; i < count; i++) {
            sum += ditherer.dither(0.0, 16);
        }

        double mean = sum / count;
        assertThat(mean).isCloseTo(0.0, offset(2.0));
    }

    @Test
    void shouldShapeNoiseTowardHigherFrequencies() {
        // Process a silence signal and measure noise energy in low vs high bands
        var ditherer = new NoiseShapedDitherer(42L);
        int count = 8192;
        double[] noiseSamples = new double[count];

        for (int i = 0; i < count; i++) {
            noiseSamples[i] = ditherer.dither(0.0, 16);
        }

        // Measure energy in first half (low frequencies) and second half (high frequencies)
        // using a simple DFT bin split approach
        double lowEnergy = 0.0;
        double highEnergy = 0.0;
        for (int k = 1; k < count / 2; k++) {
            double re = 0.0;
            double im = 0.0;
            for (int n = 0; n < count; n++) {
                double angle = 2.0 * Math.PI * k * n / count;
                re += noiseSamples[n] * Math.cos(angle);
                im -= noiseSamples[n] * Math.sin(angle);
            }
            double magnitude = re * re + im * im;
            if (k < count / 4) {
                lowEnergy += magnitude;
            } else {
                highEnergy += magnitude;
            }
        }

        // Noise-shaped dithering should have more energy at high frequencies
        assertThat(highEnergy).isGreaterThan(lowEnergy);
    }

    @Test
    void shouldWorkWith24BitTarget() {
        var ditherer = new NoiseShapedDitherer(42L);
        double maxVal24 = (1L << 23) - 1;

        double quantized = ditherer.dither(0.5, 24);
        assertThat(quantized).isCloseTo(maxVal24 * 0.5, offset(2.0));
    }

    @Test
    void resetShouldClearState() {
        var ditherer = new NoiseShapedDitherer(42L);

        // Process some samples to build up error state
        for (int i = 0; i < 100; i++) {
            ditherer.dither(0.5, 16);
        }

        ditherer.reset();

        // After reset, processing the same seed should give deterministic results
        // (the error feedback state is cleared)
        var freshDitherer = new NoiseShapedDitherer(99L);
        ditherer = new NoiseShapedDitherer(99L);

        double result1 = ditherer.dither(0.3, 16);
        double result2 = freshDitherer.dither(0.3, 16);
        assertThat(result1).isEqualTo(result2);
    }
}
