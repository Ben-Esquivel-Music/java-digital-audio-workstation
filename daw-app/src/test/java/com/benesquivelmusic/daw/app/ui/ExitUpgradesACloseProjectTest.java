package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;

import javafx.stage.WindowEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, the studio engineer's ruling Q8 (design §1.4) and ruling R10:
 * an exit asked while Close Project runs. Before the release, the close
 * becomes an exit — its keep button reads "Don't quit" — and Don't quit
 * cancels only the exit: the close goes on as the Close Project it was and
 * the window stays. After the release, Close Project installs the Untitled
 * project without the Welcome window, and the exit then runs once on it,
 * asking nothing. A Close Project asked while a close runs is refused.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ExitUpgradesACloseProjectTest {

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
    void anExitWhileCloseProjectWaitsUpgradesItAndDontQuitCancelsOnlyTheExit() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.save.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save.save();
        head.awaitHeld();
        CompletionStage<ExitCoordinator.CloseOutcome> closed = fixture.closeProject();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Keep project open");

        CompletionStage<ExitCoordinator.CloseOutcome> exit = fixture.fileExit();
        assertThat(onFx(() -> surface.keepButton().getText())).as("the close became an exit").isEqualTo("Don't quit");
        assertThat(exit).as("the exit is the running close").isSameAs(closed);

        runOnFx(() -> surface.keepButton().fire());
        assertThat(onFx(() -> surface.keepButton().getText())).as("Don't quit cancels only the exit")
                .isEqualTo("Keep project open");
        assertThat(onFx(surface::isOpen)).isTrue();

        CompletionStage<ExitCoordinator.CloseOutcome> refused = fixture.closeProject();
        assertThat(refused.toCompletableFuture().getNow(null)).as("a second Close Project is refused")
                .isEqualTo(new ExitCoordinator.CloseOutcome.Cancelled());

        head.release();
        assertThat(closed.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Closed());
        assertThat(onFx(() -> fixture.save.project.get().getName())).isEqualTo("Untitled Project");
        // Ruling 4 Q3, the stage-7 review N9: the Welcome window is posted once the run has ended.
        fixture.await(() -> fixture.welcomes.get() == 1, "Close Project's Welcome window");
        assertThat(fixture.reached(ExitCoordinator.ExitStep.HIDE)).isFalse();
        assertThat(fixture.terminalExits).hasValue(0);
        assertThat(onFx(() -> fixture.stage().isShowing())).isTrue();
    }

    @Test
    void anExitAfterCloseProjectReleasedTheFolderRunsOnceOnTheUntitledProjectWithoutTheWelcomeWindow()
            throws Exception {
        fixture.save.promptAnswer = "Discard and close";
        AtomicBoolean asked = new AtomicBoolean();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.RELEASE && asked.compareAndSet(false, true)) {
                fixture.stage().fireEvent(new WindowEvent(fixture.stage(), WindowEvent.WINDOW_CLOSE_REQUEST));
            }
        };

        fixture.closeProject();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.welcomes).as("Untitled is installed without the Welcome window").hasValue(0);
        assertThat(fixture.unsavedPrompts).as("the exit on the pristine Untitled asks nothing").hasSize(1);
        assertThat(fixture.stepNames()).as("Close Project's steps, then the exit's, once")
                .containsSubsequence("INTERCEPT DONE", "RELEASE DONE", "INTERCEPT DONE", "GUARD DONE", "HIDE DONE");
        assertThat(fixture.stepNames()).filteredOn(name -> name.startsWith("HIDE")).hasSize(1);
        assertThat(fixture.terminalExits).hasValue(1);
        assertThat(fixture.disposals).hasValue(1);
    }

    /**
     * Ruling 4 Q2 (i), the stage-6 review S2 and N10: an upgraded Close
     * Project closes its folder with Close Project's own plan, so its release
     * waits for nothing; the exit it added waits for every writer after it
     * (D11), under the surface — "Finishing disk writes…", Don't quit enabled,
     * at ten seconds {@code close.note.release} with Quit anyway. Don't quit
     * there drops only the exit: the Untitled project and the Welcome window,
     * at once, while the earlier folder's write is still held, and nothing
     * more runs once it ends.
     */
    @Test
    void dontQuitWhileTheUpgradedExitWaitsForEveryWriterEndsItAsTheCloseProjectItWas() throws Exception {
        SaveFlowFixture.Publishes.Hold heldA = anUpgradedCloseProjectOfSongBWaitingOnSongAsWrite();
        try {
            ClosingProgressDialog surface = theSurfaceOfTheExitsWaitAtTenSeconds();

            runOnFx(() -> surface.keepButton().fire());
            fixture.await(() -> fixture.welcomes.get() == 1, "the Untitled project with the Welcome window");

            assertThat(heldA.isHeld()).as("while Song A's write is still held").isTrue();
            assertThat(onFx(() -> fixture.save.project.get().getName())).isEqualTo("Untitled Project");
            assertThat(onFx(() -> fixture.coordinator().isWindowInert())).as("the fence is lowered").isFalse();
            assertThat(fixture.stepNames()).noneMatch(name -> name.startsWith("DISPOSE") || name.startsWith("HIDE"));
        } finally {
            heldA.release();
        }
        fixture.save.projectManager.whenRetiredLeasesReleased().get(ExitFixture.GUARD.toMillis(),
                TimeUnit.MILLISECONDS);
        fixture.save.settle();
        assertThat(fixture.stepNames()).as("once the write ended, nothing more ran")
                .noneMatch(name -> name.startsWith("DISPOSE") || name.startsWith("HIDE"));
        assertThat(fixture.terminalExits).hasValue(0);
        assertThat(fixture.disposals).hasValue(0);
    }

    /** Ruling 4 Q2 (ii): Quit anyway in the exit's wait for every writer: DISPOSE and HIDE while it is held. */
    @Test
    void quitAnywayWhileTheUpgradedExitWaitsForEveryWriterDisposesAndHides() throws Exception {
        SaveFlowFixture.Publishes.Hold heldA = anUpgradedCloseProjectOfSongBWaitingOnSongAsWrite();
        try {
            ClosingProgressDialog surface = theSurfaceOfTheExitsWaitAtTenSeconds();

            runOnFx(() -> surface.offerButton().fire());
            fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

            assertThat(heldA.isHeld()).as("Song A's write is still held").isTrue();
            assertThat(fixture.stepNames()).containsSubsequence("RELEASE DONE", "DISPOSE DONE", "HIDE DONE");
            assertThat(fixture.stepNames()).as("RELEASE was published DONE by the closer: nothing to mark SKIPPED")
                    .filteredOn(name -> name.startsWith("RELEASE")).containsExactly("RELEASE DONE");
            assertThat(fixture.terminalExits).hasValue(1);
        } finally {
            heldA.release();
        }
    }

    /**
     * Ruling 4 Q2 (iii), the stage-6 review N10: once the earlier folder's
     * write ended, DISPOSE comes after the retiring leases were released,
     * then HIDE.
     */
    @Test
    void theUpgradedExitDisposesOnlyOnceEveryWriterIsIdleAndTheRetiringLeasesAreReleased() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Boolean> songALockedAtDispose =
                new java.util.concurrent.atomic.AtomicReference<>();
        fixture.onStep = outcome -> {
            if (outcome.step() == ExitCoordinator.ExitStep.DISPOSE) {
                songALockedAtDispose.set(java.nio.file.Files.exists(
                        fixture.songA.resolve(com.benesquivelmusic.daw.core.persistence.ProjectLockManager.LOCK_FILE_NAME)));
            }
        };
        SaveFlowFixture.Publishes.Hold heldA = anUpgradedCloseProjectOfSongBWaitingOnSongAsWrite();
        assertThat(fixture.reached(ExitCoordinator.ExitStep.DISPOSE)).as("no DISPOSE while A's write is held").isFalse();

        heldA.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(songALockedAtDispose.get()).as("DISPOSE came after Song A's retiring lease was released").isFalse();
        assertThat(fixture.stepNames()).containsSubsequence("RELEASE DONE", "DISPOSE DONE", "HIDE DONE");
        assertThat(fixture.terminalExits).hasValue(1);
    }

    /**
     * Ruling 4 Q2 (iv): Don't quit inside the detach window — the closing
     * checkpoint of a Discard held — is enabled (it drops only the exit); the
     * close goes on as Close Project, its keep back to "Keep project open",
     * disabled until ten seconds after the detach; then the Untitled project
     * and the Welcome window.
     */
    @Test
    void dontQuitInTheDetachWindowIsEnabledAndTheCloseGoesOnAsCloseProject() throws Exception {
        SaveFlowFixture.Publishes.Hold closing = fixture.save.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        fixture.save.promptAnswer = "Discard and close";
        fixture.closeProject();
        closing.awaitHeld();
        fixture.fileExit();
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Don't quit");
        assertThat(onFx(() -> surface.keepButton().isDisabled())).as("Don't quit costs nothing: enabled").isFalse();

        runOnFx(() -> surface.keepButton().fire());
        assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Keep project open");
        assertThat(onFx(() -> surface.keepButton().isDisabled())).as("Close Project's own rule: the detach window")
                .isTrue();
        fixture.fire(ExitCoordinator.KEEP_AFTER_DETACH_DELAY);
        assertThat(onFx(() -> surface.keepButton().isDisabled())).isFalse();

        closing.release();
        fixture.await(() -> fixture.welcomes.get() == 1, "the Untitled project with the Welcome window");
        assertThat(onFx(() -> fixture.save.project.get().getName())).isEqualTo("Untitled Project");
        assertThat(fixture.stepNames()).noneMatch(name -> name.startsWith("DISPOSE") || name.startsWith("HIDE"));
    }

    /**
     * Song A's checkpoint write held; File ▸ Open of Song B; B edited and its
     * Ctrl+S held; File ▸ Close Project, then File ▸ Exit — the upgrade —
     * then the save released: B's close ends at Close Project's own release,
     * and the exit's wait for every writer holds on A's write (the technique
     * of {@code CheckpointWritersPerFolderAcrossASwitchTest}).
     */
    private SaveFlowFixture.Publishes.Hold anUpgradedCloseProjectOfSongBWaitingOnSongAsWrite() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        long songAGeneration = fixture.save.checkpoints.runningGeneration().orElseThrow();
        fixture.save.awaitCheckpointsSeeded(songAGeneration - 1);
        SaveFlowFixture.Publishes.Hold heldA = fixture.save.publishes.holdNext(
                target -> SaveFlowFixture.isCheckpoint(target) && target.startsWith(fixture.songA));
        runOnFx(() -> fixture.save.snapshots().createCheckpointWithLabel("Held in Song A"));
        heldA.awaitHeld();
        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> songB);
            fixture.lifecycle().onOpenProject();
        });
        fixture.await(() -> fixture.save.currentProjectIs(songB)
                && !fixture.lifecycle().isProjectInstallationPending(), "Song B installed");
        fixture.save.awaitCheckpointsSeeded(songAGeneration);

        fixture.edit("Keys");
        SaveFlowFixture.Publishes.Hold headB = fixture.save.publishes.holdNext(
                target -> SaveFlowFixture.isHead(target) && target.startsWith(songB));
        fixture.save.save();
        headB.awaitHeld();
        fixture.closeProject();
        fixture.await(() -> !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the wait clock");
        fixture.fileExit();
        headB.release();
        fixture.await(() -> fixture.stepNames().contains("RELEASE DONE"), "Close Project's own release");
        assertThat(heldA.isHeld()).as("fixture: Song A's write is still held").isTrue();
        assertThat(fixture.save.currentProjectIs(songB)).as("Song B's folder was released").isFalse();
        return heldA;
    }

    /** The surface of the exit's wait for every writer, fired to its ten seconds. */
    private ClosingProgressDialog theSurfaceOfTheExitsWaitAtTenSeconds() throws Exception {
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::stepText)).isEqualTo("Finishing disk writes…");
        assertThat(onFx(() -> surface.getTitle())).isEqualTo("Closing “Song B”");
        assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Don't quit");
        assertThat(onFx(() -> surface.keepButton().isVisible() && !surface.keepButton().isDisabled()))
                .as("shown and enabled, though the release began").isTrue();
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(surface::noteText)).isEqualTo("“Song B” has nothing left to write, but this session is still"
                + " finishing other disk writes after 10 seconds. The disk may not be responding. If you quit"
                + " anyway, the application ends without waiting for them: a project's lock may be left behind, as"
                + " after a crash, and your saved projects are not affected.");
        assertThat(onFx(() -> surface.offerButton().isVisible() && surface.offerButton().getText()
                .equals("Quit anyway"))).isTrue();
        return surface;
    }
}
