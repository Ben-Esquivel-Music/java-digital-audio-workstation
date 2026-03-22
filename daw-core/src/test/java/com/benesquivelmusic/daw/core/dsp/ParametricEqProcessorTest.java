package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParametricEqProcessorTest {

    @Test
    void shouldCreateWithValidParameters() {
        var eq = new ParametricEqProcessor(2, 44100.0);
        assertThat(eq.getInputChannelCount()).isEqualTo(2);
        assertThat(eq.getOutputChannelCount()).isEqualTo(2);
        assertThat(eq.getBands()).isEmpty();
    }

    @Test
    void shouldAddBands() {
        var eq = new ParametricEqProcessor(2, 44100.0);
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 6.0));
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.HIGH_SHELF, 8000.0, 0.707, 3.0));

        assertThat(eq.getBands()).hasSize(2);
    }

    @Test
    void shouldPassThroughWithNoBands() {
        var eq = new ParametricEqProcessor(2, 44100.0);

        float[][] input = {{0.5f, -0.3f, 0.8f}, {0.2f, 0.1f, -0.4f}};
        float[][] output = new float[2][3];
        eq.process(input, output, 3);

        assertThat(output[0]).containsExactly(0.5f, -0.3f, 0.8f);
        assertThat(output[1]).containsExactly(0.2f, 0.1f, -0.4f);
    }

    @Test
    void shouldProcessWithBands() {
        var eq = new ParametricEqProcessor(1, 44100.0);
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
        var eq = new ParametricEqProcessor(2, 44100.0);
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 6.0));
        eq.updateBand(0, ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.PEAK_EQ, 2000.0, 1.0, -3.0));

        assertThat(eq.getBands().getFirst().frequency()).isEqualTo(2000.0);
    }

    @Test
    void shouldRemoveBand() {
        var eq = new ParametricEqProcessor(2, 44100.0);
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.PEAK_EQ, 1000.0, 1.0, 6.0));
        eq.addBand(ParametricEqProcessor.BandConfig.of(BiquadFilter.FilterType.HIGH_SHELF, 8000.0, 0.707, 3.0));
        eq.removeBand(0);

        assertThat(eq.getBands()).hasSize(1);
        assertThat(eq.getBands().getFirst().frequency()).isEqualTo(8000.0);
    }

    @Test
    void shouldBypassDisabledBands() {
        var eq = new ParametricEqProcessor(1, 44100.0);
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
        var eq = new ParametricEqProcessor(2, 44100.0);
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
}
