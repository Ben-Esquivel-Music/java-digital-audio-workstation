package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StereoImagerProcessorTest {

    @Test
    void shouldCreateWithDefaults() {
        var imager = new StereoImagerProcessor(44100.0);
        assertThat(imager.getInputChannelCount()).isEqualTo(2);
        assertThat(imager.getOutputChannelCount()).isEqualTo(2);
        assertThat(imager.getWidth()).isEqualTo(1.0);
    }

    @Test
    void shouldCollapseToMonoAtWidthZero() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setWidth(0.0);

        float[][] input = new float[2][256];
        float[][] output = new float[2][256];
        // Different content on L and R
        for (int i = 0; i < 256; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 880.0 * i / 44100.0);
        }
        imager.process(input, output, 256);

        // At width 0, L and R should be identical (mono)
        for (int i = 0; i < 256; i++) {
            assertThat(output[0][i]).isCloseTo(output[1][i],
                    org.assertj.core.data.Offset.offset(0.001f));
        }
    }

    @Test
    void shouldPassThroughAtWidthOne() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setWidth(1.0);

        float[][] input = new float[2][256];
        float[][] output = new float[2][256];
        for (int i = 0; i < 256; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 880.0 * i / 44100.0);
        }
        imager.process(input, output, 256);

        // At width 1, output should closely match input
        for (int i = 0; i < 256; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(0.001f));
            assertThat(output[1][i]).isCloseTo(input[1][i],
                    org.assertj.core.data.Offset.offset(0.001f));
        }
    }

    @Test
    void shouldWidenStereoFieldAboveOne() {
        var imager = new StereoImagerProcessor(44100.0);

        float[][] input = new float[2][1024];
        for (int i = 0; i < 1024; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0 + 0.5);
        }

        // Process at width 1 (normal)
        imager.setWidth(1.0);
        float[][] outputNormal = new float[2][1024];
        imager.process(input, outputNormal, 1024);

        // Process at width 2 (extra wide)
        imager.reset();
        imager.setWidth(2.0);
        float[][] outputWide = new float[2][1024];
        imager.process(input, outputWide, 1024);

        // The difference between L and R should be larger at width 2
        double diffNormal = channelDifference(outputNormal, 1024);
        double diffWide = channelDifference(outputWide, 1024);
        assertThat(diffWide).isGreaterThan(diffNormal);
    }

    @Test
    void shouldHandleMonoInput() {
        var imager = new StereoImagerProcessor(44100.0);
        float[][] input = new float[1][128];
        float[][] output = new float[1][128];
        for (int i = 0; i < 128; i++) input[0][i] = 0.5f;

        imager.process(input, output, 128);
        // Should pass through without error
        assertThat(output[0][0]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void shouldSupportMonoLowFrequency() {
        var imager = new StereoImagerProcessor(44100.0);
        imager.setMonoLowFrequency(true);
        imager.setMonoLowCutoffHz(200.0);

        assertThat(imager.isMonoLowFrequency()).isTrue();
        assertThat(imager.getMonoLowCutoffHz()).isEqualTo(200.0);

        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        for (int i = 0; i < 512; i++) {
            input[0][i] = (float) Math.sin(2.0 * Math.PI * 60.0 * i / 44100.0);
            input[1][i] = (float) Math.sin(2.0 * Math.PI * 60.0 * i / 44100.0 + Math.PI / 4);
        }
        imager.process(input, output, 512);

        // Should produce finite output
        for (int i = 0; i < 512; i++) {
            assertThat(Float.isFinite(output[0][i])).isTrue();
            assertThat(Float.isFinite(output[1][i])).isTrue();
        }
    }

    @Test
    void shouldRejectInvalidWidth() {
        var imager = new StereoImagerProcessor(44100.0);
        assertThatThrownBy(() -> imager.setWidth(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> imager.setWidth(2.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new StereoImagerProcessor(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldResetState() {
        var imager = new StereoImagerProcessor(44100.0);
        float[][] input = new float[2][64];
        float[][] output = new float[2][64];
        imager.process(input, output, 64);
        imager.reset();
        // Should not throw
    }

    private static double channelDifference(float[][] buffer, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) {
            double diff = buffer[0][i] - buffer[1][i];
            sum += diff * diff;
        }
        return Math.sqrt(sum / length);
    }
}
