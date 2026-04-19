package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;

/**
 * Immutable 3D room dimensions in meters.
 *
 * <p>Defines the rectangular floor plan (width × length) of a recording
 * space and the shape of its ceiling. The origin (0, 0, 0) is at the
 * floor-level corner; width extends along X, length along Y, and height
 * along Z.</p>
 *
 * <p>The canonical constructor takes an explicit {@link CeilingShape}.
 * For backward compatibility, a convenience constructor accepts a plain
 * {@code height} and materializes a {@link CeilingShape.Flat} internally.
 * {@link #height()} returns the maximum ceiling height so legacy callers
 * that expect a scalar "room height" keep working.</p>
 *
 * @param width   room width in meters (X axis)
 * @param length  room length in meters (Y axis)
 * @param ceiling the ceiling shape (floor plan is always rectangular)
 */
public record RoomDimensions(double width, double length, CeilingShape ceiling) {

    public RoomDimensions {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        Objects.requireNonNull(ceiling, "ceiling must not be null");
    }

    /**
     * Legacy convenience constructor that models a flat ceiling at the
     * given height. Equivalent to
     * {@code new RoomDimensions(width, length, new CeilingShape.Flat(height))}.
     */
    public RoomDimensions(double width, double length, double height) {
        this(width, length, new CeilingShape.Flat(height));
    }

    /**
     * Returns the maximum ceiling height anywhere in the room, in meters.
     * For a {@link CeilingShape.Flat Flat} ceiling this equals the
     * classic room height; for curved or sloped ceilings it is the apex.
     */
    public double height() {
        return ceiling.maxHeight();
    }

    /**
     * Returns the volume of the room in cubic meters, integrated over the
     * actual ceiling shape.
     */
    public double volume() {
        return ceiling.volume(width, length);
    }

    /**
     * Returns the total interior surface area in square meters (floor +
     * ceiling + four walls), integrated over the actual ceiling shape.
     */
    public double surfaceArea() {
        double floor = width * length;
        return floor + ceiling.ceilingArea(width, length) + ceiling.wallArea(width, length);
    }
}
