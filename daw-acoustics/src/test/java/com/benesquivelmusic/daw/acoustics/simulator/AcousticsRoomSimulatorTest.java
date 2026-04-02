package com.benesquivelmusic.daw.acoustics.simulator;

import com.benesquivelmusic.daw.sdk.spatial.ImpulseResponse;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig;
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

class AcousticsRoomSimulatorTest {

    private static final int SAMPLE_RATE = 48000;
    private static final RoomDimensions STUDIO_DIMS = new RoomDimensions(6.0, 8.0, 3.0);
    private static final ListenerOrientation LISTENER =
            new ListenerOrientation(new Position3D(3.0, 4.0, 1.2), 0.0, 0.0);
    private static final SoundSource GUITAR =
            new SoundSource("Guitar", new Position3D(2.0, 2.0, 1.0), 85);

    private AcousticsRoomSimulator simulator;

    @BeforeEach
    void setUp() {
        simulator = new AcousticsRoomSimulator();
    }

    @Test
    void shouldReportNotNativeAccelerated() {
        assertThat(simulator.isNativeAccelerated()).isFalse();
    }

    @Test
    void shouldReturnNullConfigBeforeConfiguration() {
        assertThat(simulator.getConfiguration()).isNull();
    }

    @Test
    void shouldThrowWhenGeneratingIrWithoutConfiguration() {
        assertThatThrownBy(() -> simulator.generateImpulseResponse())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldConfigureAndGenerateImpulseResponse() {
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        ImpulseResponse ir = simulator.generateImpulseResponse();

        assertThat(ir).isNotNull();
        assertThat(ir.channelCount()).isEqualTo(1);
        assertThat(ir.sampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(ir.lengthInSamples()).isGreaterThan(0);
    }

    @Test
    void shouldGenerateIrWithPositiveDuration() {
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        ImpulseResponse ir = simulator.generateImpulseResponse();
        assertThat(ir.durationSeconds()).isGreaterThan(0.0);
    }

    @Test
    void shouldReturnConfigurationAfterConfigure() {
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        assertThat(simulator.getConfiguration()).isSameAs(config);
    }

    @Test
    void shouldUpdateListenerOrientation() {
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        ListenerOrientation newListener =
                new ListenerOrientation(new Position3D(4.0, 5.0, 1.2), 90.0, 0.0);
        simulator.setListenerOrientation(newListener);

        assertThat(simulator.getListenerOrientation()).isEqualTo(newListener);
    }

    @Test
    void shouldAddAndRemoveSources() {
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        SoundSource drums = new SoundSource("Drums", new Position3D(4, 3, 1), 90);
        simulator.addSource(drums);

        assertThat(simulator.removeSource("Drums")).isTrue();
        assertThat(simulator.removeSource("Nonexistent")).isFalse();
    }

    @Test
    void shouldUpdateSourcePosition() {
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        assertThat(simulator.updateSourcePosition("Guitar",
                new Position3D(4, 3, 1))).isTrue();
        assertThat(simulator.updateSourcePosition("Nonexistent",
                new Position3D(0, 0, 0))).isFalse();
    }

    @Test
    void shouldPassThroughWhenNotConfigured() {
        float[][] input = {{0.5f, 0.3f, 0.1f}};
        float[][] output = {{0.0f, 0.0f, 0.0f}};

        simulator.process(input, output, 3);

        assertThat(output[0][0]).isEqualTo(0.5f);
        assertThat(output[0][1]).isEqualTo(0.3f);
        assertThat(output[0][2]).isEqualTo(0.1f);
    }

    @Test
    void shouldProcessAudioAfterConfiguration() {
        SoundSource nearSource =
                new SoundSource("NearGuitar", new Position3D(3.0, 3.5, 1.2), 85);
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(nearSource), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        int numFrames = 1024;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];
        input[0][0] = 1.0f;

        simulator.process(input, output, numFrames);

        boolean hasNonZero = false;
        for (float sample : output[0]) {
            if (Math.abs(sample) > 1e-10f) {
                hasNonZero = true;
                break;
            }
        }
        assertThat(hasNonZero).isTrue();
    }

    @Test
    void shouldResetState() {
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(config);
        simulator.generateImpulseResponse();

        simulator.reset();

        ImpulseResponse ir = simulator.generateImpulseResponse();
        assertThat(ir).isNotNull();
    }

    @Test
    void shouldReturnCorrectChannelCounts() {
        assertThat(simulator.getInputChannelCount()).isEqualTo(1);
        assertThat(simulator.getOutputChannelCount()).isEqualTo(1);
    }

    @Test
    void shouldGenerateLongerIrForLargerRooms() {
        RoomSimulationConfig smallRoom = new RoomSimulationConfig(
                new RoomDimensions(3, 3, 2.5), Map.of(), WallMaterial.ACOUSTIC_FOAM,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        RoomSimulationConfig largeRoom = new RoomSimulationConfig(
                new RoomDimensions(30, 60, 25), Map.of(), WallMaterial.CONCRETE,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);

        simulator.configure(smallRoom);
        ImpulseResponse smallIr = simulator.generateImpulseResponse();

        simulator.configure(largeRoom);
        ImpulseResponse largeIr = simulator.generateImpulseResponse();

        assertThat(largeIr.durationSeconds()).isGreaterThan(smallIr.durationSeconds());
    }

    @Test
    void shouldEstimateRt60Correctly() {
        RoomDimensions dims = new RoomDimensions(10, 10, 10);
        double rt60 = AcousticsRoomSimulator.estimateRt60(dims, 0.161);
        assertThat(rt60).isCloseTo(0.161 * 1000.0 / (600.0 * 0.161), within(0.01));
    }

    @Test
    void shouldHandlePerSurfaceMaterials() {
        Map<String, WallMaterial> surfaceMaterials = Map.of(
                "floor", WallMaterial.CARPET,
                "ceiling", WallMaterial.ACOUSTIC_TILE,
                "leftWall", WallMaterial.DRYWALL,
                "rightWall", WallMaterial.DRYWALL,
                "frontWall", WallMaterial.GLASS,
                "backWall", WallMaterial.CURTAINS);

        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, surfaceMaterials, WallMaterial.DRYWALL,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        ImpulseResponse ir = simulator.generateImpulseResponse();
        assertThat(ir).isNotNull();
        assertThat(ir.lengthInSamples()).isGreaterThan(0);
    }

    @Test
    void shouldApplyCorrectSurfaceMaterialToMatchingWallReflection() {
        // Changing a specific surface's material from highly reflective to
        // highly absorptive should reduce the early-reflection energy of that
        // wall's first-order image source while leaving other surfaces unaffected.
        // Use CONCRETE (α=0.02, very reflective) vs ACOUSTIC_FOAM (α=0.70, absorptive).

        // Baseline: all surfaces are CONCRETE (maximally reflective)
        RoomSimulationConfig reflectiveConfig = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.CONCRETE,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(reflectiveConfig);
        ImpulseResponse reflectiveIr = simulator.generateImpulseResponse();
        double reflectiveEnergy = computeIrEnergy(reflectiveIr.samples()[0]);

        // Now make the floor highly absorptive — should reduce total early energy
        RoomSimulationConfig absorbentFloorConfig = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of("floor", WallMaterial.ACOUSTIC_FOAM), WallMaterial.CONCRETE,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(absorbentFloorConfig);
        ImpulseResponse absorbentFloorIr = simulator.generateImpulseResponse();
        double absorbentFloorEnergy = computeIrEnergy(absorbentFloorIr.samples()[0]);

        // The absorptive floor should produce less total IR energy than all-concrete
        assertThat(absorbentFloorEnergy)
                .as("Floor with ACOUSTIC_FOAM should reduce IR energy vs all-CONCRETE")
                .isLessThan(reflectiveEnergy);
    }

    @Test
    void shouldReconfigureSafely() {
        RoomSimulationConfig config1 = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(config1);
        simulator.generateImpulseResponse();

        RoomSimulationConfig config2 = new RoomSimulationConfig(
                new RoomDimensions(10, 12, 4), Map.of(), WallMaterial.CONCRETE,
                List.of(GUITAR), LISTENER, SAMPLE_RATE);
        simulator.configure(config2);

        ImpulseResponse ir = simulator.generateImpulseResponse();
        assertThat(ir).isNotNull();
        assertThat(simulator.getConfiguration()).isSameAs(config2);
    }

    @Test
    void shouldHandleMultipleSources() {
        SoundSource guitar = new SoundSource("Guitar", new Position3D(2, 2, 1), 85);
        SoundSource drums = new SoundSource("Drums", new Position3D(4, 3, 1), 90);
        SoundSource vocals = new SoundSource("Vocals", new Position3D(3, 1, 1.5), 80);

        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(guitar, drums, vocals), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        ImpulseResponse ir = simulator.generateImpulseResponse();
        assertThat(ir).isNotNull();
        assertThat(ir.lengthInSamples()).isGreaterThan(0);
    }

    @Test
    void shouldPrecomputeIrOnConfigure() {
        // After configure(), process() should work immediately without calling
        // generateImpulseResponse() — the IR is precomputed eagerly.
        SoundSource nearSource =
                new SoundSource("NearGuitar", new Position3D(3.0, 3.5, 1.2), 85);
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(nearSource), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        int numFrames = 512;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];
        input[0][0] = 1.0f;

        // This should not throw or need to regenerate internally
        simulator.process(input, output, numFrames);

        boolean hasNonZero = false;
        for (float sample : output[0]) {
            if (Math.abs(sample) > 1e-10f) {
                hasNonZero = true;
                break;
            }
        }
        assertThat(hasNonZero).isTrue();
    }

    @Test
    void shouldStreamConvolutionAcrossMultipleBlocks() {
        // Test that streaming convolution preserves the IR tail across blocks
        SoundSource nearSource =
                new SoundSource("NearGuitar", new Position3D(3.0, 3.5, 1.2), 85);
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(nearSource), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        // Process two consecutive blocks, with an impulse in the first
        int numFrames = 256;
        float[][] input1 = new float[1][numFrames];
        float[][] output1 = new float[1][numFrames];
        input1[0][0] = 1.0f;

        float[][] input2 = new float[1][numFrames];
        float[][] output2 = new float[1][numFrames];
        // Second block is silence

        simulator.process(input1, output1, numFrames);
        simulator.process(input2, output2, numFrames);

        // The reverb tail should spill into the second block (overlap-save)
        boolean secondBlockHasOutput = false;
        for (float sample : output2[0]) {
            if (Math.abs(sample) > 1e-10f) {
                secondBlockHasOutput = true;
                break;
            }
        }
        assertThat(secondBlockHasOutput)
                .as("Reverb tail should continue into the second block")
                .isTrue();
    }

    @Test
    void shouldIncludeAllSourcesInIr() {
        // Test that multiple sources produce more energy than a single source
        SoundSource guitar = new SoundSource("Guitar", new Position3D(2, 2, 1), 85);
        RoomSimulationConfig singleSourceConfig = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(guitar), LISTENER, SAMPLE_RATE);
        simulator.configure(singleSourceConfig);
        ImpulseResponse singleIr = simulator.generateImpulseResponse();

        SoundSource drums = new SoundSource("Drums", new Position3D(4, 3, 1), 90);
        SoundSource vocals = new SoundSource("Vocals", new Position3D(3, 1, 1.5), 80);
        RoomSimulationConfig multiSourceConfig = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(guitar, drums, vocals), LISTENER, SAMPLE_RATE);
        simulator.configure(multiSourceConfig);
        ImpulseResponse multiIr = simulator.generateImpulseResponse();

        // Multi-source IR should have more non-zero samples (more early reflections)
        int singleNonZero = countNonZeroSamples(singleIr.samples()[0]);
        int multiNonZero = countNonZeroSamples(multiIr.samples()[0]);
        assertThat(multiNonZero).isGreaterThan(singleNonZero);
    }

    private static int countNonZeroSamples(float[] data) {
        int count = 0;
        for (float v : data) {
            if (Math.abs(v) > 1e-7f) count++;
        }
        return count;
    }

    private static double computeIrEnergy(float[] data) {
        double energy = 0.0;
        for (float v : data) {
            energy += (double) v * v;
        }
        return energy;
    }
}
