package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.Gate;
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
import java.util.concurrent.CompletableFuture;

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.HEAD;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.failed;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project is marked clean after a save only up to the revision its
 * capture took (story 331, ruling F1): an edit made while the head write runs
 * keeps it dirty after the write succeeds — and is in neither the head nor
 * the checkpoint after it, which holds the save's own capture (ruling F3) —
 * a save with no edit during it leaves it clean, and a failed write never
 * marks it clean, nor does a capture that throws, an {@link Error} included.
 * A change listener that throws on the DIRTY the save's mark-clean fires does
 * not turn a written head into a failed save. The head write is held, or
 * failed, at the {@link AtomicFileWriter} mover seam.
 */
class SaveDirtyStateTest {

    @TempDir
    Path tempDir;

    private final RecordingMover mover = new RecordingMover();
    private ProjectManager manager;
    private DawProject project;
    private Path directory;

    @BeforeEach
    void openAProject() throws IOException {
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        manager = SaveTestSupport.manager(
                new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true), writer),
                new ProjectLockManager(), writer, ProjectManager.CHECKPOINT_WAIT);
        directory = manager.createProject("Song", tempDir).projectPath();
        project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        project.markDirty();
    }

    @AfterEach
    void releaseTheManager() {
        manager.abandonProject();
    }

    @Test
    void anEditMadeWhileTheHeadIsWrittenKeepsTheProjectDirtyAfterTheWrite() throws Exception {
        Gate held = mover.holdNext(HEAD);

        CompletableFuture<SaveOutcome> pending = manager.save(project);
        held.awaitReached();
        project.createAudioTrack("Added during the write");
        project.markDirty();
        held.release();
        SaveOutcome outcome = await(pending);

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Saved.class, saved ->
                assertThat(saved.revision()).as("the capture's revision").isLessThan(project.getEditRevision()));
        assertThat(project.isDirty()).as("the edit is not in project.daw, so the project stays dirty").isTrue();
        String head = Files.readString(directory.resolve("project.daw"));
        assertThat(head).doesNotContain("Added during the write");
        Path checkpoint = ((SaveOutcome.Saved) outcome).checkpoint().orElseThrow();
        assertThat(Files.readString(checkpoint))
                .as("the checkpoint after the save holds the save's own capture, not the model as it is now")
                .isEqualTo(head)
                .doesNotContain("Added during the write");
    }

    @Test
    void aCaptureThatThrowsAnErrorFailsTheSaveWithThatErrorAndLeavesTheProjectDirty() throws IOException {
        InternalError broken = new InternalError("injected capture failure: an Error, not an Exception");
        project.getTracks().getFirst().getMidiEffectChain().add(new SaveTestSupport.UncapturableEffect(broken));
        byte[] before = Files.readAllBytes(directory.resolve("project.daw"));
        mover.clear();

        CompletableFuture<SaveOutcome> pending = manager.save(project);

        assertThat(pending).as("refused at the call: nothing was queued").isDone();
        assertThat(await(pending)).isInstanceOfSatisfying(SaveOutcome.Failed.class, outcome -> {
            assertThat(outcome.cause()).isSameAs(broken);
            assertThat(outcome.safetyCheckpoint()).as("nothing rendered, so no safety copy").isEmpty();
        });
        assertThat(mover.publishes()).as("nothing was written").isEmpty();
        assertThat(project.isDirty()).isTrue();
        assertThat(Files.readAllBytes(directory.resolve("project.daw"))).isEqualTo(before);
    }

    @Test
    void aSaveWithNoEditDuringItsWriteLeavesTheProjectClean() {
        assertThat(project.isDirty()).as("fixture: an edit before the save").isTrue();

        saved(manager, project);

        assertThat(project.isDirty()).isFalse();
    }

    @Test
    void aChangeListenerThatThrowsWhenTheSaveMarksTheProjectCleanLeavesAWrittenHeadSaved() throws IOException {
        IllegalStateException listenerFailure = new IllegalStateException("injected DIRTY listener failure");
        project.addChangeListener(kind -> {
            if (kind == DawProject.ChangeKind.DIRTY) {
                throw listenerFailure;
            }
        });

        SaveOutcome.Saved outcome = saved(manager, project);

        assertThat(project.isDirty()).as("the project was marked clean before its listener threw").isFalse();
        assertThat(outcome.checkpoint()).as("the checkpoint after the save is still requested")
                .hasValueSatisfying(checkpoint -> assertThat(checkpoint).exists());
        assertThat(Files.readString(outcome.checkpoint().orElseThrow()))
                .isEqualTo(Files.readString(directory.resolve("project.daw")));
    }

    @Test
    void aFailedWriteNeverMarksTheProjectClean() throws IOException {
        IOException crash = new IOException("injected failure between the forced temp and the move");
        mover.failWith(HEAD, crash);

        SaveOutcome.Failed outcome = failed(manager, project);

        assertThat(outcome.cause()).isSameAs(crash);
        assertThat(project.isDirty()).isTrue();
        assertThat(Files.readString(directory.resolve("project.daw")))
                .as("the metadata file createProject wrote is as it was").startsWith("# DAW Project File");
    }
}
