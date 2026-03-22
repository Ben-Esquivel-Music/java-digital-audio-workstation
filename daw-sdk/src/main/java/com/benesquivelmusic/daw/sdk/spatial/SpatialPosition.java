package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Immutable 3D spatial position supporting both spherical and Cartesian representations.
 *
 * <p>Uses the SOFA convention for spherical coordinates: azimuth is measured
 * counter-clockwise from the front (0° = front, 90° = left, 180° = back,
 * 270° = right); elevation is measured from the horizontal plane
 * (0° = horizontal, +90° = above, −90° = below).</p>
 *
 * <p>Cartesian coordinates use a right-hand system where X = right,
 * Y = front, Z = up, with the listener at the origin.</p>
 *
 * @param azimuthDegrees   horizontal angle in degrees [0, 360)
 * @param elevationDegrees vertical angle in degrees [−90, +90]
 * @param distanceMeters   radial distance in meters (non-negative)
 */
public record SpatialPosition(double azimuthDegrees, double elevationDegrees,
                               double distanceMeters) {

    public SpatialPosition {
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("distanceMeters must be non-negative: " + distanceMeters);
        }
        if (elevationDegrees < -90.0 || elevationDegrees > 90.0) {
            throw new IllegalArgumentException(
                    "elevationDegrees must be in [−90, +90]: " + elevationDegrees);
        }
    }

    /**
     * Creates a {@code SpatialPosition} from Cartesian coordinates.
     *
     * @param x right axis in meters
     * @param y front axis in meters
     * @param z up axis in meters
     * @return the equivalent spherical position
     */
    public static SpatialPosition fromCartesian(double x, double y, double z) {
        double distance = Math.sqrt(x * x + y * y + z * z);
        if (distance < 1e-15) {
            return new SpatialPosition(0, 0, 0);
        }
        double elevation = Math.toDegrees(Math.asin(clamp(z / distance, -1.0, 1.0)));
        double azimuth = Math.toDegrees(Math.atan2(-x, y));
        if (azimuth < 0) {
            azimuth += 360.0;
        }
        return new SpatialPosition(azimuth, elevation, distance);
    }

    /**
     * Returns the Cartesian X coordinate (right axis).
     *
     * @return the X position in meters
     */
    public double x() {
        double azRad = Math.toRadians(azimuthDegrees);
        double elRad = Math.toRadians(elevationDegrees);
        return -distanceMeters * Math.cos(elRad) * Math.sin(azRad);
    }

    /**
     * Returns the Cartesian Y coordinate (front axis).
     *
     * @return the Y position in meters
     */
    public double y() {
        double azRad = Math.toRadians(azimuthDegrees);
        double elRad = Math.toRadians(elevationDegrees);
        return distanceMeters * Math.cos(elRad) * Math.cos(azRad);
    }

    /**
     * Returns the Cartesian Z coordinate (up axis).
     *
     * @return the Z position in meters
     */
    public double z() {
        double elRad = Math.toRadians(elevationDegrees);
        return distanceMeters * Math.sin(elRad);
    }

    /**
     * Returns a new position with azimuth normalized to [0, 360).
     *
     * @return the normalized position
     */
    public SpatialPosition normalize() {
        double az = azimuthDegrees % 360.0;
        if (az < 0) {
            az += 360.0;
        }
        return new SpatialPosition(az, elevationDegrees, distanceMeters);
    }

    /**
     * Returns the angular distance (great-circle angle) to another position
     * on the unit sphere, ignoring radial distance.
     *
     * @param other the target position
     * @return the angular distance in degrees [0, 180]
     */
    public double angularDistanceTo(SpatialPosition other) {
        double az1 = Math.toRadians(azimuthDegrees);
        double el1 = Math.toRadians(elevationDegrees);
        double az2 = Math.toRadians(other.azimuthDegrees);
        double el2 = Math.toRadians(other.elevationDegrees);
        double cosAngle = Math.sin(el1) * Math.sin(el2)
                + Math.cos(el1) * Math.cos(el2) * Math.cos(az2 - az1);
        return Math.toDegrees(Math.acos(clamp(cosAngle, -1.0, 1.0)));
    }

    /**
     * Converts this position to a {@link SphericalCoordinate}.
     *
     * @return the equivalent spherical coordinate
     */
    public SphericalCoordinate toSphericalCoordinate() {
        return new SphericalCoordinate(azimuthDegrees, elevationDegrees, distanceMeters);
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
