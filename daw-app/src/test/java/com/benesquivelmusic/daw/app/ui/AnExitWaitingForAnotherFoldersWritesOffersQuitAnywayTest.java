package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.InstantSource;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, rulings R8 and B3 ("every wait has a way out"), the stage-6
 * reviews' B1/B2 and ruling 4 Q1: an exit whose own project has nothing left
 * to write still waits, at RELEASE, for this session's other disk writes —
 * a checkpoint write held in a folder closed earlier (the technique of
 * {@code CheckpointWritersPerFolderAcrossASwitchTest}), whose retiring lease
 * waits for it. That wait runs under the surface's clock: at half a second
 * "Closing “…”" with "Finishing disk writes…" and an enabled Don't quit — a
 * plan with no journal end has no detach window (ruling 4 Q1, refinement 1);
 * at ten seconds {@code close.note.release} and Quit anyway, which ends the
 * application without waiting, the earlier folder's lock left behind as
 * after a crash. Don't quit keeps the window, with no WARNING: nothing was
 * paused. The rows: the Untitled project after a Close Project, a read-only
 * open after a Discard — nothing written in its folder — and a Save and close
 * whose RELEASE shows the release note, not the "saved" one.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class AnExitWaitingForAnotherFoldersWritesOffersQuitAnywayTest {

    private static final String UNTITLED_NOTE = "“Untitled Project” has nothing left to write, but this session is"
            + " still finishing other disk writes after 10 seconds. The disk may not be responding. If you quit"
            + " anyway, the application ends without waiting for them: a project's lock may be left behind, as after"
            + " a crash, and your saved projects are not affected.";

    @TempDir
    Path workspace;

    private ExitFixture fixture;
    private SaveFlowFixture.Publishes.Hold heldA;
    private long songAGeneration;

    @BeforeEach
    void songAsCheckpointWriteHeld() throws Exception {
        fixture = new ExitFixture(workspace, AutoSaveConfig.DEFAULT);
        songAGeneration = fixture.save.checkpoints.runningGeneration().orElseThrow();
        fixture.save.awaitCheckpointsSeeded(songAGeneration - 1);
        heldA = fixture.save.publishes.holdNext(
                target -> SaveFlowFixture.isCheckpoint(target) && target.startsWith(fixture.songA));
        runOnFx(() -> fixture.save.snapshots().createCheckpointWithLabel("Held in Song A"));
        heldA.awaitHeld();
    }

    @AfterEach
    void close() throws Exception {
        heldA.release();
        fixture.close();
    }

    @Test
    void quitAnywayOnTheUntitledProjectEndsTheApplicationWithoutWaitingAndLeavesTheEarlierLock() throws Exception {
        theUntitledProjectAfterACloseProjectOfSongB();

        fixture.closeBox();
        ClosingProgressDialog surface = theSurfaceAtHalfASecond("Closing “Untitled Project”");
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        assertThat(onFx(surface::noteText)).isEqualTo(UNTITLED_NOTE);
        assertThat(onFx(() -> surface.offerButton().isVisible() && surface.offerButton().getText()
                .equals("Quit anyway"))).isTrue();

        runOnFx(() -> surface.offerButton().fire());
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);

        assertThat(fixture.stepNames()).containsSubsequence("RELEASE SKIPPED", "DISPOSE DONE", "HIDE DONE");
        assertThat(heldA.isHeld()).isTrue();
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME))
                .as("Song A's lock is left behind while its write is held, as after a crash").exists();
        assertThat(fixture.terminalExits).hasValue(1);
    }

    @Test
    void dontQuitOnTheUntitledProjectKeepsTheWindowWithNoWarning() throws Exception {
        theUntitledProjectAfterACloseProjectOfSongB();

        CompletionStage<ExitCoordinator.CloseOutcome> outcome = onFx(() -> fixture.coordinator()
                .request(new ExitCoordinator.CloseDoor.WindowClose()));
        ClosingProgressDialog surface = theSurfaceAtHalfASecond("Closing “Untitled Project”");
        runOnFx(() -> surface.keepButton().fire());

        assertThat(outcome.toCompletableFuture().get(ExitFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(new ExitCoordinator.CloseOutcome.Cancelled());
        assertThat(onFx(() -> fixture.save.project.get().getName())).isEqualTo("Untitled Project");
        assertThat(onFx(() -> fixture.coordinator().isWindowInert())).as("the window is live").isFalse();
        assertThat(onFx(() -> fixture.stage().isShowing())).isTrue();
        assertThat(fixture.save.notifications()).as("nothing was journaled or paused: no WARNING")
                .noneMatch(entry -> entry.level() == NotificationLevel.WARNING
                        || entry.level() == NotificationLevel.ERROR);
        assertThat(fixture.terminalExits).hasValue(0);
    }

    @Test
    void aReadOnlyOpenDiscardedWaitsTheSameAndWritesNothingInItsFolder() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        Path songC = fixture.save.anotherProjectOnDisk("Song C");
        new ProjectLockManager(InstantSource.system(), "bob", "Mix-Room", 7L).forceAcquire(songC);
        fixture.save.projectManager.setLockConflictHandler((lock, stale) -> LockConflictResolution.OPEN_READ_ONLY);
        openThroughTheDoor(songC);
        fixture.await(() -> fixture.save.currentProjectIs(songC)
                && !fixture.lifecycle().isProjectInstallationPending(), "Song C open read-only");
        assertThat(fixture.save.projectManager.isReadOnly()).as("fixture: read-only").isTrue();
        fixture.edit("Sketch");
        Map<String, FileTime> original = listing(songC);
        String bobsLock = Files.readString(songC.resolve(ProjectLockManager.LOCK_FILE_NAME));
        fixture.save.promptAnswer = "Discard and close";

        fixture.closeBox();
        ClosingProgressDialog surface = theSurfaceAtHalfASecond("Closing “Song C”");
        fixture.fire(ExitCoordinator.NOTE_DELAY);

        assertThat(onFx(surface::noteText)).isEqualTo(UNTITLED_NOTE.replace("Untitled Project", "Song C"));
        heldA.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
        assertThat(listing(songC)).as("nothing written in the read-only folder (the studio engineer's stage-6 N1)")
                .isEqualTo(original);
        assertThat(Files.readString(songC.resolve(ProjectLockManager.LOCK_FILE_NAME))).isEqualTo(bobsLock);
    }

    /**
     * Ruling 4 Q1's normal row: Save and close of Song B while Song A's write
     * is held — at RELEASE nothing of B is left to write, so the note is the
     * release's, never "The project is saved, but its checkpoint and journal
     * are still being written".
     */
    @Test
    void aSaveAndCloseWaitingAtReleaseShowsTheReleaseNoteNotTheSavedOne() throws Exception {
        Path songB = songBOpenedThroughTheDoor();
        fixture.edit("Keys");
        fixture.save.promptAnswer = "Save and close";

        fixture.closeBox();
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.SEAL)
                && fixture.stepNames().contains("FLUSH DONE"), "B saved and sealed");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        fixture.fire(ExitCoordinator.NOTE_DELAY);
        ClosingProgressDialog surface = fixture.surface();

        assertThat(onFx(surface::stepText)).isEqualTo("Finishing disk writes…");
        assertThat(onFx(surface::noteText)).isEqualTo(UNTITLED_NOTE.replace("Untitled Project", "Song B"));
        assertThat(fixture.save.currentProjectIs(songB)).as("fixture: B's release waits for every writer").isTrue();
        heldA.release();
        fixture.awaitStep(ExitCoordinator.ExitStep.HIDE);
    }

    /** File ▸ Open of Song B, then File ▸ Close Project of B: the pristine Untitled project, A's write held. */
    private void theUntitledProjectAfterACloseProjectOfSongB() throws Exception {
        songBOpenedThroughTheDoor();
        fixture.closeProject();
        fixture.await(() -> fixture.welcomes.get() == 1, "Close Project of Song B");
        assertThat(heldA.isHeld()).as("fixture: Song A's write is still held").isTrue();
        assertThat(onFx(() -> fixture.coordinator().closeProjectAvailable())).as("fixture: pristine").isFalse();
        fixture.steps.clear();
    }

    private Path songBOpenedThroughTheDoor() throws Exception {
        runOnFx(() -> fixture.save.project.get().markClean());
        Path songB = fixture.save.anotherProjectOnDisk("Song B");
        openThroughTheDoor(songB);
        fixture.await(() -> fixture.save.currentProjectIs(songB)
                && !fixture.lifecycle().isProjectInstallationPending(), "Song B installed");
        fixture.save.awaitCheckpointsSeeded(songAGeneration);
        return songB;
    }

    /**
     * The surface at half a second: titled {@code title}, the release's step,
     * Don't quit shown and enabled — the plan has no journal end, so no
     * detach window (ruling 4 Q1, refinement 1) — and no note yet.
     */
    private ClosingProgressDialog theSurfaceAtHalfASecond(String title) throws Exception {
        fixture.await(() -> fixture.reached(ExitCoordinator.ExitStep.SEAL)
                && !fixture.timers.pending(ExitCoordinator.SURFACE_DELAY).isEmpty(), "the release's wait clock");
        fixture.fire(ExitCoordinator.SURFACE_DELAY);
        ClosingProgressDialog surface = fixture.surface();
        assertThat(onFx(surface::isOpen)).as("the tail runs under the surface's clock (B1/B2)").isTrue();
        assertThat(onFx(surface::getTitle)).isEqualTo(title);
        assertThat(onFx(surface::stepText)).isEqualTo("Finishing disk writes…");
        assertThat(onFx(() -> surface.keepButton().getText())).isEqualTo("Don't quit");
        assertThat(onFx(() -> surface.keepButton().isVisible() && !surface.keepButton().isDisabled()))
                .as("Don't quit shown and enabled").isTrue();
        assertThat(onFx(surface::noteText)).isEmpty();
        return surface;
    }

    private void openThroughTheDoor(Path folder) throws Exception {
        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> folder);
            fixture.lifecycle().onOpenProject();
        });
    }

    private static Map<String, FileTime> listing(Path folder) throws Exception {
        Map<String, FileTime> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path entry : walk.toList()) {
                entries.put(folder.relativize(entry).toString(), Files.getLastModifiedTime(entry));
            }
        }
        return entries;
    }
}
