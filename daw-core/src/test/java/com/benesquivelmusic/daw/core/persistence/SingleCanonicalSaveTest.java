package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.Gate;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingLockReader;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover;
import com.benesquivelmusic.daw.core.persistence.SaveTestSupport.RecordingMover.Publish;
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

import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.CHECKPOINT;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.HEAD;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.await;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One manual save publishes exactly one head and at most one checkpoint, and
 * the calling thread publishes neither and reads no lock file (story 331,
 * "Single-save test", core half; Persistence Integrity book §4.2, §5.2,
 * rejection §9.8). Publishes are counted through the {@link AtomicFileWriter}
 * mover seam, shared by the head and the checkpoint writers, and the
 * lock-file reads through the {@link ProjectLockManager} reader seam; this
 * class counts no capture. The save writer's lock check comes before its
 * render on the same thread, so a save that returns while that check is held
 * shows the caller neither rendered nor wrote; the head's publish then runs
 * on the thread that made the check. A document that cannot be rendered pins
 * where the render runs: its save fails on the save writer, not at the call.
 * That the checkpoint holds the save's own capture, never the model as it is
 * when the checkpoint is written, is {@code SaveDirtyStateTest}'s.
 */
class SingleCanonicalSaveTest {

    @TempDir
    Path tempDir;

    private final RecordingMover mover = new RecordingMover();
    private final RecordingLockReader lockReader = new RecordingLockReader();
    private ProjectManager manager;

