package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomConfigurationTest {

    @Test
    void shouldCreateWithDimensionsAndMaterial() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        RoomConfiguration config = new RoomConfiguration(dims, WallMaterial.DRYWALL);

        assertThat(config.getDimensions()).isEqualTo(dims);
        assertThat(config.getWallMaterial()).isEqualTo(WallMaterial.DRYWALL);
        assertThat(config.getMicrophones()).isEmpty();
        assertThat(config.getSoundSources()).isEmpty();
    }

    @Test
    void shouldAddAndRemoveMicrophones() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        MicrophonePlacement mic = new MicrophonePlacement("OH-L", new Position3D(3, 4, 2.5), 180, 0);

        config.addMicrophone(mic);
        assertThat(config.getMicrophones()).hasSize(1);
        assertThat(config.getMicrophones().get(0).name()).isEqualTo("OH-L");

        assertThat(config.removeMicrophone("OH-L")).isTrue();
        assertThat(config.getMicrophones()).isEmpty();
    }

    @Test
    void shouldAddAndRemoveSoundSources() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        SoundSource source = new SoundSource("Guitar", new Position3D(5, 2, 1), 85);

        config.addSoundSource(source);
        assertThat(config.getSoundSources()).hasSize(1);

        assertThat(config.removeSoundSource("Guitar")).isTrue();
        assertThat(config.getSoundSources()).isEmpty();
    }

    @Test
    void shouldReturnFalseWhenRemovingNonexistentMic() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);

        assertThat(config.removeMicrophone("nonexistent")).isFalse();
    }

    @Test
    void shouldUpdateDimensionsAndMaterial() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);

        RoomDimensions newDims = new RoomDimensions(12, 10, 4);
        config.setDimensions(newDims);
        config.setWallMaterial(WallMaterial.ACOUSTIC_FOAM);

        assertThat(config.getDimensions()).isEqualTo(newDims);
        assertThat(config.getWallMaterial()).isEqualTo(WallMaterial.ACOUSTIC_FOAM);
    }

    @Test
    void shouldRejectNullDimensions() {
        assertThatThrownBy(() -> new RoomConfiguration(null, WallMaterial.DRYWALL))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullWallMaterial() {
        assertThatThrownBy(() -> new RoomConfiguration(new RoomDimensions(10, 8, 3), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnUnmodifiableMicrophoneList() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);

        assertThatThrownBy(() -> config.getMicrophones().add(
                new MicrophonePlacement("Illegal", new Position3D(0, 0, 0), 0, 0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnUnmodifiableSourceList() {
        RoomConfiguration config = new RoomConfiguration(new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);

        assertThatThrownBy(() -> config.getSoundSources().add(
                new SoundSource("Illegal", new Position3D(0, 0, 0), 80)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
