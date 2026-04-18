package com.benesquivelmusic.daw.core.automation;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An automation lane for a single {@link AutomationTarget}.
 *
 * <p>The lane maintains a time-sorted list of {@link AutomationPoint}s that
 * define a breakpoint envelope. The {@link #getValueAtTime(double)} method
 * evaluates the envelope at any time position using the interpolation mode
 * defined on each point.</p>
 *
 * <p>When no points are present, the lane returns the target's
 * {@linkplain AutomationTarget#getDefaultValue() default value}.</p>
 *
 * <p>A lane can be created for either a {@link AutomationParameter}
 * (mixer-channel parameter) or a {@link PluginParameterTarget} (plugin
 * parameter). Callers that only want to deal with mixer parameters can use
 * {@link #getParameter()} which throws if the lane targets a plugin
 * parameter.</p>
 */
public final class AutomationLane {

    private final AutomationTarget target;
    private final List<AutomationPoint> points = new ArrayList<>();
    private boolean visible;

    /**
     * Creates a new automation lane for the given target.
     *
     * @param target the target controlled by this lane
     */
    public AutomationLane(AutomationTarget target) {
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.visible = true;
    }

    /** Returns the target controlled by this lane. */
    public AutomationTarget getTarget() {
        return target;
    }

    /**
     * Returns the mixer-channel parameter controlled by this lane.
     *
     * @return the {@link AutomationParameter} if this lane targets a
     *         mixer-channel parameter
     * @throws IllegalStateException if this lane targets a plugin parameter
     *         rather than a mixer-channel parameter
     */
    public AutomationParameter getParameter() {
        if (target instanceof AutomationParameter parameter) {
            return parameter;
        }
        throw new IllegalStateException(
                "lane targets a plugin parameter, not a mixer-channel parameter: " + target);
    }

    /** Returns whether this lane is visible (expanded) in the arrangement view. */
    public boolean isVisible() {
        return visible;
    }

    /** Sets whether this lane is visible (expanded) in the arrangement view. */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * Adds a point to the lane and re-sorts the list.
     *
     * @param point the point to add
     * @throws IllegalArgumentException if the point's value is outside the parameter range
     */
    public void addPoint(AutomationPoint point) {
        Objects.requireNonNull(point, "point must not be null");
        if (!target.isValidValue(point.getValue())) {
            throw new IllegalArgumentException(
                    "value " + point.getValue() + " is outside the valid range for "
                            + target + " [" + target.getMinValue() + ", "
                            + target.getMaxValue() + "]");
        }
        points.add(point);
        Collections.sort(points);
    }

    /**
     * Removes a point from the lane.
     *
     * @param point the point to remove
     * @return {@code true} if the point was removed
     */
    public boolean removePoint(AutomationPoint point) {
        return points.remove(point);
    }

    /**
     * Returns an unmodifiable view of the points, sorted by time.
     *
     * @return the list of automation points
     */
    public List<AutomationPoint> getPoints() {
        return Collections.unmodifiableList(points);
    }

    /** Returns the number of points in this lane. */
    public int getPointCount() {
        return points.size();
    }

    /** Removes all points from this lane. */
    public void clearPoints() {
        points.clear();
    }

    /**
     * Re-sorts the point list after an external modification to a point's time.
     * Call this after moving a point's time position.
     */
    public void sortPoints() {
        Collections.sort(points);
    }

    /**
     * Evaluates the automation envelope at the given time position.
     *
     * <ul>
     *   <li>If there are no points, returns the parameter's default value.</li>
     *   <li>If the time is before the first point, returns the first point's value.</li>
     *   <li>If the time is after the last point, returns the last point's value.</li>
     *   <li>Otherwise, interpolates between the two surrounding points using
     *       the left point's {@link InterpolationMode}.</li>
     * </ul>
     *
     * @param timeInBeats the time position to evaluate
     * @return the interpolated parameter value
     */
    @RealTimeSafe
    public double getValueAtTime(double timeInBeats) {
        if (points.isEmpty()) {
            return target.getDefaultValue();
        }

        AutomationPoint first = points.get(0);
        if (timeInBeats <= first.getTimeInBeats()) {
            return first.getValue();
        }

        AutomationPoint last = points.get(points.size() - 1);
        if (timeInBeats >= last.getTimeInBeats()) {
            return last.getValue();
        }

        // Find the two surrounding points via binary search
        int index = findInsertionIndex(timeInBeats);
        AutomationPoint left = points.get(index - 1);
        AutomationPoint right = points.get(index);

        double span = right.getTimeInBeats() - left.getTimeInBeats();
        if (span <= 0.0) {
            return right.getValue();
        }

        double t = (timeInBeats - left.getTimeInBeats()) / span;

        if (left.getInterpolationMode() == InterpolationMode.CURVED) {
            // Smoothstep: 3t² − 2t³
            t = t * t * (3.0 - 2.0 * t);
        }

        return left.getValue() + t * (right.getValue() - left.getValue());
    }

    /**
     * Returns the index at which a point with the given time would be inserted
     * to maintain sorted order.
     */
    private int findInsertionIndex(double timeInBeats) {
        int low = 0;
        int high = points.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (points.get(mid).getTimeInBeats() < timeInBeats) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
