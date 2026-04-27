package com.benesquivelmusic.daw.core.persistence.migration;

import java.util.List;

/**
 * Thrown when the {@link MigrationRegistry} cannot find a continuous
 * sequence of migrations to bring a project file from its on-disk
 * schema version up to the current schema version.
 *
 * <p>Common causes:</p>
 * <ul>
 *   <li>The file is from a future build (its version is greater than
 *       the current schema version) — downgrade is a non-goal.</li>
 *   <li>A version step is missing from the registry (a coding bug).</li>
 *   <li>The file has been pre-consolidated into an older legacy batch
 *       than the registry knows about.</li>
 * </ul>
 */
public final class MigrationException extends RuntimeException {

    private final int fromVersion;
    private final int toVersion;
    private final List<Integer> reachableVersions;

    public MigrationException(String message,
                              int fromVersion,
                              int toVersion,
                              List<Integer> reachableVersions) {
        super(message);
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.reachableVersions = List.copyOf(reachableVersions);
    }

    public int fromVersion() {
        return fromVersion;
    }

    public int toVersion() {
        return toVersion;
    }

    /** Versions the registry knows how to migrate from, in ascending order. */
    public List<Integer> reachableVersions() {
        return reachableVersions;
    }
}
