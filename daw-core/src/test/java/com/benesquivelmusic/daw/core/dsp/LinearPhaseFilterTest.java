package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinearPhaseFilterTest {

    @Test
    void shouldCreateFromBiquadParameters() {
        LinearPhaseFilter filter = LinearPhaseFilter.fromBiquad(
                BiquadFilter.FilterType.LOW_PASS, 44100.0, 5000.0, 0.707, 0, 255);
        assertThat(filter).isNotNull();
        assertThat(filter.getOrder()).isEqualTo(255);
        assertThat(filter.getLatency()).isEqualTo(127);
    }

    @Test
    void shouldMakeOrderOddWhenEven() {
        LinearPhaseFilter filter = LinearPhaseFilter.fromBiquad(
                BiquadFilter.FilterType.LOW_PASS, 44100.0, 5000.0, 0.707, 0, 256);
        // Even order should be rounded up to 257
        assertThat(filter.getOrder() % 2).isEqualTo(1);
    }

    @Test
    void shouldCreateFromMultipleBiquads() {
        BiquadFilter bq1 = BiquadFilter.create(BiquadFilter.FilterType.PEAK_EQ, 44100, 1000, 1.0, 6.0);
        BiquadFilter bq2 = BiquadFilter.create(BiquadFilter.FilterType.HIGH_SHELF, 44100, 8000, 0.707, -3.0);
        LinearPhaseFilter composite = LinearPhaseFilter.fromBiquads(new BiquadFilter[]{bq1, bq2}, 511);
        assertThat(composite).isNotNull();
        assertThat(composite.getOrder()).isEqualTo(511);
    }

    @Test
    void shouldPassThroughLowFrequencyWithLowPassFilter() {
        // Low-pass at 5 kHz, test with a 200 Hz sine — should pass through
        LinearPhaseFilter filter = LinearPhaseFilter.fromBiquad(
                BiquadFilter.FilterType.LOW_PASS, 44100.0, 5000.0, 0.707, 0, 1023);
        int latency = filter.getLatency();

        int totalSamples = latency + 4096;
        float[] buffer = new float[totalSamples];
        for (int i = 0; i < totalSamples; i++) {
            buffer[i] = (float) Math.sin(2.0 * Math.PI * 200.0 * i / 44100.0);
        }

        float[] inputCopy = buffer.clone();
        filter.process(buffer, 0, totalSamples);

        // After latency settling, compare RMS
        double inputRms = rms(inputCopy, latency + 1024, totalSamples);
        double outputRms = rms(buffer, latency + 1024, totalSamples);

        // Low frequency should pass with minimal attenuation
        assertThat(outputRms).isGreaterThan(inputRms * 0.8);
    }

    @Test
    void shouldAttenuateHighFrequencyWithLowPassFilter() {
        // Low-pass at 500 Hz, test with a 10 kHz sine — should be attenuated
        LinearPhaseFilter filter = LinearPhaseFilter.fromBiquad(
                BiquadFilter.FilterType.LOW_PASS, 44100.0, 500.0, 0.707, 0, 1023);
        int latency = filter.getLatency();

        int totalSamples = latency + 4096;
        float[] buffer = new float[totalSamples];
        for (int i = 0; i < totalSamples; i++) {
            buffer[i] = (float) Math.sin(2.0 * Math.PI * 10000.0 * i / 44100.0);
        }

        float[] inputCopy = buffer.clone();
        filter.process(buffer, 0, totalSamples);

        double inputRms = rms(inputCopy, latency + 1024, totalSamples);
        double outputRms = rms(buffer, latency + 1024, totalSamples);

        // High frequency should be significantly attenuated
        assertThat(outputRms).isLessThan(inputRms * 0.1);
    }

    @Test
    void shouldProduceFiniteOutput() {
        LinearPhaseFilter filter = LinearPhaseFilter.fromBiquad(
                BiquadFilter.FilterType.PEAK_EQ, 44100.0, 1000.0, 1.0, 6.0, 255);

        float[] buffer = new float[512];
        for (int i = 0; i < 512; i++) {
            buffer[i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
        }
        filter.process(buffer, 0, 512);

        for (float v : buffer) {
            assertThat(Float.isFinite(v)).isTrue();
        }
    }

    @Test
    void shouldResetState() {
        LinearPhaseFilter filter = LinearPhaseFilter.fromBiquad(
                BiquadFilter.FilterType.LOW_PASS, 44100.0, 1000.0, 0.707, 0, 127);

        float[] buf = {1.0f, 0.5f, -0.3f};
        filter.process(buf, 0, 3);
        filter.reset();

        // After reset, processing the same input should give the same first output
        LinearPhaseFilter fresh = LinearPhaseFilter.fromBiquad(
                BiquadFilter.FilterType.LOW_PASS, 44100.0, 1000.0, 0.707, 0, 127);
        float freshOut = fresh.processSample(0.7f);
        float resetOut = filter.processSample(0.7f);
        assertThat(resetOut).isCloseTo(freshOut, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldRejectInvalidFirOrder() {
        assertThatThrownBy(() -> LinearPhaseFilter.fromBiquad(
                BiquadFilter.FilterType.LOW_PASS, 44100, 1000, 0.707, 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHaveCorrectLatency() {
        LinearPhaseFilter filter = LinearPhaseFilter.fromBiquad(
                BiquadFilter.FilterType.LOW_PASS, 44100.0, 1000.0, 0.707, 0, 4095);
        assertThat(filter.getLatency()).isEqualTo(2047);
    }

    @Test
    void fftRoundTripShouldPreserveSignal() {
        int n = 16;
        double[] real = {1, 2, 3, 4, 5, 6, 7, 8, 8, 7, 6, 5, 4, 3, 2, 1};
        double[] imag = new double[n];
        double[] origReal = real.clone();

        LinearPhaseFilter.fft(real, imag, n);
        LinearPhaseFilter.ifft(real, imag, n);

        for (int i = 0; i < n; i++) {
            assertThat(real[i]).isCloseTo(origReal[i],
                    org.assertj.core.data.Offset.offset(1e-10));
        }
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
