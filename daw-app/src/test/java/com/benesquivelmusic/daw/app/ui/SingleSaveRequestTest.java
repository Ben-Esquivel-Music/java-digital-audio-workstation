package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.LockStolenException;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.isCheckpoint;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.isHead;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, "Single-save test", app half (Persistence Integrity book §4.2,
 * §5.2, §6.1; rejection §9.8): one Save on the FX thread is one request to
 * the project manager's canonical save — exactly one {@code project.daw}
 * publish and at most one checkpoint publish, every publish and the lock
 * re-read off the FX thread, and the FX handler back before the head is
 * written. A save still running {@code 500 ms} after it was requested shows
 * "Saving project…" on the strip until it ends; a faster one never does; and
 * a project switch while the timer is pending leaves no operation behind
 * (rulings F7, G13 #7).
 *
 * <p>Persistence is real (a {@link SaveFlowFixture}): the publishes are
 * observed, and held, through the {@code AtomicFileWriter} mover seam.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SingleSaveRequestTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;

    @BeforeEach
    void openSongA() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    @Test
    void oneSaveWritesOneHeadAndOneCheckpointOffTheFxThreadAndShowsItsTime() throws Exception {
        runOnFx(() -> fixture.project.get().markDirty());

        fixture.save();
        fixture.settle();

        List<SaveFlowFixture.Publish> heads = fixture.publishes.done(SaveFlowFixture::isHead);
        List<SaveFlowFixture.Publish> checkpoints = fixture.publishes.done(SaveFlowFixture::isCheckpoint);
        assertThat(heads).as("exactly one head write").hasSize(1);
        assertThat(checkpoints).as("at most one checkpoint: the one after the save").hasSize(1);
        assertThat(fixture.publishes.done()).as("nothing else was published").hasSize(2)
                .allSatisfy(publish -> assertThat(publish.thread()).as("published off the FX thread")
                        .isNotSameAs(fixture.fxThread()));
        assertThat(Files.readString(checkpoints.getFirst().target()))
                .as("the checkpoint holds the bytes the head holds")
                .isEqualTo(Files.readString(heads.getFirst().target()));
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("the project is clean").isFalse();
        assertThat(onFx(fixture.progress::isLastSaveSucceeded)).isTrue();
        assertThat(onFx(fixture.progress::getLastSaveTime)).as("the head's time is the saved metadata's")
                .isEqualTo(fixture.projectManager.getCurrentProject().lastModified());
        assertThat(onFx(fixture.progress::getSavesSucceeded)).as("one head save to flash for").isEqualTo(1L);
        assertThat(fixture.notifications()).as("a save that worked says so on the strip only").isEmpty();
    }

    @Test
    void theFxHandlerReturnsBeforeTheHeadIsWritten() throws Exception {
        runOnFx(() -> fixture.project.get().markDirty());
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);

        fixture.save();
        Thread writer = head.awaitHeld();

        assertThat(writer).as("the head is written on the save writer").isNotSameAs(fixture.fxThread());
        assertThat(writer.getName()).isEqualTo("daw-save-writer");
        assertThat(fixture.publishes.done()).as("the FX handler returned while the head is still unwritten")
                .isEmpty();
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("not clean before the head is written")
                .isTrue();

        head.release();
        fixture.settle();
        assertThat(fixture.publishes.done(SaveFlowFixture::isHead)).hasSize(1);
        assertThat(onFx(() -> fixture.project.get().isDirty())).isFalse();
    }

    @Test
    void theLockIsReReadOnTheSaveWriterNotInTheFxHandler() throws Exception {
        // Another session takes the project over: the save's re-read of the lock finds it.
        new ProjectLockManager(InstantSource.system(), "alice", "Studio-PC", 42L).forceAcquire(fixture.songA);

        fixture.save();
        fixture.settle();

        Throwable cause = onFx(fixture.progress::getSaveFailureCause);
        assertThat(cause).as("the re-read found the lock taken over").isInstanceOf(LockStolenException.class);
        List<String> frames = Arrays.stream(cause.getStackTrace())
                .map(frame -> frame.getClassName() + "." + frame.getMethodName()).toList();
        assertThat(frames).as("the lock was re-read on the project manager's save writer")
                .anyMatch(frame -> frame.endsWith("ProjectManager.drainSaves"))
                .noneMatch(frame -> frame.contains("ProjectLifecycleController"));
        assertThat(fixture.publishes.done(SaveFlowFixture::isHead)).as("nothing was written").isEmpty();
    }

    @Test
    void aSaveStillRunningPastTheThresholdShowsSavingProjectUntilItEnds() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        long requestedNanos = System.nanoTime();

        fixture.save();
        head.awaitHeld();
        fixture.awaitOnFx(() -> fixture.progress.getOperations().stream()
                .anyMatch(operation -> operation.name().equals(ProjectLifecycleController.SAVING_OPERATION_NAME)),
                "the real slow-save timer showed the operation");

        assertThat(Duration.ofNanos(System.nanoTime() - requestedNanos))
                .as("not before the threshold, on the monotonic clock")
                .isGreaterThanOrEqualTo(ProjectLifecycleController.SLOW_SAVE_THRESHOLD);
        assertThat(fixture.operationNames()).containsExactly("Saving project…");

        head.release();
        fixture.settle();
        assertThat(fixture.operationNames()).as("removed when the save ended").isEmpty();
    }

    @Test
    void aSaveThatEndsBeforeTheThresholdNeverShowsSavingProject() throws Exception {
        fixture.holdSlowSaveTimers();

        fixture.save();
        fixture.settle();
        assertThat(fixture.slowSaveTimers).as("fixture: the save armed the timer").hasSize(1);
        fixture.fireSlowSaveTimers();

        assertThat(fixture.operationNames()).as("the save ended before its timer fired").isEmpty();
        assertThat(onFx(fixture.progress::getSavesSucceeded)).isEqualTo(1L);
    }

    @Test
    void aTimerThatFiresWhileTheSaveIsHeldShowsTheOperationOnceAndTheEndRemovesIt() throws Exception {
        fixture.holdSlowSaveTimers();
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);

        fixture.save();
        fixture.save(); // a second Ctrl+S while the first writes arms no second timer
        head.awaitHeld();
        assertThat(fixture.slowSaveTimers).hasSize(1);
        fixture.fireSlowSaveTimers();
        runOnFx(() -> { });
        assertThat(fixture.operationNames()).containsExactly("Saving project…");

        head.release();
        fixture.settle();
        assertThat(fixture.operationNames()).as("removed when the last save of the project ended").isEmpty();
    }

    @Test
    void aProjectSwitchWhileTheTimerIsPendingLeavesNoOperationBehind() throws Exception {
        Path songB = fixture.anotherProjectOnDisk("Song B");
        fixture.holdSlowSaveTimers();
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();

        // A load that passes no gate (the recovery's own) replaces Song A while its save writes.
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(songB))).isTrue();
        fixture.fireSlowSaveTimers();
        runOnFx(() -> { });
        assertThat(fixture.operationNames()).as("the timer of Song A's save shows nothing over Song B")
                .isEmpty();

        head.release();
        fixture.settle();
        assertThat(fixture.operationNames()).as("and its end leaves nothing behind").isEmpty();
        assertThat(onFx(fixture.progress::getSavesSucceeded)).as("Song A's save is not Song B's").isZero();
        assertThat(onFx(fixture.progress::getLastSaveTime)).as("Song B's head time is its own")
                .isEqualTo(fixture.projectManager.getCurrentProject().lastModified());
    }
}
