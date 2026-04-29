package com.benesquivelmusic.daw.sdk.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Automation event hierarchy.
 *
 * <p>Emitted when an
 * {@link com.benesquivelmusic.daw.sdk.model.AutomationLane AutomationLane}
 * is added, removed, or its envelope is edited (point added, removed,
 * or moved). Consumers locate the affected lane via {@link #laneId()}
 * and read the post-change envelope from the current project snapshot.</p>
 */
public sealed interface AutomationEvent extends DawEvent
        permits AutomationEvent.LaneAdded,
                AutomationEvent.LaneRemoved,
                AutomationEvent.PointAdded,
                AutomationEvent.PointRemoved,
                AutomationEvent.PointMoved {

    /** Returns the id of the affected automation lane. */
    UUID laneId();

    /** Returns the wall-clock instant at which this event was produced. */
    @Override
    Instant timestamp();

    /**
     * Emitted when a new automation lane is added to the project.
     *
     * @param laneId    id of the newly-added lane
     * @param timestamp wall-clock instant of the event
     */
    record LaneAdded(UUID laneId, Instant timestamp) implements AutomationEvent {
        public LaneAdded {
            Objects.requireNonNull(laneId, "laneId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when an automation lane is removed from the project.
     *
     * @param laneId    id of the removed lane
     * @param timestamp wall-clock instant of the event
     */
    record LaneRemoved(UUID laneId, Instant timestamp) implements AutomationEvent {
        public LaneRemoved {
            Objects.requireNonNull(laneId, "laneId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a point is added to an automation lane envelope.
     *
     * @param laneId    id of the affected lane
     * @param timestamp wall-clock instant of the event
     */
    record PointAdded(UUID laneId, Instant timestamp) implements AutomationEvent {
        public PointAdded {
            Objects.requireNonNull(laneId, "laneId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a point is removed from an automation lane envelope.
     *
     * @param laneId    id of the affected lane
     * @param timestamp wall-clock instant of the event
     */
    record PointRemoved(UUID laneId, Instant timestamp) implements AutomationEvent {
        public PointRemoved {
            Objects.requireNonNull(laneId, "laneId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a point on an automation lane envelope is moved
     * (its time or value coordinate changed).
     *
     * @param laneId    id of the affected lane
     * @param timestamp wall-clock instant of the event
     */
    record PointMoved(UUID laneId, Instant timestamp) implements AutomationEvent {
        public PointMoved {
            Objects.requireNonNull(laneId, "laneId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
