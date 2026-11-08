package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;
import com.benesquivelmusic.daw.core.persistence.journal.JournalRecord;
import com.benesquivelmusic.daw.core.persistence.journal.JournalState;
import com.benesquivelmusic.daw.core.persistence.journal.ProjectContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, ruling 4 Q3 (the stage-6 review S1, the stage-7 review S1 and
 * N9): a step of the close that throws ends the run by how far it had got,
 * judged on the run's own progress, and the window is never left inert. Before
 * the journal's detach the close stops with the project open and one ERROR,
 * {@code close.failed} — a later close starts afresh; after the detach the
 * tail is skipped and the journal drained unsealed, one ERROR,
 * {@code close.failed.paused}, in place of the keep's WARNING; once the
 * release began an exit goes on as Quit anyway, logged only; Close Project
 * ends with the window live and {@code close.failed.closed}. The Welcome
 * window comes after Close Project's run has ended — the window live, the
 * close no longer pending — and a failure of it is logged only.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ACloseStepThatThrowsEndsTheRunTest {

    @TempDir
    Path workspace;

    private ExitFixture fixture;

    @BeforeEach
    void songAEdited() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        fixture.edit("Bass");
    }

    @AfterEach
    void close() throws Exception {
        try {
            fixture.close();
        } finally {
            logHandlers.forEach(coordinatorLog::removeHandler);
        }
    }

    /** P1: a prompt that throws once ends the exit with the project open; the next close box exits. */
    @Test
    void anExitWhosePromptThrowsEndsWithOneErrorAndTheNextCloseBoxExits() throws Exception {
        throwOnce(fixture, () -> fixture.beforeTheUnsavedPrompt = () -> { }, thrower -> fixture.beforeTheUnsavedPrompt = thrower);

        fixture.closeBox();
        fixture.await(() -> errorCountOnFx() == 1, "the run's ERROR");

        assertThat(errors()).containsExactly("Exit stopped because of an internal error: injected. “Song A” is"
                + " still open; save your changes before you try again.");
        assertTheWindowIsLiveAndTheProjectOpen();
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD FAILED");

        fixture.save.promptAnswer = "Discard and close";
        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        assertThat(fixture.unsavedPrompts).as("the second close box reached the prompt").hasSize(1);
        assertThat(fixture.terminalExits).hasValue(1);
    }

    /**
     * P1 through a surface action (ruling 4 Q3, constraint 1): the keep
     * button's own step throws — its CANCELLED outcome's observer — and the
     * run still ends with one ERROR, the window live.
     */
    @Test
    void aKeepActionThatThrowsEndsTheRunWithOneError() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        head.awaitHeld();
        AtomicBoolean thrown = new AtomicBoolean();
        fixture.onStep = outcome -> {
            if (outcome.result() == ExitCoordinator.StepResult.CANCELLED && thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("injected");
            }
        };
        try {
            fixture.closeBox();
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            runOnFx(() -> fixture.surface().keepButton().fire());

            assertThat(errors()).containsExactly("Exit stopped because of an internal error: injected. “Song A” is"
                    + " still open; save your changes before you try again.");
            assertThat(onFx(() -> fixture.coordinator().isWindowInert())).as("the fence is lowered").isFalse();
            assertThat(onFx(() -> fixture.lifecycle().aSwitchIsPending())).isFalse();
        } finally {
            head.release();
        }
        fixture.save.settle();
    }

    /** P1, Close Project: the same, its text naming Close Project. */
    @Test
    void aCloseProjectWhosePromptThrowsEndsWithOneErrorNamingCloseProject() throws Exception {
        throwOnce(fixture, () -> fixture.beforeTheUnsavedPrompt = () -> { }, thrower -> fixture.beforeTheUnsavedPrompt = thrower);

        CompletionStage<ExitCoordinator.CloseOutcome> outcome = fixture.closeProject();
        assertThat(outcome.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExitCoordinator.CloseOutcome.Failed.class);

        assertThat(errors()).containsExactly("Close Project stopped because of an internal error: injected. “Song A”"
                + " is still open; save your changes before you try again.");
        assertTheWindowIsLiveAndTheProjectOpen();
    }

    /**
     * The stage-6 review S1's case: the close's transport stop throws — the
     * run ends Failed, the window is live, and a second close box starts a
     * new run.
     */
    @Test
    void aTransportStopThatThrowsEndsTheRunAndASecondCloseBoxStartsANewOne() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        throwOnce(fixture, () -> fixture.beforeTheTransportStop = () -> { },
                thrower -> fixture.beforeTheTransportStop = thrower);

        CompletionStage<ExitCoordinator.CloseOutcome> outcome = onFx(() -> fixture.coordinator()
                .request(new ExitCoordinator.CloseDoor.WindowClose()));
        assertThat(outcome.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExitCoordinator.CloseOutcome.Failed.class);
        assertTheWindowIsLiveAndTheProjectOpen();
        assertThat(errors()).hasSize(1);

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        assertThat(fixture.stepNames()).filteredOn(name -> name.startsWith("INTERCEPT")).hasSize(2);
        assertThat(fixture.terminalExits).hasValue(1);
    }

    /**
     * P2: a step observer that throws on the CLOSING_CHECKPOINT outcome,
     * after the detach — the tail skipped, the journal drained unsealed, the
     * project open and its lock held, one ERROR in place of the keep's
     * WARNING.
     */
    @Test
    void aStepThatThrowsAfterTheDetachDrainsTheJournalUnsealedWithOnePausedError() throws Exception {
        AtomicBoolean thrown = new AtomicBoolean();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.CLOSING_CHECKPOINT && thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("injected");
            }
        };
        fixture.save.promptAnswer = "Discard and close";

        fixture.closeBox();
        fixture.await(() -> errorCountOnFx() == 1, "the run's ERROR");

        assertThat(errors()).containsExactly("Exit stopped because of an internal error: injected. “Song A” is"
                + " still open, and journaled persistence is paused until this project is reopened; save your"
                + " changes before you try again.");
        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message).as("one fact, one notification")
                .noneMatch(message -> message.contains("cancelled"));
        assertTheWindowIsLiveAndTheProjectOpen();
        fixture.awaitTheJournalEnded();
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA))).as("never sealed")
                .noneMatch(JournalRecord::isSessionSeal);
        assertThat(fixture.stepNames()).contains("SEAL SKIPPED", "RELEASE SKIPPED").doesNotContain("HIDE DONE");
    }

    /** P3, an exit: the observer throws on RELEASE DONE — DISPOSE and HIDE, logged only. */
    @Test
    void anExitWhoseReleaseOutcomeThrowsGoesOnAsQuitAnywayToHide() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        AtomicBoolean thrown = new AtomicBoolean();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.RELEASE && outcome.result() == ExitCoordinator.StepResult.DONE
                    && thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("injected");
            }
        };

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.stepNames()).containsSubsequence("RELEASE DONE", "DISPOSE DONE", "HIDE DONE");
        assertThat(fixture.terminalExits).hasValue(1);
        assertThat(fixture.disposals).hasValue(1);
        assertThat(fixture.save.notifications()).as("the window is going away: logged only").isEmpty();
    }

    /** P3, Close Project: the rebuild of the Untitled project throws — the window live, close.failed.closed. */
    @Test
    void aCloseProjectWhoseRebuildThrowsEndsWithTheClosedErrorAndTheWindowLive() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        fixture.save.onProjectUIRebuild = () -> {
            throw new IllegalStateException("injected");
        };

        CompletionStage<ExitCoordinator.CloseOutcome> outcome = fixture.closeProject();
        assertThat(outcome.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExitCoordinator.CloseOutcome.Failed.class);
        fixture.save.onProjectUIRebuild = () -> { };

        assertThat(errors()).containsExactly("Close Project stopped because of an internal error: injected. “Song A”"
                + " was closed, but the window may not have been reset; quit and restart the application before you go"
                + " on working.");
        assertThat(onFx(() -> fixture.coordinator().isWindowInert())).as("the fence is lowered").isFalse();
        assertThat(onFx(() -> fixture.lifecycle().aSwitchIsPending())).as("the close is no longer pending").isFalse();
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("Song A's lock is released")
                .doesNotExist();
    }

    /**
     * The stage-7 review N9: the Welcome window comes once Close Project's
     * run has ended — the window live, the close no longer pending, the
     * outcome complete; a Welcome hook that throws is outside the run: the
     * outcome stays Closed, the Untitled project installed, no notification.
     */
    @Test
    void theWelcomeWindowComesAfterTheRunEndedAndItsFailureIsLoggedOnly() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        AtomicReference<CompletionStage<ExitCoordinator.CloseOutcome>> outcome = new AtomicReference<>();
        List<Boolean> seen = new CopyOnWriteArrayList<>();
        runOnFx(() -> fixture.lifecycle().setWelcomeAfterClose(() -> {
            seen.add(fixture.coordinator().isWindowInert());
            seen.add(fixture.lifecycle().aSwitchIsPending());
            seen.add(outcome.get().toCompletableFuture().isDone());
        }));
        runOnFx(() -> outcome.set(fixture.coordinator().request(new ExitCoordinator.CloseDoor.CloseProject())));
        fixture.await(() -> seen.size() == 3, "the Welcome hook");
        assertThat(seen).as("inert, pending, outcome complete").containsExactly(false, false, true);

        runOnFx(() -> fixture.lifecycle().setWelcomeAfterClose(() -> {
            throw new IllegalStateException("injected welcome");
        }));
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> songB);
            fixture.lifecycle().onOpenProject();
        });
        fixture.await(() -> fixture.save.currentProjectIs(songB)
                && !fixture.lifecycle().isProjectInstallationPending(), "Song B installed");
        List<NotificationEntry> before = fixture.save.notifications();
        CompletionStage<ExitCoordinator.CloseOutcome> second = fixture.closeProject();

        assertThat(second.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Closed());
        fixture.save.settle();
        assertThat(onFx(() -> fixture.save.project.get().getName())).isEqualTo("Untitled Project");
        assertThat(fixture.save.notifications()).as("no notification for the hook's failure").isEqualTo(before);
        assertThat(onFx(() -> fixture.coordinator().isWindowInert())).isFalse();
    }

    /**
     * The app-fix-2 review N4: a step of the run that throws inside the
     * closer's detach callback ends the run as P2 — the tail skipped, the
     * journal drained, one paused ERROR — and the closer's turn then writes
     * no closing checkpoint of the project that stays open.
     */
    @Test
    void aStepThatThrowsInTheDetachsOwnTurnWritesNoClosingCheckpointOfTheProjectThatStaysOpen() throws Exception {
        AtomicBoolean thrown = new AtomicBoolean();
        // After FLUSH the next ten-second arming is the detach's own timer (the wait clock is already armed).
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.FLUSH) {
                fixture.timers.onArm = delay -> {
                    if (delay.equals(ExitCoordinator.KEEP_AFTER_DETACH_DELAY) && thrown.compareAndSet(false, true)) {
                        throw new IllegalStateException("injected");
                    }
                };
            }
        };
        fixture.save.promptAnswer = "Discard and close";
        try {
            fixture.closeBox();
            fixture.await(() -> errorCountOnFx() == 1, "the run's ERROR");
        } finally {
            fixture.timers.onArm = _ -> { };
        }
        fixture.save.projectManager.whenWritersIdle().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(thrown).as("fixture: the detach's turn threw").isTrue();
        assertThat(errors()).containsExactly("Exit stopped because of an internal error: injected. “Song A” is"
                + " still open, and journaled persistence is paused until this project is reopened; save your"
                + " changes before you try again.");
        assertTheWindowIsLiveAndTheProjectOpen();
        assertThat(fixture.events).as("no closing checkpoint of a project that stays open")
                .doesNotContain("checkpoint Written CLOSING");
        fixture.awaitTheJournalEnded();
        assertThat(JournalState.records(ProjectContext.forProject(fixture.songA))).as("drained, never sealed")
                .noneMatch(JournalRecord::isSessionSeal);
    }

    /**
     * The app-fix-2 review N3: DISPOSE is published once the disposal's
     * outcome is known — an observer that throws on DISPOSE DONE is a step
     * that failed, not the disposal's failure: no second disposal is asked
     * for, and HIDE follows.
     */
    @Test
    void aDisposeOutcomeObserverThatThrowsAsksForNoSecondDisposalAndTheExitHides() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        AtomicBoolean thrown = new AtomicBoolean();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.DISPOSE && outcome.result() == ExitCoordinator.StepResult.DONE
                    && thrown.compareAndSet(false, true)) {
                throw new IllegalStateException("injected");
            }
        };
        List<java.util.logging.LogRecord> severe = recordSevere();

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.disposals).as("the window's disposal ran once").hasValue(1);
        assertThat(severe).extracting(java.util.logging.LogRecord::getMessage)
                .as("not logged as the disposal's failure")
                .noneMatch(message -> message.startsWith("The window's disposal failed"));
        assertThat(fixture.terminalExits).hasValue(1);
    }

    /**
     * The app-fix-2 review N3 and the studio engineer's ruling on app fix
     * pass 2's Java items, §5: P3's going-on as Quit anyway that itself
     * throws — here its DISPOSE's outcome observer — is logged as that,
     * then HIDE follows.
     */
    @Test
    void aFailureWhileGoingOnAsQuitAnywayIsLoggedAsSuchAndTheExitHides() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        AtomicBoolean releaseThrew = new AtomicBoolean();
        AtomicBoolean disposeThrew = new AtomicBoolean();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.RELEASE && outcome.result() == ExitCoordinator.StepResult.DONE
                    && releaseThrew.compareAndSet(false, true)) {
                throw new IllegalStateException("injected");
            }
            if (outcome.step() == ExitCoordinator.ExitStep.DISPOSE && outcome.result() == ExitCoordinator.StepResult.DONE
                    && disposeThrew.compareAndSet(false, true)) {
                throw new IllegalStateException("injected again");
            }
        };
        List<java.util.logging.LogRecord> severe = recordSevere();

        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(severe).extracting(java.util.logging.LogRecord::getMessage)
                .contains("Exit protocol: going on as Quit anyway failed: injected again");
        assertThat(fixture.disposals).hasValue(1);
        assertThat(fixture.terminalExits).hasValue(1);
    }

    /**
     * The app-fix-2 review N5 (the studio engineer's ruling on app fix pass
     * 2's Java items, §3): an exit begun between Close Project's end and its
     * posted Welcome window — a dependent of the outcome posts the close box
     * inside {@code end()}, as an input event between the two turns would
     * arrive — gets no Welcome window under it; the exit goes on to HIDE.
     */
    @Test
    void anExitBegunBeforeTheWelcomeWindowsTurnShowsNoWelcomeWindowUnderIt() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        AtomicInteger welcomeRuns = new AtomicInteger();
        runOnFx(() -> fixture.lifecycle().setWelcomeAfterClose(welcomeRuns::incrementAndGet));

        runOnFx(() -> fixture.coordinator().request(new ExitCoordinator.CloseDoor.CloseProject())
                .whenComplete((ended, failure) -> javafx.application.Platform.runLater(() -> fixture.stage()
                        .fireEvent(new javafx.stage.WindowEvent(fixture.stage(),
                                javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)))));
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        fixture.save.settle();

        assertThat(welcomeRuns).as("no Welcome window under the exit").hasValue(0);
        assertThat(fixture.terminalExits).hasValue(1);
    }

    /**
     * The same for a door begun first: Open Project's file dialog, held open
     * by a nested loop, is up when the Welcome window's turn comes — a switch
     * is pending — so no Welcome window opens over it.
     */
    @Test
    void aDoorBegunBeforeTheWelcomeWindowsTurnShowsNoWelcomeWindowOverIt() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        AtomicInteger welcomeRuns = new AtomicInteger();
        AtomicBoolean chooserOpen = new AtomicBoolean();
        Object chooser = new Object();
        runOnFx(() -> {
            fixture.lifecycle().setWelcomeAfterClose(welcomeRuns::incrementAndGet);
            fixture.lifecycle().setOpenProjectPrompt(() -> {
                chooserOpen.set(true);
                javafx.application.Platform.enterNestedEventLoop(chooser);
                return null;
            });
        });

        runOnFx(() -> fixture.coordinator().request(new ExitCoordinator.CloseDoor.CloseProject())
                .whenComplete((ended, failure) -> javafx.application.Platform.runLater(() ->
                        fixture.lifecycle().onOpenProject())));
        fixture.await(chooserOpen::get, "Open Project's file dialog is open");
        try {
            // A turn queued after the Welcome window's: once it ran, the Welcome window's turn has run.
            runOnFx(() -> { });
            assertThat(welcomeRuns).as("no Welcome window over the open file dialog").hasValue(0);
        } finally {
            runOnFx(() -> javafx.application.Platform.exitNestedEventLoop(chooser, null));
        }
        fixture.save.settle();
        assertThat(welcomeRuns).hasValue(0);
    }

    /**
     * The stage-8 review B1: a Welcome window whose turn comes once the
     * application stopped — the stop posted between Close Project's end and
     * the Welcome window's turn — is not shown: the application stop is a
     * close begun first, and the first-run wizard's modal loop would
     * otherwise open inside it.
     */
    @Test
    void aWelcomeWindowWhoseTurnComesOnceTheApplicationStoppedIsNotShown() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        AtomicInteger welcomeRuns = new AtomicInteger();
        AtomicReference<java.util.concurrent.CompletableFuture<Void>> stop = new AtomicReference<>();
        runOnFx(() -> fixture.lifecycle().setWelcomeAfterClose(welcomeRuns::incrementAndGet));

        runOnFx(() -> fixture.coordinator().request(new ExitCoordinator.CloseDoor.CloseProject())
                .whenComplete((ended, failure) -> stop.set(fixture.stopTheApplication())));
        fixture.await(() -> stop.get() != null, "the stop, posted at Close Project's end");
        ExitFixture.awaitStopped(stop.get());
        fixture.save.settle();

        assertThat(welcomeRuns).as("no Welcome window once the application stopped").hasValue(0);
    }

    /** Records this test's {@code SEVERE} lines of the coordinator; the handler goes in {@link #close()}. */
    private List<java.util.logging.LogRecord> recordSevere() {
        List<java.util.logging.LogRecord> severe = new CopyOnWriteArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord logRecord) {
                if (logRecord.getLevel() == java.util.logging.Level.SEVERE) {
                    severe.add(logRecord);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        coordinatorLog.addHandler(handler);
        logHandlers.add(handler);
        return severe;
    }

    // Held strongly: the log manager keeps a logger only weakly, so a handler added to one nobody holds may be lost.
    private final java.util.logging.Logger coordinatorLog =
            java.util.logging.Logger.getLogger(ExitCoordinator.class.getName());
    private final List<java.util.logging.Handler> logHandlers = new CopyOnWriteArrayList<>();

    private void assertTheWindowIsLiveAndTheProjectOpen() throws Exception {
        assertThat(onFx(() -> fixture.coordinator().isWindowInert())).as("the fence is lowered").isFalse();
        assertThat(onFx(() -> fixture.lifecycle().aSwitchIsPending())).as("the close is no longer pending").isFalse();
        assertThat(onFx(() -> fixture.stage().isShowing())).isTrue();
        assertThat(fixture.save.currentProjectIs(fixture.songA)).as("the project is open").isTrue();
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("its lock is held").exists();
        fixture.save.projectManager.getLockManager().verifyHeld();
        assertThat(fixture.terminalExits).hasValue(0);
    }

    /** How many ERRORs the history holds; FX thread. */
    private long errorCountOnFx() {
        return fixture.save.shown.getEntries().stream().filter(entry -> entry.level() == NotificationLevel.ERROR)
                .count();
    }

    private List<String> errors() throws Exception {
        return fixture.save.notifications().stream().filter(entry -> entry.level() == NotificationLevel.ERROR)
                .map(NotificationEntry::message).toList();
    }

    /**
     * Installs, through {@code install}, a hook that throws
     * {@code IllegalStateException("injected")} once and then restores the
     * no-op through {@code restore}.
     */
    private static void throwOnce(ExitFixture fixture, Runnable restore,
                                  java.util.function.Consumer<Runnable> install) {
        AtomicBoolean thrown = new AtomicBoolean();
        install.accept(() -> {
            if (thrown.compareAndSet(false, true)) {
                restore.run();
                throw new IllegalStateException("injected");
            }
        });
    }
}
