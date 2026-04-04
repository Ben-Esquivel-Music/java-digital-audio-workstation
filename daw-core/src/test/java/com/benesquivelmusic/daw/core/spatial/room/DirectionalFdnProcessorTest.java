package com.benesquivelmusic.daw.core.spatial.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class DirectionalFdnProcessorTest {

    private static final int SAMPLE_RATE = 48000;
    private static final double ROOM_SIZE = 8.0;
    private static final double DECAY_SECONDS = 1.5;
    private static final double DAMPING = 0.3;
    private static final double SPREAD = 1.0;
    private static final double TOLERANCE = 1e-6;

    private DirectionalFdnProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new DirectionalFdnProcessor(SAMPLE_RATE, ROOM_SIZE, DECAY_SECONDS, DAMPING, SPREAD);
    }

    // ---- Construction ----

    @Test
    void shouldCreateWithValidParameters() {
        assertThat(processor.getSampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(processor.getRoomSize()).isEqualTo(ROOM_SIZE);
        assertThat(processor.getDecaySeconds()).isEqualTo(DECAY_SECONDS);
        assertThat(processor.getDamping()).isEqualTo(DAMPING);
        assertThat(processor.getDirectionalSpread()).isEqualTo(SPREAD);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> new DirectionalFdnProcessor(0, ROOM_SIZE, DECAY_SECONDS, DAMPING, SPREAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");
    }

    @Test
    void shouldRejectNonPositiveRoomSize() {
        assertThatThrownBy(() -> new DirectionalFdnProcessor(SAMPLE_RATE, 0, DECAY_SECONDS, DAMPING, SPREAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roomSize");
    }

    @Test
    void shouldRejectNonPositiveDecay() {
        assertThatThrownBy(() -> new DirectionalFdnProcessor(SAMPLE_RATE, ROOM_SIZE, 0, DAMPING, SPREAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decaySeconds");
    }

    @Test
    void shouldRejectDampingOutOfRange() {
        assertThatThrownBy(() -> new DirectionalFdnProcessor(SAMPLE_RATE, ROOM_SIZE, DECAY_SECONDS, -0.1, SPREAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("damping");
        assertThatThrownBy(() -> new DirectionalFdnProcessor(SAMPLE_RATE, ROOM_SIZE, DECAY_SECONDS, 1.1, SPREAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("damping");
    }

    @Test
    void shouldRejectSpreadOutOfRange() {
        assertThatThrownBy(() -> new DirectionalFdnProcessor(SAMPLE_RATE, ROOM_SIZE, DECAY_SECONDS, DAMPING, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directionalSpread");
        assertThatThrownBy(() -> new DirectionalFdnProcessor(SAMPLE_RATE, ROOM_SIZE, DECAY_SECONDS, DAMPING, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("directionalSpread");
    }

    // ---- Channel counts ----

    @Test
    void shouldReportMonoInput() {
        assertThat(processor.getInputChannelCount()).isEqualTo(1);
    }

    @Test
    void shouldReportFoaOutput() {
        assertThat(processor.getOutputChannelCount()).isEqualTo(4);
    }

    // ---- Processing ----

    @Test
    void shouldProduceSilenceForSilentInput() {
        int numFrames = 256;
        float[][] input = {new float[numFrames]};
        float[][] output = new float[4][numFrames];

        processor.process(input, output, numFrames);

        for (int ch = 0; ch < 4; ch++) {
            for (int n = 0; n < numFrames; n++) {
                assertThat(output[ch][n]).isZero();
            }
        }
    }

    @Test
    void shouldProduceOutputForImpulseInput() {
        // Process an impulse through several blocks to allow delay lines to produce output
        int blockSize = 512;
        int numBlocks = 8;

        float[][] input = {new float[blockSize]};
        float[][] output = new float[4][blockSize];

        // First block: inject impulse
        input[0][0] = 1.0f;
        processor.process(input, output, blockSize);

        // Clear input for subsequent blocks
        input[0][0] = 0.0f;

        boolean hasNonZero = false;
        for (int block = 0; block < numBlocks; block++) {
            processor.process(input, output, blockSize);
            for (int ch = 0; ch < 4; ch++) {
                for (int n = 0; n < blockSize; n++) {
                    if (Math.abs(output[ch][n]) > 1e-10f) {
                        hasNonZero = true;
                    }
                }
            }
        }

        assertThat(hasNonZero).as("FDN should produce reverb tail after impulse").isTrue();
    }

    @Test
    void shouldProduceOmnidirectionalWChannelOutput() {
        // W channel (index 0) should always have output regardless of spread
        int blockSize = 1024;
        int numBlocks = 4;

        float[][] input = {new float[blockSize]};
        float[][] output = new float[4][blockSize];

        input[0][0] = 1.0f;
        processor.process(input, output, blockSize);
        input[0][0] = 0.0f;

        double wEnergy = 0;
        for (int block = 0; block < numBlocks; block++) {
            processor.process(input, output, blockSize);
            for (int n = 0; n < blockSize; n++) {
                wEnergy += output[0][n] * output[0][n];
            }
        }

        assertThat(wEnergy).as("W channel should carry energy").isGreaterThan(0);
    }

    @Test
    void shouldDistributeEnergyAcrossDirectionalChannelsWithFullSpread() {
        // With full spread (1.0), Y and X channels should have energy
        int blockSize = 1024;
        int numBlocks = 4;

        float[][] input = {new float[blockSize]};
        float[][] output = new float[4][blockSize];

        input[0][0] = 1.0f;
        processor.process(input, output, blockSize);
        input[0][0] = 0.0f;

        double[] channelEnergy = new double[4];
        for (int block = 0; block < numBlocks; block++) {
            processor.process(input, output, blockSize);
            for (int ch = 0; ch < 4; ch++) {
                for (int n = 0; n < blockSize; n++) {
                    channelEnergy[ch] += output[ch][n] * output[ch][n];
                }
            }
        }

        // All FOA channels should have some energy with full directional spread
        assertThat(channelEnergy[0]).as("W energy").isGreaterThan(0);
        assertThat(channelEnergy[1]).as("Y energy").isGreaterThan(0);
        assertThat(channelEnergy[2]).as("Z energy").isGreaterThan(0);
        assertThat(channelEnergy[3]).as("X energy").isGreaterThan(0);
    }

    @Test
    void shouldCollapseToFrontWithZeroSpread() {
        DirectionalFdnProcessor collapsed =
                new DirectionalFdnProcessor(SAMPLE_RATE, ROOM_SIZE, DECAY_SECONDS, DAMPING, 0.0);

        int blockSize = 1024;
        int numBlocks = 4;

        float[][] input = {new float[blockSize]};
        float[][] output = new float[4][blockSize];

        input[0][0] = 1.0f;
        collapsed.process(input, output, blockSize);
        input[0][0] = 0.0f;

        double wEnergy = 0;
        double yEnergy = 0;
        double xEnergy = 0;

        for (int block = 0; block < numBlocks; block++) {
            collapsed.process(input, output, blockSize);
            for (int n = 0; n < blockSize; n++) {
                wEnergy += output[0][n] * output[0][n];
                yEnergy += output[1][n] * output[1][n];
                xEnergy += output[3][n] * output[3][n];
            }
        }

        // With zero spread all directions are (0,0) i.e. front
        // Y should be near zero (sin(0)=0), X should be strong (cos(0)=1)
        assertThat(wEnergy).as("W energy at zero spread").isGreaterThan(0);
        assertThat(yEnergy).as("Y should be zero at zero spread").isCloseTo(0.0, within(1e-10));
        assertThat(xEnergy).as("X energy at zero spread").isGreaterThan(0);
    }

    @Test
    void shouldDecayOverTime() {
        int blockSize = 1024;

        float[][] input = {new float[blockSize]};
        float[][] output = new float[4][blockSize];

        // Inject impulse
        input[0][0] = 1.0f;
        processor.process(input, output, blockSize);
        input[0][0] = 0.0f;

        // Measure energy in early and late blocks
        double earlyEnergy = 0;
        for (int block = 0; block < 2; block++) {
            processor.process(input, output, blockSize);
            for (int n = 0; n < blockSize; n++) {
                earlyEnergy += output[0][n] * output[0][n];
            }
        }

        // Skip several blocks
        for (int block = 0; block < 20; block++) {
            processor.process(input, output, blockSize);
        }

        double lateEnergy = 0;
        for (int block = 0; block < 2; block++) {
            processor.process(input, output, blockSize);
            for (int n = 0; n < blockSize; n++) {
                lateEnergy += output[0][n] * output[0][n];
            }
        }

        assertThat(lateEnergy).as("Late reverb energy should be less than early").isLessThan(earlyEnergy);
    }

    // ---- Reset ----

    @Test
    void shouldResetToSilence() {
        int blockSize = 256;

        float[][] input = {new float[blockSize]};
        float[][] output = new float[4][blockSize];

        // Inject signal
        Arrays.fill(input[0], 0.5f);
        processor.process(input, output, blockSize);

        // Reset
        processor.reset();

        // Process silence — output should be silent
        Arrays.fill(input[0], 0.0f);
        processor.process(input, output, blockSize);

        for (int ch = 0; ch < 4; ch++) {
            for (int n = 0; n < blockSize; n++) {
                assertThat(output[ch][n]).as("channel %d frame %d after reset", ch, n).isZero();
            }
        }
    }

    // ---- Parameter updates ----

    @Test
    void shouldUpdateRoomSize() {
        processor.setRoomSize(20.0);
        assertThat(processor.getRoomSize()).isEqualTo(20.0);
    }

    @Test
    void shouldRejectInvalidRoomSizeUpdate() {
        assertThatThrownBy(() -> processor.setRoomSize(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUpdateDecaySeconds() {
        processor.setDecaySeconds(3.0);
        assertThat(processor.getDecaySeconds()).isEqualTo(3.0);
    }

    @Test
    void shouldRejectInvalidDecayUpdate() {
        assertThatThrownBy(() -> processor.setDecaySeconds(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUpdateDamping() {
        processor.setDamping(0.8);
        assertThat(processor.getDamping()).isEqualTo(0.8);
    }

    @Test
    void shouldRejectInvalidDampingUpdate() {
        assertThatThrownBy(() -> processor.setDamping(1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUpdateDirectionalSpread() {
        processor.setDirectionalSpread(0.5);
        assertThat(processor.getDirectionalSpread()).isEqualTo(0.5);
    }

    @Test
    void shouldRejectInvalidSpreadUpdate() {
        assertThatThrownBy(() -> processor.setDirectionalSpread(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Directional assignment ----

    @Test
    void shouldAssignDistinctDirectionsToDelayLines() {
        // With full spread, each delay line should have a unique azimuth
        boolean allSame = true;
        double firstAz = processor.getDelayLineAzimuth(0);
        for (int i = 1; i < DirectionalFdnProcessor.NUM_DELAY_LINES; i++) {
            if (Math.abs(processor.getDelayLineAzimuth(i) - firstAz) > 1e-6) {
                allSame = false;
                break;
            }
        }
        assertThat(allSame).as("Delay lines should have distinct directions with full spread").isFalse();
    }

    @Test
    void shouldCollapseDirectionsAtZeroSpread() {
        DirectionalFdnProcessor collapsed =
                new DirectionalFdnProcessor(SAMPLE_RATE, ROOM_SIZE, DECAY_SECONDS, DAMPING, 0.0);

        for (int i = 0; i < DirectionalFdnProcessor.NUM_DELAY_LINES; i++) {
            assertThat(collapsed.getDelayLineAzimuth(i))
                    .as("azimuth %d at zero spread", i).isCloseTo(0.0, within(TOLERANCE));
            assertThat(collapsed.getDelayLineElevation(i))
                    .as("elevation %d at zero spread", i).isCloseTo(0.0, within(TOLERANCE));
        }
    }

    @Test
    void shouldIncludeElevationVariationAtFullSpread() {
        boolean hasPositiveEl = false;
        boolean hasNegativeEl = false;

        for (int i = 0; i < DirectionalFdnProcessor.NUM_DELAY_LINES; i++) {
            double el = processor.getDelayLineElevation(i);
            if (el > 0.01) hasPositiveEl = true;
            if (el < -0.01) hasNegativeEl = true;
        }

        assertThat(hasPositiveEl).as("Should have positive elevation lines").isTrue();
        assertThat(hasNegativeEl).as("Should have negative elevation lines").isTrue();
    }

    // ---- Edge cases ----

    @Test
    void shouldHandleSmallBufferSizes() {
        float[][] input = {new float[1]};
        float[][] output = new float[4][1];
        input[0][0] = 0.5f;

        // Should not throw
        processor.process(input, output, 1);
    }

    @Test
    void shouldHandleStereoInput() {
        int numFrames = 64;
        float[][] input = {new float[numFrames], new float[numFrames]};
        float[][] output = new float[4][numFrames];
        Arrays.fill(input[0], 0.5f);
        Arrays.fill(input[1], 0.5f);

        // Should not throw — stereo input is downmixed to mono
        processor.process(input, output, numFrames);
    }

    @Test
    void shouldProduceLongerReverbWithLargerDecay() {
        DirectionalFdnProcessor shortDecay =
                new DirectionalFdnProcessor(SAMPLE_RATE, ROOM_SIZE, 0.5, DAMPING, SPREAD);
        DirectionalFdnProcessor longDecay =
                new DirectionalFdnProcessor(SAMPLE_RATE, ROOM_SIZE, 3.0, DAMPING, SPREAD);

        int blockSize = 1024;
        int numBlocks = 30;

        double shortLateEnergy = measureLateEnergy(shortDecay, blockSize, numBlocks);
        double longLateEnergy = measureLateEnergy(longDecay, blockSize, numBlocks);

        assertThat(longLateEnergy).as("Longer decay should produce more late energy")
                .isGreaterThan(shortLateEnergy);
    }

    @Test
    void shouldUseEightDelayLines() {
        assertThat(DirectionalFdnProcessor.NUM_DELAY_LINES).isEqualTo(8);
    }

    // ---- Helpers ----

    private double measureLateEnergy(DirectionalFdnProcessor proc, int blockSize, int numBlocks) {
        float[][] input = {new float[blockSize]};
        float[][] output = new float[4][blockSize];

        // Inject impulse
        input[0][0] = 1.0f;
        proc.process(input, output, blockSize);
        input[0][0] = 0.0f;

        // Skip early blocks
        for (int block = 0; block < numBlocks; block++) {
            proc.process(input, output, blockSize);
        }

        // Measure late energy
        double energy = 0;
        for (int block = 0; block < 2; block++) {
            proc.process(input, output, blockSize);
            for (int n = 0; n < blockSize; n++) {
                energy += output[0][n] * output[0][n];
            }
        }
        return energy;
    }
}
