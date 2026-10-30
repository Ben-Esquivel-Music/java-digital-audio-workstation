package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;

/**
 * Thrown when a save is attempted but the project lock has been taken over
 * by another session (or removed): nothing was written to the project's
 * folder. The save's outcome carries it as {@link SaveOutcome.Failed#cause()}
 * (the same instance); the application catches it as itself — never as an
 * {@link IOException} — and, in place of a save failure's notification,
 * opens a blocking takeover dialog whose Save As… keeps the work in a new
 * project, so the other session's work is never clobbered (story 333;
 * Persistence Integrity book §5.4 step 3, rejection §9.14). A Save As copy
 * whose lock was lost before it was adopted reports it in
 * {@link ProjectManager.AdoptedCopy#lockCheckFailure()}.
 * {@link ProjectManager#closeProject()} never saves, so it never meets one.
 */
public class LockStolenException extends IOException {

    private final ProjectLock currentHolder;

    public LockStolenException(String message, ProjectLock currentHolder) {
        super(message);
        this.currentHolder = currentHolder;
    }

    /** Returns the lock currently present on disk, or {@code null} if the lock file is missing. */
    public ProjectLock currentHolder() {
        return currentHolder;
    }
}
