package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimiterProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        var limiter = new LimiterProcessor(2, 44100.0);
        assertThat(limiter.getInputChannelCount()).isEqualTo(2);
        assertThat(limiter.getOutputChannelCount()).isEqualTo(2);
        assertThat(limiter.getCeilingDb()).isEqualTo(-0.3);
    }

    @Test
    void shouldLimitPeaksAboveCeiling() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setCeilingDb(-6.0); // Ceiling at -6 dBFS (~0.5 linear)

        int blockSize = 4096;
        float[][] input = new float[1][blockSize];
        float[][] output = new float[1][blockSize];
        for (int i = 0; i < blockSize; i++) {
            input[0][i] = 0.9f; // Well above ceiling
        }
        limiter.process(input, output, blockSize);

        // Output peaks (after settling) should not exceed ceiling
        double ceiling = Math.pow(10.0, -6.0 / 20.0);
        for (int i = blockSize / 2; i < blockSize; i++) {
            assertThat(Math.abs(output[0][i])).isLessThanOrEqualTo((float) (ceiling + 0.05));
        }
    }

    @Test
    void shouldPassThroughBelowCeiling() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setCeilingDb(0.0);

        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = 0.1f;
        }
        limiter.process(input, output, 256);

        // The limiter has a look-ahead delay, so output is delayed but not attenuated
        // After look-ahead period, output should match input
        int lookAhead = limiter.getLookAheadSamples();
        for (int i = lookAhead + 1; i < 256; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i - lookAhead],
                    org.assertj.core.data.Offset.offset(0.01f));
        }
    }

    @Test
    void shouldReportGainReduction() {
        var limiter = new LimiterProcessor(1, 44100.0);
        limiter.setCeilingDb(-6.0);

        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = 0.9f;
        }
        limiter.process(input, output, 256);

        assertThat(limiter.getGainReductionDb()).isLessThan(0.0);
    }

    @Test
    void shouldResetState() {
        var limiter = new LimiterProcessor(1, 44100.0);
        float[][] buf = {{0.9f, 0.8f, 0.7f}};
        limiter.process(buf, new float[1][3], 3);
        limiter.reset();

        assertThat(limiter.getGainReductionDb()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectPositiveCeiling() {
        var limiter = new LimiterProcessor(1, 44100.0);
        assertThatThrownBy(() -> limiter.setCeilingDb(1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new LimiterProcessor(0, 44100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimiterProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
