package com.benesquivelmusic.daw.core.spatial.room;

import com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig;
import com.benesquivelmusic.daw.sdk.telemetry.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Factory for creating {@link RoomSimulationConfig} instances from
 * {@link RoomPreset} enumerations.
 *
 * <p>Each preset provides sensible defaults for room dimensions and wall
 * materials. The factory also places a default listener at the room center
 * and allows specifying sound sources and sample rate.</p>
 */
public final class RoomPresetLibrary {

    private RoomPresetLibrary() {
        // utility class
    }

    /** Default audio sample rate in Hz. */
    public static final int DEFAULT_SAMPLE_RATE = 48000;

    /**
     * Creates a room simulation configuration from a preset with default
     * listener position (room center, facing forward) and no sources.
     *
     * @param preset     the room preset
     * @param sampleRate the audio sample rate in Hz
     * @return the simulation configuration
     */
    public static RoomSimulationConfig fromPreset(RoomPreset preset, int sampleRate) {
        return fromPreset(preset, List.of(), sampleRate);
    }

    /**
     * Creates a room simulation configuration from a preset with the
     * given sound sources and a default listener at room center.
     *
     * @param preset     the room preset
     * @param sources    the sound sources to place in the room
     * @param sampleRate the audio sample rate in Hz
     * @return the simulation configuration
     */
    public static RoomSimulationConfig fromPreset(RoomPreset preset, List<SoundSource> sources,
                                                   int sampleRate) {
        Objects.requireNonNull(preset, "preset must not be null");
        Objects.requireNonNull(sources, "sources must not be null");

        RoomDimensions dims = preset.dimensions();
        WallMaterial material = preset.wallMaterial();

        // Default listener at room center, facing forward (along +Y)
        ListenerOrientation listener = new ListenerOrientation(
                new Position3D(dims.width() / 2.0, dims.length() / 2.0, 1.2), // ~ear height
                0.0, 0.0);

        // Apply preset material uniformly to all surfaces, with special
        // treatment for floor and ceiling where applicable
        Map<String, WallMaterial> surfaceMaterials = buildSurfaceMaterials(preset);

        return new RoomSimulationConfig(dims, surfaceMaterials, material, sources, listener, sampleRate);
    }

    /**
     * Creates a room simulation configuration from a preset with default
     * sample rate (48 kHz).
     *
     * @param preset the room preset
     * @return the simulation configuration
     */
    public static RoomSimulationConfig fromPreset(RoomPreset preset) {
        return fromPreset(preset, DEFAULT_SAMPLE_RATE);
    }

    private static Map<String, WallMaterial> buildSurfaceMaterials(RoomPreset preset) {
        SurfaceMaterialMap m = preset.materialMap();
        return Map.of(
                "floor", m.floor(),
                "ceiling", m.ceiling(),
                "leftWall", m.leftWall(),
                "rightWall", m.rightWall(),
                "frontWall", m.frontWall(),
                "backWall", m.backWall());
    }
}
