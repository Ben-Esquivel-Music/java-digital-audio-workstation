package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;

import javafx.application.Platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ExitDuringAPendingSwitchTest {

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
}
