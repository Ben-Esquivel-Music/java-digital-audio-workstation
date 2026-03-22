package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.core.spatial.room.FdnRoomSimulator;
import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;
import com.benesquivelmusic.daw.sdk.telemetry.ListenerOrientation;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class StereoToBinauralConverterTest {

    private static final int BLOCK_SIZE = 64;
    private static final double SAMPLE_RATE = 44100.0;
    private StereoToBinauralConverter converter;
    private HrtfData hrtfData;

    @BeforeEach
    void setUp() {
        converter = new StereoToBinauralConverter(SAMPLE_RATE, BLOCK_SIZE);

        // Create HRTF data with distinct left/right signatures per direction
        List<SphericalCoordinate> positions = List.of(
                new SphericalCoordinate(0, 0, 1.0),    // front
                new SphericalCoordinate(30, 0, 1.0),   // left 30°
                new SphericalCoordinate(90, 0, 1.0),   // left 90°
                new SphericalCoordinate(180, 0, 1.0),  // back
                new SphericalCoordinate(270, 0, 1.0),  // right 90°
                new SphericalCoordinate(330, 0, 1.0)   // right 30°
        );

        float[][][] ir = new float[6][2][BLOCK_SIZE];
        float[][] delays = new float[6][2];

        // Front: equal both ears
        ir[0][0][0] = 0.7f;
        ir[0][1][0] = 0.7f;

        // Left 30°: louder left ear
        ir[1][0][0] = 1.0f;
        ir[1][1][0] = 0.5f;

        // Left 90°: much louder left ear
        ir[2][0][0] = 1.0f;
        ir[2][1][0] = 0.2f;

        // Back: equal both ears
        ir[3][0][0] = 0.5f;
        ir[3][1][0] = 0.5f;

        // Right 90°: much louder right ear
        ir[4][0][0] = 0.2f;
        ir[4][1][0] = 1.0f;

        // Right 30°: louder right ear
        ir[5][0][0] = 0.5f;
        ir[5][1][0] = 1.0f;

        hrtfData = new HrtfData("TestHRTF", SAMPLE_RATE, positions, ir, delays);
    }

    @Test
    void shouldCreateWithDefaults() {
        assertThat(converter.getSpeakerAzimuth()).isEqualTo(30.0);
        assertThat(converter.getSpeakerDistance()).isEqualTo(1.0);
        assertThat(converter.getRoomInfluence()).isEqualTo(0.0);
        assertThat(converter.isEarlyReflectionsEnabled()).isFalse();
        assertThat(converter.getHrtfData()).isNull();
        assertThat(converter.getRoomSimulator()).isNull();
    }

    @Test
    void shouldReturnCorrectChannelCounts() {
        assertThat(converter.getInputChannelCount()).isEqualTo(2);
        assertThat(converter.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldPassThroughWithoutHrtfLoaded() {
        float[][] input = {
                {0.5f, 0.3f, -0.2f},
                {-0.1f, 0.7f, 0.4f}
        };
        float[][] output = new float[2][3];

        converter.process(input, output, 3);

        assertThat(output[0]).containsExactly(input[0]);
        assertThat(output[1]).containsExactly(input[1]);
    }

    @Test
    void shouldProduceBinauralOutput() {
        converter.loadHrtfData(hrtfData);

        // Prime the convolvers with zeros
        float[][] zeroInput = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        float[][] zeroOutput = new float[2][BLOCK_SIZE];
        converter.process(zeroInput, zeroOutput, BLOCK_SIZE);

        // Process an impulse on the left channel
        float[][] input = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        input[0][0] = 1.0f;
        float[][] output = new float[2][BLOCK_SIZE];
        converter.process(input, output, BLOCK_SIZE);

        // Both output channels should have non-zero content
        boolean leftHasSignal = false;
        boolean rightHasSignal = false;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if (Math.abs(output[0][i]) > 1e-6f) leftHasSignal = true;
            if (Math.abs(output[1][i]) > 1e-6f) rightHasSignal = true;
        }
        assertThat(leftHasSignal).isTrue();
        assertThat(rightHasSignal).isTrue();
    }

    @Test
    void shouldDifferentiateLeftAndRightChannels() {
        converter.loadHrtfData(hrtfData);

        // Prime
        float[][] zeroInput = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        float[][] zeroOutput = new float[2][BLOCK_SIZE];
        converter.process(zeroInput, zeroOutput, BLOCK_SIZE);

        // Process impulse only on left channel
        float[][] input = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        input[0][0] = 1.0f;
        float[][] output = new float[2][BLOCK_SIZE];
        converter.process(input, output, BLOCK_SIZE);

        // Left input is placed at left speaker (+30°), which has louder left HRTF
        // So left ear output should be louder than right ear output
        double leftPeak = 0;
        double rightPeak = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            leftPeak = Math.max(leftPeak, Math.abs(output[0][i]));
            rightPeak = Math.max(rightPeak, Math.abs(output[1][i]));
        }
        assertThat(leftPeak).isGreaterThan(rightPeak);
    }

    @Test
    void shouldProduceSymmetricOutputForBalancedStereo() {
        converter.loadHrtfData(hrtfData);

        // Prime
        float[][] zeroInput = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        float[][] zeroOutput = new float[2][BLOCK_SIZE];
        converter.process(zeroInput, zeroOutput, BLOCK_SIZE);

        // Process identical impulses on both channels
        float[][] input = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        float[][] output = new float[2][BLOCK_SIZE];
        converter.process(input, output, BLOCK_SIZE);

        // Left and right output should be approximately equal for a centered image
        double leftPeak = 0;
        double rightPeak = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            leftPeak = Math.max(leftPeak, Math.abs(output[0][i]));
            rightPeak = Math.max(rightPeak, Math.abs(output[1][i]));
        }
        assertThat(leftPeak).isCloseTo((float) rightPeak, within(0.01));
    }

    @Test
    void shouldSetSpeakerAzimuth() {
        converter.loadHrtfData(hrtfData);
        converter.setSpeakerAzimuth(45.0);
        assertThat(converter.getSpeakerAzimuth()).isEqualTo(45.0);
    }

    @Test
    void shouldSetSpeakerDistance() {
        converter.loadHrtfData(hrtfData);
        converter.setSpeakerDistance(2.0);
        assertThat(converter.getSpeakerDistance()).isEqualTo(2.0);
    }

    @Test
    void shouldSetRoomInfluence() {
        converter.setRoomInfluence(0.5);
        assertThat(converter.getRoomInfluence()).isEqualTo(0.5);
    }

    @Test
    void shouldEnableEarlyReflections() {
        converter.loadHrtfData(hrtfData);
        converter.setEarlyReflectionsEnabled(true);
        assertThat(converter.isEarlyReflectionsEnabled()).isTrue();
    }

    @Test
    void shouldProcessWithEarlyReflections() {
        converter.loadHrtfData(hrtfData);
        converter.setEarlyReflectionsEnabled(true);

        // Prime
        float[][] zeroInput = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        float[][] zeroOutput = new float[2][BLOCK_SIZE];
        converter.process(zeroInput, zeroOutput, BLOCK_SIZE);

        // Process impulse on both channels
        float[][] input = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        float[][] output = new float[2][BLOCK_SIZE];
        converter.process(input, output, BLOCK_SIZE);

        boolean hasSignal = false;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if (Math.abs(output[0][i]) > 1e-6f || Math.abs(output[1][i]) > 1e-6f) {
                hasSignal = true;
                break;
            }
        }
        assertThat(hasSignal).isTrue();
    }

    @Test
    void shouldSetRoomSimulator() {
        FdnRoomSimulator simulator = new FdnRoomSimulator();
        converter.setRoomSimulator(simulator);
        assertThat(converter.getRoomSimulator()).isSameAs(simulator);
    }

    @Test
    void shouldClearRoomSimulator() {
        FdnRoomSimulator simulator = new FdnRoomSimulator();
        converter.setRoomSimulator(simulator);
        converter.setRoomSimulator(null);
        assertThat(converter.getRoomSimulator()).isNull();
    }

    @Test
    void shouldProcessWithRoomSimulator() {
        converter.loadHrtfData(hrtfData);

        FdnRoomSimulator simulator = new FdnRoomSimulator();
        RoomSimulationConfig config = new RoomSimulationConfig(
                new RoomDimensions(6.0, 8.0, 3.0),
                Map.of(),
                WallMaterial.DRYWALL,
                List.of(new SoundSource("Src", new Position3D(3.0, 2.0, 1.0), 85)),
                new ListenerOrientation(new Position3D(3.0, 4.0, 1.2), 0.0, 0.0),
                (int) SAMPLE_RATE);
        simulator.configure(config);
        converter.setRoomSimulator(simulator);
        converter.setRoomInfluence(0.5);

        // Prime
        float[][] zeroInput = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        float[][] zeroOutput = new float[2][BLOCK_SIZE];
        converter.process(zeroInput, zeroOutput, BLOCK_SIZE);

        // Process
        float[][] input = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        float[][] output = new float[2][BLOCK_SIZE];
        converter.process(input, output, BLOCK_SIZE);

        boolean hasSignal = false;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if (Math.abs(output[0][i]) > 1e-6f || Math.abs(output[1][i]) > 1e-6f) {
                hasSignal = true;
                break;
            }
        }
        assertThat(hasSignal).isTrue();
    }

    @Test
    void shouldResetWithoutError() {
        converter.loadHrtfData(hrtfData);
        converter.reset();

        // Process after reset should work
        float[][] input = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        float[][] output = new float[2][BLOCK_SIZE];
        converter.process(input, output, BLOCK_SIZE);
    }

    @Test
    void shouldResetWithRoomSimulator() {
        converter.loadHrtfData(hrtfData);
        FdnRoomSimulator simulator = new FdnRoomSimulator();
        converter.setRoomSimulator(simulator);
        converter.reset();

        // No exception
        float[][] input = {new float[BLOCK_SIZE], new float[BLOCK_SIZE]};
        float[][] output = new float[2][BLOCK_SIZE];
        converter.process(input, output, BLOCK_SIZE);
    }

    @Test
    void shouldRejectInvalidConstructorArgs() {
        assertThatThrownBy(() -> new StereoToBinauralConverter(0, BLOCK_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StereoToBinauralConverter(-1, BLOCK_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StereoToBinauralConverter(SAMPLE_RATE, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StereoToBinauralConverter(SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSpeakerAzimuth() {
        assertThatThrownBy(() -> converter.setSpeakerAzimuth(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.setSpeakerAzimuth(91.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSpeakerDistance() {
        assertThatThrownBy(() -> converter.setSpeakerDistance(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.setSpeakerDistance(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidRoomInfluence() {
        assertThatThrownBy(() -> converter.setRoomInfluence(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.setRoomInfluence(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullHrtfData() {
        assertThatThrownBy(() -> converter.loadHrtfData(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldLoadHrtfData() {
        converter.loadHrtfData(hrtfData);
        assertThat(converter.getHrtfData()).isSameAs(hrtfData);
    }
}
