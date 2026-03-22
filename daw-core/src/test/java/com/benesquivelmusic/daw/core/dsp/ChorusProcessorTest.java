package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChorusProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        var chorus = new ChorusProcessor(2, 44100.0);
        assertThat(chorus.getInputChannelCount()).isEqualTo(2);
        assertThat(chorus.getOutputChannelCount()).isEqualTo(2);
        assertThat(chorus.getRateHz()).isEqualTo(1.5);
        assertThat(chorus.getDepthMs()).isEqualTo(3.0);
        assertThat(chorus.getBaseDelayMs()).isEqualTo(7.0);
        assertThat(chorus.getMix()).isEqualTo(0.5);
    }

    @Test
    void shouldPassDrySignalWithZeroMix() {
        var chorus = new ChorusProcessor(1, 44100.0);
        chorus.setMix(0.0);

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        chorus.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldModifySignalWithDefaultSettings() {
        var chorus = new ChorusProcessor(1, 44100.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        chorus.process(input, output, 4096);

        // Output should differ from input due to chorus modulation
        boolean differs = false;
        for (int i = 512; i < 4096; i++) { // Skip initial transient
            if (Math.abs(output[0][i] - input[0][i]) > 0.001f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void shouldNotClipOutput() {
        var chorus = new ChorusProcessor(1, 44100.0);
        chorus.setMix(0.5);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = 0.9f;
        }
        chorus.process(input, output, 4096);

        for (int i = 0; i < 4096; i++) {
            assertThat(Math.abs(output[0][i])).isLessThanOrEqualTo(1.5f);
        }
    }

    @Test
    void shouldResetState() {
        var chorus = new ChorusProcessor(1, 44100.0);

        float[][] input = new float[1][1024];
        float[][] output = new float[1][1024];
        java.util.Arrays.fill(input[0], 0.5f);
        chorus.process(input, output, 1024);

        chorus.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[1][1024];
        float[][] resetOutput = new float[1][1024];
        chorus.process(silence, resetOutput, 1024);

        // Output should be very close to zero (only delay line contents matter)
        double rms = rms(resetOutput[0], 512, 1024);
        assertThat(rms).isLessThan(0.01);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new ChorusProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChorusProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidRate() {
        var chorus = new ChorusProcessor(1, 44100.0);
        assertThatThrownBy(() -> chorus.setRateHz(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chorus.setRateHz(11.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDepth() {
        var chorus = new ChorusProcessor(1, 44100.0);
        assertThatThrownBy(() -> chorus.setDepthMs(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMix() {
        var chorus = new ChorusProcessor(1, 44100.0);
        assertThatThrownBy(() -> chorus.setMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chorus.setMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        var chorus = new ChorusProcessor(1, 44100.0);
        chorus.setRateHz(2.5);
        chorus.setDepthMs(5.0);
        chorus.setBaseDelayMs(10.0);
        chorus.setMix(0.7);

        assertThat(chorus.getRateHz()).isEqualTo(2.5);
        assertThat(chorus.getDepthMs()).isEqualTo(5.0);
        assertThat(chorus.getBaseDelayMs()).isEqualTo(10.0);
        assertThat(chorus.getMix()).isEqualTo(0.7);
    }

    @Test
    void shouldProcessStereoChannelsIndependently() {
        var chorus = new ChorusProcessor(2, 44100.0);
        chorus.setMix(1.0);

        float[][] input = new float[2][2048];
        float[][] output = new float[2][2048];
        // Different signal per channel
        for (int i = 0; i < 2048; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
            input[1][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 880.0 * i / 44100.0));
        }
        chorus.process(input, output, 2048);

        // Both channels should have signal
        assertThat(rms(output[0], 512, 2048)).isGreaterThan(0.01);
        assertThat(rms(output[1], 512, 2048)).isGreaterThan(0.01);
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
