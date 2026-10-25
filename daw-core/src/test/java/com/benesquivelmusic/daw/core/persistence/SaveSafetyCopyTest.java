package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.Recorder;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;

import static com.benesquivelmusic.daw.core.persistence.CheckpointTestSupport.checkpointNames;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.BACKUP;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.CHECKPOINT;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.HEAD;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.failed;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.tempsIn;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A save whose backup copy or head write fails after its document rendered
 * leaves those bytes in a safety checkpoint (story 331, ruling G9): one file
 * holding exactly what the head write tried to publish, named by the
 * {@link SaveOutcome.Failed} outcome next to the cause as thrown; a revision
 * already checkpointed names the file that holds it and writes no duplicate;
 * a safety copy that fails never replaces the head's cause, and is added to
 * it as suppressed. There is no safety copy after a lost lock. With autosave
 * disabled a safety copy is still written, and a save writes no checkpoint
 * after it (ruling H3). Failures are
 * injected at the {@link AtomicFileWriter} mover seam.
 */
class SaveSafetyCopyTest {

    @TempDir
    Path tempDir;

    private ProjectManager manager;

    @AfterEach
    void releaseTheManager() {
        manager.abandonProject();
    }

    @Test
    void aHeadWriteThatFailsAfterAGoodRenderLeavesOneSafetyCheckpointHoldingTheRenderedBytes() throws IOException {
        RecordingMover mover = new RecordingMover();
        manager = manager(mover, mover, new ProjectDeserializer());
        Path directory = manager.createProject("Song", tempDir).projectPath();
        Recorder recorder = Recorder.on(manager.getCheckpointManager());
        IOException crash = new IOException("injected failure between the forced temp and the move");
        mover.failWith(HEAD, crash);

        SaveOutcome.Failed outcome = failed(manager, project("Song"));

        assertThat(outcome.cause()).isSameAs(crash);
        assertThat(outcome.safetyCopyStillWriting()).isFalse();
        Path safety = outcome.safetyCheckpoint().orElseThrow();
        assertThat(checkpointNames(directory)).as("one safety checkpoint").containsExactly(safety.getFileName().toString());
        assertThat(Files.readAllBytes(safety)).as("holding the bytes the head write tried to publish")
                .isEqualTo(mover.failedTempBytes());
        assertThat(recorder.events(CheckpointEvent.Written.class)).singleElement()
                .satisfies(written -> assertThat(written.origin()).isEqualTo(CheckpointOrigin.SAFETY_COPY));
        assertThat(Files.readString(directory.resolve("project.daw"))).startsWith("# DAW Project File");
    }

    @Test
    void aBackupCopyThatFailsAlsoLeavesASafetyCheckpointForTheRenderCameFirst() throws Exception {
        RecordingMover mover = new RecordingMover();
        manager = manager(mover, mover, new ProjectDeserializer(SaveTestSupport.forcedMigrationRegistry()));
        Path directory = SaveTestSupport.projectOnDisk(manager, "Legacy", tempDir);
        byte[] original = Files.readAllBytes(directory.resolve("project.daw"));
        manager.openProject(directory);
        IOException refused = new IOException("injected failure of the backup's publish");
        mover.failWith(BACKUP, refused);
        DawProject project = manager.getCurrentDawProject();
        project.createAudioTrack("Added before the save");
        project.markDirty();

        SaveOutcome.Failed outcome = failed(manager, project);

        assertThat(outcome.cause()).isSameAs(refused);
        assertThat(Files.readAllBytes(directory.resolve("project.daw"))).as("the head is untouched")
                .isEqualTo(original);
        Path safety = outcome.safetyCheckpoint().orElseThrow();
        DawProject rendered = new ProjectDeserializer().deserialize(Files.readString(safety), directory);
        assertThat(rendered.getTracks()).as("the safety copy holds the document the save rendered")
                .extracting(track -> track.getName())
                .containsExactly("Vox", "Added before the save");
    }

