package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryChoice;
import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryDialog;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHealthScanner;
import com.benesquivelmusic.daw.app.ui.hub.ProjectHubView;
import com.benesquivelmusic.daw.app.ui.hub.WelcomeView;
import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecoveryException;
import com.benesquivelmusic.daw.core.persistence.journal.JournalReplayer;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.journal.RecoveryResult;
import com.benesquivelmusic.daw.core.persistence.journal.RecoveryScanner;
import com.benesquivelmusic.daw.core.persistence.journal.RecoverySummary;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.midi.SoundFontAssignment;
import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;
import com.benesquivelmusic.daw.core.persistence.ClipRehydrator;
import com.benesquivelmusic.daw.core.persistence.LockStatus;
import com.benesquivelmusic.daw.core.persistence.LockStolenException;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectPaths;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.SaveOutcome;
import com.benesquivelmusic.daw.core.persistence.UnreadProjectItem;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationException;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveCapture;
import com.benesquivelmusic.daw.core.persistence.archive.ArchivedProject;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveOptions;
import com.benesquivelmusic.daw.core.persistence.archive.MissingAssetResolver;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiveSummary;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.persistence.snapshot.SnapshotStore;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.session.SessionExportResult;
import com.benesquivelmusic.daw.sdk.session.SessionImportResult;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Manages project lifecycle actions: creating, opening, saving, browsing
 * recent projects, and importing/exporting sessions.
 *
 * <p>Extracted from {@link MainController} to isolate all project
 * open/save/import/export operations, their associated dialog interactions,
 * and the state resets required when switching between projects into a
 * dedicated, independently testable class. All dependencies are received
 * via constructor injection.</p>
 */
final class ProjectLifecycleController {

    private static final Logger LOG = Logger.getLogger(ProjectLifecycleController.class.getName());

    /** Filename-safe local timestamp stamp shared by the archive-restore and recovery-backup paths. */
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Story 323 review (Recording Reliability book §5.2): shown as a WARNING
     * toast when an in-app door that replaces the open project is refused
     * because a take is still being written to disk or read back from it for
     * its publication. Quitting the application and Close Project are not
     * among those doors: the exit protocol waits for the take to be published
     * before it saves, seals or releases anything (story 333, ruling R1). The
     * message asks the user to keep the application open until it has.
     */
    static final String PROJECT_CHANGE_WHILE_WRITING_MESSAGE =
            "The open project can't be replaced until the last take has been finished"
                    + " — keep the application open until it has";

    /**
     * Story 323 review (Recording Reliability book §5.2): shown as a WARNING
     * toast when an in-app door that replaces the open project is refused
     * because a recording is in flight. The take being recorded belongs to
     * the open project, so the message asks the user to stop the recording
     * first.
     */
    static final String PROJECT_CHANGE_WHILE_RECORDING_MESSAGE =
            "The open project can't be replaced while recording — stop the recording first";

    /** The names of the doors that replace the open project, as the menus name them (PM book §2.4). */
    static final String NEW_PROJECT_DOOR = "New Project";
    /** File ▸ Open Project, and an open from the Project Hub or the Welcome screen. */
    static final String OPEN_PROJECT_DOOR = "Open Project";
    /** File ▸ Import DAWproject Exchange. */
    static final String IMPORT_SESSION_DOOR = "Import DAWproject Exchange";
    /** File ▸ Restore from Archive. */
    static final String RESTORE_FROM_ARCHIVE_DOOR = "Restore from Archive";
    /** The snapshot browser's Restore. */
    static final String RESTORE_SNAPSHOT_DOOR = "Restore Snapshot";
    /** The Welcome screen's {@code Recover session ▸}, and the loads its recovery makes. */
    static final String RECOVER_SESSION_DOOR = "Recover Session";
    /** The migration report's {@code Roll back…}, and the Migration History's {@code Roll back}. */
    static final String ROLL_BACK_MIGRATION_DOOR = "Roll Back Migration";
    /**
     * File ▸ Save (Ctrl+S) of a project never saved (story 333, ruling L4): the
     * pending switch while its location prompt is open and while its folder is
     * created off the FX thread.
     */
    static final String SAVE_PROJECT_DOOR = "Save Project";

    /**
     * File ▸ Save As… (story 333, ruling R5): the pending switch while its
     * chooser is open and while its copy is made, published and opened.
     */
    static final String SAVE_AS_DOOR = "Save As";

    /** File ▸ Close Project (story 333, ruling R10): the close protocol's door that keeps the application open. */
    static final String CLOSE_PROJECT_DOOR = "Close Project";

    /**
     * The application-stop fallback (story 333, ruling R4): the close pending once {@code Application.stop()} ran
     * without the exit protocol, as the log names it — a takeover offer that comes then shows nothing.
     */
    static final String APPLICATION_STOP_DOOR = "Application stop";

    /** The worker a new project's folder is created on, off the FX thread (story 333). */
    static final String PROJECT_CREATE_WORKER = "daw-project-create";

    /**
     * How long a door waits for a save before it offers to stop waiting
     * (story 333, ruling R8): one WARNING with a "Stop waiting" action, once
     * per wait.
     */
    static final Duration DOOR_WAIT_WARNING_DELAY = Duration.ofSeconds(10);

    /**
     * The INFO of a door asked while an archive is being extracted for
     * Restore from Archive, the one switch pending then (ruling K3).
     */
    static final String STILL_EXTRACTING_MESSAGE =
            "Still extracting the archive — Restore from Archive continues when the extraction finishes.";

    /**
     * The INFO of a switch asked while Recover Session scans, asks or works
     * off the FX thread, the one switch pending then (ruling L3).
     */
    static final String STILL_RECOVERING_MESSAGE =
            "Still recovering the session — Recover Session continues when the recovery finishes.";

    /** The scan's worker of Recover Session (ruling L3), as {@link #setBeforeRecoveryWork} names it. */
    static final String RECOVERY_SCAN_WORKER = "daw-recovery-scan";
    /** The journal delete's worker of Recover Session's Restore checkpoint only. */
    static final String RECOVERY_DISCARD_WORKER = "daw-recovery-checkpoint-only";
    /** The replay's worker of Recover Session's Recover everything. */
    static final String RECOVERY_REPLAY_WORKER = "daw-recovery-replay";

    /** The action of {@link #restoredArchiveNotOpenedMessage}: opens the restored project (ruling K3). */
    static final String OPEN_RESTORED_ARCHIVE_ACTION = "Open";

    /**
     * How long a save may run before the strip shows
     * {@link #SAVING_OPERATION_NAME} (PM book §4.5, Persistence Integrity
     * book §5.4 step 3, ruling F7): a faster save shows only its outcome.
     */
    static final Duration SLOW_SAVE_THRESHOLD = Duration.ofMillis(500);

    /**
     * The strip operation of a save still running after {@link #SLOW_SAVE_THRESHOLD} (ruling G4, precedence 1):
     * the name the Saved cell knows a head save by.
     */
    static final String SAVING_OPERATION_NAME = ProjectOperationProgress.HEAD_SAVE_OPERATION_NAME;

    /**
     * The window in which a second door refused while one waits shows its
     * INFO once (Failure Surfacing book §5.4, ruling G2: one toast for
     * repeated clicks).
     */
    static final Duration DOOR_REFUSAL_WINDOW = Duration.ofSeconds(30);

    private static final DateTimeFormatter CHECKPOINT_CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    /** The date a checkpoint of another day than today carries before its time (rulings K1, L1). */
    private static final DateTimeFormatter CHECKPOINT_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    /** The question of the unsaved-changes prompt a door asks. */
    private static final String CONTINUE_QUESTION = "Do you want to save before continuing?";

    /**
     * What a save request does once its outcome reached the FX thread, after
     * the outcome itself was reported.
     */
    @FunctionalInterface
    private interface SaveFollowUp {

        /** Nothing more. */
        SaveFollowUp NONE = (outcome, sameProject) -> { };

        /**
         * FX thread.
         *
         * @param outcome     how the save ended
         * @param sameProject whether the project it saved is still the open one
         */
        void after(SaveOutcome outcome, boolean sameProject);

        /**
         * Whether this follow-up handles a lost lock itself (story 333, ruling
         * 2 OQ-4): the exit's own save hands the {@link LockStolenException} to
         * its run, which shows the exit's takeover dialog, so the save's
         * report shows nothing for it. Asked on the FX thread when the outcome
         * arrived. Every follow-up but the exit's answers {@code false}.
         *
         * @return {@code true} if a lost lock is this follow-up's to report
         */
        default boolean ownsLockLoss() {
            return false;
        }
    }

