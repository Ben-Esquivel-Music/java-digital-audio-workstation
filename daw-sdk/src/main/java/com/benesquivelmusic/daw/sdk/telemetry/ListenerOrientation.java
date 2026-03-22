package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;

/**
 * Immutable description of a listener's position and facing direction in a room.
 *
 * <p>Captures the 3D position and the head orientation (yaw and pitch) of
 * the listener. This is used by the room acoustic simulator to compute
 * direction-dependent impulse responses and binaural rendering.</p>
 *
 * <p>The coordinate system matches {@link Position3D}: X = width, Y = length,
 * Z = height (up), with the origin at the floor-level corner of the room.</p>
 *
 * @param position     the 3D position of the listener in the room
 * @param yawDegrees   horizontal facing angle in degrees (0 = along +Y, 90 = along +X)
 * @param pitchDegrees vertical facing angle in degrees (0 = horizontal, +90 = up, −90 = down)
 */
public record ListenerOrientation(Position3D position, double yawDegrees, double pitchDegrees) {

    public ListenerOrientation {
        Objects.requireNonNull(position, "position must not be null");
        if (yawDegrees < 0 || yawDegrees >= 360) {
            throw new IllegalArgumentException("yawDegrees must be in [0, 360): " + yawDegrees);
        }
        if (pitchDegrees < -90 || pitchDegrees > 90) {
            throw new IllegalArgumentException("pitchDegrees must be in [-90, 90]: " + pitchDegrees);
        }
    }
}
