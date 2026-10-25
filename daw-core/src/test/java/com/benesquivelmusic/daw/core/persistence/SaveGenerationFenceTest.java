package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.Gate;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingLockReader;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.TIMEOUT_SECONDS;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A save carries the generation of the project it captured (story 331,
 * ruling F9): a capture whose project was abandoned, or replaced by another,
 * before its write ran writes nothing — no head, no backup, no checkpoint,
 * no safety copy — and fails with a message that says so. The writer is held
 * behind a queued task ({@link ProjectManager#runAfterQueuedSaves}) while the
 * project goes away, or inside its lock check — past the first fence, before
 * the render — at the {@link ProjectLockManager} reader seam. A capture of a
 * project replaced since is never coalesced into the next project's capture:
 * it meets the fence on its own.
 */
class SaveGenerationFenceTest {

    @TempDir
    Path tempDir;

    private final RecordingMover mover = new RecordingMover();
    private final RecordingLockReader lockReader = new RecordingLockReader();
    private final List<Gate> gates = new ArrayList<>();
    private ProjectManager manager;

    @BeforeEach
    void createTheManager() {
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        manager = SaveTestSupport.manager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true), writer),
                SaveTestSupport.lockManager("alice", lockReader), writer, ProjectManager.CHECKPOINT_WAIT);
    }

    @AfterEach
    void releaseTheManager() {
        gates.forEach(Gate::release);
        manager.abandonProject();
    }

    @Test
    void aCaptureOfAProjectAbandonedBeforeItsWriteRanWritesNothing() throws Exception {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        byte[] before = Files.readAllBytes(directory.resolve("project.daw"));
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> holding = manager.runAfterQueuedSaves(
                () -> release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        mover.clear();

        CompletableFuture<SaveOutcome> pending = manager.save(project);
        manager.abandonProject();
        release.countDown();

        assertThat(await(holding)).as("the held task was released").isTrue();
        assertClosedBeforeItsWrite(await(pending), directory);
        assertThat(Files.readAllBytes(directory.resolve("project.daw"))).isEqualTo(before);
        assertThat(mover.publishes()).as("no head, backup, checkpoint or safety copy").isEmpty();
        assertThat(checkpointNames(directory)).isEmpty();
    }

    @Test
    void aCaptureOfAProjectReplacedBeforeItsWriteRanWritesIntoNeitherProject() throws Exception {
        Path first = manager.createProject("First", tempDir).projectPath();
        byte[] firstBefore = Files.readAllBytes(first.resolve("project.daw"));
        DawProject project = new DawProject("First", AudioFormat.CD_QUALITY);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> holding = manager.runAfterQueuedSaves(
                () -> release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        CompletableFuture<SaveOutcome> pending = manager.save(project);
        Path second = manager.createProject("Second", tempDir).projectPath();
        byte[] secondBefore = Files.readAllBytes(second.resolve("project.daw"));
        mover.clear();
        release.countDown();

        assertThat(await(holding)).isTrue();
        assertClosedBeforeItsWrite(await(pending), first);
        assertThat(Files.readAllBytes(first.resolve("project.daw"))).isEqualTo(firstBefore);
        assertThat(Files.readAllBytes(second.resolve("project.daw"))).isEqualTo(secondBefore);
        assertThat(mover.publishes()).isEmpty();
    }

    @Test
    void aCaptureWhoseProjectIsReplacedWhileItsLockIsCheckedWritesNothingAfterItsRender() throws Exception {
        Path first = manager.createProject("First", tempDir).projectPath();
        byte[] firstBefore = Files.readAllBytes(first.resolve("project.daw"));
        DawProject project = new DawProject("First", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        project.markDirty();
        Gate lockCheck = hold(lockReader.holdNext());

        CompletableFuture<SaveOutcome> pending = manager.save(project);
        lockCheck.awaitReached();
        assertThat(lockCheck.heldThread().getName()).as("fixture: the save writer is held past the first fence")
                .isEqualTo("daw-save-writer");
        Path second = manager.createProject("Second", tempDir).projectPath();
        byte[] secondBefore = Files.readAllBytes(second.resolve("project.daw"));
        mover.clear();
        lockCheck.release();

        assertClosedBeforeItsWrite(await(pending), first);
        assertThat(Files.readAllBytes(first.resolve("project.daw"))).as("the head is untouched")
                .isEqualTo(firstBefore);
        assertThat(Files.readAllBytes(second.resolve("project.daw"))).isEqualTo(secondBefore);
        assertThat(mover.publishes()).as("no head, backup, checkpoint or safety copy").isEmpty();
        assertThat(checkpointNames(first)).isEmpty();
        assertThat(checkpointNames(second)).isEmpty();
        assertThat(project.isDirty()).isTrue();
    }

    @Test
    void aCaptureOfAReplacedProjectIsNeverCoalescedIntoTheNextProjectsCapture() throws Exception {
        Path first = manager.createProject("First", tempDir).projectPath();
        byte[] firstBefore = Files.readAllBytes(first.resolve("project.daw"));
        DawProject firstProject = new DawProject("First", AudioFormat.CD_QUALITY);
        firstProject.createAudioTrack("Vox");
        firstProject.markDirty();
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> holding = manager.runAfterQueuedSaves(
                () -> release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        CompletableFuture<SaveOutcome> firstSave = manager.save(firstProject);
        Path second = manager.createProject("Second", tempDir).projectPath();
        DawProject secondProject = new DawProject("Second", AudioFormat.CD_QUALITY);
        secondProject.createAudioTrack("Bass");
        secondProject.markDirty();
        CompletableFuture<SaveOutcome> secondSave = manager.save(secondProject);
        assertThat(firstSave).as("both captures wait behind the held task").isNotDone();
        release.countDown();

        assertThat(await(holding)).isTrue();
        assertClosedBeforeItsWrite(await(firstSave), first);
        assertThat(await(secondSave)).isInstanceOfSatisfying(SaveOutcome.Saved.class, saved ->
                assertThat(saved.metadata().projectPath()).isEqualTo(second));
        assertThat(Files.readAllBytes(first.resolve("project.daw"))).as("the replaced project's head is untouched")
                .isEqualTo(firstBefore);
        assertThat(firstProject.isDirty()).as("its capture was never written").isTrue();
        assertThat(Files.readString(second.resolve("project.daw"))).contains("name=\"Bass\"");
    }

    private Gate hold(Gate gate) {
        gates.add(gate);
        return gate;
    }

    private static void assertClosedBeforeItsWrite(SaveOutcome outcome, Path directory) {
        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Failed.class, failed -> {
            assertThat(failed.cause()).isInstanceOf(IOException.class)
                    .hasMessage("The project in " + directory
                            + " was closed or replaced before this save was written; nothing was written");
            assertThat(failed.safetyCheckpoint()).isEmpty();
            assertThat(failed.cause().getSuppressed()).isEmpty();
        });
    }
}
