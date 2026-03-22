package com.benesquivelmusic.daw.sdk.telemetry;

/**
 * Immutable 3D position in meters within a room.
 *
 * <p>The coordinate system places the origin at the floor-level corner
 * of the room: X = width, Y = length, Z = height (up).</p>
 *
 * @param x the X coordinate in meters
 * @param y the Y coordinate in meters
 * @param z the Z coordinate in meters
 */
public record Position3D(double x, double y, double z) {

    /**
     * Returns the Euclidean distance to another position.
     *
     * @param other the target position
     * @return the distance in meters
     */
    public double distanceTo(Position3D other) {
        double dx = other.x - x;
        double dy = other.y - y;
        double dz = other.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
