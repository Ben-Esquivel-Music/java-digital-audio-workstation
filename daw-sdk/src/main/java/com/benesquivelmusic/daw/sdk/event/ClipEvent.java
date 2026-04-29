package com.benesquivelmusic.daw.sdk.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Clip event hierarchy &mdash; covers both audio and MIDI clips.
 *
 * <p>Emitted when a clip is added, removed, moved on the timeline,
 * trimmed, or renamed. The {@link #trackId()} accessor identifies the
 * track on which the clip lives, and {@link #clipId()} identifies the
 * clip itself; the post-change state is read from the current project
 * snapshot.</p>
 */
public sealed interface ClipEvent extends DawEvent
        permits ClipEvent.Added,
                ClipEvent.Removed,
                ClipEvent.Moved,
                ClipEvent.Trimmed,
                ClipEvent.Renamed {

    /** Returns the id of the track that owns the affected clip. */
    UUID trackId();

    /** Returns the id of the affected clip. */
    UUID clipId();

    /** Returns the wall-clock instant at which this event was produced. */
    @Override
    Instant timestamp();

    /**
     * Emitted when a clip is placed on a track timeline.
     *
     * @param trackId   id of the owning track
     * @param clipId    id of the newly-added clip
     * @param timestamp wall-clock instant of the event
     */
    record Added(UUID trackId, UUID clipId, Instant timestamp) implements ClipEvent {
        public Added {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(clipId, "clipId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a clip is removed from a track timeline.
     *
     * @param trackId   id of the previously-owning track
     * @param clipId    id of the removed clip
     * @param timestamp wall-clock instant of the event
     */
    record Removed(UUID trackId, UUID clipId, Instant timestamp) implements ClipEvent {
        public Removed {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(clipId, "clipId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a clip is moved along the timeline (its start
     * position changed).
     *
     * @param trackId   id of the owning track
     * @param clipId    id of the moved clip
     * @param timestamp wall-clock instant of the event
     */
    record Moved(UUID trackId, UUID clipId, Instant timestamp) implements ClipEvent {
        public Moved {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(clipId, "clipId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a clip's start- or end-edge is trimmed.
     *
     * @param trackId   id of the owning track
     * @param clipId    id of the trimmed clip
     * @param timestamp wall-clock instant of the event
     */
    record Trimmed(UUID trackId, UUID clipId, Instant timestamp) implements ClipEvent {
        public Trimmed {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(clipId, "clipId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a clip's display name changes.
     *
     * @param trackId   id of the owning track
     * @param clipId    id of the renamed clip
     * @param timestamp wall-clock instant of the event
     */
    record Renamed(UUID trackId, UUID clipId, Instant timestamp) implements ClipEvent {
        public Renamed {
            Objects.requireNonNull(trackId, "trackId must not be null");
            Objects.requireNonNull(clipId, "clipId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
