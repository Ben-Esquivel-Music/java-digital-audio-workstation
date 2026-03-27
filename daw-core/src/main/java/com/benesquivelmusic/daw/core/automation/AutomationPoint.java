package com.benesquivelmusic.daw.core.automation;

import java.util.Objects;

/**
 * A single breakpoint node in an automation envelope.
 *
 * <p>Each point defines a {@linkplain #getTimeInBeats() time position} (in beats),
 * a {@linkplain #getValue() parameter value}, and an {@linkplain #getInterpolationMode()
 * interpolation mode} describing how the value transitions to the next point.</p>
 */
public final class AutomationPoint implements Comparable<AutomationPoint> {

    private double timeInBeats;
    private double value;
    private InterpolationMode interpolationMode;

    /**
     * Creates a new automation point.
     *
     * @param timeInBeats       the time position in beats (must be &ge; 0)
     * @param value             the parameter value
     * @param interpolationMode the interpolation mode to the next point
     */
    public AutomationPoint(double timeInBeats, double value, InterpolationMode interpolationMode) {
        if (timeInBeats < 0.0) {
            throw new IllegalArgumentException("timeInBeats must be >= 0: " + timeInBeats);
        }
        Objects.requireNonNull(interpolationMode, "interpolationMode must not be null");
        this.timeInBeats = timeInBeats;
        this.value = value;
        this.interpolationMode = interpolationMode;
    }

    /**
     * Creates a new automation point with {@link InterpolationMode#LINEAR}.
     *
     * @param timeInBeats the time position in beats (must be &ge; 0)
     * @param value       the parameter value
     */
    public AutomationPoint(double timeInBeats, double value) {
        this(timeInBeats, value, InterpolationMode.LINEAR);
    }

    /** Returns the time position in beats. */
    public double getTimeInBeats() {
        return timeInBeats;
    }

    /**
     * Sets the time position in beats.
     *
     * @param timeInBeats the new time (must be &ge; 0)
     */
    public void setTimeInBeats(double timeInBeats) {
        if (timeInBeats < 0.0) {
            throw new IllegalArgumentException("timeInBeats must be >= 0: " + timeInBeats);
        }
        this.timeInBeats = timeInBeats;
    }

    /** Returns the parameter value at this point. */
    public double getValue() {
        return value;
    }

    /**
     * Sets the parameter value at this point.
     *
     * @param value the new value
     */
    public void setValue(double value) {
        this.value = value;
    }

    /** Returns the interpolation mode used when transitioning to the next point. */
    public InterpolationMode getInterpolationMode() {
        return interpolationMode;
    }

    /**
     * Sets the interpolation mode.
     *
     * @param interpolationMode the interpolation mode
     */
    public void setInterpolationMode(InterpolationMode interpolationMode) {
        this.interpolationMode = Objects.requireNonNull(interpolationMode,
                "interpolationMode must not be null");
    }

    /**
     * Compares automation points by their time position.
     */
    @Override
    public int compareTo(AutomationPoint other) {
        return Double.compare(this.timeInBeats, other.timeInBeats);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AutomationPoint other)) {
            return false;
        }
        return Double.compare(timeInBeats, other.timeInBeats) == 0
                && Double.compare(value, other.value) == 0
                && interpolationMode == other.interpolationMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeInBeats, value, interpolationMode);
    }

    @Override
    public String toString() {
        return "AutomationPoint[time=" + timeInBeats + ", value=" + value
                + ", mode=" + interpolationMode + "]";
    }
}
