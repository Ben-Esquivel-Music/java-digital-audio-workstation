package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VelvetNoiseReverbProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(2, 44100.0);
        assertThat(reverb.getInputChannelCount()).isEqualTo(2);
        assertThat(reverb.getOutputChannelCount()).isEqualTo(2);
        assertThat(reverb.getDecayTime()).isEqualTo(0.5);
        assertThat(reverb.getDensity()).isEqualTo(0.5);
        assertThat(reverb.getEarlyLateMix()).isEqualTo(0.5);
        assertThat(reverb.getDamping()).isEqualTo(0.5);
        assertThat(reverb.getMix()).isEqualTo(0.3);
    }

    @Test
    void shouldPassDrySignalWithZeroMix() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(0.0);

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        reverb.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-5f));
        }
    }

    @Test
    void shouldProduceReverbTailAfterImpulse() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);
        reverb.setDecayTime(0.8);

        // Process an impulse
        float[][] input = new float[1][8192];
        float[][] output = new float[1][8192];
        input[0][0] = 1.0f;
        reverb.process(input, output, 8192);

        // The tail should have energy after the initial impulse
        double tailRms = rms(output[0], 2048, 8192);
        assertThat(tailRms).isGreaterThan(0.0);
    }

    @Test
    void shouldDecayOverTime() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);
        reverb.setDecayTime(0.5);
        reverb.setDamping(0.0);

        // Process an impulse
        int len = 44100; // 1 second
        float[][] input = new float[1][len];
        float[][] output = new float[1][len];
        input[0][0] = 1.0f;
        reverb.process(input, output, len);

        // Early portion should be louder than late portion
        double earlyRms = rms(output[0], 2048, 8192);
        double lateRms = rms(output[0], len - 8192, len);
        assertThat(earlyRms).isGreaterThan(lateRms);
    }

    @Test
    void shouldModifySignalComparedToDry() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(0.5);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        reverb.process(input, output, 4096);

        boolean differs = false;
        for (int i = 2048; i < 4096; i++) {
            if (Math.abs(output[0][i] - input[0][i]) > 0.001f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void shouldResetState() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        input[0][0] = 1.0f;
        reverb.process(input, output, 4096);

        reverb.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[1][4096];
        float[][] resetOutput = new float[1][4096];
        reverb.process(silence, resetOutput, 4096);

        for (int i = 0; i < 4096; i++) {
            assertThat(resetOutput[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new VelvetNoiseReverbProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VelvetNoiseReverbProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDecayTime() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setDecayTime(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setDecayTime(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDensity() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setDensity(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setDensity(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidEarlyLateMix() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setEarlyLateMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setEarlyLateMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDamping() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setDamping(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setDamping(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMix() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb.setDecayTime(0.8);
        reverb.setDensity(0.7);
        reverb.setEarlyLateMix(0.3);
        reverb.setDamping(0.2);
        reverb.setMix(0.6);

        assertThat(reverb.getDecayTime()).isEqualTo(0.8);
        assertThat(reverb.getDensity()).isEqualTo(0.7);
        assertThat(reverb.getEarlyLateMix()).isEqualTo(0.3);
        assertThat(reverb.getDamping()).isEqualTo(0.2);
        assertThat(reverb.getMix()).isEqualTo(0.6);
    }

    @Test
    void shouldProcessStereo() {
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(2, 44100.0);
        reverb.setMix(1.0);
        reverb.setDamping(0.0);

        float[][] input = new float[2][4096];
        float[][] output = new float[2][4096];
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        reverb.process(input, output, 4096);

        // Both channels should have reverb tail
        assertThat(rms(output[0], 2048, 4096)).isGreaterThan(0.0);
        assertThat(rms(output[1], 2048, 4096)).isGreaterThan(0.0);
    }

    @Test
    void shouldRespondToDensityChange() {
        // Different density settings should produce different impulse responses
        VelvetNoiseReverbProcessor reverb1 = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb1.setMix(1.0);
        reverb1.setDensity(0.1);
        reverb1.setDamping(0.0);

        VelvetNoiseReverbProcessor reverb2 = new VelvetNoiseReverbProcessor(1, 44100.0);
        reverb2.setMix(1.0);
        reverb2.setDensity(0.9);
        reverb2.setDamping(0.0);

        float[][] input = new float[1][8192];
        input[0][0] = 1.0f;
        float[][] output1 = new float[1][8192];
        float[][] output2 = new float[1][8192];

        reverb1.process(input, output1, 8192);
        reverb2.process(input, output2, 8192);

        // Outputs should differ due to different densities
        boolean differs = false;
        for (int i = 1024; i < 8192; i++) {
            if (Math.abs(output1[0][i] - output2[0][i]) > 1e-6f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void shouldProduceStereoDecorrelation() {
        // Stereo channels should have different impulse responses for decorrelation
        VelvetNoiseReverbProcessor reverb = new VelvetNoiseReverbProcessor(2, 44100.0);
        reverb.setMix(1.0);
        reverb.setDamping(0.0);

        float[][] input = new float[2][8192];
        float[][] output = new float[2][8192];
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        reverb.process(input, output, 8192);

        // Left and right channels should differ due to decorrelated sequences
        boolean differs = false;
        for (int i = 1024; i < 8192; i++) {
            if (Math.abs(output[0][i] - output[1][i]) > 1e-6f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
