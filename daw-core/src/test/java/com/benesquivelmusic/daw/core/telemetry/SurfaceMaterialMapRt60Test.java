package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomSurface;
import com.benesquivelmusic.daw.sdk.telemetry.SurfaceMaterialMap;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the per-surface material aware RT60 estimation in
 * {@link SoundWaveTelemetryEngine}.
 *
 * <p>Validates the two acceptance criteria from the per-surface material
 * map story:</p>
 * <ul>
 *   <li>RT60 differs correctly between an &quot;all absorbent&quot; map
 *       and an &quot;all reflective&quot; map.</li>
 *   <li>The broadcast {@link SurfaceMaterialMap} constructor produces
 *       bit-identical RT60 to the legacy single-{@link WallMaterial}
 *       Sabine implementation.</li>
 * </ul>
 */
class SurfaceMaterialMapRt60Test {

    private static final RoomDimensions ROOM = new RoomDimensions(6.0, 8.0, 3.0);

    @Test
    void rt60DiffersBetweenAbsorbentAndReflectiveMaps() {
        SurfaceMaterialMap absorbent = new SurfaceMaterialMap(WallMaterial.ACOUSTIC_FOAM);
        SurfaceMaterialMap reflective = new SurfaceMaterialMap(WallMaterial.MARBLE);

        double absorbentRt60 = SoundWaveTelemetryEngine.estimateRt60(ROOM, absorbent);
        double reflectiveRt60 = SoundWaveTelemetryEngine.estimateRt60(ROOM, reflective);

        // Marble (α≈0.01) is far more reflective than acoustic foam (α≈0.70):
        // RT60 in the reflective room must be much longer than in the
        // absorbent room.
        assertThat(reflectiveRt60).isGreaterThan(absorbentRt60 * 10.0);
    }

    @Test
    void broadcastConstructorIsBitIdenticalToLegacySingleMaterialSabine() {
        // Use a low-absorption material so the engine selects Sabine for
        // both code paths (the legacy code only ever used Sabine).
        WallMaterial material = WallMaterial.DRYWALL;

        double legacy = SoundWaveTelemetryEngine.estimateRt60(ROOM, material);
        double broadcast = SoundWaveTelemetryEngine.estimateRt60(
                ROOM, new SurfaceMaterialMap(material));

        // Strict equality — the per-surface code path must produce the
        // exact same double for a uniform broadcast.
        assertThat(broadcast).isEqualTo(legacy);
    }

    @Test
    void mixedAbsorbentCeilingShortensRt60ComparedToReflectiveBroadcast() {
        SurfaceMaterialMap reflective = new SurfaceMaterialMap(WallMaterial.MARBLE);
        // Concert-hall style: marble floor + walls, fiberglass-style
        // absorbent ceiling.
        SurfaceMaterialMap mixed = reflective.with(RoomSurface.CEILING, WallMaterial.ACOUSTIC_FOAM);

        double reflectiveRt60 = SoundWaveTelemetryEngine.estimateRt60(ROOM, reflective);
        double mixedRt60 = SoundWaveTelemetryEngine.estimateRt60(ROOM, mixed);

        assertThat(mixedRt60).isLessThan(reflectiveRt60);
    }

    @Test
    void surfaceAreasSumToTotalSurfaceArea() {
        double[] areas = SoundWaveTelemetryEngine.surfaceAreas(ROOM);
        double sum = 0.0;
        for (double area : areas) {
            sum += area;
        }
        // Bit-identical area decomposition is the foundation of the
        // bit-identical broadcast RT60 guarantee above.
        assertThat(sum).isEqualTo(ROOM.surfaceArea());
        assertThat(areas).hasSize(6);
    }
}
