package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AmbisonicBinauralDecoderTest {

    private static final double TOLERANCE = 1e-4;
    private static final int NUM_FRAMES = 64;

    // ---- Construction ----

    @Test
    void shouldCreateWithDefaultVirtualSpeakers() {
        AmbisonicBinauralDecoder decoder = new AmbisonicBinauralDecoder(AmbisonicOrder.FIRST);
        assertThat(decoder.getInputChannelCount()).isEqualTo(4);
        assertThat(decoder.getOutputChannelCount()).isEqualTo(2);
    }

    // ---- Binaural Output ----

    @Test
    void shouldProduceStereoOutput() {
        AmbisonicBinauralDecoder decoder = new AmbisonicBinauralDecoder(AmbisonicOrder.FIRST);

        // Encode a front source
        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(0, 0);
        float[][] monoInput = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] foaBuffer = new float[4][NUM_FRAMES];
        encoder.process(monoInput, foaBuffer, NUM_FRAMES);

        float[][] output = new float[2][NUM_FRAMES];
        decoder.process(foaBuffer, output, NUM_FRAMES);

        // Both L and R should have signal for a front source (center image)
        assertThat(rms(output[0])).isGreaterThan(0);
        assertThat(rms(output[1])).isGreaterThan(0);
    }

    @Test
    void shouldPanLeftSourceToLeftEar() {
        AmbisonicBinauralDecoder decoder = new AmbisonicBinauralDecoder(AmbisonicOrder.FIRST);

        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(Math.PI / 2.0, 0); // left
        float[][] monoInput = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] foaBuffer = new float[4][NUM_FRAMES];
        encoder.process(monoInput, foaBuffer, NUM_FRAMES);

        float[][] output = new float[2][NUM_FRAMES];
        decoder.process(foaBuffer, output, NUM_FRAMES);

        // Left ear should have more energy than right ear
        assertThat(rms(output[0])).isGreaterThan(rms(output[1]));
    }

    @Test
    void shouldPanRightSourceToRightEar() {
        AmbisonicBinauralDecoder decoder = new AmbisonicBinauralDecoder(AmbisonicOrder.FIRST);

        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(-Math.PI / 2.0, 0); // right (= 3π/2)
        float[][] monoInput = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] foaBuffer = new float[4][NUM_FRAMES];
        encoder.process(monoInput, foaBuffer, NUM_FRAMES);

        float[][] output = new float[2][NUM_FRAMES];
        decoder.process(foaBuffer, output, NUM_FRAMES);

        // Right ear should have more energy than left ear
        assertThat(rms(output[1])).isGreaterThan(rms(output[0]));
    }

    @Test
    void frontSourceShouldProduceBalancedOutput() {
        AmbisonicBinauralDecoder decoder = new AmbisonicBinauralDecoder(AmbisonicOrder.FIRST);

        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(0, 0); // front center
        float[][] monoInput = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] foaBuffer = new float[4][NUM_FRAMES];
        encoder.process(monoInput, foaBuffer, NUM_FRAMES);

        float[][] output = new float[2][NUM_FRAMES];
        decoder.process(foaBuffer, output, NUM_FRAMES);

        // Front source should produce roughly equal energy in both ears
        assertThat(rms(output[0])).isCloseTo(rms(output[1]), within(0.1));
    }

    // ---- Reset ----

    @Test
    void shouldResetWithoutError() {
        AmbisonicBinauralDecoder decoder = new AmbisonicBinauralDecoder(AmbisonicOrder.FIRST);
        decoder.reset();
    }

    // ---- Helpers ----

    private static float[] constantBuffer(float value, int size) {
        float[] buffer = new float[size];
        Arrays.fill(buffer, value);
        return buffer;
    }

    private static double rms(float[] buffer) {
        double sum = 0;
        for (float v : buffer) {
            sum += v * v;
        }
        return Math.sqrt(sum / buffer.length);
    }
}
