package com.benesquivelmusic.daw.sdk.spatial;

import java.util.List;
import java.util.Objects;

/**
 * An ordered sequence of pan automation keyframes with linear interpolation.
 *
 * <p>Points must be sorted by {@link PanAutomationPoint#timeBeat()} in
 * ascending order. Querying a position at a time between two keyframes
 * returns a linearly interpolated {@link SpatialPosition}.</p>
 *
 * @param points the automation keyframes, sorted by time (ascending)
 */
public record PanAutomationCurve(List<PanAutomationPoint> points) {

    public PanAutomationCurve {
        Objects.requireNonNull(points, "points must not be null");
        points = List.copyOf(points);
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).timeBeat() < points.get(i - 1).timeBeat()) {
                throw new IllegalArgumentException(
                        "points must be sorted by timeBeat ascending");
            }
        }
    }

    /**
     * Returns the interpolated spatial position at the given time.
     *
     * <p>Before the first keyframe, returns the first keyframe's position.
     * After the last keyframe, returns the last keyframe's position.
     * Between two keyframes, linearly interpolates all parameters.</p>
     *
     * @param timeBeat the position on the timeline in beats
     * @return the interpolated position
     * @throws IllegalStateException if the curve contains no points
     */
    public SpatialPosition positionAt(double timeBeat) {
        if (points.isEmpty()) {
            throw new IllegalStateException("curve contains no automation points");
        }

        if (points.size() == 1 || timeBeat <= points.getFirst().timeBeat()) {
            return points.getFirst().toSpatialPosition();
        }
        if (timeBeat >= points.getLast().timeBeat()) {
            return points.getLast().toSpatialPosition();
        }

        // Binary search for the surrounding keyframes
        int lo = 0;
        int hi = points.size() - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (points.get(mid).timeBeat() <= timeBeat) {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        PanAutomationPoint p0 = points.get(lo);
        PanAutomationPoint p1 = points.get(hi);
        double t = (timeBeat - p0.timeBeat()) / (p1.timeBeat() - p0.timeBeat());

        double az = lerp(p0.azimuthDegrees(), p1.azimuthDegrees(), t);
        double el = lerp(p0.elevationDegrees(), p1.elevationDegrees(), t);
        double dist = lerp(p0.distanceMeters(), p1.distanceMeters(), t);

        double normalizedAz = az % 360.0;
        if (normalizedAz < 0) normalizedAz += 360.0;
        return new SpatialPosition(normalizedAz, el, dist);
    }

    /**
     * Returns whether this curve contains any automation points.
     *
     * @return {@code true} if the curve has at least one point
     */
    public boolean hasPoints() {
        return !points.isEmpty();
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
