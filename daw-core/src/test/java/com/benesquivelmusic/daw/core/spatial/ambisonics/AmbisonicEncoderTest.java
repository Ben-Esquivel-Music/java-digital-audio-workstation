package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AmbisonicEncoderTest {

    private static final double TOLERANCE = 1e-4;
    private static final int NUM_FRAMES = 64;

    // ---- Construction ----

    @Test
    void shouldCreateFoaEncoder() {
        var encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        assertThat(encoder.getInputChannelCount()).isEqualTo(1);
        assertThat(encoder.getOutputChannelCount()).isEqualTo(4);
        assertThat(encoder.getOrder()).isEqualTo(AmbisonicOrder.FIRST);
    }

    @Test
    void shouldCreateHoaEncoder() {
        var encoder = new AmbisonicEncoder(AmbisonicOrder.THIRD);
        assertThat(encoder.getOutputChannelCount()).isEqualTo(16);
    }

    @Test
    void shouldRejectNullOrder() {
        assertThatThrownBy(() -> new AmbisonicEncoder(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- FOA Encoding from Front ----

    @Test
    void shouldEncodeFrontSourceToFoa() {
        var encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(0, 0); // front

        float[][] input = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] output = new float[4][NUM_FRAMES];
        encoder.process(input, output, NUM_FRAMES);

        // W = 1, Y = 0, Z = 0, X = 1
        assertAllFramesCloseTo(output[0], 1.0f, TOLERANCE); // W
        assertAllFramesCloseTo(output[1], 0.0f, TOLERANCE); // Y
        assertAllFramesCloseTo(output[2], 0.0f, TOLERANCE); // Z
        assertAllFramesCloseTo(output[3], 1.0f, TOLERANCE); // X
    }

    // ---- FOA Encoding from Left ----

    @Test
    void shouldEncodeLeftSourceToFoa() {
        var encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(Math.PI / 2.0, 0); // left

        float[][] input = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] output = new float[4][NUM_FRAMES];
        encoder.process(input, output, NUM_FRAMES);

        assertAllFramesCloseTo(output[0], 1.0f, TOLERANCE); // W
        assertAllFramesCloseTo(output[1], 1.0f, TOLERANCE); // Y
        assertAllFramesCloseTo(output[2], 0.0f, TOLERANCE); // Z
        assertAllFramesCloseTo(output[3], 0.0f, TOLERANCE); // X
    }

    // ---- Energy Preservation ----

    @Test
    void shouldPreserveEnergyAcrossDirections() {
        var encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);

        for (double az = 0; az < 2 * Math.PI; az += Math.PI / 6) {
            encoder.setDirection(az, 0);
            double[] coeffs = encoder.getCoefficients();

            // Sum of squares of directional channels should be 1.0 (horizontal)
            double dirEnergy = coeffs[1] * coeffs[1] + coeffs[2] * coeffs[2] + coeffs[3] * coeffs[3];
            assertThat(dirEnergy).isCloseTo(1.0, within(TOLERANCE));
        }
    }

    // ---- Direction Update ----

    @Test
    void shouldUpdateDirectionBetweenProcessCalls() {
        var encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);

        // First: front
        encoder.setDirection(0, 0);
        assertThat(encoder.getAzimuthRadians()).isCloseTo(0.0, within(TOLERANCE));

        // Then: left
        encoder.setDirection(Math.PI / 2.0, 0);
        assertThat(encoder.getAzimuthRadians()).isCloseTo(Math.PI / 2.0, within(TOLERANCE));
    }

    // ---- HOA Encoding ----

    @Test
    void shouldEncodeToSecondOrder() {
        var encoder = new AmbisonicEncoder(AmbisonicOrder.SECOND);
        encoder.setDirection(Math.PI / 4.0, Math.PI / 6.0);

        float[][] input = {constantBuffer(0.5f, NUM_FRAMES)};
        float[][] output = new float[9][NUM_FRAMES];
        encoder.process(input, output, NUM_FRAMES);

        // W channel should be 0.5 (input scaled by W coefficient = 1.0)
        assertAllFramesCloseTo(output[0], 0.5f, TOLERANCE);

        // All output channels should have some signal
        boolean allZero = true;
        for (float[] ch : output) {
            for (float sample : ch) {
                if (Math.abs(sample) > 1e-6) {
                    allZero = false;
                    break;
                }
            }
        }
        assertThat(allZero).isFalse();
    }

    // ---- Reset ----

    @Test
    void shouldResetWithoutError() {
        var encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.reset(); // no-op, should not throw
    }

    // ---- Helpers ----

    private static float[] constantBuffer(float value, int size) {
        float[] buffer = new float[size];
        java.util.Arrays.fill(buffer, value);
        return buffer;
    }

    private static void assertAllFramesCloseTo(float[] buffer, float expected, double tolerance) {
        for (int i = 0; i < buffer.length; i++) {
            assertThat((double) buffer[i]).as("frame %d", i)
                    .isCloseTo(expected, within(tolerance));
        }
    }
}
