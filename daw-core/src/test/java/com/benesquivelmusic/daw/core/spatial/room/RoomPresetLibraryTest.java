package com.benesquivelmusic.daw.core.spatial.room;

import com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig;
import com.benesquivelmusic.daw.sdk.telemetry.ListenerOrientation;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomPreset;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomPresetLibraryTest {

    @Test
    void shouldCreateConfigFromStudioPreset() {
        RoomSimulationConfig config = RoomPresetLibrary.fromPreset(RoomPreset.STUDIO, 48000);

        assertThat(config.dimensions()).isEqualTo(RoomPreset.STUDIO.dimensions());
        assertThat(config.sampleRate()).isEqualTo(48000);
        assertThat(config.sources()).isEmpty();
        assertThat(config.listener()).isNotNull();
    }

    @Test
    void shouldPlaceListenerAtRoomCenter() {
        RoomSimulationConfig config = RoomPresetLibrary.fromPreset(RoomPreset.STUDIO);

        ListenerOrientation listener = config.listener();
        RoomDimensions dims = config.dimensions();

        assertThat(listener.position().x()).isEqualTo(dims.width() / 2.0);
        assertThat(listener.position().y()).isEqualTo(dims.length() / 2.0);
        assertThat(listener.position().z()).isEqualTo(1.2); // ear height
        assertThat(listener.yawDegrees()).isEqualTo(0.0);   // facing forward
    }

    @Test
    void shouldCreateConfigWithSources() {
        SoundSource guitar = new SoundSource("Guitar", new Position3D(2, 2, 1), 85);
        SoundSource drums = new SoundSource("Drums", new Position3D(4, 3, 1), 90);

        RoomSimulationConfig config = RoomPresetLibrary.fromPreset(
                RoomPreset.STUDIO, List.of(guitar, drums), 48000);

        assertThat(config.sources()).hasSize(2);
    }

    @Test
    void shouldUseDefaultSampleRate() {
        RoomSimulationConfig config = RoomPresetLibrary.fromPreset(RoomPreset.STUDIO);

        assertThat(config.sampleRate()).isEqualTo(RoomPresetLibrary.DEFAULT_SAMPLE_RATE);
    }

    @Test
    void allPresetsShouldProduceValidConfigs() {
        for (RoomPreset preset : RoomPreset.values()) {
            RoomSimulationConfig config = RoomPresetLibrary.fromPreset(preset);
            assertThat(config.dimensions()).isNotNull();
            assertThat(config.listener()).isNotNull();
            assertThat(config.sampleRate()).isGreaterThan(0);

            // All six surfaces should have materials assigned
            for (String surface : RoomSimulationConfig.SURFACE_NAMES) {
                assertThat(config.materialForSurface(surface))
                        .as("material for %s in %s", surface, preset)
                        .isNotNull();
            }
        }
    }

    @Test
    void bathroomShouldUseReflectiveMaterials() {
        RoomSimulationConfig config = RoomPresetLibrary.fromPreset(RoomPreset.BATHROOM);

        // Average absorption for bathroom should be low (reflective)
        assertThat(config.averageAbsorption()).isLessThan(0.1);
    }

    @Test
    void recordingBoothShouldUseAbsorptiveMaterials() {
        RoomSimulationConfig config = RoomPresetLibrary.fromPreset(RoomPreset.RECORDING_BOOTH);

        // Average absorption for recording booth should be high (absorptive)
        assertThat(config.averageAbsorption()).isGreaterThan(0.3);
    }

    @Test
    void shouldRejectNullPreset() {
        assertThatThrownBy(() -> RoomPresetLibrary.fromPreset(null))
                .isInstanceOf(NullPointerException.class);
    }
}
