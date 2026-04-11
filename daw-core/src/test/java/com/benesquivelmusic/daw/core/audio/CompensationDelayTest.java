package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompensationDelayTest {

    @Test
    void shouldPassThroughWhenDelayIsZero() {
        var delay = new CompensationDelay(1, 0);
        float[][] buffer = {{1.0f, 2.0f, 3.0f}};

        delay.process(buffer, 3);

        assertThat(buffer[0]).containsExactly(1.0f, 2.0f, 3.0f);
    }

    @Test
    void shouldDelayBySingleSample() {
        var delay = new CompensationDelay(1, 1);
        float[][] buffer = {{1.0f, 2.0f, 3.0f}};

        delay.process(buffer, 3);

        // First sample is silence (initial delay), then original samples shift right
        assertThat(buffer[0]).containsExactly(0.0f, 1.0f, 2.0f);
    }

    @Test
    void shouldDelayByMultipleSamples() {
        var delay = new CompensationDelay(1, 3);
        float[][] buffer = {{1.0f, 2.0f, 3.0f, 4.0f, 5.0f}};

        delay.process(buffer, 5);

        // First 3 samples are silence, then original data
        assertThat(buffer[0]).containsExactly(0.0f, 0.0f, 0.0f, 1.0f, 2.0f);
    }

    @Test
    void shouldMaintainStateAcrossBlocks() {
        var delay = new CompensationDelay(1, 2);

        float[][] block1 = {{1.0f, 2.0f}};
        delay.process(block1, 2);
        assertThat(block1[0]).containsExactly(0.0f, 0.0f);

        // Second block should output the delayed samples from block 1
        float[][] block2 = {{3.0f, 4.0f}};
        delay.process(block2, 2);
        assertThat(block2[0]).containsExactly(1.0f, 2.0f);

        // Third block
        float[][] block3 = {{5.0f, 6.0f}};
        delay.process(block3, 2);
        assertThat(block3[0]).containsExactly(3.0f, 4.0f);
    }

    @Test
    void shouldHandleStereoChannels() {
        var delay = new CompensationDelay(2, 1);
        float[][] buffer = {
                {1.0f, 2.0f},
                {10.0f, 20.0f}
        };

        delay.process(buffer, 2);

        assertThat(buffer[0]).containsExactly(0.0f, 1.0f);
        assertThat(buffer[1]).containsExactly(0.0f, 10.0f);
    }

    @Test
    void shouldResetToSilence() {
        var delay = new CompensationDelay(1, 2);

        // Prime the delay with data
        float[][] buffer = {{1.0f, 2.0f}};
        delay.process(buffer, 2);

        delay.reset();

        // After reset, delay buffer should be silence again
        float[][] buffer2 = {{3.0f, 4.0f}};
        delay.process(buffer2, 2);
        assertThat(buffer2[0]).containsExactly(0.0f, 0.0f);
    }

    @Test
    void shouldReportDelaySamples() {
        assertThat(new CompensationDelay(1, 0).getDelaySamples()).isEqualTo(0);
        assertThat(new CompensationDelay(1, 42).getDelaySamples()).isEqualTo(42);
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() -> new CompensationDelay(0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompensationDelay(1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
