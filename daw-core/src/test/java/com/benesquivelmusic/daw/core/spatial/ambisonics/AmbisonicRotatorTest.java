package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AmbisonicRotatorTest {

    private static final double TOLERANCE = 1e-4;
    private static final int NUM_FRAMES = 64;

    // ---- Construction ----

    @Test
    void shouldCreateWithNoRotation() {
        AmbisonicRotator rotator = new AmbisonicRotator(AmbisonicOrder.FIRST);
        assertThat(rotator.getYawRadians()).isCloseTo(0.0, within(1e-15));
        assertThat(rotator.getPitchRadians()).isCloseTo(0.0, within(1e-15));
        assertThat(rotator.getRollRadians()).isCloseTo(0.0, within(1e-15));
        assertThat(rotator.getInputChannelCount()).isEqualTo(4);
        assertThat(rotator.getOutputChannelCount()).isEqualTo(4);
    }

    @Test
    void shouldRejectNullOrder() {
        assertThatThrownBy(() -> new AmbisonicRotator(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- Identity Rotation ----

    @Test
    void identityRotationShouldPassThrough() {
        AmbisonicRotator rotator = new AmbisonicRotator(AmbisonicOrder.FIRST);

        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(Math.PI / 4.0, Math.PI / 6.0);

        float[][] input = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] foaInput = new float[4][NUM_FRAMES];
        encoder.process(input, foaInput, NUM_FRAMES);

        float[][] output = new float[4][NUM_FRAMES];
        rotator.process(foaInput, output, NUM_FRAMES);

        // Output should equal input (no rotation applied)
        for (int ch = 0; ch < 4; ch++) {
            for (int i = 0; i < NUM_FRAMES; i++) {
                assertThat((double) output[ch][i]).as("ch=%d frame=%d", ch, i)
                        .isCloseTo(foaInput[ch][i], within(TOLERANCE));
            }
        }
    }

    // ---- W Channel Invariance ----

    @Test
    void wChannelShouldBeUnaffectedByRotation() {
        AmbisonicRotator rotator = new AmbisonicRotator(AmbisonicOrder.FIRST);
        rotator.setRotation(Math.PI / 3.0, Math.PI / 4.0, Math.PI / 6.0);

        float[][] foaInput = new float[4][NUM_FRAMES];
        Arrays.fill(foaInput[0], 0.8f); // W channel
        // Leave directional channels at zero

        float[][] output = new float[4][NUM_FRAMES];
        rotator.process(foaInput, output, NUM_FRAMES);

        // W channel should pass through unchanged
        for (int i = 0; i < NUM_FRAMES; i++) {
            assertThat((double) output[0][i]).isCloseTo(0.8, within(TOLERANCE));
        }
    }

    // ---- 180° Yaw Rotation ----

    @Test
    void yaw180ShouldNegateXChannel() {
        AmbisonicRotator rotator = new AmbisonicRotator(AmbisonicOrder.FIRST);
        rotator.setRotation(Math.PI, 0, 0); // 180° yaw

        float[][] foaInput = new float[4][NUM_FRAMES];
        // Encode front source: W=1, Y=0, Z=0, X=1
        Arrays.fill(foaInput[0], 1.0f); // W
        Arrays.fill(foaInput[3], 1.0f); // X (ACN 3)

        float[][] output = new float[4][NUM_FRAMES];
        rotator.process(foaInput, output, NUM_FRAMES);

        // After 180° yaw: X should become -X (front → back)
        for (int i = 0; i < NUM_FRAMES; i++) {
            assertThat((double) output[0][i]).as("W frame %d", i)
                    .isCloseTo(1.0, within(TOLERANCE));
            assertThat((double) output[3][i]).as("X frame %d", i)
                    .isCloseTo(-1.0, within(TOLERANCE));
        }
    }

    // ---- 90° Yaw Rotation ----

    @Test
    void yaw90ShouldRotateFrontToLeft() {
        AmbisonicRotator rotator = new AmbisonicRotator(AmbisonicOrder.FIRST);
        rotator.setRotation(Math.PI / 2.0, 0, 0); // 90° yaw

        // Pure X (front) signal
        float[][] foaInput = new float[4][NUM_FRAMES];
        Arrays.fill(foaInput[3], 1.0f); // X (ACN 3)

        float[][] output = new float[4][NUM_FRAMES];
        rotator.process(foaInput, output, NUM_FRAMES);

        // After 90° yaw: X should become Y (front → left)
        for (int i = 0; i < NUM_FRAMES; i++) {
            assertThat((double) output[1][i]).as("Y frame %d", i)
                    .isCloseTo(1.0, within(TOLERANCE));
            assertThat((double) output[3][i]).as("X frame %d", i)
                    .isCloseTo(0.0, within(TOLERANCE));
        }
    }

    // ---- Energy Preservation ----

    @Test
    void rotationShouldPreserveDirectionalEnergy() {
        AmbisonicRotator rotator = new AmbisonicRotator(AmbisonicOrder.FIRST);
        rotator.setRotation(0.7, 0.3, 0.1);

        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(Math.PI / 3.0, Math.PI / 8.0);

        float[][] monoInput = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] foaInput = new float[4][NUM_FRAMES];
        encoder.process(monoInput, foaInput, NUM_FRAMES);

        float[][] output = new float[4][NUM_FRAMES];
        rotator.process(foaInput, output, NUM_FRAMES);

        // Directional energy (Y² + Z² + X²) should be preserved
        double inputDirEnergy = channelEnergy(foaInput, 1, 4, NUM_FRAMES);
        double outputDirEnergy = channelEnergy(output, 1, 4, NUM_FRAMES);
        assertThat(outputDirEnergy).isCloseTo(inputDirEnergy, within(TOLERANCE));
    }

    // ---- Reset ----

    @Test
    void shouldResetWithoutError() {
        AmbisonicRotator rotator = new AmbisonicRotator(AmbisonicOrder.FIRST);
        rotator.reset();
    }

    // ---- Helpers ----

    private static float[] constantBuffer(float value, int size) {
        float[] buffer = new float[size];
        Arrays.fill(buffer, value);
        return buffer;
    }

    private static double channelEnergy(float[][] buffer, int startCh, int endCh, int numFrames) {
        double energy = 0;
        for (int ch = startCh; ch < endCh; ch++) {
            for (int i = 0; i < numFrames; i++) {
                energy += buffer[ch][i] * buffer[ch][i];
            }
        }
        return energy;
    }
}
