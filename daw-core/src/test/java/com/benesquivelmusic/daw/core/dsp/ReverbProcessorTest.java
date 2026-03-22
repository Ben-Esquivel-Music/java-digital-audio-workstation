package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReverbProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        ReverbProcessor reverb = new ReverbProcessor(2, 44100.0);
        assertThat(reverb.getInputChannelCount()).isEqualTo(2);
        assertThat(reverb.getOutputChannelCount()).isEqualTo(2);
        assertThat(reverb.getRoomSize()).isEqualTo(0.5);
        assertThat(reverb.getDecay()).isEqualTo(0.5);
        assertThat(reverb.getDamping()).isEqualTo(0.5);
        assertThat(reverb.getMix()).isEqualTo(0.3);
    }

    @Test
    void shouldPassDrySignalWithZeroMix() {
        ReverbProcessor reverb = new ReverbProcessor(1, 44100.0);
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
        ReverbProcessor reverb = new ReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);
        reverb.setDecay(0.8);

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
        ReverbProcessor reverb = new ReverbProcessor(1, 44100.0);
        reverb.setMix(1.0);
        reverb.setDecay(0.5);

        // Process an impulse
        float[][] input = new float[1][16384];
        float[][] output = new float[1][16384];
        input[0][0] = 1.0f;
        reverb.process(input, output, 16384);

        // Early tail should be louder than late tail
        double earlyRms = rms(output[0], 2048, 4096);
        double lateRms = rms(output[0], 12288, 16384);
        assertThat(earlyRms).isGreaterThan(lateRms);
    }

    @Test
    void shouldModifySignalComparedToDry() {
        ReverbProcessor reverb = new ReverbProcessor(1, 44100.0);
        reverb.setMix(0.5);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        reverb.process(input, output, 4096);

        // Output should differ from input due to reverb
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
        ReverbProcessor reverb = new ReverbProcessor(1, 44100.0);
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
        assertThatThrownBy(() -> new ReverbProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReverbProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidRoomSize() {
        ReverbProcessor reverb = new ReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setRoomSize(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setRoomSize(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDecay() {
        ReverbProcessor reverb = new ReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setDecay(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setDecay(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDamping() {
        ReverbProcessor reverb = new ReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setDamping(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setDamping(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMix() {
        ReverbProcessor reverb = new ReverbProcessor(1, 44100.0);
        assertThatThrownBy(() -> reverb.setMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reverb.setMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        ReverbProcessor reverb = new ReverbProcessor(1, 44100.0);
        reverb.setDecay(0.8);
        reverb.setDamping(0.3);
        reverb.setMix(0.6);

        assertThat(reverb.getDecay()).isEqualTo(0.8);
        assertThat(reverb.getDamping()).isEqualTo(0.3);
        assertThat(reverb.getMix()).isEqualTo(0.6);
    }

    @Test
    void shouldProcessStereo() {
        ReverbProcessor reverb = new ReverbProcessor(2, 44100.0);
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

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
