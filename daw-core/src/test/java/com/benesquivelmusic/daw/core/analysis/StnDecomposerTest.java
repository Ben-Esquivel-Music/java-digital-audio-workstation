package com.benesquivelmusic.daw.core.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StnDecomposerTest {

    @Test
    void shouldCreateWithDefaultParameters() {
        StnDecomposer decomposer = new StnDecomposer(1024);
        assertThat(decomposer.getFftSize()).isEqualTo(1024);
        assertThat(decomposer.getHopSize()).isEqualTo(256);
        assertThat(decomposer.getTonalMedianLength()).isEqualTo(17);
        assertThat(decomposer.getTransientMedianLength()).isEqualTo(17);
    }

    @Test
    void shouldCreateWithCustomParameters() {
        StnDecomposer decomposer = new StnDecomposer(2048, 512, 11, 13);
        assertThat(decomposer.getFftSize()).isEqualTo(2048);
        assertThat(decomposer.getHopSize()).isEqualTo(512);
        assertThat(decomposer.getTonalMedianLength()).isEqualTo(11);
        assertThat(decomposer.getTransientMedianLength()).isEqualTo(13);
    }

    @Test
    void shouldRejectNonPowerOfTwoFftSize() {
        assertThatThrownBy(() -> new StnDecomposer(1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroFftSize() {
        assertThatThrownBy(() -> new StnDecomposer(0, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidHopSize() {
        assertThatThrownBy(() -> new StnDecomposer(1024, 0, 17, 17))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StnDecomposer(1024, 2048, 17, 17))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectEvenTonalMedianLength() {
        assertThatThrownBy(() -> new StnDecomposer(1024, 256, 16, 17))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectEvenTransientMedianLength() {
        assertThatThrownBy(() -> new StnDecomposer(1024, 256, 17, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroMedianLength() {
        assertThatThrownBy(() -> new StnDecomposer(1024, 256, 0, 17))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnEmptyResultForTooShortInput() {
        StnDecomposer decomposer = new StnDecomposer(1024);
        StnDecomposer.StnResult result = decomposer.decompose(new float[512]);

        assertThat(result.sines()).hasSize(512);
        assertThat(result.transients()).hasSize(512);
        assertThat(result.noise()).hasSize(512);
        // All zeros for too-short input
        assertThat(rms(result.sines())).isEqualTo(0.0f);
        assertThat(rms(result.transients())).isEqualTo(0.0f);
        assertThat(rms(result.noise())).isEqualTo(0.0f);
    }

    @Test
    void shouldReturnResultsMatchingInputLength() {
        StnDecomposer decomposer = new StnDecomposer(1024);
        float[] samples = generateSineWave(440.0, 44100.0, 44100);
        StnDecomposer.StnResult result = decomposer.decompose(samples);

        assertThat(result.sines()).hasSize(samples.length);
        assertThat(result.transients()).hasSize(samples.length);
        assertThat(result.noise()).hasSize(samples.length);
    }

    @Test
    void shouldProduceSilentOutputForSilentInput() {
        StnDecomposer decomposer = new StnDecomposer(1024);
        float[] silence = new float[4096];
        StnDecomposer.StnResult result = decomposer.decompose(silence);

        assertThat(rms(result.sines())).isLessThan(1e-6f);
        assertThat(rms(result.transients())).isLessThan(1e-6f);
        assertThat(rms(result.noise())).isLessThan(1e-6f);
    }

    @Test
    void shouldConcentrateSineInTonalComponent() {
        double sampleRate = 44100.0;
        int length = 44100; // 1 second
        StnDecomposer decomposer = new StnDecomposer(2048, 512, 17, 17);

        float[] samples = generateSineWave(440.0, sampleRate, length);
        StnDecomposer.StnResult result = decomposer.decompose(samples);

        // The tonal (sines) component should carry the majority of energy
        float sinesRms = rms(result.sines());
        float transientRms = rms(result.transients());
        float noiseRms = rms(result.noise());

        assertThat(sinesRms).isGreaterThan(transientRms);
        assertThat(sinesRms).isGreaterThan(noiseRms);
    }

    @Test
    void shouldConcentrateClickInTransientComponent() {
        int length = 8192;
        StnDecomposer decomposer = new StnDecomposer(1024, 256, 17, 17);

        // Create a single sharp click (broadband transient) in silence
        float[] samples = new float[length];
        int clickPos = length / 2;
        for (int i = 0; i < 32; i++) {
            if (clickPos + i < length) {
                samples[clickPos + i] = (i == 0) ? 1.0f : 0.0f;
            }
        }

        StnDecomposer.StnResult result = decomposer.decompose(samples);

        // The transient component should carry more energy than the tonal component
        float transientRms = rms(result.transients());
        float sinesRms = rms(result.sines());

        assertThat(transientRms).isGreaterThan(sinesRms);
    }

    @Test
    void componentsShouldSumToOriginal() {
        double sampleRate = 44100.0;
        StnDecomposer decomposer = new StnDecomposer(1024, 256, 17, 17);

        float[] samples = generateSineWave(440.0, sampleRate, 8192);
        StnDecomposer.StnResult result = decomposer.decompose(samples);

        // Sum the three components
        float[] reconstructed = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            reconstructed[i] = result.sines()[i] + result.transients()[i] + result.noise()[i];
        }

        // The reconstruction should closely approximate the original in the
        // well-covered region (skip edges where overlap-add normalization
        // may be incomplete)
        int margin = decomposer.getFftSize();
        float maxError = 0.0f;
        for (int i = margin; i < samples.length - margin; i++) {
            float error = Math.abs(reconstructed[i] - samples[i]);
            if (error > maxError) {
                maxError = error;
            }
        }
        assertThat(maxError).isLessThan(0.01f);
    }

    @Test
    void shouldHandleExactlyFftSizeInput() {
        StnDecomposer decomposer = new StnDecomposer(1024);
        float[] samples = generateSineWave(440.0, 44100.0, 1024);
        StnDecomposer.StnResult result = decomposer.decompose(samples);

        assertThat(result.sines()).hasSize(1024);
        assertThat(result.transients()).hasSize(1024);
        assertThat(result.noise()).hasSize(1024);
    }

    @Test
    void ifftShouldInvertFft() {
        double[] real = {1.0, 0.5, -0.3, 0.7, 0.2, -0.1, 0.4, -0.6};
        double[] imag = new double[8];
        double[] originalReal = real.clone();

        FftUtils.fft(real, imag);
        FftUtils.ifft(real, imag);

        for (int i = 0; i < real.length; i++) {
            assertThat(real[i]).isCloseTo(originalReal[i],
                    org.assertj.core.data.Offset.offset(1e-10));
        }
    }

    @Test
    void shouldDecomposeMultiToneSignal() {
        double sampleRate = 44100.0;
        int length = 44100;
        StnDecomposer decomposer = new StnDecomposer(2048, 512, 17, 17);

        // Mix two sine waves
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate)
                    + 0.3 * Math.sin(2.0 * Math.PI * 880.0 * i / sampleRate));
        }

        StnDecomposer.StnResult result = decomposer.decompose(samples);

        // Tonal should dominate for a pure tonal signal
        float sinesRms = rms(result.sines());
        float transientRms = rms(result.transients());

        assertThat(sinesRms).isGreaterThan(transientRms);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static float[] generateSineWave(double frequency, double sampleRate, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * frequency * i / sampleRate);
        }
        return samples;
    }

    private static float rms(float[] buffer) {
        double sum = 0.0;
        for (float v : buffer) {
            sum += (double) v * v;
        }
        return (float) Math.sqrt(sum / buffer.length);
    }
}
