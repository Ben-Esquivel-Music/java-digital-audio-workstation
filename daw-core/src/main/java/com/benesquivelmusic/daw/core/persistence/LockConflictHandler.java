package com.benesquivelmusic.daw.core.persistence;

/**
 * Strategy invoked by {@link ProjectManager} (or directly by {@link ProjectLockManager})
 * when a project is opened that is already locked by another session.
 *
 * <p>UI implementations show a dialog displaying the holder's identity and
 * whether the lock appears stale, and return the user's choice. Headless
 * tests can supply a deterministic implementation.</p>
 */
@FunctionalInterface
public interface LockConflictHandler {

    /**
     * Resolves a lock conflict.
     *
     * @param existingLock the lock currently present on disk
     * @param stale        {@code true} if the existing lock has not been
     *                     refreshed within the staleness threshold
     * @return the user's resolution
     */
    LockConflictResolution resolve(ProjectLock existingLock, boolean stale);
}
