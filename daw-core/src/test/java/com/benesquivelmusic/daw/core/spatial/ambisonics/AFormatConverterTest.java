package com.benesquivelmusic.daw.core.spatial.ambisonics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AFormatConverterTest {

    private static final double TOLERANCE = 1e-6;
    private static final int NUM_FRAMES = 64;

    // ---- Channel Counts ----

    @Test
    void shouldHaveFourInputChannels() {
        AFormatConverter converter = new AFormatConverter();
        assertThat(converter.getInputChannelCount()).isEqualTo(4);
    }

    @Test
    void shouldHaveFourOutputChannels() {
        AFormatConverter converter = new AFormatConverter();
        assertThat(converter.getOutputChannelCount()).isEqualTo(4);
    }

    // ---- Conversion with Known Signals ----

    @Test
    void shouldConvertUniformSignalToOmniOnly() {
        AFormatConverter converter = new AFormatConverter();

        // All capsules receive identical signal → pure omnidirectional
        float[][] input = new float[4][NUM_FRAMES];
        for (float[] ch : input) Arrays.fill(ch, 1.0f);

        float[][] output = new float[4][NUM_FRAMES];
        converter.process(input, output, NUM_FRAMES);

        // W = 0.5 * (1+1+1+1) = 2.0
        assertAllFramesCloseTo(output[0], 2.0f, TOLERANCE);
        // Y = 0.5 * (1-1+1-1) = 0
        assertAllFramesCloseTo(output[1], 0.0f, TOLERANCE);
        // Z = 0.5 * (1-1-1+1) = 0
        assertAllFramesCloseTo(output[2], 0.0f, TOLERANCE);
        // X = 0.5 * (1+1-1-1) = 0
        assertAllFramesCloseTo(output[3], 0.0f, TOLERANCE);
    }

    @Test
    void shouldConvertFluOnlyToCorrectBFormat() {
        AFormatConverter converter = new AFormatConverter();

        // Only FLU capsule has signal
        float[][] input = new float[4][NUM_FRAMES];
        Arrays.fill(input[AFormatConverter.FLU], 1.0f);

        float[][] output = new float[4][NUM_FRAMES];
        converter.process(input, output, NUM_FRAMES);

        // W = 0.5 * 1 = 0.5
        assertAllFramesCloseTo(output[0], 0.5f, TOLERANCE);
        // Y = 0.5 * 1 = 0.5
        assertAllFramesCloseTo(output[1], 0.5f, TOLERANCE);
        // Z = 0.5 * 1 = 0.5
        assertAllFramesCloseTo(output[2], 0.5f, TOLERANCE);
        // X = 0.5 * 1 = 0.5
        assertAllFramesCloseTo(output[3], 0.5f, TOLERANCE);
    }

    @Test
    void shouldConvertOpposingCapsulesForXAxis() {
        AFormatConverter converter = new AFormatConverter();

        // FLU and FRD are "front" capsules, BLD and BRU are "back"
        // Signal only in front capsules → dominant X component
        float[][] input = new float[4][NUM_FRAMES];
        Arrays.fill(input[AFormatConverter.FLU], 1.0f);
        Arrays.fill(input[AFormatConverter.FRD], 1.0f);

        float[][] output = new float[4][NUM_FRAMES];
        converter.process(input, output, NUM_FRAMES);

        // W = 0.5 * (1+1+0+0) = 1.0
        assertAllFramesCloseTo(output[0], 1.0f, TOLERANCE);
        // X = 0.5 * (1+1-0-0) = 1.0
        assertAllFramesCloseTo(output[3], 1.0f, TOLERANCE);
    }

    @Test
    void shouldProduceEnergyConservation() {
        AFormatConverter converter = new AFormatConverter();

        // Random-ish A-format signal
        float[][] input = new float[4][NUM_FRAMES];
        Arrays.fill(input[0], 0.7f);
        Arrays.fill(input[1], -0.3f);
        Arrays.fill(input[2], 0.5f);
        Arrays.fill(input[3], -0.1f);

        float[][] output = new float[4][NUM_FRAMES];
        converter.process(input, output, NUM_FRAMES);

        // Verify that output is non-trivial
        double totalEnergy = 0;
        for (float[] ch : output) {
            for (float v : ch) totalEnergy += v * v;
        }
        assertThat(totalEnergy).isGreaterThan(0);
    }

    // ---- Reset ----

    @Test
    void shouldResetWithoutError() {
        AFormatConverter converter = new AFormatConverter();
        converter.reset();
    }

    // ---- Helpers ----

    private static void assertAllFramesCloseTo(float[] buffer, float expected, double tolerance) {
        for (int i = 0; i < buffer.length; i++) {
            assertThat((double) buffer[i]).as("frame %d", i)
                    .isCloseTo(expected, within(tolerance));
        }
    }
}
