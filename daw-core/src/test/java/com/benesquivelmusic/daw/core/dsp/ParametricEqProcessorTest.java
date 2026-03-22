package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParametricEqProcessorTest {

    @Test
    void shouldCreateWithValidParameters() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        assertThat(eq.getInputChannelCount()).isEqualTo(2);
        assertThat(eq.getOutputChannelCount()).isEqualTo(2);
        assertThat(eq.getBands()).isEmpty();
    }

    @Test
    void shouldAddBands() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 6.0));
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.HIGH_SHELF, 8000.0, 0.707, 3.0));

        assertThat(eq.getBands()).hasSize(2);
    }

    @Test
    void shouldPassThroughWithNoBands() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);

        float[][] input = {{0.5f, -0.3f, 0.8f}, {0.2f, 0.1f, -0.4f}};
        float[][] output = new float[2][3];
        eq.process(input, output, 3);

        assertThat(output[0]).containsExactly(0.5f, -0.3f, 0.8f);
        assertThat(output[1]).containsExactly(0.2f, 0.1f, -0.4f);
    }

    @Test
    void shouldProcessWithBands() {
        ParametricEqProcessor eq = new ParametricEqProcessor(1, 44100.0);
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.LOW_PASS, 5000.0, 0.707, 0));

        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
        }
        eq.process(input, output, 256);

        // Output should have finite values
        for (float v : output[0]) {
            assertThat(Float.isFinite(v)).isTrue();
        }
    }

    @Test
    void shouldUpdateBand() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 6.0));
        eq.updateBand(0, ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.PEAK_EQ, 2000.0, 1.0, -3.0));

        assertThat(eq.getBands().getFirst().frequency()).isEqualTo(2000.0);
    }

    @Test
    void shouldRemoveBand() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 6.0));
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.HIGH_SHELF, 8000.0, 0.707, 3.0));
        eq.removeBand(0);

        assertThat(eq.getBands()).hasSize(1);
        assertThat(eq.getBands().getFirst().frequency()).isEqualTo(8000.0);
    }

    @Test
    void shouldBypassDisabledBands() {
        ParametricEqProcessor eq = new ParametricEqProcessor(1, 44100.0);
        eq.addBand(new ParametricEqProcessor.BandConfig(
                BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 20.0, false));

        float[][] input = new float[1][128];
        float[][] output = new float[1][128];
        for (int i = 0; i < 128; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
        }
        eq.process(input, output, 128);

        // Disabled band should pass through unchanged
        assertThat(output[0]).containsExactly(input[0]);
    }

    @Test
    void shouldResetAllFilters() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 6.0));

        float[][] input = new float[2][64];
        float[][] output = new float[2][64];
        eq.process(input, output, 64);
        eq.reset();
        // Should not throw
    }

    @Test
    void shouldRejectInvalidBandConfig() {
        assertThatThrownBy(() -> ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.PEAK_EQ, 0, 1.0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.PEAK_EQ, 1000, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ParametricEqProcessor.BandConfig.of(
                null, 1000, 1.0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new ParametricEqProcessor(0, 44100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParametricEqProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- FilterMode tests ----

    @Test
    void shouldDefaultToMinimumPhaseAndStereo() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        assertThat(eq.getFilterMode()).isEqualTo(ParametricEqProcessor.FilterMode.MINIMUM_PHASE);
        assertThat(eq.getProcessingMode()).isEqualTo(ParametricEqProcessor.ProcessingMode.STEREO);
        assertThat(eq.getLatencySamples()).isEqualTo(0);
    }

    @Test
    void shouldReportLatencyInLinearPhaseMode() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.setFilterMode(ParametricEqProcessor.FilterMode.LINEAR_PHASE);

        // Default firOrder is 4095, latency = (4095-1)/2 = 2047
        assertThat(eq.getLatencySamples()).isEqualTo(2047);
    }

    @Test
    void shouldReportZeroLatencyInMinimumPhaseMode() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.setFilterMode(ParametricEqProcessor.FilterMode.MINIMUM_PHASE);
        assertThat(eq.getLatencySamples()).isEqualTo(0);
    }

    @Test
    void shouldSetAndGetFirOrder() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.setFirOrder(1023);
        assertThat(eq.getFirOrder()).isEqualTo(1023);
    }

    @Test
    void shouldRejectInvalidFirOrder() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        assertThatThrownBy(() -> eq.setFirOrder(2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldProcessInLinearPhaseMode() {
        ParametricEqProcessor eq = new ParametricEqProcessor(1, 44100.0);
        eq.setFilterMode(ParametricEqProcessor.FilterMode.LINEAR_PHASE);
        eq.setFirOrder(255);
        eq.addBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.LOW_PASS, 5000.0, 0.707, 0));

        int latency = eq.getLatencySamples();
        int totalSamples = latency + 2048;
        float[][] input = new float[1][totalSamples];
        float[][] output = new float[1][totalSamples];
        for (int i = 0; i < totalSamples; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 200.0 * i / 44100.0);
        }
        eq.process(input, output, totalSamples);

        // Output should have finite values and pass low-frequency signal
        for (float v : output[0]) {
            assertThat(Float.isFinite(v)).isTrue();
        }
        double inputRms = rms(input[0], latency + 512, totalSamples);
        double outputRms = rms(output[0], latency + 512, totalSamples);
        assertThat(outputRms).isGreaterThan(inputRms * 0.5);
    }

    @Test
    void shouldPassThroughLinearPhaseWithNoBands() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.setFilterMode(ParametricEqProcessor.FilterMode.LINEAR_PHASE);

        float[][] input = {{0.5f, -0.3f, 0.8f}, {0.2f, 0.1f, -0.4f}};
        float[][] output = new float[2][3];
        eq.process(input, output, 3);

        // No bands → pass through
        assertThat(output[0]).containsExactly(0.5f, -0.3f, 0.8f);
        assertThat(output[1]).containsExactly(0.2f, 0.1f, -0.4f);
    }

    // ---- M/S mode tests ----

    @Test
    void shouldManageMidAndSideBands() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.setProcessingMode(ParametricEqProcessor.ProcessingMode.MID_SIDE);

        eq.addMidBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 3.0));
        eq.addSideBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.HIGH_SHELF, 8000.0, 0.707, 6.0));

        assertThat(eq.getMidBands()).hasSize(1);
        assertThat(eq.getSideBands()).hasSize(1);
        assertThat(eq.getMidBands().getFirst().frequency()).isEqualTo(1000.0);
        assertThat(eq.getSideBands().getFirst().frequency()).isEqualTo(8000.0);
    }

    @Test
    void shouldUpdateAndRemoveMidSideBands() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.addMidBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 3.0));
        eq.addMidBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.PEAK_EQ, 2000.0, 1.0, -2.0));
        eq.updateMidBand(0, ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.PEAK_EQ, 500.0, 1.5, 4.0));
        assertThat(eq.getMidBands().getFirst().frequency()).isEqualTo(500.0);

        eq.removeMidBand(0);
        assertThat(eq.getMidBands()).hasSize(1);
        assertThat(eq.getMidBands().getFirst().frequency()).isEqualTo(2000.0);

        eq.addSideBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.HIGH_SHELF, 8000.0, 0.707, 3.0));
        eq.updateSideBand(0, ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.HIGH_SHELF, 10000.0, 0.707, 5.0));
        assertThat(eq.getSideBands().getFirst().frequency()).isEqualTo(10000.0);
        eq.removeSideBand(0);
        assertThat(eq.getSideBands()).isEmpty();
    }

    @Test
    void shouldPassThroughMidSideWithNoBands() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.setProcessingMode(ParametricEqProcessor.ProcessingMode.MID_SIDE);

        float[][] input = {{0.5f, -0.3f, 0.8f}, {0.2f, 0.1f, -0.4f}};
        float[][] output = new float[2][3];
        eq.process(input, output, 3);

        // M/S encode then decode with no processing = pass through
        for (int i = 0; i < 3; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
            assertThat(output[1][i]).isCloseTo(input[1][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldProcessMidSideWithIndependentBands() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.setProcessingMode(ParametricEqProcessor.ProcessingMode.MID_SIDE);

        // Apply a strong low-pass to the side channel only
        eq.addSideBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.LOW_PASS, 100.0, 0.707, 0));

        int numFrames = 4096;
        float[][] input = new float[2][numFrames];
        float[][] output = new float[2][numFrames];

        // Generate a high-frequency stereo difference signal
        for (int i = 0; i < numFrames; i++) {
            double highFreqSine = Math.sin(2.0 * Math.PI * 10000.0 * i / 44100.0);
            input[0][i] = (float) highFreqSine; // L = high freq
            input[1][i] = (float) -highFreqSine; // R = -high freq (pure side signal)
        }

        eq.process(input, output, numFrames);

        // The side channel is low-passed at 100 Hz, so the 10 kHz side content
        // should be strongly attenuated. The output L and R should have reduced
        // amplitude compared to the input.
        double inputRms = rms(input[0], 2048, numFrames);
        double outputRmsL = rms(output[0], 2048, numFrames);
        assertThat(outputRmsL).isLessThan(inputRms * 0.5);
    }

    @Test
    void shouldProcessMidSideInLinearPhaseMode() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.setProcessingMode(ParametricEqProcessor.ProcessingMode.MID_SIDE);
        eq.setFilterMode(ParametricEqProcessor.FilterMode.LINEAR_PHASE);
        eq.setFirOrder(255);

        eq.addMidBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 6.0));
        eq.addSideBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.HIGH_SHELF, 8000.0, 0.707, 3.0));

        int latency = eq.getLatencySamples();
        int totalSamples = latency + 2048;
        float[][] input = new float[2][totalSamples];
        float[][] output = new float[2][totalSamples];

        for (int i = 0; i < totalSamples; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0 + 0.5);
        }

        eq.process(input, output, totalSamples);

        // Output should have finite values
        for (float v : output[0]) {
            assertThat(Float.isFinite(v)).isTrue();
        }
        for (float v : output[1]) {
            assertThat(Float.isFinite(v)).isTrue();
        }
    }

    @Test
    void shouldResetMidSideAndLinearPhaseFilters() {
        ParametricEqProcessor eq = new ParametricEqProcessor(2, 44100.0);
        eq.setProcessingMode(ParametricEqProcessor.ProcessingMode.MID_SIDE);
        eq.setFilterMode(ParametricEqProcessor.FilterMode.LINEAR_PHASE);
        eq.setFirOrder(127);

        eq.addMidBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 6.0));
        eq.addSideBand(ParametricEqProcessor.BandConfig.of(
                BiquadFilter.FilterType.HIGH_SHELF, 8000.0, 0.707, 3.0));

        float[][] input = new float[2][256];
        float[][] output = new float[2][256];
        eq.process(input, output, 256);

        // Should not throw
        eq.reset();
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
