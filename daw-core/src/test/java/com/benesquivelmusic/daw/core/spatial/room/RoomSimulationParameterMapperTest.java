package com.benesquivelmusic.daw.core.spatial.room;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig;
import com.benesquivelmusic.daw.sdk.telemetry.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomSimulationParameterMapperTest {

    @Test
    void shouldMapBasicConfiguration() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        RoomConfiguration roomConfig = new RoomConfiguration(dims, WallMaterial.DRYWALL);
        roomConfig.addSoundSource(new SoundSource("Guitar", new Position3D(3, 2, 1), 85));

        RoomSimulationConfig simConfig = RoomSimulationParameterMapper.toSimulationConfig(roomConfig, 48000);

        assertThat(simConfig.dimensions()).isEqualTo(dims);
        assertThat(simConfig.defaultMaterial()).isEqualTo(WallMaterial.DRYWALL);
        assertThat(simConfig.sources()).hasSize(1);
        assertThat(simConfig.sampleRate()).isEqualTo(48000);
    }

    @Test
    void shouldPlaceListenerAtRoomCenter() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        RoomConfiguration roomConfig = new RoomConfiguration(dims, WallMaterial.DRYWALL);

        RoomSimulationConfig simConfig = RoomSimulationParameterMapper.toSimulationConfig(roomConfig);

        assertThat(simConfig.listener().position().x()).isEqualTo(5.0);
        assertThat(simConfig.listener().position().y()).isEqualTo(4.0);
        assertThat(simConfig.listener().position().z()).isEqualTo(1.2);
    }

    @Test
    void shouldUseDefaultSampleRate() {
        RoomConfiguration roomConfig = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);

        RoomSimulationConfig simConfig = RoomSimulationParameterMapper.toSimulationConfig(roomConfig);

        assertThat(simConfig.sampleRate()).isEqualTo(RoomSimulationParameterMapper.DEFAULT_SAMPLE_RATE);
    }

    @Test
    void shouldPreserveAllSoundSources() {
        RoomConfiguration roomConfig = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        roomConfig.addSoundSource(new SoundSource("Guitar", new Position3D(3, 2, 1), 85));
        roomConfig.addSoundSource(new SoundSource("Drums", new Position3D(5, 3, 1), 90));
        roomConfig.addSoundSource(new SoundSource("Vocals", new Position3D(4, 4, 1.5), 75));

        RoomSimulationConfig simConfig = RoomSimulationParameterMapper.toSimulationConfig(roomConfig);

        assertThat(simConfig.sources()).hasSize(3);
    }

    @Test
    void shouldApplyDefaultMaterialToAllSurfaces() {
        RoomConfiguration roomConfig = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.ACOUSTIC_FOAM);

        RoomSimulationConfig simConfig = RoomSimulationParameterMapper.toSimulationConfig(roomConfig);

        for (String surface : RoomSimulationConfig.SURFACE_NAMES) {
            assertThat(simConfig.materialForSurface(surface)).isEqualTo(WallMaterial.ACOUSTIC_FOAM);
        }
    }

    @Test
    void shouldAcceptCustomListenerOrientation() {
        RoomConfiguration roomConfig = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        ListenerOrientation customListener = new ListenerOrientation(new Position3D(2, 2, 1.5), 90.0, 10.0);

        RoomSimulationConfig simConfig = RoomSimulationParameterMapper.toSimulationConfig(
                roomConfig, customListener, 44100);

        assertThat(simConfig.listener()).isEqualTo(customListener);
        assertThat(simConfig.sampleRate()).isEqualTo(44100);
    }

    @Test
    void shouldRejectNullRoomConfig() {
        assertThatThrownBy(() -> RoomSimulationParameterMapper.toSimulationConfig(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        RoomConfiguration roomConfig = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);

        assertThatThrownBy(() -> RoomSimulationParameterMapper.toSimulationConfig(roomConfig, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
