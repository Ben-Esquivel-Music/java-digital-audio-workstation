package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoiseGateProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        var gate = new NoiseGateProcessor(2, 44100.0);
        assertThat(gate.getInputChannelCount()).isEqualTo(2);
        assertThat(gate.getOutputChannelCount()).isEqualTo(2);
        assertThat(gate.getThresholdDb()).isEqualTo(-40.0);
    }

    @Test
    void shouldAttenuateSignalBelowThreshold() {
        var gate = new NoiseGateProcessor(1, 44100.0);
        gate.setThresholdDb(-10.0); // High threshold
        gate.setRangeDb(-80.0);
        gate.setAttackMs(0.1);
        gate.setReleaseMs(10.0);
        gate.setHoldMs(0.1);

        float[][] input = new float[1][2048];
        float[][] output = new float[1][2048];
        // Quiet signal well below threshold
        for (int i = 0; i < 2048; i++) {
            input[0][i] = 0.01f;
        }
        gate.process(input, output, 2048);

        // Output should be significantly attenuated
        double outputRms = rms(output[0], 1024, 2048);
        double inputRms = rms(input[0], 1024, 2048);
        assertThat(outputRms).isLessThan(inputRms * 0.5);
    }

    @Test
    void shouldPassSignalAboveThreshold() {
        var gate = new NoiseGateProcessor(1, 44100.0);
        gate.setThresholdDb(-40.0); // Low threshold
        gate.setAttackMs(0.01);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        // Loud signal well above threshold
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        gate.process(input, output, 4096);

        // After gate opens, output should be close to input
        double inputRms = rms(input[0], 2048, 4096);
        double outputRms = rms(output[0], 2048, 4096);
        assertThat(outputRms).isGreaterThan(inputRms * 0.8);
    }

    @Test
    void shouldResetState() {
        var gate = new NoiseGateProcessor(1, 44100.0);
        float[][] buf = {{0.9f, 0.8f}};
        gate.process(buf, new float[1][2], 2);
        gate.reset();
        // Should not throw
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new NoiseGateProcessor(0, 44100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NoiseGateProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        var gate = new NoiseGateProcessor(1, 44100.0);
        gate.setThresholdDb(-30.0);
        gate.setAttackMs(5.0);
        gate.setHoldMs(100.0);
        gate.setReleaseMs(200.0);
        gate.setRangeDb(-60.0);

        assertThat(gate.getThresholdDb()).isEqualTo(-30.0);
        assertThat(gate.getAttackMs()).isEqualTo(5.0);
        assertThat(gate.getHoldMs()).isEqualTo(100.0);
        assertThat(gate.getReleaseMs()).isEqualTo(200.0);
        assertThat(gate.getRangeDb()).isEqualTo(-60.0);
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
