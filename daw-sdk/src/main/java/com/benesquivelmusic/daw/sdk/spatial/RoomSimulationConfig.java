package com.benesquivelmusic.daw.sdk.spatial;

import com.benesquivelmusic.daw.sdk.telemetry.ListenerOrientation;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for a room acoustic simulation.
 *
 * <p>Aggregates the room geometry, per-surface wall materials, sound source
 * placements, listener orientation, and audio sample rate. This record
 * serves as the input to {@link RoomSimulator#configure(RoomSimulationConfig)}.</p>
 *
 * <p>Surface materials are keyed by surface name:
 * {@code "floor"}, {@code "ceiling"}, {@code "leftWall"}, {@code "rightWall"},
 * {@code "frontWall"}, {@code "backWall"}. If a surface is not specified
 * in the map, implementations should fall back to
 * {@link #defaultMaterial()}.</p>
 *
 * @param dimensions      the rectangular room dimensions
 * @param surfaceMaterials per-surface absorption materials (may be empty)
 * @param defaultMaterial  the fallback material for surfaces not in the map
 * @param sources          the sound sources placed in the room
 * @param listener         the listener position and orientation
 * @param sampleRate       the audio sample rate in Hz
 */
public record RoomSimulationConfig(
        RoomDimensions dimensions,
        Map<String, WallMaterial> surfaceMaterials,
        WallMaterial defaultMaterial,
        List<SoundSource> sources,
        ListenerOrientation listener,
        int sampleRate
) {

    /** The six standard rectangular room surface names. */
    public static final List<String> SURFACE_NAMES = List.of(
            "floor", "ceiling", "leftWall", "rightWall", "frontWall", "backWall");

    public RoomSimulationConfig {
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        Objects.requireNonNull(surfaceMaterials, "surfaceMaterials must not be null");
        Objects.requireNonNull(defaultMaterial, "defaultMaterial must not be null");
        Objects.requireNonNull(sources, "sources must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        surfaceMaterials = Map.copyOf(surfaceMaterials);
        sources = List.copyOf(sources);
    }

    /**
     * Returns the wall material for a given surface, falling back to
     * {@link #defaultMaterial()} if the surface is not explicitly mapped.
     *
     * @param surfaceName the surface name (e.g., "floor", "ceiling")
     * @return the wall material for the surface
     */
    public WallMaterial materialForSurface(String surfaceName) {
        return surfaceMaterials.getOrDefault(surfaceName, defaultMaterial);
    }

    /**
     * Computes the average absorption coefficient across all six room surfaces.
     *
     * @return the average absorption coefficient in [0.0, 1.0]
     */
    public double averageAbsorption() {
        double sum = 0.0;
        for (String surface : SURFACE_NAMES) {
            sum += materialForSurface(surface).absorptionCoefficient();
        }
        return sum / SURFACE_NAMES.size();
    }
}
