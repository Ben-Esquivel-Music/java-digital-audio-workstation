package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Immutable spherical coordinate representing a direction and distance
 * relative to the listener's head center.
 *
 * <p>Uses the SOFA convention: azimuth is measured counter-clockwise
 * from the front (0° = front, 90° = left, 180° = back, 270° = right);
 * elevation is measured from the horizontal plane (0° = horizontal,
 * +90° = above, −90° = below).</p>
 *
 * @param azimuthDegrees   horizontal angle in degrees [0, 360)
 * @param elevationDegrees vertical angle in degrees [−90, +90]
 * @param distanceMeters   radial distance in meters (non-negative)
 */
public record SphericalCoordinate(double azimuthDegrees, double elevationDegrees,
                                  double distanceMeters) {

    public SphericalCoordinate {
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("distanceMeters must be non-negative: " + distanceMeters);
        }
    }

    /**
     * Returns the angular distance (great-circle angle) to another coordinate
     * on the unit sphere, ignoring radial distance.
     *
     * @param other the target coordinate
     * @return the angular distance in degrees [0, 180]
     */
    public double angularDistanceTo(SphericalCoordinate other) {
        double az1 = Math.toRadians(azimuthDegrees);
        double el1 = Math.toRadians(elevationDegrees);
        double az2 = Math.toRadians(other.azimuthDegrees);
        double el2 = Math.toRadians(other.elevationDegrees);
        double cosAngle = Math.sin(el1) * Math.sin(el2)
                + Math.cos(el1) * Math.cos(el2) * Math.cos(az2 - az1);
        return Math.toDegrees(Math.acos(Math.min(1.0, Math.max(-1.0, cosAngle))));
    }

    /**
     * Returns a new coordinate with azimuth normalized to [0, 360).
     *
     * @return the normalized coordinate
     */
    public SphericalCoordinate normalize() {
        double az = azimuthDegrees % 360.0;
        if (az < 0) {
            az += 360.0;
        }
        return new SphericalCoordinate(az, elevationDegrees, distanceMeters);
    }
}
