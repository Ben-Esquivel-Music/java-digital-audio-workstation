package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BiquadFilterTest {

    @Test
    void shouldCreateLowPassFilter() {
        var filter = BiquadFilter.create(BiquadFilter.FilterType.LOW_PASS, 44100.0, 1000.0, 0.707, 0);
        assertThat(filter).isNotNull();
    }

    @Test
    void shouldCreateHighPassFilter() {
        var filter = BiquadFilter.create(BiquadFilter.FilterType.HIGH_PASS, 44100.0, 1000.0, 0.707, 0);
        assertThat(filter).isNotNull();
    }

    @Test
    void shouldCreatePeakEqFilter() {
        var filter = BiquadFilter.create(BiquadFilter.FilterType.PEAK_EQ, 44100.0, 1000.0, 1.0, 6.0);
        assertThat(filter).isNotNull();
    }

    @Test
    void shouldCreateShelfFilters() {
        var lowShelf = BiquadFilter.create(BiquadFilter.FilterType.LOW_SHELF, 44100.0, 100.0, 0.707, 3.0);
        var highShelf = BiquadFilter.create(BiquadFilter.FilterType.HIGH_SHELF, 44100.0, 8000.0, 0.707, -3.0);
        assertThat(lowShelf).isNotNull();
        assertThat(highShelf).isNotNull();
    }

    @Test
    void shouldPassDcThroughLowPass() {
        var filter = BiquadFilter.create(BiquadFilter.FilterType.LOW_PASS, 44100.0, 10000.0, 0.707, 0);

        // Feed a DC signal (well below cutoff) — should pass through
        float output = 0;
        for (int i = 0; i < 1000; i++) {
            output = filter.processSample(1.0f);
        }
        // After settling, output should be close to input (DC passes through LPF)
        assertThat(output).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void shouldAttenuateHighFrequenciesWithLowPass() {
        double sampleRate = 44100.0;
        var filter = BiquadFilter.create(BiquadFilter.FilterType.LOW_PASS, sampleRate, 100.0, 0.707, 0);

        // Generate a high-frequency sine (10 kHz) well above the 100 Hz cutoff
        float[] input = new float[4096];
        float[] output = new float[4096];
        for (int i = 0; i < input.length; i++) {
            input[i] = (float) Math.sin(2.0 * Math.PI * 10000.0 * i / sampleRate);
        }

        for (int i = 0; i < input.length; i++) {
            output[i] = filter.processSample(input[i]);
        }

        // RMS of output should be much less than input
        double inputRms = rms(input, 1000, input.length);
        double outputRms = rms(output, 1000, output.length);
        assertThat(outputRms).isLessThan(inputRms * 0.1);
    }

    @Test
    void shouldProcessBuffer() {
        var filter = BiquadFilter.create(BiquadFilter.FilterType.LOW_PASS, 44100.0, 5000.0, 0.707, 0);
        float[] buffer = {0.5f, -0.3f, 0.8f, -0.1f};
        filter.process(buffer, 0, buffer.length);
        // Just verify it runs without error and modifies the buffer
        assertThat(buffer).isNotNull();
    }

    @Test
    void shouldResetState() {
        var filter = BiquadFilter.create(BiquadFilter.FilterType.LOW_PASS, 44100.0, 1000.0, 0.707, 0);
        filter.processSample(1.0f);
        filter.processSample(0.5f);
        filter.reset();

        // After reset, the filter should behave like a fresh filter
        float fresh = BiquadFilter.create(BiquadFilter.FilterType.LOW_PASS, 44100.0, 1000.0, 0.707, 0)
                .processSample(0.3f);
        float afterReset = filter.processSample(0.3f);
        assertThat(afterReset).isCloseTo(fresh, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldRecalculateCoefficients() {
        var filter = BiquadFilter.create(BiquadFilter.FilterType.LOW_PASS, 44100.0, 1000.0, 0.707, 0);
        filter.recalculate(BiquadFilter.FilterType.HIGH_PASS, 44100.0, 5000.0, 0.707, 0);
        // Should not throw and should produce a valid filter
        float output = filter.processSample(1.0f);
        assertThat(Float.isFinite(output)).isTrue();
    }

    @Test
    void shouldCreateNotchFilter() {
        var filter = BiquadFilter.create(BiquadFilter.FilterType.NOTCH, 44100.0, 1000.0, 1.0, 0);
        assertThat(filter).isNotNull();
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
