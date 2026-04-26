package com.benesquivelmusic.daw.sdk.model;

/**
 * An immutable automation point at a specific beat on an
 * {@link AutomationLane}.
 *
 * @param beat  timeline beat position (must be {@code >= 0})
 * @param value parameter value at this point
 */
public record AutomationPoint(double beat, double value) {

    public AutomationPoint {
        if (beat < 0) {
            throw new IllegalArgumentException("beat must not be negative: " + beat);
        }
    }

    public AutomationPoint withBeat(double beat) {
        return new AutomationPoint(beat, value);
    }

    public AutomationPoint withValue(double value) {
        return new AutomationPoint(beat, value);
    }
}
