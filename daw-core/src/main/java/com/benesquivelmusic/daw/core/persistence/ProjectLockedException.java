package com.benesquivelmusic.daw.core.persistence;

import java.io.IOException;

/**
 * Thrown by {@link ProjectManager#openProject(java.nio.file.Path)} when the
 * project is already locked by another session and the configured
 * {@link LockConflictHandler} returned (or defaulted to)
 * {@link LockConflictResolution#CANCEL}.
 */
public class ProjectLockedException extends IOException {

    private final ProjectLock holder;
    private final boolean stale;

    public ProjectLockedException(String message, ProjectLock holder, boolean stale) {
        super(message);
        this.holder = holder;
        this.stale = stale;
    }

    /** The lock holder reported on disk. */
    public ProjectLock holder() {
        return holder;
    }

    /** Whether the existing lock was considered stale at the time the conflict was raised. */
    public boolean stale() {
        return stale;
    }
}
