package com.benesquivelmusic.daw.sdk.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SurfaceMaterialMapTest {

    @Test
    void broadcastConstructorAssignsSameMaterialToEverySurface() {
        SurfaceMaterialMap map = new SurfaceMaterialMap(WallMaterial.DRYWALL);

        assertThat(map.floor()).isEqualTo(WallMaterial.DRYWALL);
        assertThat(map.frontWall()).isEqualTo(WallMaterial.DRYWALL);
        assertThat(map.backWall()).isEqualTo(WallMaterial.DRYWALL);
        assertThat(map.leftWall()).isEqualTo(WallMaterial.DRYWALL);
        assertThat(map.rightWall()).isEqualTo(WallMaterial.DRYWALL);
        assertThat(map.ceiling()).isEqualTo(WallMaterial.DRYWALL);
        assertThat(map.isUniform()).isTrue();
    }

    @Test
    void perSurfaceConstructorPreservesEveryMaterial() {
        SurfaceMaterialMap map = new SurfaceMaterialMap(
                WallMaterial.MARBLE, WallMaterial.WOOD, WallMaterial.CURTAINS,
                WallMaterial.GLASS, WallMaterial.DRYWALL, WallMaterial.ACOUSTIC_TILE);

        assertThat(map.materialAt(RoomSurface.FLOOR)).isEqualTo(WallMaterial.MARBLE);
        assertThat(map.materialAt(RoomSurface.FRONT_WALL)).isEqualTo(WallMaterial.WOOD);
        assertThat(map.materialAt(RoomSurface.BACK_WALL)).isEqualTo(WallMaterial.CURTAINS);
        assertThat(map.materialAt(RoomSurface.LEFT_WALL)).isEqualTo(WallMaterial.GLASS);
        assertThat(map.materialAt(RoomSurface.RIGHT_WALL)).isEqualTo(WallMaterial.DRYWALL);
        assertThat(map.materialAt(RoomSurface.CEILING)).isEqualTo(WallMaterial.ACOUSTIC_TILE);
        assertThat(map.isUniform()).isFalse();
    }

    @Test
    void withReplacesOnlyTheTargetSurface() {
        SurfaceMaterialMap original = new SurfaceMaterialMap(WallMaterial.CONCRETE);
        SurfaceMaterialMap updated = original.with(RoomSurface.CEILING, WallMaterial.ACOUSTIC_FOAM);

        assertThat(updated.ceiling()).isEqualTo(WallMaterial.ACOUSTIC_FOAM);
        assertThat(updated.floor()).isEqualTo(WallMaterial.CONCRETE);
        assertThat(original.ceiling())
                .as("original is unchanged")
                .isEqualTo(WallMaterial.CONCRETE);
    }

    @Test
    void shouldRejectNullMaterials() {
        assertThatThrownBy(() -> new SurfaceMaterialMap(
                null, WallMaterial.WOOD, WallMaterial.WOOD,
                WallMaterial.WOOD, WallMaterial.WOOD, WallMaterial.WOOD))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void meanAbsorptionAveragesAllSixSurfaces() {
        SurfaceMaterialMap map = new SurfaceMaterialMap(WallMaterial.DRYWALL);
        assertThat(map.meanAbsorption())
                .isCloseTo(WallMaterial.DRYWALL.absorptionCoefficient(), org.assertj.core.data.Offset.offset(1e-12));
    }
}
