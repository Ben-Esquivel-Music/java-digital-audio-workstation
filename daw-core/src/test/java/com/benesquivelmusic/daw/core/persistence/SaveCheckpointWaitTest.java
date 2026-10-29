package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.Gate;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.CHECKPOINT;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.HEAD;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.SHORT_CHECKPOINT_WAIT;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The save writer waits for the checkpoint it requests at most its bound,
 * and a checkpoint never fails a save whose {@code project.daw} landed
 * (story 331, rulings J3, G13 #5, J2). Past the bound a save of a written
 * head is {@link SaveOutcome.Saved} with no checkpoint, and a failed save's
 * safety copy is reported as still being written; either checkpoint lands
 * once released and its listeners hear of it then. A checkpoint after the
 * save that fails leaves the save {@code Saved} and the project clean, and
 * is reported with the "after a manual save" origin. With autosave disabled
 * a save writes no checkpoint and waits behind none. Checkpoints are held,
 * or failed, at their writer's mover seam; the save's bound is
 * {@link SaveTestSupport#SHORT_CHECKPOINT_WAIT} through its seam.
 */
class SaveCheckpointWaitTest {

    @TempDir
    Path tempDir;

    private final RecordingMover headMover = new RecordingMover();
    private final RecordingMover checkpointMover = new RecordingMover();
    private ProjectManager manager;
    private Gate held;

    @AfterEach
    void releaseTheManager() {
        if (held != null) {
            held.release();
        }
        await(manager.closeProject());
    }

    @Test
    void aCheckpointHeldPastTheBoundLetsTheSaveCompleteSavedWithoutItAndItLandsWhenReleased() throws Exception {
        Path directory = open(true, SHORT_CHECKPOINT_WAIT);
        Recorder recorder = Recorder.on(manager.getCheckpointManager());
        DawProject project = project();
        held = checkpointMover.holdNext(CHECKPOINT);

        long started = System.nanoTime();
        CompletableFuture<SaveOutcome> pending = manager.save(project);
        CompletableFuture<Void> idle = manager.whenSavesIdle();
        SaveOutcome outcome = await(pending);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Saved.class,
                saved -> assertThat(saved.checkpoint()).isEmpty());
        assertThat(took).as("the save waited out its bound, and no longer")
                .isGreaterThanOrEqualTo(SHORT_CHECKPOINT_WAIT)
                .isLessThan(SHORT_CHECKPOINT_WAIT.plusSeconds(5));
        assertThat(Files.readString(directory.resolve("project.daw"))).contains("name=\"Vox\"");
        assertThat(project.isDirty()).isFalse();
        await(idle);
        // Saves going idle does not wait for the checkpoint writer to reach its publish: under load it may still be
        // on its way, so the hold is awaited (bounded, longer than every wait inside) before it is asserted.
        assertThat(held.awaitReached(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("non-vacuity: the checkpoint is held")
                .isTrue();
        assertThat(checkpointMover.publishes(CHECKPOINT)).as("and still being written").isEmpty();
        assertThat(recorder.events(CheckpointEvent.Written.class)).isEmpty();

        held.release();
        recorder.awaitWritten(1);

        CheckpointEvent.Written written = recorder.events(CheckpointEvent.Written.class).getFirst();
        assertThat(written.origin()).isEqualTo(CheckpointOrigin.AFTER_SAVE);
        assertThat(written.file()).exists();
        assertThat(Files.readString(written.file())).isEqualTo(Files.readString(directory.resolve("project.daw")));
    }

    @Test
    void aSafetyCopyHeldPastTheBoundFailsTheSaveAsStillWritingAndLandsWhenReleased() throws Exception {
        Path directory = open(true, SHORT_CHECKPOINT_WAIT);
        Recorder recorder = Recorder.on(manager.getCheckpointManager());
        IOException crash = new IOException("injected head failure");
        headMover.failWith(HEAD, crash);
        held = checkpointMover.holdNext(CHECKPOINT);

        SaveOutcome outcome = await(manager.save(project()));

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Failed.class, failed -> {
            assertThat(failed.cause()).isSameAs(crash);
            assertThat(failed.safetyCheckpoint()).isEmpty();
            assertThat(failed.safetyCopyStillWriting()).isTrue();
        });
        assertThat(crash.getSuppressed()).singleElement().isInstanceOfSatisfying(TimeoutException.class,
                timeout -> assertThat(timeout).hasMessage("the safety copy was still being written after 300 ms"));
        assertThat(held.awaitReached(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("non-vacuity: the safety copy is held")
                .isTrue();

        held.release();
        recorder.awaitWritten(1);

        CheckpointEvent.Written written = recorder.events(CheckpointEvent.Written.class).getFirst();
        assertThat(written.origin()).isEqualTo(CheckpointOrigin.SAFETY_COPY);
        assertThat(checkpointNames(directory)).containsExactly(written.file().getFileName().toString());
        assertThat(Files.readAllBytes(written.file())).isEqualTo(headMover.failedTempBytes());
    }

    @Test
    void withAutosaveDisabledASaveWritesNoCheckpointAndWaitsBehindNoUserCheckpoint() throws Exception {
        // A bound longer than the test's wait: a save waiting behind the held checkpoint could not complete.
        Path directory = open(false, Duration.ofSeconds(SaveTestSupport.TIMEOUT_SECONDS * 6));
        held = checkpointMover.holdNext(CHECKPOINT);
        CompletableFuture<Path> userCheckpoint = manager.getCheckpointManager().writeCheckpoint(
                "<daw-project/>", 1L, CheckpointOrigin.USER);
        held.awaitReached();

        SaveOutcome outcome = await(manager.save(project()));

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Saved.class,
                saved -> assertThat(saved.checkpoint()).isEmpty());
        assertThat(Files.readString(directory.resolve("project.daw"))).contains("name=\"Vox\"");
        assertThat(userCheckpoint).as("the user's checkpoint is still held").isNotDone();

        held.release();
        Path written = await(userCheckpoint);

        assertThat(checkpointNames(directory)).as("only the user's checkpoint")
                .containsExactly(written.getFileName().toString());
    }

    @Test
    void aCheckpointAfterTheSaveThatFailsLeavesTheSaveSavedAndIsReportedAsAfterAManualSave() throws Exception {
        Path directory = open(true, ProjectManager.CHECKPOINT_WAIT);
        Recorder recorder = Recorder.on(manager.getCheckpointManager());
        IOException full = new IOException("injected: checkpoints/ cannot take another file");
        checkpointMover.failWith(CHECKPOINT, full);
        DawProject project = project();

        SaveOutcome outcome = await(manager.save(project));

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Saved.class,
                saved -> assertThat(saved.checkpoint()).isEmpty());
        assertThat(project.isDirty()).as("project.daw landed, so the project is clean").isFalse();
        assertThat(Files.readString(directory.resolve("project.daw"))).contains("name=\"Vox\"");
        recorder.awaitFailed(1);
        assertThat(recorder.events(CheckpointEvent.Failed.class)).singleElement().satisfies(failed -> {
            assertThat(failed.origin()).isEqualTo(CheckpointOrigin.AFTER_SAVE);
            assertThat(failed.cause()).isSameAs(full);
        });
    }

    private Path open(boolean autosave, Duration checkpointWait) throws IOException {
        CheckpointManager checkpoints = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, autosave),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, checkpointMover));
        manager = SaveTestSupport.manager(checkpoints, new ProjectLockManager(),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, headMover), checkpointWait);
        return manager.createProject("Song", tempDir).projectPath();
    }

    private static DawProject project() {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        project.markDirty();
        return project;
    }
}
