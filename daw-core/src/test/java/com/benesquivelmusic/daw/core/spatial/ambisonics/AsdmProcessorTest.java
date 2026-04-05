package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

class AsdmProcessorTest {

    private static final double TOLERANCE = 1e-3;
    private static final int NUM_FRAMES = 256;

    // ---- Construction ----

    @Test
    void shouldCreateWithDefaultSmoothing() {
        AsdmProcessor asdm = new AsdmProcessor();
        assertThat(asdm.getSmoothingCoefficient()).isCloseTo(0.95, within(1e-10));
        assertThat(asdm.getInputChannelCount()).isEqualTo(4);
        assertThat(asdm.getOutputChannelCount()).isEqualTo(8);
    }

    @Test
    void shouldCreateWithCustomSmoothing() {
        AsdmProcessor asdm = new AsdmProcessor(0.5);
        assertThat(asdm.getSmoothingCoefficient()).isCloseTo(0.5, within(1e-10));
    }

    @Test
    void shouldRejectInvalidSmoothing() {
        assertThatThrownBy(() -> new AsdmProcessor(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AsdmProcessor(1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Salient/Diffuse Separation ----

    @Test
    void shouldSeparateDirectionalSignalAsSalient() {
        AsdmProcessor asdm = new AsdmProcessor(0.0); // no smoothing for deterministic test

        // Create a highly directional FOA signal (source from front)
        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(0, 0); // front

        float[][] monoInput = {constantBuffer(0.5f, NUM_FRAMES)};
        float[][] foaBuffer = new float[4][NUM_FRAMES];
        encoder.process(monoInput, foaBuffer, NUM_FRAMES);

        float[][] output = new float[8][NUM_FRAMES];
        asdm.process(foaBuffer, output, NUM_FRAMES);

        // Salient stream (channels 0-3) should carry most of the energy
        double salientEnergy = channelEnergy(output, 0, 4, NUM_FRAMES);
        double diffuseEnergy = channelEnergy(output, 4, 8, NUM_FRAMES);

        assertThat(salientEnergy).isGreaterThan(diffuseEnergy);
    }

    @Test
    void shouldSumToOriginalSignal() {
        AsdmProcessor asdm = new AsdmProcessor(0.0); // no smoothing

        // Create test FOA signal
        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(Math.PI / 4.0, 0);

        float[][] monoInput = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] foaBuffer = new float[4][NUM_FRAMES];
        encoder.process(monoInput, foaBuffer, NUM_FRAMES);

        float[][] output = new float[8][NUM_FRAMES];
        asdm.process(foaBuffer, output, NUM_FRAMES);

        // Salient + diffuse should approximately equal the original
        for (int ch = 0; ch < 4; ch++) {
            for (int i = 0; i < NUM_FRAMES; i++) {
                float sum = output[ch][i] + output[ch + 4][i];
                assertThat((double) sum).as("ch=%d frame=%d", ch, i)
                        .isCloseTo(foaBuffer[ch][i], within(TOLERANCE));
            }
        }
    }

    @Test
    void shouldTreatSilenceAsDiffuse() {
        AsdmProcessor asdm = new AsdmProcessor(0.0);

        float[][] input = new float[4][NUM_FRAMES]; // all zeros
        float[][] output = new float[8][NUM_FRAMES];
        asdm.process(input, output, NUM_FRAMES);

        // Both streams should be zero (silence)
        for (float[] ch : output) {
            for (float v : ch) {
                assertThat((double) v).isCloseTo(0.0, within(TOLERANCE));
            }
        }
    }

    // ---- Smoothing ----

    @Test
    void shouldUpdateSmoothingCoefficient() {
        AsdmProcessor asdm = new AsdmProcessor();
        asdm.setSmoothingCoefficient(0.8);
        assertThat(asdm.getSmoothingCoefficient()).isCloseTo(0.8, within(1e-10));
    }

    @Test
    void shouldRejectInvalidSmoothingUpdate() {
        AsdmProcessor asdm = new AsdmProcessor();
        assertThatThrownBy(() -> asdm.setSmoothingCoefficient(1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Reset ----

    @Test
    void shouldResetState() {
        AsdmProcessor asdm = new AsdmProcessor();
        // Process some data to change state
        float[][] input = new float[4][NUM_FRAMES];
        Arrays.fill(input[0], 1.0f);
        float[][] output = new float[8][NUM_FRAMES];
        asdm.process(input, output, NUM_FRAMES);

        asdm.reset();
        // After reset, should not throw
        asdm.process(input, output, NUM_FRAMES);
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
