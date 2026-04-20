package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;

/**
 * Location and mounting of an {@link AcousticTreatment} inside a room.
 *
 * <p>Two variants are supported:
 * <ul>
 *     <li>{@link OnSurface} — a panel centered at a point on one of the six
 *         {@link RoomSurface room surfaces}. The {@code u, v} coordinates
 *         are local 2D coordinates in meters on that surface: for wall
 *         surfaces {@code u} is the horizontal axis (along the wall) and
 *         {@code v} is the vertical axis; for the floor and ceiling
 *         {@code u} is the X axis of the room and {@code v} is the Y axis.</li>
 *     <li>{@link InCorner} — a corner-mounted trap spanning two adjacent
 *         surfaces (typically used for low-frequency pressure traps).</li>
 * </ul></p>
 */
public sealed interface WallAttachment permits WallAttachment.OnSurface, WallAttachment.InCorner {

    /**
     * Panel mounted flat on a single room surface, centered at local
     * coordinates {@code (u, v)} on that surface.
     *
     * @param surface the surface the treatment is attached to
     * @param u       local first-axis coordinate in meters
     * @param v       local second-axis coordinate in meters
     */
    record OnSurface(RoomSurface surface, double u, double v) implements WallAttachment {
        public OnSurface {
            Objects.requireNonNull(surface, "surface must not be null");
        }
    }

    /**
     * Corner-mounted trap, straddling two adjacent surfaces at a specified
     * height {@code z} above the floor. Used for low-frequency pressure
     * traps where two walls meet, or for wall-ceiling edge treatments.
     *
     * @param surfaceA the first surface of the corner
     * @param surfaceB the second surface of the corner (adjacent to A)
     * @param z        height in meters of the trap's centre above the floor
     */
    record InCorner(RoomSurface surfaceA, RoomSurface surfaceB, double z) implements WallAttachment {
        public InCorner {
            Objects.requireNonNull(surfaceA, "surfaceA must not be null");
            Objects.requireNonNull(surfaceB, "surfaceB must not be null");
            if (surfaceA == surfaceB) {
                throw new IllegalArgumentException(
                        "surfaceA and surfaceB must differ: " + surfaceA);
            }
        }
    }
}
