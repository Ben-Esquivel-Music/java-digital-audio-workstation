package com.benesquivelmusic.daw.sdk.spatial;

/**
 * A single automation keyframe for the 3D spatial panner.
 *
 * <p>Records the panner position (azimuth, elevation, distance) at a
 * specific time in beats on the project timeline.</p>
 *
 * @param timeBeat         the position on the timeline in beats (non-negative)
 * @param azimuthDegrees   the azimuth at this keyframe in degrees
 * @param elevationDegrees the elevation at this keyframe in degrees [−90, +90]
 * @param distanceMeters   the distance at this keyframe in meters (non-negative)
 */
public record PanAutomationPoint(double timeBeat, double azimuthDegrees,
                                  double elevationDegrees, double distanceMeters) {

    public PanAutomationPoint {
        if (timeBeat < 0) {
            throw new IllegalArgumentException("timeBeat must be non-negative: " + timeBeat);
        }
        if (elevationDegrees < -90.0 || elevationDegrees > 90.0) {
            throw new IllegalArgumentException(
                    "elevationDegrees must be in [−90, +90]: " + elevationDegrees);
        }
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("distanceMeters must be non-negative: " + distanceMeters);
        }
    }

    /**
     * Returns the spatial position encoded by this automation point.
     *
     * @return the position as a {@link SpatialPosition}
     */
    public SpatialPosition toSpatialPosition() {
        double normalizedAz = azimuthDegrees % 360.0;
        if (normalizedAz < 0) normalizedAz += 360.0;
        return new SpatialPosition(normalizedAz, elevationDegrees, distanceMeters);
    }
}
