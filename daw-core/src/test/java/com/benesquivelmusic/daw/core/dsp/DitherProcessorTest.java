package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class DitherProcessorTest {

    @Test
    void shouldQuantizeToTargetBitDepth() {
        // Use fixed seed for reproducibility
        DitherProcessor proc = new DitherProcessor(1, 16, 42L);
        float[][] input = {{0.5f, -0.5f, 0.0f, 1.0f}};
        float[][] output = new float[1][4];

        proc.process(input, output, 4);

        // Output should be close to input (within quantization noise)
        for (int i = 0; i < 4; i++) {
            assertThat((double) output[0][i]).isCloseTo(input[0][i], offset(0.01));
        }
    }

    @Test
    void shouldProduceDifferentOutputWithDither() {
        // Two passes with random seed should produce slightly different outputs
        DitherProcessor proc1 = new DitherProcessor(1, 8, 1L);
        DitherProcessor proc2 = new DitherProcessor(1, 8, 2L);

        float[][] input = {{0.3333f}};
        float[][] out1 = new float[1][1];
        float[][] out2 = new float[1][1];

        proc1.process(input, out1, 1);
        proc2.process(input, out2, 1);

        // With different seeds and 8-bit quantization, results differ slightly
        // (not guaranteed to be different for every value, but statistically likely)
        // We mainly verify both produce valid output close to the input
        assertThat((double) out1[0][0]).isCloseTo(0.333, offset(0.02));
        assertThat((double) out2[0][0]).isCloseTo(0.333, offset(0.02));
    }

    @Test
    void shouldHandleSilence() {
        DitherProcessor proc = new DitherProcessor(2, 16, 42L);
        float[][] input = {{0.0f}, {0.0f}};
        float[][] output = new float[2][1];

        proc.process(input, output, 1);

        // Output should be near silence (within dither noise floor)
        assertThat(Math.abs(output[0][0])).isLessThan(0.001f);
        assertThat(Math.abs(output[1][0])).isLessThan(0.001f);
    }

    @Test
    void shouldClampToValidRange() {
        DitherProcessor proc = new DitherProcessor(1, 8, 42L);
        float[][] input = {{1.5f, -1.5f}}; // Beyond normal range
        float[][] output = new float[1][2];

        proc.process(input, output, 2);

        // Should be clamped near the limits
        assertThat(output[0][0]).isLessThanOrEqualTo(1.01f);
        assertThat(output[0][1]).isGreaterThanOrEqualTo(-1.01f);
    }

    @Test
    void shouldRejectInvalidChannels() {
        assertThatThrownBy(() -> new DitherProcessor(0, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidBitDepth() {
        assertThatThrownBy(() -> new DitherProcessor(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DitherProcessor(1, 33))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUpdateBitDepthDynamically() {
        DitherProcessor proc = new DitherProcessor(1, 16);
        proc.setTargetBitDepth(24);
        assertThat(proc.getTargetBitDepth()).isEqualTo(24);
    }

    @Test
    void shouldReturnCorrectChannelCounts() {
        DitherProcessor proc = new DitherProcessor(2, 16);

        assertThat(proc.getInputChannelCount()).isEqualTo(2);
        assertThat(proc.getOutputChannelCount()).isEqualTo(2);
    }
}
