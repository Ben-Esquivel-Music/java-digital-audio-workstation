package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.AudienceMember;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomTelemetryData;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SoundWavePath;
import com.benesquivelmusic.daw.sdk.telemetry.TelemetrySuggestion;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class SoundWaveTelemetryEngineTest {

    @Test
    void shouldComputeDirectPath() {
        SoundSource source = new SoundSource("Guitar", new Position3D(2, 2, 1), 85);
        MicrophonePlacement mic = new MicrophonePlacement("Mic1", new Position3D(5, 6, 1), 0, 0);

        SoundWavePath path = SoundWaveTelemetryEngine.computeDirectPath(source, mic);

        assertThat(path.sourceName()).isEqualTo("Guitar");
        assertThat(path.microphoneName()).isEqualTo("Mic1");
        assertThat(path.reflected()).isFalse();
        assertThat(path.totalDistance()).isCloseTo(5.0, offset(0.01));
        assertThat(path.delayMs()).isCloseTo(
                5.0 / SoundWaveTelemetryEngine.SPEED_OF_SOUND_MPS * 1000, offset(0.1));
        assertThat(path.waypoints()).hasSize(2);
    }

    @Test
    void shouldComputeFirstOrderReflections() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        SoundSource source = new SoundSource("Guitar", new Position3D(3, 3, 1), 85);
        MicrophonePlacement mic = new MicrophonePlacement("Mic1", new Position3D(7, 5, 1.5), 0, 0);

        List<SoundWavePath> reflections = SoundWaveTelemetryEngine.computeFirstOrderReflections(
                source, mic, dims, WallMaterial.DRYWALL);

        // 6 reflections: left, right, front, back, floor, ceiling
        assertThat(reflections).hasSize(6);
        for (SoundWavePath path : reflections) {
            assertThat(path.reflected()).isTrue();
            assertThat(path.waypoints()).hasSize(3);
            assertThat(path.totalDistance()).isGreaterThan(0);
            assertThat(path.delayMs()).isGreaterThan(0);
        }
    }

    @Test
    void reflectedPathsShouldBeLongerThanDirectPath() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        SoundSource source = new SoundSource("Vocals", new Position3D(5, 4, 1.5), 75);
        MicrophonePlacement mic = new MicrophonePlacement("Condenser", new Position3D(5.5, 4.5, 1.5), 0, 0);

        SoundWavePath direct = SoundWaveTelemetryEngine.computeDirectPath(source, mic);
        List<SoundWavePath> reflections = SoundWaveTelemetryEngine.computeFirstOrderReflections(
                source, mic, dims, WallMaterial.DRYWALL);

        for (SoundWavePath reflected : reflections) {
            assertThat(reflected.totalDistance())
                    .as("reflected path should be longer than direct")
                    .isGreaterThan(direct.totalDistance());
        }
    }

    @Test
    void shouldEstimateRt60() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);

        double rt60Concrete = SoundWaveTelemetryEngine.estimateRt60(dims, WallMaterial.CONCRETE);
        double rt60Foam = SoundWaveTelemetryEngine.estimateRt60(dims, WallMaterial.ACOUSTIC_FOAM);

        // Concrete is highly reflective → longer RT60
        assertThat(rt60Concrete).isGreaterThan(rt60Foam);
        // Both should be positive
        assertThat(rt60Concrete).isGreaterThan(0);
        assertThat(rt60Foam).isGreaterThan(0);
    }

    @Test
    void shouldComputeFullTelemetry() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        config.addSoundSource(new SoundSource("Guitar", new Position3D(3, 2, 1), 85));
        config.addMicrophone(new MicrophonePlacement("Mic1", new Position3D(5, 4, 1.5), 0, 0));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.roomDimensions().width()).isEqualTo(10);
        // 1 direct + 6 reflections
        assertThat(data.wavePaths()).hasSize(7);
        assertThat(data.estimatedRt60Seconds()).isGreaterThan(0);
    }

    @Test
    void shouldComputePathsForMultipleSourceMicPairs() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        config.addSoundSource(new SoundSource("Guitar", new Position3D(3, 2, 1), 85));
        config.addSoundSource(new SoundSource("Vocals", new Position3D(5, 2, 1.5), 75));
        config.addMicrophone(new MicrophonePlacement("Mic1", new Position3D(4, 5, 1.5), 0, 0));
        config.addMicrophone(new MicrophonePlacement("Mic2", new Position3D(6, 5, 1.5), 0, 0));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        // 2 sources × 2 mics × (1 direct + 6 reflections) = 28
        assertThat(data.wavePaths()).hasSize(28);
    }

    @Test
    void shouldSuggestDampeningForReflectiveRoom() {
        // Concrete room with large volume → high RT60
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(15, 12, 5), WallMaterial.CONCRETE);
        config.addSoundSource(new SoundSource("Src", new Position3D(7, 6, 1), 80));
        config.addMicrophone(new MicrophonePlacement("Mic", new Position3D(8, 6, 1.5), 0, 0));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.suggestions()).anySatisfy(s ->
                assertThat(s).isInstanceOf(TelemetrySuggestion.AddDampening.class));
    }

    @Test
    void shouldSuggestRemovingDampeningForOverlyDeadRoom() {
        // Small room with highly absorptive material → low RT60
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(2, 2, 2), WallMaterial.ACOUSTIC_FOAM);
        config.addSoundSource(new SoundSource("Src", new Position3D(1, 1, 1), 80));
        config.addMicrophone(new MicrophonePlacement("Mic", new Position3D(1.5, 1, 1), 0, 0));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.suggestions()).anySatisfy(s ->
                assertThat(s).isInstanceOf(TelemetrySuggestion.RemoveDampening.class));
    }

    @Test
    void shouldSuggestMicAngleAdjustment() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        // Source is in front of the mic (+Y direction, azimuth ~ 0)
        config.addSoundSource(new SoundSource("Vocalist", new Position3D(5, 6, 1.5), 75));
        // Mic is aimed in the opposite direction (azimuth 180)
        config.addMicrophone(new MicrophonePlacement("Condenser", new Position3D(5, 4, 1.5), 180, 0));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.suggestions()).anySatisfy(s ->
                assertThat(s).isInstanceOf(TelemetrySuggestion.AdjustMicAngle.class));
    }

    @Test
    void shouldSuggestMovingMicAwayFromWall() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        config.addSoundSource(new SoundSource("Src", new Position3D(5, 4, 1), 80));
        // Mic is very close to the left wall (x = 0.1)
        config.addMicrophone(new MicrophonePlacement("WallMic", new Position3D(0.1, 4, 1.5), 0, 0));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.suggestions()).anySatisfy(s ->
                assertThat(s).isInstanceOf(TelemetrySuggestion.AdjustMicPosition.class));
    }

    @Test
    void shouldProduceNoPathsWithEmptyConfig() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.wavePaths()).isEmpty();
    }

    @Test
    void shouldRejectNullConfig() {
        assertThatThrownBy(() -> SoundWaveTelemetryEngine.compute(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldPassAudienceMembersThroughToTelemetryData() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        config.addSoundSource(new SoundSource("Guitar", new Position3D(3, 2, 1), 85));
        config.addMicrophone(new MicrophonePlacement("Mic1", new Position3D(5, 4, 1.5), 0, 0));
        config.addAudienceMember(new AudienceMember("Row 1 Seat 1", new Position3D(2, 6, 0)));
        config.addAudienceMember(new AudienceMember("Row 1 Seat 2", new Position3D(3, 6, 0)));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.audienceMembers()).hasSize(2);
        assertThat(data.audienceMembers().get(0).name()).isEqualTo("Row 1 Seat 1");
        assertThat(data.audienceMembers().get(1).name()).isEqualTo("Row 1 Seat 2");
    }

    @Test
    void shouldReturnEmptyAudienceMembersWhenNoneConfigured() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        config.addSoundSource(new SoundSource("Guitar", new Position3D(3, 2, 1), 85));
        config.addMicrophone(new MicrophonePlacement("Mic1", new Position3D(5, 4, 1.5), 0, 0));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.audienceMembers()).isEmpty();
    }

    @Test
    void shouldComputeAzimuthTowardPositiveY() {
        Position3D from = new Position3D(5, 5, 1);
        Position3D to = new Position3D(5, 10, 1); // straight +Y

        double azimuth = SoundWaveTelemetryEngine.computeAzimuthToward(from, to);

        assertThat(azimuth).isCloseTo(0.0, offset(0.01));
    }

    @Test
    void shouldComputeAzimuthTowardPositiveX() {
        Position3D from = new Position3D(5, 5, 1);
        Position3D to = new Position3D(10, 5, 1); // straight +X

        double azimuth = SoundWaveTelemetryEngine.computeAzimuthToward(from, to);

        assertThat(azimuth).isCloseTo(90.0, offset(0.01));
    }

    @Test
    void shouldComputeElevationForSameHeight() {
        Position3D from = new Position3D(0, 0, 1);
        Position3D to = new Position3D(5, 0, 1);

        double elevation = SoundWaveTelemetryEngine.computeElevationToward(from, to);

        assertThat(elevation).isCloseTo(0.0, offset(0.01));
    }

    @Test
    void shouldComputeElevationForAbove() {
        Position3D from = new Position3D(0, 0, 0);
        Position3D to = new Position3D(0, 0, 5); // straight up

        double elevation = SoundWaveTelemetryEngine.computeElevationToward(from, to);

        assertThat(elevation).isCloseTo(90.0, offset(0.01));
    }

    @Test
    void shouldPassSoundSourcesThroughToTelemetryData() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        config.addSoundSource(new SoundSource("Guitar", new Position3D(3, 2, 1), 85));
        config.addSoundSource(new SoundSource("Vocals", new Position3D(5, 2, 1.5), 75));
        config.addMicrophone(new MicrophonePlacement("Mic1", new Position3D(5, 4, 1.5), 0, 0));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.soundSources()).hasSize(2);
        assertThat(data.soundSources().get(0).name()).isEqualTo("Guitar");
        assertThat(data.soundSources().get(1).name()).isEqualTo("Vocals");
    }

    @Test
    void shouldPassMicrophonesThroughToTelemetryData() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        config.addSoundSource(new SoundSource("Guitar", new Position3D(3, 2, 1), 85));
        config.addMicrophone(new MicrophonePlacement("Mic1", new Position3D(5, 4, 1.5), 45, 10));
        config.addMicrophone(new MicrophonePlacement("Mic2", new Position3D(6, 4, 1.5), 90, 0));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.microphones()).hasSize(2);
        assertThat(data.microphones().get(0).name()).isEqualTo("Mic1");
        assertThat(data.microphones().get(1).name()).isEqualTo("Mic2");
    }

    @Test
    void shouldPassWallMaterialThroughToTelemetryData() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.CONCRETE);
        config.addSoundSource(new SoundSource("Src", new Position3D(5, 4, 1), 80));
        config.addMicrophone(new MicrophonePlacement("Mic", new Position3D(6, 4, 1.5), 0, 0));

        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        assertThat(data.wallMaterial()).isEqualTo(WallMaterial.CONCRETE);
    }
}
