package com.benesquivelmusic.daw.core.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable description of a project lock written to a {@code .project.lock}
 * sidecar file.
 *
 * <p>The lock identifies the holder by OS username, hostname, and process id,
 * and records when the project was opened and when the holder was last seen
 * (the latter is updated by a heartbeat). A unique {@code lockId} ensures
 * that a holder can detect when their lock has been "stolen" by another
 * session even if user / host / pid would otherwise match (for example,
 * after a crash and restart).</p>
 *
 * @param lockId      a unique identifier for this lock instance
 * @param user        the OS username of the lock holder
 * @param hostname    the hostname of the machine holding the lock
 * @param pid         the operating-system process id of the holder
 * @param openedAt    when the project was opened
 * @param lastSeenAt  the most recent heartbeat timestamp
 */
public record ProjectLock(
        String lockId,
        String user,
        String hostname,
        long pid,
        Instant openedAt,
        Instant lastSeenAt
) {
    public ProjectLock {
        Objects.requireNonNull(lockId, "lockId must not be null");
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(hostname, "hostname must not be null");
        Objects.requireNonNull(openedAt, "openedAt must not be null");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
    }

    /**
     * Creates a new lock for the given identity, with {@code openedAt} and
     * {@code lastSeenAt} both set to {@code now} and a freshly generated
     * {@code lockId}.
     */
    public static ProjectLock create(String user, String hostname, long pid, Instant now) {
        return new ProjectLock(UUID.randomUUID().toString(), user, hostname, pid, now, now);
    }

    /** Returns a copy of this lock with {@code lastSeenAt} updated to {@code now}. */
    public ProjectLock withHeartbeat(Instant now) {
        return new ProjectLock(lockId, user, hostname, pid, openedAt, now);
    }
}
