package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringReverbProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(2, 44100.0);
        assertThat(reverb.getInputChannelCount()).isEqualTo(2);
        assertThat(reverb.getOutputChannelCount()).isEqualTo(2);
        assertThat(reverb.getSpringTension()).isEqualTo(0.5);
        assertThat(reverb.getDecayTime()).isEqualTo(0.5);
        assertThat(reverb.getDamping()).isEqualTo(0.5);
        assertThat(reverb.getMix()).isEqualTo(0.3);
        assertThat(reverb.getPreDelayMs()).isEqualTo(10.0);
        assertThat(reverb.getHelixAngle()).isEqualTo(0.5);
    }

    @Test
    void shouldPassDrySignalWithZeroMix() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
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
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
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
    void shouldExhibitDispersion() {
        // Dispersion means higher frequencies arrive later than lower frequencies.
        // Feed a broadband impulse and check that output spreads over time.
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);
        reverb.setDecayTime(0.6);
        reverb.setHelixAngle(0.8);

        float[][] input = new float[1][16384];
        float[][] output = new float[1][16384];
        input[0][0] = 1.0f;
        reverb.process(input, output, 16384);

        // The impulse response should be spread over time (not just a single peak)
        double earlyRms = rms(output[0], 0, 4096);
        double midRms = rms(output[0], 4096, 8192);
        // Both segments should have energy due to dispersive spreading
        assertThat(earlyRms).isGreaterThan(0.0);
        assertThat(midRms).isGreaterThan(0.0);
    }

    @Test
    void shouldDecayOverTime() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);
        reverb.setDecayTime(0.3);

        // Use a longer buffer to allow the reverb to fully decay
        int len = 88200; // 2 seconds
        float[][] input = new float[1][len];
        float[][] output = new float[1][len];
        input[0][0] = 1.0f;
        reverb.process(input, output, len);

        // First half should have more energy than second half
        double firstHalfRms = rms(output[0], 0, len / 2);
        double secondHalfRms = rms(output[0], len / 2, len);
        assertThat(firstHalfRms).isGreaterThan(secondHalfRms);
    }

    @Test
    void shouldModifySignalComparedToDry() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
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
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
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
        assertThatThrownBy(() -> new SpringReverbProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpringReverbProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSpringTension() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setSpringTension(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setSpringTension(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDecayTime() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setDecayTime(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setDecayTime(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDamping() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setDamping(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setDamping(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMix() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidPreDelay() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setPreDelayMs(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setPreDelayMs(201.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidHelixAngle() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setHelixAngle(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setHelixAngle(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(1, 44100.0);
        reverb.setSpringTension(0.8);
        reverb.setDecayTime(0.7);
        reverb.setDamping(0.3);
        reverb.setMix(0.6);
        reverb.setPreDelayMs(25.0);
        reverb.setHelixAngle(0.9);

        assertThat(reverb.getSpringTension()).isEqualTo(0.8);
        assertThat(reverb.getDecayTime()).isEqualTo(0.7);
        assertThat(reverb.getDamping()).isEqualTo(0.3);
        assertThat(reverb.getMix()).isEqualTo(0.6);
        assertThat(reverb.getPreDelayMs()).isEqualTo(25.0);
        assertThat(reverb.getHelixAngle()).isEqualTo(0.9);
    }

    @Test
    void shouldProcessStereo() {
        SpringReverbProcessor reverb = new SpringReverbProcessor(2, 44100.0);
        reverb.setMix(1.0);

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
    void shouldRespondToHelixAngleChange() {
        // Different helix angles should produce different impulse responses
        SpringReverbProcessor reverb1 = new SpringReverbProcessor(1, 44100.0);
        reverb1.setMix(1.0);
        reverb1.setHelixAngle(0.1);
        reverb1.setDecayTime(0.6);

        SpringReverbProcessor reverb2 = new SpringReverbProcessor(1, 44100.0);
        reverb2.setMix(1.0);
        reverb2.setHelixAngle(0.9);
        reverb2.setDecayTime(0.6);

        float[][] input = new float[1][8192];
        input[0][0] = 1.0f;
        float[][] output1 = new float[1][8192];
        float[][] output2 = new float[1][8192];

        reverb1.process(input, output1, 8192);
        reverb2.process(input, output2, 8192);

        // Outputs should differ due to different helix angles
        boolean differs = false;
        for (int i = 1024; i < 8192; i++) {
            if (Math.abs(output1[0][i] - output2[0][i]) > 1e-6f) {
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
