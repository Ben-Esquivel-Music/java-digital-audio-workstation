package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.RecoveryDialog;
import com.benesquivelmusic.daw.app.ui.hub.WelcomeView;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.SaveAsPlan;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression;
import com.benesquivelmusic.daw.core.persistence.migration.ProjectMigration;
import com.benesquivelmusic.daw.core.persistence.snapshot.SnapshotStore;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.session.SessionImportResult;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's ruling R12 #8 (and S1, Q3; design §1.4):
 * an exit while another switch is pending. A door waiting for a save is
 * replaced silently — a FINE log — and the exit waits for the same save; the
 * door never opens its project. Another switch running is waited for under
 * the closing surface, "Finishing Save As…", with the Q3 note and
 * Quit anyway at ten seconds. A door's open file dialog refuses the exit
 * with the L4 text. Close Project refuses while a switch is pending (the G2
 * shape). A recording in flight is asked about before any of those waits.
 * And while the close runs — its own run the switch pending — a Welcome or
 * Hub open a stale continuation fires is refused at FINE: one closer runs.
 *
 * <p>While the close runs, the work this class lands while the exit waits
 * for it shows no window of its own (the studio engineer's ruling R-1.6;
 * PR #989 review 5711460812): the recovery dialog Recover Session lands is
 * answered with Cancel, unshown, on its ungated path and through the open
 * recovery gate, and the Project Locked dialog a gated open's worker asks
 * for is answered with Cancel, unshown; the migration report a gated open's
 * install lands is logged at INFO, unshown, and a suppressed one is not
 * logged either; the Import Summary that an Import DAWproject Exchange's
 * replacement lands is logged at INFO with its text, unshown; and a user's
 * Save As whose target the plan refuses shows neither its refusal nor its
 * chooser again, its refusal logged at INFO. A dialog of that work already
 * up when the close began still answers an assistive technology's action,
 * but the window its answer would open is not shown, logged at INFO: the
 * recovery dialog's Inspect events… shows no Captured events dialog, the
 * Project Locked dialog's Take Over asks no take-over confirmation and
 * takes its Cancel, and the migration report's Roll back… asks no
 * confirmation and rolls nothing back. With no close, each but the
 * suppressed report shows its dialog. Other work that lands while a close
 * runs may still show its dialog, a known limit (future story 417). The
 * Welcome window's Discard, a confirmed discard, the OK of a named
 * snapshot's name dialog that was open when the close began, and the Save
 * or Discard of a door's unsaved-changes prompt that was up when the close
 * began, with no switch pending, are refused at FINE; with no close, or
 * after its Cancel, each does what it did. Export DAWproject Exchange's
 * folder, returned by its chooser once the close began, is not written,
 * logged at INFO; with no close, it is written.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ExitDuringAPendingSwitchTest {

    /** The title of the named snapshot's name dialog. */
    private static final String SNAPSHOT_DIALOG_TITLE = "Snapshot";
    /** The title of the migration report dialog. */
    private static final String MIGRATION_REPORT_TITLE = "Project Migrated";
    /** The title of Import DAWproject Exchange's summary dialog. */
    private static final String IMPORT_SUMMARY_TITLE = "Import Summary";
    /** The start of the project-lock conflict dialog's title, which names the lock's holder after it. */
    private static final String LOCKED_TITLE_PREFIX = "Project Locked by ";
    /** The label of the project-lock conflict dialog's Take Over button. */
    private static final String TAKE_OVER_BUTTON_TEXT = "Take Over (Steal Lock)";
    /** The title of the confirmation the project-lock conflict dialog's Take Over asks. */
    private static final String TAKE_OVER_CONFIRMATION_TITLE = "Confirm Lock Take-Over";
    /** The title of the confirmation the migration report's Roll back… asks. */
    private static final String ROLL_BACK_CONFIRMATION_TITLE = "Roll back migration?";
    /** The title of the dialog the recovery dialog's Inspect events… shows. */
    private static final String CAPTURED_EVENTS_TITLE = "Captured events";
    /**
     * The header of a door's unsaved-changes prompt
     * ({@code ProjectLifecycleController.UnsavedChangesPrompt.door}); the
     * close's own prompt, under the same title, has a header that names the
     * project.
     */
    private static final String DOOR_PROMPT_HEADER = "You have unsaved changes.";
    /** The label of a door's unsaved-changes prompt's Save button. */
    private static final String DOOR_PROMPT_SAVE = "Save";
    /** The label of a door's unsaved-changes prompt's Discard button. */
    private static final String DOOR_PROMPT_DISCARD = "Discard";
    /** The start of the notification a DAWproject Exchange export shows once it wrote its file. */
    private static final String EXPORTED_PREFIX = "DAWproject Exchange exported to ";

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final Handler recording = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            logged.add(logRecord);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Logger lifecycleLog;
    private Level lifecycleLevel;
    /** The recent projects of a test's own fixture, removed after the fixture is closed; or {@code null}. */
    private Preferences recentProjectsNode;

    @BeforeEach
    void songAEdited() throws Exception {
        lifecycleLog = Logger.getLogger(ProjectLifecycleController.class.getName());
        lifecycleLevel = lifecycleLog.getLevel();
        lifecycleLog.setLevel(Level.FINE);
        lifecycleLog.addHandler(recording);
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
    }

    @AfterEach
    void close() throws Exception {
        try {
            fixture.close();
        } finally {
            lifecycleLog.removeHandler(recording);
            lifecycleLog.setLevel(lifecycleLevel);
            if (recentProjectsNode != null) {
                recentProjectsNode.removeNode();
            }
        }
    }

    @Test
    void anExitReplacesADoorWaitingForASaveSilentlyAndWaitsForThatSave() throws Exception {
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.promptAnswer = "Save";
        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> songB);
            fixture.lifecycle().onOpenProject();
        });
        head.awaitHeld();
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isEqualTo(new ProjectLifecycleController.PendingSwitch.WaitingDoor("Open Project"));

        fixture.closeBox();
        fixture.await(() -> fixture.lifecycle().closeReadiness().pending()
                instanceof ProjectLifecycleController.PendingSwitch.None, "the door was replaced");
        assertThat(messages(Level.FINE)).contains("Open Project no longer waits for the save: the application is"
                + " closing");
        assertThat(fixture.save.notifications()).as("silently: no toast").isEmpty();
        head.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.save.currentProjectIs(songB)).as("the door never opened its project").isFalse();
        assertThat(fixture.unsavedPrompts).as("the door's own prompt only: the save left nothing unsaved")
                .hasSize(1);
    }

    @Test
    void anExitWaitsForAPendingSaveAsUnderTheSurfaceAndOffersQuitAnywayAtTenSeconds() throws Exception {
        SaveFlowFixture.Publishes.Hold copying = holdASaveAsCopy();

        fixture.closeBox();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        assertThat(onFx(() -> fixture.surface().stepText())).isEqualTo("Finishing Save As…");
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(() -> fixture.surface().noteText())).isEqualTo("Save As hasn't finished after 10 seconds."
                + " The disk may not be responding. If you quit anyway, Save As is left unfinished, as after a"
                + " crash.");
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE");

        runOnFx(() -> fixture.surface().offerButton().fire());
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        assertThat(fixture.stepNames()).contains("SEAL SKIPPED", "RELEASE SKIPPED");
        finish(copying);
    }

    /**
     * The stage-6 review B1: Quit anyway in the wait for a Save As copy ends
     * GUARD's waits — the copy that finishes while DISPOSE still waits for the
     * engine's hand-off decides nothing: no prompt, no second GUARD, the
     * surface still "Stopping audio…"; then exactly one HIDE.
     */
    @Test
    void aSaveAsThatFinishesAfterQuitAnywayDecidesNothingDuringDispose() throws Exception {
        java.util.concurrent.CompletableFuture<Void> engineStopping = new java.util.concurrent.CompletableFuture<>();
        fixture.handOff = engineStopping;
        SaveFlowFixture.Publishes.Hold copying = holdASaveAsCopy();
        fixture.closeBox();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        runOnFx(() -> fixture.surface().offerButton().fire());
        fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
        List<String> atDispose = fixture.stepNames();

        finish(copying);
        fixture.save.settle();

        assertThat(fixture.stepNames()).as("no second GUARD, nothing more").isEqualTo(atDispose);
        assertThat(fixture.unsavedPrompts).as("no prompt during DISPOSE").isEmpty();
        assertThat(onFx(() -> fixture.surface().stepText())).isEqualTo("Stopping audio…");

        engineStopping.complete(null);
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        assertThat(fixture.stepNames()).filteredOn(name -> name.startsWith("HIDE")).hasSize(1);
        assertThat(fixture.terminalExits).hasValue(1);
    }

    /**
     * The stage-6 review S3, ruling R12 #8 and ruling 4 Q5 (i): an exit waits
     * for an archive being extracted under the surface — "Finishing Restore
     * from Archive…", the Q3 note with Quit anyway at ten seconds — and when
     * the extraction lands, its load is refused with the ruled WARNING: the
     * archive stays restored, it is not opened, and the exit goes on to HIDE.
     */
    @Test
    void anExitWaitsForAnArchiveExtractionAndItsLateLoadIsRefusedWithTheRuledWarning() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        Path archive = fixture.save.archiveOnDisk("Song R");
        Path restored = java.nio.file.Files.createDirectories(workspace.resolve("restored"));
        SaveFlowFixture.Publishes.Hold extraction = holdAnArchiveExtraction(archive, restored);

        fixture.closeBox();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        assertThat(onFx(() -> fixture.surface().stepText())).isEqualTo("Finishing Restore from Archive…");
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(() -> fixture.surface().noteText())).isEqualTo("Restore from Archive hasn't finished after"
                + " 10 seconds. The disk may not be responding. If you quit anyway, Restore from Archive is left"
                + " unfinished, as after a crash.");
        assertThat(onFx(() -> fixture.surface().offerButton().getText())).isEqualTo("Quit anyway");

        extraction.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        Path destination;
        try (java.util.stream.Stream<Path> folders = java.nio.file.Files.list(restored)) {
            destination = folders.filter(java.nio.file.Files::isDirectory).findFirst().orElseThrow();
        }
        assertThat(fixture.save.notifications()).filteredOn(entry -> entry.level() == NotificationLevel.WARNING)
                .extracting(NotificationEntry::message).containsExactly("The archive was restored to " + destination
                        + "; the project was not opened, because the open project is being closed — open it later"
                        + " with File ▸ Open Project…");
        assertThat(fixture.save.currentProjectIs(destination)).as("not opened").isFalse();
        assertThat(destination.resolve("project.daw")).as("its work stays done").exists();
    }

    /**
     * The stage-6 review S3: a recording in flight while an archive is being
     * extracted is asked about before the extraction ends.
     */
    @Test
    void aRecordingInFlightDuringAnExtractionIsAskedAboutBeforeTheExtractionEnds() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        Path archive = fixture.save.archiveOnDisk("Song R");
        Path restored = java.nio.file.Files.createDirectories(workspace.resolve("restored"));
        SaveFlowFixture.Publishes.Hold extraction = holdAnArchiveExtraction(archive, restored);
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        runOnFx(takes::record);

        fixture.closeBox();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(fixture.stopTakePrompts).as("the confirmation, with the extraction still running").hasSize(1);
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isEqualTo(new ProjectLifecycleController.PendingSwitch.Extracting());
        extraction.release();
        fixture.await(() -> !(fixture.lifecycle().closeReadiness().pending()
                instanceof ProjectLifecycleController.PendingSwitch.Extracting), "the extraction ended");
    }

    /**
     * The stage-6 review S3, ruling R12 #8: an exit waits for Recover Session
     * — its journal's delete held on the worker (ungated, as story 331's
     * recovery tests drive it) — under the surface, "Finishing Recover
     * Session…" and the Q3 note at ten seconds; once the work lands, its
     * load is refused with the ruled WARNING, and the exit goes on to HIDE.
     */
    @Test
    void anExitWaitsForRecoverSessionAndItsLateLoadIsRefusedWithTheRuledWarning() throws Exception {
        fixture.close();
        Path ungated = java.nio.file.Files.createDirectories(workspace.resolve("ungated"));
        fixture = new ExitFixture(ungated, AutoSaveConfig.DEFAULT, true, java.util.function.UnaryOperator.identity(),
                false);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        Path songB = RecoverableProjects.withACheckpointAndAJournal(fixture.save, "Song B", 3);
        try (AutoCloseable answering = RecoverableProjects.answeringTheRecoveryDialogWith("Restore checkpoint only")) {
            runOnFx(() -> fixture.lifecycle().setBeforeRecoveryWork(worker -> {
                if (worker.equals(ProjectLifecycleController.RECOVERY_DISCARD_WORKER)) {
                    held.countDown();
                    try {
                        released.await(ExitFixture.GUARD.toMillis() * 3, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }));
            runOnFx(() -> fixture.lifecycle().beginRecovery(songB));
            assertThat(held.await(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the journal's delete reached its worker").isTrue();
            assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                    .isEqualTo(new ProjectLifecycleController.PendingSwitch.Recovering());

            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface().stepText())).isEqualTo("Finishing Recover Session…");
            fixture.fire(ExitCoordinator.NOTE_DELAY);
            assertThat(onFx(() -> fixture.surface().noteText())).isEqualTo("Recover Session hasn't finished after"
                    + " 10 seconds. The disk may not be responding. If you quit anyway, Recover Session is left"
                    + " unfinished, as after a crash.");
        } finally {
            released.countDown();
        }
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.save.notifications()).filteredOn(entry -> entry.level() == NotificationLevel.WARNING)
                .extracting(NotificationEntry::message).containsExactly("The journal of " + songB + " was discarded;"
                        + " the project was not opened, because the open project is being closed — open it later"
                        + " with File ▸ Open Project…");
        assertThat(fixture.save.currentProjectIs(songB)).as("not opened").isFalse();
    }

    /**
     * Ruling R-1.6 (PR #989 review 5711460812), the ungated path: the
     * recovery dialog of a Recover Session scan that lands after the closing
     * surface shows — a dialog shown then would be fenced and, being
     * application-modal, would disable the Closing dialog — is answered with
     * Cancel without being shown, logged at INFO; the project is not opened,
     * its journal is kept as after a user's Cancel, the Welcome window is not
     * shown again, and the exit goes on to HIDE. With no close, the same
     * Recover Session shows the dialog, and its Cancel the Welcome window.
     */
    @Test
    void anExitWaitingForRecoverSessionAnswersItsRecoveryDialogWithCancelWithoutShowingIt() throws Exception {
        fixture.close();
        Path ungated = java.nio.file.Files.createDirectories(workspace.resolve("ungated"));
        fixture = new ExitFixture(ungated, AutoSaveConfig.DEFAULT, true, java.util.function.UnaryOperator.identity(),
                false);
        aRecoveryDialogThatLandsWhileTheExitWaitsIsAnsweredWithCancelUnshown("Finishing Recover Session…");
    }

    /**
     * Ruling R-1.6 (PR #989 review 5711460812), through the open recovery
     * gate the application installs: as
     * {@link #anExitWaitingForRecoverSessionAnswersItsRecoveryDialogWithCancelWithoutShowingIt},
     * its work held in the gate's recovery work, and no project is left
     * being installed once it ends.
     */
    @Test
    void anExitWaitingForAGatedRecoveryAnswersItsRecoveryDialogWithCancelWithoutShowingIt() throws Exception {
        fixture.close();
        Path gated = java.nio.file.Files.createDirectories(workspace.resolve("gated"));
        fixture = new ExitFixture(gated, AutoSaveConfig.DEFAULT);
        aRecoveryDialogThatLandsWhileTheExitWaitsIsAnsweredWithCancelUnshown("Finishing Open Project…");
        assertThat(onFx(() -> fixture.lifecycle().isProjectInstallationPending()))
                .as("the gate's Cancel left no project being installed").isFalse();
    }

    /**
     * Ruling R-1.6 as ruling 7 amends it (PR #989 review 5711460812), through
     * the open recovery gate: a recovery dialog shown before the exit began
     * still answers an assistive technology's action, but its Inspect
     * events…, fired after the closing surface shows, opens no Captured
     * events dialog — shown then, it would be fenced and, being
     * application-modal, would disable the Closing dialog while the exit
     * waits for its answer — and its text goes to the log at INFO; the
     * project is not opened, its journal is kept, the Welcome window is not
     * shown again, and the exit goes on to HIDE. With no close, the same
     * answer shows the Captured events dialog.
     */
    @Test
    void anInspectAnswerOnARecoveryDialogUpWhenTheExitBeganOpensNoCapturedEventsDialog() throws Exception {
        fixture.close();
        Path gated = java.nio.file.Files.createDirectories(workspace.resolve("gated"));
        fixture = new ExitFixture(gated, AutoSaveConfig.DEFAULT);
        Path songB = RecoverableProjects.withACheckpointAndAJournal(fixture.save, "Song B", 3);
        java.util.concurrent.atomic.AtomicInteger welcomes = new java.util.concurrent.atomic.AtomicInteger();
        List<RecoveryDialog> recoveryDialogs = new CopyOnWriteArrayList<>();
        List<Window> capturedEvents = new CopyOnWriteArrayList<>();
        List<String> capturedEventsTexts = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> counting = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && CAPTURED_EVENTS_TITLE.equals(stage.getTitle())) {
                        capturedEvents.add(window);
                        Platform.runLater(() -> {
                            if (stage.getScene().getRoot().lookup(".dawg-dialog-message") instanceof Label message) {
                                capturedEventsTexts.add(message.getText());
                            }
                            window.hide();
                        });
                    }
                }
            }
        };
        runOnFx(() -> {
            fixture.lifecycle().setWelcomePresenter(_ -> welcomes.incrementAndGet());
            fixture.lifecycle().setRecoveryDialogs(summary -> {
                RecoveryDialog dialog = new RecoveryDialog(summary);
                recoveryDialogs.add(dialog);
                return dialog;
            });
            Window.getWindows().addListener(counting);
        });
        try {
            runOnFx(() -> fixture.lifecycle().beginRecovery(songB));
            RecoveryDialog withNoClose = theRecoveryDialogShowing(recoveryDialogs, 1);
            runOnFx(() -> inspectEventsButtonOf(withNoClose).executeAccessibleAction(AccessibleAction.FIRE));
            fixture.await(() -> welcomes.get() == 1 && !fixture.lifecycle().isProjectInstallationPending()
                    && fixture.lifecycle().closeReadiness().pending()
                    instanceof ProjectLifecycleController.PendingSwitch.None, "the Inspect answer ended the recovery");
            assertThat(capturedEvents).as("positive control: with no close the Captured events dialog shows")
                    .hasSize(1);
            assertThat(capturedEventsTexts).as("fixture: its text was read before it was hidden").hasSize(1);
            String events = capturedEventsTexts.getFirst();
            assertThat(messages(Level.INFO)).as("and nothing is logged")
                    .noneMatch(message -> message.startsWith("The Captured events dialog"));
            List<String> journalBefore = listing(songB.resolve("journal"));

            runOnFx(() -> fixture.lifecycle().beginRecovery(songB));
            RecoveryDialog upWhenTheExitBegan = theRecoveryDialogShowing(recoveryDialogs, 2);
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface().stepText())).as("the exit waits for the gated open")
                    .isEqualTo("Finishing Open Project…");
            runOnFx(() -> inspectEventsButtonOf(upWhenTheExitBegan).executeAccessibleAction(AccessibleAction.FIRE));
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(capturedEvents).as("no Captured events dialog was shown while the exit waited").hasSize(1);
            assertThat(messages(Level.INFO)).filteredOn(message -> message.startsWith("The Captured events dialog"))
                    .containsExactly("The Captured events dialog of the recovery was not shown while Exit closes the"
                            + " open project: the project was not opened, and what it offered to recover was kept."
                            + " It would have said: " + events);
            assertThat(messages(Level.INFO)).as("the recovery dialog was up when the exit began, not refused")
                    .noneMatch(message -> message.startsWith("The recovery dialog was not shown"));
            assertThat(welcomes).as("nor the Welcome window again").hasValue(1);
            assertThat(listing(songB.resolve("journal"))).as("what it offered to recover was kept")
                    .isEqualTo(journalBefore);
            assertThat(fixture.save.currentProjectIs(songB)).as("not opened").isFalse();
            assertThat(onFx(() -> fixture.lifecycle().isProjectInstallationPending()))
                    .as("the gate's Cancel left no project being installed").isFalse();
        } finally {
            try {
                hideTheShowingDialogs(RecoverableProjects.RECOVERY_DIALOG_TITLE::equals);
                fixture.await(() -> !fixture.lifecycle().isProjectInstallationPending()
                        && fixture.lifecycle().closeReadiness().pending()
                        instanceof ProjectLifecycleController.PendingSwitch.None, "the held work ended");
            } finally {
                stopCounting(counting, capturedEvents);
            }
        }
    }

    /**
     * Song B's Recover Session, first with no close — the dialog shows once
     * and its Cancel shows the Welcome window — then held on its worker while
     * an exit waits for it under the surface whose step is {@code waitStep}:
     * released, it shows no dialog and no Welcome window, logs the ruled INFO
     * once, keeps Song B's journal and does not open it. The recovery dialogs
     * are counted as they show; while the exit waits, a counted one is hidden
     * on a later turn (ruling-4 H2), never inside its own add notification.
     */
    private void aRecoveryDialogThatLandsWhileTheExitWaitsIsAnsweredWithCancelUnshown(String waitStep)
            throws Exception {
        Path songB = RecoverableProjects.withACheckpointAndAJournal(fixture.save, "Song B", 3);
        java.util.concurrent.atomic.AtomicInteger welcomes = new java.util.concurrent.atomic.AtomicInteger();
        List<Window> recoveryDialogs = new CopyOnWriteArrayList<>();
        AtomicBoolean hideOnALaterTurn = new AtomicBoolean();
        ListChangeListener<Window> counting = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage
                            && RecoverableProjects.RECOVERY_DIALOG_TITLE.equals(stage.getTitle())) {
                        recoveryDialogs.add(window);
                        if (hideOnALaterTurn.get()) {
                            Platform.runLater(window::hide);
                        }
                    }
                }
            }
        };
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        runOnFx(() -> {
            fixture.lifecycle().setWelcomePresenter(_ -> welcomes.incrementAndGet());
            Window.getWindows().addListener(counting);
        });
        try {
            try (AutoCloseable answering = RecoverableProjects.answeringTheRecoveryDialogWith("Cancel")) {
                runOnFx(() -> fixture.lifecycle().beginRecovery(songB));
                fixture.await(() -> welcomes.get() == 1 && !fixture.lifecycle().isProjectInstallationPending()
                        && fixture.lifecycle().closeReadiness().pending()
                        instanceof ProjectLifecycleController.PendingSwitch.None, "the dialog's Cancel ended it");
            }
            assertThat(recoveryDialogs).as("positive control: with no close the recovery dialog shows").hasSize(1);
            List<String> journalBefore = listing(songB.resolve("journal"));

            hideOnALaterTurn.set(true);
            runOnFx(() -> fixture.lifecycle().setBeforeRecoveryWork(worker -> {
                if (worker.equals(ProjectLifecycleController.RECOVERY_SCAN_WORKER)) {
                    held.countDown();
                    try {
                        released.await(ExitFixture.GUARD.toMillis() * 3, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }));
            runOnFx(() -> fixture.lifecycle().beginRecovery(songB));
            assertThat(held.await(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the recovery's work reached its worker").isTrue();

            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface().stepText())).as("the dialog lands after the surface")
                    .isEqualTo(waitStep);
            released.countDown();
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(recoveryDialogs).as("no recovery dialog was shown while the exit waited").hasSize(1);
            assertThat(welcomes).as("nor the Welcome window again").hasValue(1);
            assertThat(messages(Level.INFO)).filteredOn(message -> message.startsWith("The recovery dialog"))
                    .containsExactly("The recovery dialog was not shown while Exit closes the open project; the"
                            + " project was not opened, and what it offered to recover was kept.");
            assertThat(listing(songB.resolve("journal"))).as("what it offered to recover was kept")
                    .isEqualTo(journalBefore);
            assertThat(fixture.save.currentProjectIs(songB)).as("not opened").isFalse();
        } finally {
            released.countDown();
            try {
                fixture.await(() -> !fixture.lifecycle().isProjectInstallationPending()
                        && fixture.lifecycle().closeReadiness().pending()
                        instanceof ProjectLifecycleController.PendingSwitch.None, "the held work ended");
            } finally {
                runOnFx(() -> Window.getWindows().removeListener(counting));
            }
        }
    }

    /**
     * Ruling R-1.6 (PR #989 review 5711460812), through the open recovery
     * gate: the migration report that the install of a gated open of an
     * older project lands after the closing surface shows — a dialog shown
     * then would be fenced and, being application-modal, would disable the
     * Closing dialog — is not shown and is logged at INFO; Roll back is not
     * offered, project.daw is left as it was, and the exit goes on to HIDE.
     * With no close, the same door's open of another older project shows the
     * report.
     */
    @Test
    void anExitWaitingForAGatedOpensInstallLogsItsMigrationReportWithoutShowingIt() throws Exception {
        fixture.close();
        Path migrating = java.nio.file.Files.createDirectories(workspace.resolve("migrating"));
        fixture = new ExitFixture(migrating, AutoSaveConfig.DEFAULT,
                new ProjectDeserializer(aRegistryOneVersionAhead()));
        Path songC = fixture.save.anotherProjectOnDisk("Song C");
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        byte[] songBAsWritten = java.nio.file.Files.readAllBytes(songB.resolve("project.daw"));
        List<Window> migrationReports = new CopyOnWriteArrayList<>();
        AtomicBoolean hideOnALaterTurn = new AtomicBoolean();
        ListChangeListener<Window> counting = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && MIGRATION_REPORT_TITLE.equals(stage.getTitle())) {
                        migrationReports.add(window);
                        Platform.runLater(hideOnALaterTurn.get() ? window::hide : () -> pressOk(stage));
                    }
                }
            }
        };
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        runOnFx(() -> Window.getWindows().addListener(counting));
        try {
            openThroughTheDoor(songC);
            fixture.await(() -> fixture.save.currentProjectIs(songC)
                    && !fixture.lifecycle().isProjectInstallationPending(), "Song C installed");
            assertThat(fixture.save.projectManager.getLastMigrationReport().changesAuthoredContent())
                    .as("fixture: the open ran a migration that changes what the user authored").isTrue();
            assertThat(migrationReports).as("positive control: with no close the migration report shows")
                    .hasSize(1);

            hideOnALaterTurn.set(true);
            runOnFx(() -> fixture.lifecycle().setBeforeRecoveryWork(worker -> {
                if (worker.equals(ProjectLifecycleController.RECOVERY_SCAN_WORKER)) {
                    held.countDown();
                    try {
                        released.await(ExitFixture.GUARD.toMillis() * 3, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }));
            openThroughTheDoor(songB);
            assertThat(held.await(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the open's work reached its worker").isTrue();

            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface().stepText())).as("the report lands after the surface")
                    .isEqualTo("Finishing Open Project…");
            released.countDown();
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(messages(Level.INFO)).as("fixture: the install that has the report ran")
                    .contains("Opened project from " + songB);
            assertThat(migrationReports).as("no migration report was shown while the exit waited").hasSize(1);
            assertThat(messages(Level.INFO)).filteredOn(message -> message.startsWith("The migration report"))
                    .containsExactly("The migration report for " + songB + " was not shown while Exit closes the"
                            + " open project; Roll back was not offered, and project.daw is still v"
                            + MigrationRegistry.CURRENT_VERSION + ", so an open before the project is saved shows"
                            + " the report again.");
            assertThat(java.nio.file.Files.readAllBytes(songB.resolve("project.daw")))
                    .as("project.daw is left as it was").isEqualTo(songBAsWritten);
        } finally {
            released.countDown();
            try {
                fixture.await(() -> !fixture.lifecycle().isProjectInstallationPending(), "the held work ended");
            } finally {
                runOnFx(() -> Window.getWindows().removeListener(counting));
            }
        }
    }

    /**
     * Ruling R-1.6 (PR #989 review 5711460812) and ruling 6 R6-1's first
     * correction, through the open recovery gate: the migration report of a
     * gated open of an older project whose user chose "Don't show again" for
     * its version, which the open's install lands after the closing surface
     * shows, is neither shown nor logged, as with no close it is not shown;
     * the exit goes on to HIDE.
     */
    @Test
    void anExitWaitingForAGatedOpensInstallLogsNothingForASuppressedMigrationReport() throws Exception {
        fixture.close();
        Path migrating = java.nio.file.Files.createDirectories(workspace.resolve("migrating"));
        fixture = new ExitFixture(migrating, AutoSaveConfig.DEFAULT,
                new ProjectDeserializer(aRegistryOneVersionAhead()));
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        MigrationSuppression.suppress(songB, MigrationRegistry.CURRENT_VERSION + 1);
        List<Window> migrationReports = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> counting = hidingOnALaterTurn(MIGRATION_REPORT_TITLE::equals, migrationReports);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        runOnFx(() -> Window.getWindows().addListener(counting));
        try {
            runOnFx(() -> fixture.lifecycle().setBeforeRecoveryWork(worker -> {
                if (worker.equals(ProjectLifecycleController.RECOVERY_SCAN_WORKER)) {
                    holdUntilReleased(held, released);
                }
            }));
            openThroughTheDoor(songB);
            assertThat(held.await(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the open's work reached its worker").isTrue();

            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface().stepText())).as("the report lands after the surface")
                    .isEqualTo("Finishing Open Project…");
            released.countDown();
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(messages(Level.INFO)).as("fixture: the install that has the report ran")
                    .contains("Opened project from " + songB);
            assertThat(MigrationSuppression.isSuppressed(songB, MigrationRegistry.CURRENT_VERSION + 1))
                    .as("fixture: the report is suppressed for the version the open migrated to").isTrue();
            assertThat(migrationReports).as("no migration report was shown").isEmpty();
            assertThat(messages(Level.INFO)).as("nor logged")
                    .noneMatch(message -> message.startsWith("The migration report"));
        } finally {
            released.countDown();
            try {
                fixture.await(() -> !fixture.lifecycle().isProjectInstallationPending(), "the held work ended");
            } finally {
                stopCounting(counting, migrationReports);
            }
        }
    }

    /**
     * Ruling R-1.6 as ruling 7 amends it (PR #989 review 5711460812), through
     * the open recovery gate: a migration report a gated open's install
     * showed before the exit began still answers an assistive technology's
     * action, but its Roll back…, fired after the closing surface shows,
     * opens no Roll back migration? dialog and takes its Cancel, logged at
     * INFO: nothing is rolled back, the report stays open until its OK
     * answers it, project.daw is left as it was, and the exit goes on to
     * HIDE. With no close, the same press shows the confirmation.
     */
    @Test
    void aRollBackPressOnAMigrationReportUpWhenTheExitBeganOpensNoConfirmationAndRollsNothingBack()
            throws Exception {
        fixture.close();
        Path migrating = java.nio.file.Files.createDirectories(workspace.resolve("migrating"));
        fixture = new ExitFixture(migrating, AutoSaveConfig.DEFAULT,
                new ProjectDeserializer(aRegistryOneVersionAhead()));
        Logger dialogLog = Logger.getLogger(MigrationReportDialog.class.getName());
        Path songC = fixture.save.anotherProjectOnDisk("Song C");
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        byte[] songBAsWritten = java.nio.file.Files.readAllBytes(songB.resolve("project.daw"));
        List<Window> confirmations = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> counting = hidingOnALaterTurn(ROLL_BACK_CONFIRMATION_TITLE::equals, confirmations);
        dialogLog.addHandler(recording);
        try {
            runOnFx(() -> Window.getWindows().addListener(counting));
            openThroughTheDoor(songC);
            Stage withNoClose = theDialogShowing(MIGRATION_REPORT_TITLE::equals, "Song C's migration report shows");
            runOnFx(() -> buttonOf(withNoClose, MigrationReportDialog.ROLLBACK_BUTTON_TYPE)
                    .executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(confirmations).as("positive control: with no close Roll back… asks its confirmation")
                    .hasSize(1);
            assertThat(onFx(withNoClose::isShowing)).as("whose Cancel left the report open").isTrue();
            runOnFx(() -> buttonOf(withNoClose, MigrationReportDialog.OK_BUTTON_TYPE)
                    .executeAccessibleAction(AccessibleAction.FIRE));
            fixture.await(() -> fixture.save.currentProjectIs(songC)
                    && !fixture.lifecycle().isProjectInstallationPending(), "Song C installed");
            assertThat(messages(Level.INFO)).as("and nothing is logged")
                    .noneMatch(message -> message.startsWith("The “Roll back migration?” dialog"));

            openThroughTheDoor(songB);
            Stage upWhenTheExitBegan = theDialogShowing(MIGRATION_REPORT_TITLE::equals,
                    "Song B's migration report shows");
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface().stepText())).as("the exit waits for the gated open's install")
                    .isEqualTo("Finishing Open Project…");
            MigrationReport report = onFx(() -> fixture.save.projectManager.getLastMigrationReport());
            long refusedBefore = refusalsWhileTheExitCloses();
            runOnFx(() -> buttonOf(upWhenTheExitBegan, MigrationReportDialog.ROLLBACK_BUTTON_TYPE)
                    .executeAccessibleAction(AccessibleAction.FIRE));

            assertThat(confirmations).as("no Roll back migration? dialog was shown while the exit waited")
                    .hasSize(1);
            assertThat(messages(Level.INFO))
                    .filteredOn(message -> message.startsWith("The “Roll back migration?” dialog"))
                    .containsExactly("The “Roll back migration?” dialog of the migration from v" + report.fromVersion()
                            + " to v" + report.toVersion() + " was not shown while Exit closes the open project:"
                            + " its Cancel was taken, so nothing was rolled back, and the migration report stays"
                            + " open.");
            assertThat(onFx(upWhenTheExitBegan::isShowing)).as("the migration report stays open").isTrue();
            assertThat(refusalsWhileTheExitCloses()).as("no roll-back was asked for").isEqualTo(refusedBefore);
            assertThat(messages(Level.INFO)).as("the report was up when the exit began, not refused")
                    .noneMatch(message -> message.startsWith("The migration report"));

            runOnFx(() -> buttonOf(upWhenTheExitBegan, MigrationReportDialog.OK_BUTTON_TYPE)
                    .executeAccessibleAction(AccessibleAction.FIRE));
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
            assertThat(java.nio.file.Files.readAllBytes(songB.resolve("project.daw")))
                    .as("project.daw is left as it was").isEqualTo(songBAsWritten);
        } finally {
            try {
                hideTheShowingDialogs(MIGRATION_REPORT_TITLE::equals);
                fixture.await(() -> !fixture.lifecycle().isProjectInstallationPending(), "the held work ended");
            } finally {
                stopCounting(counting, confirmations);
                dialogLog.removeHandler(recording);
            }
        }
    }

    /**
     * Ruling R-1.6 (PR #989 review 5711460812), through the open recovery
     * gate: the Import Summary that the replacement of an Import DAWproject
     * Exchange lands after the closing surface shows — a dialog shown then
     * would be fenced and, being application-modal, would disable the
     * Closing dialog — is not shown and is logged at INFO with its text; the
     * imported session is installed, unsaved, so the exit asks about it,
     * Discard and close answers, and the exit goes on to HIDE. With no close,
     * the same door's import of another session shows the summary.
     */
    @Test
    void anExitWaitingForAnImportsReplacementLogsItsImportSummaryWithoutShowingIt() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        Path songI = aSessionOnDisk("Song I");
        Path songJ = aSessionOnDisk("Song J");
        SessionInterchangeController interchange = new SessionInterchangeController();
        String songIName = interchange.importSession(songI).sessionData().projectName();
        SessionImportResult songJRead = interchange.importSession(songJ);
        String songJSummary = interchange.buildImportSummary(songJRead);
        List<Window> summaries = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> counting = hidingOnALaterTurn(IMPORT_SUMMARY_TITLE::equals, summaries);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        runOnFx(() -> Window.getWindows().addListener(counting));
        try {
            importThroughTheDoor(songI);
            fixture.await(() -> songIName.equals(fixture.save.project.get().getName())
                    && !fixture.lifecycle().isProjectInstallationPending(), "Song I installed");
            assertThat(summaries).as("positive control: with no close the Import Summary shows").hasSize(1);
            assertThat(messages(Level.INFO)).as("and is not logged")
                    .noneMatch(message -> message.startsWith("The import summary"));
            runOnFx(() -> fixture.save.project.get().markClean());

            fixture.beforeTheOutgoingJournalCloses = () -> holdUntilReleased(held, released);
            importThroughTheDoor(songJ);
            assertThat(held.await(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the replacement reached its outgoing journal's close").isTrue();

            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface().stepText())).as("the summary lands after the surface")
                    .isEqualTo("Finishing Import DAWproject Exchange…");
            fixture.save.promptAnswer = "Discard and close";
            released.countDown();
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(messages(Level.INFO)).as("fixture: the replacement that has the summary ran")
                    .contains("Imported DAWproject session from " + songJ);
            assertThat(summaries).as("no Import Summary was shown while the exit waited").hasSize(1);
            assertThat(messages(Level.INFO)).filteredOn(message -> message.startsWith("The import summary"))
                    .containsExactly("The import summary of " + songJ + " was not shown while Exit closes the"
                            + " open project: " + songJSummary);
            assertThat(fixture.unsavedPrompts).as("the imported session, unsaved, was asked about")
                    .singleElement().satisfies(prompt -> assertThat(prompt.header())
                            .isEqualTo("Save the changes to “" + songJRead.sessionData().projectName()
                                    + "” before closing?"));
            assertThat(fixture.steps).as("and Discard and close answered")
                    .contains(new ExitCoordinator.StepOutcome(ExitCoordinator.ExitStep.GUARD,
                            ExitCoordinator.StepResult.DONE, "Discard and close"));
        } finally {
            released.countDown();
            fixture.beforeTheOutgoingJournalCloses = () -> { };
            try {
                fixture.await(() -> !fixture.lifecycle().isProjectInstallationPending(), "the held work ended");
            } finally {
                stopCounting(counting, summaries);
            }
        }
    }

    /**
     * Ruling R-1.6 (PR #989 review 5711460812), through the open recovery
     * gate: the Project Locked dialog of a gated open of a project another
     * session holds, which the open's worker asks for after the closing
     * surface shows — a dialog shown then would be fenced and, being
     * application-modal, would disable the Closing dialog, and the exit waits
     * for the open, whose worker waits for the dialog's answer — is not
     * shown: its Cancel is taken and logged at INFO, the project is not
     * opened, its lock is left to the other session, and the exit goes on to
     * HIDE. With no close, the same door's open of that project shows the
     * dialog.
     */
    @Test
    void anExitWaitingForAGatedOpenAnswersItsProjectLockedDialogWithCancelWithoutShowingIt() throws Exception {
        Logger dialogLog = Logger.getLogger(LockConflictDialog.class.getName());
        AtomicBoolean holdTheWorker = new AtomicBoolean();
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        List<Window> lockDialogs = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> counting = hidingOnALaterTurn(title -> title.startsWith(LOCKED_TITLE_PREFIX),
                lockDialogs);
        dialogLog.addHandler(recording);
        try {
            runOnFx(() -> fixture.save.project.get().markClean());
            Path songB = fixture.save.anotherProjectOnDisk("Song B");
            new ProjectLockManager(InstantSource.system(), "bob", "Mix-Room", 7L).forceAcquire(songB);
            Path lockFile = songB.resolve(ProjectLockManager.LOCK_FILE_NAME);
            String bobsLock = java.nio.file.Files.readString(lockFile);
            LockConflictDialog dialog = new LockConflictDialog(Instant::now,
                    () -> fixture.lifecycle().closingDoor());
            fixture.save.projectManager.setLockConflictHandler((lock, stale) -> {
                if (holdTheWorker.get()) {
                    holdUntilReleased(held, released);
                }
                return dialog.resolve(lock, stale);
            });
            runOnFx(() -> Window.getWindows().addListener(counting));
            openThroughTheDoor(songB);
            fixture.await(() -> !fixture.lifecycle().isProjectInstallationPending()
                    && fixture.lifecycle().closeReadiness().pending()
                    instanceof ProjectLifecycleController.PendingSwitch.None, "the dialog's Cancel ended the open");
            assertThat(lockDialogs).as("positive control: with no close the Project Locked dialog shows")
                    .singleElement().satisfies(window -> assertThat(((Stage) window).getTitle())
                            .isEqualTo(LOCKED_TITLE_PREFIX + "bob@Mix-Room"));

            holdTheWorker.set(true);
            openThroughTheDoor(songB);
            assertThat(held.await(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the open's worker met the lock").isTrue();

            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface().stepText())).as("the dialog would land after the surface")
                    .isEqualTo("Finishing Open Project…");
            released.countDown();
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(lockDialogs).as("no Project Locked dialog was shown while the exit waited").hasSize(1);
            assertThat(messages(Level.INFO)).filteredOn(message -> message.startsWith("The Project Locked dialog"))
                    .containsExactly("The Project Locked dialog of bob@Mix-Room's lock was not shown while Exit"
                            + " closes the open project: its Cancel was taken, so the locked folder was neither"
                            + " opened read-only nor taken over, and the lock was left to bob@Mix-Room.");
            assertThat(fixture.save.currentProjectIs(songB)).as("not opened").isFalse();
            assertThat(java.nio.file.Files.readString(lockFile)).as("its lock is left to the other session")
                    .isEqualTo(bobsLock);
        } finally {
            released.countDown();
            try {
                fixture.await(() -> !fixture.lifecycle().isProjectInstallationPending(), "the held work ended");
            } finally {
                stopCounting(counting, lockDialogs);
                dialogLog.removeHandler(recording);
            }
        }
    }

    /**
     * Ruling R-1.6 as ruling 7 amends it (PR #989 review 5711460812), through
     * the open recovery gate: a Project Locked dialog shown before the exit
     * began still answers an assistive technology's action, but its Take
     * Over (Steal Lock), fired after the closing surface shows, opens no
     * Confirm Lock Take-Over dialog and takes its Cancel, logged at INFO: the
     * lock is left to its holder, the project is not opened, and the exit
     * goes on to HIDE. With no close, the same answer shows the confirmation.
     */
    @Test
    void aTakeOverAnswerOnAProjectLockedDialogUpWhenTheExitBeganOpensNoConfirmationAndTakesItsCancel()
            throws Exception {
        Logger dialogLog = Logger.getLogger(LockConflictDialog.class.getName());
        List<Window> confirmations = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> counting = hidingOnALaterTurn(TAKE_OVER_CONFIRMATION_TITLE::equals,
                confirmations);
        dialogLog.addHandler(recording);
        try {
            runOnFx(() -> fixture.save.project.get().markClean());
            Path songB = fixture.save.anotherProjectOnDisk("Song B");
            new ProjectLockManager(InstantSource.system(), "bob", "Mix-Room", 7L).forceAcquire(songB);
            Path lockFile = songB.resolve(ProjectLockManager.LOCK_FILE_NAME);
            String bobsLock = java.nio.file.Files.readString(lockFile);
            fixture.save.projectManager.setLockConflictHandler(new LockConflictDialog(Instant::now,
                    () -> fixture.lifecycle().closingDoor()));
            runOnFx(() -> Window.getWindows().addListener(counting));
            openThroughTheDoor(songB);
            Stage withNoClose = theDialogShowing(title -> title.startsWith(LOCKED_TITLE_PREFIX),
                    "the Project Locked dialog shows");
            runOnFx(() -> buttonLabelled(withNoClose, TAKE_OVER_BUTTON_TEXT)
                    .executeAccessibleAction(AccessibleAction.FIRE));
            fixture.await(() -> !fixture.lifecycle().isProjectInstallationPending()
                    && fixture.lifecycle().closeReadiness().pending()
                    instanceof ProjectLifecycleController.PendingSwitch.None, "the confirmation's Cancel ended the open");
            assertThat(confirmations).as("positive control: with no close Take Over asks its confirmation")
                    .hasSize(1);
            assertThat(fixture.save.notifications()).filteredOn(entry -> entry.level() == NotificationLevel.ERROR)
                    .extracting(NotificationEntry::message).as("whose Cancel failed the open")
                    .anyMatch(message -> message.startsWith("Open failed: "));
            assertThat(java.nio.file.Files.readString(lockFile)).as("and left the lock to its holder")
                    .isEqualTo(bobsLock);
            assertThat(messages(Level.INFO)).as("and nothing is logged")
                    .noneMatch(message -> message.startsWith("The Confirm Lock Take-Over dialog"));

            openThroughTheDoor(songB);
            Stage upWhenTheExitBegan = theDialogShowing(title -> title.startsWith(LOCKED_TITLE_PREFIX),
                    "the Project Locked dialog shows again");
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface().stepText())).as("the exit waits for the gated open")
                    .isEqualTo("Finishing Open Project…");
            runOnFx(() -> buttonLabelled(upWhenTheExitBegan, TAKE_OVER_BUTTON_TEXT)
                    .executeAccessibleAction(AccessibleAction.FIRE));
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(confirmations).as("no Confirm Lock Take-Over dialog was shown while the exit waited")
                    .hasSize(1);
            assertThat(messages(Level.INFO))
                    .filteredOn(message -> message.startsWith("The Confirm Lock Take-Over dialog"))
                    .containsExactly("The Confirm Lock Take-Over dialog of bob@Mix-Room's lock was not shown while"
                            + " Exit closes the open project: its Cancel was taken, so the locked folder was neither"
                            + " opened read-only nor taken over, and the lock was left to bob@Mix-Room.");
            assertThat(messages(Level.INFO)).as("the Project Locked dialog was up when the exit began, not refused")
                    .noneMatch(message -> message.startsWith("The Project Locked dialog"));
            assertThat(fixture.save.currentProjectIs(songB)).as("not opened").isFalse();
            assertThat(java.nio.file.Files.readString(lockFile)).as("its lock is left to the other session")
                    .isEqualTo(bobsLock);
        } finally {
            try {
                hideTheShowingDialogs(title -> title.startsWith(LOCKED_TITLE_PREFIX));
                fixture.await(() -> !fixture.lifecycle().isProjectInstallationPending(), "the held work ended");
            } finally {
                stopCounting(counting, confirmations);
                dialogLog.removeHandler(recording);
            }
        }
    }

    /**
     * Ruling R-1.6 (PR #989 review 5711460812): a user's Save As whose plan,
     * which the exit waits for, refuses its target after the closing surface
     * shows — the refusal, being application-modal, would disable the Closing
     * dialog, and the chooser opened again would be a question the exit waits
     * for — shows neither: the refusal is logged at INFO, nothing is copied,
     * the Save As ends as its chooser's Cancel ends it, and the exit goes on
     * to HIDE. With no close, the same refusal shows and the chooser opens
     * again.
     */
    @Test
    void anExitWaitingForASaveAsWhoseTargetIsRefusedShowsNeitherItsRefusalNorItsChooserAgain() throws Exception {
        Logger saveAsLog = Logger.getLogger(SaveAsFlow.class.getName());
        AtomicBoolean holdThePlan = new AtomicBoolean();
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        Path target = workspace.resolve("Song A copy");
        String refusal = "A folder named “Song A copy” already exists in " + workspace.toString()
                + ". Choose another name.";
        saveAsLog.addHandler(recording);
        try {
            runOnFx(() -> fixture.save.project.get().markClean());
            runOnFx(() -> fixture.save.saveAsFlow().setPlanner((_, chosen, _) -> {
                if (holdThePlan.get()) {
                    holdUntilReleased(held, released);
                }
                return new SaveAsPlan.TargetTaken(chosen.getParent(), chosen.getFileName().toString());
            }));
            fixture.save.saveAsTargets.choose(target);
            java.util.concurrent.CompletionStage<SaveAsFlow.SaveAsResult> withNoClose =
                    onFx(() -> fixture.save.saveAsFlow().start(SaveAsFlow.Prefill.COPY));
            assertThat(withNoClose.toCompletableFuture().get(ExitFixture.GUARD.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS)).as("the chooser asked again, and its Cancel ended it")
                    .isEqualTo(new SaveAsFlow.ChooserCancelled());
            assertThat(fixture.save.saveAsRefusals).as("positive control: with no close the refusal shows")
                    .containsExactly(refusal);
            assertThat(fixture.save.saveAsTargets.calls()).as("and the chooser opens again").hasSize(2);
            assertThat(messages(Level.INFO)).as("and nothing is logged")
                    .noneMatch(message -> message.startsWith("The Save Project As refusal"));

            holdThePlan.set(true);
            fixture.save.saveAsTargets.choose(target);
            java.util.concurrent.CompletionStage<SaveAsFlow.SaveAsResult> whileTheExitWaits =
                    onFx(() -> fixture.save.saveAsFlow().start(SaveAsFlow.Prefill.COPY));
            assertThat(held.await(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the plan reached its worker").isTrue();
            assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                    .isEqualTo(new ProjectLifecycleController.PendingSwitch.SaveAsCopying());

            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            assertThat(onFx(() -> fixture.surface().stepText())).as("the refusal lands after the surface")
                    .isEqualTo("Finishing Save As…");
            released.countDown();
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(whileTheExitWaits.toCompletableFuture().get(ExitFixture.GUARD.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS)).as("the Save As ended as its chooser's Cancel ends it")
                    .isEqualTo(new SaveAsFlow.ChooserCancelled());
            assertThat(fixture.save.saveAsRefusals).as("no refusal was shown while the exit waited")
                    .containsExactly(refusal);
            assertThat(fixture.save.saveAsTargets.calls()).as("nor the chooser opened again: its one call chose")
                    .hasSize(3);
            assertThat(messages(Level.INFO)).filteredOn(message -> message.startsWith("The Save Project As refusal"))
                    .containsExactly("The Save Project As refusal of “Song A copy” was not shown while Exit closes"
                            + " the open project: " + refusal + " The chooser was not opened again, so the Save As"
                            + " ended as its Cancel ends it, and nothing was copied.");
            assertThat(target).as("nothing was copied").doesNotExist();
            assertThat(listing(workspace)).as("nor staged").noneMatch(entry -> entry.contains(".saving-as"));
            assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending())).as("the Save As is pending no more")
                    .isEqualTo(new ProjectLifecycleController.PendingSwitch.None());
        } finally {
            released.countDown();
            try {
                fixture.await(() -> fixture.lifecycle().closeReadiness().pending()
                        instanceof ProjectLifecycleController.PendingSwitch.None, "the held work ended");
            } finally {
                saveAsLog.removeHandler(recording);
            }
        }
    }

    /** File ▸ Open of {@code folder}, its chooser answered; returns once the FX steps returned. */
    private void openThroughTheDoor(Path folder) throws Exception {
        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> folder);
            fixture.lifecycle().onOpenProject();
        });
    }

    /** File ▸ Import DAWproject Exchange of {@code session}, its chooser answered; returns once the FX steps returned. */
    private void importThroughTheDoor(Path session) throws Exception {
        runOnFx(() -> {
            fixture.lifecycle().setImportSessionPrompt(() -> session);
            fixture.lifecycle().onImportSession();
        });
    }

    /** A DAWproject Exchange of a one-track project named {@code name}, exported into the workspace; its file. */
    private Path aSessionOnDisk(String name) throws java.io.IOException {
        DawProject exported = new DawProject(name, AudioFormat.CD_QUALITY);
        exported.createAudioTrack("Imported");
        return new SessionInterchangeController().exportSession(exported, workspace, name).outputPath();
    }

    /** Counts {@code held} down, then waits — at most three guards — until {@code released} is: a worker's hold. */
    private static void holdUntilReleased(java.util.concurrent.CountDownLatch held,
                                          java.util.concurrent.CountDownLatch released) {
        held.countDown();
        try {
            released.await(ExitFixture.GUARD.toMillis() * 3, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A listener that records each window added whose title {@code title}
     * accepts, and hides it on a later turn, never inside its own add
     * notification (ruling-4 H2); see {@link #stopCounting}.
     */
    private static ListChangeListener<Window> hidingOnALaterTurn(Predicate<String> title, List<Window> recorded) {
        return change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage && stage.getTitle() != null && title.test(stage.getTitle())) {
                        recorded.add(window);
                        Platform.runLater(window::hide);
                    }
                }
            }
        };
    }

    /** Removes {@code counting} first, then hides every window it recorded that still shows (ruling-4 H2). */
    private static void stopCounting(ListChangeListener<Window> counting, List<Window> recorded) throws Exception {
        runOnFx(() -> {
            Window.getWindows().removeListener(counting);
            for (Window window : recorded) {
                if (window.isShowing()) {
                    window.hide();
                }
            }
        });
    }

    /**
     * Waits, bounded, until a dialog whose title {@code title} accepts shows,
     * and returns its window; the first found when several show.
     */
    private Stage theDialogShowing(Predicate<String> title, String what) throws Exception {
        fixture.await(() -> showingDialog(title).isPresent(), what);
        return onFx(() -> showingDialog(title).orElseThrow());
    }

    /** A showing window whose title {@code title} accepts, if any. FX thread. */
    private static Optional<Stage> showingDialog(Predicate<String> title) {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(stage -> stage.isShowing() && stage.getTitle() != null && title.test(stage.getTitle()))
                .findFirst();
    }

    /** Hides every showing window whose title {@code title} accepts: a test's own first dialogs, at its end. */
    private static void hideTheShowingDialogs(Predicate<String> title) throws Exception {
        runOnFx(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window instanceof Stage stage && stage.isShowing() && stage.getTitle() != null
                        && title.test(stage.getTitle())) {
                    stage.hide();
                }
            }
        });
    }

    /** The button of {@code type} on the dialog shown in {@code dialog}. FX thread. */
    private static Button buttonOf(Stage dialog, ButtonType type) {
        return (Button) dialogPaneOf(dialog).lookupButton(type);
    }

    /** The button labelled {@code label} on the dialog shown in {@code dialog}. FX thread. */
    private static Button buttonLabelled(Stage dialog, String label) {
        return buttonLabelled(dialogPaneOf(dialog), label);
    }

    /** The button labelled {@code label} on {@code pane}. FX thread. */
    private static Button buttonLabelled(DialogPane pane, String label) {
        ButtonType type = pane.getButtonTypes().stream().filter(candidate -> candidate.getText().equals(label))
                .findFirst().orElseThrow();
        return (Button) pane.lookupButton(type);
    }

    /** The dialog pane of the dialog shown in {@code dialog}. FX thread. */
    private static DialogPane dialogPaneOf(Stage dialog) {
        Node root = dialog.getScene().getRoot();
        return root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
    }

    /**
     * Waits, bounded, until the {@code count}-th recovery dialog built shows,
     * and returns it.
     */
    private RecoveryDialog theRecoveryDialogShowing(List<RecoveryDialog> built, int count) throws Exception {
        fixture.await(() -> built.size() == count && built.get(count - 1).isShowing(), "the recovery dialog shows");
        return built.get(count - 1);
    }

    /** The recovery dialog's Inspect events… button. FX thread. */
    private static Button inspectEventsButtonOf(RecoveryDialog dialog) {
        return (Button) dialog.getDialogPane().lookupButton(dialog.inspectEventsButtonType());
    }

    /** Presses the migration report's OK, which is also its cancel, so the report closes. FX thread. */
    private static void pressOk(Stage stage) {
        Node root = stage.getScene().getRoot();
        DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
        ((Button) pane.lookupButton(MigrationReportDialog.OK_BUTTON_TYPE)).fire();
    }

    /**
     * A registry one schema version ahead of the files the application writes, every step the identity and,
     * as {@link ProjectMigration#step} makes it, one that changes what the user authored: a project written at
     * {@link MigrationRegistry#CURRENT_VERSION} opens migrated, with the migration report.
     */
    private static MigrationRegistry aRegistryOneVersionAhead() {
        int target = MigrationRegistry.CURRENT_VERSION + 1;
        MigrationRegistry.Builder builder = MigrationRegistry.builder(target);
        for (int version = 1; version < target; version++) {
            int from = version;
            builder.add(ProjectMigration.step(from, "v" + from + "->v" + (from + 1), document -> document));
        }
        return builder.build();
    }

    @Test
    void aRecordingInFlightIsAskedAboutBeforeThePendingSwitchIsWaitedFor() throws Exception {
        SaveFlowFixture.Publishes.Hold copying = holdASaveAsCopy();
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        runOnFx(takes::record);

        fixture.closeBox();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(fixture.stopTakePrompts).as("the confirmation came first (the studio engineer's S1)").hasSize(1);
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .as("while the Save As is still pending")
                .isEqualTo(new ProjectLifecycleController.PendingSwitch.SaveAsCopying());
        finish(copying);
    }

    @Test
    void aDoorsOpenFileDialogRefusesTheExit() throws Exception {
        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> {
                Object key = new Object();
                Platform.runLater(() -> {
                    fixture.stage().fireEvent(new javafx.stage.WindowEvent(fixture.stage(),
                            javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
                    Platform.runLater(() -> Platform.runLater(() -> Platform.exitNestedEventLoop(key, null)));
                });
                Platform.enterNestedEventLoop(key);
                return null;
            });
            fixture.save.project.get().markClean();
            fixture.lifecycle().onOpenProject();
        });
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message)
                .containsExactly("Open Project's file dialog is still open — finish or cancel it first.");
    }

    @Test
    void closeProjectIsRefusedWhileADoorWaitsForASave() throws Exception {
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.promptAnswer = "Save";
        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> songB);
            fixture.lifecycle().onOpenProject();
        });
        head.awaitHeld();

        fixture.closeProject();
        fixture.await(() -> fixture.steps.size() == 2, "GUARD's end");

        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message)
                .containsExactly("Still saving — Open Project continues when the save finishes.");
        head.release();
        fixture.await(() -> fixture.save.currentProjectIs(songB), "the door goes on as it was");
    }

    @Test
    void doorsAStaleContinuationOpensWhileTheCloseRunsAreRefusedAtFineAndOneCloserRuns() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.promptAnswer = "Save and close";
        fixture.closeBox();
        head.awaitHeld();
        AtomicBoolean proceeded = new AtomicBoolean();

        runOnFx(() -> {
            fixture.lifecycle().whenProjectMayClose(ProjectLifecycleController.OPEN_PROJECT_DOOR,
                    () -> proceeded.set(true));
        });
        head.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(proceeded).as("the Welcome/Hub open was refused").isFalse();
        assertThat(messages(Level.FINE)).contains("Refused while Exit closes the open project");
        assertThat(fixture.save.notifications()).as("at FINE: no toast").isEmpty();
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA)))
                .as("one closer ran: one seal").filteredOn(JournalRecord::isSessionSeal).hasSize(1);
    }

    /**
     * PR #989 review 5711460812: the Welcome window's Discard recovery,
     * activated by an assistive technology's action while the close waits on
     * a save queued before it, is refused at FINE before the window is
     * dismissed: the Welcome window and its recover row stay up, no
     * confirmation shows, no recovery becomes the switch pending — so the
     * exit never waits on it — no project is being installed, and the
     * journal stays. After the exit's Cancel, the same action on the same
     * button asks its confirmation, which the test backs out of.
     */
    @Test
    void theWelcomeWindowsDiscardIsRefusedWhileTheCloseRuns() throws Exception {
        fixture.close();
        recentProjectsNode = Preferences.userRoot().node("daw-test-discard-while-closing-" + System.nanoTime());
        Path withRecents = java.nio.file.Files.createDirectories(workspace.resolve("recents"));
        fixture = new ExitFixture(withRecents, AutoSaveConfig.DEFAULT, true, java.util.function.UnaryOperator.identity(),
                true, new RecentProjectsStore(recentProjectsNode));
        Path songB = RecoverableProjects.withACheckpointAndAJournal(fixture.save, "Song B", 3);
        fixture.save.projectManager.getRecentProjectsStore().addRecentProject(songB);
        List<WelcomeView> views = new CopyOnWriteArrayList<>();
        List<Stage> welcomeStages = new CopyOnWriteArrayList<>();
        try {
            runOnFx(() -> {
                fixture.lifecycle().setWelcomePresenter(view -> {
                    views.add(view);
                    Stage stage = new Stage();
                    stage.setTitle("Welcome");
                    stage.setScene(new Scene(view, 900, 600));
                    welcomeStages.add(stage);
                    stage.show();
                });
                fixture.lifecycle().showWelcome();
            });
            WelcomeView welcome = views.getFirst();
            WelcomeTestSupport.awaitClassification(welcome);
            assertThat(welcome.recoverPaths()).as("fixture: Song B is a recover row").contains(songB);
            Button discard = onFx(() -> discardButtonOf(welcome, songB));
            Stage welcomeStage = welcomeStages.getFirst();

            fixture.edit("Bass");
            SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
            try {
                fixture.save.save();
                head.awaitHeld();
                fixture.closeBox();
                fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(),
                        "the close waits on the save");
                fixture.fire(ExitCoordinator.SURFACE_DELAY);
                String waitStep = onFx(() -> fixture.surface().stepText());
                long refusedBefore = refusalsWhileTheExitCloses();

                List<Window> refusedShows = windowsShownBy(() -> discard.executeAccessibleAction(AccessibleAction.FIRE));

                assertThat(onFx(welcomeStage::isShowing)).as("the Welcome window stays up").isTrue();
                assertThat(onFx(() -> discardButtonOf(welcome, songB)))
                        .as("and its recover row with it").isSameAs(discard);
                assertThat(refusedShows).as("no confirmation shows").isEmpty();
                assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                        .as("no recovery is pending").isNotInstanceOf(ProjectLifecycleController.PendingSwitch.Recovering.class);
                assertThat(onFx(() -> fixture.surface().stepText())).as("the exit never waits on it")
                        .isEqualTo(waitStep).isNotEqualTo("Finishing Recover Session…");
                assertThat(onFx(() -> fixture.lifecycle().isProjectInstallationPending()))
                        .as("no project is being installed").isFalse();
                assertThat(songB.resolve("journal")).as("the journal stays").isDirectory();
                assertThat(refusalsWhileTheExitCloses() - refusedBefore).as("refused at FINE, once").isEqualTo(1);

                runOnFx(() -> fixture.surface().keepButton().fire());
                fixture.await(() -> !fixture.fence().isRaised(), "the exit's Cancel");
                List<Window> admittedShows = windowsShownBy(
                        () -> discard.executeAccessibleAction(AccessibleAction.FIRE));
                assertThat(admittedShows).as("positive control: the same action asks its confirmation")
                        .filteredOn(window -> !welcomeStages.contains(window)).singleElement()
                        .satisfies(window -> assertThat(((Stage) window).getTitle()).isEqualTo("Discard recovery"));
                assertThat(songB.resolve("journal")).as("which the test backed out of").isDirectory();
            } finally {
                head.release();
            }
        } finally {
            for (WelcomeView view : views) {
                for (Thread scan : onFx(view::pendingScans)) {
                    scan.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
                }
            }
            runOnFx(() -> {
                for (Stage stage : welcomeStages) {
                    if (stage.isShowing()) {
                        stage.hide();
                    }
                }
            });
        }
    }

    /**
     * PR #989 review 5711460812: a discard confirmed after the close began —
     * its confirmation asked while the close was not yet running — is refused
     * at FINE: no project is being installed and no recovery is pending, so
     * the exit never waits on it. After the exit's Cancel, the same call
     * discards the journal.
     */
    @Test
    void aConfirmedDiscardIsRefusedWhenTheCloseBeganWhileItAsked() throws Exception {
        Path songB = RecoverableProjects.withACheckpointAndAJournal(fixture.save, "Song B", 3);
        java.util.concurrent.atomic.AtomicInteger welcomes = new java.util.concurrent.atomic.AtomicInteger();
        runOnFx(() -> fixture.lifecycle().setWelcomePresenter(_ -> welcomes.incrementAndGet()));
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        try {
            fixture.save.save();
            head.awaitHeld();
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(),
                    "the close waits on the save");
            long refusedBefore = refusalsWhileTheExitCloses();

            List<Boolean> refused = onFx(() -> {
                fixture.lifecycle().discardConfirmedRecovery(songB);
                return List.of(fixture.lifecycle().isProjectInstallationPending(),
                        fixture.lifecycle().closeReadiness().pending()
                                instanceof ProjectLifecycleController.PendingSwitch.Recovering);
            });

            assertThat(refused).as("no project is being installed, and no recovery is pending")
                    .containsExactly(false, false);
            assertThat(refusalsWhileTheExitCloses() - refusedBefore).as("refused at FINE, once").isEqualTo(1);
            assertThat(songB.resolve("journal")).as("the journal stays").isDirectory();

            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            runOnFx(() -> fixture.surface().keepButton().fire());
            fixture.await(() -> !fixture.fence().isRaised(), "the exit's Cancel");
            boolean installing = onFx(() -> {
                fixture.lifecycle().discardConfirmedRecovery(songB);
                return fixture.lifecycle().isProjectInstallationPending();
            });
            assertThat(installing).as("positive control: the same call begins the discard").isTrue();
            fixture.await(() -> !java.nio.file.Files.exists(songB.resolve("journal"))
                    && !fixture.lifecycle().isProjectInstallationPending() && welcomes.get() == 1,
                    "the discard ended");
        } finally {
            head.release();
        }
    }

    /**
     * PR #989 review 5711460812: a close that begins while a named snapshot's
     * name dialog is open — the close waiting on a save queued before it —
     * refuses the dialog's OK, which an assistive technology's action fires
     * past the window's fence, at FINE: no snapshot is made. Before the
     * close, the same door's OK made one. A dialog's OK answers it once, so
     * the positive control is an earlier dialog of the same door, answered
     * before the close.
     */
    @Test
    void aNamedSnapshotWhoseNameDialogWasOpenWhenTheCloseBeganIsRefusedAtItsOk() throws Exception {
        java.util.concurrent.CountDownLatch created = new java.util.concurrent.CountDownLatch(1);
        runOnFx(() -> fixture.lifecycle().setNamedSnapshotCreatedHook(created::countDown));
        try {
            Button ok = openTheSnapshotNameDialog();
            runOnFx(() -> ok.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(created.await(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("positive control: before the close, the dialog's OK made a snapshot").isTrue();
            assertThat(snapshotsOf(fixture.songA)).hasSize(1);

            fixture.edit("Keys");
            SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
            try {
                fixture.save.save();
                head.awaitHeld();
                Button refusedOk = openTheSnapshotNameDialog();
                fixture.closeBox();
                fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(),
                        "the close began, and waits on the save");
                long refusedBefore = refusalsWhileTheExitCloses();

                runOnFx(() -> refusedOk.executeAccessibleAction(AccessibleAction.FIRE));
                // The dialog's continuation runs once its nested loop has been left, after this FX turn.
                fixture.await(() -> refusalsWhileTheExitCloses() > refusedBefore, "the OK's continuation was refused");

                assertThat(refusalsWhileTheExitCloses() - refusedBefore).as("its OK was refused at FINE, once")
                        .isEqualTo(1);
                assertThat(onFx(ExitDuringAPendingSwitchTest::snapshotNameDialog)).as("the dialog answered").isEmpty();
            } finally {
                head.release();
            }
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
            fixture.save.settle();
            assertThat(snapshotsOf(fixture.songA)).as("no snapshot was made while the close ran").hasSize(1);
        } finally {
            runOnFx(() -> {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window instanceof Stage stage && SNAPSHOT_DIALOG_TITLE.equals(stage.getTitle())) {
                        stage.hide();
                    }
                }
            });
        }
    }

    /**
     * Ruling 8 R8-2 (PR #989 review 5711460812; ruling R-1.1 (e)): a door's
     * unsaved-changes prompt asked with no close running and no switch
     * pending — the Hub's or the Welcome window's open of a recent project —
     * is still up when the exit begins, so the exit neither refuses nor waits
     * for the door; its Discard, fired by an assistive technology's action
     * past the window's fence after the exit decided, is refused at FINE and
     * does what its Cancel does: no load begins, the other project's folder
     * is untouched, and the exit's own Save and close goes on to HIDE. With
     * no close, the same answer opens the project.
     */
    @Test
    void aDiscardOnADoorPromptUpWhenTheExitBeganIsRefusedAtFineAndOpensNothing() throws Exception {
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        Path songC = fixture.save.anotherProjectOnDisk("Song C");
        SaveFlowFixture.Publishes.Hold head = null;
        fixture.save.answersByInput = ExitDuringAPendingSwitchTest::isADoorPrompt;
        try {
            DialogPane withNoClose = openTheDoorPrompt(() -> fixture.lifecycle().whenProjectMayClose(
                    ProjectLifecycleController.OPEN_PROJECT_DOOR,
                    () -> fixture.lifecycle().loadProjectFromPath(songB)));
            runOnFx(() -> buttonLabelled(withNoClose, DOOR_PROMPT_DISCARD)
                    .executeAccessibleAction(AccessibleAction.FIRE));
            fixture.await(() -> fixture.save.currentProjectIs(songB)
                    && !fixture.lifecycle().isProjectInstallationPending(), "Song B opened");
            fixture.save.settle();
            assertThat(fixture.save.currentProjectIs(songB))
                    .as("positive control: with no close the door's Discard opens the project").isTrue();

            fixture.edit("Keys");
            List<String> songCBefore = listing(songC);
            head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
            fixture.save.promptAnswer = "Save and close";
            DialogPane upWhenTheExitBegan = openTheDoorPrompt(() -> fixture.lifecycle().whenProjectMayClose(
                    ProjectLifecycleController.OPEN_PROJECT_DOOR,
                    () -> fixture.lifecycle().loadProjectFromPath(songC)));
            assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                    .as("nothing is pending while the door's prompt asks, so the exit does not wait for it")
                    .isInstanceOf(ProjectLifecycleController.PendingSwitch.None.class);
            fixture.closeBox();
            head.awaitHeld();
            assertThat(fixture.stepNames()).as("the exit decided before the door's prompt was answered")
                    .contains("GUARD DONE");
            assertThat(onFx(() -> isShowing(upWhenTheExitBegan))).as("the door's prompt is still up").isTrue();
            long refusedBefore = refusalsWhileTheExitCloses();
            int shownBefore = fixture.save.notifications().size();

            runOnFx(() -> buttonLabelled(upWhenTheExitBegan, DOOR_PROMPT_DISCARD)
                    .executeAccessibleAction(AccessibleAction.FIRE));
            awaitTheDoorPromptsAnswer(upWhenTheExitBegan, refusedBefore);

            assertThat(refusalsWhileTheExitCloses() - refusedBefore).as("its Discard was refused at FINE, once")
                    .isEqualTo(1);
            assertThat(onFx(() -> fixture.lifecycle().isProjectInstallationPending())).as("no load began").isFalse();
            assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending())).as("no switch is pending")
                    .isInstanceOf(ProjectLifecycleController.PendingSwitch.None.class);
            assertThat(fixture.save.currentProjectIs(songC)).as("Song C is not opened").isFalse();
            assertThat(fixture.save.notifications()).as("at FINE: no toast").hasSize(shownBefore);

            head.release();
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
            assertThat(listing(songC)).as("Song C's folder is untouched: no lease and no lock was taken")
                    .isEqualTo(songCBefore);
            assertThat(java.nio.file.Files.readString(songB.resolve("project.daw")))
                    .as("the exit's own Save and close stood").contains("Keys");
        } finally {
            try {
                if (head != null) {
                    head.release();
                }
                hideTheShowingDialogs(ExitFixture.UNSAVED_TITLE::equals);
            } finally {
                fixture.save.answersByInput = _ -> false;
            }
        }
    }

    /**
     * Ruling 8 R8-2 (PR #989 review 5711460812; ruling R-1.1 (e)): the prompt
     * Open Project asks again after its chooser, the project having been
     * edited while the chooser was open, is still up when the exit begins,
     * with no switch pending; its Save, fired by an assistive technology's
     * action past the window's fence after the exit decided, is refused at
     * FINE and does what its Cancel does: the door waits for no save of its
     * own, no load begins, and the exit's own Save and close goes on to
     * HIDE. With no close, the same answer saves the project and opens the
     * chosen one.
     */
    @Test
    void aSaveOnAnAnsweredSwitchPromptUpWhenTheExitBeganIsRefusedAtFineAndSavesNothing() throws Exception {
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        Path songC = fixture.save.anotherProjectOnDisk("Song C");
        java.util.concurrent.atomic.AtomicInteger chooserCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Path> chosen =
                new java.util.concurrent.atomic.AtomicReference<>(songB);
        java.util.concurrent.atomic.AtomicReference<String> editedWhileChoosing =
                new java.util.concurrent.atomic.AtomicReference<>("Edited while choosing");
        SaveFlowFixture.Publishes.Hold head = null;
        fixture.save.answersByInput = ExitDuringAPendingSwitchTest::isADoorPrompt;
        try {
            runOnFx(() -> {
                fixture.save.project.get().markClean();
                fixture.lifecycle().setOpenProjectPrompt(() -> {
                    chooserCalls.incrementAndGet();
                    // An edit from a floating window while the chooser is open.
                    fixture.save.project.get().createAudioTrack(editedWhileChoosing.get());
                    fixture.save.project.get().markDirty();
                    return chosen.get();
                });
            });
            DialogPane withNoClose = openTheDoorPrompt(() -> fixture.lifecycle().onOpenProject());
            assertThat(chooserCalls).as("fixture: the prompt is the answered switch's, asked after the chooser")
                    .hasValue(1);
            runOnFx(() -> buttonLabelled(withNoClose, DOOR_PROMPT_SAVE).executeAccessibleAction(AccessibleAction.FIRE));
            fixture.await(() -> fixture.save.currentProjectIs(songB)
                    && !fixture.lifecycle().isProjectInstallationPending(), "Song A saved, then Song B opened");
            fixture.save.settle();
            assertThat(fixture.save.currentProjectIs(songB))
                    .as("positive control: with no close the prompt's Save opens the chosen project").isTrue();
            assertThat(java.nio.file.Files.readString(fixture.songA.resolve("project.daw")))
                    .as("after saving the edit made while the chooser was open").contains("Edited while choosing");

            editedWhileChoosing.set("Keys");
            chosen.set(songC);
            List<String> songCBefore = listing(songC);
            head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
            fixture.save.promptAnswer = "Save and close";
            DialogPane upWhenTheExitBegan = openTheDoorPrompt(() -> fixture.lifecycle().onOpenProject());
            assertThat(chooserCalls).as("fixture: asked again after the chooser").hasValue(2);
            assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                    .as("nothing is pending while the answered switch's prompt asks, so the exit does not wait for it")
                    .isInstanceOf(ProjectLifecycleController.PendingSwitch.None.class);
            fixture.closeBox();
            head.awaitHeld();
            assertThat(fixture.stepNames()).as("the exit decided before the prompt was answered")
                    .contains("GUARD DONE");
            assertThat(onFx(() -> isShowing(upWhenTheExitBegan))).as("the prompt is still up").isTrue();
            long refusedBefore = refusalsWhileTheExitCloses();

            runOnFx(() -> buttonLabelled(upWhenTheExitBegan, DOOR_PROMPT_SAVE)
                    .executeAccessibleAction(AccessibleAction.FIRE));
            awaitTheDoorPromptsAnswer(upWhenTheExitBegan, refusedBefore);

            assertThat(refusalsWhileTheExitCloses() - refusedBefore).as("its Save was refused at FINE, once")
                    .isEqualTo(1);
            assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                    .as("no door waits for a save of its own")
                    .isInstanceOf(ProjectLifecycleController.PendingSwitch.None.class);
            assertThat(onFx(() -> fixture.lifecycle().isProjectInstallationPending())).as("no load began").isFalse();
            assertThat(fixture.save.currentProjectIs(songC)).as("Song C is not opened").isFalse();

            head.release();
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
            assertThat(listing(songC)).as("Song C's folder is untouched: no lease and no lock was taken")
                    .isEqualTo(songCBefore);
            assertThat(java.nio.file.Files.readString(songB.resolve("project.daw")))
                    .as("the exit's own Save and close stood").contains("Keys");
        } finally {
            try {
                if (head != null) {
                    head.release();
                }
                hideTheShowingDialogs(ExitFixture.UNSAVED_TITLE::equals);
            } finally {
                fixture.save.answersByInput = _ -> false;
            }
        }
    }

    /**
     * Ruling 8 R8-5 (PR #989 review 5711460812; ruling R-1.1 (e), in ruling 3
     * G4a's shape): Export DAWproject Exchange's folder chooser is native, so
     * the window's fence never covers it, and with no switch pending a close
     * that begins while it is open neither refuses nor waits for it; the
     * folder it returns once the close began is not written, logged at INFO,
     * and the exit goes on to HIDE. With no close, the same export writes the
     * folder.
     */
    @Test
    void aSessionExportWhoseFolderIsChosenAfterTheExitBeganWritesNothing() throws Exception {
        runOnFx(() -> fixture.lifecycle().setProjectActionsAllowed(() -> !fixture.lifecycle()
                .isProjectInstallationPending() && !fixture.coordinator().isWindowInert()));
        Path exported = workspace.resolve("Exported");
        assertThat(onFx(() -> fixture.lifecycle().exportSessionTo(exported)))
                .as("positive control: with no close the export is admitted").isTrue();
        assertThat(listing(exported)).as("and writes the folder")
                .anyMatch(entry -> entry.startsWith("Song A.dawproject "));
        assertThat(exportedNotifications()).as("and says so").hasSize(1);

        Path whileClosing = workspace.resolve("Exported while closing");
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        try {
            fixture.save.save();
            head.awaitHeld();
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(),
                    "the close began, and waits on the save");

            assertThat(onFx(() -> fixture.lifecycle().exportSessionTo(whileClosing)))
                    .as("the folder the chooser returned once the close began is refused").isFalse();
            assertThat(messages(Level.INFO))
                    .filteredOn(message -> message.startsWith("The DAWproject Exchange export of"))
                    .containsExactly("The DAWproject Exchange export of “Song A” to “" + whileClosing.toAbsolutePath()
                            + "” was not written: the project could not be edited.");
            assertThat(listing(whileClosing)).as("nothing is written").isEmpty();
            assertThat(exportedNotifications()).as("and nothing more is said").hasSize(1);
        } finally {
            head.release();
        }
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
    }

    /** A user's Save As of Song A, its copy held at its first publish: the switch pending meanwhile. */
    private SaveFlowFixture.Publishes.Hold holdASaveAsCopy() throws Exception {
        Path target = workspace.resolve("Song A copy");
        fixture.save.saveAsTargets.choose(target);
        SaveFlowFixture.Publishes.Hold copying = fixture.save.publishes.holdNext(
                path -> path.toString().contains("Song A copy"));
        runOnFx(() -> fixture.save.saveAsFlow().start(SaveAsFlow.Prefill.COPY));
        copying.awaitHeld();
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isEqualTo(new ProjectLifecycleController.PendingSwitch.SaveAsCopying());
        return copying;
    }

    /** Restore from Archive of {@code archive} into {@code restored}, its extraction held at its head's publish. */
    private SaveFlowFixture.Publishes.Hold holdAnArchiveExtraction(Path archive, Path restored) throws Exception {
        SaveFlowFixture.Publishes.Hold extraction = fixture.save.publishes.holdNext(
                target -> SaveFlowFixture.isHead(target) && target.startsWith(restored));
        runOnFx(() -> {
            fixture.lifecycle().setFileWriter(fixture.save.writer);
            fixture.lifecycle().setArchiveToRestorePrompt(() -> archive);
            fixture.lifecycle().setRestoreParentPrompt(() -> restored);
            fixture.lifecycle().onRestoreFromArchive();
        });
        extraction.awaitHeld();
        assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                .isEqualTo(new ProjectLifecycleController.PendingSwitch.Extracting());
        return extraction;
    }

    /** Lets the held copy finish, and waits until the Save As ended. */
    private void finish(SaveFlowFixture.Publishes.Hold copying) throws Exception {
        copying.release();
        fixture.await(() -> fixture.lifecycle().closeReadiness().pending()
                instanceof ProjectLifecycleController.PendingSwitch.None, "the Save As ended");
    }

    private List<String> messages(Level level) {
        return logged.stream().filter(entry -> entry.getLevel() == level).map(LogRecord::getMessage).toList();
    }

    /** How many FINE refusals the lifecycle controller logged so far for a door while the exit closes. */
    private long refusalsWhileTheExitCloses() {
        return messages(Level.FINE).stream().filter("Refused while Exit closes the open project"::equals).count();
    }

    /**
     * Runs {@code act} on the FX thread and returns every window it showed,
     * each recorded as it is added and hidden on a later turn, never inside
     * its own add notification; the listener is removed, then any recorded
     * window still showing hidden, before this returns (ruling-4 H2).
     */
    private static List<Window> windowsShownBy(Runnable act) throws Exception {
        List<Window> shown = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> counting = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    shown.add(window);
                    Platform.runLater(window::hide);
                }
            }
        };
        runOnFx(() -> {
            Window.getWindows().addListener(counting);
            try {
                act.run();
            } finally {
                Window.getWindows().removeListener(counting);
                for (Window window : shown) {
                    if (window.isShowing()) {
                        window.hide();
                    }
                }
            }
        });
        return List.copyOf(shown);
    }

    /** The files and their sizes under {@code directory}, sorted, or an empty list when it does not exist. */
    private static List<String> listing(Path directory) throws java.io.IOException {
        if (!java.nio.file.Files.isDirectory(directory)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> entries = java.nio.file.Files.walk(directory)) {
            List<String> listed = new java.util.ArrayList<>();
            for (Path entry : entries.sorted().toList()) {
                listed.add(directory.relativize(entry) + (java.nio.file.Files.isRegularFile(entry)
                        ? " " + java.nio.file.Files.size(entry) : "/"));
            }
            return listed;
        }
    }

    /** The named snapshots of {@code project}, from its {@code snapshots/} folder. */
    private static List<Path> snapshotsOf(Path project) throws java.io.IOException {
        Path folder = project.resolve(SnapshotStore.SNAPSHOTS_DIR);
        if (!java.nio.file.Files.isDirectory(folder)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> entries = java.nio.file.Files.list(folder)) {
            return entries.filter(java.nio.file.Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith("."))
                    .toList();
        }
    }

    /**
     * Asks for a named snapshot as the menu does, on a later FX turn — its
     * name dialog's nested loop runs there — and returns the dialog's OK
     * once it shows.
     */
    private Button openTheSnapshotNameDialog() throws Exception {
        Platform.runLater(() -> fixture.lifecycle().onCreateNamedSnapshot());
        fixture.await(() -> snapshotNameDialog().isPresent(), "the snapshot's name dialog shows");
        return onFx(() -> {
            javafx.scene.Node root = snapshotNameDialog().orElseThrow().getScene().getRoot();
            DialogPane pane = root instanceof DialogPane direct ? direct : (DialogPane) root.lookup(".dialog-pane");
            return (Button) pane.lookupButton(ButtonType.OK);
        });
    }

    /**
     * Whether {@code stage} is a door's unsaved-changes prompt: titled as
     * every unsaved-changes prompt is, under the door's header, not the
     * close's own. The test answers it itself, by an assistive technology's
     * action ({@link SaveFlowFixture#answersByInput}). FX thread.
     */
    private static boolean isADoorPrompt(Stage stage) {
        if (!ExitFixture.UNSAVED_TITLE.equals(stage.getTitle()) || stage.getScene() == null) {
            return false;
        }
        Node root = stage.getScene().getRoot();
        Node pane = root instanceof DialogPane ? root : root.lookup(".dialog-pane");
        return pane instanceof DialogPane prompt && DOOR_PROMPT_HEADER.equals(prompt.getHeaderText());
    }

    /**
     * Runs {@code ask} on a later FX turn — the prompt's nested loop runs
     * there, as {@link #openTheSnapshotNameDialog} posts its door — then
     * waits, bounded, until a door's unsaved-changes prompt shows, and
     * returns its dialog pane.
     */
    private DialogPane openTheDoorPrompt(Runnable ask) throws Exception {
        Platform.runLater(ask);
        fixture.await(() -> showingDoorPrompt().isPresent(), "the door's unsaved-changes prompt shows");
        return onFx(() -> dialogPaneOf(showingDoorPrompt().orElseThrow()));
    }

    /** The door's unsaved-changes prompt, while one shows. FX thread. */
    private static Optional<Stage> showingDoorPrompt() {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(stage -> stage.isShowing() && isADoorPrompt(stage)).findFirst();
    }

    /** Whether the window of {@code pane} still shows. FX thread. */
    private static boolean isShowing(DialogPane pane) {
        return pane.getScene() != null && pane.getScene().getWindow() != null
                && pane.getScene().getWindow().isShowing();
    }

    /**
     * Waits, bounded, until the door's prompt of {@code pane}, just
     * answered, no longer shows and its answer has been acted on — a refusal
     * logged since {@code refusedBefore}, a project being installed, or a
     * switch pending — so the wait ends whether the answer was refused or
     * started the door's work: the prompt's continuation runs once its
     * nested loop has been left, after the answering FX turn.
     */
    private void awaitTheDoorPromptsAnswer(DialogPane pane, long refusedBefore) throws Exception {
        fixture.await(() -> !isShowing(pane) && (refusalsWhileTheExitCloses() > refusedBefore
                || fixture.lifecycle().isProjectInstallationPending()
                || !(fixture.lifecycle().closeReadiness().pending()
                instanceof ProjectLifecycleController.PendingSwitch.None)), "the door's prompt's answer was acted on");
    }

    /** The messages of the notifications an export showed once it wrote its file, in order. */
    private List<String> exportedNotifications() throws Exception {
        return fixture.save.notifications().stream().map(NotificationEntry::message)
                .filter(message -> message.startsWith(EXPORTED_PREFIX)).toList();
    }

    /** The Discard recovery button of {@code project}'s recover row on {@code welcome}, in the rows' order. FX thread. */
    private static Button discardButtonOf(WelcomeView welcome, Path project) {
        List<javafx.scene.Node> discards = List.copyOf(welcome.recoverSection().lookupAll(".welcome-recover-discard"));
        assertThat(discards).as("a Discard recovery button per recover row").hasSameSizeAs(welcome.recoverPaths());
        return (Button) discards.get(welcome.recoverPaths().indexOf(project));
    }

    /** The snapshot's name dialog, while it shows. FX thread. */
    private static Optional<Stage> snapshotNameDialog() {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(stage -> SNAPSHOT_DIALOG_TITLE.equals(stage.getTitle()) && stage.isShowing()).findFirst();
    }
}
