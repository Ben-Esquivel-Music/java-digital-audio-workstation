package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.LockConflictResolution;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Goals — Tests, "Per-folder writer" (the application's half;
 * design §4.1): each project folder has a checkpoint writer of its own, so a
 * checkpoint write held in Song A — a folder on a volume that stopped
 * answering — holds nothing of Song B. With the recovery gate on, as the
 * application installs it, a File ▸ Open of Song B while A's write is held
 * returns its FX steps at once, installs B, and B's checkpoints are seeded
 * and armed; a Ctrl+S in B writes B's head. Song A's lock stays while the
 * write it owes is held (story 331's lock hand-off). A same-path reopen of A
 * meanwhile shows no lock-conflict prompt: this session's own retiring lock
 * is taken over silently, and the obligation moves with it — closing A again
 * keeps A's lock until the held write has ended. The reopen's recovery gate
 * removes A's checkpoint temps, the held write's own temp among them, so the
 * write fails once released: its generation had ended, and it says so;
 * nothing is lost (design FEAS-N5).
 *
 * <p>File ▸ Close Project of Song B through the real door — the exit
 * protocol, as the application wires it — while A's write is held completes
 * as well: the Untitled project is installed, B's lock is released after B's
 * own work, and A's lock still waits for the write A owes, released only once
 * that write ended (design FEAS-B6, COV-S-1).</p>
 *
 * <p>The write is held at its publish through the fixture's mover, never by a
 * sleep.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CheckpointWritersPerFolderAcrossASwitchTest {

    @TempDir
    Path workspace;

    @Test
    void aWriteHeldInSongAHoldsNoneOfSongBAndAReopenOfATakesItsOwnRetiringLockOver() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            Path songA = fixture.songA;
            Path songB = fixture.anotherProjectOnDisk("Song B");
            List<ProjectLock> conflictsAsked = new CopyOnWriteArrayList<>();
            fixture.projectManager.setLockConflictHandler((existing, stale) -> {
                conflictsAsked.add(existing);
                return LockConflictResolution.CANCEL;
            });
            runOnFx(() -> fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> { }, _ -> { }));
            long songAGeneration = fixture.checkpoints.runningGeneration().orElseThrow();
            fixture.awaitCheckpointsSeeded(songAGeneration - 1);

            SaveFlowFixture.Publishes.Hold held = fixture.publishes.holdNext(
                    target -> SaveFlowFixture.isCheckpoint(target) && target.startsWith(songA));
            try {
                runOnFx(() -> fixture.snapshots().createCheckpointWithLabel("Held in Song A"));
                Thread writer = held.awaitHeld();
                assertThat(writer.getName()).as("fixture: Song A's checkpoint writer holds it")
                        .startsWith("daw-checkpoint-writer");

                // File ▸ Open of Song B: its FX steps return although Song A's write is held.
                long switchStarted = System.nanoTime();
                openThroughTheDoor(fixture, songB);
                assertThat(System.nanoTime() - switchStarted)
                        .as("the switch's FX steps returned while Song A's write is held")
                        .isLessThan(TimeUnit.SECONDS.toNanos(5));
                fixture.awaitOnFx(() -> fixture.currentProjectIs(songB)
                        && !fixture.lifecycle().isProjectInstallationPending(), "Song B installed");
                fixture.awaitCheckpointsSeeded(songAGeneration);
                long songBGeneration = fixture.checkpoints.runningGeneration().orElseThrow();
                fixture.awaitOnFx(() -> fixture.checkpointEvents.stream().anyMatch(event ->
                        event instanceof CheckpointEvent.Rearmed rearmed && rearmed.generation() == songBGeneration
                                && rearmed.reason() == CheckpointEvent.RearmReason.STARTED),
                        "Song B's checkpoints armed");
                assertThat(held.isHeld()).as("fixture: Song A's write is still held").isTrue();
                assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME))
                        .as("Song A's lock stays while the write it owes is held").exists();

                // Ctrl+S in Song B writes Song B's head.
                runOnFx(() -> fixture.project.get().markDirty());
                fixture.save();
                fixture.settle();
                assertThat(fixture.publishes.done(target -> SaveFlowFixture.isHead(target)
                        && target.startsWith(songB))).as("Song B's head was written").isNotEmpty();
                assertThat(onFx(fixture.progress::isLastSaveSucceeded)).isTrue();
                assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
                // Stage-4 review N7: Song B's save names its own checkpoint, written in B's folder, while A's
                // write is still held — it never waited out ruling J3's bound behind A's writer.
                runOnFx(() -> fixture.project.get().markDirty());
                java.util.concurrent.CompletableFuture<com.benesquivelmusic.daw.core.persistence.SaveOutcome> saved =
                        onFx(() -> fixture.projectManager.save(fixture.project.get()));
                assertThat(saved.get(SaveFlowFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS)).isInstanceOfSatisfying(
                        com.benesquivelmusic.daw.core.persistence.SaveOutcome.Saved.class,
                        outcome -> assertThat(outcome.checkpoint()).as("Saved, naming its checkpoint")
                                .hasValueSatisfying(file -> assertThat(file).startsWith(songB.resolve("checkpoints"))));
                assertThat(held.isHeld()).as("while Song A's write is still held").isTrue();
                fixture.settle();

                // A same-path reopen of Song A, the write still held: no lock-conflict prompt.
                openThroughTheDoor(fixture, songA);
                fixture.awaitOnFx(() -> fixture.currentProjectIs(songA)
                        && !fixture.lifecycle().isProjectInstallationPending(), "Song A reopened");
                assertThat(conflictsAsked).as("this session's own retiring lock is taken over silently").isEmpty();
                assertThat(fixture.projectManager.isReadOnly()).isFalse();
                fixture.projectManager.getLockManager().verifyHeld();

                // Song A closed again: its lock now waits for the work it inherited, the held write.
                openThroughTheDoor(fixture, songB);
                fixture.awaitOnFx(() -> fixture.currentProjectIs(songB)
                        && !fixture.lifecycle().isProjectInstallationPending(), "Song B installed again");
                assertThat(held.isHeld()).isTrue();
                assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME))
                        .as("the reopened lease inherited the held write: its lock stays").exists();
                assertThat(conflictsAsked).isEmpty();
            } finally {
                held.release();
            }
            fixture.projectManager.whenRetiredLeasesReleased().get(SaveFlowFixture.GUARD.toMillis(),
                    TimeUnit.MILLISECONDS);
            assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME))
                    .as("once the held write ended, Song A's lock is released").doesNotExist();
            OptionalLong running = fixture.checkpoints.runningGeneration();
            assertThat(running).as("Song B's checkpoints run on").isPresent();
            assertThat(Files.exists(songB.resolve(ProjectLockManager.LOCK_FILE_NAME))).isTrue();
        }
    }

    @Test
    void closeProjectOfSongBThroughTheDoorCompletesWhileSongAsWriteIsHeldAndOnlyAsLockWaitsForIt()
            throws Exception {
        try (ExitFixture exit = new ExitFixture(workspace, AutoSaveConfig.DEFAULT)) {
            SaveFlowFixture fixture = exit.save;
            Path songA = fixture.songA;
            Path songB = fixture.anotherProjectOnDisk("Song B");
            long songAGeneration = fixture.checkpoints.runningGeneration().orElseThrow();
            fixture.awaitCheckpointsSeeded(songAGeneration - 1);

            SaveFlowFixture.Publishes.Hold held = fixture.publishes.holdNext(
                    target -> SaveFlowFixture.isCheckpoint(target) && target.startsWith(songA));
            try {
                runOnFx(() -> fixture.snapshots().createCheckpointWithLabel("Held in Song A"));
                held.awaitHeld();
                openThroughTheDoor(fixture, songB);
                fixture.awaitOnFx(() -> fixture.currentProjectIs(songB)
                        && !fixture.lifecycle().isProjectInstallationPending(), "Song B installed");
                fixture.awaitCheckpointsSeeded(songAGeneration);

                // Song B's own writes land: a Ctrl+S in B writes B's head.
                runOnFx(() -> fixture.project.get().markDirty());
                fixture.save();
                fixture.settle();
                assertThat(fixture.publishes.done(target -> SaveFlowFixture.isHead(target)
                        && target.startsWith(songB))).as("Song B's head was written").isNotEmpty();

                // File ▸ Close Project of Song B through the real door, Song A's write still held.
                exit.closeProject();
                exit.await(() -> exit.welcomes.get() == 1, "Close Project of Song B installed the Untitled project");
                assertThat(held.isHeld()).as("while Song A's write is still held").isTrue();
                assertThat(exit.stepNames()).contains("SEAL DONE", "RELEASE DONE")
                        .noneMatch(name -> name.startsWith("DISPOSE") || name.startsWith("HIDE"));
                assertThat(fixture.projectManager.getCurrentProject()).as("no folder is open").isNull();
                fixture.awaitOnFx(() -> !Files.exists(songB.resolve(ProjectLockManager.LOCK_FILE_NAME)),
                        "Song B's lock released after B's own work, A's write still held");
                assertThat(held.isHeld()).isTrue();
                assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME))
                        .as("Song A's held work is still owed: its lock stays").exists();
            } finally {
                held.release();
            }
            fixture.projectManager.whenRetiredLeasesReleased().get(SaveFlowFixture.GUARD.toMillis(),
                    TimeUnit.MILLISECONDS);
            assertThat(songA.resolve(ProjectLockManager.LOCK_FILE_NAME))
                    .as("once the held write ended, Song A's lock is released").doesNotExist();
            assertThat(fixture.checkpoints.runningGeneration()).as("no generation runs for the Untitled project")
                    .isEmpty();
        }
    }

    /** File ▸ Open of {@code folder}, its chooser answered; returns once the FX steps returned. */
    private static void openThroughTheDoor(SaveFlowFixture fixture, Path folder) throws Exception {
        runOnFx(() -> {
            fixture.lifecycle().setOpenProjectPrompt(() -> folder);
            fixture.lifecycle().onOpenProject();
        });
    }
}
