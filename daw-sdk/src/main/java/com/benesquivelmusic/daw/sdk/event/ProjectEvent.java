package com.benesquivelmusic.daw.sdk.event;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Project lifecycle event hierarchy.
 *
 * <p>Emitted around session-level operations: loading a project file,
 * saving, creating a fresh project, closing the current project, and
 * undo/redo. These events are about the project as a whole rather than
 * about any specific entity inside it.</p>
 */
public sealed interface ProjectEvent extends DawEvent
        permits ProjectEvent.Opened,
                ProjectEvent.Closed,
                ProjectEvent.Saved,
                ProjectEvent.Created,
                ProjectEvent.Undone,
                ProjectEvent.Redone {

    /** Returns the wall-clock instant at which this event was produced. */
    @Override
    Instant timestamp();

    /**
     * Emitted when a project is loaded from disk.
     *
     * @param projectId id of the loaded project
     * @param location  filesystem path the project was loaded from
     * @param timestamp wall-clock instant of the event
     */
    record Opened(UUID projectId, Path location, Instant timestamp) implements ProjectEvent {
        public Opened {
            Objects.requireNonNull(projectId, "projectId must not be null");
            Objects.requireNonNull(location, "location must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the current project is closed.
     *
     * @param projectId id of the closed project
     * @param timestamp wall-clock instant of the event
     */
    record Closed(UUID projectId, Instant timestamp) implements ProjectEvent {
        public Closed {
            Objects.requireNonNull(projectId, "projectId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the current project is persisted to disk.
     *
     * @param projectId id of the saved project
     * @param location  filesystem path the project was saved to
     * @param timestamp wall-clock instant of the event
     */
    record Saved(UUID projectId, Path location, Instant timestamp) implements ProjectEvent {
        public Saved {
            Objects.requireNonNull(projectId, "projectId must not be null");
            Objects.requireNonNull(location, "location must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a fresh empty project is created.
     *
     * @param projectId id of the new project
     * @param timestamp wall-clock instant of the event
     */
    record Created(UUID projectId, Instant timestamp) implements ProjectEvent {
        public Created {
            Objects.requireNonNull(projectId, "projectId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the most recent action is undone.
     *
     * @param projectId id of the affected project
     * @param timestamp wall-clock instant of the event
     */
    record Undone(UUID projectId, Instant timestamp) implements ProjectEvent {
        public Undone {
            Objects.requireNonNull(projectId, "projectId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when a previously-undone action is re-applied.
     *
     * @param projectId id of the affected project
     * @param timestamp wall-clock instant of the event
     */
    record Redone(UUID projectId, Instant timestamp) implements ProjectEvent {
        public Redone {
            Objects.requireNonNull(projectId, "projectId must not be null");
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }
}
