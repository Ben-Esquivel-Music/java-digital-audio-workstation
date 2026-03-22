package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeslieProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        var leslie = new LeslieProcessor(2, 44100.0);
        assertThat(leslie.getInputChannelCount()).isEqualTo(2);
        assertThat(leslie.getOutputChannelCount()).isEqualTo(2);
        assertThat(leslie.getSpeed()).isEqualTo(0.0);
        assertThat(leslie.getAcceleration()).isEqualTo(0.5);
        assertThat(leslie.getHornDrumBalance()).isEqualTo(0.5);
        assertThat(leslie.getDistance()).isEqualTo(0.5);
        assertThat(leslie.getMix()).isEqualTo(0.5);
    }

    @Test
    void shouldPassDrySignalWithZeroMix() {
        var leslie = new LeslieProcessor(1, 44100.0);
        leslie.setMix(0.0);

        float[][] input = new float[1][512];
        float[][] output = new float[1][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        leslie.process(input, output, 512);

        for (int i = 0; i < 512; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-5f));
        }
    }

    @Test
    void shouldModifySignalWithDefaultSettings() {
        var leslie = new LeslieProcessor(1, 44100.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        leslie.process(input, output, 4096);

        // Output should differ from input due to Leslie modulation
        boolean differs = false;
        for (int i = 512; i < 4096; i++) {
            if (Math.abs(output[0][i] - input[0][i]) > 0.001f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void shouldProduceAmplitudeModulation() {
        // At full wet with distance=1, the AM should be clearly audible
        var leslie = new LeslieProcessor(1, 44100.0);
        leslie.setMix(1.0);
        leslie.setDistance(1.0);
        leslie.setSpeed(1.0);         // Fast speed for clear modulation
        leslie.setAcceleration(1.0);  // Instant speed change

        // Feed a constant-amplitude sine
        int numFrames = 44100; // 1 second
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];
        for (int i = 0; i < numFrames; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * i / 44100.0));
        }
        leslie.process(input, output, numFrames);

        // Compute RMS of early and later segments — they should differ (AM)
        // Skip first 4096 frames for filter settling
        double rms1 = rms(output[0], 4096, 8192);
        double rms2 = rms(output[0], 8192, 12288);
        double rms3 = rms(output[0], 12288, 16384);

        // At least two of these should differ significantly (modulation)
        double maxRms = Math.max(rms1, Math.max(rms2, rms3));
        double minRms = Math.min(rms1, Math.min(rms2, rms3));
        assertThat(maxRms - minRms).isGreaterThan(0.001);
    }

    @Test
    void shouldRespondToSpeedChange() {
        // Different speeds should produce different outputs
        var leslieSlow = new LeslieProcessor(1, 44100.0);
        leslieSlow.setMix(1.0);
        leslieSlow.setSpeed(0.0);
        leslieSlow.setAcceleration(1.0);

        var leslieFast = new LeslieProcessor(1, 44100.0);
        leslieFast.setMix(1.0);
        leslieFast.setSpeed(1.0);
        leslieFast.setAcceleration(1.0);

        float[][] input = new float[1][8192];
        for (int i = 0; i < 8192; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }

        float[][] outputSlow = new float[1][8192];
        float[][] outputFast = new float[1][8192];
        leslieSlow.process(input, outputSlow, 8192);
        leslieFast.process(input, outputFast, 8192);

        // Outputs should differ due to different rotation speeds
        boolean differs = false;
        for (int i = 2048; i < 8192; i++) {
            if (Math.abs(outputSlow[0][i] - outputFast[0][i]) > 1e-4f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void shouldResetState() {
        var leslie = new LeslieProcessor(1, 44100.0);
        leslie.setMix(1.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
        }
        leslie.process(input, output, 4096);

        leslie.reset();

        // After reset, processing silence should produce near-silence
        float[][] silence = new float[1][4096];
        float[][] resetOutput = new float[1][4096];
        leslie.process(silence, resetOutput, 4096);

        double rmsValue = rms(resetOutput[0], 512, 4096);
        assertThat(rmsValue).isLessThan(0.01);
    }

    @Test
    void shouldRejectInvalidConstructorParameters() {
        assertThatThrownBy(() -> new LeslieProcessor(0, 44100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeslieProcessor(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSpeed() {
        var leslie = new LeslieProcessor(1, 44100.0);
        assertThatThrownBy(() -> leslie.setSpeed(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leslie.setSpeed(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidAcceleration() {
        var leslie = new LeslieProcessor(1, 44100.0);
        assertThatThrownBy(() -> leslie.setAcceleration(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leslie.setAcceleration(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidHornDrumBalance() {
        var leslie = new LeslieProcessor(1, 44100.0);
        assertThatThrownBy(() -> leslie.setHornDrumBalance(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leslie.setHornDrumBalance(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidDistance() {
        var leslie = new LeslieProcessor(1, 44100.0);
        assertThatThrownBy(() -> leslie.setDistance(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leslie.setDistance(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidMix() {
        var leslie = new LeslieProcessor(1, 44100.0);
        assertThatThrownBy(() -> leslie.setMix(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leslie.setMix(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportParameterChanges() {
        var leslie = new LeslieProcessor(1, 44100.0);
        leslie.setSpeed(0.7);
        leslie.setAcceleration(0.8);
        leslie.setHornDrumBalance(0.6);
        leslie.setDistance(0.3);
        leslie.setMix(0.4);

        assertThat(leslie.getSpeed()).isEqualTo(0.7);
        assertThat(leslie.getAcceleration()).isEqualTo(0.8);
        assertThat(leslie.getHornDrumBalance()).isEqualTo(0.6);
        assertThat(leslie.getDistance()).isEqualTo(0.3);
        assertThat(leslie.getMix()).isEqualTo(0.4);
    }

    @Test
    void shouldProcessStereo() {
        var leslie = new LeslieProcessor(2, 44100.0);
        leslie.setMix(1.0);

        float[][] input = new float[2][4096];
        float[][] output = new float[2][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
            input[1][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 880.0 * i / 44100.0));
        }
        leslie.process(input, output, 4096);

        // Both channels should have signal
        assertThat(rms(output[0], 512, 4096)).isGreaterThan(0.01);
        assertThat(rms(output[1], 512, 4096)).isGreaterThan(0.01);
    }

    @Test
    void shouldNotClipOutput() {
        var leslie = new LeslieProcessor(1, 44100.0);
        leslie.setMix(1.0);

        float[][] input = new float[1][4096];
        float[][] output = new float[1][4096];
        for (int i = 0; i < 4096; i++) {
            input[0][i] = 0.9f;
        }
        leslie.process(input, output, 4096);

        for (int i = 0; i < 4096; i++) {
            assertThat(Math.abs(output[0][i])).isLessThanOrEqualTo(1.5f);
        }
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