    @BeforeEach
    void createTheManager() {
        AtomicFileWriter writer = new AtomicFileWriter(AtomicFileWriter.TEMP_CHANNEL_OPENER, mover);
        CheckpointManager checkpoints = new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true),
                writer);
        manager = SaveTestSupport.manager(checkpoints, SaveTestSupport.lockManager("alice", lockReader), writer,
                ProjectManager.CHECKPOINT_WAIT);
    }

    @AfterEach
    void releaseTheManager() {
        manager.closeProject().join();
    }

    @Test
    void oneSavePublishesOneHeadAndOneCheckpointAndNeitherOnTheCallingThread() throws Exception {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox");
        mover.clear();
        lockReader.clear();
        Thread caller = Thread.currentThread();
        Gate lockCheck = lockReader.holdNext();

        CompletableFuture<SaveOutcome> pending = manager.save(project);

        lockCheck.awaitReached();
        assertThat(pending).as("the save returned while its writer was held at the lock check, before the render")
                .isNotDone();
        assertThat(mover.publishes()).as("nothing is published before the lock check").isEmpty();
        Thread saveWriter = lockCheck.heldThread();
        lockCheck.release();
        SaveOutcome outcome = await(pending);

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(saveWriter).isNotSameAs(caller);
        assertThat(saveWriter.isVirtual()).isTrue();
        assertThat(saveWriter.getName()).isEqualTo("daw-save-writer");
        assertThat(lockReader.readers()).as("the lock file is read off the calling thread, by the save writer")
                .contains(saveWriter)
                .doesNotContain(caller);
        List<Publish> heads = mover.publishes(HEAD);
        assertThat(heads).as("exactly one head write").singleElement()
                .satisfies(head -> assertThat(head.thread()).isSameAs(saveWriter));
        List<Publish> checkpoints = mover.publishes(CHECKPOINT);
        assertThat(checkpoints).as("at most one checkpoint: exactly one here").singleElement()
                .satisfies(checkpoint -> assertThat(checkpoint.thread().getName()).isEqualTo("daw-checkpoint-writer"));
        Path orderingFact = directory.resolve("checkpoints").resolve(CheckpointOrder.FILE_NAME);
        assertThat(mover.publishes(orderingFact::equals)).as("one explicit checkpoint-order certification")
                .singleElement().satisfies(fact -> assertThat(fact.thread()).isSameAs(checkpoints.getFirst().thread()));
        assertThat(mover.publishes()).extracting(Publish::target)
                .as("one head, its checkpoint certification, and one checkpoint, with no other writes")
                .containsExactly(heads.getFirst().target(), orderingFact, checkpoints.getFirst().target());
        assertThat(mover.publishes()).extracting(Publish::thread).doesNotContain(caller);
        Path checkpoint = checkpoints.getFirst().target();
        assertThat(((SaveOutcome.Saved) outcome).checkpoint()).contains(checkpoint);
        assertThat(CheckpointOrder.read(orderingFact.getParent())).containsExactly(checkpoint.getFileName().toString());
        assertThat(Files.readString(checkpoint)).as("the checkpoint holds the bytes the head was written with")
                .isEqualTo(Files.readString(directory.resolve("project.daw")));
    }

    @Test
    void theRenderRunsOnTheSaveWriterSoADocumentThatCannotRenderFailsThereNotAtTheCall() throws Exception {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        byte[] before = Files.readAllBytes(directory.resolve("project.daw"));
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        // XML 1.0 cannot hold U+0000: the capture takes the name as it is, and only the render refuses it.
        // This fixture relies on that serialization defect (a NUL or an unpaired surrogate in a track name fails
        // every save of the project): replace it with another document that captures but cannot render when the
        // defect is fixed — refused at the capture, this test would fail blaming the render's place.
        project.createAudioTrack("Vox\0");
        project.markDirty();
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> holding = manager.runAfterQueuedSaves(
                () -> release.await(SaveTestSupport.TIMEOUT_SECONDS, TimeUnit.SECONDS));
        mover.clear();

        CompletableFuture<SaveOutcome> pending = manager.save(project);

        assertThat(pending).as("the call only captured: a render there would have failed this save at once")
                .isNotDone();
        release.countDown();
        assertThat(await(holding)).isTrue();
        assertThat(await(pending)).isInstanceOfSatisfying(SaveOutcome.Failed.class, failed -> {
            assertThat(failed.cause()).isInstanceOf(IOException.class)
                    .hasMessage("Failed to serialize project");
            assertThat(failed.safetyCheckpoint()).as("nothing rendered, so no safety copy").isEmpty();
        });
        assertThat(mover.publishes()).isEmpty();
        assertThat(Files.readAllBytes(directory.resolve("project.daw"))).isEqualTo(before);
        assertThat(project.isDirty()).isTrue();
    }

    @Test
    void twoSavesOfAnUnchangedRevisionWriteTheHeadTwiceButOneCheckpoint() throws Exception {
        manager.createProject("Song", tempDir);
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        mover.clear();

        SaveOutcome.Saved first = saved(manager, project);
        SaveOutcome.Saved second = saved(manager, project);

        assertThat(second.revision()).as("fixture: no edit between the saves").isEqualTo(first.revision());
        assertThat(mover.publishes(HEAD)).hasSize(2);
        assertThat(mover.publishes(CHECKPOINT)).as("the revision is checkpointed once (ruling F3)").hasSize(1);
        assertThat(second.checkpoint()).as("the second save names the checkpoint that holds the revision")
                .isEqualTo(first.checkpoint())
                .isPresent();
    }

    @Test
    void rapidSavesQueuedBehindAHeldWriteCoalesceIntoTheNewestCapture() throws Exception {
        Path directory = manager.createProject("Song", tempDir).projectPath();
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.markDirty();
        mover.clear();
        Gate held = mover.holdNext(HEAD);

        CompletableFuture<SaveOutcome> first = manager.save(project);
        held.awaitReached();
        List<CompletableFuture<SaveOutcome>> queued = new ArrayList<>();
        for (int take = 1; take <= 5; take++) {
            project.createAudioTrack("Take " + take);
            project.markDirty();
            queued.add(manager.save(project));
        }
        assertThat(queued).as("every capture waits behind the held write").noneMatch(CompletableFuture::isDone);
        held.release();

        SaveOutcome firstOutcome = await(first);
        List<SaveOutcome> outcomes = queued.stream().map(SaveTestSupport::await).toList();

        assertThat(firstOutcome).isInstanceOf(SaveOutcome.Saved.class);
        SaveOutcome newest = outcomes.getLast();
        assertThat(newest).isInstanceOfSatisfying(SaveOutcome.Saved.class,
                saved -> assertThat(saved.revision()).isEqualTo(project.getEditRevision()));
        assertThat(outcomes).as("a replaced capture completes with the newest one's outcome")
                .allSatisfy(outcome -> assertThat(outcome).isSameAs(newest));
        assertThat(mover.publishes(HEAD)).as("the held write, then the newest capture: two head writes for six")
                .hasSize(2);
        assertThat(Files.readString(directory.resolve("project.daw")))
                .as("project.daw holds the last capture")
                .contains("name=\"Take 5\"");
        assertThat(project.isDirty()).as("no edit came after the last capture").isFalse();
    }
}
