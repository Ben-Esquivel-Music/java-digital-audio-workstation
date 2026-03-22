package com.benesquivelmusic.daw.sdk.spatial;

import com.benesquivelmusic.daw.sdk.telemetry.ListenerOrientation;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RoomSimulationConfigTest {

    private static final RoomDimensions DIMS = new RoomDimensions(10, 8, 3);
    private static final ListenerOrientation LISTENER =
            new ListenerOrientation(new Position3D(5, 4, 1.2), 0.0, 0.0);

    @Test
    void shouldCreateWithValidParameters() {
        var source = new SoundSource("Guitar", new Position3D(3, 2, 1), 85);
        var config = new RoomSimulationConfig(
                DIMS, Map.of("floor", WallMaterial.CARPET), WallMaterial.DRYWALL,
                List.of(source), LISTENER, 48000);

        assertThat(config.dimensions()).isEqualTo(DIMS);
        assertThat(config.defaultMaterial()).isEqualTo(WallMaterial.DRYWALL);
        assertThat(config.sources()).hasSize(1);
        assertThat(config.listener()).isEqualTo(LISTENER);
        assertThat(config.sampleRate()).isEqualTo(48000);
    }

    @Test
    void shouldReturnMaterialForMappedSurface() {
        var config = new RoomSimulationConfig(
                DIMS, Map.of("floor", WallMaterial.CARPET), WallMaterial.DRYWALL,
                List.of(), LISTENER, 48000);

        assertThat(config.materialForSurface("floor")).isEqualTo(WallMaterial.CARPET);
    }

    @Test
    void shouldFallBackToDefaultMaterialForUnmappedSurface() {
        var config = new RoomSimulationConfig(
                DIMS, Map.of("floor", WallMaterial.CARPET), WallMaterial.DRYWALL,
                List.of(), LISTENER, 48000);

        assertThat(config.materialForSurface("ceiling")).isEqualTo(WallMaterial.DRYWALL);
    }

    @Test
    void shouldComputeAverageAbsorption() {
        // All surfaces use the same material
        var config = new RoomSimulationConfig(
                DIMS, Map.of(), WallMaterial.ACOUSTIC_FOAM,
                List.of(), LISTENER, 48000);

        assertThat(config.averageAbsorption())
                .isCloseTo(WallMaterial.ACOUSTIC_FOAM.absorptionCoefficient(), within(1e-10));
    }

    @Test
    void shouldMakeSourcesImmutable() {
        var source = new SoundSource("Guitar", new Position3D(3, 2, 1), 85);
        var config = new RoomSimulationConfig(
                DIMS, Map.of(), WallMaterial.DRYWALL,
                List.of(source), LISTENER, 48000);

        assertThatThrownBy(() -> config.sources().add(
                new SoundSource("Drums", new Position3D(1, 1, 1), 90)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldMakeSurfaceMaterialsImmutable() {
        var config = new RoomSimulationConfig(
                DIMS, Map.of("floor", WallMaterial.CARPET), WallMaterial.DRYWALL,
                List.of(), LISTENER, 48000);

        assertThatThrownBy(() -> config.surfaceMaterials().put("ceiling", WallMaterial.GLASS))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullDimensions() {
        assertThatThrownBy(() -> new RoomSimulationConfig(
                null, Map.of(), WallMaterial.DRYWALL, List.of(), LISTENER, 48000))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullDefaultMaterial() {
        assertThatThrownBy(() -> new RoomSimulationConfig(
                DIMS, Map.of(), null, List.of(), LISTENER, 48000))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> new RoomSimulationConfig(
                DIMS, Map.of(), WallMaterial.DRYWALL, List.of(), LISTENER, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHaveSixSurfaceNames() {
        assertThat(RoomSimulationConfig.SURFACE_NAMES).hasSize(6);
        assertThat(RoomSimulationConfig.SURFACE_NAMES).contains(
                "floor", "ceiling", "leftWall", "rightWall", "frontWall", "backWall");
    }
}
