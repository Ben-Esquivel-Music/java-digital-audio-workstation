package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.CHECKPOINT;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.HEAD;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.SHORT_CHECKPOINT_WAIT;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A door waits for the save writer to go idle before it judges the project
 * again (story 331, rulings G1, J3): {@link ProjectManager#whenSavesIdle()}
 * completes only after the write in progress, the capture waiting behind it
 * and that capture's checkpoint have finished — or that checkpoint's bounded
 * wait ran out. A task queued through
 * {@link ProjectManager#runAfterQueuedSaves} runs after the saves queued
 * before it and before those queued after it, and completes with what it
 * returns or throws. Writes are held at the {@link AtomicFileWriter} mover
 * seam.
 */
class SaveWriterIdleTest {

    @TempDir
    Path tempDir;

    private final RecordingMover headMover = new RecordingMover();
    private final RecordingMover checkpointMover = new RecordingMover();
    private final List<Gate> gates = new ArrayList<>();
    private ProjectManager manager;

    @AfterEach
    void releaseTheManager() {
        gates.forEach(Gate::release);
        manager.closeProject().join();
    }

    @Test
    void theIdleFutureCompletesOnlyAfterTheHeldWriteTheCaptureBehindItAndThatCapturesCheckpoint() throws Exception {
        Path directory = open(ProjectManager.CHECKPOINT_WAIT);
        DawProject project = project();
        Gate headHeld = hold(headMover.holdNext(HEAD));
        Gate secondCheckpointHeld = hold(checkpointMover.holdNext(name("checkpoint-002-")));

        CompletableFuture<SaveOutcome> first = manager.save(project);
        headHeld.awaitReached();
        project.createAudioTrack("Second");
        project.markDirty();
        CompletableFuture<SaveOutcome> second = manager.save(project);
        CompletableFuture<Void> idle = manager.whenSavesIdle();
        assertThat(idle).isNotDone();

        headHeld.release();
        assertThat(await(first)).isInstanceOf(SaveOutcome.Saved.class);
        secondCheckpointHeld.awaitReached();
        assertThat(Files.readString(directory.resolve("project.daw")))
                .as("the waiting capture's head is written").contains("name=\"Second\"");
        assertThat(idle).as("its checkpoint is still being written").isNotDone();
        assertThat(second).isNotDone();

        secondCheckpointHeld.release();
        await(idle);

        assertThat(second).as("the capture behind the held write finished first").isDone();
        assertThat(await(second)).isInstanceOfSatisfying(SaveOutcome.Saved.class, saved ->
                assertThat(saved.checkpoint()).hasValueSatisfying(checkpoint ->
                        assertThat(checkpoint.getFileName().toString()).startsWith("checkpoint-002-")));
    }

    @Test
    void theIdleFutureCompletesWhenTheCheckpointsBoundedWaitRunsOut() throws Exception {
        open(SHORT_CHECKPOINT_WAIT);
        Gate checkpointHeld = hold(checkpointMover.holdNext(CHECKPOINT));

        CompletableFuture<SaveOutcome> pending = manager.save(project());
        CompletableFuture<Void> idle = manager.whenSavesIdle();
        await(idle);

        assertThat(pending).isDone();
        assertThat(checkpointHeld.isReached()).as("non-vacuity: the checkpoint is held").isTrue();
        assertThat(checkpointMover.publishes(CHECKPOINT)).as("and still being written").isEmpty();
    }

    @Test
    void aQueuedTaskRunsAfterTheSavesQueuedBeforeItAndBeforeThoseQueuedAfterIt() throws Exception {
        Path directory = open(ProjectManager.CHECKPOINT_WAIT);
        DawProject project = project();
        Gate headHeld = hold(headMover.holdNext(HEAD));

        CompletableFuture<SaveOutcome> before = manager.save(project);
        headHeld.awaitReached();
        CompletableFuture<String> copied = manager.runAfterQueuedSaves(
                () -> Files.readString(directory.resolve("project.daw")));
        project.createAudioTrack("Queued after the task");
        project.markDirty();
        CompletableFuture<SaveOutcome> after = manager.save(project);
        IllegalStateException failure = new IllegalStateException("injected task failure");
        CompletableFuture<Object> failing = manager.runAfterQueuedSaves(() -> {
            throw failure;
        });
        headHeld.release();

        assertThat(await(copied)).as("the task read the head the save before it wrote")
                .contains("name=\"Vox\"")
                .doesNotContain("Queued after the task");
        assertThat(await(before)).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(await(after)).isInstanceOf(SaveOutcome.Saved.class);
        assertThatThrownBy(() -> failing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isSameAs(failure);
        assertThat(Files.readString(directory.resolve("project.daw"))).contains("name=\"Queued after the task\"");
    }

    private Gate hold(Gate gate) {
        gates.add(gate);
        return gate;
    }

    private static Predicate<Path> name(String prefix) {
        return target -> target.getFileName().toString().startsWith(prefix);
    }

    private Path open(Duration checkpointWait) throws IOException {
        CheckpointManager checkpoints = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
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
