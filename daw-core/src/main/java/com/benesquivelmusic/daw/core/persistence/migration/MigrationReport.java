package com.benesquivelmusic.daw.core.persistence.migration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Describes the outcome of running the {@link MigrationRegistry} against
 * a single project file load.
 *
 * <p>A report is always produced — even when no migrations were needed
 * — so that callers can uniformly inspect {@link #wasMigrated()} (backup,
 * history, badge) and {@link #changesAuthoredContent()} (the migration
 * report dialog) to decide what to surface.</p>
 *
 * @param fromVersion the schema version found in the file
 * @param toVersion   the schema version after migration (always equal
 *                    to {@link MigrationRegistry#currentVersion()} when
 *                    migration succeeds)
 * @param applied     the migrations that ran, in order
 * @param timestamp   wall-clock instant the migration ran (mainly for
 *                    audit/log surfaces)
 */
public record MigrationReport(int fromVersion,
                              int toVersion,
                              List<AppliedMigration> applied,
                              Instant timestamp) {

    public MigrationReport {
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(timestamp, "timestamp");
        applied = List.copyOf(applied);
    }

    /** Returns {@code true} if at least one migration was applied. */
    public boolean wasMigrated() {
        return !applied.isEmpty();
    }

    /**
     * Returns {@code true} if some applied migration changes what the user
     * authored ({@link ProjectMigration#changesAuthoredContent()}) — the
     * migration report dialog's gate. A report whose steps are all
     * {@linkplain ProjectMigration#formatOnly format-only} still
     * {@linkplain #wasMigrated() was migrated}: its backup, history and badge
     * follow {@link #wasMigrated()}.
     */
    public boolean changesAuthoredContent() {
        return applied.stream().anyMatch(AppliedMigration::changesAuthoredContent);
    }

    /**
     * Convenience factory for the common "no migration needed" case.
     */
    public static MigrationReport noOp(int currentVersion) {
        return new MigrationReport(currentVersion, currentVersion, List.of(), Instant.now());
    }

    /**
     * One row in a {@link MigrationReport} — represents a single migration
     * step that ran successfully.
     *
     * @param fromVersion            the version before this step
     * @param toVersion              the version after this step
     * @param description            copied from the underlying {@link ProjectMigration}
     * @param changesAuthoredContent copied from the underlying
     *                               {@link ProjectMigration#changesAuthoredContent()}
     */
    public record AppliedMigration(int fromVersion, int toVersion, String description,
                                   boolean changesAuthoredContent) {
        public AppliedMigration {
            Objects.requireNonNull(description, "description");
        }

        /** A row for a step that changes what the user authored. */
        public AppliedMigration(int fromVersion, int toVersion, String description) {
            this(fromVersion, toVersion, description, true);
        }

        static AppliedMigration of(ProjectMigration migration) {
            return new AppliedMigration(migration.fromVersion(),
                    migration.toVersion(), migration.description(),
                    migration.changesAuthoredContent());
        }
    }
}
