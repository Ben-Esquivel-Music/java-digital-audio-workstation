package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompressorProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        var comp = new CompressorProcessor(2, 44100.0);
        assertThat(comp.getInputChannelCount()).isEqualTo(2);
        assertThat(comp.getOutputChannelCount()).isEqualTo(2);
        assertThat(comp.getThresholdDb()).isEqualTo(-20.0);
        assertThat(comp.getRatio()).isEqualTo(4.0);
    }

    @Test
    void shouldPassThroughBelowThreshold() {
        var comp = new CompressorProcessor(1, 44100.0);
        comp.setThresholdDb(0.0); // Threshold at 0 dB — nothing should compress

        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = 0.1f; // Well below threshold
        }
        comp.process(input, output, 256);

        // Output should be close to input (no compression)
        for (int i = 128; i < 256; i++) { // Skip initial transient
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(0.02f));
        }
    }

    @Test
    void shouldReduceGainAboveThreshold() {
        var comp = new CompressorProcessor(1, 44100.0);
        comp.setThresholdDb(-20.0);
        comp.setRatio(10.0);
        comp.setAttackMs(0.01); // Very fast attack
        comp.setKneeDb(0.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        // Generate a loud signal at ~0 dBFS
        for (int i = 0; i < input[0].length; i++) {
            input[0][i] = 0.9f;
        }
        comp.process(input, output, input[0].length);

        // After the compressor settles, output should be less than input
        double inputRms = rms(input[0], 2048, 4096);
        double outputRms = rms(output[0], 2048, 4096);
        assertThat(outputRms).isLessThan(inputRms);
    }

    @Test
    void shouldReportGainReduction() {
        var comp = new CompressorProcessor(1, 44100.0);
        comp.setThresholdDb(-20.0);
        comp.setRatio(4.0);
        comp.setAttackMs(0.01);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = 0.9f;
        }
        comp.process(input, output, 4096);

        assertThat(comp.getGainReductionDb()).isLessThanOrEqualTo(0.0);
    }

    @Test
    void shouldApplyMakeupGain() {
        var comp = new CompressorProcessor(1, 44100.0);
        comp.setThresholdDb(-60.0); // Very low threshold
        comp.setRatio(10.0);
        comp.setMakeupGainDb(20.0);
        comp.setAttackMs(0.01);

        float[][] input = new float[1][256];
        float[][] output = new float[1][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = 0.5f;
        }
        comp.process(input, output, 256);

        assertThat(comp.getMakeupGainDb()).isEqualTo(20.0);
    }

    @Test
    void shouldResetState() {
        var comp = new CompressorProcessor(1, 44100.0);
        float[][] buf = new float[1][128];
        java.util.Arrays.fill(buf[0], 0.9f);
        comp.process(buf, new float[1][128], 128);

        comp.reset();
        assertThat(comp.getGainReductionDb()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectInvalidRatio() {
        var comp = new CompressorProcessor(1, 44100.0);
        assertThatThrownBy(() -> comp.setRatio(0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportDetectionModes() {
        var comp = new CompressorProcessor(1, 44100.0);
        comp.setDetectionMode(CompressorProcessor.DetectionMode.RMS);
        assertThat(comp.getDetectionMode()).isEqualTo(CompressorProcessor.DetectionMode.RMS);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new CompressorProcessor(0, 44100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompressorProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
