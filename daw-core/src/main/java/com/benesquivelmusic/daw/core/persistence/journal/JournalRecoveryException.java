package com.benesquivelmusic.daw.core.persistence.journal;

/**
 * Story 298 — thrown when a structured crash-recovery is rolled back.
 *
 * <p>{@code JournalReplayer.recover(...)} verifies the segments' checksums and
 * loads the verified recovery point as sibling subtasks of a
 * shutdown-on-failure {@code DawScope}; if either fails the scope cancels the
 * other and nothing is applied to a live project — the recovery is atomic. It
 * is also thrown, before any subtask, when the plan says replay cannot
 * start: it has no recovery point replay can load, or it found a damaged
 * journal segment. This unchecked exception carries the originating cause so
 * callers can surface it while keeping their prior project state
 * untouched.</p>
 */
public final class JournalRecoveryException extends RuntimeException {

    /**
     * @param message a short description of the rollback
     * @param cause   the originating failure (the first failing subtask's cause)
     */
    public JournalRecoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
