package com.benesquivelmusic.daw.core.persistence;

/**
 * User decision returned by a {@link LockConflictHandler} when a project is
 * opened while another session already holds the lock.
 */
public enum LockConflictResolution {

    /** Open the project read-only, leaving the existing lock untouched. */
    OPEN_READ_ONLY,

    /** Forcibly take over the lock from the other session (force-steal). */
    TAKE_OVER,

    /** Abort the open. */
    CANCEL
}