    /**
     * The unsaved-changes prompt a switch or a close asks with (story 333,
     * ruling R2): one Alert, titled "Unsaved Changes", for the doors and the
     * close protocol, with the header, the content and the labels of the
     * asker. The doors ask {@link #DOOR} — their text and buttons as before.
     * A close asks the R2 prompt ({@code ProjectClosePrompts}), whose Discard
     * stands apart.
     *
     * @param header       the prompt's header
     * @param content      the prompt's content
     * @param saveLabel    the Save button's label
     * @param discardLabel the Discard button's label
     * @param cancelLabel  the Cancel button's label; empty for the platform's own
     *                     Cancel ({@link ButtonType#CANCEL}), as the doors' prompt
     *                     has always shown it
     * @param discardApart whether Discard stands apart, on the left
     *                     ({@link ButtonBar.ButtonData#LEFT}), with Save the default
     *                     button (Enter) and Cancel the cancel button (Escape and
     *                     the window's close box): the close protocol's layout
     * @param owner        the window that owns the prompt, or empty for none
     */
    record UnsavedChangesPrompt(String header, String content, String saveLabel, String discardLabel,
                                Optional<String> cancelLabel, boolean discardApart, Optional<Window> owner) {

        /** The doors' prompt (story 331): the text and buttons they have always shown. */
        static final UnsavedChangesPrompt DOOR = door(CONTINUE_QUESTION);

        UnsavedChangesPrompt {
            Objects.requireNonNull(header, "header must not be null");
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(saveLabel, "saveLabel must not be null");
            Objects.requireNonNull(discardLabel, "discardLabel must not be null");
            Objects.requireNonNull(cancelLabel, "cancelLabel must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
        }

        /**
         * The doors' prompt asking {@code question} — {@link #DOOR}'s, or a
         * restored archive's question (ruling K3).
         *
         * @param question the prompt's content
         * @return the prompt
         */
        static UnsavedChangesPrompt door(String question) {
            return new UnsavedChangesPrompt("You have unsaved changes.", question, "Save", "Discard",
                    Optional.empty(), false, Optional.empty());
        }
    }

    /** The answers of the unsaved-changes prompt. */
    enum UnsavedChangesAnswer { SAVE, DISCARD, CANCEL }

    /**
     * The switch pending now, as the close protocol reads it (story 333;
     * rulings G2, K3, L3, L4, R12 #8, Q3): at most one at a time — a door's
     * own first save is reported as the folder being created, which the door
     * waits for — named as its door is named. Read on the FX thread through
     * {@link #closeReadiness()}; {@link #switchPendingProperty()} is whether
     * one is.
     */
    sealed interface PendingSwitch permits PendingSwitch.None, PendingSwitch.WaitingDoor,
            PendingSwitch.DoorDialogOpen, PendingSwitch.Extracting, PendingSwitch.Recovering,
            PendingSwitch.Installing, PendingSwitch.CreatingFolder, PendingSwitch.SaveAsChoosing,
            PendingSwitch.SaveAsCopying {

        /** The pending switch's name, as a refusal or a progress step names it. */
        String name();

        /** No switch is pending. */
        record None() implements PendingSwitch {
            @Override
            public String name() {
                return "";
            }
        }

        /** A door waits for a save — its own prompt's, or one this controller asked for (ruling G2). */
        record WaitingDoor(String name) implements PendingSwitch {
            public WaitingDoor {
                Objects.requireNonNull(name, "name must not be null");
            }
        }

        /** A door's file dialog is open (ruling L4). */
        record DoorDialogOpen(String name) implements PendingSwitch {
            public DoorDialogOpen {
                Objects.requireNonNull(name, "name must not be null");
            }
        }

        /** An archive is being extracted for Restore from Archive (ruling K3). */
        record Extracting() implements PendingSwitch {
            @Override
            public String name() {
                return RESTORE_FROM_ARCHIVE_DOOR;
            }
        }

        /** Recover Session scans, asks or works off the FX thread (ruling L3). */
        record Recovering() implements PendingSwitch {
            @Override
            public String name() {
                return RECOVER_SESSION_DOOR;
            }
        }

        /** A project is being installed in the open project's place (story 332's installation fence). */
        record Installing(String name) implements PendingSwitch {
            public Installing {
                Objects.requireNonNull(name, "name must not be null");
            }
        }

        /** A never-saved project's first save creates its folder off the FX thread (story 333, ruling L4). */
        record CreatingFolder(String name) implements PendingSwitch {
            public CreatingFolder {
                Objects.requireNonNull(name, "name must not be null");
            }
        }

        /** Save As's chooser is open, or its pre-fill is checked (story 333, ruling R5). */
        record SaveAsChoosing() implements PendingSwitch {
            @Override
            public String name() {
                return SAVE_AS_DOOR;
            }
        }

        /** Save As copies, publishes and opens its copy (story 333, ruling R5). */
        record SaveAsCopying() implements PendingSwitch {
            @Override
            public String name() {
                return SAVE_AS_DOOR;
            }
        }
    }

    /**
     * What the close protocol reads of the open project before it asks
     * anything (story 333; rulings R2, R9, R12 #8), on the FX thread.
     *
     * @param projectName the open project's name
     * @param dirty       whether it has unsaved changes ({@link DawProject#isDirty()})
     * @param hasFolder   whether it has a project folder (it was saved, or opened from one)
     * @param readOnly    whether its folder was opened read-only
     * @param headSavedAt when its {@code project.daw} was last durably written
     *                    ({@link #headSavedAt}); empty for a folder whose head was
     *                    never written by a save of it ("left empty, as it was
     *                    created") and for no folder
     * @param headFailure the failure of its last head save, while it stands
     *                    ({@link #lastHeadFailure})
     * @param lockLost    whether its lock is already known to be lost — its
     *                    last head save met a {@link LockStolenException}, a
     *                    Save As copy's adoption met one, or its lease is
     *                    {@link LockStatus#STOLEN} — known facts
     *                    only, no lock I/O (the studio engineer's stage-4 S8:
     *                    the close then shows ruling R7's exit dialog, not R2)
     * @param knownLockLoss the lost lock as it is known, with its holder: the
     *                    last head save's {@link LockStolenException}, or the
     *                    one a Save As copy's adoption met (ruling 3 Q1, the
     *                    same instance); empty when the loss is known only
     *                    from the lease's status, whose holder is never read
     *                    at GUARD (the studio engineer's stage-6 S1)
     * @param pending     the switch pending now
     * @param today       the strip model's today (ruling L1)
     */
    record CloseReadiness(String projectName, boolean dirty, boolean hasFolder, boolean readOnly,
                          Optional<Instant> headSavedAt, Optional<Throwable> headFailure, boolean lockLost,
                          Optional<LockStolenException> knownLockLoss, PendingSwitch pending, LocalDate today) {
        CloseReadiness {
            Objects.requireNonNull(projectName, "projectName must not be null");
            Objects.requireNonNull(headSavedAt, "headSavedAt must not be null");
            Objects.requireNonNull(headFailure, "headFailure must not be null");
            Objects.requireNonNull(knownLockLoss, "knownLockLoss must not be null");
            Objects.requireNonNull(pending, "pending must not be null");
            Objects.requireNonNull(today, "today must not be null");
        }
    }

    /**
     * What the saves queued before a close reported, once each of them had
     * (story 333; rulings R9 and 2 OQ-4).
     *
     * @param anyFailed whether one of them failed — its 331 ERROR, or its lost lock, is the report
     * @param lockLost  the lost lock one of them met, handed to the close instead of the save's own takeover
     *                  dialog; a lock loss among them governs the close (ruling 2 OQ-4, constraint 3)
     */
    record SavesReport(boolean anyFailed, Optional<LockStolenException> lockLost) {
        SavesReport {
            Objects.requireNonNull(lockLost, "lockLost must not be null");
        }
    }

    /**
     * How the close protocol's own save — the FLUSH of "Save and close" —
     * ended (story 333; rulings R2, R7, R9).
     */
    sealed interface FlushResult permits FlushResult.Saved, FlushResult.LockLost, FlushResult.SaveFailed,
            FlushResult.NotRequested {

        /** The canonical save wrote the head (story 331). */
        record Saved(SaveOutcome.Saved outcome) implements FlushResult {
            public Saved {
                Objects.requireNonNull(outcome, "outcome must not be null");
            }
        }

        /** The save met a lock another session took over, caught as itself (ruling R7). */
        record LockLost(LockStolenException stolen) implements FlushResult {
            public LockLost {
                Objects.requireNonNull(stolen, "stolen must not be null");
            }
        }

        /** The save failed; its 331 ERROR is already shown (ruling R9). */
        record SaveFailed(SaveOutcome.Failed outcome) implements FlushResult {
            public SaveFailed {
                Objects.requireNonNull(outcome, "outcome must not be null");
            }
        }

        /** No save was asked for: the first save's location prompt was cancelled, or it was refused. */
        record NotRequested() implements FlushResult { }
    }

    /**
     * The close protocol's wait for the saves queued before it (story 333,
     * ruling 2 OQ-4): while {@code stillWanted} answers {@code true}, the
     * first lost lock those saves meet is kept here instead of being shown
     * as the save's own takeover dialog. FX-confined.
     */
    private static final class ClosingSavesWait {
        private final BooleanSupplier stillWanted;
        private LockStolenException lockLost;

        private ClosingSavesWait(BooleanSupplier stillWanted) {
            this.stillWanted = stillWanted;
        }

        /** Takes {@code stolen} for the close, when it still waits; the first one governs. FX thread. */
        private boolean takes(LockStolenException stolen) {
            if (!stillWanted.getAsBoolean()) {
                return false;
            }
            if (lockLost == null) {
                lockLost = stolen;
            }
            return true;
        }
    }

    /**
     * The functional dependencies the composition root supplies — story 294
     * replaces the callback-up {@code Host} interface (Control Synchronization
     * Design Book §4.2/§9 "use publish/subscribe, not a callback-up {@code Host}
     * for cross-surface updates") with a record of direct functional deps. The
     * swappable project / undo manager are {@link Supplier}s read live, so a
     * project load is reflected without rebuilding this controller; the action /
     * cascade hooks are {@link Runnable}/{@link Consumer}.
     *
     * <p>The dirty flag is deliberately <em>not</em> a dep: it now lives on
     * {@link DawProject} as the §1.2 "one dirty bit", so this controller reads
     * {@code project().get().isDirty()} and calls {@code markDirty()}/
     * {@code markClean()} directly through the project supplier — the imperative
     * {@code MainController.projectDirty} boolean it used to poke is gone.</p>
     *
     * <p>Story 329 — {@code repaintArrangement} repaints the arrangement canvas
     * (the repaint {@code AudioImportController} also receives): the open
     * project's audio ({@link OpenProjectAudio}) runs it once per drain of
     * loaded clip audio, so waveforms and placeholders follow the load.</p>
     */
    record Deps(
            Supplier<DawProject> project,
            Consumer<DawProject> setProject,
            Supplier<UndoManager> undoManager,
            Consumer<UndoManager> setUndoManager,
            Runnable resetTrackCounters,
            Runnable rebuildHistoryPanel,
            Runnable onProjectUIRebuild,
            Supplier<String> captureLayoutJson,
            Consumer<String> applyLayoutJson,
            Runnable repaintArrangement) {
        Deps {
            Objects.requireNonNull(project, "project must not be null");
            Objects.requireNonNull(setProject, "setProject must not be null");
            Objects.requireNonNull(undoManager, "undoManager must not be null");
            Objects.requireNonNull(setUndoManager, "setUndoManager must not be null");
            Objects.requireNonNull(resetTrackCounters, "resetTrackCounters must not be null");
            Objects.requireNonNull(rebuildHistoryPanel, "rebuildHistoryPanel must not be null");
            Objects.requireNonNull(onProjectUIRebuild, "onProjectUIRebuild must not be null");
            Objects.requireNonNull(captureLayoutJson, "captureLayoutJson must not be null");
            Objects.requireNonNull(applyLayoutJson, "applyLayoutJson must not be null");
            Objects.requireNonNull(repaintArrangement, "repaintArrangement must not be null");
        }
    }

    private final ProjectManager projectManager;
    private final SessionInterchangeController sessionInterchangeController;
    private final NotificationBar notificationBar;
    /**
     * Story 295 — the single §5.5 project-status model. Save / autosave /
     * archive paths report through this; the {@code statusBarLabel} and
     * {@code checkpointLabel} this controller used to poke are gone (the §8
     * rejection of "status text in {@code Label.setText}"). The Session Status
     * Strip binds to this model. Transient action confirmations and errors
     * still go through {@link #notificationBar} (§8: the notification bar is
     * for things the user must see — errors, takeovers, migrations).
     */
    private final ProjectOperationProgress progress;
    private final BorderPane rootPane;
    private final VBox trackListPanel;
    private final Deps deps;
    /** Story 189 — engine for {@code .dawz} archive save/restore. */
    private final ProjectArchiver projectArchiver;
    /** Story 299 — durable user-named copies of the current project document. */
    private final SnapshotStore snapshotStore = new SnapshotStore();
    /**
     * Story 331 — the one atomic-replace writer (Persistence Integrity book
     * §4.1, §2.2) this controller's own project writes go through: the
     * recovered project file and its backup, the restored archive's
     * {@code project.daw} and the migration roll-back's copy. Each runs on
     * the thread it ran on before: the first two on their workers, the
     * roll-back on the FX thread (moving it off is story 373's). The
     * recovered file and the roll-back follow a {@code project.daw} that is a
     * symbolic link to its {@code .daw} file (ruling M3). Read on those
     * threads; a test replaces it ({@link #setFileWriter}).
     */
    private volatile AtomicFileWriter fileWriter = new AtomicFileWriter();
    /**
     * The FX-thread marshalling seam (story 289), injected on the production
     * path and threaded into the modeless {@link TaskProgressIndicator}s. May
     * be {@code null} in a pure-unit context
     * (the compatibility constructor defaults it to
     * {@link FxDispatcher#getDefault()}); {@link #postFx} tolerates the null.
     */
    private final FxDispatcher fxDispatcher;

    /**
     * Story 329 — the FX-confined owner of the open project's audio: the
     * clip rehydration run of the current open, its live missing-assets
     * report, the {@code Loading audio — N of M} strip operation and the
     * report window. Every door that installs a project begins a run;
     * every door that replaces or abandons one retires it
     * ({@link #resetProjectState()}, {@link #dispose()}; an open from disk
     * through {@link OpenProjectAudio#begin}, once the new project parsed).
     */
    private final OpenProjectAudio openProjectAudio;

    /**
     * Story 329 — relink in the open session (decisions D16, D17): what the
     * missing-assets report's {@code Locate file…}, {@code Skip} and
     * {@code Locate folder…} do, against {@link #openProjectAudio}'s report.
     */
    private final AssetRelinkFlow assetRelink;

    /**
     * Story 329 — the check every export, bounce and freeze entry point runs
     * before it renders (decisions D19, D28): refused while a clip it renders
     * loads, confirmed in the render path's words when clips would render
     * silent.
     */
    private final RenderReadinessGuard renderGuard;

    /**
     * Story 296 — supplies the destination <em>parent</em> directory for a
     * brand-new project, replacing the §8/§1.1 silent
     * {@code Files.createTempDirectory} first-save. The default
     * ({@link #promptNewProjectLocation()}) prompts once via a
     * {@link DirectoryChooser} rooted at the §3.1 workspace ({@code ~/DAW
     * Projects}); a {@code null} return means the user cancelled, so no project
     * is created on disk. Tests inject a stub via
     * {@link #setNewProjectLocationPrompt(Supplier)}.
     */
    private Supplier<Path> newProjectLocationPrompt = this::promptNewProjectLocation;

    /**
     * Story 331 (ruling L4) — the Open Project door's folder chooser: the
     * project folder to open, or {@code null} when the user cancelled. The
     * default ({@link #promptProjectToOpen()}) is a {@link DirectoryChooser};
     * tests inject one through {@link #setOpenProjectPrompt(Supplier)}.
     */
    private Supplier<Path> openProjectPrompt = this::promptProjectToOpen;

    /**
     * Story 331 (ruling L4) — the Import DAWproject Exchange door's file
     * chooser: the file to import, or {@code null} when the user cancelled.
     * The default ({@link #promptSessionToImport()}) is a {@link FileChooser};
     * tests inject one through {@link #setImportSessionPrompt(Supplier)}.
     */
    private Supplier<Path> importSessionPrompt = this::promptSessionToImport;

    /**
     * Story 331 (ruling L4) — the Restore from Archive door's first chooser:
     * the {@code .dawz} archive, or {@code null} when the user cancelled. The
     * default ({@link #promptArchiveToRestore()}) is a {@link FileChooser};
     * tests inject one through {@link #setArchiveToRestorePrompt(Supplier)}.
     */
    private Supplier<Path> archiveToRestorePrompt = this::promptArchiveToRestore;

    /**
     * Story 331 (ruling L4) — the Restore from Archive door's second chooser:
     * the folder the restored project's own new folder is created in, or
     * {@code null} when the user cancelled. The default
     * ({@link #promptRestoreParent()}) is a {@link DirectoryChooser}; tests
     * inject one through {@link #setRestoreParentPrompt(Supplier)}.
     */
    private Supplier<Path> restoreParentPrompt = this::promptRestoreParent;

    /**
     * Story 296 — presents the §4.2 Project Hub built by
     * {@link #buildProjectHub()}. The default ({@link #presentProjectHub(ProjectHubView)})
     * opens it in its own themed {@link Stage}; tests inject a capturing
     * presenter via {@link #setProjectHubPresenter(Consumer)} so the headless
     * {@code RecentMenuOpensHubTest} can assert the hub was opened (rather than a
     * filename {@code ContextMenu} built).
     */
    private Consumer<ProjectHubView> projectHubPresenter = this::presentProjectHub;

    /**
     * Story 296 — presents the §4.1 Welcome screen built by
     * {@link #buildWelcomeView()}. The default
     * ({@link #presentWelcome(WelcomeView)}) opens it in its own themed
     * {@link Stage}; tests inject a capturing presenter via
     * {@link #setWelcomePresenter(Consumer)}.
     */
    private Consumer<WelcomeView> welcomePresenter = this::presentWelcome;

    /**
     * Story 298 — the crash-recovery entry point invoked from the Welcome
     * screen's {@code [Recover session ▸]} button, with the project and what
     * dismisses the Welcome window once the recovery may begin (story 331: a
     * refused Recover leaves the window up). The default
     * ({@link #beginRecovery(Path, Runnable)}) scans the project off the FX
     * thread and, when a recovery decision is owed, shows the {@link RecoveryDialog}.
     * Field + setter ({@link #setRecoverAction(BiConsumer)}), no ctor change
     * (the story-296 seam shape).
     */
    private BiConsumer<Path, Runnable> recoverAction = this::beginRecovery;

    /**
     * Story 298 — the discard-recovery action invoked from the Welcome screen's
     * {@code [Discard recovery]} button. The default
     * ({@link #discardRecovery(Path)}) confirms, then deletes the project's
     * {@code journal/} directory off the FX thread.
     */
    private Consumer<Path> discardAction = this::discardRecovery;

    /** Invoked after a background named snapshot write completes. */
    private Runnable namedSnapshotCreatedHook = () -> {};

    /**
     * Story 298 — opens the per-project write-ahead journal after a successful
     * load (set by {@code MainController}); a {@code null} value (the pure-unit
     * default) skips journal wiring.
     */
    private Consumer<Path> journalOpenHook;
    private OpenRecoveryGate openRecoveryGate;
    private Supplier<Runnable> prepareJournalClose = () -> () -> { };
    private Consumer<Path> prepareJournalOpen = _ -> { };
    private OpenRecoveryGate.Result recoveryResult = OpenRecoveryGate.Result.PLAIN;
    private final ReadOnlyBooleanWrapper projectInstallationPending =
            new ReadOnlyBooleanWrapper(this, "projectInstallationPending");
    boolean isProjectInstallationPending() { return projectInstallationPending.get(); }
    ReadOnlyBooleanProperty projectInstallationPendingProperty() {
        return projectInstallationPending.getReadOnlyProperty();
    }

    /**
     * Story 333 — the door whose project is being installed while
     * {@link #projectInstallationPending} holds, as {@link PendingSwitch.Installing}
     * names it; {@code null} otherwise. Written only with the installation
     * fence ({@link #raiseInstallationFence}, {@link #lowerInstallationFence}).
     * FX thread.
     */
    private String installingDoor;

    /**
     * Story 333 (javafx-application-design skill §4, "Use JavaFX Properties for
     * everything observable") — whether a switch is pending or a project is
     * being installed: the fact the close protocol waits on instead of
     * polling. Its one writer is {@link #pendingChanged()}, which every setter
     * of the pending state calls, and which listens to
     * {@link #projectInstallationPending}. FX thread.
     */
    private final ReadOnlyBooleanWrapper switchPending = new ReadOnlyBooleanWrapper(this, "switchPending");

    /** Production wiring: all disk-open doors use the same worker gate and FX installation. */
    void enableRecoveryGate(Supplier<Boolean> enabled, Supplier<Runnable> closeJournal, Consumer<Path> openJournal) {
        prepareJournalClose = Objects.requireNonNull(closeJournal);
        prepareJournalOpen = Objects.requireNonNull(openJournal);
        openRecoveryGate = new OpenRecoveryGate(projectManager, enabled, this::chooseRecoveryOnFx,
                this::writeRecoveredProjectFile);
        projectManager.setRecoveryGate((folder, readOnly, lock) -> {
            beforeRecoveryWork.accept(RECOVERY_SCAN_WORKER);
            recoveryResult = openRecoveryGate.run(folder, readOnly, lock);
        });
    }

    private RecoveryChoice chooseRecoveryOnFx(RecoverySummary summary) {
        CompletableFuture<RecoveryChoice> answer = new CompletableFuture<>();
        postFx(() -> {
            try {
                RecoveryChoice choice = recoveryDialogs.apply(summary).showAndWait().orElse(RecoveryChoice.CANCEL);
                if (choice == RecoveryChoice.INSPECT_EVENTS) showInspectEvents(summary);
                answer.complete(choice);
            } catch (RuntimeException | Error failure) { answer.completeExceptionally(failure); }
        });
        return answer.join();
    }

    private void beginGatedLoad(Path folder, OpenDoor door, long attempt) {
        raiseInstallationFence(installingDoorOf(door));
        startRecoveryWorker(RECOVERY_SCAN_WORKER, () -> {
            ProjectManager.PreparedOpen prepared = null;
            boolean installed = false;
            boolean retiredOutgoing = false;
            boolean outgoingJournalDetached = false;
            try {
                boolean sameDirectory = sameOpenDirectory(folder);
                if (sameDirectory && !(door instanceof OpenDoor.MigrationRollback)) {
                    postFx(() -> {
                        lastLoadResult = false;
                        notificationBar.show(NotificationLevel.INFO, "Project is already open: " + folder);
                    });
                    return;
                }
                if (sameDirectory && door instanceof OpenDoor.MigrationRollback) {
                    Runnable close = callOnFx(prepareJournalClose);
                    outgoingJournalDetached = true;
                    close.run();
                    if (!mayRetireAfterJournalClose(folder)) return;
                    projectManager.getLockManager().verifyHeld();
                    com.benesquivelmusic.daw.core.persistence.journal.JournalState.clear(ProjectContext.forProject(folder));
                    projectManager.closeProject();
                    retiredOutgoing = true;
                }
                OpenDoor selectedDoor = door;
                try {
                    prepared = projectManager.prepareOpen(folder); // target lease leaves outgoing project intact
                } catch (OpenRecoveryGate.ReplayFailed failed) {
                    LOG.log(Level.WARNING, "Recovery failed; preserving evidence and opening the last saved head read-only", failed);
                    prepared = projectManager.prepareReadOnlyOpen(folder);
                    recoveryResult = OpenRecoveryGate.Result.PLAIN;
                    String reason = failureReason(failed) + "; recovery evidence was preserved; fallback is read-only";
                    selectedDoor = failed.writing() ? new OpenDoor.RecoveredWriteFailed(reason)
                            : new OpenDoor.FailedReplayFallback(reason);
                }
                if (!retiredOutgoing) {
                    Runnable closeJournal = callOnFx(() -> {
                        String applied = "The recovery decision for " + folder + " was applied";
                        if (refuseLateLoadWhileATakeIsRecordingOrBeingWritten(applied)) return null;
                        return prepareJournalClose.get();
                    });
                    if (closeJournal == null) return;
                    outgoingJournalDetached = true;
                    closeJournal.run();
                    if (!mayRetireAfterJournalClose(folder)) return;
                    projectManager.closeProject();
                    retiredOutgoing = true;
                }
                projectManager.openProject(folder, prepared); // PARSE only; target lock and recovery already resolved
                OpenDoor actualDoor = recoveryResult.door(selectedDoor);
                // Story 333 — the migration dialog's "Don't show again" marker is read here, off the FX thread.
                boolean migrationDialogSuppressed = migrationDialogSuppressed(folder);
                PreparedLoad loaded = callOnFx(() -> {
                    if (refuseLateLoadWhileATakeIsRecordingOrBeingWritten(
                            "The recovery decision for " + folder + " was applied")) return null;
                    raiseInstallationFence(installingDoorOf(door));
                    headSavedAt = null;
                    progress.checkpointsEnded();
                    return prepareLoadedProject(folder, migrationDialogSuppressed); // REHYDRATE and ACCOUNT before JOURNAL
                });
                if (loaded == null) return;
                if (!projectManager.isReadOnly()) prepareJournalOpen.accept(folder);
                installed = true;
                postFx(() -> {
                    if (attempt != loadAttempts) return;
                    setRecovering(false);
                    try { lastLoadResult = finishPreparedLoad(folder, actualDoor, attempt, true, loaded); }
                    finally { finishOpenAttempt(attempt); }
                });
            } catch (OpenRecoveryGate.Cancelled cancelled) {
                postFx(() -> {
                    if (attempt != loadAttempts) return;
                    finishOpenAttempt(attempt); lastLoadResult = false; reshowWelcomeAfterRecovery();
                });
            } catch (Throwable failure) {
                LOG.log(Level.WARNING, "Project open/recovery failed for " + folder, failure);
                boolean outgoingJournalPaused = outgoingJournalDetached && !retiredOutgoing;
                postFx(() -> {
                    if (attempt != loadAttempts) return;
                    finishOpenAttempt(attempt);
                    lastLoadResult = false;
                    notificationBar.show(NotificationLevel.ERROR, "Open failed: " + failureReason(failure)
                            + (outgoingJournalPaused ? "; " + PAUSED_JOURNAL_MESSAGE : ""));
                });
            } finally {
                if (!installed) {
                    if (retiredOutgoing && prepared != null && projectManager.getLockManager() == prepared.lock()) {
                        projectManager.closeProject();
                    }
                    if (prepared != null) {
                        try { prepared.close(); }
                        catch (IOException failure) { LOG.log(Level.WARNING, "Could not release cancelled target lease", failure); }
                    }
                    postFx(() -> finishOpenAttempt(attempt));
                }
            }
        });
    }

    private static final String PAUSED_JOURNAL_MESSAGE =
            "Recovery evidence was preserved. Journaled persistence is paused until this project is reopened.";

    private boolean mayRetireAfterJournalClose(Path target) {
        return callOnFx(() -> {
            if (!refuseLateLoadWhileATakeIsRecordingOrBeingWritten("The outgoing journal was closed before opening " + target)) {
                return true;
            }
            lastLoadResult = false;
            notificationBar.show(NotificationLevel.WARNING, "Project switch cancelled; " + PAUSED_JOURNAL_MESSAGE);
            return false;
        });
    }

    private void finishOpenAttempt(long attempt) {
        if (attempt != loadAttempts) return;
        setRecovering(false);
        lowerInstallationFence();
    }

    /**
     * Raises the installation fence (story 332): editing, the transport and
     * the project actions are fenced while {@code door}'s project replaces
     * the open one. With {@link #lowerInstallationFence()} the only writer of
     * {@link #projectInstallationPending}, {@link #installingDoor} and the
     * root pane's disable state (pinned by {@code PendingSwitchStateHasOneWriterTest}).
     * FX thread.
     *
     * @param door the door whose project is being installed
     */
    private void raiseInstallationFence(String door) {
        installingDoor = door;
        projectInstallationPending.set(true);
        rootPane.setDisable(true);
    }

    /** Lowers the installation fence ({@link #raiseInstallationFence}). FX thread. */
    private void lowerInstallationFence() {
        installingDoor = null;
        projectInstallationPending.set(false);
        rootPane.setDisable(false);
    }

    /** The door an open through {@code door} installs its project for, as {@link PendingSwitch.Installing} names it. */
    private static String installingDoorOf(OpenDoor door) {
        return switch (door) {
            case OpenDoor.Plain _ -> OPEN_PROJECT_DOOR;
            case OpenDoor.ArchiveRestore _ -> RESTORE_FROM_ARCHIVE_DOOR;
            case OpenDoor.JournalReplay _, OpenDoor.InterruptedSavePromotion _, OpenDoor.RecoveredWriteFailed _,
                 OpenDoor.FailedReplayFallback _ -> RECOVER_SESSION_DOOR;
            case OpenDoor.MigrationRollback _ -> ROLL_BACK_MIGRATION_DOOR;
            case OpenDoor.SnapshotRestore _ -> RESTORE_SNAPSHOT_DOOR;
        };
    }

    private <T> T callOnFx(Supplier<T> work) {
        CompletableFuture<T> answer = new CompletableFuture<>();
        postFx(() -> {
            try { answer.complete(work.get()); }
            catch (RuntimeException | Error failure) { answer.completeExceptionally(failure); }
        });
        return answer.join();
    }

    private boolean sameOpenFolder(Path folder) {
        ProjectMetadata current = projectManager.getCurrentProject();
        return current != null && current.projectPath() != null
                && current.projectPath().toAbsolutePath().normalize().equals(folder.toAbsolutePath().normalize());
    }

    /** Filesystem identity must be checked on a worker before target-lock negotiation. */
    private boolean sameOpenDirectory(Path folder) throws IOException {
        ProjectMetadata current = projectManager.getCurrentProject();
        return current != null && current.projectPath() != null
                && (sameOpenFolder(folder) || Files.isSameFile(current.projectPath(), folder));
    }

    /**
     * Story 323 review — whether a take is still being written, in the sense
     * of {@code TransportController.isTakeBeingWritten()}: a stopped take
     * whose capture thread is still sealing it, or whose audio the storage
     * executor is still reading back from the sealed segments before the
     * take is published (story 324), or a start that was cancelled or
     * failed and is still having its files removed. Set by
     * {@code MainController} through {@link #setTakeBeingWrittenCheck}; the
     * pure-unit default never refuses. FX thread.
     */
    private BooleanSupplier takeBeingWritten = () -> false;

    /**
     * Story 323 review — whether a recording is in flight, set by
     * {@code MainController} through {@link #setRecordingInFlightCheck}; the
     * pure-unit default never refuses. FX thread.
     */
    private BooleanSupplier recordingInFlight = () -> false;

    /**
     * Story 329 (decision D41) — the number of {@link #loadProjectFromPath}
     * calls so far. A migration dialog's Roll back re-enters the method while
     * the outer call waits for the dialog; the outer call, its number no
     * longer the last, then stays quiet. FX thread.
     */
    private long loadAttempts;

    /**
     * Story 329 (decision D41) — what the last {@link #loadProjectFromPath}
     * call to return returned: a call a re-entrant load superseded returns
     * the superseding call's result. FX thread.
     */
    private boolean lastLoadResult;

    /**
     * Story 296 — the single auxiliary window currently hosting a Hub / Welcome
     * surface (opened by {@link #showInOwnedStage(String, Parent)}), or
     * {@code null} when none is open. Tracked so a repeat "Project Hub…" closes
     * the prior window instead of stacking a second orphan over it.
     */
    private Stage auxiliaryStage;

    /**
     * Story 331 — the saves and named-snapshot copies this controller asked
     * the project manager for whose outcome has not reached the FX thread
     * yet, of any project. A door replaces the open project only while it is
     * {@code 0}: otherwise it waits for the save writer to go idle again
     * (ruling G1), so no write this application asked for is still running
     * into the folder whose lock the switch releases. FX thread.
     */
    private int pendingWrites;

    /**
     * Story 331 — the saves of the open project (its
     * {@linkplain ProjectOperationProgress#projectGeneration() generation})
     * still running: the {@link #SAVING_OPERATION_NAME} operation shows only
     * while one runs. Zeroed when a project replaces it. FX thread.
     */
    private int pendingSavesOfOpenProject;

    /**
     * Story 331 (ruling G4) — when the open project's {@code project.daw} was
     * last durably written: the time of its last save that ended
     * {@link SaveOutcome.Saved} while it was the open project, else the
     * opened file's own time, or {@code null} for a project never saved. Set
     * on a {@code Saved} of the open generation and at every replacement
     * ({@link #projectReplaced}), cleared when the open project is abandoned;
     * a snapshot restore, which keeps the project manager's project, carries
     * it over. Never the in-memory metadata's stamp, which every save stamps
     * at its capture and a failed save does not take back; never read back
     * from the strip model, whose keyed write of a save's time may still be
     * pending when a restore begins a new generation. FX thread.
     */
    private Instant headSavedAt;

    /**
     * Story 331 (ruling G13 #7) — the slow-save timer that is pending, or
     * {@code null}: a timer that is no longer this one when it fires does
     * nothing. FX thread.
     */
    private Object slowSaveTimer;

    /** Story 331 — the {@link #SAVING_OPERATION_NAME} operation shown, or {@code null}. FX thread. */
    private ProjectOperationProgress.NamedOperation savingOperation;

    /**
     * Story 331 — runs a task on the FX thread once {@link #SLOW_SAVE_THRESHOLD}
     * has passed; tests replace it to fire the timer when they choose.
     */
    private Consumer<Runnable> slowSaveTimerScheduler = task -> CompletableFuture
            .delayedExecutor(SLOW_SAVE_THRESHOLD.toMillis(), TimeUnit.MILLISECONDS)
            .execute(() -> postFx(task));

    /**
     * Story 331 (ruling G2) — the switch waiting for a save to finish, or for
     * the save its own prompt asked for — a door, or a switch that comes
     * after work off the FX thread ({@link #whenNoAppWriteIsPending}) — or
     * {@code null}: only one switch is pending, and another is refused
     * meanwhile. Written only by {@link #setWaitingDoor} (story 333; like
     * every field of the pending state). FX thread.
     */
    private String waitingDoor;

    /**
     * Story 333 (ruling R8) — the wait of the door that waits for a save
     * now, or {@code null}: created by the door's first wait and kept across
     * its re-waits, until the door switches, is refused, its save fails, or
     * the user stops waiting. Every continuation of the wait checks that its
     * wait is still this one, so a wait that was stopped never switches the
     * project when its save ends. FX thread.
     */
    private DoorWait currentDoorWait;

    /** Story 333 — the number of door waits so far: each {@link DoorWait}'s epoch. FX thread. */
    private long doorWaitEpochs;

    /** Story 333 — the 10 s timer of {@link #currentDoorWait}, or {@code null}. FX thread. */
    private CloseTimers.Cancellable doorWaitTimer;

    /**
     * Story 333 — the clock of the doors' "Stop waiting" offer (ruling R8); a
     * test installs manual timers ({@link #setDoorWaitTimers}).
     */
    private CloseTimers doorWaitTimers;

    /**
     * A door's wait for a save (story 333, ruling R8): the door, its epoch,
     * and — for a late switch that already did its work — what "Stop
     * waiting" reports instead of {@code door.cancelled}, which would call
     * that work cancelled (the studio engineer's stage-4 S3). Compared by
     * identity; a continuation holding a wait that is no longer
     * {@link #currentDoorWait} does nothing.
     *
     * @param door                the waiting door's name
     * @param epoch               the wait's number
     * @param stoppedAfterItsWork a late switch's own not-opened report, or empty for a door that did nothing yet
     */
    private record DoorWait(String door, long epoch, Optional<Runnable> stoppedAfterItsWork) {
        DoorWait {
            Objects.requireNonNull(door, "door must not be null");
            Objects.requireNonNull(stoppedAfterItsWork, "stoppedAfterItsWork must not be null");
        }
    }

    /**
     * Story 333 (the studio engineer's stage-4 N3) — the "Still saving" WARNING
     * the current door wait showed, or {@code null}: the wait's end takes it
     * off the bar while it still shows there. FX thread.
     */
    private String doorWaitWarning;

    /**
     * Story 333 (ruling L4) — the first save of a never-saved project whose
     * folder is being created off the FX thread, the one switch pending from
     * its location prompt's answer until the create's outcome reached the FX
     * thread; {@code null} otherwise. Written only by
     * {@link #setCreatingFolder}. FX thread.
     */
    private FolderBeingCreated creatingFolder;

    /**
     * A first save whose folder is being created (story 333): the door that
     * asked for it, the save's outcome — the one a Ctrl+S during the create
     * is handed too, so it makes no second folder, no second head and no
     * ERROR — and what waits for the save's submission: a named snapshot's
     * copy, queued on the save writer right behind that save in the FX step
     * that submits it. FX-confined.
     *
     * @param door        the door's name, {@link #SAVE_PROJECT_DOOR}
     * @param composed    the first save's outcome
     * @param onSubmitted run with the save's own outcome once it is submitted
     */
    private record FolderBeingCreated(String door, CompletableFuture<SaveOutcome> composed,
                                      List<Consumer<CompletableFuture<SaveOutcome>>> onSubmitted) { }

    /** Where a user's Save As stands while it is the switch pending (story 333, ruling R5). */
    private enum SaveAsPhase { CHOOSING, COPYING }

    /**
     * Story 333 (ruling R5) — a user's Save As, the one switch pending from
     * its start until it ended: choosing its target, or copying, publishing
     * and opening its copy; {@code null} otherwise. Written only by
     * {@link #setSaveAsPhase}. FX thread.
     */
    private SaveAsPhase saveAsPhase;

    /**
     * Story 333 (design §1.4) — the close protocol's own run, the switch
     * pending from the moment the exit or Close Project starts waiting on
     * disk work until it ends; it stays set for the rest of the process
     * after an exit's HIDE, and once the application-stop fallback's own
     * close set it ({@code ExitCoordinator.closeAtTheStop}) or the fallback
     * took over a close that had set it, so a stale continuation is refused:
     * the door's name, or {@code null}. While it is set every door and every
     * later switch is refused at FINE — the window is input-inert then, so
     * only a stale programmatic continuation can reach one — and the close's
     * own nested work (its first-save chooser and create, its Save As copy)
     * runs under it. It is not part of {@link #switchPending}, which the
     * close itself waits on. Written only by {@link #setClosing}. FX thread.
     */
    private String closing;

    /**
     * Story 333 (ruling 2 OQ-4) — the close protocol's wait for the saves
     * queued before it ({@link #whenItsSavesHaveReported}), or {@code null}:
     * while it is wanted, a lost lock any of those saves meets is handed to
     * it instead of the save's own takeover dialog. FX thread.
     */
    private ClosingSavesWait closingSavesWait;

    /**
     * Story 333 (ruling R10) — what runs after Close Project installed the
     * Untitled project and its run ended, when the Welcome window is to show
     * ({@link #showWelcomeAfterClose}): by default
     * {@link #showWelcome()}; {@code MainController} installs its own
     * ({@link #setWelcomeAfterClose}). FX thread.
     */
    private Runnable welcomeAfterClose = this::showWelcome;

    /**
     * Story 333 — File ▸ Save As…, set by {@code MainController}
     * ({@link #setSaveAsFlow}); {@code null} in a pure-unit context, where
     * Save As does nothing. FX thread.
     */
    private SaveAsFlow saveAsFlow;

    /** Story 333 — the close protocol's prompts, the takeover dialog's among them; made on first use. FX thread. */
    private ProjectClosePrompts closePrompts;

    /**
     * Story 333 — what runs on the FX thread once the open project's folder
     * changed without a rebuild after it: a new project's folder was created
     * off the FX thread after the UI was rebuilt for it. {@code MainController}
     * registers the folder's checkpoints with the snapshot browser, opens its
     * session manifest and repaints the lock indicator and labels — what the
     * rebuild did when the folder already existed. A no-op by default.
     */
    private Runnable projectFolderChangedHook = () -> { };

    /**
     * Story 333 (ruling R2, R9) — the failure of the open project's last
     * head save while it stands, or {@code null}: set when a save of the open
     * project, or the creation of its folder, failed; cleared by a head save's
     * success and when a project or a snapshot replaces the open one, as the
     * strip clears its head failure. It is an FX field of its own because the
     * strip's failure state is written through the keyed pulse, so a prompt
     * shown right after a failed save would read the strip stale (memory
     * "async publish then sync re-read is stale"). FX thread.
     */
    private Throwable lastHeadFailure;

    /**
     * Story 333 (ruling 3 Q1; the studio engineer's stage-6 S1) — the lost
     * lock a Save As copy's adoption met, the same instance, while that copy
     * is the open project; {@code null} otherwise. With
     * {@link #lastHeadFailure}'s own {@link LockStolenException} it is the
     * known loss {@link CloseReadiness#knownLockLoss()} reports, so a close
     * names the holder without reading the lock. Set by the adoption,
     * cleared where {@link #lastHeadFailure} is. FX thread.
     */
    private LockStolenException adoptedUnderALostLock;

    /**
     * Story 333 — a monotonic count of the failed head saves of whichever
     * project was open: never reset at a replacement — the close protocol
     * snapshots it and compares the count after the saves it waits for, so
     * only the difference matters. A first save or a new project whose folder
     * could not be created counts as a failed save. FX thread.
     */
    private long failedSavesOfOpenProject;

    /**
     * Story 333 — the Project Manager book's §3.1 workspace
     * ({@code ~/DAW Projects}) as the location prompt's initial directory,
     * when it is a directory: read once, off the FX thread, when this
     * controller is built; {@code null} otherwise.
     */
    private volatile java.io.File workspaceRootIfDirectory;

    /**
     * Story 331 (ruling K3) — an archive is being extracted for Restore from
     * Archive: from the FX step that starts the extraction until its outcome
     * reaches the FX thread, the one switch pending. Written only by
     * {@link #setExtractingArchive}. FX thread.
     */
    private boolean extractingArchive;

    /**
     * Story 331 (ruling L3) — Recover Session is the one switch pending while
     * it scans, while its {@link RecoveryDialog} asks, and while it discards
     * the journal or replays it off the FX thread: from the FX step in
     * {@link #beginRecovery} until each of those hands over to the switch
     * point that follows it — in one FX step — or the recovery ends without
     * a load. Written only by {@link #setRecovering}. FX thread.
     */
    private boolean recovering;

    /**
     * Story 331 (ruling L4) — the door whose file dialog is open: the one
     * switch pending from the door's judgment until its dialog returned
     * nothing, its import read failed, an exception escaped it, or its own
     * switch point or the archive's extraction took it over in one FX step;
     * {@code null} otherwise. Written only by {@link #setDoorDialog}. FX
     * thread.
     */
    private DoorDialog doorDialog;

    /**
     * Story 331 (rulings K3, L4) — what the Restore from Archive door's
     * answer covered: the project open, at its edit revision, at the door's
     * judgment — or, for an extraction a test starts without the door, in
     * the FX step that started it; {@code null} when no extraction started.
     * FX thread.
     */
    private DoorAnswer archiveDoorAnswer;

    /**
     * The project a door's answer covered: the instance open when the door
     * was answered, at its edit revision then (rulings K3, L4).
     */
    private record DoorAnswer(DawProject project, long revision) {

        static DoorAnswer of(DawProject open) {
            return new DoorAnswer(open, open.getEditRevision());
        }

        /** Whether {@code open} is the same instance at the same revision: the answer still covers it. */
        boolean covers(DawProject open) {
            return open == project && open.getEditRevision() == revision;
        }
    }

    /**
     * A door's file dialog, open from the door's judgment (ruling L4): the
     * door, and what its answer covered then. Compared by identity.
     */
    private record DoorDialog(String door, DoorAnswer answer) { }

    /**
     * A switch that replaces the open project as a door's answer covered it
     * (rulings G1, K3, L4), judged in order: (1) {@code refused}, the
     * recording and take refusal; (2) the wait while an app write is
     * pending, then again from (1); (3) when the project is dirty and the
     * answer no longer covers it, the unsaved-changes prompt asking
     * {@code question} — Discard switches, Save makes the one save request
     * and starts again from (1) once it is {@link SaveOutcome.Saved} for the
     * same project, Cancel, a failed save and a cancelled location prompt run
     * {@code notSwitched}.
     *
     * @param name        the switch's name, as a refusal names it
     * @param answered    what the door's answer covered
     * @param refused     step (1); {@code true} ends the switch
     * @param question    the prompt's content text
     * @param switchNow   what replaces the open project
     * @param notSwitched what follows when the user kept the project open
     * @param stoppedAfterItsWork what "Stop waiting" reports for a late switch that already did its work, or
     *                    empty for a door ({@link DoorWait})
     */
    private record AnsweredSwitch(String name, DoorAnswer answered, BooleanSupplier refused, String question,
                                  Runnable switchNow, Runnable notSwitched, Optional<Runnable> stoppedAfterItsWork) {
        AnsweredSwitch {
            Objects.requireNonNull(stoppedAfterItsWork, "stoppedAfterItsWork must not be null");
        }
    }

    /**
     * An extracted archive the late load opens (ruling K3).
     *
     * @param destination the folder it was extracted into
     * @param door        the announcement its load makes
     */
    private record RestoredArchive(Path destination, OpenDoor.ArchiveRestore door) {

        /** What the extraction did, a sentence without its full stop. */
        String alreadyDone() {
            return "The archive was restored to " + destination;
        }
    }

    /** Story 331 — the last refusal INFO a second door showed, and when (nanoTime). FX thread. */
    private String lastDoorRefusal;
    private long lastDoorRefusalNanos;

    /**
     * Story 331 — a test seam run on the FX thread as a waiting door resumes,
     * before it judges the project again (memory "Pin ordering on the acting
     * thread"); a no-op in production.
     */
    private Runnable beforeWaitingDoorResumes = () -> { };

    /**
     * Story 331 (ruling L3) — a test seam run on a recovery worker, with the
     * worker's name, before its work and inside its failure handling: a test
     * holds the worker there to act while Recover Session is the switch
     * pending, or throws to take the worker's failure path; a no-op in
     * production.
     */
    private Consumer<String> beforeRecoveryWork = _ -> { };

    /**
     * Story 331 — builds Recover Session's {@link RecoveryDialog} for the
     * scan's summary, on the FX thread; a test replaces it to make the
     * dialog's construction throw ({@link #setRecoveryDialogs}).
     */
    private Function<RecoverySummary, RecoveryDialog> recoveryDialogs = RecoveryDialog::new;

    ProjectLifecycleController(ProjectManager projectManager,
                               SessionInterchangeController sessionInterchangeController,
                               NotificationBar notificationBar,
                               ProjectOperationProgress progress,
                               BorderPane rootPane,
                               VBox trackListPanel,
                               Deps deps,
                               ProjectArchiver projectArchiver) {
        this(projectManager, sessionInterchangeController, notificationBar,
                progress, rootPane, trackListPanel, deps,
                projectArchiver, FxDispatcher.getDefault());
    }

    ProjectLifecycleController(ProjectManager projectManager,
                               SessionInterchangeController sessionInterchangeController,
                               NotificationBar notificationBar,
                               ProjectOperationProgress progress,
                               BorderPane rootPane,
                               VBox trackListPanel,
                               Deps deps,
                               ProjectArchiver projectArchiver,
                               FxDispatcher fxDispatcher) {
        this.projectManager = Objects.requireNonNull(projectManager, "projectManager must not be null");
        this.sessionInterchangeController = Objects.requireNonNull(sessionInterchangeController,
                "sessionInterchangeController must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.rootPane = Objects.requireNonNull(rootPane, "rootPane must not be null");
        this.trackListPanel = Objects.requireNonNull(trackListPanel, "trackListPanel must not be null");
        this.deps = Objects.requireNonNull(deps, "deps must not be null");
        this.projectArchiver = Objects.requireNonNull(projectArchiver, "projectArchiver must not be null");
        // May be null in a pure-unit context; postFx() / the leaf views fall
        // back to the static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;
        // The drain and the relink results run as keyed work, so the owner
        // and the relink flow need a real dispatcher: the injected one, else
        // the app-scoped default (resolved once, shared by both).
        FxDispatcher audioDispatcher = resolveDispatcher();
        this.openProjectAudio = new OpenProjectAudio(audioDispatcher, progress, notificationBar,
                deps.project(), deps.repaintArrangement(), this::presentMissingAssetsDialog);
        this.assetRelink = new AssetRelinkFlow(openProjectAudio, audioDispatcher, notificationBar,
                deps.repaintArrangement(), this::chooseRelinkFile, this::chooseRelinkFolder,
                message -> confirm(ProjectAudioText.text("missingAssets.confirm.title"), message,
                        ProjectAudioText.text("missingAssets.confirm.button")));
        this.renderGuard = new RenderReadinessGuard(openProjectAudio, notificationBar, this::confirm);
        this.doorWaitTimers = CloseTimers.onFx(fxDispatcher);
        // Story 333 — switchPending follows the installation fence too. The listener re-reads the fence
        // (pendingChanged() calls get()), so it is told of every later change, and lives as long as this
        // controller, which owns both properties.
        projectInstallationPending.addListener(_ -> pendingChanged());
        resolveWorkspaceRootOffTheFxThread();
    }

    /**
     * Reads, on a virtual thread, whether the Project Manager book's §3.1
     * workspace is a directory, so the location prompt never asks the disk
     * on the FX thread (story 333). A thread that cannot start leaves the
     * prompt without an initial directory.
     */
    private void resolveWorkspaceRootOffTheFxThread() {
        try {
            Thread.ofVirtual().name("daw-workspace-root").start(() -> {
                Path workspace = workspaceRoot();
                if (workspace != null && Files.isDirectory(workspace)) {
                    workspaceRootIfDirectory = workspace.toFile();
                }
            });
        } catch (RuntimeException notStarted) {
            LOG.log(Level.FINE, "The workspace root is not read: its worker did not start", notStarted);
        }
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam — the
     * null branch reproduces today's behaviour exactly (story 289).
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
    }

    // ── Project action handlers ──────────────────────────────────────────────

    /**
     * File ▸ Save (Ctrl+S): the one save request (story 331; see
     * {@link #saveOpenProject}). It returns once the project is captured;
     * the write runs on the project manager's save writer, and its outcome
     * reaches the strip and the notification bar on a later FX turn. Save
     * stays enabled while a save runs: a second request is coalesced by the
     * project manager (ruling F2). FX thread.
     */
    void onSaveProject() {
        saveOpenProject(SaveFollowUp.NONE, () -> { });
    }

    /**
     * File ▸ Save As… (story 333, ruling R5) — also the palette's Save
     * Project As… and a save failure's {@code Save As…} action: the open
     * project copied into a new folder, which then becomes the open project
     * in this window ({@link SaveAsFlow}). FX thread; returns at once.
     */
    void onSaveProjectAs() {
        if (saveAsFlow == null) {
            LOG.warning("Save As is not available: no Save As flow is installed");
            return;
        }
        saveAsFlow.start(SaveAsFlow.Prefill.COPY);
    }

    /**
     * Story 333 — what {@link SaveAsFlow} needs of this controller: the Save
     * As as the one switch pending, the recording and take facts, the
     * installation of the adopted copy as the strip's new project and the
     * folder-dependent work that follows a folder change, and the copy's
     * journal open (the gate's, so a failure throws). FX thread, but for
     * the journal's open, which runs on the Save As worker.
     *
     * @return the flow's view of this controller
     */
    SaveAsFlow.Lifecycle saveAsLifecycle() {
        return new SaveAsFlow.Lifecycle() {
            @Override
            public boolean beginSaveAs() {
                if (isProjectInstallationPending()) {
                    LOG.fine("Save As is fenced while a project is being installed");
                    return false;
                }
                if (aSwitchIsPending()) {
                    refuseWhileASwitchIsPending();
                    return false;
                }
                setSaveAsPhase(SaveAsPhase.CHOOSING);
                return true;
            }

            @Override
            public void saveAsCopying(boolean copying) {
                if (saveAsPhase != null) {
                    setSaveAsPhase(copying ? SaveAsPhase.COPYING : SaveAsPhase.CHOOSING);
                }
            }

            @Override
            public void endSaveAs() {
                setSaveAsPhase(null);
            }

            @Override
            public boolean recordingInFlight() {
                return recordingInFlight.getAsBoolean();
            }

            @Override
            public boolean takeBeingWritten() {
                return takeBeingWritten.getAsBoolean();
            }

            @Override
            public void copyAdopted(ProjectMetadata adopted, Optional<LockStolenException> lockLost) {
                projectReplaced(adopted.lastModified());
                adoptedUnderALostLock = lockLost.orElse(null);
                projectFolderChangedHook.run();
            }

            @Override
            public void openJournalOf(Path folder) {
                prepareJournalOpen.accept(folder);
            }
        };
    }

    /**
     * The close protocol's prompts (story 333), owned by the main window:
     * the takeover dialog and the Save As target's refusal among them. Made
     * on first use. FX thread.
     *
     * @return the prompts
     */
    ProjectClosePrompts closePrompts() {
        if (closePrompts == null) {
            closePrompts = new ProjectClosePrompts(this, () -> Optional.ofNullable(mainWindow()));
        }
        return closePrompts;
    }

    /**
     * Prompts for a durable named snapshot, saves, and copies the saved
     * {@code project.daw} into {@code snapshots/} off the FX thread
     * ({@link #createNamedSnapshot}).
     */
    void onCreateNamedSnapshot() {
        if (isProjectInstallationPending()) return;
        TextInputDialog dialog = new TextInputDialog("");
        dialog.setTitle("Snapshot");
        dialog.setHeaderText("Create a named snapshot of the current project.");
        dialog.setContentText("Name:");
        Window owner = rootPane.getScene() == null ? null : rootPane.getScene().getWindow();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        ThemeManager.getDefault().applyTo(dialog.getDialogPane());
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        createNamedSnapshot(result.get());
    }

    /**
     * Testable/action seam for named snapshot creation (story 331, ruling
     * K4). Saves first — through the one save request — and, in the same FX
     * step, queues the copy of {@code project.daw} into {@code snapshots/} on
     * the project manager's save writer right behind that save: so the
     * snapshot holds exactly the bytes that save wrote — the project as it
     * was when the user named it — even when a later Ctrl+S is captured while
     * that save is still being written (it is queued behind the copy, never
     * coalesced into the snapshot's save). The copy is made only if that save
     * ended {@link SaveOutcome.Saved} (ruling G13 #10; a failed save never
     * snapshots the old head under the user's name, and reports only its own
     * ERROR). Nothing is queued when no save was requested — a cancelled
     * location prompt, a project that could not be created. A first save
     * creates the project's folder off the FX thread (story 333): its copy is
     * queued in the FX step that submits that save, right behind it. FX
     * thread.
     */
    void createNamedSnapshot(String name) {
        String snapshotName = name == null || name.trim().isEmpty()
                ? "Snapshot"
                : name.trim();
        saveOpenProject(SaveFollowUp.NONE, () -> { }).ifPresent(save -> copyNamedSnapshotAfter(save, snapshotName));
    }

    /**
     * Queues the copy of the {@code project.daw} {@code save} writes into
     * {@code snapshots/} on the save writer ({@link ProjectManager#runAfterQueuedSaves}),
     * right behind that save; its outcome comes back on the FX thread. The
     * copy counts among the {@linkplain #pendingWrites writes a door waits
     * for}, and "Creating snapshot…" shows, from now until its outcome
     * reaches the FX thread. A first save whose folder is still being created
     * has not queued its save yet: the copy is queued once it has, and not at
     * all when the folder could not be created (story 333). FX thread.
     */
    private void copyNamedSnapshotAfter(CompletableFuture<SaveOutcome> save, String snapshotName) {
        FolderBeingCreated creating = creatingFolder;
        if (creating != null && creating.composed() == save) {
            creating.onSubmitted().add(submitted -> copyNamedSnapshotAfter(submitted, snapshotName));
            return;
        }
        ProjectOperationProgress.NamedOperation snapshotOp =
                new ProjectOperationProgress.NamedOperation("Creating snapshot…");
        progress.addOperation(snapshotOp);
        pendingWrites++;
        projectManager.runAfterQueuedSaves(() -> copyIfItsSaveWasSaved(save, snapshotName))
                .whenComplete((copied, failure) -> {
                    Throwable cause = unwrap(failure);
                    if (cause != null) {
                        LOG.log(Level.WARNING, "Failed to create named snapshot", cause);
                    } else {
                        copied.ifPresent(snapshot -> LOG.info(() -> "Created named snapshot " + snapshot.path()));
                    }
                    postFx(() -> {
                        pendingWrites--;
                        progress.removeOperation(snapshotOp);
                        if (cause != null) {
                            notificationBar.show(NotificationLevel.ERROR,
                                    "Snapshot failed: " + failureReason(cause));
                        } else if (copied.isPresent()) {
                            namedSnapshotCreatedHook.run();
                            notificationBar.show(NotificationLevel.SUCCESS,
                                    "Snapshot saved: " + copied.get().name());
                        }
                    });
                });
    }

    /**
     * The named snapshot's copy, on the save writer right after its own save
     * ended: it reads that save's outcome without waiting — the save writer
     * completes a save's future before it takes the next entry — and copies
     * only on {@link SaveOutcome.Saved} (ruling G13 #10). Otherwise it copies
     * nothing; the save's own ERROR is the report.
     *
     * @return the snapshot, or empty when its save did not end {@code Saved}
     */
    private Optional<SnapshotStore.Snapshot> copyIfItsSaveWasSaved(CompletableFuture<SaveOutcome> save,
                                                                   String snapshotName) throws IOException {
        if (!save.isDone()) {
            LOG.warning(() -> "The save of the snapshot \"" + snapshotName + "\" had not ended when its copy ran;"
                    + " nothing was copied");
            return Optional.empty();
        }
        if (save.isCompletedExceptionally() || !(save.getNow(null) instanceof SaveOutcome.Saved saved)) {
            return Optional.empty();
        }
        Path projectDir = saved.metadata().projectPath();
        if (projectDir == null) {
            throw new IOException("No project file to snapshot");
        }
        return Optional.of(snapshotStore.createSnapshot(projectDir, snapshotName));
    }

    // ── The one save request (story 331) ────────────────────────────────────

    /**
     * Saves the open project — the one save request every save gesture goes
     * through (story 331; Persistence Integrity book §4.2, §5.2, §6.1;
     * rejection §9.8): Save, the Save of the unsaved-changes prompt, Retry and
     * the named snapshot's save. A project that was never saved first asks
     * for its folder, which is created off the FX thread
     * ({@link #saveIntoANewFolder}); a request made while that folder is being
     * created is that first save's request — one folder, one head, no ERROR
     * (story 333). Otherwise {@code beforeCapture} runs, then the live layout
     * is captured into the project and it is handed to
     * {@link ProjectManager#save}, which only captures on this thread. See
     * {@link #submitSave} for what follows. FX thread.
     *
     * @param then          what to do once the outcome reached the FX thread; a
     *                      request coalesced into a first save whose folder is
     *                      being created is told that first save's outcome, in
     *                      a later FX turn
     * @param beforeCapture what runs right before the capture, after any
     *                      chooser (the close protocol stops playback there,
     *                      story 333); Ctrl+S runs nothing
     * @return the save's outcome if a save was requested ({@code then} will
     *         run); empty if a project is being installed, the location prompt
     *         was cancelled, or the project manager had no project to save into
     *         (the failure is reported)
     */
    private Optional<CompletableFuture<SaveOutcome>> saveOpenProject(SaveFollowUp then, Runnable beforeCapture) {
        if (isProjectInstallationPending()) return Optional.empty();
        if (creatingFolder != null) return Optional.of(coalescedIntoTheFirstSave(then));
        if (projectManager.getCurrentProject() == null) {
            return saveIntoANewFolder(then, beforeCapture);
        }
        beforeCapture.run();
        // Story 282 — capture the live layout state into the project model
        // before the capture. {@code null} from the supplier clears the field
        // so legacy projects round-trip byte-identical when the layout manager
        // is not installed.
        return submitSave(deps.project().get(), deps.captureLayoutJson().get(), then);
    }

    /**
     * A save request made while a first save's folder is being created: that
     * first save's request — one folder, one head, no ERROR — whose outcome
     * {@code then} is told, in a later FX turn, as the first save's own
     * follow-up is (stage-4 review N4). FX thread.
     */
    private CompletableFuture<SaveOutcome> coalescedIntoTheFirstSave(SaveFollowUp then) {
        CompletableFuture<SaveOutcome> composed = creatingFolder.composed();
        if (then != SaveFollowUp.NONE) {
            composed.whenComplete((outcome, failure) -> postFx(() -> then.after(outcome != null ? outcome
                    : new SaveOutcome.Failed(unwrap(failure), Optional.empty()), true)));
        }
        return composed;
    }

    /**
     * The first save of a project never saved (story 333; §8 / §1.1, ruling
     * L4). Refused, with the pending switch's INFO, while another switch is
     * pending — a door's file dialog, an archive's extraction, a recovery or
     * a Save As (stage-4 review S3; a door that waits for its own prompt's
     * save is the one asking for it) — it prompts for a real destination
     * once — as {@link #SAVE_PROJECT_DOOR}, the one switch pending while the
     * prompt is open — instead of the silent {@code Files.createTempDirectory}
     * into the system temp dir; a cancelled prompt saves nothing. Then
     * {@code beforeCapture} runs and the create becomes the one switch
     * pending, both before the prompt hands its pending state over, so the
     * pending fact never reads false between them; the folder is created off
     * the FX thread ({@link #createProjectOffTheFxThread}) until
     * {@link #saveIntoTheCreatedFolder} captures the project as it is then
     * and saves it into the folder. The strip's "Saving project…" covers the
     * create as it covers the head write that follows (ruling F7). FX thread.
     *
     * @return the first save's outcome, or empty when it was refused or the prompt was cancelled
     */
    private Optional<CompletableFuture<SaveOutcome>> saveIntoANewFolder(SaveFollowUp then, Runnable beforeCapture) {
        if (doorDialog != null || extractingArchive || recovering || saveAsPhase != null) {
            refuseWhileASwitchIsPending();
            return Optional.empty();
        }
        AtomicReference<Path> chosen = new AtomicReference<>();
        AtomicReference<CompletableFuture<SaveOutcome>> pending = new AtomicReference<>();
        whileItsDialogIsOpen(SAVE_PROJECT_DOOR, _ -> {
            Path parent = newProjectLocationPrompt.get();
            if (parent == null) {
                return;
            }
            chosen.set(parent);
            beforeCapture.run();
            CompletableFuture<SaveOutcome> composed = new CompletableFuture<>();
            setCreatingFolder(new FolderBeingCreated(SAVE_PROJECT_DOOR, composed, new ArrayList<>()));
            pending.set(composed);
        });
        Path parentDirectory = chosen.get();
        CompletableFuture<SaveOutcome> composed = pending.get();
        if (parentDirectory == null || composed == null) {
            return Optional.empty();
        }
        long generation = progress.projectGeneration();
        pendingSavesOfOpenProject++;
        armSlowSaveIndicator(generation);
        createProjectOffTheFxThread(deps.project().get().getName(), parentDirectory).whenComplete(
                (created, failure) -> postFx(() -> saveIntoTheCreatedFolder(created, failure, then, composed,
                        generation)));
        return Optional.of(composed);
    }

    /**
     * The outcome of a first save's folder creation, on the FX thread (story
     * 333): the first save is no longer the switch pending. A folder that
     * could not be created is a failed save — the head's failure state, its
     * ERROR with a Retry for an I/O failure, {@code then} and the first save's
     * outcome {@link SaveOutcome.Failed}. A created folder gets the project
     * captured now, with its layout, and saved into it through
     * {@link #submitSave}; a named snapshot asked for meanwhile queues its
     * copy right behind that save, in this step.
     */
    private void saveIntoTheCreatedFolder(ProjectMetadata created, Throwable failure, SaveFollowUp then,
                                          CompletableFuture<SaveOutcome> composed, long armedGeneration) {
        FolderBeingCreated creating = creatingFolder;
        setCreatingFolder(null);
        if (failure != null) {
            Throwable notCreated = unwrap(failure);
            LOG.log(Level.WARNING, "Failed to create the project folder for its first save", notCreated);
            long generation = progress.projectGeneration();
            createEnded(armedGeneration);
            progress.recordSaveFailed(generation, Instant.now(), notCreated);
            noteHeadSaveFailed(notCreated);
            showSaveFailure("Save failed: the project folder could not be created: "
                    + failureReason(notCreated), notCreated instanceof IOException, generation);
            SaveOutcome.Failed notSaved = new SaveOutcome.Failed(notCreated, Optional.empty());
            then.after(notSaved, true);
            composed.complete(notSaved);
            return;
        }
        LOG.fine(() -> "The first save's folder was created: " + created.projectPath());
        Optional<CompletableFuture<SaveOutcome>> save = submitSave(deps.project().get(),
                deps.captureLayoutJson().get(), completing(composed, then));
        // After the head's save was counted, so the strip's "Saving project…" never settles in between.
        createEnded(armedGeneration);
        if (save.isEmpty()) {
            SaveOutcome.Failed notSaved = new SaveOutcome.Failed(
                    new IllegalStateException("No project is open to save into"), Optional.empty());
            then.after(notSaved, true);
            composed.complete(notSaved);
            return;
        }
        if (creating != null) {
            creating.onSubmitted().forEach(waiting -> waiting.accept(save.get()));
        }
    }

    /**
     * A first save's folder creation ended: it no longer counts among the
     * saves the strip's "Saving project…" covers — unless a project replaced
     * the one it was armed for, which started the count over. FX thread.
     */
    private void createEnded(long armedGeneration) {
        if (armedGeneration == progress.projectGeneration() && pendingSavesOfOpenProject > 0
                && --pendingSavesOfOpenProject == 0) {
            settleSlowSaveIndicator();
        }
    }

    /**
     * {@code then}, then the first save's outcome {@code composed} completed
     * with the same outcome — the side effects before the terminal state.
     */
    private static SaveFollowUp completing(CompletableFuture<SaveOutcome> composed, SaveFollowUp then) {
        return new SaveFollowUp() {
            @Override
            public void after(SaveOutcome outcome, boolean sameProject) {
                try {
                    then.after(outcome, sameProject);
                } finally {
                    composed.complete(outcome);
                }
            }

            @Override
            public boolean ownsLockLoss() {
                return then.ownsLockLoss();
            }
        };
    }

    /**
     * Creates the project folder {@code name} under {@code parentDirectory}
     * on a virtual thread, {@link #PROJECT_CREATE_WORKER} (story 333): the
     * folder, its metadata-only {@code project.daw}, its Recent Projects entry
     * and its lock are disk I/O the FX thread never does
     * ({@link ProjectManager#createProject}). Callable on the FX thread; never
     * waits.
     *
     * @return the new project's metadata, completed on the worker; exceptional
     *         when the folder could not be created or the worker not started
     */
    private CompletableFuture<ProjectMetadata> createProjectOffTheFxThread(String name, Path parentDirectory) {
        CompletableFuture<ProjectMetadata> created = new CompletableFuture<>();
        try {
            Thread.ofVirtual().name(PROJECT_CREATE_WORKER).start(() -> {
                try {
                    created.complete(projectManager.createProject(name, parentDirectory));
                } catch (Throwable notCreated) {
                    created.completeExceptionally(notCreated);
                }
            });
        } catch (RuntimeException | Error notStarted) {
            created.completeExceptionally(notStarted);
        }
        return created;
    }

    /**
     * Hands {@code project} to {@link ProjectManager#save} on this, the FX,
     * thread — where it is only captured — and follows the save to its
     * outcome (story 331):
     * <ul>
     *   <li>the save counts among the {@linkplain #pendingWrites writes a door
     *       waits for} until its outcome reaches the FX thread;</li>
     *   <li>if it is still running after {@link #SLOW_SAVE_THRESHOLD}, the
     *       strip shows {@link #SAVING_OPERATION_NAME} until it ends (ruling
     *       F7) — decided on the FX thread from one FX-owned timer that also
     *       checks the project, so the operation is never left showing
     *       (ruling G13 #7);</li>
     *   <li>the outcome, which completes on the save writer, comes back
     *       through the {@link FxDispatcher}: {@link SaveOutcome.Saved} is the
     *       head's success, {@link SaveOutcome.Failed} the head's failure state
     *       and an ERROR notification, every time (ruling F6) — see
     *       {@link #saveFailureMessage}. An outcome of a project replaced
     *       since changes nothing of the open project (ruling G13 #7).</li>
     * </ul>
     * Nothing here waits: the save writer renders and writes off this thread.
     *
     * @return the save's outcome, completed on the save writer, if the save
     *         was requested ({@code then} will run); empty if the project
     *         manager had no open project to save into (reported as a failed
     *         save)
     */
    private Optional<CompletableFuture<SaveOutcome>> submitSave(DawProject project, String layoutJson,
                                                                 SaveFollowUp then) {
        long generation = progress.projectGeneration();
        boolean readOnly = projectManager.isReadOnly();
        ProjectMetadata open = projectManager.getCurrentProject();
        Path folder = open == null ? null : open.projectPath();
        project.setLayoutJson(layoutJson);
        CompletableFuture<SaveOutcome> outcome;
        try {
            outcome = projectManager.save(project);
        } catch (IllegalStateException notOpen) {
            // The project manager holds no project to save into (a create or an
            // open that failed part-way): nothing was captured.
            reportSaveFailed(generation, new SaveOutcome.Failed(notOpen, Optional.empty()), readOnly, then);
            return Optional.empty();
        }
        pendingWrites++;
        pendingSavesOfOpenProject++;
        armSlowSaveIndicator(generation);
        outcome.whenComplete((ended, failure) -> {
            SaveOutcome result = ended != null ? ended : new SaveOutcome.Failed(unwrap(failure), Optional.empty());
            postFx(() -> saveEnded(generation, readOnly, folder, result, then));
        });
        return Optional.of(outcome);
    }

    /** A save's outcome, on the FX thread (see {@link #submitSave}). */
    private void saveEnded(long generation, boolean readOnly, Path folder, SaveOutcome outcome, SaveFollowUp then) {
        pendingWrites--;
        boolean sameProject = generation == progress.projectGeneration();
        if (sameProject) {
            if (--pendingSavesOfOpenProject == 0) {
                settleSlowSaveIndicator();
            }
            switch (outcome) {
                case SaveOutcome.Saved saved -> {
                    // Story 295 / ruling G4 — the Saved cell shows the new time
                    // and flashes once; no "Project saved" notification.
                    headSavedAt = saved.metadata().lastModified();
                    lastHeadFailure = null;
                    adoptedUnderALostLock = null;
                    progress.recordSaveSucceeded(generation, headSavedAt, ProjectOperationProgress.SaveScope.FULL);
                    LOG.info("Project saved successfully");
                }
                case SaveOutcome.Failed failed -> reportSaveFailed(generation, failed, readOnly, then);
            }
        } else {
            switch (outcome) {
                case SaveOutcome.Saved _ -> LOG.info(() -> "A save of " + folder + ", which another project has"
                        + " replaced since, was written");
                case SaveOutcome.Failed failed -> {
                    // Its project is no longer open: the open project's strip is not
                    // touched, but the user still learns that it was not saved.
                    LOG.log(Level.WARNING, failed.cause(), () -> "A save of " + folder
                            + ", which another project has replaced since, failed");
                    notificationBar.show(NotificationLevel.ERROR,
                            saveFailureMessage(failed, readOnly, folder, progress.getToday()));
                }
            }
        }
        then.after(outcome, sameProject);
    }

    /**
     * A failed save of the open project (story 331; rulings F6, G9, G13 #6,
     * J3): the head's failure state, which only a head save success or a
     * project replacement clears — on the strip and in {@link #lastHeadFailure}
     * (story 333) — one more in {@link #failedSavesOfOpenProject}, and an
     * ERROR notification, every time, with a Retry only for an I/O failure —
     * not on a read-only project, where a retry cannot succeed — and
     * {@code Save As…} otherwise (story 333, the studio engineer's ruling
     * Q1/Q5). A lock another session took over is caught as itself, never as
     * an {@link IOException} (Persistence Integrity book §9.14), and has no
     * ERROR: its follow-up reports it when it owns it (the exit's save); a
     * close that still waits for the saves queued before it takes it over,
     * and shows its own exit dialog (ruling 2 OQ-4 — ownership is decided
     * here, on the FX thread, when the outcome arrived); otherwise the
     * takeover dialog asks, in a later FX turn — after the save's follow-up
     * ran — whether to keep the work in a new project
     * ({@link #offerSaveAsAfterTakeover}; ruling R7). FX thread.
     */
    private void reportSaveFailed(long generation, SaveOutcome.Failed failed, boolean readOnly, SaveFollowUp then) {
        Throwable cause = failed.cause();
        LOG.log(Level.WARNING, cause, () -> "Failed to save project");
        progress.recordSaveFailed(generation, Instant.now(), cause);
        noteHeadSaveFailed(cause);
        if (cause instanceof LockStolenException stolen) {
            if (!then.ownsLockLoss() && !takenOverByTheClose(stolen)) {
                postFx(() -> offerSaveAsAfterTakeover(generation, stolen));
            }
            return;
        }
        boolean retry = cause instanceof IOException && !readOnly;
        showSaveFailure(saveFailureMessage(failed, readOnly, null, progress.getToday()), retry, generation);
    }

    /**
     * A save of the open project met a lock another session took over
     * (story 333, ruling R7, its Ctrl+S variant): the blocking takeover
     * dialog — {@code Save As…} keeps the work in a new project
     * ({@link SaveAsFlow}, pre-filled {@code <folder>-takeover-<stamp>});
     * {@code Not now} leaves the project dirty, its head failure standing and
     * the lock cell showing the steal, and says nothing more. Every save that
     * meets the lost lock asks again: the dialog is not rate-limited. A save
     * of a project replaced since asks nothing, and nor does one whose offer
     * runs while a close is pending ({@link #closing}): an interactive close
     * meets the lost lock itself, in the takeover dialog whose Save As can
     * run — at GUARD when the loss is known, or at its FLUSH (ruling R7) —
     * where a Save As started here would be refused while the close runs;
     * after an exit's HIDE, and while the application-stop fallback runs,
     * nothing may ask (ruling 4 Q6.2 item 4), and a dialog opened inside one
     * of {@code Application.stop()}'s nested waits would hold that wait open
     * (story 333, the stage-8 review B1; the studio engineer's final ruling
     * F1). FX thread, outside a pulse.
     *
     * @param generation the strip generation of the project the save was of
     * @param stolen     what the save's lock check found
     */
    private void offerSaveAsAfterTakeover(long generation, LockStolenException stolen) {
        if (generation != progress.projectGeneration()) {
            return;
        }
        String pending = closing;
        if (pending != null) {
            LOG.fine(() -> "The takeover dialog was not shown while " + pending + " closes the open project");
            return;
        }
        ProjectClosePrompts prompts = closePrompts();
        ProjectClosePrompts.TakenOverAnswer answer = prompts.takenOver(
                prompts.takenOverOnSave(deps.project().get().getName(), stolen.currentHolder()));
        if (answer == ProjectClosePrompts.TakenOverAnswer.SAVE_AS && generation == progress.projectGeneration()) {
            if (saveAsFlow == null) {
                LOG.warning("Save As is not available: no Save As flow is installed");
                return;
            }
            saveAsFlow.start(SaveAsFlow.Prefill.TAKEOVER);
        }
    }

    /**
     * A head save of the open project failed — a save, or the creation of a
     * first save's or a new project's folder (story 333): its failure stands
     * ({@link #lastHeadFailure}) and the failed saves count one more
     * ({@link #failedSavesOfOpenProject}). FX thread.
     */
    private void noteHeadSaveFailed(Throwable cause) {
        lastHeadFailure = cause;
        failedSavesOfOpenProject++;
    }

    /**
     * Shows a save's ERROR with its one action (story 333, the studio
     * engineer's ruling Q1): a Retry of the open project's save when
     * {@code retry}, else {@code Save As…} — File ▸ Save As… of the open
     * project, read-only included. Each belongs to the project that failed
     * to save: in a later project it does nothing. FX thread.
     */
    private void showSaveFailure(String message, boolean retry, long generation) {
        if (retry) {
            notificationBar.show(NotificationLevel.ERROR, message, "Retry", () -> {
                // A Retry belongs to the project that failed to save.
                if (generation == progress.projectGeneration()) {
                    onSaveProject();
                }
            });
        } else {
            notificationBar.show(NotificationLevel.ERROR, message, ProjectCloseText.text("saveFailure.saveAs"), () -> {
                // So does its Save As.
                if (generation == progress.projectGeneration()) {
                    onSaveProjectAs();
                }
            });
        }
    }

    /**
     * The ERROR of a failed save (rulings G9, G13 #6, J3): the cause; for a
     * lock another session took over, its holder; and, unless the lock was
     * lost or the project was opened read-only — where no safety copy is
     * ever made — where the work is: the safety checkpoint named ("Your work
     * is in checkpoint 014 (14:32)."), a safety copy still being written, or
     * none written. A safety checkpoint of another day than {@code today}
     * carries its date (ruling K1).
     *
     * @param failed   the outcome
     * @param readOnly whether the project was opened read-only
     * @param folder   the folder of a project no longer open, named in the
     *                 message, or {@code null} for the open project
     * @param today    the strip model's today (ruling L1)
     * @return the message
     */
    static String saveFailureMessage(SaveOutcome.Failed failed, boolean readOnly, Path folder, LocalDate today) {
        String failedSave = folder == null || folder.getFileName() == null ? "Save failed: "
                : "Saving " + folder.getFileName() + " failed: ";
        Throwable cause = failed.cause();
        if (cause instanceof LockStolenException stolen) {
            return failedSave + holderOf(stolen.currentHolder()) + " — nothing was written";
        }
        if (readOnly) {
            return failedSave + failureReason(cause);
        }
        String reason = withoutFullStop(failedSave + failureReason(cause)) + ".";
        Optional<Path> safety = failed.safetyCheckpoint();
        if (safety.isPresent()) {
            return reason + " Your work is in " + checkpointName(safety.get(), today) + ".";
        }
        if (failed.safetyCopyStillWriting()) {
            return reason + " A safety copy of your work is still being written.";
        }
        return reason + " No safety copy could be written.";
    }

    /** Who holds a lock that was taken over (ruling G13 #6: the message names {@code currentHolder()}). */
    private static String holderOf(ProjectLock holder) {
        if (holder == null) {
            return "the project's lock was removed, so another session may have taken the project over";
        }
        return "the project was taken over by " + holder.user() + " on " + holder.hostname()
                + " (process " + holder.pid() + ")";
    }

    /**
     * {@code "checkpoint 014 (14:32)"} for a checkpoint file (ruling G9): its
     * index and the time in its name — with the date first,
     * {@code "checkpoint 047 (2026-10-22 18:10)"}, when it is of another day
     * than {@code today}, as the strip's cell and tooltip and the autosave
     * warning give it (rulings K1, L1); the file name when it is not one.
     *
     * @param checkpoint the checkpoint file
     * @param today      the strip model's today
     * @return how the notification names it
     */
    static String checkpointName(Path checkpoint, LocalDate today) {
        String fileName = checkpoint.getFileName().toString();
        return CheckpointFileName.parse(fileName)
                .map(name -> "checkpoint " + String.format(Locale.ROOT, "%03d", name.index()) + " ("
                        + (name.stamp().toLocalDate().equals(today) ? "" : CHECKPOINT_DAY.format(name.stamp()) + " ")
                        + CHECKPOINT_CLOCK.format(name.stamp()) + ")")
                .orElse("the checkpoint " + fileName);
    }

    private static String withoutFullStop(String text) {
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /**
     * Arms the slow-save timer of a save of {@code generation}, unless one is
     * pending or the operation already shows (story 331, ruling G13 #7). FX
     * thread.
     */
    private void armSlowSaveIndicator(long generation) {
        if (savingOperation != null || slowSaveTimer != null) {
            return;
        }
        Object timer = new Object();
        slowSaveTimer = timer;
        slowSaveTimerScheduler.accept(() -> slowSaveTimerFired(timer, generation));
    }

    /**
     * The slow-save timer fired, on the FX thread: the operation shows only if
     * this is still the pending timer, the project it was armed for is still
     * open and one of its saves still runs.
     */
    private void slowSaveTimerFired(Object timer, long generation) {
        if (timer != slowSaveTimer) {
            return;
        }
        slowSaveTimer = null;
        if (generation != progress.projectGeneration() || pendingSavesOfOpenProject == 0) {
            return;
        }
        savingOperation = new ProjectOperationProgress.NamedOperation(SAVING_OPERATION_NAME);
        progress.addOperation(savingOperation);
    }

    /** No save of the open project runs any more, or it was replaced: no timer, no operation. FX thread. */
    private void settleSlowSaveIndicator() {
        slowSaveTimer = null;
        if (savingOperation != null) {
            progress.removeOperation(savingOperation);
            savingOperation = null;
        }
    }

    /**
     * A project replaced the open one and ended its checkpoints (story 331,
     * rulings G13 #4, M4): the strip's head and checkpoint states, its
     * countdown and the autosave failure streak start over, the Saved cell's
     * time is {@code lastSaved}, and nothing still pending of the replaced
     * project's saves shows on the strip. FX thread.
     *
     * @param lastSaved the replacing project's {@code ProjectMetadata.lastModified},
     *                  or {@code null} when it was never saved
     */
    private void projectReplaced(Instant lastSaved) {
        headSavedAt = lastSaved;
        lastHeadFailure = null;
        adoptedUnderALostLock = null;
        progress.resetForProject(lastSaved);
        settleTheReplacedProjectsSaves();
    }

    /**
     * A snapshot of the open project replaced it, and the project manager's
     * project and its checkpoints run on (story 331, rulings G4, G13 #4, M4):
     * the strip's head and checkpoint states and the streak start over, the
     * countdown runs on, and the Saved cell's time stays when
     * {@code project.daw} was last durably written ({@link #headSavedAt}) —
     * never the stamp of a save that failed. FX thread.
     */
    private void snapshotReplacedTheOpenProject() {
        lastHeadFailure = null;
        adoptedUnderALostLock = null;
        progress.resetForSnapshotRestore(headSavedAt);
        settleTheReplacedProjectsSaves();
    }

    /** Nothing still pending of the replaced project's saves shows on the strip. FX thread. */
    private void settleTheReplacedProjectsSaves() {
        pendingSavesOfOpenProject = 0;
        settleSlowSaveIndicator();
    }

    /**
     * The opened file's own time: the project manager's open project's
     * {@code lastModified}, read right after {@link ProjectManager#openProject}
     * and before any save of it stamps it at its capture; {@code null} when
     * none is open.
     */
    private Instant openedFileLastSaved() {
        ProjectMetadata open = projectManager.getCurrentProject();
        return open == null ? null : open.lastModified();
    }

    /** {@code failure} without the {@link CompletionException} a dependent stage may wrap it in. */
    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException wrapped && wrapped.getCause() != null
                ? wrapped.getCause() : failure;
    }

    /** A failure's message, or its simple class name when it has none, so the user never reads "null". */
    static String failureReason(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /**
     * File ▸ New Project, through the project-close gate
     * ({@link #whenProjectMayClose}). FX thread.
     */
    void onNewProject() {
        newProject(() -> { });
    }

    /**
     * {@link #onNewProject()}, running {@code proceeding} first in the gate's
     * proceed: the Welcome window's New dismisses the window there, so a
     * refused New leaves it up. FX thread.
     */
    private void newProject(Runnable proceeding) {
        whenProjectMayClose(NEW_PROJECT_DOOR, () -> {
            proceeding.run();
            whileItsDialogIsOpen(NEW_PROJECT_DOOR, this::createNewProject);
        });
    }

    /**
     * {@link #onNewProject()} once the open project may close: the location
     * prompt — the door the one switch pending while it is open — then the
     * new project, past the switch point and as the door's answer covered
     * the project ({@link #switchAfterItsDialog}). FX thread.
     */
    private void createNewProject(DoorDialog dialog) {
        // §8 / §1.1 — prompt for a real destination once, then write the project
        // there immediately. A null return means the user cancelled, so we do
        // NOT silently create an Untitled project that would later first-save
        // into the system temp directory (the §8 rejection of "silent first-save
        // into /tmp"). createProject() writes project.daw, acquires the lock and
        // starts the heartbeat/autosave on the chosen directory.
        Path parentDirectory = newProjectLocationPrompt.get();
        if (parentDirectory == null) {
            return;
        }
        switchAfterItsDialog(dialog, () -> createNewProjectIn(parentDirectory));
    }

    /** Replaces the open project with a new one created in {@code parentDirectory}. FX thread. */
    private void createNewProjectIn(Path parentDirectory) {
        replaceAfterJournalClose(NEW_PROJECT_DOOR, attempt -> createNewProjectAfterClose(parentDirectory, attempt));
    }

    /**
     * New Project once the open project was closed (story 333): the new
     * project is installed on the FX thread — the live project, a fresh undo
     * history, the rebuilt UI and the built-in Default layout — and only then,
     * as this step's last statement, its folder's creation starts off the FX
     * thread ({@link #createProjectOffTheFxThread}), so the rebuild never reads
     * a project manager the create is filling in; under the installation
     * fence, which stays up until the replacement finishes
     * ({@link #finishReplacement}); without the recovery gate ({@code attempt}
     * {@code -1}) this step raises the fence itself. The create's outcome
     * continues in {@link #newProjectFolderCreated}. FX thread.
     *
     * @param parentDirectory where the new project's folder is created
     * @param attempt         the replacement's attempt, or {@code -1} without the recovery gate
     */
    private void createNewProjectAfterClose(Path parentDirectory, long attempt) {
        long installation = attempt >= 0L ? attempt : raiseUngatedInstallationFence(NEW_PROJECT_DOOR);
        DawProject newProject = installFreshUntitledProject();
        // Last (stage-4 review S1): the rebuild above read the project manager before the create touched it.
        createProjectOffTheFxThread(newProject.getName(), parentDirectory).whenComplete((created, failure) ->
                postFx(() -> newProjectFolderCreated(newProject, created, failure, installation)));
    }

    /**
     * Installs a fresh in-memory Untitled project in the window: the
     * replacement New Project and Close Project share (ruling R10, "the
     * same replacement New performs, without creating a folder") — the live
     * project, a new undo history, the rebuilt UI (which retires the
     * outgoing transport controller and the closed project's plug-ins and
     * their editors) and the built-in Default layout, so the closed
     * project's named layouts don't leak into the next one (story 282; the
     * studio engineer's stage-6 S4 and stage-7 S3). FX thread.
     *
     * @return the Untitled project installed
     */
    private DawProject installFreshUntitledProject() {
        DawProject untitled = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        deps.setProject().accept(untitled);
        projectReplaced(null);
        deps.setUndoManager().accept(new UndoManager());
        deps.rebuildHistoryPanel().run();
        deps.resetTrackCounters().run();
        rebuildUI();
        // Story 282 — Reset layouts to the built-in Default so that a
        // previously opened project's saved layouts don't leak into the
        // next project.
        deps.applyLayoutJson().accept(null);
        return untitled;
    }

    /**
     * Raises the installation fence of a replacement made without the
     * recovery gate (story 333): a new project's folder is created off the
     * FX thread, so editing and the project actions stay fenced until the
     * replacement finishes. FX thread.
     *
     * @return the replacement's attempt, which {@link #finishReplacement} ends
     */
    private long raiseUngatedInstallationFence(String door) {
        long attempt = ++loadAttempts;
        raiseInstallationFence(door);
        return attempt;
    }

    /**
     * New Project's folder creation ended, on the FX thread (story 333). A
     * folder that could not be created leaves the in-memory project, so the
     * UI is still usable, and says so. A created folder first tells
     * {@code MainController} the open project's folder changed
     * ({@link #projectFolderChangedHook}: its checkpoints are registered with
     * the snapshot browser, its session manifest opens, the lock indicator and
     * the labels follow), then persists the full initial state through the one
     * save request — {@code createProject} wrote a metadata-only file first;
     * the new project's layout is the built-in Default, so none is stored —
     * whose outcome is reported like any save's, and opens the new project's
     * journal once it is saved.
     */
    private void newProjectFolderCreated(DawProject newProject, ProjectMetadata created, Throwable failure,
                                         long attempt) {
        if (attempt != loadAttempts || deps.project().get() != newProject) {
            // Another replacement took this one's place since (unreachable under the fence): nothing of it runs
            // but the end of its attempt, which does nothing for an attempt no longer the current one.
            LOG.fine(() -> "The folder of a new project that was replaced since was "
                    + (failure != null ? "not created" : "created: " + created.projectPath()));
            finishReplacement(attempt);
            return;
        }
        if (failure != null) {
            Throwable notCreated = unwrap(failure);
            progress.recordSaveFailed(progress.projectGeneration(), Instant.now(), notCreated);
            noteHeadSaveFailed(notCreated);
            notificationBar.show(NotificationLevel.ERROR, "Could not create project: " + failureReason(notCreated));
            LOG.log(Level.WARNING, "Failed to create new project on disk", notCreated);
            finishReplacement(attempt);
            return;
        }
        projectFolderChangedHook.run();
        Path folder = created.projectPath();
        Optional<CompletableFuture<SaveOutcome>> initialSave = submitSave(newProject, null, (outcome, sameProject) -> {
            if (sameProject && outcome instanceof SaveOutcome.Saved) {
                openCreatedJournal(folder, attempt);
            } else {
                finishReplacement(attempt);
            }
        });
        if (initialSave.isEmpty()) finishReplacement(attempt);
    }

    private void openCreatedJournal(Path folder, long attempt) {
        if (openRecoveryGate == null) {
            notifyJournalOpen(folder);
            notificationBar.show(NotificationLevel.SUCCESS, "New project created");
            finishReplacement(attempt);
            return;
        }
        Thread.ofVirtual().name("daw-new-project-journal").start(() -> {
            try {
                prepareJournalOpen.accept(folder);
                postFx(() -> {
                    if (attempt == loadAttempts) notificationBar.show(NotificationLevel.SUCCESS, "New project created");
                    finishReplacement(attempt);
                });
            } catch (RuntimeException | Error failure) {
                postFx(() -> {
                    notificationBar.show(NotificationLevel.ERROR, "New project created; journaled persistence is paused until this project is reopened. " + failureReason(failure));
                    finishReplacement(attempt);
                });
            }
        });
    }

    /**
     * File ▸ Open Project, through the project-close gate
     * ({@link #whenProjectMayClose}). FX thread.
     */
    void onOpenProject() {
        openProject(() -> { });
    }

    /** {@link #onOpenProject()}, running {@code proceeding} first in the gate's proceed (see {@link #newProject}). */
    private void openProject(Runnable proceeding) {
        whenProjectMayClose(OPEN_PROJECT_DOOR, () -> {
            proceeding.run();
            whileItsDialogIsOpen(OPEN_PROJECT_DOOR, this::chooseAndOpenProject);
        });
    }

    /**
     * {@link #onOpenProject()} once the open project may close: the chooser
     * — the door the one switch pending while it is open — then the load,
     * past the switch point and as the door's answer covered the project
     * ({@link #switchAfterItsDialog}). FX thread.
     */
    private void chooseAndOpenProject(DoorDialog dialog) {
        Path projectDir = openProjectPrompt.get();
        if (projectDir == null) {
            return;
        }
        switchAfterItsDialog(dialog, () -> loadProjectFromPath(projectDir));
    }

    /** Default {@link #openProjectPrompt}: a {@link DirectoryChooser} over the main window. */
    private Path promptProjectToOpen() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Project");
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File selected = chooser.showDialog(stage);
        return selected == null ? null : selected.toPath();
    }

    /**
     * Opens the §4.2 Project Hub — the replacement for the old filename
     * {@code ContextMenu} of recent projects (§1.4; §8 "a Recent Projects menu
     * that is just filenames"). Appendix A: "menu retains a 'Project Hub…'
     * item". The File-menu entry now opens this full-window browser, which
     * surfaces last-opened, size-on-disk, a health badge, search, and a
     * per-project detail strip instead of bare filenames.
     */
    void onRecentProjects() {
        projectHubPresenter.accept(buildProjectHub());
    }

    // ── DAWproject Exchange Import/Export ────────────────────────────────────

    /**
     * File ▸ Import DAWproject Exchange, through the project-close gate
     * ({@link #whenProjectMayClose}). FX thread.
     */
    void onImportSession() {
        importSession(() -> { });
    }

    /** {@link #onImportSession()}, running {@code proceeding} first in the gate's proceed (see {@link #newProject}). */
    private void importSession(Runnable proceeding) {
        whenProjectMayClose(IMPORT_SESSION_DOOR, () -> {
            proceeding.run();
            whileItsDialogIsOpen(IMPORT_SESSION_DOOR, this::chooseAndImportSession);
        });
    }

    /**
     * {@link #onImportSession()} once the open project may close: the
     * chooser and the import's read — the door the one switch pending
     * meanwhile — then the replacement, past the switch point and as the
     * door's answer covered the project ({@link #switchAfterItsDialog}). FX
     * thread.
     */
    private void chooseAndImportSession(DoorDialog dialog) {
        Path source = importSessionPrompt.get();
        if (source == null) {
            return;
        }
        SessionImportResult result;
        try {
            result = sessionInterchangeController.importSession(source);
        } catch (IOException e) {
            notificationBar.show(NotificationLevel.ERROR,
                    "Import failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to import session", e);
            return;
        }
        switchAfterItsDialog(dialog, () -> installImportedSession(source, result));
    }

    /** Default {@link #importSessionPrompt}: a {@link FileChooser} over the main window. */
    private Path promptSessionToImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import DAWproject Exchange");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("DAWproject Files", "*.dawproject", "*.xml"));
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File selected = chooser.showOpenDialog(stage);
        return selected == null ? null : selected.toPath();
    }

    /** Replaces the open project with the session {@code result} imported from {@code source}. FX thread. */
    private void installImportedSession(Path source, SessionImportResult result) {
        replaceAfterJournalClose(IMPORT_SESSION_DOOR, attempt -> {
            try { installImportedSessionAfterClose(source, result); }
            finally { finishReplacement(attempt); }
        });
    }

    private void installImportedSessionAfterClose(Path source, SessionImportResult result) {
        deps.setProject().accept(new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY));
        projectReplaced(null);
        deps.setUndoManager().accept(new UndoManager());
        deps.rebuildHistoryPanel().run();
        deps.resetTrackCounters().run();
        sessionInterchangeController.applySessionData(result.sessionData(), deps.project().get());
        deps.project().get().markDirty();
        rebuildUI();

        String summary = sessionInterchangeController.buildImportSummary(result);
        Alert summaryDialog = new Alert(Alert.AlertType.INFORMATION);
        summaryDialog.setTitle("Import Summary");
        summaryDialog.setHeaderText("DAWproject Exchange imported successfully");
        summaryDialog.setContentText(summary);
        ThemeManager.getDefault().applyTo(summaryDialog.getDialogPane());
        summaryDialog.showAndWait();

        notificationBar.show(NotificationLevel.SUCCESS,
                "Imported DAWproject Exchange: " + result.sessionData().projectName());
        LOG.info("Imported DAWproject session from " + source);
    }

