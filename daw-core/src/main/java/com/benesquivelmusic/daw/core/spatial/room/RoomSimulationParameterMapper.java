package com.benesquivelmusic.daw.core.spatial.room;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.sdk.spatial.RoomSimulationConfig;
import com.benesquivelmusic.daw.sdk.telemetry.ListenerOrientation;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;

import java.util.Map;
import java.util.Objects;

/**
 * Maps between the existing {@link RoomConfiguration} telemetry type and
 * the new {@link RoomSimulationConfig} used by {@link com.benesquivelmusic.daw.sdk.spatial.RoomSimulator}.
 *
 * <p>This enables the room simulator to be driven from existing telemetry
 * data without requiring users to reconfigure their room setup.</p>
 */
public final class RoomSimulationParameterMapper {

    private RoomSimulationParameterMapper() {
        // utility class
    }

    /** Default audio sample rate in Hz when not specified. */
    public static final int DEFAULT_SAMPLE_RATE = 48000;

    /**
     * Converts a {@link RoomConfiguration} to a {@link RoomSimulationConfig}.
     *
     * <p>The existing {@code RoomConfiguration} uses a single wall material
     * for all surfaces. This mapper applies that material uniformly. The
     * listener is placed at the room center at ear height (1.2 m), facing
     * forward along the +Y axis.</p>
     *
     * @param roomConfig the existing room configuration
     * @param sampleRate the audio sample rate in Hz
     * @return the equivalent simulation configuration
     */
    public static RoomSimulationConfig toSimulationConfig(RoomConfiguration roomConfig,
                                                           int sampleRate) {
        Objects.requireNonNull(roomConfig, "roomConfig must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }

        RoomDimensions dims = roomConfig.getDimensions();

        // Default listener at room center, ear height, facing forward
        ListenerOrientation listener = new ListenerOrientation(
                new Position3D(dims.width() / 2.0, dims.length() / 2.0, 1.2),
                0.0, 0.0);

        return new RoomSimulationConfig(
                dims,
                Map.of(), // no per-surface overrides — use the default material
                roomConfig.getWallMaterial(),
                roomConfig.getSoundSources(),
                listener,
                sampleRate);
    }

    /**
     * Converts a {@link RoomConfiguration} to a {@link RoomSimulationConfig}
     * using the default sample rate (48 kHz).
     *
     * @param roomConfig the existing room configuration
     * @return the equivalent simulation configuration
     */
    public static RoomSimulationConfig toSimulationConfig(RoomConfiguration roomConfig) {
        return toSimulationConfig(roomConfig, DEFAULT_SAMPLE_RATE);
    }

    /**
     * Converts a {@link RoomConfiguration} to a {@link RoomSimulationConfig}
     * with a custom listener orientation.
     *
     * @param roomConfig the existing room configuration
     * @param listener   the listener orientation to use
     * @param sampleRate the audio sample rate in Hz
     * @return the equivalent simulation configuration
     */
    public static RoomSimulationConfig toSimulationConfig(RoomConfiguration roomConfig,
                                                           ListenerOrientation listener,
                                                           int sampleRate) {
        Objects.requireNonNull(roomConfig, "roomConfig must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }

        return new RoomSimulationConfig(
                roomConfig.getDimensions(),
                Map.of(),
                roomConfig.getWallMaterial(),
                roomConfig.getSoundSources(),
                listener,
                sampleRate);
    }
}
