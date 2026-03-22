package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MidSideDecoderTest {

    @Test
    void shouldDecodeMidSideToStereo() {
        float[] mid =  {0.5f, 0.5f, 0.5f, 0.5f};
        float[] side = {0.3f, 0.1f, -0.1f, -0.3f};
        float[] left = new float[4];
        float[] right = new float[4];

        MidSideDecoder.decode(mid, side, left, right, 4);

        // L = Mid + Side
        assertThat(left[0]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(left[1]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(left[2]).isCloseTo(0.4f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(left[3]).isCloseTo(0.2f, org.assertj.core.data.Offset.offset(1e-6f));

        // R = Mid - Side
        assertThat(right[0]).isCloseTo(0.2f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(right[1]).isCloseTo(0.4f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(right[2]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(right[3]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldRoundTripWithEncoder() {
        float[] origLeft =  {0.9f, -0.5f, 0.3f, -0.7f, 0.1f};
        float[] origRight = {0.2f, 0.8f, -0.4f, 0.6f, -0.2f};
        int n = origLeft.length;

        float[] mid = new float[n];
        float[] side = new float[n];
        MidSideEncoder.encode(origLeft, origRight, mid, side, n);

        float[] recoveredLeft = new float[n];
        float[] recoveredRight = new float[n];
        MidSideDecoder.decode(mid, side, recoveredLeft, recoveredRight, n);

        // Verify perfect round-trip signal preservation
        for (int i = 0; i < n; i++) {
            assertThat(recoveredLeft[i]).isCloseTo(origLeft[i],
                    org.assertj.core.data.Offset.offset(1e-6f));
            assertThat(recoveredRight[i]).isCloseTo(origRight[i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldRoundTripWithSilence() {
        float[] left = new float[64];
        float[] right = new float[64];
        float[] mid = new float[64];
        float[] side = new float[64];

        MidSideEncoder.encode(left, right, mid, side, 64);
        float[] outLeft = new float[64];
        float[] outRight = new float[64];
        MidSideDecoder.decode(mid, side, outLeft, outRight, 64);

        assertThat(outLeft).containsExactly(left);
        assertThat(outRight).containsExactly(right);
    }
}