    void onExportSession() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Export DAWproject Exchange");
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }
        try {
            SessionExportResult result = sessionInterchangeController.exportSession(
                    deps.project().get(), selected.toPath(), deps.project().get().getName());

            String message = "DAWproject Exchange exported to " + result.outputPath().getFileName();
            if (!result.warnings().isEmpty()) {
                message += " (" + result.warnings().size() + " warnings)";
            }
            notificationBar.show(NotificationLevel.SUCCESS, message);

            if (!result.warnings().isEmpty()) {
                StringBuilder warningText = new StringBuilder("Export completed with warnings:\n\n");
                for (String warning : result.warnings()) {
                    warningText.append("  \u2022 ").append(warning).append("\n");
                }
                Alert warningDialog = new Alert(Alert.AlertType.WARNING);
                warningDialog.setTitle("Export Warnings");
                warningDialog.setHeaderText("DAWproject Exchange exported with warnings");
                warningDialog.setContentText(warningText.toString());
                ThemeManager.getDefault().applyTo(warningDialog.getDialogPane());
                warningDialog.showAndWait();
            }

            LOG.info("Exported DAWproject Exchange to " + result.outputPath());
        } catch (IOException e) {
            notificationBar.show(NotificationLevel.ERROR,
                    "Export failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to export DAWproject Exchange", e);
        }
    }

    // ── Project Archive (Story 189 — Project Archive (ZIP With Assets)) ────

    /**
     * "File → Archive Project…" — bundles the current project together
     * with every referenced asset into a single self-contained
     * {@code .dawz} ZIP at a user-chosen location.
     *
     * <p>Walks every {@link AudioClip} and {@link SoundFontAssignment}
     * referenced by the project to detect missing asset files, asks for
     * per-asset decisions, then runs the ZIP I/O on a background virtual
     * thread. Progress is published to the status strip, not a modal.</p>
     */
    void onArchiveProject() {
        DawProject current = deps.project().get();
        if (current == null) {
            notificationBar.show(NotificationLevel.ERROR, "No project to archive");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Archive Project");
        chooser.setInitialFileName(safeFileName(current.getName()) + ProjectArchiver.ARCHIVE_EXTENSION);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("DAW Archive (*.dawz)", "*.dawz"));
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File selected = chooser.showSaveDialog(stage);
        if (selected == null) {
            return;
        }
        Path archivePath = selected.toPath();
        // FileChooser on some platforms does not append the extension.
        if (!archivePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                .endsWith(ProjectArchiver.ARCHIVE_EXTENSION)) {
            archivePath = archivePath.resolveSibling(
                    archivePath.getFileName() + ProjectArchiver.ARCHIVE_EXTENSION);
        }

        long estimatedAssetBytes = estimateArchiveAssetBytes(current, ArchiveOptions.defaults());
        List<String> missing = collectMissingAssetPaths(current);
        Window owner = rootPane.getScene().getWindow();
        Optional<ArchiveSummaryDialog.ArchivePlan> plan =
                ArchiveSummaryDialog.chooseArchivePlan(missing, estimatedAssetBytes, owner);
        if (plan.isEmpty()) {
            return;
        }
        archiveInBackground(current, archivePath, plan.get());
    }

    /**
     * Archives {@code project} to {@code archivePath}: captured here, on the
     * FX thread, and written on a background virtual thread, which never
     * reads the live model (story 331; Persistence Integrity book §2.3, §4.2
     * — an edit made while the archive is written never reaches it), with
     * progress surfaced through the strip's {@link ProjectOperationProgress}
     * model only; the preflight dialog has already dismissed. Package-private
     * so a test can run an archive without the {@code FileChooser} and the
     * plan dialog.
     *
     * <p>A capture that throws — anything, a
     * {@code ConcurrentModificationException} or an {@link Error} included —
     * is reported as "Archive failed: &lt;reason&gt;" with the strip
     * operation removed, and no worker starts. The strip operation is
     * removed however the worker ends, an {@link Error} included, so it never
     * outlives the worker. Any {@link IOException} or
     * {@link RuntimeException} from the write is reported as "Archive
     * failed: &lt;reason&gt;" and logged; SUCCESS is shown only when the
     * write returned, and once it has returned nothing else can turn it into
     * a failure (the headline falls back to a plain text, the log line runs
     * after the outcome is fixed). The archiver itself never leaves a
     * partial {@code .dawz} at the target.</p>
     */
    void archiveInBackground(DawProject project, Path archivePath, ArchiveSummaryDialog.ArchivePlan plan) {
        ProjectOperationProgress.NamedOperation archiveOp =
                new ProjectOperationProgress.NamedOperation("Archiving project…");
        progress.addOperation(archiveOp);
        ArchiveCapture capture;
        try {
            capture = projectArchiver.capture(project, plan.options(), plan.missingAssetDecisions());
        } catch (Throwable notCaptured) {
            LOG.log(Level.WARNING, "Failed to capture the project for its archive", notCaptured);
            progress.removeOperation(archiveOp);
            notificationBar.show(NotificationLevel.ERROR, "Archive failed: " + failureReason(notCaptured));
            return;
        }

        Thread.ofVirtual()
                .name("daw-archive-worker")
                .start(() -> {
                    Runnable outcome = () -> { };
                    ProjectArchiveSummary archived = null;
                    try {
                        ProjectArchiveSummary summary = projectArchiver.write(capture, archivePath);
                        // Only the write decides success or failure: the
                        // assignment cannot throw, the headline is formatted
                        // under a guard, and the log line runs after the try.
                        archived = summary;
                        outcome = () -> notificationBar.show(NotificationLevel.SUCCESS,
                                archivedMessage(summary, archivePath));
                    } catch (IOException | RuntimeException e) {
                        String reason = failureReason(e);
                        outcome = () -> notificationBar.show(NotificationLevel.ERROR,
                                "Archive failed: " + reason);
                        LOG.log(Level.WARNING, "Failed to archive project", e);
                    } finally {
                        Runnable shown = outcome;
                        postFx(() -> {
                            progress.removeOperation(archiveOp);
                            shown.run();
                        });
                    }
                    if (archived != null) {
                        ProjectArchiveSummary summary = archived;
                        LOG.info(() -> "Archived project to " + summary.outputPath()
                                + " (" + summary.uniqueAssetCount() + " assets, "
                                + summary.totalAssetBytes() + " bytes)");
                    }
                });
    }

    /**
     * The SUCCESS text for a published archive. The archive is already on
     * disk, so a formatting failure must not turn it into a failure: it is
     * logged and the text falls back to a plain "Archived project to
     * &lt;path&gt;".
     */
    private static String archivedMessage(ProjectArchiveSummary summary, Path archivePath) {
        try {
            return ArchiveSummaryDialog.formatHeadline(summary) + " — "
                    + ArchiveSummaryDialog.archivePathDisplay(archivePath);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not format the archive headline", e);
            return "Archived project to " + archivePath;
        }
    }

    /**
     * "File → Restore from Archive…" — opens a {@code .dawz} archive,
     * extracts it into a user-chosen destination directory, and loads
     * the restored project (which goes into the recent-projects list
     * via {@link ProjectManager#openProject}).
     *
     * <p>The extraction and XML-rewrite work runs on a background
     * virtual thread with a modeless {@link TaskProgressIndicator} so
     * large archives do not freeze the UI. The restored project is
     * then loaded on the JavaFX application thread via
     * {@link #loadProjectFromPath}, and the success notification is
     * only shown if that load actually succeeds.</p>
     *
     * <p>It passes the project-close gate ({@link #whenProjectMayClose})
     * first. FX thread.</p>
     */
    void onRestoreFromArchive() {
        restoreFromArchive(() -> { });
    }

    /**
     * {@link #onRestoreFromArchive()}, running {@code proceeding} first in the
     * gate's proceed (see {@link #newProject}).
     */
    private void restoreFromArchive(Runnable proceeding) {
        whenProjectMayClose(RESTORE_FROM_ARCHIVE_DOOR, () -> {
            proceeding.run();
            whileItsDialogIsOpen(RESTORE_FROM_ARCHIVE_DOOR, this::chooseAndRestoreArchive);
        });
    }

    /**
     * {@link #onRestoreFromArchive()} once the open project may close: the
     * two choosers — the door the one switch pending while they are open —
     * then the extraction, which takes the door's pending state over in the
     * same FX step, with the answer the door recorded at its judgment
     * (ruling L4). FX thread.
     */
    private void chooseAndRestoreArchive(DoorDialog dialog) {
        Path archivePath = archiveToRestorePrompt.get();
        if (archivePath == null) {
            return;
        }
        Path parentDir = restoreParentPrompt.get();
        if (parentDir == null) {
            return;
        }
        String stem = archivePath.getFileName().toString();
        if (stem.toLowerCase(java.util.Locale.ROOT).endsWith(ProjectArchiver.ARCHIVE_EXTENSION)) {
            stem = stem.substring(0, stem.length() - ProjectArchiver.ARCHIVE_EXTENSION.length());
        }
        String stamp = LocalDateTime.now().format(BACKUP_STAMP);
        Path destination = parentDir.resolve(stem + "-" + stamp);
        handOver(dialog);
        startArchiveRestore(archivePath, destination, dialog.answer());
    }

    /** Default {@link #archiveToRestorePrompt}: a {@link FileChooser} over the main window. */
    private Path promptArchiveToRestore() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Restore from Archive");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("DAW Archive (*.dawz)", "*.dawz"));
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File archiveSelected = fileChooser.showOpenDialog(stage);
        return archiveSelected == null ? null : archiveSelected.toPath();
    }

    /**
     * Default {@link #restoreParentPrompt}: a {@link DirectoryChooser} over the
     * main window, opening in {@code ~/Documents} — the restored project's
     * folder is {@code <chosen>/<archive-stem>-<timestamp>/}.
     */
    private Path promptRestoreParent() {
        Path defaultRoot = defaultRestoreRoot();
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Choose restore destination directory");
        if (defaultRoot != null && Files.isDirectory(defaultRoot)) {
            dirChooser.setInitialDirectory(defaultRoot.toFile());
        }
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File parentDir = dirChooser.showDialog(stage);
        return parentDir == null ? null : parentDir.toPath();
    }

    /**
     * Starts extracting {@code archivePath} into {@code destination} without
     * the door — a test starts a restore without the choosers: what the
     * answer covered is the project open now, at its edit revision, as it
     * was before ruling L4. See
     * {@link #startArchiveRestore(Path, Path, DoorAnswer)}. FX thread.
     *
     * @param archivePath the {@code .dawz} archive
     * @param destination the new folder to restore it into
     */
    void startArchiveRestore(Path archivePath, Path destination) {
        startArchiveRestore(archivePath, destination, DoorAnswer.of(deps.project().get()));
    }

    /**
     * Starts extracting {@code archivePath} into {@code destination} — the
     * step of Restore from Archive after its choosers, in the FX step its
     * second chooser returned in (story 331, rulings K3, L4). It keeps what
     * the door's answer covered, {@code answered}, for the load that follows
     * the extraction ({@link #openRestoredArchive}), and the extraction
     * becomes the one switch pending: a switch asked until its outcome
     * reaches the FX thread is refused with {@link #STILL_EXTRACTING_MESSAGE}.
     * Refused itself, extracting nothing, while another switch is pending.
     * The extraction runs on a virtual thread, with a modeless progress
     * window and the strip's operation; the pending state is set only once
     * they are up, right before the worker starts, and dropped again if it
     * does not start. FX thread.
     */
    private void startArchiveRestore(Path archivePath, Path destination, DoorAnswer answered) {
        if (aSwitchIsPending()) {
            refuseWhileASwitchIsPending();
            return;
        }

        // Run the potentially expensive ZIP extraction on a background
        // virtual thread. UI updates (load + notification) are marshalled
        // onto the FX thread via the FxDispatcher seam (story 289) once
        // extraction is complete.
        TaskProgressIndicator restoreProgress =
                new TaskProgressIndicator(mainWindow(), "Restoring archive\u2026", fxDispatcher);
        restoreProgress.hideCancelButton();
        restoreProgress.show();
        restoreProgress.update(-1.0, "Extracting archive\u2026");
        ProjectOperationProgress.NamedOperation restoreOp =
                new ProjectOperationProgress.NamedOperation("Restoring archive\u2026");
        progress.addOperation(restoreOp);

        archiveDoorAnswer = answered;
        setExtractingArchive(true);
        try {
            Thread.ofVirtual()
                    .name("daw-restore-worker")
                    .start(() -> restoreArchive(archivePath, destination, () -> {
                        setExtractingArchive(false);
                        progress.removeOperation(restoreOp);
                        restoreProgress.close();
                    }));
        } catch (RuntimeException | Error notStarted) {
            setExtractingArchive(false);
            archiveDoorAnswer = null;
            progress.removeOperation(restoreOp);
            restoreProgress.close();
            throw notStarted;
        }
    }

    /**
     * The worker half of {@link #onRestoreFromArchive()}, off the FX thread:
     * extracts {@code archivePath} into {@code destination} and writes the
     * resolved project document to {@code destination/project.daw}; then, on
     * the FX thread, runs {@code finished} and opens it
     * ({@link #openRestoredArchive}) with what the archive could not resolve
     * and the saved items its read could not honour as written — or runs
     * {@code finished} and shows the ERROR when the restore failed. The
     * announcement counts the archive read's items instead of the load's
     * own read's: the rewritten {@code project.daw} no longer holds what
     * that read dropped or adjusted, but still holds the
     * unrecognised-MIDI-effect placeholders it wrote back, which the load
     * reads and reports again — counting both would count those twice.
     * Package-private so a test can drive it without the choosers. Whatever
     * the extraction throws — an {@link Error} too — ends it with the ERROR,
     * so {@code finished} always runs: a pending restore never outlives its
     * worker (story 331, ruling K3).
     *
     * @param archivePath the {@code .dawz} archive
     * @param destination the folder to restore it into
     * @param finished    run on the FX thread before the load or the ERROR
     */
    void restoreArchive(Path archivePath, Path destination, Runnable finished) {
        try {
            ArchivedProject archived = projectArchiver.openArchive(
                    archivePath, destination, MissingAssetResolver.none());
            // openArchive resolves the project's relative
            // "assets/<sha>_*" references to absolute paths inside
            // the destination directory. We must write this
            // resolved project document back to
            // {destination}/project.daw so that
            // ProjectManager.openProject — which only reads the
            // file from disk — picks up the absolute asset paths
            // instead of the archive-internal relative ones. Story 331:
            // an atomic replace of the extracted file, so a failed write
            // leaves it whole and the restore reports the failure.
            String xml = new ProjectSerializer().serialize(archived.project());
            fileWriter.writeString(destination.resolve("project.daw"), xml);

            List<String> archiveMissing = archived.missingAssets();
            List<UnreadProjectItem> archiveUnread = archived.unreadItems();
            String projectName = archived.header().projectName();

            // Load the restored project on the FX thread; the
            // load announces the restore only if it succeeded.
            postFx(() -> {
                finished.run();
                openRestoredArchive(destination, projectName, archiveMissing, archiveUnread);
            });
            LOG.info(() -> "Restored archive " + archivePath
                    + " into " + destination);
        } catch (Throwable e) {
            postFx(() -> {
                finished.run();
                archiveDoorAnswer = null;
                notificationBar.show(NotificationLevel.ERROR,
                        "Restore failed: " + failureReason(e));
            });
            LOG.log(Level.WARNING, "Failed to restore archive", e);
        }
    }

    /**
     * The FX half of {@link #onRestoreFromArchive()}, once the archive has
     * been extracted into {@code destination}: loads the restored project
     * through the {@link OpenDoor.ArchiveRestore} door, whose one
     * announcement — made only if the load succeeded — counts the distinct
     * union of the open's missing assets and the references the archive
     * could not resolve itself ({@code archiveMissing}, story 329), and the
     * saved items the archive's read could not honour as written
     * ({@code archiveUnread}, story 330) instead of the load's.
     *
     * <p>The load comes after the extraction, which the window stayed live
     * through, so it passes the switch point ({@link #whenNoAppWriteIsPending},
     * story 331, ruling K3): while another switch is pending it is refused
     * with the WARNING that says the archive is already there
     * ({@link #lateSwitchRefusedMessage}, ruling L3 (b)); otherwise, in order,
     * (1) while a recording is in flight or a take is being written it is
     * refused (story 323 review) and the one WARNING names
     * {@code destination}, since the archive is already there; (2) while a
     * save or a named-snapshot copy this controller asked for has not
     * reported its outcome, it waits without blocking the FX thread for the
     * save writer to go idle, then starts again from (1); (3) when the
     * project is dirty and is no longer the one the door's answer covered —
     * the same instance at the same edit revision as at the door's judgment
     * (ruling L4) — it asks again ({@link #restoredArchivePromptText}):
     * Discard loads; Save makes the one save request and, once it is
     * {@link SaveOutcome.Saved} for the same project, starts again from (1);
     * Cancel, a failed save or a cancelled location prompt leave the project
     * open and the extracted folder in place, with one INFO
     * ({@link #restoredArchiveNotOpenedMessage}) whose
     * {@link #OPEN_RESTORED_ARCHIVE_ACTION} opens it later through the
     * project-close gate. When no extraction started through the door — a
     * test drives this half directly — the project open now is what the
     * answer covered. Package-private so a test can drive it without the
     * choosers. FX thread.</p>
     *
     * @param destination    the folder the archive was extracted into
     * @param projectName    the archived project's name
     * @param archiveMissing the references the archive could not resolve, as stored
     * @param archiveUnread  the saved items the archive's read could not honour as written
     */
    void openRestoredArchive(Path destination, String projectName, List<String> archiveMissing,
                             List<UnreadProjectItem> archiveUnread) {
        DoorAnswer answered = archiveDoorAnswer != null ? archiveDoorAnswer : DoorAnswer.of(deps.project().get());
        archiveDoorAnswer = null;
        RestoredArchive restored = new RestoredArchive(destination,
                new OpenDoor.ArchiveRestore(projectName, archiveMissing, archiveUnread));
        AnsweredSwitch load = new AnsweredSwitch(RESTORE_FROM_ARCHIVE_DOOR, answered,
                () -> refuseLateLoadWhileATakeIsRecordingOrBeingWritten(restored.alreadyDone()),
                restoredArchivePromptText(destination),
                () -> loadProjectFromPath(restored.destination(), restored.door()),
                () -> offerToOpenTheRestoredArchiveLater(restored),
                Optional.of(() -> offerToOpenTheRestoredArchiveLater(restored)));
        whenNoAppWriteIsPending(RESTORE_FROM_ARCHIVE_DOOR, restored.alreadyDone(), load.refused(),
                () -> switchIfTheAnswerCovers(load), load.stoppedAfterItsWork());
    }

    /**
     * Steps (1) and (2) of an {@link AnsweredSwitch} again — after its
     * prompt's save, as the one switch pending — then step (3). FX thread.
     */
    private void judgeTheAnsweredSwitch(AnsweredSwitch pending) {
        switchOnceAppWritesEnded(pending.name(), pending.refused(), () -> switchIfTheAnswerCovers(pending),
                pending.stoppedAfterItsWork());
    }

    /** Step (3) of an {@link AnsweredSwitch}: no app write is pending. FX thread. */
    private void switchIfTheAnswerCovers(AnsweredSwitch pending) {
        DawProject open = deps.project().get();
        if (!open.isDirty() || pending.answered().covers(open)) {
            pending.switchNow().run();
            return;
        }
        switch (askAboutUnsavedChanges(UnsavedChangesPrompt.door(pending.question()))) {
            case DISCARD -> pending.switchNow().run();
            case CANCEL -> pending.notSwitched().run();
            case SAVE -> saveThenJudgeAgain(pending.name(), () -> judgeTheAnsweredSwitch(pending),
                    pending.notSwitched(), pending.stoppedAfterItsWork());
        }
    }

    /**
     * The extracted archive was not opened: one INFO, whose action opens it
     * through the project-close gate — its prompt is the one asked — with
     * the same announcement (ruling K3). The folder stays where it is. FX
     * thread.
     */
    private void offerToOpenTheRestoredArchiveLater(RestoredArchive restored) {
        String message = restoredArchiveNotOpenedMessage(restored.destination());
        LOG.info(message);
        notificationBar.show(NotificationLevel.INFO, message, OPEN_RESTORED_ARCHIVE_ACTION,
                () -> whenProjectMayClose(RESTORE_FROM_ARCHIVE_DOOR,
                        () -> loadProjectFromPath(restored.destination(), restored.door())));
    }

    /**
     * The question of the prompt a restored archive's load asks when the
     * project was edited since the door's answer (ruling K3).
     *
     * @param destination the folder the archive was extracted into
     * @return the prompt's content text
     */
    static String restoredArchivePromptText(Path destination) {
        return "The archive was restored to " + destination + ". Do you want to save before opening it?";
    }

    /**
     * The INFO of a restored archive that was not opened (ruling K3).
     *
     * @param destination the folder the archive was extracted into
     * @return the notification's text
     */
    static String restoredArchiveNotOpenedMessage(Path destination) {
        return "The archive was restored to " + destination
                + "; the project was not opened. Open it now, or later with File ▸ Open Project…";
    }

    /**
     * Installs a project restored from the snapshot browser (story 190;
     * story 329, decision D21) — the {@code SnapshotsController} restore
     * sink {@code MainController} wires. The same install as an open from
     * disk without the read: the restored project becomes the live project
     * with a fresh undo history, its audio begins loading (the previous
     * run is retired), the UI is rebuilt, the project is marked dirty — a
     * restored snapshot is unsaved relative to disk — and the
     * {@link OpenDoor.SnapshotRestore} door's one announcement, qualified
     * by what the snapshot's clips could not find and by the saved items its
     * deserialization could not honour as written (story 330), replaces the
     * old unconditional {@code Restored snapshot} INFO. The project manager's
     * project is not abandoned: a snapshot is a state of the open project.
     * FX thread.
     *
     * @param restored      the deserialized snapshot
     * @param label         the snapshot's label
     * @param missingAssets what the snapshot's deserialization found missing
     * @param unreadItems   the saved items the snapshot's deserialization
     *                      could not honour as written
     */
    void installRestoredSnapshot(DawProject restored, String label, MissingAssetsReport missingAssets,
                                 List<UnreadProjectItem> unreadItems) {
        Objects.requireNonNull(restored, "restored must not be null");
        Objects.requireNonNull(missingAssets, "missingAssets must not be null");
        Objects.requireNonNull(unreadItems, "unreadItems must not be null");
        deps.setProject().accept(restored);
        // Story 331 (G4, G13 #4, M4) — the strip starts over, its countdown running on; project.daw is as the
        // open project last wrote it.
        snapshotReplacedTheOpenProject();
        deps.setUndoManager().accept(new UndoManager());
        deps.rebuildHistoryPanel().run();
        deps.resetTrackCounters().run();
        Path projectDir = restored.getMetadata().projectPath();
        OpenProjectAudio.Generation generation = openProjectAudio.begin(restored, projectDir, missingAssets,
                restored.getTransport().getPositionInBeats());
        rebuildUI();
        // Mark dirty AFTER the rebuild so the DIRTY signal lands on the freshly built
        // ProjectVM (the outgoing VM was disposed in rebuildViewModels and the new one
        // is wired to the project there), which re-syncs the Save menu through its dirty
        // listener. Marking BEFORE the rebuild announced to no live listener and left
        // the bit visible only via the new VM's constructor seed — correct merely by
        // coincidence of ordering. A restored snapshot is unsaved relative to disk, so
        // it must be dirty.
        deps.project().get().markDirty();
        openProjectAudio.announce(generation, new OpenDoor.SnapshotRestore(label), projectDir, unreadItems);
    }

    /**
     * Walks the project for asset references whose path no longer points
     * at a regular file on disk. Mirrors the iteration order of
     * {@link ProjectArchiver} so the user sees the set the archiver will end
     * up skipping, except a blank clip reference, which the archiver writes
     * into the archive as it is and this list leaves out (see below): a clip
     * that carries a segment list (a recorded take, story 323) is checked
     * segment by segment, each missing segment reported once and the head
     * not a second time; a single-file clip is checked by its source file
     * path.
     *
     * <p>A clip reference that is not an absolute path on this platform names
     * no file ({@link ProjectPaths#isAbsoluteReference}, story 323): it is
     * listed as missing without touching the filesystem, and never looked up
     * against the JVM's working directory. A {@code null} or blank clip
     * reference is skipped, because the dialog turns every listed path into
     * an {@code ArchiveAssetDecision}, which refuses a blank path. A
     * SoundFont path is checked as it is.</p>
     *
     * <p>Package-private for unit tests.</p>
     */
    static List<String> collectMissingAssetPaths(DawProject project) {
        List<String> missing = new ArrayList<>();
        for (Track track : project.getTracks()) {
            // The same holders the archiver walks: mid-audition, the take-lane
            // clips the comp selection slices, not the soloed lane (PR #984 review).
            for (AudioClip clip : track.getTakeComping().referenceHoldersAsSaved(track)) {
                List<String> segments = clip.getSourceSegmentPaths();
                if (!segments.isEmpty()) {
                    for (String segment : segments) {
                        if (!segment.isBlank() && !namesRegularFile(segment)) {
                            missing.add(segment);
                        }
                    }
                    continue;
                }
                String p = clip.getSourceFilePath();
                if (p != null && !p.isBlank() && !namesRegularFile(p)) {
                    missing.add(p);
                }
            }
            SoundFontAssignment sf = track.getSoundFontAssignment();
            if (sf != null && sf.soundFontPath() != null) {
                Path sfPath = sf.soundFontPath();
                if (!Files.isRegularFile(sfPath)) {
                    missing.add(sfPath.toString());
                }
            }
        }
        return missing;
    }

    private long estimateArchiveAssetBytes(DawProject project, ArchiveOptions options) {
        try {
            return projectArchiver.previewAssetSizes(project, options)
                    .values()
                    .stream()
                    .mapToLong(Long::longValue)
                    .sum();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not estimate archive asset bytes", e);
            return 0L;
        }
    }

    /**
     * Whether a clip reference names a regular file. A reference that is not
     * an absolute path on this platform names no file (story 323) and is
     * answered without touching the filesystem.
     */
    private static boolean namesRegularFile(String clipReference) {
        return ProjectPaths.isAbsoluteReference(clipReference)
                && Files.isRegularFile(Path.of(clipReference));
    }

    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "project";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static Path defaultRestoreRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        Path docs = Paths.get(home, "Documents");
        return Files.isDirectory(docs) ? docs : Paths.get(home);
    }

    // ── Supporting methods ───────────────────────────────────────────────────

    /**
     * The gate the in-app doors that replace the open project pass before
     * they do anything (replacing the project closes it): New, Open, Import,
     * Restore from Archive, a Project Hub or Welcome open and (through
     * {@code SnapshotsController.Deps}) a snapshot restore. Recover and a
     * migration roll-back, which do not pass it — they ask nothing about
     * unsaved changes — pass the switch point ({@link #whenNoAppWriteIsPending})
     * instead, as every switch made after a chooser or after work off the FX
     * thread does. Quitting the application and Close Project pass neither:
     * the exit protocol has its own guard (story 333).
     *
     * <p>Continuation-passing (story 331, rulings G1, G2, G13 #8, K3, L3, L4):
     * it runs {@code proceed}, on the FX thread, once the door may replace
     * the open project, and never otherwise. In order:</p>
     * <ol>
     *   <li>While another switch is pending — a door or a later switch
     *       waiting for a save ({@link #whenNoAppWriteIsPending}), an archive
     *       being extracted, a recovery scanning, asking or working off the
     *       FX thread, or a door whose file dialog is open — this one is
     *       refused with one INFO notification, "Still saving — &lt;first
     *       door&gt; continues when the save finishes.",
     *       {@link #STILL_EXTRACTING_MESSAGE}, {@link #STILL_RECOVERING_MESSAGE}
     *       or {@link #doorDialogStillOpenMessage}, shown once per
     *       {@link #DOOR_REFUSAL_WINDOW} for repeated clicks; the pending
     *       switch goes on (rulings G2, K3, L3, L4).</li>
     *   <li>It refuses while a recording is in flight or a take is still
     *       being written to disk ({@link #refuseWhileATakeIsRecordingOrBeingWritten()}).
     *       That refusal cannot be left to the unsaved-changes prompt, which
     *       asks only when the project is dirty and, when it asks, lets the
     *       door go ahead once the user saves or discards: a take still being
     *       written is not in the project yet and has not marked it dirty —
     *       {@code TransportController} marks the project dirty when it
     *       publishes the take's clips, which it does only once the take has
     *       been written to disk — and a door that replaced the project
     *       meanwhile would leave the take in no project.</li>
     *   <li>While a save or a named-snapshot copy this controller asked for
     *       has not reported its outcome, the door waits — without blocking
     *       the FX thread — until the project manager's save writer has
     *       finished every save queued before it
     *       ({@link ProjectManager#whenSavesIdle()}), then starts again from
     *       step 2 against the project as it is now: a recording started
     *       meanwhile refuses it, and a save requested meanwhile makes it
     *       wait again, so the project is never switched while a write this
     *       application asked for still runs into its folder (ruling G1).</li>
     *   <li>A clean project proceeds. A dirty one asks: Save, Discard or
     *       Cancel. Discard proceeds and Cancel does nothing. Save makes the
     *       one save request and the door waits for it: once it is
     *       {@link SaveOutcome.Saved}, the door starts again from step 2 (an
     *       edit made during the write asks again); a failed save — reported
     *       as every save failure is — cancels the door (ruling G1 (3)). A
     *       failed save the door did not ask for leaves the project dirty, so
     *       the prompt asks.</li>
     * </ol>
     *
     * @param door    the door's name, as the menus name it (the refusal INFO names it)
     * @param proceed what the door does once the open project may close
     */
    void whenProjectMayClose(String door, Runnable proceed) {
        Objects.requireNonNull(door, "door must not be null");
        Objects.requireNonNull(proceed, "proceed must not be null");
        if (aSwitchIsPending()) {
            refuseWhileASwitchIsPending();
            return;
        }
        judgeDoor(door, proceed);
    }

    /** Steps 2 to 4 of {@link #whenProjectMayClose}; {@link #waitingDoor} is {@code null} or {@code door}. */
    private void judgeDoor(String door, Runnable proceed) {
        switchOnceAppWritesEnded(door, this::refuseWhileATakeIsRecordingOrBeingWritten,
                () -> proceedUnlessCancelled(door, proceed), Optional.empty());
    }

    /** Step 4 of {@link #whenProjectMayClose}: no app write is pending. FX thread. */
    private void proceedUnlessCancelled(String door, Runnable proceed) {
        if (!deps.project().get().isDirty()) {
            proceed.run();
            return;
        }
        switch (askAboutUnsavedChanges(UnsavedChangesPrompt.DOOR)) {
            case CANCEL -> { }
            case DISCARD -> proceed.run();
            // A failed save at the prompt cancels the door; its ERROR is already shown.
            case SAVE -> saveThenJudgeAgain(door, () -> judgeDoor(door, proceed), () -> { }, Optional.empty());
        }
    }

    /**
     * Runs a door's file dialog and what follows it once the gate let the
     * door proceed — in the FX step of the door's judgment (story 331, ruling
     * L4): it records what the door's answer covered, the project open now
     * at its edit revision, and the door is the one switch pending while
     * {@code chooseThenSwitch} shows its dialog, so another switch asked
     * meanwhile — from a window the dialog does not block — is refused with
     * {@link #doorDialogStillOpenMessage}. The door's switch point, or the
     * archive's extraction, takes that state over in the FX step the dialog
     * returned in ({@link #handOver}); a dialog that returned nothing, an
     * import read that failed and an exception end it here. FX thread.
     *
     * @param door             the door's name, as the menus name it
     * @param chooseThenSwitch the dialog, then the door's switch
     */
    private void whileItsDialogIsOpen(String door, Consumer<DoorDialog> chooseThenSwitch) {
        DoorDialog dialog = new DoorDialog(door, DoorAnswer.of(deps.project().get()));
        setDoorDialog(dialog);
        try {
            chooseThenSwitch.accept(dialog);
        } finally {
            handOver(dialog);
        }
    }

    /** The door's dialog returned: it is no longer the switch pending — unless another took its place. FX thread. */
    private void handOver(DoorDialog dialog) {
        if (doorDialog == dialog) {
            setDoorDialog(null);
        }
    }

    /**
     * The switch of New Project, Open Project and Import DAWproject Exchange
     * once their dialog returned (story 331, ruling L4): in the same FX step
     * it takes the door's pending state over, then — past the switch point —
     * (1) refuses while a recording is in flight or a take is being written,
     * (2) waits while an app write is pending, starting again from (1), and
     * (3) when the project is dirty and the door's answer no longer covers
     * it, asks the door's own unsaved-changes prompt again: Discard runs
     * {@code switchNow}, Cancel ends the door with no message, Save makes the
     * one save request and starts again from (1) once it is
     * {@link SaveOutcome.Saved} for the same project, and a failed save or a
     * cancelled location prompt end the door, its ERROR already shown. With
     * no edit since the door's judgment nothing more is asked. FX thread.
     */
    private void switchAfterItsDialog(DoorDialog dialog, Runnable switchNow) {
        handOver(dialog);
        AnsweredSwitch pending = new AnsweredSwitch(dialog.door(), dialog.answer(),
                this::refuseWhileATakeIsRecordingOrBeingWritten, CONTINUE_QUESTION, switchNow, () -> { },
                Optional.empty());
        whenNoAppWriteIsPending(dialog.door(), pending.refused(), () -> switchIfTheAnswerCovers(pending));
    }

    /**
     * The one point every switch passes once it is about to replace the open
     * project after a gap — work off the FX thread, or the event loop of a
     * chooser or a dialog it showed (story 331, rulings G1, G2, G13 #8):
     * while another switch is pending it is refused with that switch's INFO
     * ({@link #refuseWhileASwitchIsPending}), and nothing more happens;
     * otherwise {@link #switchOnceAppWritesEnded} — it refuses while a
     * recording is in flight or a take is being written ({@code refused}),
     * waits, as the one switch pending, while a save or a named-snapshot copy
     * this controller asked for has not reported its outcome, and runs
     * {@code switchNow} once neither holds — at once, in this FX step, when
     * nothing is outstanding. Every wait is a continuation posted back
     * through the {@link FxDispatcher}: the FX thread never waits. For a
     * switch that already did something off the FX thread, see
     * {@link #whenNoAppWriteIsPending(String, String, BooleanSupplier, Runnable)}.
     * FX thread.
     *
     * @param name      the switch's name, as the INFO refusing a door meanwhile names it
     * @param refused   the recording and take refusal, run at every judgment; {@code true} ends the switch
     * @param switchNow what replaces the open project, or writes its head
     */
    private void whenNoAppWriteIsPending(String name, BooleanSupplier refused, Runnable switchNow) {
        if (aSwitchIsPending()) {
            refuseWhileASwitchIsPending();
            return;
        }
        switchOnceAppWritesEnded(name, refused, switchNow, Optional.empty());
    }

    /**
     * {@link #whenNoAppWriteIsPending(String, BooleanSupplier, Runnable)} for
     * a late switch that already did something off the FX thread — an
     * archive extracted, recovered changes written, a journal discarded, a
     * recovery whose write or replay failed (story 331, ruling L3 (b)): while
     * another switch is pending it is refused with a WARNING that says what
     * was done, that the project was not opened and which switch is replacing
     * the open project ({@link #lateSwitchRefusedMessage}), shown and logged
     * every time — that message is the only record of the work. A wait it
     * then makes for a save, which the user stops, says what was done and
     * that the project was not opened ({@code door.cancelled.alreadyDone}),
     * never that it was cancelled (the studio engineer's stage-4 S3). FX
     * thread.
     *
     * @param name        the switch's name, as the INFO refusing a door meanwhile names it
     * @param alreadyDone what the work did, a sentence without its full stop
     * @param refused     the recording and take refusal, run at every judgment; {@code true} ends the switch
     * @param switchNow   what replaces the open project
     */
    private void whenNoAppWriteIsPending(String name, String alreadyDone, BooleanSupplier refused,
                                         Runnable switchNow) {
        whenNoAppWriteIsPending(name, alreadyDone, refused, switchNow,
                Optional.of(() -> reportStoppedAfterItsWork(alreadyDone)));
    }

    /**
     * {@link #whenNoAppWriteIsPending(String, String, BooleanSupplier, Runnable)}
     * whose stopped wait reports {@code stoppedAfterItsWork} — a restored
     * archive's "Open it later" INFO (ruling K3). FX thread.
     */
    private void whenNoAppWriteIsPending(String name, String alreadyDone, BooleanSupplier refused,
                                         Runnable switchNow, Optional<Runnable> stoppedAfterItsWork) {
        if (aSwitchIsPending()) {
            // Story 333's two variants are the bundle's (ruling 4 Q5 (i)); the L3 (b) text stays 331's literal.
            String warning = closing != null ? ProjectCloseText.text("door.lateRefused.closing", alreadyDone)
                    : aFirstSaveIsPending() ? ProjectCloseText.text("door.lateRefused.firstSave", alreadyDone)
                    : lateSwitchRefusedMessage(alreadyDone, pendingSwitchName());
            LOG.warning(warning);
            notificationBar.show(NotificationLevel.WARNING, warning);
            return;
        }
        switchOnceAppWritesEnded(name, refused, switchNow, stoppedAfterItsWork);
    }

    /**
     * A late switch's wait, stopped (the studio engineer's stage-4 S3): its
     * work stays done, the project was not opened, and the open project and
     * its save go on — one INFO says so. FX thread.
     *
     * @param alreadyDone what the work did, a sentence without its full stop
     */
    private void reportStoppedAfterItsWork(String alreadyDone) {
        String stopped = ProjectCloseText.text("door.cancelled.alreadyDone", alreadyDone,
                deps.project().get().getName());
        LOG.info(stopped);
        notificationBar.show(NotificationLevel.INFO, stopped);
    }

    /**
     * The WARNING of a late switch that already did something, refused
     * because another switch is pending (ruling L3 (b)).
     *
     * @param alreadyDone what the work did, a sentence without its full stop
     * @param pending     the pending switch's name
     * @return the notification's text
     */
    static String lateSwitchRefusedMessage(String alreadyDone, String pending) {
        return alreadyDone + "; the project was not opened, because " + pending
                + " is replacing the open project — open it later with File ▸ Open Project…";
    }

    /**
     * Whether the switch pending is the open project's first save, for no
     * door — its location prompt open, or its folder being created — which
     * replaces nothing: a late switch refused then says so
     * ({@code door.lateRefused.firstSave}, the studio engineer's stage-4 N1
     * and appfix-1 N1, ruling 4 Q5 (i)). FX thread.
     */
    private boolean aFirstSaveIsPending() {
        return doorDialog != null ? SAVE_PROJECT_DOOR.equals(doorDialog.door())
                : !recovering && !extractingArchive && creatingFolder != null && waitingDoor == null;
    }

    /**
     * The INFO of a switch asked while a door's file dialog is open (ruling
     * L4).
     *
     * @param door the door whose dialog is open
     * @return the notification's text
     */
    static String doorDialogStillOpenMessage(String door) {
        return door + "'s file dialog is still open — finish or cancel it first.";
    }

    /**
     * The judgment of {@link #whenNoAppWriteIsPending} and of a door
     * ({@link #judgeDoor}): {@link #waitingDoor} is {@code null} or
     * {@code name}. Refused: no switch is pending any more. A write pending:
     * {@code name} is the switch pending until the save writer is idle, then
     * it is judged again from the refusal on the FX thread, so a recording
     * started meanwhile refuses it and a save requested meanwhile makes it
     * wait again. Its first wait starts its {@link DoorWait} (story 333,
     * ruling R8), kept across its re-waits: after
     * {@link #DOOR_WAIT_WARNING_DELAY} still waiting, one WARNING offers to
     * stop waiting, and a wait that was stopped never resumes. Neither: no
     * switch is pending any more, and {@code switchNow} runs. FX thread.
     */
    private void switchOnceAppWritesEnded(String name, BooleanSupplier refused, Runnable switchNow,
                                          Optional<Runnable> stoppedAfterItsWork) {
        if (refused.getAsBoolean()) {
            endDoorWait();
            setWaitingDoor(null);
            return;
        }
        if (pendingWrites > 0) {
            setWaitingDoor(name);
            DoorWait wait = doorWaitOf(name, stoppedAfterItsWork);
            projectManager.whenSavesIdle().whenComplete((idle, never) -> postFx(() -> {
                if (currentDoorWait != wait) {
                    LOG.fine(() -> wait.door() + " no longer waits for the save: it does not resume");
                    return;
                }
                beforeWaitingDoorResumes.run();
                switchOnceAppWritesEnded(name, refused, switchNow, stoppedAfterItsWork);
            }));
            return;
        }
        endDoorWait();
        setWaitingDoor(null);
        switchNow.run();
    }

    /**
     * The Save of an unsaved-changes prompt: the one save request, with
     * {@code name} the switch pending while it is written. Once it is
     * {@link SaveOutcome.Saved} for the same project, {@code judgeAgain}
     * runs; a failed save, a cancelled location prompt or a project that
     * could not be created end the switch and run {@code notSaved} (ruling
     * G1 (3)). The door waits for the save as its {@link DoorWait} (story
     * 333, ruling R8), from when the request returned — after any location
     * prompt, so the user's time in the chooser is not waiting — and a wait
     * that was stopped ignores the save's outcome, which was reported as
     * every save's is. FX thread.
     */
    private void saveThenJudgeAgain(String name, Runnable judgeAgain, Runnable notSaved,
                                    Optional<Runnable> stoppedAfterItsWork) {
        setWaitingDoor(name);
        AtomicReference<DoorWait> waiting = new AtomicReference<>();
        Optional<CompletableFuture<SaveOutcome>> requested = saveOpenProject((outcome, sameProject) -> {
            if (currentDoorWait != waiting.get()) {
                LOG.fine(() -> name + " no longer waits for its save: its outcome ends nothing");
                return;
            }
            if (sameProject && outcome instanceof SaveOutcome.Saved) {
                judgeAgain.run();
            } else {
                endDoorWait();
                setWaitingDoor(null);
                notSaved.run();
            }
        }, () -> { });
        if (requested.isEmpty()) {
            endDoorWait();
            setWaitingDoor(null);
            notSaved.run();
            return;
        }
        waiting.set(doorWaitOf(name, stoppedAfterItsWork));
    }

    /**
     * The wait of the door {@code door} (story 333, ruling R8): the current
     * one when it is this door's — a re-wait keeps it — else a new one, whose
     * timer shows the "Stop waiting" WARNING once, after
     * {@link #DOOR_WAIT_WARNING_DELAY}. FX thread.
     */
    private DoorWait doorWaitOf(String door, Optional<Runnable> stoppedAfterItsWork) {
        if (currentDoorWait != null && currentDoorWait.door().equals(door)) {
            return currentDoorWait;
        }
        endDoorWait();
        DoorWait wait = new DoorWait(door, ++doorWaitEpochs, stoppedAfterItsWork);
        currentDoorWait = wait;
        doorWaitTimer = doorWaitTimers.after(DOOR_WAIT_WARNING_DELAY, () -> warnStillSaving(wait));
        return wait;
    }

    /**
     * The door's wait ended — it switched, was refused, its save failed, or
     * the user stopped it: its timer is cancelled, and its "Still saving"
     * WARNING, while the bar still shows it, is taken off (the studio
     * engineer's stage-4 N3). FX thread.
     */
    private void endDoorWait() {
        currentDoorWait = null;
        if (doorWaitTimer != null) {
            doorWaitTimer.cancel();
            doorWaitTimer = null;
        }
        String shown = doorWaitWarning;
        doorWaitWarning = null;
        if (shown != null && notificationBar.getCurrentLevel() == NotificationLevel.WARNING
                && shown.equals(notificationBar.getMessage())) {
            notificationBar.dismiss();
        }
    }

    /**
     * {@link #DOOR_WAIT_WARNING_DELAY} into a door's wait, still waiting
     * (story 333, ruling R8): one WARNING, {@code door.stillSaving}, whose
     * action {@code Stop waiting} cancels the door — once per wait, and never
     * rate-limited. A wait that already ended shows nothing. FX thread.
     */
    private void warnStillSaving(DoorWait wait) {
        if (currentDoorWait != wait || waitingDoor == null) {
            return;
        }
        doorWaitTimer = null;
        String warning = ProjectCloseText.text("door.stillSaving", deps.project().get().getName(), wait.door());
        LOG.warning(warning);
        doorWaitWarning = warning;
        notificationBar.show(NotificationLevel.WARNING, warning, ProjectCloseText.text("door.stopWaiting"),
                () -> stopWaiting(wait));
    }

    /**
     * The WARNING's {@code Stop waiting} (story 333, ruling R8): the door is
     * cancelled — no switch is pending any more, and nothing it would have
     * done runs when the save ends — the open project stays as it is, and its
     * save goes on with its usual outcome; one INFO says so: a late switch's
     * own report for one that already did its work (its archive restored, its
     * recovered changes written), {@code door.cancelled} otherwise. An action
     * of a wait that has ended does nothing. FX thread.
     */
    private void stopWaiting(DoorWait wait) {
        if (currentDoorWait != wait) {
            return;
        }
        endDoorWait();
        setWaitingDoor(null);
        if (wait.stoppedAfterItsWork().isPresent()) {
            wait.stoppedAfterItsWork().get().run();
            return;
        }
        String cancelled = ProjectCloseText.text("door.cancelled", wait.door(), deps.project().get().getName());
        LOG.info(cancelled);
        notificationBar.show(NotificationLevel.INFO, cancelled);
    }

    /**
     * Whether a switch is pending: one waits for a save ({@link #waitingDoor}),
     * an archive is extracted, a recovery scans, asks or works off the FX
     * thread, a door's file dialog is open, a first save's folder is being
     * created, or a Save As chooses its target or copies — or the close
     * protocol runs ({@link #closing}, story 333). Only one is at a time —
     * each refuses the others while it is pending, a first save included —
     * but for a door's own first save, which waits for it as its
     * {@link #waitingDoor} while it is {@link #creatingFolder}, and for the
     * close, which waits for the switch it found pending. Package-private so
     * a test reads whether the close's own run is still pending (ruling 4
     * Q3). FX thread.
     */
    boolean aSwitchIsPending() {
        return aSwitchBesidesClosingIsPending() || closing != null;
    }

    /**
     * {@link #aSwitchIsPending()} but for the close protocol's own run: the
     * fact {@link #switchPending} reports, which the close waits on (story
     * 333, design §3.1).
     */
    private boolean aSwitchBesidesClosingIsPending() {
        return waitingDoor != null || extractingArchive || recovering || doorDialog != null
                || creatingFolder != null || saveAsPhase != null;
    }

    /** The pending switch's name, as a refusal names it; see {@link #aSwitchIsPending()}. */
    private String pendingSwitchName() {
        if (closing != null) {
            return closing;
        }
        if (doorDialog != null) {
            return doorDialog.door();
        }
        if (recovering) {
            return RECOVER_SESSION_DOOR;
        }
        if (extractingArchive) {
            return RESTORE_FROM_ARCHIVE_DOOR;
        }
        if (creatingFolder != null && waitingDoor == null) {
            return creatingFolder.door();
        }
        if (saveAsPhase != null) {
            return SAVE_AS_DOOR;
        }
        return waitingDoor;
    }

    /**
     * The switch pending now (story 333): what {@link #closeReadiness()}
     * reports — a door's open file dialog first, then an installation, named
     * by its door ({@link #installingDoor}: every fenced replacement names
     * one, a gated New or Open as well, whose worker also keeps
     * {@link #recovering} set), then the rest in the order a refusal names
     * them. Recover Session's unfenced scan and dialog report
     * {@link PendingSwitch.Recovering}. FX thread.
     */
    private PendingSwitch pendingSwitch() {
        if (doorDialog != null) {
            return new PendingSwitch.DoorDialogOpen(doorDialog.door());
        }
        if (projectInstallationPending.get()) {
            return new PendingSwitch.Installing(installingDoor);
        }
        if (recovering) {
            return new PendingSwitch.Recovering();
        }
        if (extractingArchive) {
            return new PendingSwitch.Extracting();
        }
        if (creatingFolder != null) {
            return new PendingSwitch.CreatingFolder(creatingFolder.door());
        }
        if (saveAsPhase != null) {
            return saveAsPhase == SaveAsPhase.CHOOSING ? new PendingSwitch.SaveAsChoosing()
                    : new PendingSwitch.SaveAsCopying();
        }
        if (waitingDoor != null) {
            return new PendingSwitch.WaitingDoor(waitingDoor);
        }
        return new PendingSwitch.None();
    }

    /**
     * The one writer of {@link #switchPending}, called by every setter of the
     * pending state and by the installation fence's listener. It reads the
     * fence first, every time, so that invalidation listener is told of every
     * later change (an invalidation listener whose property is never read
     * again hears nothing more). FX thread.
     */
    private void pendingChanged() {
        boolean installing = projectInstallationPending.get();
        switchPending.set(installing || aSwitchBesidesClosingIsPending());
    }

    /** The door waiting for a save, or {@code null}; see {@link #waitingDoor}. FX thread. */
    private void setWaitingDoor(String door) {
        waitingDoor = door;
        pendingChanged();
    }

    /** Whether an archive is being extracted; see {@link #extractingArchive}. FX thread. */
    private void setExtractingArchive(boolean extracting) {
        extractingArchive = extracting;
        pendingChanged();
    }

    /** Whether Recover Session is the switch pending; see {@link #recovering}. FX thread. */
    private void setRecovering(boolean pending) {
        recovering = pending;
        pendingChanged();
    }

    /** The door whose file dialog is open, or {@code null}; see {@link #doorDialog}. FX thread. */
    private void setDoorDialog(DoorDialog dialog) {
        doorDialog = dialog;
        pendingChanged();
    }

    /** The first save whose folder is being created, or {@code null}; see {@link #creatingFolder}. FX thread. */
    private void setCreatingFolder(FolderBeingCreated creating) {
        creatingFolder = creating;
        pendingChanged();
    }

    /** Where a user's Save As stands, or {@code null}; see {@link #saveAsPhase}. FX thread. */
    private void setSaveAsPhase(SaveAsPhase phase) {
        saveAsPhase = phase;
        pendingChanged();
    }

    /** The close protocol's run, by its door's name, or {@code null}; see {@link #closing}. FX thread. */
    private void setClosing(String door) {
        closing = door;
        pendingChanged();
    }

    /**
     * Story 333 (design §1.4, §3.1) — the close protocol starts waiting on
     * disk work: from now until {@link #endClosing()} its run is the one
     * switch pending, so a door or a later switch a stale continuation
     * starts is refused, at FINE. FX thread.
     *
     * @param door the close's name, as a log line or a late switch's refusal names it
     */
    void beginClosing(String door) {
        setClosing(Objects.requireNonNull(door, "door must not be null"));
    }

    /** Story 333 — the close protocol's run ended; no switch of it is pending any more. FX thread. */
    void endClosing() {
        setClosing(null);
    }

    /**
     * Whether a switch is pending or a project is being installed (story 333):
     * the fact the close protocol waits on — never its own run
     * ({@link #closing}). Read-only; its one writer is this controller's
     * pending state. FX thread.
     *
     * @return the property
     */
    ReadOnlyBooleanProperty switchPendingProperty() {
        return switchPending.getReadOnlyProperty();
    }

    /**
     * What the close protocol reads of the open project now (story 333,
     * rulings R2, R9, R12 #8): its name, whether it has unsaved changes and a
     * folder, whether that folder was opened read-only, when its head was last
     * written, the failure of its last head save while it stands, whether its
     * lock is already known lost, the switch pending and today. FX thread.
     *
     * @return the open project's readiness to close
     */
    CloseReadiness closeReadiness() {
        DawProject project = deps.project().get();
        ProjectMetadata open = projectManager.getCurrentProject();
        boolean hasFolder = open != null && open.projectPath() != null;
        Optional<LockStolenException> knownLockLoss = !hasFolder ? Optional.empty()
                : lastHeadFailure instanceof LockStolenException stolen ? Optional.of(stolen)
                : Optional.ofNullable(adoptedUnderALostLock);
        boolean lockLost = hasFolder && (knownLockLoss.isPresent()
                || projectManager.getLockManager().status() == LockStatus.STOLEN);
        return new CloseReadiness(project.getName(), project.isDirty(), hasFolder,
                hasFolder && projectManager.isReadOnly(), Optional.ofNullable(headSavedAt),
                Optional.ofNullable(lastHeadFailure), lockLost, knownLockLoss, pendingSwitch(), progress.getToday());
    }

    /**
     * Story 333 — how many head saves failed so far, of whichever project was
     * open ({@link #failedSavesOfOpenProject}): the close protocol compares it
     * before and after the saves it waits for. FX thread.
     *
     * @return the count
     */
    long failedSavesOfOpenProject() {
        return failedSavesOfOpenProject;
    }

    /**
     * Story 333 (ruling R12 #8) — an exit replaces a door that waits for a
     * save, silently: the door is cancelled as its "Stop waiting" cancels it
     * — epoch-fenced, so its wait never resumes and nothing it would have
     * done runs when the save ends — with a FINE log instead of the INFO;
     * the save goes on, and the exit waits for it. A door waiting for its
     * own prompt's first save is cancelled the same way, while that save
     * still creates its folder. Nothing when no door waits. FX thread.
     */
    void cancelWaitingDoorForExit() {
        if (waitingDoor == null) {
            return;
        }
        String door = waitingDoor;
        endDoorWait();
        setWaitingDoor(null);
        LOG.fine(() -> door + " no longer waits for the save: the application is closing");
    }

    /**
     * Story 333 (rulings R1, R9 and 2 OQ-4) — waits, without blocking the FX
     * thread, until every save and named-snapshot copy this controller asked
     * for before this call has reported its outcome on the FX thread, then
     * tells {@code reported} — on the FX thread, unless {@code stillWanted}
     * answered {@code false} at a wake-up, which ends the wait silently —
     * whether one of them failed (the monotonic count of failed head saves
     * changed) and the lost lock one of them met. While the wait is wanted,
     * a lost lock is the close's, decided in that save's report
     * ({@link #reportSaveFailed}): it shows no takeover dialog of its own.
     * Like a door's wait: the save writer's idle, then an unkeyed post that
     * looks again. FX thread.
     *
     * @param stillWanted asked at every wake-up and when a lost lock arrives
     * @param reported    told once, on the FX thread, what the saves reported
     */
    void whenItsSavesHaveReported(BooleanSupplier stillWanted, Consumer<SavesReport> reported) {
        Objects.requireNonNull(stillWanted, "stillWanted must not be null");
        Objects.requireNonNull(reported, "reported must not be null");
        ClosingSavesWait wait = new ClosingSavesWait(stillWanted);
        closingSavesWait = wait;
        awaitTheSavesOf(wait, failedSavesOfOpenProject, reported);
    }

    /** One look of {@link #whenItsSavesHaveReported}: wait again, end silently, or report. FX thread. */
    private void awaitTheSavesOf(ClosingSavesWait wait, long failedBefore, Consumer<SavesReport> reported) {
        if (!wait.stillWanted.getAsBoolean()) {
            if (closingSavesWait == wait) {
                closingSavesWait = null;
            }
            return;
        }
        if (pendingWrites > 0) {
            projectManager.whenSavesIdle().whenComplete((idle, never) ->
                    postFx(() -> awaitTheSavesOf(wait, failedBefore, reported)));
            return;
        }
        if (closingSavesWait == wait) {
            closingSavesWait = null;
        }
        reported.accept(new SavesReport(failedSavesOfOpenProject != failedBefore, Optional.ofNullable(wait.lockLost)));
    }

    /**
     * Whether the close protocol, waiting for the saves queued before it,
     * takes {@code stolen} over (ruling 2 OQ-4): only while its wait is
     * still wanted — a run already kept open ("Don't quit") leaves the save
     * its own takeover dialog. FX thread.
     */
    private boolean takenOverByTheClose(LockStolenException stolen) {
        ClosingSavesWait wait = closingSavesWait;
        return wait != null && wait.takes(stolen);
    }

    /**
     * Story 333 (rulings R2, R7, R9) — the close protocol's FLUSH: the one
     * save request of the open project ({@link #saveOpenProject}) — a
     * project never saved first asks for its folder, which is created off
     * the FX thread — with {@code beforeCapture} (the close's playback stop)
     * run right before the capture, after any chooser. Its follow-up owns a
     * lost lock while {@code stillWanted} answers {@code true}: the close
     * then shows the exit's takeover dialog, and the save's report shows
     * none. FX thread; returns once the request was made.
     *
     * @param stillWanted   whether the close still wants the outcome, asked when it arrives
     * @param beforeCapture what runs right before the capture
     * @return how the save ended, completed on the FX thread
     */
    CompletionStage<FlushResult> saveBeforeClosing(BooleanSupplier stillWanted, Runnable beforeCapture) {
        Objects.requireNonNull(stillWanted, "stillWanted must not be null");
        Objects.requireNonNull(beforeCapture, "beforeCapture must not be null");
        CompletableFuture<FlushResult> flushed = new CompletableFuture<>();
        SaveFollowUp followUp = new SaveFollowUp() {
            @Override
            public void after(SaveOutcome outcome, boolean sameProject) {
                flushed.complete(switch (outcome) {
                    case SaveOutcome.Saved saved -> new FlushResult.Saved(saved);
                    case SaveOutcome.Failed failed -> failed.cause() instanceof LockStolenException stolen
                            ? new FlushResult.LockLost(stolen) : new FlushResult.SaveFailed(failed);
                });
            }

            @Override
            public boolean ownsLockLoss() {
                return stillWanted.getAsBoolean();
            }
        };
        if (saveOpenProject(followUp, beforeCapture).isEmpty()) {
            flushed.complete(new FlushResult.NotRequested());
        }
        return flushed;
    }

    /**
     * Story 333 (ruling R10) — File ▸ Close Project closed the open
     * project's folder: the window is as at launch, a fresh in-memory
     * Untitled project — New's replacement without its folder
     * ({@link #installFreshUntitledProject}), its tempo the new project's
     * default and loop and punch off — with a new undo history, the rebuilt
     * UI (which retires the outgoing transport controller, after the take
     * the close waited out was published) and the built-in Default layout,
     * as New's; the open project's audio retired, the strip started over,
     * and what depends on the open folder told there is none. The Welcome
     * window is not opened here: the close shows it once its run has ended
     * ({@link #showWelcomeAfterClose}, ruling 4 Q3 and the stage-7 review
     * N9). FX thread.
     */
    void installUntitledAfterClose() {
        openProjectAudio.retire();
        headSavedAt = null;
        progress.checkpointsEnded();
        installFreshUntitledProject();
        projectFolderChangedHook.run();
    }

    /**
     * Story 333 (ruling R10) — the Welcome window over the Untitled project
     * Close Project installed ({@link #installUntitledAfterClose}), shown by
     * the close once its run has ended and no exit runs next — never inside
     * the run, whose fence and pending close would refuse the first-run
     * wizard's own actions (ruling 4 Q3, the stage-7 review N9). FX thread.
     */
    void showWelcomeAfterClose() {
        welcomeAfterClose.run();
    }

    /**
     * Asks whether to save the open project's unsaved changes before a
     * switch or a close replaces it, as {@code prompt} words it (story 333,
     * ruling R2: one Alert for the doors and the close protocol). Titled
     * "Unsaved Changes"; Save, Discard, then Cancel. With
     * {@link UnsavedChangesPrompt#discardApart()} Discard stands apart on the
     * left, Save is the default button (Enter) and Cancel the cancel button
     * (Escape and the window's close box); without it the buttons are the
     * doors' own, as before. Owned by the prompt's owner, when it names one.
     * FX thread.
     *
     * @param prompt what the prompt says, and its buttons
     * @return the answer; the window closed without an answer is a Cancel
     */
    UnsavedChangesAnswer askAboutUnsavedChanges(UnsavedChangesPrompt prompt) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText(prompt.header());
        alert.setContentText(prompt.content());
        ButtonType saveBtn = prompt.discardApart()
                ? new ButtonType(prompt.saveLabel(), ButtonBar.ButtonData.OK_DONE)
                : new ButtonType(prompt.saveLabel());
        ButtonType discardBtn = prompt.discardApart()
                ? new ButtonType(prompt.discardLabel(), ButtonBar.ButtonData.LEFT)
                : new ButtonType(prompt.discardLabel());
        ButtonType cancelBtn = prompt.cancelLabel()
                .map(label -> new ButtonType(label, ButtonBar.ButtonData.CANCEL_CLOSE))
                .orElse(ButtonType.CANCEL);
        alert.getButtonTypes().setAll(saveBtn, discardBtn, cancelBtn);
        if (prompt.discardApart()) {
            // The safe answer is the default: Enter saves; Discard is never the default.
            ((Button) alert.getDialogPane().lookupButton(saveBtn)).setDefaultButton(true);
            ((Button) alert.getDialogPane().lookupButton(discardBtn)).setDefaultButton(false);
        }
        prompt.owner().ifPresent(alert::initOwner);
        ThemeManager.getDefault().applyTo(alert.getDialogPane());

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancelBtn) {
            return UnsavedChangesAnswer.CANCEL;
        }
        return result.get() == saveBtn ? UnsavedChangesAnswer.SAVE : UnsavedChangesAnswer.DISCARD;
    }

    /**
     * A door or a later switch asked while another switch is pending (rulings
     * G2, K3, L3, L4): refused, with one INFO naming the pending switch —
     * {@link #doorDialogStillOpenMessage} while a door's file dialog is open,
     * {@link #STILL_RECOVERING_MESSAGE} while a recovery runs,
     * {@link #STILL_EXTRACTING_MESSAGE} while an archive is extracted;
     * "Still creating the project folder — Save Project continues when the
     * folder has been created." ({@code door.stillCreatingFolder}) while a
     * first save's folder is created for no door (story 333) — one a door's
     * prompt asked for names that door, as below; "Still finishing Save As —
     * try again when it has finished." ({@code door.stillFinishing}) while a
     * Save As copies, publishes and opens its copy, or deletes what a
     * cancelled copy made (story 333; a Save As whose chooser is open is
     * refused as a door whose file dialog is open); the two story-333 texts
     * are bundle values (ruling 4 Q5 (i); the studio engineer's final ruling
     * F13); else "Still saving —
     * &lt;it&gt; continues when the save finishes." for a door waiting for a
     * save; the same
     * refusal again within {@link #DOOR_REFUSAL_WINDOW} shows nothing more
     * (Failure Surfacing book §5.4: one toast per fingerprint per window). A
     * late switch that already did something is refused with a WARNING
     * instead ({@link #whenNoAppWriteIsPending(String, String, BooleanSupplier, Runnable)}).
     * While the close protocol runs ({@link #closing}, story 333) the refusal
     * is a FINE log, not a toast: every window is input-inert then, so only a
     * stale programmatic continuation is refused. FX thread.
     */
    void refuseWhileASwitchIsPending() {
        if (closing != null) {
            LOG.fine(() -> "Refused while " + closing + " closes the open project");
            return;
        }
        String refusal;
        if (doorDialog != null) {
            refusal = doorDialogStillOpenMessage(doorDialog.door());
        } else if (recovering) {
            refusal = STILL_RECOVERING_MESSAGE;
        } else if (extractingArchive) {
            refusal = STILL_EXTRACTING_MESSAGE;
        } else if (creatingFolder != null && waitingDoor == null) {
            refusal = ProjectCloseText.text("door.stillCreatingFolder", creatingFolder.door());
        } else if (saveAsPhase == SaveAsPhase.CHOOSING) {
            refusal = doorDialogStillOpenMessage(SAVE_AS_DOOR);
        } else if (saveAsPhase != null) {
            refusal = ProjectCloseText.text("door.stillFinishing", SAVE_AS_DOOR);
        } else {
            refusal = "Still saving — " + waitingDoor + " continues when the save finishes.";
        }
        long now = System.nanoTime();
        if (refusal.equals(lastDoorRefusal) && now - lastDoorRefusalNanos < DOOR_REFUSAL_WINDOW.toNanos()) {
            LOG.fine(() -> "Refused again: " + refusal);
            return;
        }
        lastDoorRefusal = refusal;
        lastDoorRefusalNanos = now;
        LOG.info(refusal);
        notificationBar.show(NotificationLevel.INFO, refusal);
    }

    /**
     * Story 323 review (Recording Reliability book §5.2): refuses to replace
     * the open project while a recording is in flight or a take is still
     * being written to disk. A recording in flight — an audio take being
     * prepared (its take directory being allocated or its files being
     * created) or whose pipeline is active, or a live MIDI recorder —
     * records into the open project, whose tracks its take is published
     * into. Every Stop of an audio take leaves the take FINALIZING until the
     * FX turn posted by its capture thread's termination has published the
     * take into the project it was recorded in (or reported why it could
     * not), and a start that was cancelled, or that failed once its take
     * directory existed, counts as FINALIZING until the FX turn after the
     * removal of its files has run, whether or not everything could be
     * removed — the same rule that refuses Record meanwhile. Shows
     * {@link #PROJECT_CHANGE_WHILE_RECORDING_MESSAGE} or
     * {@link #PROJECT_CHANGE_WHILE_WRITING_MESSAGE} as a WARNING toast, with
     * no modal dialog, and logs it. Only the in-app doors that call this are
     * refused; the exit protocol and Close Project wait for the take instead
     * (story 333, ruling R1).
     *
     * @return {@code true} if a recording is in flight or a take is still
     *         being written, and the change is refused
     */
    private boolean refuseWhileATakeIsRecordingOrBeingWritten() {
        return refuseWithWarning(PROJECT_CHANGE_WHILE_WRITING_MESSAGE,
                PROJECT_CHANGE_WHILE_RECORDING_MESSAGE);
    }

    /**
     * The refusal of a load that follows work already done off the FX thread
     * — an archive extracted, recovered changes written, a journal discarded,
     * a recovery that failed. The same refusal as
     * {@link #refuseWhileATakeIsRecordingOrBeingWritten()}, but its one
     * WARNING ({@link #lateLoadRefusalMessage}, or
     * {@link #lateLoadRefusalWhileRecordingMessage} while a recording is in
     * flight) says what that work did, since the load it refuses was that
     * work's outcome. Asked by the caller in the FX turn that would load, so
     * {@link #loadProjectFromPath} is not reached and does not refuse a
     * second time.
     *
     * @param alreadyDone what the work did, a sentence without its full stop
     * @return {@code true} if a recording is in flight or a take is still
     *         being written, and the load is refused
     */
    private boolean refuseLateLoadWhileATakeIsRecordingOrBeingWritten(String alreadyDone) {
        return refuseWithWarning(lateLoadRefusalMessage(alreadyDone),
                lateLoadRefusalWhileRecordingMessage(alreadyDone));
    }

    /**
     * Logs and shows {@code whileWriting} as the WARNING while a take is
     * still being written, else {@code whileRecording} while a recording is
     * in flight. The take being written is asked about first, so it is the
     * one named were both ever true; Record refuses while a take is being
     * written ({@code TransportController}), so no recording starts then.
     *
     * @return {@code true} if the change is refused
     */
    private boolean refuseWithWarning(String whileWriting, String whileRecording) {
        String warning;
        if (takeBeingWritten.getAsBoolean()) {
            warning = whileWriting;
        } else if (recordingInFlight.getAsBoolean()) {
            warning = whileRecording;
        } else {
            return false;
        }
        LOG.warning("Project change refused — " + warning);
        notificationBar.show(NotificationLevel.WARNING, warning);
        return true;
    }

    /**
     * The WARNING of {@link #refuseLateLoadWhileATakeIsRecordingOrBeingWritten}
     * while a take is being written: what the work already did, that the
     * project was not opened and why, and that it can be opened once the
     * take has been finished — written and, for its publication, read back.
     */
    static String lateLoadRefusalMessage(String alreadyDone) {
        return alreadyDone + "; the project was not opened, because the open project can't be replaced"
                + " until the last take has been finished — open it once it has,"
                + " and keep the application open until then";
    }

    /**
     * The WARNING of {@link #refuseLateLoadWhileATakeIsRecordingOrBeingWritten}
     * while a recording is in flight: what the work already did, that the
     * project was not opened and why, and that it can be opened once the
     * recording has been stopped.
     */
    static String lateLoadRefusalWhileRecordingMessage(String alreadyDone) {
        return alreadyDone + "; the project was not opened, because the open project can't be replaced"
                + " while recording — stop the recording, then open it";
    }

    /**
     * Loads a project from the given directory. Refused, before the current
     * project is abandoned, while a recording is in flight or a take is still
     * being written to disk ({@link #refuseWhileATakeIsRecordingOrBeingWritten()}):
     * the doors that pass {@link #whenProjectMayClose} first have been
     * refused already, but the recovery flow reaches this after its scan off
     * the FX thread, and a recording may have started, or a take started
     * being written, meanwhile. The archive restore, the journal replay and a
     * journal discard that deleted the journal, whose work off the FX thread
     * comes before their load, refuse first themselves
     * ({@link #refuseLateLoadWhileATakeIsRecordingOrBeingWritten}), so the
     * user is told what that work did.
     *
     * @return {@code true} if the project loaded successfully,
     *         {@code false} if it was refused or failed (the warning or error
     *         is already surfaced in the notification bar)
     */
    boolean loadProjectFromPath(Path projectDir) {
        return loadProjectFromPath(projectDir, OpenDoor.PLAIN);
    }

    /**
     * Loads a project from the given directory through {@code door} (story
     * 329; Persistence Integrity book §5.3): refused as
     * {@link #loadProjectFromPath(Path)} says, then
     * <ol>
     *   <li>PARSE — {@link ProjectManager#openProject} reads and deserializes
     *       the project (its clips carry references, no samples);</li>
     *   <li>ACCOUNT — the open's missing-assets report
     *       ({@link ProjectManager#getLastMissingAssets()}), classified
     *       without decoding anything, and the saved items the open could
     *       not honour as written ({@link ProjectManager#getLastUnreadItems()});</li>
     *   <li>the project is installed as the live project, clean;</li>
     *   <li>REHYDRATE — {@link OpenProjectAudio#begin} retires the previous
     *       open's audio and queues the decode of every clip source the
     *       report does not name, off the FX thread; an open that failed
     *       before here left the project on screen and its audio alone
     *       (decision D31);</li>
     *   <li>CASCADE — the UI is rebuilt and the project's layout applied;</li>
     *   <li>the migration report dialog, when the load ran a migration that
     *       changes what the user authored; its Roll back, run once the
     *       dialog closed, may re-enter this method (decisions D41, D42);</li>
     *   <li>ANNOUNCE — unless a Roll back re-entered this method, and only
     *       if this open is still the current one, the door's one
     *       announcement, qualified by the missing assets and the unread
     *       items, and the report, presented once;</li>
     *   <li>the write-ahead journal opens, on the same two conditions as the
     *       announcement; a Roll back with no backup abandons the project
     *       without re-entering this method, so it opens none (decision
     *       D48).</li>
     * </ol>
     * The announcement is the only notification a successful load shows:
     * the doors that used to add their own pass their {@link OpenDoor}
     * instead. Decodes never hold the open: the window is interactive while
     * clips load, each showing {@code Loading…} until its audio lands. A
     * load a Roll back re-entered is superseded by the inner load, which
     * says what happened — its announcement, or its error — and opens the
     * journal when it succeeds: the outer load then announces nothing,
     * opens no journal and returns the inner load's result.
     *
     * @param projectDir the project directory
     * @param door       how the project is being opened
     * @return {@code true} if the project loaded successfully,
     *         {@code false} if it was refused or failed (the warning or error
     *         is already surfaced in the notification bar); for a load a
     *         Roll back superseded, the superseding load's result
     */
    boolean loadProjectFromPath(Path projectDir, OpenDoor door) {
        Objects.requireNonNull(door, "door must not be null");
        if (openRecoveryGate != null) {
            if (recovering) { refuseWhileASwitchIsPending(); return false; }
            if (refuseWhileATakeIsRecordingOrBeingWritten()) return false;
            if (sameOpenFolder(projectDir) && !(door instanceof OpenDoor.MigrationRollback)) {
                notificationBar.show(NotificationLevel.INFO, "Project is already open: " + projectDir);
                return false;
            }
        }
        long attempt = ++loadAttempts;
        boolean loaded = loadAttempt(projectDir, door, attempt);
        lastLoadResult = loaded;
        return loaded;
    }

    /** One {@link #loadProjectFromPath(Path, OpenDoor)} call, numbered {@code attempt}. */
    private boolean loadAttempt(Path projectDir, OpenDoor door, long attempt) {
        if (openRecoveryGate != null) {
            beginGatedLoad(projectDir, door, attempt);
            return true;
        }
        if (refuseWhileATakeIsRecordingOrBeingWritten()) {
            return false;
        }
        try {
            // Story 329 (D31) — the open project's audio is retired only once the
            // new one parsed (begin() retires it): a failed open leaves the project
            // still on screen loading, reported and relinkable.
            abandonOpenProject();
            projectManager.openProject(projectDir);
            return finishLoad(projectDir, door, attempt, false);
        } catch (MigrationException e) {
            // Unmapped or broken migration chain — surface as an error
            // notification rather than letting the runtime exception
            // bubble out of the menu/action handler.
            notificationBar.show(NotificationLevel.ERROR,
                    "Migration failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to migrate project on open", e);
            return false;
        } catch (IOException e) {
            notificationBar.show(NotificationLevel.ERROR,
                    "Open failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to open project", e);
            return false;
        }
    }

    /**
     * A parsed project, installed and about to be shown.
     *
     * @param project                   the installed project
     * @param unread                    the saved items its read could not honour as written
     * @param generation                its audio's load
     * @param migrationDialogSuppressed whether the user chose "Don't show again" for the migration
     *                                  its load ran, read off the FX thread (story 333)
     */
    private record PreparedLoad(DawProject project, List<UnreadProjectItem> unread,
                                OpenProjectAudio.Generation generation, boolean migrationDialogSuppressed) { }

    private PreparedLoad prepareLoadedProject(Path projectDir, boolean migrationDialogSuppressed) {
            // Story 331 (G13 #4) — the strip starts over, its time the opened file's.
            projectReplaced(openedFileLastSaved());
            DawProject dawProject = projectManager.getCurrentDawProject();
            if (dawProject == null) {
                dawProject = new DawProject(
                        projectManager.getCurrentProject().name(),
                        AudioFormat.STUDIO_QUALITY);
            }
            MissingAssetsReport missingAssets = projectManager.getLastMissingAssets();
            // Story 330 — captured with the missing assets, before the
            // migration dialog's Roll back can re-enter the load and replace
            // the project manager's lists.
            List<UnreadProjectItem> unreadItems = projectManager.getLastUnreadItems();
            deps.setProject().accept(dawProject);
            deps.setUndoManager().accept(new UndoManager());
            deps.rebuildHistoryPanel().run();
            deps.resetTrackCounters().run();
            deps.project().get().markClean();
            OpenProjectAudio.Generation generation = openProjectAudio.begin(dawProject, projectDir,
                    missingAssets, dawProject.getTransport().getPositionInBeats());
        return new PreparedLoad(dawProject, unreadItems, generation, migrationDialogSuppressed);
    }

    /**
     * The install of an open made without the recovery gate, on the FX
     * thread — where that open read the project (known limit, story 384) —
     * so its migration dialog's "Don't show again" marker is read here too.
     */
    private boolean finishLoad(Path projectDir, OpenDoor door, long attempt, boolean journalPrepared) {
        return finishPreparedLoad(projectDir, door, attempt, journalPrepared,
                prepareLoadedProject(projectDir, migrationDialogSuppressed(projectDir)));
    }

    private boolean finishPreparedLoad(Path projectDir, OpenDoor door, long attempt, boolean journalPrepared,
                                       PreparedLoad loaded) {
        DawProject dawProject = loaded.project();
        List<UnreadProjectItem> unreadItems = loaded.unread();
        OpenProjectAudio.Generation generation = loaded.generation();
            rebuildUI();
            // Story 282 — Mission Control. Apply the layout JSON blob
            // embedded in the project file (may be {@code null} for
            // legacy projects, in which case the layout manager falls
            // back to the Default built-in).
            deps.applyLayoutJson().accept(dawProject.getLayoutJson());
            LOG.info("Opened project from " + projectDir);
            // Story 247 — surface the migration report dialog when the
            // load ran a registry-driven schema migration that changes what
            // the user authored (story 330: a format-only step opens
            // silently), unless the user already chose "Don't show again"
            // for this project — read before this FX step (story 333).
            maybeShowMigrationReport(projectDir, loaded.migrationDialogSuppressed());
            // Story 329 (D41) — a Roll back re-entered this method: the inner
            // load said what happened, announced or failed, and opened the
            // journal if it succeeded. This load stays quiet and answers with
            // the inner load's result.
            if (attempt != loadAttempts) {
                return lastLoadResult;
            }
            // Story 329 — the one announcement, after the migration dialog,
            // and only if the open is still the current one.
            if (openProjectAudio.isCurrent(generation)) {
                openProjectAudio.announce(generation, door, projectDir, unreadItems);
                // Story 298 — open the write-ahead journal for the just-loaded
                // project (no-op when journaled persistence is disabled). The hook
                // is set by MainController, which owns the journal coordinator
                // alongside the checkpoint manager + session it must seal.
                // Story 329 (D48) — only for the open still current: a Roll back
                // with no .bak abandoned this project for an Untitled one without
                // re-entering the load, so its folder gets no journal.
                if (!journalPrepared && !projectManager.isReadOnly()) notifyJournalOpen(projectDir);
            }
            return true;
    }

    /**
     * Whether the migration report dialog of the load the project manager
     * just made is suppressed for {@code projectDir} — the user chose "Don't
     * show again" for its version ({@link MigrationSuppression}). Reads the
     * marker from disk: called off the FX thread by the gated open (story
     * 333), and on it only by an open made without the gate. {@code false}
     * when no migration that changes what the user authored ran, without
     * reading anything.
     */
    private boolean migrationDialogSuppressed(Path projectDir) {
        MigrationReport report = projectManager.getLastMigrationReport();
        return report != null && report.changesAuthoredContent()
                && MigrationSuppression.isSuppressed(projectDir, report.toVersion());
    }

    /**
     * Invokes {@link MigrationReportDialog#showUnlessSuppressed} for the
     * just-loaded project when the load ran a schema migration that changes
     * what the user authored ({@link MigrationReport#changesAuthoredContent()},
     * the gate {@code showUnlessSuppressed} applies too): a load whose
     * migrations only re-express the file format opens without the dialog —
     * it is still backed up on its first save, listed in the Migration
     * History and badged on the hub, all of which follow
     * {@link MigrationReport#wasMigrated()}. Nothing is read from disk here
     * (story 333): whether the user chose "Don't show again" was read before,
     * off the FX thread ({@link #migrationDialogSuppressed}). Wired with a
     * roll-back action that restores the most recent sibling
     * {@code project.daw.v<n>.*.bak} (created by the first save after a
     * previous migrated load) and reloads the project. When no backup
     * exists yet, rolling back simply abandons the in-memory migrated
     * state — the on-disk file is still the pre-migration original.
     *
     * <p>Package-private so tests can drive it directly without going
     * through a directory chooser.</p>
     *
     * @param projectDir the project's folder
     * @param suppressed whether the user chose "Don't show again" for this migration
     */
    void maybeShowMigrationReport(Path projectDir, boolean suppressed) {
        MigrationReport report = projectManager.getLastMigrationReport();
        if (report == null || !report.changesAuthoredContent()) {
            return;
        }
        MigrationReportDialog.showUnlessSuppressed(report, projectDir, suppressed,
                () -> rollbackMigration(projectDir, report));
    }

    void showMigrationHistoryForCurrentProject() {
        ProjectMetadata current = projectManager.getCurrentProject();
        if (current == null || current.projectPath() == null) {
            notificationBar.show(NotificationLevel.WARNING, "No project history to show");
            return;
        }
        showMigrationHistory(current.projectPath());
    }

    void showMigrationHistory(Path projectDir) {
        if (projectDir == null) {
            notificationBar.show(NotificationLevel.WARNING, "No project history to show");
            return;
        }
        MigrationHistoryView view = new MigrationHistoryView(fxDispatcher);
        view.setOnDiffRequested(event ->
                showMigrationDiff(projectDir, event.getEntry()));
        view.setOnRollbackRequested(event ->
                rollbackMigration(projectDir, reportFor(event.getEntry()), event.getBackupPath()));
        view.refreshFromProjectDirectory(projectDir);
        showInOwnedStage("Migration History", view);
    }

    /**
     * Rolls back to the pre-migration version. If a sibling
     * {@code project.daw.v<fromVersion>.*.bak} exists, the newest one
     * is copied over {@code project.daw} and the project is reloaded
     * (which will re-trigger the migration in memory, but the on-disk
     * file once again carries the user's original schema). When no
     * backup is found, the in-memory migrated project is discarded —
     * the original file on disk has not been overwritten yet.
     */
    void rollbackMigration(Path projectDir, MigrationReport report) {
        Optional<Path> newestBackup = findNewestBackup(projectDir, report.fromVersion());
        rollbackMigration(projectDir, report, newestBackup.orElse(null));
    }

    void rollbackMigration(Path projectDir, MigrationReport report, Path selectedBackup) {
        // Story 323 review: a roll-back reloads or abandons the open project,
        // so it is refused while a recording is in flight or a take is being
        // written — before the backup is copied over project.daw, so disk and
        // memory are left as they are. Story 331: it passes the switch point,
        // so its copy over project.daw waits for a save of the open project
        // this controller asked for, which would otherwise overwrite it.
        whenNoAppWriteIsPending(ROLL_BACK_MIGRATION_DOOR, this::refuseWhileATakeIsRecordingOrBeingWritten,
                () -> rollBackNow(projectDir, report, selectedBackup));
    }

    /**
     * {@link #rollbackMigration(Path, MigrationReport, Path)} once no app
     * write is pending. The backup replaces {@code project.daw} atomically
     * (story 331): a failed copy leaves it as it was. A {@code project.daw}
     * that is a symbolic link to a {@code .daw} file stays a link, and that
     * file is replaced; one that leads anywhere else is refused, and nothing
     * is copied (ruling M3). FX thread, as before
     * story 331 — the copy blocks on storage there until story 373 moves it
     * off.
     */
    private void rollBackNow(Path projectDir, MigrationReport report, Path selectedBackup) {
        Path projectFile = projectDir.resolve("project.daw");
        try {
            if (selectedBackup != null && Files.isRegularFile(selectedBackup)) {
                fileWriter.copy(selectedBackup, projectFile, AtomicFileWriter.Overwrite.REPLACE,
                        AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE);
                LOG.info("Rolled back migration: restored " + selectedBackup
                        + " over " + projectFile);
                // Story 329 — the reload's one announcement says what the
                // roll-back did, qualified by what it could not find.
                loadProjectFromPath(projectDir, new OpenDoor.MigrationRollback(report.fromVersion()));
            } else {
                // No backup yet: the migrated DawProject lives only in
                // memory, so abandoning is a complete rollback.
                replaceAfterJournalClose(ROLL_BACK_MIGRATION_DOOR, attempt -> {
                    try {
                        deps.setProject().accept(new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY));
                        projectReplaced(null);
                        deps.setUndoManager().accept(new UndoManager());
                        deps.rebuildHistoryPanel().run();
                        deps.resetTrackCounters().run();
                        deps.project().get().markClean();
                        rebuildUI();
                        notificationBar.show(NotificationLevel.SUCCESS,
                                "Discarded in-memory migration; the on-disk file is "
                                        + "still the original (v" + report.fromVersion() + ")");
                        LOG.info("Rolled back migration by abandoning in-memory project (no .bak yet)");
                    } finally { finishReplacement(attempt); }
                });
            }
        } catch (IOException e) {
            notificationBar.show(NotificationLevel.ERROR,
                    "Roll back failed: " + e.getMessage());
            LOG.log(Level.WARNING, "Failed to roll back migration", e);
        }
    }

    private void showMigrationDiff(Path projectDir,
                                   MigrationHistoryView.MigrationHistoryEntry entry) {
        Path current = projectDir.resolve("project.daw");
        Path backup = entry.backupPath();
        Thread.ofVirtual().name("daw-migration-diff").start(() -> {
            try {
                String currentText = Files.readString(current);
                String backupText = Files.readString(backup);
                String diff = diffText(current, backup, currentText, backupText);
                postFx(() -> showModelessStage("Migration Diff", diffView(diff), 860, 560));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to diff migration backup " + backup, e);
                postFx(() -> notificationBar.show(NotificationLevel.ERROR,
                        "Diff failed: " + e.getMessage()));
            }
        });
    }

    private static Parent diffView(String diff) {
        TextArea text = new TextArea(diff);
        text.setEditable(false);
        text.setWrapText(false);
        text.getStyleClass().add("snapshot-preview");
        VBox box = new VBox(8, text);
        box.setPrefSize(860, 560);
        VBox.setVgrow(text, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    private void showModelessStage(String title, Parent content, double width, double height) {
        if (!content.getStyleClass().contains("root-pane")) {
            content.getStyleClass().add("root-pane");
        }
        Scene scene = new Scene(content, width, height);
        ThemeManager.getDefault().applyTo(scene);
        DensityManager.getDefault().applyTo(scene);
        Stage stage = new Stage();
        stage.setTitle(title);
        Window owner = rootPane.getScene() != null ? rootPane.getScene().getWindow() : null;
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setScene(scene);
        stage.show();
    }

    private static String diffText(Path current,
                                   Path backup,
                                   String currentText,
                                   String backupText) {
        StringBuilder out = new StringBuilder();
        out.append("Current: ").append(current).append('\n');
        out.append("Backup : ").append(backup).append("\n\n");
        if (Objects.equals(currentText, backupText)) {
            out.append("No textual differences.");
            return out.toString();
        }
        List<String> currentLines = currentText.lines().toList();
        List<String> backupLines = backupText.lines().toList();
        int max = Math.max(currentLines.size(), backupLines.size());
        int shown = 0;
        for (int i = 0; i < max && shown < 200; i++) {
            String oldLine = i < backupLines.size() ? backupLines.get(i) : "";
            String newLine = i < currentLines.size() ? currentLines.get(i) : "";
            if (Objects.equals(oldLine, newLine)) {
                continue;
            }
            out.append("@@ line ").append(i + 1).append(" @@\n")
                    .append("- ").append(truncateLine(oldLine)).append('\n')
                    .append("+ ").append(truncateLine(newLine)).append("\n\n");
            shown++;
        }
        if (shown >= 200) {
            out.append("... additional differences omitted ...\n");
        }
        return out.toString();
    }

    private static String truncateLine(String line) {
        return line.length() <= 240 ? line : line.substring(0, 240) + "...";
    }

    private static MigrationReport reportFor(MigrationHistoryView.MigrationHistoryEntry entry) {
        String description = entry.bullets().isEmpty()
                ? "historical migration"
                : String.join("; ", entry.bullets());
        return new MigrationReport(
                entry.fromSchemaVersion(),
                entry.toSchemaVersion(),
                List.of(new MigrationReport.AppliedMigration(
                        entry.fromSchemaVersion(),
                        entry.toSchemaVersion(),
                        description)),
                entry.timestamp());
    }

    /**
     * Finds the newest sibling {@code project.daw.v<fromVersion>.*.bak}
     * file in the project directory, or empty if none exists.
     */
    private static Optional<Path> findNewestBackup(Path projectDir, int fromVersion) {
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return Optional.empty();
        }
        String prefix = "project.daw.v" + fromVersion + ".";
        try (Stream<Path> entries = Files.list(projectDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".bak");
                    })
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Abandons the open project before a door replaces it without reading a
     * project file — New project, Import session, a roll-back that abandons
     * the migrated project — and, first, retires its audio (story 329,
     * decisions D21, D31): its decodes are cancelled, its report window
     * closes and nothing it still had in flight reaches the project that
     * replaces it. An open from disk retires the audio only once the new
     * project parsed.
     */
    void resetProjectState() {
        replaceAfterJournalClose(NEW_PROJECT_DOOR, this::finishReplacement);
    }

    /**
     * Keeps editing and transport fenced until the replacement's durable state is ready.
     *
     * @param door        the door that replaces the open project, as {@link PendingSwitch.Installing} names it
     * @param replacement the replacement, given its attempt ({@code -1} without the recovery gate)
     */
    private void replaceAfterJournalClose(String door, java.util.function.LongConsumer replacement) {
        if (openRecoveryGate == null) {
            openProjectAudio.retire();
            abandonOpenProject();
            replacement.accept(-1L);
            return;
        }
        long attempt = ++loadAttempts;
        raiseInstallationFence(door);
        try {
            startRecoveryWorker("daw-project-replacement", () -> {
                boolean detached = false;
                try {
                    Runnable close = callOnFx(() -> {
                        if (refuseWhileATakeIsRecordingOrBeingWritten()) return null;
                        return prepareJournalClose.get();
                    });
                    if (close == null) {
                        postFx(() -> finishReplacement(attempt));
                        return;
                    }
                    detached = true;
                    close.run();
                    boolean mayReplace = callOnFx(() ->
                            !refuseLateLoadWhileATakeIsRecordingOrBeingWritten("The outgoing journal was closed"));
                    if (!mayReplace) {
                        postFx(() -> {
                            notificationBar.show(NotificationLevel.WARNING,
                                    "Project switch cancelled; recovery evidence was preserved. Journaled persistence is paused until this project is reopened.");
                            finishReplacement(attempt);
                        });
                        return;
                    }
                    projectManager.closeProject();
                    postFx(() -> {
                        if (attempt != loadAttempts) return;
                        openProjectAudio.retire();
                        headSavedAt = null;
                        progress.checkpointsEnded();
                        try { replacement.accept(attempt); }
                        catch (RuntimeException | Error failure) {
                            finishReplacement(attempt);
                            notificationBar.show(NotificationLevel.ERROR, "Project replacement failed: " + failureReason(failure));
                        }
                    });
                } catch (RuntimeException | Error failure) {
                    boolean journalDetached = detached;
                    postFx(() -> {
                        finishReplacement(attempt);
                        notificationBar.show(NotificationLevel.ERROR, "Project close failed: " + failureReason(failure)
                                + (journalDetached ? "; recovery evidence was preserved. Journaled persistence is paused until this project is reopened." : ""));
                    });
                }
            });
        } catch (RuntimeException | Error failure) {
            finishReplacement(attempt);
            throw failure;
        }
    }

    private void finishReplacement(long attempt) {
        if (attempt >= 0L) finishOpenAttempt(attempt);
    }

    /**
     * Abandons the open project in the project manager, leaving its audio
     * alone: the open path's first step, before PARSE (decision D31). The
     * audio is retired only when the new project replaces it
     * ({@link OpenProjectAudio#begin}); an open that fails before then
     * leaves the project on screen with its clips still loading, its report
     * and its relink. Its checkpoints end with it, so the strip shows its
     * last good checkpoint no more (story 331, ruling K1), nor a countdown:
     * "No checkpoint scheduled" until a project is opened or created and its
     * checkpoints re-arm (ruling N2); the Saved cell is left as it is. And
     * the project manager holds no {@code project.daw} of it any more, so
     * none was written for it ({@link #headSavedAt}) until a replacement says
     * when the replacing project's was.
     */
    private void abandonOpenProject() {
        if (projectManager.getCurrentProject() != null) {
            projectManager.closeProject();
        }
        headSavedAt = null;
        progress.checkpointsEnded();
    }

    private void rebuildUI() {
        trackListPanel.getChildren().clear();
        Label header = new Label("TRACKS");
        header.getStyleClass().add("panel-header");
        // No icon-next-to-label per UI Design Book §2.4.
        trackListPanel.getChildren().add(header);
        // Story 322 fix round (N1): the host owns the control-wiring generation
        // and constructs the fresh MixerView over it (MainController
        // .handleProjectRebuild), so the view's first refresh() binds through
        // that generation instead of building a standalone wiring first.
        deps.onProjectUIRebuild().run();
    }

    // ── Project Hub / Welcome (story 296) ─────────────────────────────────────

    /**
     * Builds the §4.2 Project Hub bound to this manager's recent projects, a
     * fresh {@link ProjectHealthScanner}, and the open / reveal actions. Each
     * card's size + health is scanned on a background virtual thread and
     * marshalled to the FX thread through the {@link FxDispatcher} (§2.1) — the
     * FX thread never walks a project tree.
     *
     * @return a new Project Hub view
     */
    ProjectHubView buildProjectHub() {
        FxDispatcher d = resolveDispatcher();
        // Self-reference so "Open" can dismiss the Hub window before loading;
        // "Reveal" is a side action and leaves the Hub open.
        ProjectHubView[] ref = new ProjectHubView[1];
        ProjectHubView hub = new ProjectHubView(
                projectManager.getRecentProjectPaths(),
                new ProjectHealthScanner(d),
                projectDir -> {
                    if (projectDir != null) {
                        whenProjectMayClose(OPEN_PROJECT_DOOR, () -> {
                            dismiss(ref[0]);
                            loadProjectFromPath(projectDir);
                        });
                    }
                },
                this::revealInFileBrowser,
                this::clearRecentProjects);
        hub.setOnMigrationHistory(this::showMigrationHistory);
        ref[0] = hub;
        return hub;
    }

    /**
     * Clears the recent-projects list (the Hub's §1.4 "Clear Recent" action,
     * carried over from the retired recent-projects {@code ContextMenu}).
     */
    private void clearRecentProjects() {
        RecentProjectsStore store = projectManager.getRecentProjectsStore();
        if (store != null) {
            store.clear();
        }
        notificationBar.show(NotificationLevel.SUCCESS, "Recent projects cleared");
    }

    /**
     * Builds the §4.1 Welcome screen (Recover → Continue → Start) bound to this
     * manager's recent projects. The four Start tiles route to the existing
     * lifecycle flows; a Continue card routes through
     * {@link #openProjectFromHub(Path, Runnable)}, and "Recover" through
     * {@link #recoverAction}, by default {@link #beginRecovery(Path, Runnable)}
     * (story 298's journal-replay recovery). Each gated action dismisses the
     * window inside its gate's proceed, so a refused one leaves it up (story
     * 331); "Discard recovery", which nothing gates, dismisses it first.
     *
     * @return a new Welcome view
     */
    WelcomeView buildWelcomeView() {
        FxDispatcher d = resolveDispatcher();
        // Self-reference so a Welcome action dismisses the launch window once
        // its flow may go on — inside the gate's proceed, as the Hub does
        // (story 331): a refused action leaves the window, and its recover
        // rows, up — then runs the chosen flow over the main window.
        WelcomeView[] ref = new WelcomeView[1];
        Runnable dismissWelcome = () -> dismiss(ref[0]);
        WelcomeView view = new WelcomeView(
                projectManager.getRecentProjectPaths(),
                new ProjectHealthScanner(d),
                d,
                java.time.InstantSource.system(),
                () -> newProject(dismissWelcome),
                projectDir -> openProjectFromHub(projectDir, dismissWelcome),
                // Story 298 — Recover runs the crash-recovery flow (scan →
                // RecoveryDialog → replay) over the main window via the
                // overridable seam, which dismisses the launch window once
                // the recovery may begin.
                projectDir -> recoverAction.accept(projectDir, dismissWelcome),
                // Discard replaces no project, so no gate refuses it: it
                // dismisses, then confirm-deletes the journal/ dir.
                projectDir -> { dismiss(ref[0]); discardAction.accept(projectDir); },
                () -> openProject(dismissWelcome),
                () -> restoreFromArchive(dismissWelcome),
                () -> importSession(dismissWelcome));
        ref[0] = view;
        return view;
    }

    /** Hides the window hosting {@code surface} (a Hub / Welcome launch surface), if shown. */
    private static void dismiss(javafx.scene.Node surface) {
        if (surface != null && surface.getScene() != null && surface.getScene().getWindow() != null) {
            surface.getScene().getWindow().hide();
        }
    }

    /**
     * Presents the §4.1 Welcome screen (shown on launch when no project is
     * open). Routed through the {@link #welcomePresenter} seam so a test can
     * capture it without opening a window.
     */
    void showWelcome() {
        welcomePresenter.accept(buildWelcomeView());
    }

    /**
     * Opens {@code projectDir} once {@link #whenProjectMayClose} lets it,
     * running {@code proceeding} first in the gate's proceed (Welcome
     * action).
     */
    private void openProjectFromHub(Path projectDir, Runnable proceeding) {
        if (projectDir != null) {
            whenProjectMayClose(OPEN_PROJECT_DOOR, () -> {
                proceeding.run();
                loadProjectFromPath(projectDir);
            });
        }
    }

    // ── Crash recovery (story 298, Project Manager Design Book §4.6.1) ─────────

    /**
     * The §4.6.1 crash-recovery entry point. Scans {@code projectDir} for a
     * recoverable checkpoint + journal on a virtual thread (the FX thread never
     * does scan I/O), then marshals back:
     *
     * <ul>
     *   <li>no summary / no journal → just open the project normally;</li>
     *   <li>otherwise show the {@link RecoveryDialog} and branch on the
     *       {@link RecoveryChoice}: {@code REPLAY_ALL} loads the journal's
     *       verified recovery point (JournalReplayer.recover) and opens the
     *       recovered state;
     *       {@code CHECKPOINT_ONLY} opens the project as-is; {@code INSPECT_EVENTS}
     *       lists the captured event types and leaves the project unopened;
     *       {@code CANCEL} does nothing.</li>
     * </ul>
     *
     * <p>Recover Session is the one switch pending from this FX step until
     * its last load passes the switch point or it ends without one (story
     * 331, ruling L3): through the scan, the {@link RecoveryDialog}, the
     * journal's delete and the replay — any switch asked meanwhile is
     * refused with {@link #STILL_RECOVERING_MESSAGE} — and through the wait
     * for the app's saves before the replay, as the switch waiting. Each
     * worker hands over to the switch point that follows it in one FX step,
     * so the recovery's own switch points never refuse it, and nothing
     * thrown on a worker leaves it pending. Recover asked while another
     * switch is pending is refused with that switch's INFO, before the
     * recording and take refusal.</p>
     *
     * <p>Without the open recovery gate (test-only; production always
     * installs it) this path's choices do not match every sentence of the
     * dialog, which describes the gated choices; a future story retires
     * it.</p>
     *
     * <p>Package-private so a test can drive it directly.</p>
     *
     * @param projectDir the project to recover; {@code null} is ignored
     */
    void beginRecovery(Path projectDir) {
        beginRecovery(projectDir, () -> { });
    }

    /**
     * {@link #beginRecovery(Path)}, running {@code proceeding} once neither
     * refusal applies, right before the scan starts: the Welcome window's
     * Recover dismisses the window there, so a refused Recover leaves it, and
     * its recover rows, up (story 331). FX thread.
     *
     * @param projectDir the project to recover; {@code null} is ignored
     * @param proceeding run when the recovery begins
     */
    private void beginRecovery(Path projectDir, Runnable proceeding) {
        if (projectDir == null) {
            return;
        }
        if (aSwitchIsPending()) {
            refuseWhileASwitchIsPending();
            return;
        }
        // Story 323 review: recovery opens a project, so it is refused while
        // a recording is in flight or a take is being written, before the
        // scan; its later loads are refused again if a recording has started,
        // or a take has started being written, meanwhile.
        if (refuseWhileATakeIsRecordingOrBeingWritten()) {
            return;
        }
        if (openRecoveryGate != null) {
            proceeding.run();
            whenNoAppWriteIsPending(RECOVER_SESSION_DOOR, this::refuseWhileATakeIsRecordingOrBeingWritten,
                    () -> loadProjectFromPath(projectDir));
            return;
        }
        proceeding.run();
        ProjectContext ctx = ProjectContext.forProject(projectDir);
        startRecoveryWorker(RECOVERY_SCAN_WORKER, () -> scanForRecovery(projectDir, ctx));
    }

    /**
     * The scan's worker: a scan that throws — anything — opens the last
     * clean save, as a failed scan always did. Off the FX thread.
     */
    private void scanForRecovery(Path projectDir, ProjectContext ctx) {
        Optional<RecoverySummary> summary;
        try {
            beforeRecoveryWork.accept(RECOVERY_SCAN_WORKER);
            summary = new RecoveryScanner().plan(ctx, new com.benesquivelmusic.daw.core.persistence.RecoveryTemps(
                    List.of(), null), true).map(com.benesquivelmusic.daw.core.persistence.journal.RecoveryPlan::summary);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Recovery scan failed for " + projectDir, e);
            postFx(() -> afterTheRecoveryWorker(() -> openLastCleanSaveAfterFailedScan(projectDir)));
            return;
        } catch (Throwable e) {
            LOG.log(Level.SEVERE, "Recovery scan of " + projectDir + " failed unexpectedly", e);
            postFx(() -> afterTheRecoveryWorker(() -> openLastCleanSaveAfterFailedScan(projectDir)));
            return;
        }
        postFx(() -> presentRecovery(projectDir, summary.orElse(null)));
    }

    /** A failed scan's load, past the switch point: it did nothing, so a refusal is the plain INFO. FX thread. */
    private void openLastCleanSaveAfterFailedScan(Path projectDir) {
        whenNoAppWriteIsPending(RECOVER_SESSION_DOOR, this::refuseWhileATakeIsRecordingOrBeingWritten,
                () -> loadProjectFromPath(projectDir));
    }

    /**
     * Makes Recover Session the one switch pending and starts {@code work}
     * on a virtual thread named {@code name}; a worker that cannot start
     * leaves no recovery pending (ruling L3). FX thread.
     */
    private void startRecoveryWorker(String name, Runnable work) {
        setRecovering(true);
        try {
            Thread.ofVirtual().name(name).start(work);
        } catch (RuntimeException | Error notStarted) {
            setRecovering(false);
            throw notStarted;
        }
    }

    /**
     * A recovery worker's outcome, on the FX thread: Recover Session hands
     * its pending state over to {@code switchPoint}, the switch point that
     * follows, in this FX step (ruling L3).
     */
    private void afterTheRecoveryWorker(Runnable switchPoint) {
        setRecovering(false);
        switchPoint.run();
    }

    /**
     * FX-thread: show the recovery dialog (or open directly when nothing to
     * recover), Recover Session the one switch pending while it asks (ruling
     * L3). Each load passes the switch point first
     * ({@link #whenNoAppWriteIsPending}, story 331); so does the replay,
     * which writes {@code project.daw}, before it starts — refusing nothing
     * there: a recording started meanwhile refuses the load that follows the
     * replay, which then says what the replay wrote (story 323 review).
     * Inspect and Cancel end the recovery; so does anything thrown here,
     * which is logged at SEVERE with its stack, shown as the ERROR "Recovery
     * failed: &lt;reason&gt;" ({@link #failureReason}), and shows the Welcome
     * window again — nothing is left pending.
     */
    private void presentRecovery(Path projectDir, RecoverySummary summary) {
        try {
            if (summary == null) {
                // No recovery decision is owed — open the last clean state normally.
                afterTheRecoveryWorker(() -> whenNoAppWriteIsPending(RECOVER_SESSION_DOOR,
                        this::refuseWhileATakeIsRecordingOrBeingWritten, () -> loadProjectFromPath(projectDir)));
                return;
            }
            RecoveryChoice choice = recoveryDialogs.apply(summary).showAndWait().orElse(RecoveryChoice.CANCEL);
            switch (choice) {
                // The wait for the app's saves before the replay keeps the
                // recovery pending as the switch waiting; the replay's worker
                // takes over in the FX step the wait ends in.
                case REPLAY_ALL -> afterTheRecoveryWorker(() -> whenNoAppWriteIsPending(RECOVER_SESSION_DOOR,
                        () -> false, () -> replayThenOpen(projectDir)));
                // Per the dialog ("Restore checkpoint only opens the last clean save and
                // deletes the journal"), delete the journal before opening so the next
                // launch does not re-offer recovery.
                case CHECKPOINT_ONLY -> discardJournalThenLoad(projectDir);
                // Inspect / Cancel do not open a project. The Recover action already
                // dismissed the Welcome launch window, so re-present it rather than
                // strand the user on the empty main window (story 298 review).
                case INSPECT_EVENTS -> {
                    setRecovering(false);
                    showInspectEvents(summary);
                    reshowWelcomeAfterRecovery();
                }
                case CANCEL -> {
                    setRecovering(false);
                    reshowWelcomeAfterRecovery();
                }
            }
        } catch (RuntimeException | Error thrown) {
            // Anything thrown here ends the recovery: logged with its stack, said, and the Welcome window is back.
            setRecovering(false);
            LOG.log(Level.SEVERE, "Recovering " + projectDir + " failed unexpectedly", thrown);
            notificationBar.show(NotificationLevel.ERROR, "Recovery failed: " + failureReason(thrown));
            reshowWelcomeAfterRecovery();
        }
    }

    /**
     * Re-presents the Welcome launch screen after a recovery outcome that did
     * not open a project (Inspect / Cancel / Discard). The Recover and Discard
     * actions dismiss the launch window up-front; without re-presenting it the
     * user would be stranded on the empty main window with no way back to the
     * recover candidates (story 298 review).
     */
    private void reshowWelcomeAfterRecovery() {
        showWelcome();
    }

    /**
     * Discards the project's journal off the FX thread, then opens the last
     * clean save. Used by the {@code CHECKPOINT_ONLY} recovery choice so the
     * journal the user chose to discard is not re-offered on the next launch.
     * Recover Session stays the one switch pending until the delete's worker
     * hands over to the load's switch point (ruling L3); a delete that throws
     * — anything — takes the path of a journal that was not deleted.
     */
    private void discardJournalThenLoad(Path projectDir) {
        Path journalDir = ProjectContext.forProject(projectDir).journalDirectory();
        startRecoveryWorker(RECOVERY_DISCARD_WORKER, () -> {
            boolean discarded;
            try {
                beforeRecoveryWork.accept(RECOVERY_DISCARD_WORKER);
                discarded = deleteJournalDirectory(journalDir);
            } catch (Throwable e) {
                LOG.log(Level.SEVERE, "Discarding the journal " + journalDir + " failed unexpectedly", e);
                discarded = false;
            }
            if (discarded) {
                // Story 323 review, ruling L3 (b): a refusal says the journal is already gone.
                String alreadyDone = "The journal of " + projectDir + " was discarded";
                postFx(() -> afterTheRecoveryWorker(() -> whenNoAppWriteIsPending(RECOVER_SESSION_DOOR,
                        alreadyDone, () -> refuseLateLoadWhileATakeIsRecordingOrBeingWritten(alreadyDone),
                        () -> loadProjectFromPath(projectDir))));
            } else {
                postFx(() -> afterTheRecoveryWorker(() -> whenNoAppWriteIsPending(RECOVER_SESSION_DOOR,
                        this::refuseWhileATakeIsRecordingOrBeingWritten, () -> loadProjectFromPath(projectDir))));
            }
        });
    }

    /**
     * Replays the journal from its verified recovery point
     * (JournalReplayer.recover) on a virtual thread,
     * which then atomically rewrites {@code project.daw} with the recovered
     * state (backing up the prior file first, mirroring
     * {@code ProjectManager.writeSiblingBackup}'s {@code .bak} naming); then,
     * on the FX thread, makes it the live opened project through the normal
     * {@link #loadProjectFromPath(Path)} path. A
     * {@link JournalRecoveryException} surfaces a red notification and falls back
     * to opening the un-replayed project. Recover Session is the one switch
     * pending again from this FX step — the switch point before the replay
     * hands over to the replay's worker — until the worker hands over to the
     * load's switch point (ruling L3). FX thread.
     */
    private void replayThenOpen(Path projectDir) {
        startRecoveryWorker(RECOVERY_REPLAY_WORKER, () -> replayJournal(projectDir));
    }

    /**
     * The worker half of {@link #replayThenOpen}, off the FX thread: replays
     * the journal from its verified recovery point, rewrites {@code project.daw}
     * with the recovered project and deletes the journal; then, on the FX
     * thread, opens it ({@link #openRecoveredProject}) with the saved items
     * the replay's read of the checkpoint could not honour as written, which
     * the announcement counts instead of the load's own read's: the
     * rewritten {@code project.daw} no longer holds what that read dropped
     * or adjusted, but still holds the unrecognised-MIDI-effect placeholders
     * it wrote back, which the load reads and reports again — counting both
     * would count those twice. A failed replay or write opens the last clean
     * save instead; anything else thrown takes the failed replay's path, its
     * reason {@link #failureReason}. Each outcome hands Recover Session's
     * pending state over to its load's switch point in the FX step it
     * reaches (ruling L3). Package-private so a test can drive it without
     * the recovery dialog.
     *
     * @param projectDir the project whose journal is replayed
     */
    void replayJournal(Path projectDir) {
        ProjectContext ctx = ProjectContext.forProject(projectDir);
        try {
            beforeRecoveryWork.accept(RECOVERY_REPLAY_WORKER);
            RecoveryResult result = new JournalReplayer().recover(ctx);
            String xml = new ProjectSerializer().serialize(result.recoveredProject());
            // Ungated, so no lease check before the replace; story 384 retires this test-only path.
            writeRecoveredProjectFile(ctx.projectFile(), xml, () -> { });
            // The replayed records are now folded into project.daw, so the
            // journal that produced them is subsumed — clear it so the next
            // launch does not re-offer (and re-replay) the same changes. A
            // fresh journal opens when loadProjectFromPath reopens below.
            deleteJournalDirectory(ctx.journalDirectory());
            int applied = result.appliedRecordCount();
            List<UnreadProjectItem> replayUnread = result.unreadItems();
            postFx(() -> afterTheRecoveryWorker(
                    () -> openRecoveredProject(projectDir, ctx.projectFile(), applied, replayUnread)));
        } catch (JournalRecoveryException e) {
            LOG.log(Level.WARNING, "Journal recovery rolled back for " + projectDir, e);
            String failure = e.getMessage();
            postFx(() -> afterTheRecoveryWorker(() -> openLastCleanSaveAfterFailedReplay(projectDir, failure)));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to persist recovered project for " + projectDir, e);
            String failure = e.getMessage();
            postFx(() -> afterTheRecoveryWorker(
                    () -> openLastCleanSaveAfterRecoveredWriteFailed(projectDir, failure)));
        } catch (Throwable e) {
            LOG.log(Level.SEVERE, "Journal recovery of " + projectDir + " failed unexpectedly", e);
            String failure = failureReason(e);
            postFx(() -> afterTheRecoveryWorker(() -> openLastCleanSaveAfterFailedReplay(projectDir, failure)));
        }
    }

    /**
     * The FX half of a journal replay whose recovered project could not be
     * written: opens the last clean save through the
     * {@link OpenDoor.RecoveredWriteFailed} door, whose one ERROR says both
     * — the write failed, the last clean save was opened — qualified by the
     * missing assets (story 329: the old ERROR was replaced on the bar by
     * the load's own SUCCESS a moment later). When that load fails too, the
     * ERROR says so after the load's own. While a recording is in flight or
     * a take is being written the load is refused (story 323 review) and
     * the one WARNING carries the failure instead. While another switch is
     * pending it is refused with the WARNING that says so
     * ({@link #lateSwitchRefusedMessage}, ruling L3 (b)). Package-private so a test
     * can drive it without a journal. FX thread.
     *
     * @param projectDir the project
     * @param failure    why the recovered project could not be written
     */
    void openLastCleanSaveAfterRecoveredWriteFailed(Path projectDir, String failure) {
        String failed = "Could not write recovered project: " + failure;
        whenNoAppWriteIsPending(RECOVER_SESSION_DOOR, failed,
                () -> refuseLateLoadWhileATakeIsRecordingOrBeingWritten(failed),
                () -> {
                    if (!loadProjectFromPath(projectDir, new OpenDoor.RecoveredWriteFailed(failure))) {
                        notificationBar.show(NotificationLevel.ERROR, ProjectAudioText.text(
                                "projectOpen.recoveredWriteFailed.notOpened", String.valueOf(failure)));
                    }
                });
    }

    /**
     * The FX half of a journal replay whose recovered changes have been
     * written to {@code projectFile}: loads the project through the
     * {@link OpenDoor.JournalReplay} door, whose one announcement — made
     * only if the load succeeded — says how many changes were recovered,
     * qualified by the saved items the replay's read of its checkpoint could
     * not honour as written ({@code replayUnread}, story 330) instead of the
     * load's. While a recording is in flight or a take is being written the
     * load is refused (story 323 review) and the one WARNING says that the
     * changes are already in {@code projectFile}. While another switch is
     * pending it is refused with the WARNING that says so
     * ({@link #lateSwitchRefusedMessage}, ruling L3 (b)). Package-private so a test
     * can drive it without a journal. FX thread.
     */
    void openRecoveredProject(Path projectDir, Path projectFile, int applied,
                              List<UnreadProjectItem> replayUnread) {
        String written = applied + " recovered change" + (applied == 1 ? " was" : "s were") + " written to "
                + projectFile;
        whenNoAppWriteIsPending(RECOVER_SESSION_DOOR, written,
                () -> refuseLateLoadWhileATakeIsRecordingOrBeingWritten(written),
                // The ungated path is test-only and retired by a future story; its announcement is not corrected here.
                () -> loadProjectFromPath(projectDir, new OpenDoor.JournalReplay(applied, 0, replayUnread)));
    }

    /**
     * The FX half of a journal replay that failed and was rolled back: opens
     * the last clean save through the {@link OpenDoor.FailedReplayFallback}
     * door, whose one ERROR — made after the load, so it never claims an
     * open that did not happen — says the recovery failed and the last
     * clean save was opened instead. When that load fails too, the ERROR
     * says so after the load's own. While a recording is in flight or a
     * take is being written the load is refused (story 323 review) and the
     * one WARNING carries the failure instead. While another switch is
     * pending it is refused with the WARNING that says so
     * ({@link #lateSwitchRefusedMessage}, ruling L3 (b)). Package-private so a test can
     * drive it without a journal. FX thread.
     */
    void openLastCleanSaveAfterFailedReplay(Path projectDir, String failure) {
        String failed = "Recovery failed (" + failure + ")";
        whenNoAppWriteIsPending(RECOVER_SESSION_DOOR, failed,
                () -> refuseLateLoadWhileATakeIsRecordingOrBeingWritten(failed),
                () -> {
                    if (!loadProjectFromPath(projectDir, new OpenDoor.FailedReplayFallback(failure))) {
                        notificationBar.show(NotificationLevel.ERROR, ProjectAudioText.text(
                                "projectOpen.failedReplay.notOpened", String.valueOf(failure)));
                    }
                });
    }

    /**
     * Atomically replaces {@code projectFile} with {@code xml} through the
     * {@link AtomicFileWriter} (story 331), backing up the prior file first
     * to a sibling {@code project.daw.recovered.<stamp>.bak} (mirroring the
     * {@code ProjectManager.writeSiblingBackup} {@code .bak} naming) so the
     * pre-recovery file is never lost. A failed replace leaves
     * {@code projectFile} as it was and throws the writer's original
     * exception. A {@code projectFile} that is a symbolic link to a
     * {@code .daw} file stays a link, and that file is replaced; one that
     * leads anywhere else is refused (ruling M3) before anything is read
     * through it, so no backup is taken of the file it leads to. A read-only
     * {@code .daw} file — {@code project.daw} itself, or the one a link leads
     * to — is not refused until the replace, so it is backed up first and
     * the backup stays (story 383). {@code beforeReplace} runs last before
     * the replace, after the backup, whose copy can be long: the open
     * recovery gate's lease check, so a session whose lock was taken over
     * meanwhile replaces nothing (its failure propagates as itself, and the
     * backup stays). Runs off the FX thread, on the replay's worker.
     */
    private void writeRecoveredProjectFile(Path projectFile, String xml,
                                           OpenRecoveryGate.LeaseCheck beforeReplace) throws IOException {
        AtomicFileWriter writer = fileWriter;
        Path dawFile = AtomicFileWriter.followLinkToDawFile(projectFile);
        if (Files.exists(dawFile)) {
            backUpBeforeRecovery(writer, projectFile);
        }
        beforeReplace.verify();
        writer.writeString(projectFile, xml, AtomicFileWriter.ReplacePermission.TARGET,
                AtomicFileWriter.ReplaceOption.FOLLOW_LINK_TO_DAW_FILE);
    }

    /**
     * Copies {@code projectFile} to {@code project.daw.recovered.<stamp>.bak},
     * or {@code ….<stamp>-<n>.bak} when that name is taken, never over an
     * existing backup: the durable copy publishes only under a name nothing
     * holds ({@link AtomicFileWriter.Overwrite#NEVER}), so a name taken
     * between the choice and the publish moves it on to the next suffix.
     */
    private static Path backUpBeforeRecovery(AtomicFileWriter writer, Path projectFile) throws IOException {
        String stamp = LocalDateTime.now().format(BACKUP_STAMP);
        for (int n = 0; ; n++) {
            String suffix = n == 0 ? "" : "-" + n;
            Path backup = projectFile.resolveSibling(
                    projectFile.getFileName() + ".recovered." + stamp + suffix + ".bak");
            try {
                return writer.copy(projectFile, backup, AtomicFileWriter.Overwrite.NEVER);
            } catch (FileAlreadyExistsException taken) {
                // A refusal with the name still free is not a taken name.
                if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                    throw taken;
                }
            }
        }
    }

    /**
     * Minimal {@code INSPECT_EVENTS} surface (story 298): an informational dialog
     * worded from the summary only ({@link RecoveryDialog#inspectEventsText}) —
     * the changes journaled after the recovery point, the journal's segment-file
     * count and the change types, never a session record — leaving the project
     * unopened so the user can decide their next step.
     */
    private void showInspectEvents(RecoverySummary summary) {
        DawgDialog.info("Captured events", RecoveryDialog.inspectEventsText(summary)).showAndWait();
    }

    /**
     * The {@code [Discard recovery]} action: confirms, then deletes the
     * project's {@code journal/} directory off the FX thread so the next open is
     * a plain checkpoint open. Package-private so a test can drive it.
     *
     * @param projectDir the project whose journal should be discarded
     */
    void discardRecovery(Path projectDir) {
        if (projectDir == null) {
            return;
        }
        if (recovering) {
            refuseWhileASwitchIsPending();
            return;
        }
        DawgDialog<javafx.scene.control.ButtonType> confirm = DawgDialog.confirm(
                "Discard recovery",
                "Discard the unsaved changes captured since the last clean save? "
                        + "This deletes the journal and cannot be undone.",
                "Discard");
        Optional<javafx.scene.control.ButtonType> result = confirm.showAndWait();
        boolean discard = result.isPresent()
                && result.get().getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE;
        if (!discard) {
            // User backed out — the Welcome launch window was dismissed when the
            // Discard action fired, so bring it back rather than strand them.
            reshowWelcomeAfterRecovery();
            return;
        }
        discardConfirmedRecovery(projectDir);
    }

    /** Holds an independent writable target lease for the entire confirmed discard. */
    void discardConfirmedRecovery(Path projectDir) {
        if (recovering) { refuseWhileASwitchIsPending(); return; }
        long attempt = ++loadAttempts;
        raiseInstallationFence(RECOVER_SESSION_DOOR);
        try {
            startRecoveryWorker("daw-recovery-discard", () -> {
                try {
                    if (sameOpenDirectory(projectDir)) throw new IOException("The active project's recovery journal cannot be discarded");
                    try (ProjectManager.PreparedOpen target = projectManager.prepareLockedAccess(projectDir)) {
                        if (target.readOnly()) throw new IOException("Recovery journal was preserved because the project is read-only");
                        boolean deleted = target.lock().withHeldLease(target.lock().currentLock().orElseThrow(),
                                () -> deleteJournalDirectory(ProjectContext.forProject(projectDir).journalDirectory()));
                        postFx(() -> notificationBar.show(
                                deleted ? NotificationLevel.SUCCESS : NotificationLevel.WARNING,
                                deleted ? "Discarded recovery journal" : "No recovery journal to discard"));
                    }
                } catch (IOException | RuntimeException failure) {
                    LOG.log(Level.WARNING, "Recovery discard refused or failed for " + projectDir, failure);
                    postFx(() -> notificationBar.show(NotificationLevel.WARNING,
                            "Recovery journal was preserved: " + failureReason(failure)));
                } finally {
                    postFx(() -> {
                        finishOpenAttempt(attempt);
                        reshowWelcomeAfterRecovery();
                    });
                }
            });
        } catch (RuntimeException | Error failure) {
            finishOpenAttempt(attempt);
            throw failure;
        }
    }

    /** Recursively deletes {@code journalDir}; returns {@code true} if it existed and was removed. */
    private static boolean deleteJournalDirectory(Path journalDir) {
        if (journalDir == null || !Files.isDirectory(journalDir)) {
            return false;
        }
        try {
            // Reuse the shared recursive-delete helper rather than hand-rolling
            // a Files.walk traversal (story 298 review).
            ProjectArchiver.deleteRecursively(journalDir);
            return !Files.exists(journalDir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to delete journal directory " + journalDir, e);
            return false;
        }
    }

    // ── Seam defaults (overridable for tests / custom wiring) ─────────────────

    /**
     * Default {@link #newProjectLocationPrompt}: a {@link DirectoryChooser}
     * rooted at the §3.1 workspace ({@code ~/DAW Projects}) when it is a
     * directory — read once off the FX thread ({@link #workspaceRootIfDirectory},
     * story 333), so the FX thread asks the disk nothing here. Returns the
     * chosen parent directory, or {@code null} if the user cancelled.
     */
    private Path promptNewProjectLocation() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a location for the new project");
        java.io.File workspace = workspaceRootIfDirectory;
        if (workspace != null) {
            chooser.setInitialDirectory(workspace);
        }
        Window owner = rootPane.getScene() != null ? rootPane.getScene().getWindow() : null;
        java.io.File chosen = chooser.showDialog(owner);
        return chosen == null ? null : chosen.toPath();
    }

    /** The §3.1 default workspace root ({@code ~/DAW Projects}); {@code null} if no home. */
    static Path workspaceRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home, "DAW Projects");
    }

    /** Default {@link #projectHubPresenter}: open the Hub in its own themed Stage. */
    private void presentProjectHub(ProjectHubView hub) {
        showInOwnedStage("Project Hub", hub);
    }

    /** Default {@link #welcomePresenter}: open the Welcome screen in its own themed Stage. */
    private void presentWelcome(WelcomeView welcome) {
        showInOwnedStage("Welcome", welcome);
    }

    /**
     * Shows {@code content} as the root of a new {@link Stage} owned by the main
     * window, themed exactly like the primary scene (the ordered
     * {@link ThemeManager} stylesheets + the {@link DensityManager} density
     * class) so role tokens resolve and a theme/density switch reaches it.
     */
    private void showInOwnedStage(String title, Parent content) {
        if (!content.getStyleClass().contains("root-pane")) {
            content.getStyleClass().add("root-pane");
        }
        Scene scene = new Scene(content, 1100, 720);
        ThemeManager.getDefault().applyTo(scene);
        DensityManager.getDefault().applyTo(scene);
        Stage stage = new Stage();
        stage.setTitle(title);
        Window owner = rootPane.getScene() != null ? rootPane.getScene().getWindow() : null;
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setScene(scene);
        // Only ever one auxiliary Hub/Welcome window at a time: close any prior one
        // so a repeated "Project Hub…" replaces rather than stacks orphan windows
        // (each running its own background scans). The hidden-handler clears the
        // reference when the user closes the window themselves.
        if (auxiliaryStage != null && auxiliaryStage.isShowing()) {
            auxiliaryStage.close();
        }
        auxiliaryStage = stage;
        stage.setOnHidden(_ -> {
            if (auxiliaryStage == stage) {
                auxiliaryStage = null;
            }
        });
        stage.show();
    }

    /**
     * Reveals {@code projectDir} in the OS file browser (the Hub's "Reveal"
     * action). Runs the launch on a background virtual thread so a slow shell
     * never stalls the FX thread; errors are marshalled back to the
     * notification bar.
     */
    private void revealInFileBrowser(Path projectDir) {
        if (projectDir == null) {
            return;
        }
        Thread.ofVirtual().name("daw-reveal").start(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    Path toOpen = Files.isDirectory(projectDir) ? projectDir : projectDir.getParent();
                    if (toOpen == null) {
                        postFx(() -> notificationBar.show(NotificationLevel.ERROR,
                                "Could not reveal project: no parent directory"));
                        return;
                    }
                    java.awt.Desktop.getDesktop().open(toOpen.toFile());
                } else {
                    postFx(() -> notificationBar.show(NotificationLevel.ERROR,
                            "Reveal is not supported on this platform"));
                }
            } catch (IOException | RuntimeException e) {
                postFx(() -> notificationBar.show(NotificationLevel.ERROR,
                        "Could not reveal project: " + e.getMessage()));
                LOG.log(Level.WARNING, "Failed to reveal project directory", e);
            }
        });
    }

    /**
     * Resolves a non-{@code null} {@link FxDispatcher} for the scanner / views:
     * the injected one, else the app-scoped default, else a fresh instance (the
     * scanner only uses {@link FxDispatcher#onFx(Runnable)}, which works without
     * {@code start()}).
     */
    private FxDispatcher resolveDispatcher() {
        if (fxDispatcher != null) {
            return fxDispatcher;
        }
        FxDispatcher def = FxDispatcher.getDefault();
        return def != null ? def : new FxDispatcher();
    }

    // ── The open project's audio (story 329) ──────────────────────────────────

    /**
     * Returns the owner of the open project's audio: the arrangement asks it
     * what each clip's body shows, and the relink, export and menu wiring
     * ask it what is loading and what is unresolved.
     *
     * @return the open project's audio
     */
    OpenProjectAudio openProjectAudio() {
        return openProjectAudio;
    }

    /**
     * Retires the open project's audio for good — the window is closing
     * (decision D21, called from the primary stage's hidden handler): its
     * decodes are cancelled and nothing it still has in flight has an
     * effect. FX thread.
     */
    void dispose() {
        openProjectAudio.retire();
    }

    /**
     * Returns the relink flow of the open project's report (tests answer its
     * choosers and confirmation and hold its workers).
     *
     * @return the relink flow
     */
    AssetRelinkFlow assetRelink() {
        return assetRelink;
    }

    /**
     * Returns the check every export, bounce and freeze entry point runs
     * before it renders (decision D19).
     *
     * @return the render readiness guard
     */
    RenderReadinessGuard renderGuard() {
        return renderGuard;
    }

    /**
     * Sets what drops a relinked clip's cached sample-rate conversions — the
     * engine's {@code SampleRateConversionCache::invalidateForClip}, wired by
     * {@code MainController} (decision D17).
     *
     * @param invalidator called with the id of each re-pointed clip
     */
    void setClipConversionInvalidator(Consumer<String> invalidator) {
        assetRelink.setInvalidator(invalidator);
    }

    /**
     * "Relink Missing Assets…" (decision D15): presents the open's one
     * missing-assets report again — the same window — while it has rows;
     * otherwise says there is nothing to relink.
     */
    void onRelinkMissingAssets() {
        Optional<OpenProjectAudio.Generation> generation = openProjectAudio.currentGeneration();
        if (generation.isEmpty() || !hasUnresolvedAssets()) {
            notificationBar.show(NotificationLevel.INFO, ProjectAudioText.text("missingAssets.nothingToRelink"));
            return;
        }
        openProjectAudio.presentReport(generation.get());
    }

    /**
     * Whether the open's missing-assets report still has rows — what
     * enables "Relink Missing Assets…".
     *
     * @return {@code true} if a relink can still act on something
     */
    boolean hasUnresolvedAssets() {
        return !openProjectAudio.report().isEmpty();
    }

    /**
     * Default {@link OpenProjectAudio.ReportPresenter}: the non-modal
     * {@link MissingAssetsDialog}, owned by the main window, shown now.
     */
    private OpenProjectAudio.ReportView presentMissingAssetsDialog(OpenProjectAudio.Generation generation,
                                                                    MissingAssetsReport report) {
        MissingAssetsDialog dialog = missingAssetsDialog(generation, report);
        dialog.present();
        return dialog;
    }

    /**
     * Builds the missing-assets report window of {@code generation}, not yet
     * shown: its rows' actions are the relink flow's, its archive link the
     * archive flow.
     *
     * @param generation the open the report belongs to
     * @param report     the report as it is now
     * @return the report window
     */
    MissingAssetsDialog missingAssetsDialog(OpenProjectAudio.Generation generation, MissingAssetsReport report) {
        return new MissingAssetsDialog(mainWindow(), report,
                assetRelink.actionsFor(generation, this::onArchiveProject));
    }

    /** Locate file's default chooser: opens in the project folder. */
    private Optional<Path> chooseRelinkFile(MissingAsset row, Optional<Path> initialDirectory) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(switch (row.reference()) {
            case AssetReference.SingleFile file -> ProjectAudioText.text("missingAssets.chooser.file",
                    fileNameOf(file.reference()));
            case AssetReference.Take _ -> ProjectAudioText.text("missingAssets.chooser.take");
        });
        initialDirectory.filter(Files::isDirectory).ifPresent(folder -> chooser.setInitialDirectory(folder.toFile()));
        java.io.File picked = chooser.showOpenDialog(mainWindow());
        return picked == null ? Optional.empty() : Optional.of(picked.toPath());
    }

    /** Locate folder's default chooser: opens in the project folder. */
    private Optional<Path> chooseRelinkFolder(Optional<Path> initialDirectory) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(ProjectAudioText.text("missingAssets.chooser.folder"));
        initialDirectory.filter(Files::isDirectory).ifPresent(folder -> chooser.setInitialDirectory(folder.toFile()));
        java.io.File picked = chooser.showDialog(mainWindow());
        return picked == null ? Optional.empty() : Optional.of(picked.toPath());
    }

    /** The default confirmation of the relink flow and the render guard: a §5.9 confirm dialog. */
    private boolean confirm(String title, String message, String confirmLabel) {
        DawgDialog<ButtonType> dialog = DawgDialog.confirm(title, message, confirmLabel);
        Window owner = mainWindow();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog.showAndWait()
                .filter(pressed -> pressed.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                .isPresent();
    }

    private Window mainWindow() {
        return rootPane.getScene() == null ? null : rootPane.getScene().getWindow();
    }

    /** The last element of a clip reference, whichever separator it was written with. */
    private static String fileNameOf(String reference) {
        int slash = Math.max(reference.lastIndexOf('/'), reference.lastIndexOf('\\'));
        return slash < 0 ? reference : reference.substring(slash + 1);
    }

    /**
     * Overrides the rehydrator later opens start (story 329). Tests inject one
     * whose decode threads they hold, and record where decodes run.
     *
     * @param rehydrator the rehydrator; must not be {@code null}
     */
    void setClipRehydrator(ClipRehydrator rehydrator) {
        openProjectAudio.setRehydrator(rehydrator);
    }

    /**
     * Story 331 — replaces the atomic-replace writer of this controller's own
     * project writes (the recovered project file and its backup, the restored
     * archive's {@code project.daw}, the migration roll-back). Tests inject
     * one whose mover fails or records a publish.
     *
     * @param writer the writer; must not be {@code null}
     */
    void setFileWriter(AtomicFileWriter writer) {
        this.fileWriter = Objects.requireNonNull(writer, "writer must not be null");
    }

    /**
     * Overrides the missing-assets report presenter (story 329). Tests inject
     * a capturing presenter that shows nothing and counts presentations.
     *
     * @param presenter the presenter; must not be {@code null}
     */
    void setMissingAssetsPresenter(OpenProjectAudio.ReportPresenter presenter) {
        openProjectAudio.setPresenter(presenter);
    }

    // ── Seam overrides (story 296) ────────────────────────────────────────────

    /**
     * Overrides the new-project location prompt (the §8 "kill the /tmp
     * first-save" seam). Tests inject a stub returning a fixed directory.
     *
     * @param prompt supplies the destination parent directory, or {@code null}
     *               to cancel; must not be {@code null}
     */
    void setNewProjectLocationPrompt(Supplier<Path> prompt) {
        this.newProjectLocationPrompt = Objects.requireNonNull(prompt, "prompt must not be null");
    }

    /**
     * Overrides the Open Project door's folder chooser (story 331, ruling
     * L4): tests return a folder, or {@code null} for a cancel, and may act
     * while it is "open". FX thread.
     */
    void setOpenProjectPrompt(Supplier<Path> prompt) {
        this.openProjectPrompt = Objects.requireNonNull(prompt, "prompt must not be null");
    }

    /**
     * Overrides the Import DAWproject Exchange door's file chooser (story
     * 331, ruling L4): tests return a file, or {@code null} for a cancel. FX
     * thread.
     */
    void setImportSessionPrompt(Supplier<Path> prompt) {
        this.importSessionPrompt = Objects.requireNonNull(prompt, "prompt must not be null");
    }

    /**
     * Overrides the Restore from Archive door's archive chooser (story 331,
     * ruling L4): tests return a {@code .dawz}, or {@code null} for a cancel.
     * FX thread.
     */
    void setArchiveToRestorePrompt(Supplier<Path> prompt) {
        this.archiveToRestorePrompt = Objects.requireNonNull(prompt, "prompt must not be null");
    }

    /**
     * Overrides the Restore from Archive door's destination chooser (story
     * 331, ruling L4): tests return the folder the restored project's own
     * folder is created in, or {@code null} for a cancel. FX thread.
     */
    void setRestoreParentPrompt(Supplier<Path> prompt) {
        this.restoreParentPrompt = Objects.requireNonNull(prompt, "prompt must not be null");
    }

    /**
     * Overrides the Project Hub presenter. Tests inject a capturing consumer to
     * assert the Hub was opened without showing a window.
     *
     * @param presenter the hub presenter; must not be {@code null}
     */
    void setProjectHubPresenter(Consumer<ProjectHubView> presenter) {
        this.projectHubPresenter = Objects.requireNonNull(presenter, "presenter must not be null");
    }

    /**
     * Overrides the Welcome-screen presenter. Tests inject a capturing consumer.
     *
     * @param presenter the welcome presenter; must not be {@code null}
     */
    void setWelcomePresenter(Consumer<WelcomeView> presenter) {
        this.welcomePresenter = Objects.requireNonNull(presenter, "presenter must not be null");
    }

    /**
     * Overrides the story-298 crash-recovery action (the Welcome screen's
     * {@code [Recover session ▸]} target), given the project and what dismisses
     * the Welcome window once the recovery may begin.
     *
     * @param action the recovery action; must not be {@code null}
     */
    void setRecoverAction(BiConsumer<Path, Runnable> action) {
        this.recoverAction = Objects.requireNonNull(action, "action must not be null");
    }

    /**
     * Overrides the story-298 discard-recovery action (the Welcome screen's
     * {@code [Discard recovery]} target).
     *
     * @param action the discard action; must not be {@code null}
     */
    void setDiscardAction(Consumer<Path> action) {
        this.discardAction = Objects.requireNonNull(action, "action must not be null");
    }

    /**
     * Sets the post-write hook used by the snapshot browser to refresh after a
     * named snapshot is created. Tests may inject a latch/spy here.
     *
     * @param hook hook to run on the FX thread after a successful snapshot
     */
    void setNamedSnapshotCreatedHook(Runnable hook) {
        this.namedSnapshotCreatedHook = Objects.requireNonNull(hook, "hook must not be null");
    }

    /**
     * Story 331 — overrides what runs a task on the FX thread once
     * {@link #SLOW_SAVE_THRESHOLD} has passed after a save was requested.
     * Tests capture the task and run it on the FX thread when they choose.
     *
     * @param scheduler receives each slow-save timer task; must not be {@code null}
     */
    void setSlowSaveTimerScheduler(Consumer<Runnable> scheduler) {
        this.slowSaveTimerScheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    /**
     * Story 331 — sets what runs on the FX thread as a door that waited for
     * the save writer resumes, before it judges the project again: a test
     * pins what happens between the idle wait and the door's own step on the
     * thread that takes it. The default does nothing.
     *
     * @param hook run as a waiting door resumes; must not be {@code null}
     */
    void setBeforeWaitingDoorResumes(Runnable hook) {
        this.beforeWaitingDoorResumes = Objects.requireNonNull(hook, "hook must not be null");
    }

    /**
     * Story 333 — sets what runs on the FX thread once the open project's
     * folder changed without a rebuild after it ({@link #projectFolderChangedHook}):
     * {@code MainController} registers the folder's checkpoints, opens its
     * session manifest and repaints the lock indicator and labels.
     *
     * @param hook run when the open project's folder changed; must not be {@code null}
     */
    void setProjectFolderChangedHook(Runnable hook) {
        this.projectFolderChangedHook = Objects.requireNonNull(hook, "hook must not be null");
    }

    /**
     * Story 333 — installs File ▸ Save As… ({@link #onSaveProjectAs}), which
     * the takeover dialog's and a save failure's {@code Save As…} run too.
     * {@code MainController} builds it over {@link #saveAsLifecycle()}.
     *
     * @param flow the Save As flow; must not be {@code null}
     */
    void setSaveAsFlow(SaveAsFlow flow) {
        this.saveAsFlow = Objects.requireNonNull(flow, "flow must not be null");
    }

    /**
     * Story 333 (ruling R10) — sets what runs after Close Project installed
     * the Untitled project, when the Welcome window is to show
     * ({@link #showWelcomeAfterClose}); {@code MainController} installs
     * its own. The default shows the Welcome window ({@link #showWelcome()}).
     *
     * @param hook run after Close Project; must not be {@code null}
     */
    void setWelcomeAfterClose(Runnable hook) {
        this.welcomeAfterClose = Objects.requireNonNull(hook, "hook must not be null");
    }

    /**
     * Story 333 (ruling R8) — replaces the clock of the doors' "Stop waiting"
     * offer. Tests install manual timers and fire the 10 s threshold when
     * they choose. FX thread.
     *
     * @param timers the timers; must not be {@code null}
     */
    void setDoorWaitTimers(CloseTimers timers) {
        this.doorWaitTimers = Objects.requireNonNull(timers, "timers must not be null");
    }

    /**
     * Story 331 (ruling L3) — sets what runs on a recovery worker, with its
     * name ({@link #RECOVERY_SCAN_WORKER}, {@link #RECOVERY_DISCARD_WORKER},
     * {@link #RECOVERY_REPLAY_WORKER}), before its work and inside its
     * failure handling: a test holds the worker to act while the recovery is
     * the switch pending, or throws to take the worker's failure path. The
     * default does nothing.
     *
     * @param hook run on each recovery worker before its work; must not be {@code null}
     */
    void setBeforeRecoveryWork(Consumer<String> hook) {
        this.beforeRecoveryWork = Objects.requireNonNull(hook, "hook must not be null");
    }

    /**
     * Story 331 — sets what builds Recover Session's recovery dialog for the
     * scan's summary, on the FX thread: a test makes its construction throw.
     * The default is the {@link RecoveryDialog} itself.
     *
     * @param dialogs builds the dialog for a summary; must not be {@code null}
     */
    void setRecoveryDialogs(Function<RecoverySummary, RecoveryDialog> dialogs) {
        this.recoveryDialogs = Objects.requireNonNull(dialogs, "dialogs must not be null");
    }

    /**
     * Sets the story-298 journal-open hook, invoked with a project directory
     * after a successful open / new so {@code MainController}'s
     * {@code ProjectJournalCoordinator} can start the write-ahead journal. A
     * {@code null} hook (the default) skips journal wiring.
     *
     * @param hook the journal-open hook, or {@code null} to disable
     */
    void setJournalOpenHook(Consumer<Path> hook) {
        this.journalOpenHook = hook;
    }

    /**
     * Story 323 review — sets the check that tells this controller whether a
     * take is still being written to disk; while it answers {@code true},
     * every in-app door that replaces the open project refuses — New, Open,
     * Import, Restore from Archive, a Project Hub or Welcome open and a
     * snapshot restore through {@link #whenProjectMayClose}, Recover and
     * a migration roll-back at their start, and the loads the recovery and
     * archive-restore flows make later (see
     * {@link #refuseWhileATakeIsRecordingOrBeingWritten()}). Quitting the
     * application and Close Project are not refused: the exit protocol waits
     * for the take instead (story 333, ruling R1).
     * {@code MainController} supplies the current transport controller's
     * answer; the default never refuses.
     *
     * @param check whether a take is still being written; must not be {@code null}
     */
    void setTakeBeingWrittenCheck(BooleanSupplier check) {
        this.takeBeingWritten = Objects.requireNonNull(check, "check must not be null");
    }

    /**
     * Story 323 review — sets the check that tells this controller whether a
     * recording is in flight; while it answers {@code true}, the doors
     * {@link #setTakeBeingWrittenCheck} lists refuse at the same points, with
     * {@link #PROJECT_CHANGE_WHILE_RECORDING_MESSAGE} — or, for the load that
     * follows an archive extraction, a journal replay (successful or not) or
     * a journal discard that deleted the journal, with
     * {@link #lateLoadRefusalWhileRecordingMessage} — unless the
     * take-being-written check answers {@code true} as well, whose refusal is
     * shown instead. Quitting the application and Close Project are not
     * refused: the exit protocol asks first whether to stop the take (story
     * 333, ruling R1). {@code MainController} supplies the
     * current transport controller's answer; the default never refuses.
     *
     * @param check whether a recording is in flight; must not be {@code null}
     */
    void setRecordingInFlightCheck(BooleanSupplier check) {
        this.recordingInFlight = Objects.requireNonNull(check, "check must not be null");
    }

    /** Invokes the journal-open hook when one is installed (story 298). */
    private void notifyJournalOpen(Path projectDir) {
        Consumer<Path> hook = this.journalOpenHook;
        if (hook != null && projectDir != null) {
            hook.accept(projectDir);
        }
    }
}
