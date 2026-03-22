package com.benesquivelmusic.daw.core.dsp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MidSideEncoderTest {

    @Test
    void shouldEncodeStereoToMidSide() {
        float[] left =  {0.8f, 0.6f, 0.4f, 0.2f};
        float[] right = {0.2f, 0.4f, 0.6f, 0.8f};
        float[] mid = new float[4];
        float[] side = new float[4];

        MidSideEncoder.encode(left, right, mid, side, 4);

        // Mid = (L + R) * 0.5
        assertThat(mid[0]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(mid[1]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(mid[2]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(mid[3]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(1e-6f));

        // Side = (L - R) * 0.5
        assertThat(side[0]).isCloseTo(0.3f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(side[1]).isCloseTo(0.1f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(side[2]).isCloseTo(-0.1f, org.assertj.core.data.Offset.offset(1e-6f));
        assertThat(side[3]).isCloseTo(-0.3f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void shouldProduceZeroSideForMonoSignal() {
        float[] left =  {0.5f, -0.3f, 0.7f};
        float[] right = {0.5f, -0.3f, 0.7f};
        float[] mid = new float[3];
        float[] side = new float[3];

        MidSideEncoder.encode(left, right, mid, side, 3);

        // Mono signal: mid = signal, side = 0
        for (int i = 0; i < 3; i++) {
            assertThat(mid[i]).isCloseTo(left[i], org.assertj.core.data.Offset.offset(1e-6f));
            assertThat(side[i]).isCloseTo(0.0f, org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldProduceZeroMidForPureSideSignal() {
        float[] left =  {0.5f, -0.3f, 0.7f};
        float[] right = {-0.5f, 0.3f, -0.7f};
        float[] mid = new float[3];
        float[] side = new float[3];

        MidSideEncoder.encode(left, right, mid, side, 3);

        // L = -R: mid = 0, side = L
        for (int i = 0; i < 3; i++) {
            assertThat(mid[i]).isCloseTo(0.0f, org.assertj.core.data.Offset.offset(1e-6f));
            assertThat(side[i]).isCloseTo(left[i], org.assertj.core.data.Offset.offset(1e-6f));
        }
    }
}
