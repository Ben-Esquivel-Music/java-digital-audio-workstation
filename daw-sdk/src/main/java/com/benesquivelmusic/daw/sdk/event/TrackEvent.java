package com.benesquivelmusic.daw.sdk.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Track event hierarchy.
 *
 * <p>Emitted when a {@link com.benesquivelmusic.daw.sdk.model.Track Track}
 * is added, removed, renamed, or has one of its transport flags
 * (mute, solo, armed) changed. Consumers read the post-change state from
 * the current project snapshot &mdash; the records below carry only the
 * track id and a wall-clock timestamp.</p>
 */
public sealed interface TrackEvent extends DawEvent
        permits TrackEvent.Added,
                TrackEvent.Removed,
                TrackEvent.Renamed,
                TrackEvent.Muted,
                TrackEvent.Soloed,
                TrackEvent.Armed {

    /** Returns the id of the affected track. */
    UUID trackId();

    /** Returns the wall-clock instant at which this event was produced. */
    @Override
    Instant timestamp();

    /**
     * Emitted when a track is added to the project.
     *
     * @param trackId   id of the newly-added track
     * @param timestamp wall-clock instant of the event
     */
    record Added(UUID trackId, Instant timestamp) implements TrackEvent {
        public Added {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a track is removed from the project.
     *
     * @param trackId   id of the removed track
     * @param timestamp wall-clock instant of the event
     */
    record Removed(UUID trackId, Instant timestamp) implements TrackEvent {
        public Removed {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a track's display name changes.
     *
     * @param trackId   id of the renamed track
     * @param timestamp wall-clock instant of the event
     */
    record Renamed(UUID trackId, Instant timestamp) implements TrackEvent {
        public Renamed {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a track's mute flag changes.
     *
     * @param trackId   id of the affected track
     * @param muted     the new mute state
     * @param timestamp wall-clock instant of the event
     */
    record Muted(UUID trackId, boolean muted, Instant timestamp) implements TrackEvent {
        public Muted {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a track's solo flag changes.
     *
     * @param trackId   id of the affected track
     * @param soloed    the new solo state
     * @param timestamp wall-clock instant of the event
     */
    record Soloed(UUID trackId, boolean soloed, Instant timestamp) implements TrackEvent {
        public Soloed {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a track's record-arm flag changes.
     *
     * @param trackId   id of the affected track
     * @param armed     the new armed state
     * @param timestamp wall-clock instant of the event
     */
    record Armed(UUID trackId, boolean armed, Instant timestamp) implements TrackEvent {
        public Armed {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
