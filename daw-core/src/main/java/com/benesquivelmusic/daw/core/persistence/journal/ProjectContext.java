package com.benesquivelmusic.daw.core.persistence.journal;

import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.UUID;

/**
 * Story 298 — the §5.4 non-preview recovery context.
 *
 * <p>An immutable, value-typed descriptor of which project a journal /
 * crash-recovery operation is acting on. It is the explicit alternative
 * to a {@link ThreadLocal}: rather than smuggling per-recovery state
 * through a thread-bound slot, {@code JournalReplayer} threads a single
 * {@code ProjectContext} record through every {@code DawScope.fork(...)}
 * lambda by closure capture. Because the record is shallowly immutable
 * (JEP 395) it can be shared freely across the recovery's virtual
 * threads without synchronization.</p>
 *
 * <p>The DAW deliberately keeps the build free of {@code --enable-preview}
 * (JEP 506 {@code ScopedValue} is final in JDK 25 but is not needed here):
 * a plain record argument carries the context with zero ceremony and no
 * thread affinity.</p>
 *
 * @param projectDirectory the root project directory (contains
 *                         {@code project.daw}, {@code journal/} and
 *                         {@code checkpoints/})
 * @param requestId        a unique id for this recovery / journal request,
 *                         useful for correlating log lines across the
 *                         forked subtasks
 * @param requestedAt      the wall-clock instant the request was created
 */
public record ProjectContext(Path projectDirectory, UUID requestId, Instant requestedAt) {

    /** Name of the per-project journal directory ({@code journal/}). */
    public static final String JOURNAL_DIR_NAME = "journal";

    /** Name of the per-project checkpoint directory ({@code checkpoints/}). */
    public static final String CHECKPOINT_DIR_NAME = "checkpoints";

    /** Canonical project file name ({@code project.daw}). */
    public static final String PROJECT_FILE_NAME = "project.daw";

    /**
     * Canonical constructor. All three components are required.
     */
    public ProjectContext {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    }

    /**
     * Creates a fresh context for {@code projectDirectory} with a random
     * request id stamped at the current system instant.
     *
     * @param projectDirectory the root project directory
     * @return a new context
     */
    public static ProjectContext forProject(Path projectDirectory) {
        return forProject(projectDirectory, InstantSource.system());
    }

    /**
     * Creates a fresh context for {@code projectDirectory} with a random
     * request id stamped at {@code clock}'s current instant (test seam).
     *
     * @param projectDirectory the root project directory
     * @param clock            the instant source (use a fixed source in tests)
     * @return a new context
     */
    public static ProjectContext forProject(Path projectDirectory, InstantSource clock) {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new ProjectContext(projectDirectory, UUID.randomUUID(), clock.instant());
    }

    /** {@return the per-project journal directory, {@code projectDirectory/journal}} */
    public Path journalDirectory() {
        return projectDirectory.resolve(JOURNAL_DIR_NAME);
    }

    /** {@return the per-project checkpoint directory, {@code projectDirectory/checkpoints}} */
    public Path checkpointDirectory() {
        return projectDirectory.resolve(CHECKPOINT_DIR_NAME);
    }

    /** {@return the canonical project file, {@code projectDirectory/project.daw}} */
    public Path projectFile() {
        return projectDirectory.resolve(PROJECT_FILE_NAME);
    }
}
