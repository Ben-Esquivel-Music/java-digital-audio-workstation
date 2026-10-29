package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Hung-save", the exit half (story 331's ruling
 * M1; the studio engineer's rulings R8, S2, S3, Q4 and the stage-4 S2
 * amendment): with a save held at its write, an exit offers — after ten
 * seconds on the wait clock — a way to quit anyway that says the save may
 * not land; Quit anyway is never the default button nor focused, so Enter
 * quits nothing; pressed, it skips SEAL and RELEASE and leaves the lock, then
 * DISPOSE and HIDE run. A Discard waiting on a held closing checkpoint says
 * its checkpoint and journal are still being written — never "saved". Close
 * Project has no Quit anyway: its keep button is disabled for ten seconds
 * after the journal's detach, then keeps the project open with its
 * journaling paused. (The doors' "Stop waiting" half is
 * {@code DoorStopWaitingTest}'s.)
 */
@ExtendWith(JavaFxToolkitExtension.class)
class HungSaveEscapeTest {

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
        fixture.close();
    }

    @Test
    void anExitWaitingOnAHeldSaveOffersQuitAnywayWhichSkipsSealAndReleaseAndLeavesTheLock() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        head.awaitHeld();
        fixture.closeBox();
        awaitTheClock();

        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::noteText)).as("no note before ten seconds").isEmpty();
        assertThat(onFx(() -> surface.offerButton().isVisible())).isFalse();
        assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Don't quit");

        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(surface::noteText)).isEqualTo("The save hasn't finished after 10 seconds. The disk may not be"
                + " responding. If you quit anyway, the save may not land: project.daw keeps the last save that"
                + " finished, and the next open may offer to recover this session.");
        assertThat(onFx(() -> surface.offerButton().isVisible())).isTrue();
        assertThat(onFx(() -> surface.offerButton().getText())).isEqualTo("Quit anyway");
        assertThat(onFx(() -> surface.offerButton().isDefaultButton())).as("never the default (ruling R8)")
                .isFalse();
        assertThat(onFx(() -> surface.keepButton().isDefaultButton())).isFalse();
        assertThat(onFx(() -> surface.getDialogPane().getScene().getFocusOwner()))
                .as("the keep button has the focus, Quit anyway never").isSameAs(onFx(surface::keepButton));

        runOnFx(() -> surface.offerButton().fire());
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.stepNames()).containsExactly("INTERCEPT DONE", "GUARD SKIPPED", "FLUSH SKIPPED",
                "CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED", "RELEASE SKIPPED", "DISPOSE DONE", "HIDE DONE");
        assertThat(fixture.disposals).hasValue(1);
        assertThat(fixture.terminalExits).hasValue(1);
        head.release();
        fixture.save.settle();
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME))
                .as("Quit anyway leaves the lock, as after a crash — even once the held save ended").exists();
        assertThat(fixture.save.currentProjectIs(fixture.songA)).as("nothing released the folder").isTrue();
    }

    /**
     * The stage-6 review B1: Quit anyway ends GUARD's waits — the held save
     * that reports while DISPOSE still waits for the engine's hand-off
     * decides nothing: no second GUARD, no prompt, the surface still
     * "Stopping audio…"; then exactly one HIDE.
     */
    @Test
    void aSaveThatReportsAfterQuitAnywayDecidesNothingDuringDispose() throws Exception {
        java.util.concurrent.CompletableFuture<Void> engineStopping = new java.util.concurrent.CompletableFuture<>();
        fixture.handOff = engineStopping;
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        head.awaitHeld();
        fixture.closeBox();
        awaitTheClock();
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        runOnFx(() -> fixture.surface().offerButton().fire());
        fixture.awaitStep(ExitCoordinator.ExitStep.DISPOSE);
        java.util.List<String> atDispose = fixture.stepNames();

        head.release();
        fixture.save.settle();
        fixture.save.settle();

        assertThat(fixture.stepNames()).as("no second GUARD, nothing more").isEqualTo(atDispose);
        assertThat(fixture.unsavedPrompts).as("no prompt during DISPOSE").isEmpty();
        assertThat(onFx(() -> fixture.surface().stepText())).isEqualTo("Stopping audio…");
        assertThat(fixture.reached(ExitCoordinator.ExitStep.HIDE)).isFalse();

        engineStopping.complete(null);
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        assertThat(fixture.stepNames()).filteredOn(name -> name.startsWith("HIDE")).hasSize(1);
        assertThat(fixture.terminalExits).hasValue(1);
        fixture.awaitTheJournalEnded();
    }

    @Test
    void enterOnTheSurfaceNeverQuitsAnywayItPressesTheFocusedDontQuit() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        head.awaitHeld();
        fixture.closeBox();
        awaitTheClock();
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(() -> surface.offerButton().isVisible())).isTrue();

        runOnFx(() -> {
            Node focused = surface.getDialogPane().getScene().getFocusOwner();
            focused.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            focused.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.ENTER, false, false, false, false));
        });

        assertThat(fixture.stepNames()).as("Enter on the focused keep button is Don't quit, never Quit anyway")
                .containsExactly("INTERCEPT DONE", "GUARD CANCELLED");
        assertThat(fixture.terminalExits).hasValue(0);
        assertThat(onFx(() -> fixture.stage().isShowing())).isTrue();
        head.release();
        fixture.save.settle();
        assertThat(fixture.save.notifications()).as("the save went on with its usual outcome: no ERROR").isEmpty();
        assertThat(onFx(() -> fixture.save.project.get().isDirty())).isFalse();
    }

    @Test
    void aDiscardWaitingOnAHeldClosingCheckpointSaysItsCheckpointAndJournalAreStillBeingWritten()
            throws Exception {
        SaveFlowFixture.Publishes.Hold closing = fixture.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        fixture.save.promptAnswer = "Discard and close";
        fixture.closeBox();
        closing.awaitHeld();
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);

        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::stepText)).isEqualTo("Writing checkpoint…");
        assertThat(onFx(surface::noteText)).as("the studio engineer's S2: never the 'saved' text after a Discard")
                .isEqualTo("The project's checkpoint and journal are still being written after 10 seconds. The disk"
                        + " may not be responding. If you quit anyway, the next open may offer to recover this"
                        + " session.");
        assertThat(onFx(() -> surface.offerButton().getText())).isEqualTo("Quit anyway");
        closing.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
    }

    @Test
    void closeProjectHasNoQuitAnywayAndItsKeepButtonWaitsTenSecondsAfterTheDetach() throws Exception {
        SaveFlowFixture.Publishes.Hold closing = fixture.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        fixture.save.promptAnswer = "Discard and close";
        fixture.closeProject();
        closing.awaitHeld();
        fixture.fire(ExitCoordinator.SURFACE_DELAY);

        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Keep project open");
        assertThat(onFx(() -> surface.keepButton().isDisabled())).as("Q4: disabled from the detach on").isTrue();
        runOnFx(() -> surface.keepButton().fire());
        assertThat(onFx(surface::isOpen)).as("a disabled keep does nothing").isTrue();

        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(surface::noteText)).isEqualTo("The project's checkpoint and journal are still being written"
                + " after 10 seconds. The disk may not be responding. The project closes once they have been"
                + " written; choose Keep project open to keep working.");
        assertThat(onFx(() -> surface.offerButton().isVisible())).as("Close Project has no Quit anyway").isFalse();
        assertThat(onFx(() -> surface.keepButton().isDisabled()))
                .as("the ten seconds after the detach were the same ten seconds: one fire of 10 s covers both")
                .isFalse();

        runOnFx(() -> surface.keepButton().fire());
        fixture.await(() -> !fixture.surface().isOpen(), "the close ended");

        assertThat(fixture.save.notifications()).extracting(NotificationEntry::message).contains(
                "Close Project cancelled; recovery evidence was preserved. Journaled persistence is paused until"
                        + " this project is reopened.");
        assertThat(fixture.stepNames()).contains("CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED", "RELEASE SKIPPED")
                .doesNotContain("HIDE DONE");
        assertThat(onFx(() -> fixture.save.project.get().getName())).as("the project stays open").isEqualTo("Song A");
        assertThat(fixture.save.currentProjectIs(fixture.songA)).isTrue();
        fixture.await(() -> !fixture.journal.isOpen(), "the journal drained unsealed: recovery evidence preserved");
        assertThat(fixture.journal.closedUnsealed(fixture.songA)).isTrue();
        closing.release();
        fixture.save.settle();
        assertThat(com.benesquivelmusic.daw.core.persistence.journal.JournalState.records(
                com.benesquivelmusic.daw.core.persistence.journal.ProjectContext.forProject(fixture.songA)))
                .as("never sealed").noneMatch(com.benesquivelmusic.daw.core.persistence.journal.JournalRecord::isSessionSeal);
    }

    @Test
    void theClockStartsOverAfterThePromptSoQuitAnywayIsNotShownRightAfterTheAnswer() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.promptAnswer = "Save and close";
        fixture.closeBox();
        head.awaitHeld();
        assertThat(fixture.timers.armed()).as("the GUARD's clock was cancelled by the prompt")
                .filteredOn(timer -> timer.delay().equals(ExitCoordinator.NOTE_DELAY)).hasSize(2)
                .first().satisfies(timer -> assertThat(timer.cancelled()).isTrue());
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        assertThat(onFx(() -> fixture.surface().stepText())).isEqualTo("Saving project…");
        assertThat(onFx(() -> fixture.surface().offerButton().isVisible()))
                .as("the studio engineer's S3: time spent thinking is not disk time").isFalse();
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(() -> fixture.surface().offerButton().isVisible())).isTrue();
        head.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
    }

    /**
     * The studio engineer's stage-6 S2: once the keep button cannot hold the
     * focus — disabled after the detach — Quit anyway never takes it, so
     * Enter on the surface quits nothing. The detach comes after the clock
     * started, so the note and Quit anyway show while the keep button is
     * still disabled.
     */
    @Test
    void enterWhileTheKeepButtonIsDisabledAfterTheDetachQuitsNothing() throws Exception {
        SaveFlowFixture.Publishes.Hold closing = aCloseWhoseDetachComesAfterItsClockStarted(true);
        ClosingProgressDialog surface = fixture.surface();
        fireTheClocksNoteAlone();
        assertThat(onFx(() -> surface.offerButton().isVisible())).as("fixture: Quit anyway is offered").isTrue();
        assertThat(onFx(() -> surface.keepButton().isDisabled())).as("fixture: inside the detach window").isTrue();
        awaitPulses(3);

        javafx.scene.Node owner = onFx(() -> surface.getDialogPane().getScene().getFocusOwner());
        assertThat(owner).as("never Quit anyway").isNotSameAs(onFx(surface::offerButton));
        runOnFx(() -> {
            Node focused = surface.getDialogPane().getScene().getFocusOwner();
            Node target = focused != null ? focused : surface.getDialogPane();
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.ENTER, false, false, false, false));
        });

        assertThat(fixture.stepNames()).as("Enter ran no Quit anyway").noneMatch(name -> name.startsWith("DISPOSE"));
        assertThat(fixture.terminalExits).hasValue(0);
        closing.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
    }

    /**
     * The studio engineer's stage-6 S3: a Close Project note offers the keep
     * button, so it shows only while that button can be pressed — the wait
     * clock's ten seconds alone, inside the detach window, show no note; the
     * detach's ten seconds then show the note and the enabled button
     * together.
     */
    @Test
    void closeProjectsNoteWaitsForTheKeepButtonItNames() throws Exception {
        SaveFlowFixture.Publishes.Hold closing = aCloseWhoseDetachComesAfterItsClockStarted(false);
        ClosingProgressDialog surface = fixture.surface();

        fireTheClocksNoteAlone();
        assertThat(onFx(surface::noteText)).as("no note naming a disabled Keep project open").isEmpty();
        assertThat(onFx(() -> surface.keepButton().isDisabled())).isTrue();

        fixture.fire(ExitCoordinator.KEEP_AFTER_DETACH_DELAY);
        assertThat(onFx(surface::noteText)).isEqualTo("The project's checkpoint and journal are still being written"
                + " after 10 seconds. The disk may not be responding. The project closes once they have been"
                + " written; choose Keep project open to keep working.");
        assertThat(onFx(() -> surface.keepButton().isDisabled())).isFalse();
        closing.release();
        fixture.await(() -> fixture.welcomes.get() == 1, "Close Project ended");
    }

    /**
     * A Discard whose detach comes after its wait clock started: a checkpoint
     * of the open generation is held before the close — so the closer's first
     * step waits on it — then released once the clock runs; the closing
     * checkpoint that follows the detach is held, and returned. The surface
     * is shown.
     */
    private SaveFlowFixture.Publishes.Hold aCloseWhoseDetachComesAfterItsClockStarted(boolean exit)
            throws Exception {
        SaveFlowFixture.Publishes.Hold generation = fixture.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        runOnFx(() -> fixture.save.snapshots().createCheckpointWithLabel("Held before the close"));
        generation.awaitHeld();
        fixture.edit("After the held checkpoint");
        fixture.save.promptAnswer = "Discard and close";
        if (exit) {
            fixture.closeBox();
        } else {
            fixture.closeProject();
        }
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.FLUSH)
                && !fixture.timers.pending(ExitCoordinator.NOTE_DELAY).isEmpty(), "the tail's wait clock");
        SaveFlowFixture.Publishes.Hold closing = fixture.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        generation.release();
        closing.awaitHeld();
        fixture.await(() -> fixture.timers.pending(ExitCoordinator.KEEP_AFTER_DETACH_DELAY).size() == 2,
                "the detach's clock beside the wait clock's note");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        assertThat(onFx(() -> fixture.surface().isOpen())).isTrue();
        return closing;
    }

    /** Fires the wait clock's ten-second note alone: the first pending ten-second task, armed before the detach's. */
    private void fireTheClocksNoteAlone() throws Exception {
        runOnFx(() -> fixture.timers.fire(fixture.timers.pending(ExitCoordinator.NOTE_DELAY).getFirst()));
    }

    /** Waits, bounded, until the toolkit ran {@code pulses} more pulses (an animation timer counts them). */
    private static void awaitPulses(int pulses) throws Exception {
        java.util.concurrent.CountDownLatch counted = new java.util.concurrent.CountDownLatch(pulses);
        javafx.animation.AnimationTimer timer = onFx(() -> {
            javafx.animation.AnimationTimer counting = new javafx.animation.AnimationTimer() {
                @Override
                public void handle(long now) {
                    counted.countDown();
                }
            };
            counting.start();
            return counting;
        });
        try {
            assertThat(counted.await(ExitFixture.GUARD.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("%d pulses within %s", pulses, ExitFixture.GUARD).isTrue();
        } finally {
            runOnFx(timer::stop);
        }
    }

    private void awaitTheClock() throws Exception {
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
    }
}
