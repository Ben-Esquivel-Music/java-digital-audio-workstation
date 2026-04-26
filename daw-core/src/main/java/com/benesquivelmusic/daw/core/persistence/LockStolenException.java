package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;

/**
 * Thrown when a save is attempted but the project lock has been taken over
 * by another session (or removed). The UI should respond by prompting the
 * user to "Save As" to a different location to avoid clobbering the other
 * session's work.
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
