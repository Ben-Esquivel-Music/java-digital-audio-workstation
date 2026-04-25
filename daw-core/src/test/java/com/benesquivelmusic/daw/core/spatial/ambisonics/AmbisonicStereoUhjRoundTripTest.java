package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.spatial.AmbisonicDecoder;
import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;
import com.benesquivelmusic.daw.sdk.spatial.AmbisonicTrack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Round-trip test for an ambisonic signal flow:
 * mono source → {@link AmbisonicEncoder} → simple UHJ-style stereo decode.
 *
 * <p>The {@link AmbisonicDecoder.StereoUhj} variant in the SDK names the
 * decoder choice; a real DAW implementation builds the matrix from this
 * choice. This test exercises the contract by applying the canonical
 * horizontal stereo matrix
 * {@code L = 0.5 (W + Y), R = 0.5 (W − Y)} to FOA channels (ACN 0 = W,
 * ACN 1 = Y) and verifying that the resulting L/R balance follows the
 * source azimuth.</p>
 */
class AmbisonicStereoUhjRoundTripTest {

    private static final int NUM_FRAMES = 64;
    private static final double TOLERANCE = 1e-4;

    @Test
    void frontSourceShouldProduceEqualLeftAndRight() {
        float[] lr = roundTrip(0.0); // 0° azimuth = front
        assertThat(lr[0]).isCloseTo(0.5f, within(1e-4f));
        assertThat(lr[1]).isCloseTo(0.5f, within(1e-4f));
    }

    @Test
    void hardLeftSourceShouldProduceLeftOnly() {
        // +90° azimuth = full left in AmbiX convention.
        float[] lr = roundTrip(Math.PI / 2.0);
        assertThat(lr[0]).isCloseTo(1.0f, within(1e-4f));
        assertThat(lr[1]).isCloseTo(0.0f, within(1e-4f));
    }

    @Test
    void hardRightSourceShouldProduceRightOnly() {
        // -90° azimuth = full right.
        float[] lr = roundTrip(-Math.PI / 2.0);
        assertThat(lr[0]).isCloseTo(0.0f, within(1e-4f));
        assertThat(lr[1]).isCloseTo(1.0f, within(1e-4f));
    }

    @Test
    void thirtyDegreesLeftShouldFavorLeftChannel() {
        float[] lr = roundTrip(Math.toRadians(30.0));
        // For W=1, Y=sin(30°)=0.5 ⇒ L=0.75, R=0.25
        assertThat(lr[0]).isCloseTo(0.75f, within(1e-4f));
        assertThat(lr[1]).isCloseTo(0.25f, within(1e-4f));
        assertThat(lr[0]).isGreaterThan(lr[1]);
    }

    @Test
    void orderMismatchedDecoderShouldRaiseClearError() {
        // An AmbisonicTrack at SECOND order requires 9 channel
        // assignments — supplying 4 (FIRST-order count) must fail with
        // a clear, descriptive IllegalArgumentException.
        assertThatThrownBy(() -> new AmbisonicTrack(
                "Ambi", AmbisonicOrder.SECOND,
                java.util.List.of(0, 1, 2, 3),
                new AmbisonicDecoder.StereoUhj()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected 9")
                .hasMessageContaining("got 4");
    }

    /**
     * Encodes a unit DC mono source at the given horizontal azimuth and
     * decodes the W/Y channels with the canonical stereo UHJ matrix
     * {@code L = 0.5 (W + Y), R = 0.5 (W − Y)}, returning the steady-state
     * L/R values.
     */
    private static float[] roundTrip(double azimuthRadians) {
        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(azimuthRadians, 0.0);

        float[][] mono = new float[1][NUM_FRAMES];
        for (int i = 0; i < NUM_FRAMES; i++) {
            mono[0][i] = 1.0f;
        }

        float[][] bFormat = new float[4][NUM_FRAMES];
        encoder.process(mono, bFormat, NUM_FRAMES);

        // Sample mid-buffer to avoid any potential edge effects.
        int t = NUM_FRAMES / 2;
        float w = bFormat[0][t]; // ACN 0
        float y = bFormat[1][t]; // ACN 1
        float l = 0.5f * (w + y);
        float r = 0.5f * (w - y);

        // Sanity: same value across the buffer (DC input).
        assertThat(bFormat[0][0]).isCloseTo(w, within((float) TOLERANCE));
        assertThat(bFormat[1][NUM_FRAMES - 1]).isCloseTo(y, within((float) TOLERANCE));

        return new float[]{l, r};
    }
}
