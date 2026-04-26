package com.benesquivelmusic.daw.sdk.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable automation lane: a named time-series of {@link AutomationPoint
 * automation points} controlling a single parameter on a track or mixer
 * channel.
 *
 * @param id            stable unique identifier
 * @param parameterName fully-qualified parameter name (e.g. {@code "track.volume"})
 * @param points        immutable list of automation points (any order)
 */
public record AutomationLane(UUID id, String parameterName, List<AutomationPoint> points) {

    public AutomationLane {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(parameterName, "parameterName must not be null");
        points = List.copyOf(Objects.requireNonNull(points, "points must not be null"));
    }

    /** Creates a freshly-identified, empty automation lane. */
    public static AutomationLane of(String parameterName) {
        return new AutomationLane(UUID.randomUUID(), parameterName, List.of());
    }

    public AutomationLane withId(UUID id) {
        return new AutomationLane(id, parameterName, points);
    }

    public AutomationLane withParameterName(String parameterName) {
        return new AutomationLane(id, parameterName, points);
    }

    public AutomationLane withPoints(List<AutomationPoint> points) {
        return new AutomationLane(id, parameterName, points);
    }
}
