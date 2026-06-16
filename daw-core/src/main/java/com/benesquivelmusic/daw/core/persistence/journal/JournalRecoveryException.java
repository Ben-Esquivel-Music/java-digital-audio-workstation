package com.benesquivelmusic.daw.core.persistence.journal;

/**
 * Story 298 — thrown when a structured crash-recovery is rolled back.
 *
 * <p>{@code JournalReplayer.recover(...)} performs checksum verification,
 * checkpoint loading, and journal reading as sibling subtasks of a
 * shutdown-on-failure {@code DawScope}. If <em>any</em> subtask fails (e.g. a
 * corrupt segment fails CRC verification, or the checkpoint cannot be
 * deserialised) the scope cancels the survivors and nothing is applied to a
 * live project — the recovery is atomic. This unchecked exception carries the
 * originating cause so callers can surface it while keeping their prior
 * project state untouched.</p>
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
