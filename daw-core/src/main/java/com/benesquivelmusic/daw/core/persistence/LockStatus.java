package com.benesquivelmusic.daw.core.persistence;

/**
 * High-level state of a project lock from the perspective of the current
 * session, suitable for display in the title bar via a {@code LockStatusIndicator}.
 */
public enum LockStatus {

    /** No lock has been acquired (no project open or read-only without lock attempt). */
    NONE,

    /** This session holds the lock and is the authoritative writer. */
    HELD,

    /** Project was opened read-only because another session held the lock. */
    READ_ONLY,

    /** This session previously held the lock but it has been taken over by another session. */
    STOLEN
}
