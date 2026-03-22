package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelayProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        var delay = new DelayProcessor(2, 44100.0);
        assertThat(delay.getInputChannelCount()).isEqualTo(2);
        assertThat(delay.getOutputChannelCount()).isEqualTo(2);
        assertThat(delay.getFeedback()).isEqualTo(0.3);
        assertThat(delay.getMix()).isEqualTo(0.5);
    }

    @Test
    void shouldPassDrySignalWithZeroMix() {
        var delay = new DelayProcessor(1, 44100.0, 500.0);
        delay.setDelayMs(100.0);
        delay.setMix(0.0);
        delay.setFeedback(0.0);

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        delay.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldProduceDelayedSignalWithFullWetMix() {
        var delay = new DelayProcessor(1, 44100.0, 500.0);
        delay.setDelayMs(10.0);
        delay.setMix(1.0);
        delay.setFeedback(0.0);

        int delaySamples = (int) (10.0 * 0.001 * 44100.0);

        // Process an impulse
        float[][] input = new float[1][2048];
        float[][] output = new float[1][2048];
        input[0][0] = 1.0f;

        delay.process(input, output, 2048);

        // The first samples should be silent (delayed)
        for (int i = 0; i < delaySamples; i++) {
            assertThat(output[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
        // The impulse should appear at the delay time
        assertThat(output[0][delaySamples]).isCloseTo(1.0f,
                org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void shouldProduceFeedbackRepeats() {
        var delay = new DelayProcessor(1, 44100.0, 500.0);
        delay.setDelayMs(10.0);
        delay.setMix(1.0);
        delay.setFeedback(0.5);

        int delaySamples = (int) (10.0 * 0.001 * 44100.0);

        // Process an impulse
        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        input[0][0] = 1.0f;

        delay.process(input, output, 4096);

        // First echo
        assertThat(Math.abs(output[0][delaySamples])).isGreaterThan(0.5f);
        // Second echo (should be attenuated by feedback)
        assertThat(Math.abs(output[0][delaySamples * 2])).isGreaterThan(0.1f);
        assertThat(Math.abs(output[0][delaySamples * 2]))
                .isLessThan(Math.abs(output[0][delaySamples]));
    }

    @Test
    void shouldResetState() {
        var delay = new DelayProcessor(1, 44100.0, 500.0);
        delay.setDelayMs(10.0);
        delay.setMix(1.0);

        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        input[0][0] = 1.0f;
        delay.process(input, output, 256);

        delay.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[1][256];
        float[][] resetOutput = new float[1][256];
        delay.process(silence, resetOutput, 256);

        for (int i = 0; i < 256; i++) {
            assertThat(resetOutput[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new DelayProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayProcessor(2, 44100.0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidFeedback() {
        var delay = new DelayProcessor(1, 44100.0);
        assertThatThrownBy(() -> delay.setFeedback(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delay.setFeedback(1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMix() {
        var delay = new DelayProcessor(1, 44100.0);
        assertThatThrownBy(() -> delay.setMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> delay.setMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeDelayMs() {
        var delay = new DelayProcessor(1, 44100.0, 500.0);
        assertThatThrownBy(() -> delay.setDelayMs(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        var delay = new DelayProcessor(1, 44100.0, 500.0);
        delay.setDelayMs(200.0);
        delay.setFeedback(0.7);
        delay.setMix(0.8);

        assertThat(delay.getDelayMs()).isEqualTo(200.0);
        assertThat(delay.getFeedback()).isEqualTo(0.7);
        assertThat(delay.getMix()).isEqualTo(0.8);
    }
}
