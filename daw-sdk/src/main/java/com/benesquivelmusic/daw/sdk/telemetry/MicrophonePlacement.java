package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;

/**
 * Immutable description of a microphone's placement in a room.
 *
 * <p>Captures the microphone name, its 3D position, and the direction
 * it is pointing (azimuth and elevation angles in degrees).</p>
 *
 * @param name      a descriptive label for this microphone
 * @param position  the 3D position of the microphone in the room
 * @param azimuth   horizontal aim angle in degrees (0 = along +Y, 90 = along +X)
 * @param elevation vertical aim angle in degrees (0 = horizontal, 90 = straight up)
 */
public record MicrophonePlacement(String name, Position3D position, double azimuth, double elevation) {

    public MicrophonePlacement {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(position, "position must not be null");
        if (azimuth < 0 || azimuth >= 360) {
            throw new IllegalArgumentException("azimuth must be in [0, 360): " + azimuth);
        }
        if (elevation < -90 || elevation > 90) {
            throw new IllegalArgumentException("elevation must be in [-90, 90]: " + elevation);
        }
    }
}
