package com.benesquivelmusic.daw.core.spatial.room;

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

class FdnRoomSimulatorTest {

    private static final int SAMPLE_RATE = 48000;
    private static final RoomDimensions STUDIO_DIMS = new RoomDimensions(6.0, 8.0, 3.0);
    private static final ListenerOrientation LISTENER =
            new ListenerOrientation(new Position3D(3.0, 4.0, 1.2), 0.0, 0.0);
    private static final SoundSource GUITAR =
            new SoundSource("Guitar", new Position3D(2.0, 2.0, 1.0), 85);

    private FdnRoomSimulator simulator;

    @BeforeEach
    void setUp() {
        simulator = new FdnRoomSimulator();
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

        ListenerOrientation newListener = new ListenerOrientation(new Position3D(4.0, 5.0, 1.2), 90.0, 0.0);
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

        assertThat(simulator.updateSourcePosition("Guitar", new Position3D(4, 3, 1))).isTrue();
        assertThat(simulator.updateSourcePosition("Nonexistent", new Position3D(0, 0, 0))).isFalse();
    }

    @Test
    void shouldPassThroughWhenNotConfigured() {
        float[][] input = {{0.5f, 0.3f, 0.1f}};
        float[][] output = {{0.0f, 0.0f, 0.0f}};

        simulator.process(input, output, 3);

        // Should pass through when not configured
        assertThat(output[0][0]).isEqualTo(0.5f);
        assertThat(output[0][1]).isEqualTo(0.3f);
        assertThat(output[0][2]).isEqualTo(0.1f);
    }

    @Test
    void shouldProcessAudioAfterConfiguration() {
        // Place source very close to listener so the direct sound arrives within a short buffer
        SoundSource nearSource = new SoundSource("NearGuitar", new Position3D(3.0, 3.5, 1.2), 85);
        RoomSimulationConfig config = new RoomSimulationConfig(
                STUDIO_DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(nearSource), LISTENER, SAMPLE_RATE);
        simulator.configure(config);

        // Use a large enough buffer to capture the direct sound arrival
        // Distance ~0.5m -> ~70 samples at 48 kHz
        int numFrames = 1024;
        float[][] input = new float[1][numFrames];
        float[][] output = new float[1][numFrames];

        // Create an impulse input
        input[0][0] = 1.0f;

        simulator.process(input, output, numFrames);

        // Output should not be all zeros (IR is applied)
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

        // After reset, generating IR again should work
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

        // Cathedral-like room should have longer reverb than a treated small room
        assertThat(largeIr.durationSeconds()).isGreaterThan(smallIr.durationSeconds());
    }

    @Test
    void shouldEstimateRt60Correctly() {
        // Known case: 10x10x10 room with absorption=0.161 gives RT60 ≈ 1.0 sec
        // RT60 = 0.161 * V / A = 0.161 * 1000 / (600 * 0.161) ≈ 1.665
        RoomDimensions dims = new RoomDimensions(10, 10, 10);
        double rt60 = FdnRoomSimulator.estimateRt60(dims, 0.161);
        assertThat(rt60).isCloseTo(0.161 * 1000.0 / (600.0 * 0.161), within(0.01));
    }
}
