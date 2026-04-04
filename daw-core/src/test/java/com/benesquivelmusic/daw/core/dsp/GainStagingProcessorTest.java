package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class GainStagingProcessorTest {

    @Test
    void shouldPassThroughAtZeroGain() {
        GainStagingProcessor proc = new GainStagingProcessor(2, 0.0);
        float[][] input = {{0.5f, -0.3f}, {0.7f, -0.1f}};
        float[][] output = new float[2][2];

        proc.process(input, output, 2);

        assertThat(output[0]).containsExactly(0.5f, -0.3f);
        assertThat(output[1]).containsExactly(0.7f, -0.1f);
    }

    @Test
    void shouldApplyPositiveGain() {
        GainStagingProcessor proc = new GainStagingProcessor(1, 6.0);
        float[][] input = {{0.5f}};
        float[][] output = new float[1][1];

        proc.process(input, output, 1);

        // +6 dB ≈ 2× gain
        assertThat((double) output[0][0]).isCloseTo(1.0, offset(0.02));
    }

    @Test
    void shouldApplyNegativeGain() {
        GainStagingProcessor proc = new GainStagingProcessor(1, -6.0);
        float[][] input = {{1.0f}};
        float[][] output = new float[1][1];

        proc.process(input, output, 1);

        // -6 dB ≈ 0.5× gain
        assertThat((double) output[0][0]).isCloseTo(0.5, offset(0.02));
    }

    @Test
    void shouldUpdateGainDynamically() {
        GainStagingProcessor proc = new GainStagingProcessor(1, 0.0);
        proc.setGainDb(12.0);

        assertThat(proc.getGainDb()).isEqualTo(12.0);

        float[][] input = {{0.25f}};
        float[][] output = new float[1][1];
        proc.process(input, output, 1);

        // +12 dB ≈ 4× gain
        assertThat((double) output[0][0]).isCloseTo(1.0, offset(0.02));
    }

    @Test
    void shouldRejectInvalidChannels() {
        assertThatThrownBy(() -> new GainStagingProcessor(0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnCorrectChannelCounts() {
        GainStagingProcessor proc = new GainStagingProcessor(2, 0.0);

        assertThat(proc.getInputChannelCount()).isEqualTo(2);
        assertThat(proc.getOutputChannelCount()).isEqualTo(2);
    }
}
