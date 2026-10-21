package com.benesquivelmusic.daw.app.ui;

import java.util.List;
import java.util.Objects;

/**
 * The way a project came to be opened (story 329, decision D14) — the one
 * thing an open's single announcement depends on besides the missing-assets
 * report. Every door that installs a deserialized project passes one to
 * {@code ProjectLifecycleController.loadProjectFromPath(Path, OpenDoor)} (or,
 * for a snapshot, to {@code installRestoredSnapshot}), and the load shows the
 * announcement {@link OpenAnnouncement#of} words for it; no caller shows a
 * notification of its own for a load that succeeded.
 */
sealed interface OpenDoor permits OpenDoor.Plain, OpenDoor.ArchiveRestore, OpenDoor.JournalReplay,
        OpenDoor.RecoveredWriteFailed, OpenDoor.FailedReplayFallback, OpenDoor.MigrationRollback,
        OpenDoor.SnapshotRestore {

    /** The plain door: Open, a recent project, the Hub, Welcome, a recovery with nothing to replay. */
    Plain PLAIN = new Plain();

    /** Open, a recent project, the Project Hub, Welcome's Continue, a recovery with nothing to replay. */
    record Plain() implements OpenDoor {
    }

    /**
     * An archive restored into its destination folder.
     *
     * @param projectName    the archived project's name
     * @param archiveMissing the asset references the archive itself could not
     *                       resolve, as stored (project-relative or absolute);
     *                       copied
     */
    record ArchiveRestore(String projectName, List<String> archiveMissing) implements OpenDoor {
        public ArchiveRestore {
            Objects.requireNonNull(projectName, "projectName must not be null");
            archiveMissing = List.copyOf(Objects.requireNonNull(archiveMissing, "archiveMissing must not be null"));
        }
    }

    /**
     * A journal replay whose recovered changes were written to {@code project.daw}.
     *
     * @param appliedChanges the number of recovered changes
     */
    record JournalReplay(int appliedChanges) implements OpenDoor {
    }

    /**
     * A journal replay whose recovered project could not be written, so the
     * last clean save is opened instead.
     *
     * @param failure why the write failed
     */
    record RecoveredWriteFailed(String failure) implements OpenDoor {
        public RecoveredWriteFailed {
            failure = String.valueOf(failure);
        }
    }

    /**
     * A journal replay that failed and was rolled back, so the last clean
     * save is opened instead.
     *
     * @param failure why the replay failed
     */
    record FailedReplayFallback(String failure) implements OpenDoor {
        public FailedReplayFallback {
            failure = String.valueOf(failure);
        }
    }

    /**
     * A migration roll-back that restored a pre-migration backup over
     * {@code project.daw} and reopened it.
     *
     * @param fromVersion the schema version the backup holds
     */
    record MigrationRollback(int fromVersion) implements OpenDoor {
    }

    /**
     * A snapshot restored from the snapshot browser (decision D21).
     *
     * @param label the snapshot's label
     */
    record SnapshotRestore(String label) implements OpenDoor {
        public SnapshotRestore {
            Objects.requireNonNull(label, "label must not be null");
        }
    }
}
