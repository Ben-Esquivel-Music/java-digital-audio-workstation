package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress.SaveScope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, one coalescing key per fact (memory "FxDispatcher coalesce: one
 * key per fact"): a head save's success and failure, and a checkpoint's
 * written and failed outcome, posted in either order within one frame, each
 * leave every fact they set — the failure state of the later one, the time
 * (and, for a checkpoint, the file) of the success. The head's success signal
 * the Saved cell flashes on advances once in each frame with at least one
 * head-save success, and at no other time. The journal's queued count and its
 * back-pressure keep each other in one frame, and a checkpoint failure leaves
 * the last good checkpoint, a fact of its own key, as it is.
 *
 * <p>The model's dispatcher is never started: keyed state changes only in the
 * pulse each test runs, so "one frame" is one pulse.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SameFrameStripFactsTest {

    private static final Instant SAVED = Instant.parse("2026-10-23T14:30:00Z");
    private static final Instant FAILED = Instant.parse("2026-10-23T14:31:00Z");
    private static final Path CHECKPOINT = Path.of("checkpoints", "checkpoint-012-20261023T143000.daw");

    private final FxDispatcher dispatcher = new FxDispatcher();
    private final ProjectOperationProgress progress = new ProjectOperationProgress(dispatcher);
    private final IOException cause = new IOException("the volume went away");

    @Test
    void aHeadSuccessThenAFailureInOneFrameKeepTheSuccesssTimeAndCountAndTheFailureStands() throws Exception {
        long generation = progress.projectGeneration();

        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        progress.recordSaveFailed(generation, FAILED, cause);
        pulse();

        assertThat(onFx(progress::getLastSaveTime)).as("the success's time").isEqualTo(SAVED);
        assertThat(onFx(progress::getLastSaveScope)).isEqualTo(SaveScope.FULL);
        assertThat(onFx(progress::getSavesSucceeded)).as("the success's signal").isEqualTo(1L);
        assertThat(onFx(progress::isLastSaveSucceeded)).as("the later failure stands").isFalse();
        assertThat(onFx(progress::getSaveFailureCause)).isSameAs(cause);
        assertThat(onFx(progress::getSaveFailureTime)).isEqualTo(FAILED);
    }

    @Test
    void aHeadFailureThenASuccessInOneFrameLeaveNoFailureAndTheSuccesssTimeAndCount() throws Exception {
        long generation = progress.projectGeneration();

        progress.recordSaveFailed(generation, FAILED, cause);
        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        pulse();

        assertThat(onFx(progress::getLastSaveTime)).isEqualTo(SAVED);
        assertThat(onFx(progress::getLastSaveScope)).isEqualTo(SaveScope.FULL);
        assertThat(onFx(progress::getSavesSucceeded)).isEqualTo(1L);
        assertThat(onFx(progress::isLastSaveSucceeded)).as("the later success cleared it").isTrue();
        assertThat(onFx(progress::getSaveFailureCause)).isNull();
        assertThat(onFx(progress::getSaveFailureTime)).isNull();
    }

    @Test
    void aCheckpointWrittenThenFailedInOneFrameKeepTheWrittenFileAndTimeAndTheFailureStands() throws Exception {
        long generation = progress.projectGeneration();

        progress.recordCheckpointWritten(generation, SAVED, CHECKPOINT, () -> true);
        progress.recordCheckpointFailed(generation, FAILED, cause);
        pulse();

        assertThat(onFx(progress::getLastCheckpointTime)).as("the last good checkpoint's time").isEqualTo(SAVED);
        assertThat(onFx(progress::getLastCheckpointFile)).isEqualTo(CHECKPOINT);
        assertThat(onFx(progress::isLastCheckpointSucceeded)).as("the later failure stands").isFalse();
        assertThat(onFx(progress::getCheckpointFailureCause)).isSameAs(cause);
        assertThat(onFx(progress::getCheckpointFailureTime)).isEqualTo(FAILED);
    }

    @Test
    void aCheckpointFailedThenWrittenInOneFrameLeaveNoFailureAndTheWrittenFileAndTime() throws Exception {
        long generation = progress.projectGeneration();

        progress.recordCheckpointFailed(generation, FAILED, cause);
        progress.recordCheckpointWritten(generation, SAVED, CHECKPOINT, () -> true);
        pulse();

        assertThat(onFx(progress::getLastCheckpointTime)).isEqualTo(SAVED);
        assertThat(onFx(progress::getLastCheckpointFile)).isEqualTo(CHECKPOINT);
        assertThat(onFx(progress::isLastCheckpointSucceeded)).as("the later success cleared it").isTrue();
        assertThat(onFx(progress::getCheckpointFailureCause)).isNull();
        assertThat(onFx(progress::getCheckpointFailureTime)).isNull();
    }

    @Test
    void theJournalsQueuedCountAndItsBackPressurePostedInOneFrameBothSurvive() throws Exception {
        progress.setJournalEventsQueued(7);
        progress.setJournalBackpressure(ProjectOperationProgress.JournalBackpressure.SLOW);
        pulse();

        assertThat(onFx(progress::getJournalEventsQueued)).as("the count").isEqualTo(7);
        assertThat(onFx(progress::getJournalBackpressure)).as("and the back-pressure")
                .isEqualTo(ProjectOperationProgress.JournalBackpressure.SLOW);

        progress.setJournalBackpressure(ProjectOperationProgress.JournalBackpressure.NORMAL);
        progress.setJournalEventsQueued(0);
        pulse();

        assertThat(onFx(progress::getJournalEventsQueued)).isZero();
        assertThat(onFx(progress::getJournalBackpressure)).isEqualTo(ProjectOperationProgress.JournalBackpressure.NORMAL);
    }

    /**
     * One key per fact: a checkpoint failure sets only its key's three facts.
     * The last good checkpoint is a fact of its own key — cleared once its
     * checkpoints have ended, by the {@code checkpointsEnded()} of the
     * abandon or replacement that ends them, never by a failure, which that
     * end leaves standing.
     */
    @Test
    void aCheckpointFailureLeavesTheLastGoodCheckpointToItsOwnKey() throws Exception {
        long generation = progress.projectGeneration();
        AtomicBoolean running = new AtomicBoolean(true);
        progress.recordCheckpointWritten(generation, SAVED, CHECKPOINT, running::get);
        pulse();
        running.set(false);

        progress.recordCheckpointFailed(generation, FAILED, cause);
        pulse();

        assertThat(onFx(progress::isLastCheckpointSucceeded)).isFalse();
        assertThat(onFx(progress::getLastCheckpointFile)).as("the failure did not touch the last good checkpoint")
                .isEqualTo(CHECKPOINT);
        runOnFx(progress::checkpointsEnded);
        assertThat(onFx(progress::getLastCheckpointFile)).as("the end of its checkpoints clears it").isNull();
        assertThat(onFx(progress::isLastCheckpointSucceeded)).as("and leaves the failure standing").isFalse();
    }

    /**
     * The contract of {@code savesSucceededProperty()}: it advances by one in
     * each frame with at least one head-save success of the open project —
     * two in one frame advance it once — and changes on nothing else: a
     * failure, a checkpoint, a project replacement, or a success of a
     * replaced project.
     */
    @Test
    void theSaveSuccessSignalAdvancesOncePerFrameWithASuccessAndAtNoOtherTime() throws Exception {
        long generation = progress.projectGeneration();
        progress.recordSaveSucceeded(generation, SAVED, SaveScope.FULL);
        progress.recordSaveSucceeded(generation, SAVED.plusSeconds(1), SaveScope.FULL);
        pulse();
        assertThat(onFx(progress::getSavesSucceeded)).as("two successes in one frame: once").isEqualTo(1L);
        assertThat(onFx(progress::getLastSaveTime)).as("the later success's time").isEqualTo(SAVED.plusSeconds(1));

        progress.recordSaveSucceeded(generation, SAVED.plusSeconds(2), SaveScope.FULL);
        pulse();
        assertThat(onFx(progress::getSavesSucceeded)).as("a success in the next frame: once more").isEqualTo(2L);

        progress.recordSaveFailed(generation, FAILED, cause);
        progress.recordCheckpointWritten(generation, SAVED, CHECKPOINT, () -> true);
        pulse();
        runOnFx(() -> progress.resetForProject(SAVED));
        progress.recordSaveSucceeded(generation, SAVED.plusSeconds(3), SaveScope.FULL);
        pulse();
        assertThat(onFx(progress::getSavesSucceeded))
                .as("no change for a failure, a checkpoint, a replacement or the replaced project's success")
                .isEqualTo(2L);
    }

    private void pulse() throws Exception {
        runOnFx(dispatcher::pulse);
    }
}
