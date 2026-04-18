package com.benesquivelmusic.daw.core.automation;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ramer–Douglas–Peucker thinning for automation breakpoints.
 *
 * <p>Recording modes ({@link com.benesquivelmusic.daw.core.track.AutomationMode#WRITE WRITE},
 * {@link com.benesquivelmusic.daw.core.track.AutomationMode#LATCH LATCH},
 * {@link com.benesquivelmusic.daw.core.track.AutomationMode#TOUCH TOUCH}) emit
 * a new breakpoint for every parameter change that arrives from the UI. This
 * produces dense point clouds with many near-redundant points. The Ramer–
 * Douglas–Peucker algorithm keeps only the points whose perpendicular
 * distance from the straight line between the two surrounding kept points
 * exceeds a {@code tolerance} threshold, preserving the shape of the curve
 * while eliminating noise.</p>
 *
 * <p>All methods are pure: they do not mutate their input. Use
 * {@link #simplifyLane(AutomationLane, double)} as a convenience to replace
 * the contents of a lane in place.</p>
 */
public final class AutomationSimplifier {

    private AutomationSimplifier() {
    }

    /**
     * Returns a simplified copy of {@code points} using the Ramer–Douglas–
     * Peucker algorithm with the given tolerance.
     *
     * <p>The first and last points are always retained. The interpolation
     * mode of every retained point is preserved. If {@code points} has fewer
     * than three entries, the input list is returned unchanged (wrapped in
     * an unmodifiable list).</p>
     *
     * @param points    the dense point list, sorted by time
     * @param tolerance maximum perpendicular distance (in the same units as
     *                  the point values) at which a point may be dropped
     *                  without altering the overall curve shape; must be
     *                  strictly positive
     * @return a new list containing the retained points in time order
     * @throws IllegalArgumentException if {@code tolerance} is not positive
     */
    public static List<AutomationPoint> simplify(List<AutomationPoint> points,
                                                 double tolerance) {
        Objects.requireNonNull(points, "points must not be null");
        if (!(tolerance > 0.0) || Double.isNaN(tolerance)) {
            throw new IllegalArgumentException("tolerance must be > 0: " + tolerance);
        }
        int n = points.size();
        if (n < 3) {
            return List.copyOf(points);
        }

        BitSet keep = new BitSet(n);
        keep.set(0);
        keep.set(n - 1);
        rdp(points, 0, n - 1, tolerance, keep);

        var out = new ArrayList<AutomationPoint>(keep.cardinality());
        for (int i = keep.nextSetBit(0); i >= 0; i = keep.nextSetBit(i + 1)) {
            out.add(points.get(i));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Simplifies the points of {@code lane} in place.
     *
     * @param lane      the lane to thin
     * @param tolerance simplification tolerance (must be &gt; 0)
     * @return the number of points removed (zero if the lane had &lt; 3 points)
     */
    public static int simplifyLane(AutomationLane lane, double tolerance) {
        Objects.requireNonNull(lane, "lane must not be null");
        List<AutomationPoint> before = new ArrayList<>(lane.getPoints());
        List<AutomationPoint> after = simplify(before, tolerance);
        int removed = before.size() - after.size();
        if (removed <= 0) {
            return 0;
        }
        lane.clearPoints();
        for (AutomationPoint p : after) {
            lane.addPoint(p);
        }
        return removed;
    }

    /**
     * Recursive RDP: splits {@code [first, last]} at the point with the
     * maximum perpendicular distance from the line {@code (first, last)} if
     * that distance exceeds {@code tolerance}, keeps the splitting point,
     * and recurses on the two halves.
     */
    private static void rdp(List<AutomationPoint> points,
                            int first, int last,
                            double tolerance, BitSet keep) {
        if (last - first < 2) {
            return;
        }
        AutomationPoint a = points.get(first);
        AutomationPoint b = points.get(last);
        double ax = a.getTimeInBeats();
        double ay = a.getValue();
        double bx = b.getTimeInBeats();
        double by = b.getValue();
        double dx = bx - ax;
        double dy = by - ay;
        double segLengthSq = dx * dx + dy * dy;

        double maxDist = -1.0;
        int maxIdx = -1;
        for (int i = first + 1; i < last; i++) {
            AutomationPoint p = points.get(i);
            double d = perpendicularDistance(
                    p.getTimeInBeats(), p.getValue(),
                    ax, ay, dx, dy, segLengthSq);
            if (d > maxDist) {
                maxDist = d;
                maxIdx = i;
            }
        }

        if (maxIdx < 0 || maxDist <= tolerance) {
            return;
        }
        keep.set(maxIdx);
        rdp(points, first, maxIdx, tolerance, keep);
        rdp(points, maxIdx, last, tolerance, keep);
    }

    /**
     * Perpendicular distance from point {@code (px, py)} to the line segment
     * starting at {@code (ax, ay)} with direction {@code (dx, dy)} and
     * squared length {@code segLengthSq}. If the segment is degenerate
     * (length 0), falls back to the Euclidean distance to {@code (ax, ay)}.
     */
    private static double perpendicularDistance(double px, double py,
                                                double ax, double ay,
                                                double dx, double dy,
                                                double segLengthSq) {
        if (segLengthSq == 0.0) {
            double ex = px - ax;
            double ey = py - ay;
            return Math.hypot(ex, ey);
        }
        double cross = Math.abs(dx * (py - ay) - dy * (px - ax));
        return cross / Math.sqrt(segLengthSq);
    }
}
