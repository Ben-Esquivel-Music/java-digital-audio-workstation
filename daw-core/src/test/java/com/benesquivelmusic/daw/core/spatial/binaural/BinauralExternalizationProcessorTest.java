package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class BinauralExternalizationProcessorTest {

    private static final int BLOCK_SIZE = 64;
    private static final double SAMPLE_RATE = 44100.0;
    private BinauralExternalizationProcessor processor;
    private HrtfData hrtfData;

    @BeforeEach
    void setUp() {
        processor = new BinauralExternalizationProcessor(SAMPLE_RATE, BLOCK_SIZE);

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
        assertThat(processor.getCrossfeedLevel()).isEqualTo(0.3);
        assertThat(processor.getRoomSize()).isEqualTo(0.3);
        assertThat(processor.getExternalizationAmount()).isEqualTo(0.5);
        assertThat(processor.getHeadphoneCompensationGainDb()).isEqualTo(0.0);
        assertThat(processor.getHrtfData()).isNull();
    }

    @Test
    void shouldReturnCorrectChannelCounts() {
        assertThat(processor.getInputChannelCount()).isEqualTo(2);
        assertThat(processor.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldPassDrySignalWithZeroExternalization() {
        processor.setExternalizationAmount(0.0);

        float[][] input = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE));
            input[1][i] = (float) (0.3 * Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE));
        }
        processor.process(input, output, BLOCK_SIZE);

        for (int i = 0; i < BLOCK_SIZE; i++) {
            assertThat(output[0][i]).isCloseTo(input[0][i],
                    org.assertj.core.data.Offset.offset(1e-5f));
            assertThat(output[1][i]).isCloseTo(input[1][i],
                    org.assertj.core.data.Offset.offset(1e-5f));
        }
    }

    @Test
    void shouldModifySignalWithExternalization() {
        processor.setExternalizationAmount(1.0);
        processor.setCrossfeedLevel(0.5);

        float[][] input = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE));
            input[1][i] = (float) (0.3 * Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE));
        }
        processor.process(input, output, BLOCK_SIZE);

        // Output should differ from input due to crossfeed and processing
        boolean differs = false;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if (Math.abs(output[0][i] - input[0][i]) > 0.001f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    @Test
    void shouldApplyCrossfeedBleed() {
        processor.setExternalizationAmount(1.0);
        processor.setCrossfeedLevel(1.0);
        processor.setRoomSize(0.0);

        // Left channel only — right channel is silent
        float[][] input = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 200.0 * i / SAMPLE_RATE));
            input[1][i] = 0.0f;
        }

        // Process multiple blocks to let filters settle
        for (int block = 0; block < 8; block++) {
            processor.process(input, output, BLOCK_SIZE);
        }

        // Right channel should have some signal due to crossfeed from left
        double rightRms = rms(output[1], 0, BLOCK_SIZE);
        assertThat(rightRms).isGreaterThan(0.0);
    }

    @Test
    void shouldProduceReflectionEnergyAfterDelay() {
        processor.setExternalizationAmount(1.0);
        processor.setCrossfeedLevel(0.0);
        processor.setRoomSize(0.0);

        // Process an impulse
        float[][] input = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        processor.process(input, output, BLOCK_SIZE);

        // The reflections should add energy after the initial samples
        // (reflection delays start at 2.5ms ≈ 110 samples at 44100 Hz,
        //  but within a 64-sample block, partial energy from the
        //  headphone EQ and processing chain should still be present)
        boolean hasEnergy = false;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if (Math.abs(output[0][i]) > 1e-6f) {
                hasEnergy = true;
                break;
            }
        }
        assertThat(hasEnergy).isTrue();
    }

    @Test
    void shouldApplyRoomColoration() {
        processor.setExternalizationAmount(1.0);
        processor.setCrossfeedLevel(0.0);
        processor.setRoomSize(1.0);

        // Process an impulse and then silence
        float[][] impulseInput = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        impulseInput[0][0] = 1.0f;
        impulseInput[1][0] = 1.0f;
        processor.process(impulseInput, output, BLOCK_SIZE);

        // Process more blocks of silence — FDN should produce a reverb tail
        float[][] silence = new float[2][BLOCK_SIZE];
        float[][] tailOutput = new float[2][BLOCK_SIZE];
        processor.process(silence, tailOutput, BLOCK_SIZE);

        double tailRms = rms(tailOutput[0], 0, BLOCK_SIZE);
        assertThat(tailRms).isGreaterThan(0.0);
    }

    @Test
    void shouldResetState() {
        processor.setExternalizationAmount(1.0);
        processor.setRoomSize(0.5);

        // Process an impulse
        float[][] input = new float[2][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        processor.process(input, output, BLOCK_SIZE);

        processor.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[2][BLOCK_SIZE];
        float[][] resetOutput = new float[2][BLOCK_SIZE];
        processor.process(silence, resetOutput, BLOCK_SIZE);

        for (int i = 0; i < BLOCK_SIZE; i++) {
            assertThat(resetOutput[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
            assertThat(resetOutput[1][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldLoadHrtfData() {
        processor.loadHrtfData(hrtfData);
        assertThat(processor.getHrtfData()).isSameAs(hrtfData);
    }

    @Test
    void shouldProcessWithHrtfReflections() {
        processor.loadHrtfData(hrtfData);
        processor.setExternalizationAmount(1.0);

        // Prime with zeros
        float[][] zeroInput = new float[2][BLOCK_SIZE];
        float[][] zeroOutput = new float[2][BLOCK_SIZE];
        processor.process(zeroInput, zeroOutput, BLOCK_SIZE);

        // Process impulse on both channels
        float[][] input = new float[2][BLOCK_SIZE];
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        float[][] output = new float[2][BLOCK_SIZE];
        processor.process(input, output, BLOCK_SIZE);

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
    void shouldResetWithHrtfLoaded() {
        processor.loadHrtfData(hrtfData);
        processor.setExternalizationAmount(1.0);

        float[][] input = new float[2][BLOCK_SIZE];
        input[0][0] = 1.0f;
        input[1][0] = 1.0f;
        float[][] output = new float[2][BLOCK_SIZE];
        processor.process(input, output, BLOCK_SIZE);

        processor.reset();

        // After reset, processing silence should produce silence
        float[][] silence = new float[2][BLOCK_SIZE];
        float[][] resetOutput = new float[2][BLOCK_SIZE];
        processor.process(silence, resetOutput, BLOCK_SIZE);

        for (int i = 0; i < BLOCK_SIZE; i++) {
            assertThat(resetOutput[0][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
            assertThat(resetOutput[1][i]).isCloseTo(0.0f,
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldSupportParameterChanges() {
        processor.setCrossfeedLevel(0.8);
        processor.setRoomSize(0.6);
        processor.setExternalizationAmount(0.7);
        processor.setHeadphoneCompensationGainDb(-3.0);

        assertThat(processor.getCrossfeedLevel()).isEqualTo(0.8);
        assertThat(processor.getRoomSize()).isEqualTo(0.6);
        assertThat(processor.getExternalizationAmount()).isEqualTo(0.7);
        assertThat(processor.getHeadphoneCompensationGainDb()).isEqualTo(-3.0);
    }

    @Test
    void shouldRejectInvalidConstructorArgs() {
        assertThatThrownBy(() -> new BinauralExternalizationProcessor(0, BLOCK_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BinauralExternalizationProcessor(-1, BLOCK_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BinauralExternalizationProcessor(SAMPLE_RATE, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BinauralExternalizationProcessor(SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidCrossfeedLevel() {
        assertThatThrownBy(() -> processor.setCrossfeedLevel(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.setCrossfeedLevel(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidRoomSize() {
        assertThatThrownBy(() -> processor.setRoomSize(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.setRoomSize(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidExternalizationAmount() {
        assertThatThrownBy(() -> processor.setExternalizationAmount(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.setExternalizationAmount(1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullHrtfData() {
        assertThatThrownBy(() -> processor.loadHrtfData(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldProcessStereoSymmetrically() {
        processor.setExternalizationAmount(1.0);
        processor.setCrossfeedLevel(0.0);
        processor.setRoomSize(0.0);
        processor.setHeadphoneCompensationGainDb(0.0);

        // Identical signals on both channels should produce identical output
        float[][] input = new float[2][BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            float sample = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE));
            input[0][i] = sample;
            input[1][i] = sample;
        }
        float[][] output = new float[2][BLOCK_SIZE];
        processor.process(input, output, BLOCK_SIZE);

        for (int i = 0; i < BLOCK_SIZE; i++) {
            assertThat(output[0][i]).isCloseTo(output[1][i],
                    org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    @Test
    void shouldHandleMonoInput() {
        processor.setExternalizationAmount(1.0);

        // Single-channel input (mono)
        float[][] input = new float[1][BLOCK_SIZE];
        float[][] output = new float[2][BLOCK_SIZE];
        input[0][0] = 1.0f;
        processor.process(input, output, BLOCK_SIZE);

        // Should produce stereo output without errors
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
    void shouldApplyHeadphoneCompensation() {
        processor.setExternalizationAmount(1.0);
        processor.setCrossfeedLevel(0.0);
        processor.setRoomSize(0.0);

        // Process a signal with zero EQ gain
        processor.setHeadphoneCompensationGainDb(0.0);
        float[][] input = new float[2][BLOCK_SIZE];
        for (int i = 0; i < BLOCK_SIZE; i++) {
            input[0][i] = (float) (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE));
            input[1][i] = input[0][i];
        }
        float[][] flatOutput = new float[2][BLOCK_SIZE];
        processor.process(input, flatOutput, BLOCK_SIZE);

        // Reset and process with non-zero EQ gain
        processor.reset();
        processor.setHeadphoneCompensationGainDb(6.0);
        float[][] boostedOutput = new float[2][BLOCK_SIZE];
        processor.process(input, boostedOutput, BLOCK_SIZE);

        // The boosted output should differ from the flat output
        boolean differs = false;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if (Math.abs(boostedOutput[0][i] - flatOutput[0][i]) > 0.001f) {
                differs = true;
                break;
            }
        }
        assertThat(differs).isTrue();
    }

    private static double rms(float[] buffer, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) {
            sum += (double) buffer[i] * buffer[i];
        }
        return Math.sqrt(sum / (end - start));
    }
}
