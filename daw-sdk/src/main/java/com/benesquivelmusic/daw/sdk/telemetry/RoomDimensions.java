package com.benesquivelmusic.daw.sdk.telemetry;

/**
 * Immutable 3D room dimensions in meters.
 *
 * <p>Defines the rectangular volume of a recording space. The origin
 * (0, 0, 0) is at the floor-level corner; width extends along X,
 * length along Y, and height along Z.</p>
 *
 * @param width  room width in meters (X axis)
 * @param length room length in meters (Y axis)
 * @param height room height in meters (Z axis)
 */
public record RoomDimensions(double width, double length, double height) {

    public RoomDimensions {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive: " + height);
        }
    }

    /**
     * Returns the volume of the room in cubic meters.
     *
     * @return the room volume
     */
    public double volume() {
        return width * length * height;
    }

    /**
     * Returns the total wall surface area in square meters (floor + ceiling + four walls).
     *
     * @return the total surface area
     */
    public double surfaceArea() {
        return 2.0 * (width * length + width * height + length * height);
    }
}
