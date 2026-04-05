package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ImpulseResponseTest {

    @Test
    void shouldCreateMonoImpulseResponse() {
        float[] samples = {1.0f, 0.5f, 0.25f, 0.125f};
        ImpulseResponse ir = new ImpulseResponse(new float[][]{samples}, 48000);

        assertThat(ir.channelCount()).isEqualTo(1);
        assertThat(ir.lengthInSamples()).isEqualTo(4);
        assertThat(ir.sampleRate()).isEqualTo(48000);
    }

    @Test
    void shouldCreateStereoImpulseResponse() {
        float[] left = {1.0f, 0.5f};
        float[] right = {0.8f, 0.4f};
        ImpulseResponse ir = new ImpulseResponse(new float[][]{left, right}, 44100);

        assertThat(ir.channelCount()).isEqualTo(2);
        assertThat(ir.lengthInSamples()).isEqualTo(2);
    }

    @Test
    void shouldComputeDurationCorrectly() {
        float[] samples = new float[48000]; // 1 second at 48 kHz
        ImpulseResponse ir = new ImpulseResponse(new float[][]{samples}, 48000);

        assertThat(ir.durationSeconds()).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void shouldDefensivelyCopySamples() {
        float[] original = {1.0f, 0.5f};
        ImpulseResponse ir = new ImpulseResponse(new float[][]{original}, 48000);

        original[0] = 999.0f;
        assertThat(ir.samples()[0][0]).isEqualTo(1.0f);
    }

    @Test
    void shouldRejectNullSamples() {
        assertThatThrownBy(() -> new ImpulseResponse(null, 48000))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyChannelArray() {
        assertThatThrownBy(() -> new ImpulseResponse(new float[0][], 48000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectEmptySampleArray() {
        assertThatThrownBy(() -> new ImpulseResponse(new float[][]{new float[0]}, 48000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectMismatchedChannelLengths() {
        float[] ch1 = {1.0f, 0.5f};
        float[] ch2 = {1.0f}; // different length
        assertThatThrownBy(() -> new ImpulseResponse(new float[][]{ch1, ch2}, 48000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        float[] samples = {1.0f};
        assertThatThrownBy(() -> new ImpulseResponse(new float[][]{samples}, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImpulseResponse(new float[][]{samples}, -44100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
