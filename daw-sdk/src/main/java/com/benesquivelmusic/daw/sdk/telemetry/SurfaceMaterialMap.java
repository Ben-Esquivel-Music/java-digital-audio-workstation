package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;

/**
 * Per-surface assignment of {@link WallMaterial} for the six interior
 * surfaces of a rectangular room.
 *
 * <p>Real rooms rarely have a single material covering every surface — a
 * concert hall floor may be marble while the ceiling is absorbent
 * fiberglass; a church may have stone walls and a wood floor. The
 * {@code SurfaceMaterialMap} captures that per-surface variation so the
 * RT60 estimate and reflection energy calculations in
 * {@code SoundWaveTelemetryEngine} can use each surface's own absorption
 * coefficient.</p>
 *
 * <p>The {@link #SurfaceMaterialMap(WallMaterial)} broadcast constructor
 * assigns the same material to every surface — useful for backwards
 * compatibility and for legacy data that only carried a single material.</p>
 *
 * @param floor      material covering the floor (z = 0)
 * @param frontWall  material covering the wall at y = 0
 * @param backWall   material covering the wall at y = length
 * @param leftWall   material covering the wall at x = 0
 * @param rightWall  material covering the wall at x = width
 * @param ceiling    material covering the ceiling
 */
public record SurfaceMaterialMap(
        WallMaterial floor,
        WallMaterial frontWall,
        WallMaterial backWall,
        WallMaterial leftWall,
        WallMaterial rightWall,
        WallMaterial ceiling
) {

    public SurfaceMaterialMap {
        Objects.requireNonNull(floor, "floor must not be null");
        Objects.requireNonNull(frontWall, "frontWall must not be null");
        Objects.requireNonNull(backWall, "backWall must not be null");
        Objects.requireNonNull(leftWall, "leftWall must not be null");
        Objects.requireNonNull(rightWall, "rightWall must not be null");
        Objects.requireNonNull(ceiling, "ceiling must not be null");
    }

    /**
     * Broadcasts a single material to every surface.
     *
     * <p>Provided for backwards compatibility with legacy single-material
     * room configurations and projects.</p>
     *
     * @param uniform the material applied to every surface
     */
    public SurfaceMaterialMap(WallMaterial uniform) {
        this(uniform, uniform, uniform, uniform, uniform, uniform);
    }

    /**
     * Returns the material assigned to {@code surface}.
     *
     * @param surface the room surface to query
     * @return the wall material at that surface
     */
    public WallMaterial materialAt(RoomSurface surface) {
        Objects.requireNonNull(surface, "surface must not be null");
        return switch (surface) {
            case FLOOR -> floor;
            case FRONT_WALL -> frontWall;
            case BACK_WALL -> backWall;
            case LEFT_WALL -> leftWall;
            case RIGHT_WALL -> rightWall;
            case CEILING -> ceiling;
        };
    }

    /**
     * Returns a copy of this map with the material for {@code surface}
     * replaced by {@code material}. The original map is unchanged.
     *
     * @param surface  the surface to update
     * @param material the new material for that surface
     * @return a new {@code SurfaceMaterialMap} with the substitution applied
     */
    public SurfaceMaterialMap with(RoomSurface surface, WallMaterial material) {
        Objects.requireNonNull(surface, "surface must not be null");
        Objects.requireNonNull(material, "material must not be null");
        return switch (surface) {
            case FLOOR -> new SurfaceMaterialMap(material, frontWall, backWall, leftWall, rightWall, ceiling);
            case FRONT_WALL -> new SurfaceMaterialMap(floor, material, backWall, leftWall, rightWall, ceiling);
            case BACK_WALL -> new SurfaceMaterialMap(floor, frontWall, material, leftWall, rightWall, ceiling);
            case LEFT_WALL -> new SurfaceMaterialMap(floor, frontWall, backWall, material, rightWall, ceiling);
            case RIGHT_WALL -> new SurfaceMaterialMap(floor, frontWall, backWall, leftWall, material, ceiling);
            case CEILING -> new SurfaceMaterialMap(floor, frontWall, backWall, leftWall, rightWall, material);
        };
    }

    /**
     * Returns {@code true} when every surface has the same material — i.e.
     * the map was constructed via the broadcast constructor or otherwise
     * carries a single uniform material.
     */
    public boolean isUniform() {
        return floor == frontWall
                && floor == backWall
                && floor == leftWall
                && floor == rightWall
                && floor == ceiling;
    }

    /**
     * Returns the unweighted arithmetic mean absorption coefficient across
     * all six surfaces. Useful as a quick &quot;liveliness&quot; indicator
     * but not as accurate as the area-weighted mean used internally by
     * {@code SoundWaveTelemetryEngine} for RT60.
     */
    public double meanAbsorption() {
        return (floor.absorptionCoefficient()
                + frontWall.absorptionCoefficient()
                + backWall.absorptionCoefficient()
                + leftWall.absorptionCoefficient()
                + rightWall.absorptionCoefficient()
                + ceiling.absorptionCoefficient()) / 6.0;
    }
}
