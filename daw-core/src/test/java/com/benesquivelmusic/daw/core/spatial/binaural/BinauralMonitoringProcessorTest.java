package com.benesquivelmusic.daw.core.spatial.binaural;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit + integration tests for {@link BinauralMonitoringProcessor}.
 *
 * <p>Verifies the issue contract: the binaural processor produces stereo
 * output from mono input with direction-based spatialisation.</p>
 */
class BinauralMonitoringProcessorTest {

    private static final double SAMPLE_RATE = 48000.0;
    private static final int BLOCK = 512;

    @Test
    void shouldAlwaysProduceStereoOutput() {
        var p = new BinauralMonitoringProcessor(1, SAMPLE_RATE);
        assertThat(p.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldRejectInvalidChannels() {
        assertThatThrownBy(() -> new BinauralMonitoringProcessor(0, SAMPLE_RATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSampleRate() {
        assertThatThrownBy(() -> new BinauralMonitoringProcessor(1, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldProduceStereoOutputFromMonoImpulse() {
        var p = new BinauralMonitoringProcessor(1, SAMPLE_RATE);
        p.setWetLevel(1.0);

        float[][] input = new float[1][BLOCK];
        float[][] output = new float[2][BLOCK];
        input[0][0] = 1.0f;

        p.process(input, output, BLOCK);

        boolean leftNonZero = false;
        boolean rightNonZero = false;
        for (int n = 0; n < BLOCK; n++) {
            if (Math.abs(output[0][n]) > 1e-6f) leftNonZero = true;
            if (Math.abs(output[1][n]) > 1e-6f) rightNonZero = true;
        }

        assertThat(leftNonZero)
                .as("binaural processor must produce left-channel output from mono input")
                .isTrue();
        assertThat(rightNonZero)
                .as("binaural processor must produce right-channel output from mono input")
                .isTrue();
    }

    @Test
    void leftAndRightChannelsShouldDifferWhenWetOnly() {
        // With wet-only operation, spherical panning guarantees the two stereo
        // channels are distinct — the defining characteristic of a binaural
        // spatialiser.
        var p = new BinauralMonitoringProcessor(1, SAMPLE_RATE);
        p.setWetLevel(1.0);

        float[][] input = new float[1][BLOCK];
        float[][] output = new float[2][BLOCK];
        input[0][0] = 1.0f;
        p.process(input, output, BLOCK);

        // Compare energies — they should not be identical if HRTF-like panning
        // is applied (the spherical-Fibonacci source distribution is not left-
        // right symmetric for small source counts).
        double diff = 0.0;
        for (int n = 0; n < BLOCK; n++) {
            float d = output[0][n] - output[1][n];
            diff += d * d;
        }
        assertThat(diff)
                .as("left and right channels must differ under spatial panning")
                .isGreaterThan(0.0);
    }

    @Test
    void fullyDryModeShouldDownmixInputToBothChannels() {
        var p = new BinauralMonitoringProcessor(1, SAMPLE_RATE);
        p.setWetLevel(0.0);

        float[][] input = new float[1][BLOCK];
        float[][] output = new float[2][BLOCK];
        for (int n = 0; n < BLOCK; n++) input[0][n] = 0.5f;

        p.process(input, output, BLOCK);

        for (int n = 0; n < BLOCK; n++) {
            assertThat(output[0][n]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(1e-5f));
            assertThat(output[1][n]).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(1e-5f));
        }
    }

    @Test
    void shouldRejectOutputBufferWithFewerThanTwoChannels() {
        var p = new BinauralMonitoringProcessor(1, SAMPLE_RATE);
        float[][] input = new float[1][BLOCK];
        float[][] output = new float[1][BLOCK];
        assertThatThrownBy(() -> p.process(input, output, BLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldClampWetLevel() {
        var p = new BinauralMonitoringProcessor(1, SAMPLE_RATE);
        p.setWetLevel(-1.0);
        assertThat(p.getWetLevel()).isEqualTo(0.0);
        p.setWetLevel(2.0);
        assertThat(p.getWetLevel()).isEqualTo(1.0);
    }

    @Test
    void shouldAcceptStereoInput() {
        var p = new BinauralMonitoringProcessor(2, SAMPLE_RATE);
        p.setWetLevel(1.0);
        float[][] input = new float[2][BLOCK];
        float[][] output = new float[2][BLOCK];
        input[0][0] = 1.0f;
        input[1][0] = -1.0f;
        p.process(input, output, BLOCK);
        // No exception = stereo input is accepted
        assertThat(p.getInputChannelCount()).isEqualTo(2);
    }
}
