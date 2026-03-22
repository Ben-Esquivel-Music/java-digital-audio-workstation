package com.benesquivelmusic.daw.core.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class SampleRateConverterTest {

    @Test
    void shouldReturnCloneWhenRatesAreEqual() {
        float[] input = {0.0f, 0.5f, 1.0f, -0.5f, -1.0f};
        float[] output = SampleRateConverter.convert(input, 44100, 44100);

        assertThat(output).isEqualTo(input);
        assertThat(output).isNotSameAs(input); // should be a clone
    }

    @Test
    void shouldUpsampleToCorrectLength() {
        int inputLength = 1000;
        float[] input = new float[inputLength];
        for (int i = 0; i < inputLength; i++) {
            input[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
        }

        float[] output = SampleRateConverter.convert(input, 44100, 96000);

        // Expected length: 1000 * (96000/44100) ≈ 2177
        int expectedLength = Math.round(inputLength * 96000.0f / 44100.0f);
        assertThat(output.length).isEqualTo(expectedLength);
    }

    @Test
    void shouldDownsampleToCorrectLength() {
        int inputLength = 2000;
        float[] input = new float[inputLength];
        for (int i = 0; i < inputLength; i++) {
            input[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 96000.0);
        }

        float[] output = SampleRateConverter.convert(input, 96000, 44100);

        int expectedLength = Math.round(inputLength * 44100.0f / 96000.0f);
        assertThat(output.length).isEqualTo(expectedLength);
    }

    @Test
    void shouldPreserveDcSignal() {
        // A constant signal should remain constant after conversion
        float[] input = new float[1000];
        java.util.Arrays.fill(input, 0.5f);

        float[] output = SampleRateConverter.convert(input, 44100, 48000);

        // Skip edges where the kernel may have edge effects
        int margin = 50;
        for (int i = margin; i < output.length - margin; i++) {
            assertThat((double) output[i]).isCloseTo(0.5, offset(0.01));
        }
    }

    @Test
    void shouldPreserveLowFrequencySineWave() {
        // A 100 Hz sine at 44100 Hz should survive conversion to 48000 Hz
        int inputLength = 4410; // 0.1 seconds
        float[] input = new float[inputLength];
        double freq = 100.0;
        for (int i = 0; i < inputLength; i++) {
            input[i] = (float) Math.sin(2.0 * Math.PI * freq * i / 44100.0);
        }

        float[] output = SampleRateConverter.convert(input, 44100, 48000);

        // Verify frequency content by checking zero crossings in the output
        // Expected: ~100 Hz = ~10 full cycles in 0.1 seconds
        int zeroCrossings = 0;
        for (int i = 1; i < output.length; i++) {
            if ((output[i - 1] >= 0 && output[i] < 0) || (output[i - 1] < 0 && output[i] >= 0)) {
                zeroCrossings++;
            }
        }

        // 100 Hz sine = 20 zero crossings per 0.1 seconds (2 per cycle)
        // Allow some tolerance for edge effects
        assertThat(zeroCrossings).isBetween(18, 22);
    }

    @Test
    void shouldHandleEmptyInput() {
        float[] input = new float[0];
        float[] output = SampleRateConverter.convert(input, 44100, 48000);
        assertThat(output).isEmpty();
    }

    @Test
    void shouldDownsample96kTo44k() {
        int inputLength = 9600; // 0.1 seconds at 96kHz
        float[] input = new float[inputLength];
        double freq = 1000.0;
        for (int i = 0; i < inputLength; i++) {
            input[i] = (float) Math.sin(2.0 * Math.PI * freq * i / 96000.0);
        }

        float[] output = SampleRateConverter.convert(input, 96000, 44100);

        // Check output length
        int expectedLength = Math.round(inputLength * 44100.0f / 96000.0f);
        assertThat(output.length).isEqualTo(expectedLength);

        // Verify the signal is still a reasonable sinusoid (check RMS)
        double rms = 0.0;
        int margin = 100;
        for (int i = margin; i < output.length - margin; i++) {
            rms += output[i] * output[i];
        }
        rms = Math.sqrt(rms / (output.length - 2 * margin));

        // RMS of a sine wave should be ~0.707
        assertThat(rms).isCloseTo(0.707, offset(0.05));
    }
}
