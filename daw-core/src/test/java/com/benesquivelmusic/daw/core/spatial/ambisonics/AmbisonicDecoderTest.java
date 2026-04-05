package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;
import com.benesquivelmusic.daw.sdk.spatial.DecoderType;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AmbisonicDecoderTest {

    private static final double TOLERANCE = 1e-4;
    private static final int NUM_FRAMES = 64;

    private static final List<SpatialPosition> STEREO_SPEAKERS = List.of(
            new SpatialPosition(30, 0, 1.0),   // left
            new SpatialPosition(330, 0, 1.0)    // right
    );

    private static final List<SpatialPosition> QUAD_SPEAKERS = List.of(
            new SpatialPosition(45, 0, 1.0),    // FL
            new SpatialPosition(315, 0, 1.0),   // FR
            new SpatialPosition(135, 0, 1.0),   // RL
            new SpatialPosition(225, 0, 1.0)     // RR
    );

    // ---- Construction ----

    @Test
    void shouldCreateBasicDecoder() {
        AmbisonicDecoder decoder = new AmbisonicDecoder(AmbisonicOrder.FIRST, STEREO_SPEAKERS, DecoderType.BASIC);
        assertThat(decoder.getInputChannelCount()).isEqualTo(4);
        assertThat(decoder.getOutputChannelCount()).isEqualTo(2);
        assertThat(decoder.getDecoderType()).isEqualTo(DecoderType.BASIC);
    }

    @Test
    void shouldRejectNullOrder() {
        assertThatThrownBy(() -> new AmbisonicDecoder(null, STEREO_SPEAKERS, DecoderType.BASIC))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSpeakers() {
        assertThatThrownBy(() -> new AmbisonicDecoder(AmbisonicOrder.FIRST, null, DecoderType.BASIC))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptySpeakers() {
        assertThatThrownBy(() -> new AmbisonicDecoder(AmbisonicOrder.FIRST, List.of(), DecoderType.BASIC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Decode-Encode Round Trip ----

    @Test
    void shouldReconstructSignalOnRoundTrip() {
        // Encode a front source to FOA, then decode to quad speakers
        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(0, 0); // front

        float[][] monoInput = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] foaBuffer = new float[4][NUM_FRAMES];
        encoder.process(monoInput, foaBuffer, NUM_FRAMES);

        AmbisonicDecoder decoder = new AmbisonicDecoder(AmbisonicOrder.FIRST, QUAD_SPEAKERS, DecoderType.BASIC);
        float[][] speakerOutput = new float[4][NUM_FRAMES];
        decoder.process(foaBuffer, speakerOutput, NUM_FRAMES);

        // Front speakers (FL, FR) should have more signal than rear speakers (RL, RR)
        double frontEnergy = rms(speakerOutput[0]) + rms(speakerOutput[1]);
        double rearEnergy = rms(speakerOutput[2]) + rms(speakerOutput[3]);
        assertThat(frontEnergy).isGreaterThan(rearEnergy);
    }

    @Test
    void shouldLocalizeLeftSourceToLeftSpeakers() {
        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(Math.PI / 2.0, 0); // left

        float[][] monoInput = {constantBuffer(1.0f, NUM_FRAMES)};
        float[][] foaBuffer = new float[4][NUM_FRAMES];
        encoder.process(monoInput, foaBuffer, NUM_FRAMES);

        AmbisonicDecoder decoder = new AmbisonicDecoder(AmbisonicOrder.FIRST, QUAD_SPEAKERS, DecoderType.BASIC);
        float[][] speakerOutput = new float[4][NUM_FRAMES];
        decoder.process(foaBuffer, speakerOutput, NUM_FRAMES);

        // Left speakers (FL=0, RL=2) should have more energy than right (FR=1, RR=3)
        double leftEnergy = rms(speakerOutput[0]) + rms(speakerOutput[2]);
        double rightEnergy = rms(speakerOutput[1]) + rms(speakerOutput[3]);
        assertThat(leftEnergy).isGreaterThan(rightEnergy);
    }

    // ---- Decoder Types ----

    @Test
    void maxReDecoderShouldProduceOutput() {
        AmbisonicDecoder decoder = new AmbisonicDecoder(AmbisonicOrder.FIRST, QUAD_SPEAKERS, DecoderType.MAX_RE);

        float[][] foaInput = createTestFoaSignal(NUM_FRAMES);
        float[][] output = new float[4][NUM_FRAMES];
        decoder.process(foaInput, output, NUM_FRAMES);

        assertThat(rms(output[0])).isGreaterThan(0);
    }

    @Test
    void inPhaseDecoderShouldProduceOutput() {
        AmbisonicDecoder decoder = new AmbisonicDecoder(AmbisonicOrder.FIRST, QUAD_SPEAKERS, DecoderType.IN_PHASE);

        float[][] foaInput = createTestFoaSignal(NUM_FRAMES);
        float[][] output = new float[4][NUM_FRAMES];
        decoder.process(foaInput, output, NUM_FRAMES);

        assertThat(rms(output[0])).isGreaterThan(0);
    }

    @Test
    void shouldUpdateDecoderType() {
        AmbisonicDecoder decoder = new AmbisonicDecoder(AmbisonicOrder.FIRST, QUAD_SPEAKERS, DecoderType.BASIC);
        decoder.setDecoderType(DecoderType.MAX_RE);
        assertThat(decoder.getDecoderType()).isEqualTo(DecoderType.MAX_RE);
    }

    // ---- Decoder Matrix ----

    @Test
    void decoderMatrixShouldHaveCorrectDimensions() {
        AmbisonicDecoder decoder = new AmbisonicDecoder(AmbisonicOrder.FIRST, QUAD_SPEAKERS, DecoderType.BASIC);
        double[][] matrix = decoder.getDecoderMatrix();
        assertThat(matrix).hasNumberOfRows(4); // 4 speakers
        for (double[] row : matrix) {
            assertThat(row).hasSize(4); // 4 ambisonic channels
        }
    }

    @Test
    void decoderMatrixShouldUpdateWithSpeakers() {
        AmbisonicDecoder decoder = new AmbisonicDecoder(AmbisonicOrder.FIRST, STEREO_SPEAKERS, DecoderType.BASIC);
        double[][] matrix1 = decoder.getDecoderMatrix();
        assertThat(matrix1).hasNumberOfRows(2);

        decoder.setSpeakerPositions(QUAD_SPEAKERS);
        double[][] matrix2 = decoder.getDecoderMatrix();
        assertThat(matrix2).hasNumberOfRows(4);
    }

    // ---- HOA Decoding ----

    @Test
    void shouldDecodeSecondOrderToSpeakers() {
        AmbisonicDecoder decoder = new AmbisonicDecoder(AmbisonicOrder.SECOND, QUAD_SPEAKERS, DecoderType.BASIC);
        assertThat(decoder.getInputChannelCount()).isEqualTo(9);

        float[][] hoaInput = new float[9][NUM_FRAMES];
        hoaInput[0] = constantBuffer(1.0f, NUM_FRAMES); // W only
        float[][] output = new float[4][NUM_FRAMES];
        decoder.process(hoaInput, output, NUM_FRAMES);

        // Omnidirectional source should produce equal output at all speakers
        double rms0 = rms(output[0]);
        assertThat(rms0).isGreaterThan(0);
        for (int i = 1; i < 4; i++) {
            assertThat(rms(output[i])).isCloseTo(rms0, within(TOLERANCE));
        }
    }

    // ---- Reset ----

    @Test
    void shouldResetWithoutError() {
        AmbisonicDecoder decoder = new AmbisonicDecoder(AmbisonicOrder.FIRST, STEREO_SPEAKERS, DecoderType.BASIC);
        decoder.reset();
    }

    // ---- Helpers ----

    private static float[] constantBuffer(float value, int size) {
        float[] buffer = new float[size];
        java.util.Arrays.fill(buffer, value);
        return buffer;
    }

    private static float[][] createTestFoaSignal(int numFrames) {
        // Encode a front source
        AmbisonicEncoder encoder = new AmbisonicEncoder(AmbisonicOrder.FIRST);
        encoder.setDirection(0, 0);
        float[][] input = {constantBuffer(1.0f, numFrames)};
        float[][] foa = new float[4][numFrames];
        encoder.process(input, foa, numFrames);
        return foa;
    }

    private static double rms(float[] buffer) {
        double sum = 0;
        for (float v : buffer) {
            sum += v * v;
        }
        return Math.sqrt(sum / buffer.length);
    }
}
