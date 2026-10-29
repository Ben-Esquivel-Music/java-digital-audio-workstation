package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.JournalEnding.JournalEnd;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.ClosingCheckpoint;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.Plan;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.Step;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.StepOutcome;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.StepResult;
import com.benesquivelmusic.daw.app.ui.ProjectFolderCloser.WriterScope;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ProjectLockManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 333, the shared tail of a close ({@link ProjectFolderCloser}; the
 * studio engineer's rulings R3, R8 and ruling 2 OQ-1; Persistence Integrity
 * book §5.4): over a real project manager, its checkpoints and its atomic
 * writer, the steps run in the ruled order — the generation idle, then the
 * journal's detach before the trigger is read, the closing checkpoint, the
 * journal's end on its own worker while the folder is still open, then the
 * release — each outcome told before the next step. A close stopped part-way
 * skips what has not begun: a journal end not yet begun becomes the drain it
 * is asked for, never the seal as well; one that runs is not interrupted,
 * and the stop hands it back so the release can owe it. A seal that fails
 * stops the close, or goes on when the plan says so.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectFolderCloserTest {

    @TempDir
    Path workspace;

    private SaveFlowFixture fixture;
    private final RecordingEnding ending = new RecordingEnding();
    private final List<String> steps = new CopyOnWriteArrayList<>();

    @BeforeEach
    void songAEdited() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        runOnFx(() -> fixture.project.get().markDirty());
    }

    @AfterEach
    void close() throws Exception {
        ending.releaseTheSeal.countDown();
        fixture.close();
    }

    @Test
    void theStepsRunInTheirOrderAndTheJournalEndsWhileTheFolderIsStillOpen() throws Exception {
        ProjectFolderCloser.Closing closing = close(new Plan(ClosingCheckpoint.IF_RECORDS_OR_NOT_CHECKPOINTED,
                Optional.of(JournalEnd.SEAL), WriterScope.EVERY_WRITER, true, false), () -> true);

        ProjectFolderCloser.Result result = await(closing.result());

        assertThat(result).isInstanceOf(ProjectFolderCloser.Released.class);
        assertThat(((ProjectFolderCloser.Released) result).closingCheckpoint()).as("trigger (ii): the edit")
                .hasValueSatisfying(file -> assertThat(file).startsWith(fixture.songA.resolve("checkpoints")));
        assertThat(steps).containsExactly(
                "reached CLOSING_CHECKPOINT", "detached", "CLOSING_CHECKPOINT DONE",
                "reached SEAL", "sealBegan", "SEAL DONE",
                "reached RELEASE", "releaseBegan", "RELEASE DONE");
        assertThat(ending.log).containsExactly("detach", "prepare SEAL", "run SEAL");
        assertThat(ending.sealThread.get()).isEqualTo(ProjectFolderCloser.SEAL_WORKER);
        assertThat(ending.folderOpenWhenItRan.get()).as("R3: the journal ends before the release")
                .isEqualTo(fixture.songA);
        assertThat(fixture.songA.resolve(ProjectLockManager.LOCK_FILE_NAME)).as("awaited release").doesNotExist();
    }

    @Test
    void noClosingCheckpointIsWrittenWhenThePlanOwesNone() throws Exception {
        int before = SaveFlowFixture.checkpointFiles(fixture.songA).size();

        await(close(new Plan(ClosingCheckpoint.NONE, Optional.empty(), WriterScope.OPEN_GENERATION, false, false),
                () -> true).result());

        assertThat(steps).contains("CLOSING_CHECKPOINT SKIPPED", "SEAL SKIPPED");
        assertThat(SaveFlowFixture.checkpointFiles(fixture.songA)).hasSize(before);
        assertThat(ending.log).as("detached, no journal end").containsExactly("detach");
    }

    @Test
    void aStopBeforeTheJournalEndBeganDrainsItInsteadAndNeverSealsIt() throws Exception {
        SaveFlowFixture.Publishes.Hold checkpoint = fixture.publishes.holdNext(SaveFlowFixture::isCheckpoint);
        ProjectFolderCloser.Closing closing = close(new Plan(ClosingCheckpoint.IF_RECORDS_OR_NOT_CHECKPOINTED,
                Optional.of(JournalEnd.SEAL), WriterScope.OPEN_GENERATION, false, false), () -> true);
        checkpoint.awaitHeld();

        CompletionStage<Void> owed = onFx(() -> closing.skipRemaining(JournalEnd.DRAIN_WITHOUT_MANIFEST));
        await(owed);
        checkpoint.release();
        fixture.settle();

        assertThat(await(closing.result())).isInstanceOf(ProjectFolderCloser.Stopped.class);
        assertThat(steps).as("stage-5 review N4: the awaited closing checkpoint ends SKIPPED, never DONE later")
                .containsExactly("reached CLOSING_CHECKPOINT", "detached", "CLOSING_CHECKPOINT SKIPPED",
                        "SEAL SKIPPED", "RELEASE SKIPPED");
        assertThat(ending.log).containsExactly("detach", "detach", "prepare DRAIN_WITHOUT_MANIFEST",
                "run DRAIN_WITHOUT_MANIFEST");
        assertThat(ending.drainThread.get()).isEqualTo(ProjectFolderCloser.DRAIN_WORKER);
        assertThat(fixture.currentProjectIs(fixture.songA)).as("no release: the caller makes it").isTrue();
    }

    @Test
    void aStopWhileTheSealRunsHandsItBackAndNeverInterruptsIt() throws Exception {
        ending.holdTheSeal.set(true);
        ProjectFolderCloser.Closing closing = close(new Plan(ClosingCheckpoint.NONE, Optional.of(JournalEnd.SEAL),
                WriterScope.OPEN_GENERATION, false, false), () -> true);
        assertThat(ending.sealReached.await(GUARD.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        CompletionStage<Void> owed = onFx(() -> closing.skipRemaining(JournalEnd.DRAIN_WITHOUT_MANIFEST));

        assertThat(owed.toCompletableFuture().isDone()).as("the running seal is what a release must owe").isFalse();
        assertThat(onFx(closing::journalEndBegan)).isTrue();
        ending.releaseTheSeal.countDown();
        await(owed);
        assertThat(ending.log).as("no drain beside the running seal").containsExactly("detach", "prepare SEAL",
                "run SEAL");
        assertThat(await(closing.result())).isInstanceOf(ProjectFolderCloser.Stopped.class);
    }

    @Test
    void aSealSubmittedButNotStartedWhenTheCloseStopsNeverRunsBesideTheDrain() throws Exception {
        List<Runnable> submitted = new CopyOnWriteArrayList<>();
        ProjectFolderCloser.Closing closing = onFx(() -> new ProjectFolderCloser(fixture.projectManager,
                fixture.saveAsJournal(), ending, fixture.project::get, fixture.dispatcher, submitted::add)
                .close(new Plan(ClosingCheckpoint.NONE, Optional.of(JournalEnd.SEAL), WriterScope.OPEN_GENERATION,
                        false, false), () -> true, recordingSteps()));
        fixture.awaitOnFx(() -> submitted.size() == 1, "the seal handed to its worker");

        await(onFx(() -> closing.skipRemaining(JournalEnd.DRAIN_WITHOUT_MANIFEST)));
        submitted.getFirst().run();

        assertThat(ending.log).as("cancel(false) is no proof: the phase compare-and-set is")
                .containsExactly("detach", "prepare SEAL", "detach", "prepare DRAIN_WITHOUT_MANIFEST",
                        "run DRAIN_WITHOUT_MANIFEST");
        assertThat(onFx(closing::journalEndBegan)).isFalse();
        assertThat(steps).as("stage-5 review N4: the seal reached but never run ends SKIPPED")
                .containsExactly("reached CLOSING_CHECKPOINT", "detached", "CLOSING_CHECKPOINT SKIPPED",
                        "reached SEAL", "SEAL SKIPPED", "RELEASE SKIPPED");
    }

    /**
     * The app fix pass 1 review, N4: a stop while the release waits for every
     * writer reports RELEASE SKIPPED — no release was made — and the folder
     * stays open once the writer is idle.
     */
    @Test
    void aStopWhileTheReleaseWaitsForEveryWriterReportsItSkipped() throws Exception {
        SaveFlowFixture.Publishes.Hold head = fixture.publishes.holdNext(SaveFlowFixture::isHead);
        fixture.save();
        head.awaitHeld();
        ProjectFolderCloser.Closing closing = close(new Plan(ClosingCheckpoint.NONE, Optional.empty(),
                WriterScope.EVERY_WRITER, true, false), () -> true);
        fixture.awaitOnFx(() -> steps.contains("reached RELEASE"), "the release waiting for the held save");

        await(onFx(() -> closing.skipRemaining(null)));
        head.release();
        fixture.settle();

        assertThat(await(closing.result())).isInstanceOf(ProjectFolderCloser.Stopped.class);
        assertThat(steps).as("the release's wait ended SKIPPED, never DONE")
                .containsExactly("reached CLOSING_CHECKPOINT", "detached", "CLOSING_CHECKPOINT SKIPPED",
                        "reached SEAL", "SEAL SKIPPED", "reached RELEASE", "RELEASE SKIPPED");
        assertThat(fixture.currentProjectIs(fixture.songA)).as("no release was made").isTrue();
    }

    @Test
    void aSealThatFailsStopsTheCloseUntilItIsReleasedAnyway() throws Exception {
        ending.failTheSeal.set(true);
        ProjectFolderCloser.Closing closing = close(new Plan(ClosingCheckpoint.NONE, Optional.of(JournalEnd.SEAL),
                WriterScope.OPEN_GENERATION, false, false), () -> true);

        ProjectFolderCloser.Result failed = await(closing.result());

        assertThat(failed).isInstanceOfSatisfying(ProjectFolderCloser.SealFailed.class,
                sealFailed -> assertThat(sealFailed.cause()).hasMessage("the journal could not be sealed"));
        assertThat(fixture.currentProjectIs(fixture.songA)).as("nothing released yet").isTrue();
        assertThat(steps).contains("SEAL FAILED").doesNotContain("reached RELEASE");

        assertThat(await(onFx(closing::releaseAnyway))).isInstanceOf(ProjectFolderCloser.Released.class);
        assertThat(fixture.currentProjectIs(fixture.songA)).isFalse();
        assertThatThrownBy(() -> onFx(closing::releaseAnyway)).hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSealThatFailsGoesOnToTheReleaseWhenThePlanSaysSo() throws Exception {
        ending.failTheSeal.set(true);

        ProjectFolderCloser.Result result = await(close(new Plan(ClosingCheckpoint.NONE,
                Optional.of(JournalEnd.SEAL), WriterScope.OPEN_GENERATION, false, true), () -> true).result());

        assertThat(result).isInstanceOfSatisfying(ProjectFolderCloser.Released.class,
                released -> assertThat(released.sealFailure()).isPresent());
        assertThat(fixture.currentProjectIs(fixture.songA)).isFalse();
    }

    @Test
    void aCloseNoLongerWantedStopsAtItsNextStepAndEndsNoJournal() throws Exception {
        AtomicBoolean wanted = new AtomicBoolean(false);

        assertThat(await(close(new Plan(ClosingCheckpoint.NONE, Optional.of(JournalEnd.SEAL),
                WriterScope.OPEN_GENERATION, false, false), wanted::get).result()))
                .isInstanceOf(ProjectFolderCloser.Stopped.class);

        assertThat(ending.log).isEmpty();
        assertThat(fixture.currentProjectIs(fixture.songA)).isTrue();
    }

    private ProjectFolderCloser.Closing close(Plan plan, java.util.function.BooleanSupplier wanted) throws Exception {
        return onFx(() -> new ProjectFolderCloser(fixture.projectManager, fixture.saveAsJournal(), ending,
                fixture.project::get, fixture.dispatcher).close(plan, wanted, recordingSteps()));
    }

    /** Steps that record what the close tells them, in {@link #steps}. */
    private ProjectFolderCloser.Steps recordingSteps() {
        return new ProjectFolderCloser.Steps() {
            @Override
            public void reached(Step step) {
                steps.add("reached " + step);
            }

            @Override
            public void outcome(StepOutcome outcome) {
                steps.add(outcome.step() + " " + outcome.result());
            }

            @Override
            public void detached() {
                steps.add("detached");
            }

            @Override
            public void sealBegan() {
                steps.add("sealBegan");
            }

            @Override
            public void releaseBegan() {
                steps.add("releaseBegan");
            }
        };
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** A journal ending that records what it is asked, and can hold or fail its seal. */
    private final class RecordingEnding implements JournalEnding {
        final List<String> log = new CopyOnWriteArrayList<>();
        final AtomicReference<String> sealThread = new AtomicReference<>();
        final AtomicReference<String> drainThread = new AtomicReference<>();
        final AtomicReference<Path> folderOpenWhenItRan = new AtomicReference<>();
        final AtomicBoolean holdTheSeal = new AtomicBoolean();
        final AtomicBoolean failTheSeal = new AtomicBoolean();
        final CountDownLatch sealReached = new CountDownLatch(1);
        final CountDownLatch releaseTheSeal = new CountDownLatch(1);

        @Override
        public void detach() {
            log.add("detach");
        }

        @Override
        public Runnable prepare(JournalEnd mode) {
            log.add("prepare " + mode);
            return () -> {
                log.add("run " + mode);
                (mode == JournalEnd.SEAL ? sealThread : drainThread).set(Thread.currentThread().getName());
                folderOpenWhenItRan.set(fixture.openFolder());
                if (mode == JournalEnd.SEAL && holdTheSeal.get()) {
                    sealReached.countDown();
                    try {
                        releaseTheSeal.await(GUARD.toMillis() * 3, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (mode == JournalEnd.SEAL && failTheSeal.get()) {
                    throw new UncheckedIOException("sealing failed", new IOException("the journal could not be sealed"));
                }
            };
        }
    }
}
