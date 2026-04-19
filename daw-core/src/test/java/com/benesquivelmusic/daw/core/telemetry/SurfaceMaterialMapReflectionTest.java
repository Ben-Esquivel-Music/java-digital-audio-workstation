package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomSurface;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SoundWavePath;
import com.benesquivelmusic.daw.sdk.telemetry.SurfaceMaterialMap;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link SoundWaveTelemetryEngine} tags each first-order
 * reflection with the {@link RoomSurface} it originated from, and that
 * per-surface absorption is applied when computing the reflection's
 * attenuation.
 */
class SurfaceMaterialMapReflectionTest {

    private static final RoomDimensions ROOM = new RoomDimensions(10, 8, 3);
    private static final SoundSource SOURCE =
            new SoundSource("Guitar", new Position3D(3, 3, 1), 85);
    private static final MicrophonePlacement MIC =
            new MicrophonePlacement("Mic1", new Position3D(7, 5, 1.5), 0, 0);

    @Test
    void everyFirstOrderReflectionCarriesItsOriginatingSurface() {
        List<SoundWavePath> reflections = SoundWaveTelemetryEngine.computeFirstOrderReflections(
                SOURCE, MIC, ROOM, new SurfaceMaterialMap(WallMaterial.DRYWALL));

        Set<RoomSurface> seen = EnumSet.noneOf(RoomSurface.class);
        for (SoundWavePath path : reflections) {
            assertThat(path.reflected()).isTrue();
            assertThat(path.reflectingSurface())
                    .as("reflectingSurface for path %s", path)
                    .isNotNull();
            seen.add(path.reflectingSurface());
        }
        // All six surfaces of the rectangular room must produce a reflection.
        assertThat(seen).containsExactlyInAnyOrder(RoomSurface.values());
    }

    @Test
    void perSurfaceAbsorptionAffectsReflectionAttenuation() {
        // Identical map except for the ceiling absorption.
        SurfaceMaterialMap reflective = new SurfaceMaterialMap(WallMaterial.MARBLE);
        SurfaceMaterialMap absorbentCeiling = reflective.with(
                RoomSurface.CEILING, WallMaterial.ACOUSTIC_FOAM);

        List<SoundWavePath> reflectivePaths = SoundWaveTelemetryEngine.computeFirstOrderReflections(
                SOURCE, MIC, ROOM, reflective);
        List<SoundWavePath> mixedPaths = SoundWaveTelemetryEngine.computeFirstOrderReflections(
                SOURCE, MIC, ROOM, absorbentCeiling);

        double reflectiveCeiling = ceilingAttenuation(reflectivePaths);
        double absorbentCeilingAtt = ceilingAttenuation(mixedPaths);

        // More absorbent ceiling -> stronger negative attenuation (more loss)
        assertThat(absorbentCeilingAtt).isLessThan(reflectiveCeiling);

        // Floor reflection attenuation must be unaffected by changing
        // only the ceiling material.
        assertThat(floorAttenuation(mixedPaths))
                .isEqualTo(floorAttenuation(reflectivePaths));
    }

    private static double ceilingAttenuation(List<SoundWavePath> paths) {
        return paths.stream()
                .filter(p -> p.reflectingSurface() == RoomSurface.CEILING)
                .findFirst()
                .orElseThrow()
                .attenuationDb();
    }

    private static double floorAttenuation(List<SoundWavePath> paths) {
        return paths.stream()
                .filter(p -> p.reflectingSurface() == RoomSurface.FLOOR)
                .findFirst()
                .orElseThrow()
                .attenuationDb();
    }
}
