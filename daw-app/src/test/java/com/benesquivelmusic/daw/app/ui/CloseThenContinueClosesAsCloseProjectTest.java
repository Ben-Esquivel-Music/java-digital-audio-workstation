package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, design §1.8 and ruling 4 Q6.4 — the close-then-continue
 * primitive ({@code ExitCoordinator.closeOpenProjectThenContinue}), the
 * future project workspace switch's door: it closes as Close Project does —
 * ruling R1's confirmation with Close Project's header, R2's prompt, R8's
 * {@code Keep project open}, its tail with no release wait and no HIDE — then
 * installs the Untitled project without the Welcome window (R10) and hands
 * its continuation exactly one outcome, posted: {@code Closed} once the
 * Untitled project is installed; {@code Cancelled} for Keep recording, the
 * Veto, a keep — whose WARNING names the switch — every refusal, and an exit
 * that upgraded it once that exit is certain; {@code Failed(cause)} for R9.
 * Unlike Close Project it is never refused for "nothing to close": on a
 * pristine Untitled project every step runs.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CloseThenContinueClosesAsCloseProjectTest {

    private static final String SWITCH = "Switch Workspace";

    @TempDir
    Path workspace;

    private ExitFixture fixture;

    @BeforeEach
    void songA() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    /**
     * Ruling 4 Q6.4 item 1: on a pristine Untitled project — where Close
     * Project's shortcut does nothing (the studio engineer's stage-7 N1) —
     * the primitive runs every step, continues once with {@code Closed}, and
     * shows no Welcome window.
     */
    @Test
    void onAPristineUntitledProjectEveryStepRunsAndItContinuesOnceWithNoWelcomeWindow() throws Exception {
        fixture.closeProject();
        fixture.await(() -> fixture.welcomes.get() == 1, "Close Project installed the Untitled project");
        fixture.steps.clear();
        fixture.closeProject();
        fixture.save.settle();
        assertThat(fixture.steps).as("Close Project with nothing to close does nothing").isEmpty();

        CompletableFuture<ExitCoordinator.CloseOutcome> outcome = fixture.closeThenContinue(SWITCH);

        assertThat(outcome.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Closed());
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD DONE", "FLUSH SKIPPED",
                "CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED", "RELEASE DONE");
        assertThat(fixture.steps.getFirst().detail()).as("named by its switch").isEqualTo(SWITCH);
        fixture.save.settle();
        assertThat(fixture.continuations).as("exactly one outcome").hasSize(1);
        assertThat(fixture.welcomes).as("no Welcome window for the primitive").hasValue(1);
        assertThat(fixture.disposals).hasValue(0);
        assertThat(fixture.terminalExits).hasValue(0);
    }

    /** Save and close: one head save, the Untitled project installed without Welcome, then {@code Closed}. */
    @Test
    void saveAndCloseSavesInstallsTheUntitledProjectAndOnlyThenContinuesClosed() throws Exception {
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Save and close";

        CompletableFuture<ExitCoordinator.CloseOutcome> outcome = fixture.closeThenContinue(SWITCH);

        assertThat(outcome.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Closed());
        assertThat(fixture.unsavedPrompts).singleElement().satisfies(prompt -> assertThat(prompt.header())
                .as("ruling R2's prompt").isEqualTo("Save the changes to “Song A” before closing?"));
        assertThat(Files.readString(fixture.songA.resolve("project.daw"), StandardCharsets.UTF_8)).contains("Bass");
        assertThat(fixture.events).containsSubsequence("step FLUSH DONE", "step RELEASE DONE", "continuation Closed");
        assertThat(onFx(() -> fixture.save.project.get().getName())).isEqualTo("Untitled Project");
        assertThat(fixture.stepNames()).noneMatch(name -> name.startsWith("DISPOSE") || name.startsWith("HIDE"));
        fixture.save.settle();
        assertThat(fixture.welcomes).hasValue(0);
        assertThat(fixture.continuations).hasSize(1);
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).doesNotExist();
    }

    /** Ruling R1: the confirmation with Close Project's header; Keep recording gives {@code Cancelled}, nothing ran. */
    @Test
    void aRecordingAsksWithCloseProjectsHeaderAndKeepRecordingCancels() throws Exception {
        ExitFixture.ScriptedTakes takes = new ExitFixture.ScriptedTakes();
        fixture.scriptedTakes = takes;
        runOnFx(takes::record);
        fixture.stopTakeAnswer = "Keep recording";

        CompletableFuture<ExitCoordinator.CloseOutcome> outcome = fixture.closeThenContinue(SWITCH);

        assertThat(outcome.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Cancelled());
        assertThat(fixture.stopTakePrompts).singleElement().satisfies(prompt -> assertThat(prompt.header())
                .isEqualTo("Stop the take and close “Song A”?"));
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertThat(takes.recordingInFlight()).as("still recording").isTrue();
    }

    /** Ruling R2's Cancel, the Veto: {@code Cancelled}, the project open and dirty. */
    @Test
    void theVetoCancelsAndLeavesTheProjectOpenAndDirty() throws Exception {
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Cancel";

        CompletableFuture<ExitCoordinator.CloseOutcome> outcome = fixture.closeThenContinue(SWITCH);

        assertThat(outcome.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Cancelled());
        assertThat(fixture.save.currentProjectIs(fixture.songA)).isTrue();
        assertThat(onFx(() -> fixture.save.project.get().isDirty())).isTrue();
        assertThat(onFx(() -> fixture.coordinator().isWindowInert())).isFalse();
    }

    /**
     * Ruling R8 and ruling 4 Q6.4 item 3: {@code Keep project open}, and a
     * keep after the detach — its ten seconds passed — gives
     * {@code Cancelled} with the WARNING that names the switch.
     */
    @Test
    void aKeepAfterTheDetachCancelsWithTheWarningThatNamesTheSwitch() throws Exception {
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Discard and close";
        SaveFlowFixture.Publishes.Hold closing = fixture.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        CompletableFuture<ExitCoordinator.CloseOutcome> outcome;
        try {
            outcome = fixture.closeThenContinue(SWITCH);
            closing.awaitHeld();
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            ClosingProgressDialog surface = fixture.surface();
            assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Keep project open");
            assertThat(onFx(() -> surface.keepButton().isDisabled())).as("the detach's ten seconds").isTrue();
            fixture.fire(ExitCoordinator.KEEP_AFTER_DETACH_DELAY);
            runOnFx(() -> surface.keepButton().fire());

            assertThat(outcome.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .isEqualTo(new ExitCoordinator.CloseOutcome.Cancelled());
        } finally {
            closing.release();
        }
        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message).contains("Switch Workspace"
                + " cancelled; recovery evidence was preserved. Journaled persistence is paused until this project"
                + " is reopened.");
        fixture.save.settle();
        assertThat(fixture.continuations).hasSize(1);
        fixture.awaitTheJournalEnded();
    }

    /** Ruling R9: a save queued before it that failed gives {@code Failed(cause)}, once. */
    @Test
    void aQueuedSaveThatFailedGivesFailedOnce() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.publishes.failNext(SaveFlowFixture::isHead, new IOException("the volume went away"));
        fixture.edit("Bass");
        fixture.save.save();
        head.awaitHeld();
        CompletableFuture<ExitCoordinator.CloseOutcome> outcome = fixture.closeThenContinue(SWITCH);
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the close waits");
        head.release();

        assertThat(outcome.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOfSatisfying(ExitCoordinator.CloseOutcome.Failed.class, failed ->
                        assertThat(failed.cause()).hasMessageContaining("the volume went away"));
        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD FAILED");
        fixture.save.settle();
        assertThat(fixture.continuations).hasSize(1);
    }

    /**
     * Refused while a door waits for a save: the G2 INFO, and {@code Cancelled}, posted — not delivered inside
     * GUARD's own turn, where the refusal is made (the stage-8 review S6; the app-fix-3 review N2): a check
     * posted from that turn's GUARD CANCELLED, ahead of anything the turn posts after it, finds nothing yet.
     */
    @Test
    void whileADoorWaitsForASaveItIsRefusedAndCancelled() throws Exception {
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        fixture.edit("Bass");
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.promptAnswer = "Save";
        java.util.concurrent.atomic.AtomicInteger deliveredByGuardsTurn =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        fixture.onStep = step -> {
            if (step.step() == ExitCoordinator.ExitStep.GUARD && step.result() == ExitCoordinator.StepResult.CANCELLED
                    && once.compareAndSet(false, true)) {
                com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher.runOnFx(fixture.save.dispatcher,
                        () -> deliveredByGuardsTurn.set(fixture.continuations.size()));
            }
        };
        try {
            runOnFx(() -> {
                fixture.lifecycle().setOpenProjectPrompt(() -> songB);
                fixture.lifecycle().onOpenProject();
            });
            head.awaitHeld();

            CompletableFuture<ExitCoordinator.CloseOutcome> outcome = fixture.closeThenContinue(SWITCH);

            assertThat(outcome.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .isEqualTo(new ExitCoordinator.CloseOutcome.Cancelled());
            fixture.await(() -> deliveredByGuardsTurn.get() >= 0, "the check posted from GUARD's turn");
            assertThat(deliveredByGuardsTurn).as("nothing delivered inside GUARD's turn").hasValue(0);
            assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
            assertThat(fixture.save.notifications()).extracting(NotificationEntry::message)
                    .containsExactly("Still saving — Open Project continues when the save finishes.");
        } finally {
            head.release();
        }
        fixture.await(() -> fixture.save.currentProjectIs(songB), "the door goes on as it was");
    }

    /** Refused while another close runs, and after the protocol ran: {@code Cancelled}, posted, no step. */
    @Test
    void whileAnotherCloseRunsAndOnceTheApplicationClosedItIsCancelled() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Save and close";
        try {
            fixture.closeBox();
            head.awaitHeld();
            List<String> before = fixture.stepNames();

            CompletableFuture<ExitCoordinator.CloseOutcome> whileClosing = fixture.closeThenContinue(SWITCH);

            assertThat(whileClosing.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .isEqualTo(new ExitCoordinator.CloseOutcome.Cancelled());
            assertThat(fixture.stepNames()).isEqualTo(before);
        } finally {
            head.release();
        }
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        CompletableFuture<ExitCoordinator.CloseOutcome> afterTheProtocol = fixture.closeThenContinue(SWITCH);
        assertThat(afterTheProtocol.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Cancelled());
    }

    /**
     * The stage-8 review S6 (design §1.8, "every refusal → Cancelled
     * (posted)"): a refusal while another close runs reaches the
     * continuation on a later FX turn, never inside the calling turn — once.
     */
    @Test
    void aRefusalWhileAnotherCloseRunsIsPostedNeverInsideTheCallingTurn() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.edit("Bass");
        fixture.save.promptAnswer = "Save and close";
        try {
            fixture.closeBox();
            head.awaitHeld();

            assertRefusedOnALaterTurn("while an exit runs");
        } finally {
            head.release();
        }
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
    }

    /** The same once the protocol ran (HIDE): refused, posted. */
    @Test
    void aRefusalAfterTheProtocolRanIsPostedNeverInsideTheCallingTurn() throws Exception {
        fixture.closeBox();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertRefusedOnALaterTurn("after the protocol ran");
    }

    /** The same once the application stopped: refused, posted. */
    @Test
    void aRefusalOnceTheApplicationStoppedIsPostedNeverInsideTheCallingTurn() throws Exception {
        CompletableFuture<Void> stopped = fixture.stopTheApplication();
        ExitFixture.awaitStopped(stopped);

        assertRefusedOnALaterTurn("once the application stopped");
    }

    /**
     * The stage-8 review N5: a primitive's door built directly —
     * {@code request(new CloseThenContinue(name, then))}, not through
     * {@code closeOpenProjectThenContinue} — still hands its continuation
     * exactly one outcome, posted: {@code Closed}, once the Untitled project
     * is installed.
     */
    @Test
    void aPrimitivesDoorBuiltDirectlyHandsItsContinuationOneOutcomePosted() throws Exception {
        List<ExitCoordinator.CloseOutcome> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        runOnFx(() -> {
            fixture.coordinator().request(new ExitCoordinator.CloseDoor.CloseThenContinue(SWITCH, received::add));
            assertThat(received).as("nothing inside the calling turn").isEmpty();
        });

        fixture.await(() -> !received.isEmpty(), "the continuation's outcome");
        fixture.save.settle();
        assertThat(received).containsExactly(new ExitCoordinator.CloseOutcome.Closed());
        assertThat(onFx(() -> fixture.save.project.get().getName())).isEqualTo("Untitled Project");
        assertThat(fixture.welcomes).as("no Welcome window for the primitive").hasValue(0);
    }

    /** The primitive asked in one FX turn: nothing delivered in it; then exactly one {@code Cancelled}. */
    private void assertRefusedOnALaterTurn(String when) throws Exception {
        List<ExitCoordinator.CloseOutcome> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        runOnFx(() -> {
            fixture.coordinator().closeOpenProjectThenContinue(SWITCH, received::add);
            assertThat(received).as("%s: nothing delivered inside the calling turn", when).isEmpty();
        });
        fixture.await(() -> !received.isEmpty(), when + ": the refusal on a later turn");
        runOnFx(() -> { });
        assertThat(received).as(when).containsExactly(new ExitCoordinator.CloseOutcome.Cancelled());
    }

    /**
     * Ruling 4 Q6.4 item 2: an exit that upgraded the primitive, then Don't
     * quit — the run goes on as the primitive it was, and completes with
     * {@code Closed}, once.
     */
    @Test
    void anUpgradeThenDontQuitGoesOnAsThePrimitiveAndContinuesClosedOnce() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.edit("Bass");
        fixture.save.save();
        head.awaitHeld();
        CompletableFuture<ExitCoordinator.CloseOutcome> outcome;
        try {
            outcome = fixture.closeThenContinue(SWITCH);
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the close waits");
            fixture.closeBox();
            fixture.fire(ExitCoordinator.SURFACE_DELAY);
            ClosingProgressDialog surface = fixture.surface();
            assertThat(onFx(() -> surface.keepButton().getText())).as("the upgrade").isEqualTo("Don't quit");
            runOnFx(() -> surface.keepButton().fire());
            assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Keep project open");
        } finally {
            head.release();
        }

        assertThat(outcome.get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Closed());
        fixture.save.settle();
        assertThat(fixture.continuations).hasSize(1);
        assertThat(fixture.stepNames()).noneMatch(name -> name.startsWith("DISPOSE") || name.startsWith("HIDE"));
        assertThat(fixture.welcomes).hasValue(0);
    }

    /** An upgrade whose exit goes on: {@code Cancelled}, once, before HIDE — the switch will not follow. */
    @Test
    void anUpgradeWhoseExitGoesOnContinuesCancelledOnceBeforeHide() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.edit("Bass");
        fixture.save.save();
        head.awaitHeld();
        try {
            fixture.closeThenContinue(SWITCH);
            fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the close waits");
            fixture.closeBox();
        } finally {
            head.release();
        }
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        fixture.save.settle();

        assertThat(fixture.continuations).as("one outcome").containsExactly(new ExitCoordinator.CloseOutcome.Cancelled());
        assertThat(fixture.events).as("delivered once the exit was certain, before HIDE")
                .containsSubsequence("step DISPOSE DONE", "continuation Cancelled", "step HIDE DONE");
        assertThat(fixture.terminalExits).hasValue(1);
    }
}