    @Test
    void aRevisionAlreadyCheckpointedNamesTheFileThatHoldsItAndWritesNoDuplicate() throws IOException {
        RecordingMover mover = new RecordingMover();
        manager = manager(mover, mover, new ProjectDeserializer());
        Path directory = manager.createProject("Song", tempDir).projectPath();
        DawProject project = project("Song");
        Path checkpointed = saved(manager, project).checkpoint().orElseThrow();
        mover.failWith(HEAD, new IOException("injected head failure"));

        SaveOutcome.Failed outcome = failed(manager, project);

        assertThat(outcome.safetyCheckpoint()).as("the revision's checkpoint is named").contains(checkpointed);
        assertThat(checkpointNames(directory)).as("no duplicate is written")
                .containsExactly(checkpointed.getFileName().toString());
    }

    @Test
    void aSafetyCopyThatFailsKeepsTheHeadsCauseWithTheCopysErrorSuppressed() throws IOException {
        RecordingMover headMover = new RecordingMover();
        RecordingMover checkpointMover = new RecordingMover();
        manager = manager(headMover, checkpointMover, new ProjectDeserializer());
        Path directory = manager.createProject("Song", tempDir).projectPath();
        IOException headFailure = new IOException("injected head failure");
        IOException copyFailure = new IOException("injected safety-copy failure");
        headMover.failWith(HEAD, headFailure);
        checkpointMover.failWith(CHECKPOINT, copyFailure);

        SaveOutcome.Failed outcome = failed(manager, project("Song"));

        assertThat(outcome.cause()).as("the head's cause, never the copy's").isSameAs(headFailure);
        assertThat(outcome.safetyCheckpoint()).isEmpty();
        assertThat(headFailure.getSuppressed()).as("the copy's error, as thrown").containsExactly(copyFailure);
        assertThat(outcome.safetyCopyStillWriting()).isFalse();
        assertThat(checkpointNames(directory)).isEmpty();
    }

    @Test
    void withAutosaveDisabledAFailedSaveStillLeavesASafetyCopyAndASavedOneNoCheckpoint() throws IOException {
        RecordingMover mover = new RecordingMover();
        CheckpointManager checkpoints = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, false),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover));
        manager = SaveTestSupport.manager(checkpoints, new ProjectLockManager(),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover), ProjectManager.CHECKPOINT_WAIT);
        Path directory = manager.createProject("Song", tempDir).projectPath();
        DawProject project = project("Song");
        IOException crash = new IOException("injected head failure");
        mover.failWith(HEAD, crash);

        SaveOutcome.Failed outcome = failed(manager, project);

        assertThat(outcome.cause()).isSameAs(crash);
        Path safety = outcome.safetyCheckpoint().orElseThrow();
        assertThat(Files.readAllBytes(safety)).as("a safety copy is no autosave (ruling H3)")
                .isEqualTo(mover.failedTempBytes());

        mover.stopFailing();
        project.markDirty();
        SaveOutcome.Saved saved = saved(manager, project);

        assertThat(saved.checkpoint()).as("the checkpoint after a save is autosave's (ruling H3)").isEmpty();
        assertThat(checkpointNames(directory)).containsExactly(safety.getFileName().toString());
    }

    @Test
    void noSafetyCopyIsWrittenAfterTheLockWasStolen() throws IOException {
        RecordingMover mover = new RecordingMover();
        manager = manager(mover, mover, new ProjectDeserializer());
        Path directory = manager.createProject("Song", tempDir).projectPath();
        new ProjectLockManager(InstantSource.system(), "thief", "elsewhere", 99L).forceAcquire(directory);
        mover.clear();

        SaveOutcome.Failed outcome = failed(manager, project("Song"));

        assertThat(outcome.cause()).isInstanceOf(LockStolenException.class);
        assertThat(outcome.safetyCheckpoint()).isEmpty();
        assertThat(mover.publishes()).as("no head, no safety copy").isEmpty();
        assertThat(checkpointNames(directory)).isEmpty();
        assertThat(tempsIn(directory)).isEmpty();
    }

    private static DawProject project(String name) {
        DawProject project = new DawProject(name, AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        project.markDirty();
        return project;
    }

    private static ProjectManager manager(RecordingMover headMover, RecordingMover checkpointMover,
                                          ProjectDeserializer deserializer) {
        CheckpointManager checkpoints = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, checkpointMover));
        return SaveTestSupport.manager(checkpoints, new ProjectLockManager(), deserializer,
                new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, headMover), ProjectManager.CHECKPOINT_WAIT);
    }
}
