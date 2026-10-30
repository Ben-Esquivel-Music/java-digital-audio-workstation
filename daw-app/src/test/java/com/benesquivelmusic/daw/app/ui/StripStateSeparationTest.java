package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.CheckpointStatusListener;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, the strip's two states (rulings F6, G4, G13 #3, #4, K1): the
 * head save's outcome and the checkpoint's outcome are separate facts under
 * separate coalescing keys. A failed head write followed in the same frame by
 * a successful safety checkpoint leaves the head failure standing; a
 * checkpoint success never clears a head failure, nor a head success a
 * checkpoint failure; a project replacement resets both; and the autosave
 * failure streak raises exactly one WARNING, on its second consecutive
 * failure, naming the last good checkpoint of the running checkpoints — or
 * that no earlier checkpoint of the project was found.
 *
 * <p>The model's dispatcher is never started: keyed state changes only in the
 * pulse each test runs, so "the same frame" is one pulse. The listener's
 * facts come from a checkpoint manager whose generation runs on the test's
 * folder; each names that generation.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StripStateSeparationTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant NOW = Instant.parse("2026-10-23T14:32:00Z");

    @TempDir
    Path workspace;

    private final FxDispatcher dispatcher = new FxDispatcher();
    private final ProjectOperationProgress progress = new ProjectOperationProgress(dispatcher);
    private final NotificationHistoryService shown = new NotificationHistoryService();
    private final CheckpointManager checkpoints = new CheckpointManager(AutoSaveConfig.DEFAULT);
    private CheckpointStatusListener status;

    @BeforeEach
    void wireTheListener() throws Exception {
        checkpoints.start(workspace).get(SaveFlowFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        runOnFx(() -> {
            NotificationBar bar = new NotificationBar();
            bar.setAnimated(false);
            bar.setAutoDismissMillis(600_000);
            bar.setHistoryService(shown);
            status = new CheckpointStatusListener(progress, checkpoints, bar, dispatcher, InstantSource.fixed(NOW),
                    ZONE);
        });
    }

    @AfterEach
    void stopTheCheckpoints() {
        checkpoints.stop();
    }

    @Test
    void aFailedHeadWriteAndASuccessfulSafetyCheckpointInTheSameFrameLeaveTheHeadFailureStanding()
            throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT)) {
            runOnFx(() -> fixture.project.get().markDirty());
            IOException failure = new IOException("injected head write failure");
            fixture.publishes.failNext(SaveFlowFixture::isHead, failure);

            fixture.save();
            // The safety copy's Written and the head's Failed are both posted before this one pulse.
            fixture.settle();

            assertThat(SaveFlowFixture.checkpointFiles(fixture.songA)).as("fixture: the safety copy").hasSize(1);
            assertThat(onFx(fixture.progress::isLastSaveSucceeded)).as("the head failure stands").isFalse();
            assertThat(onFx(fixture.progress::getSaveFailureCause)).isSameAs(failure);
            assertThat(onFx(fixture.progress::isLastCheckpointSucceeded)).as("next to the checkpoint's success")
                    .isTrue();
            assertThat(onFx(fixture.progress::getLastCheckpointFile))
                    .isEqualTo(SaveFlowFixture.checkpointFiles(fixture.songA).getFirst());
        }
    }

    @Test
    void aCheckpointSuccessNeverClearsAHeadFailure() throws Exception {
        long generation = progress.projectGeneration();
        IOException failure = new IOException("disk full");
        progress.recordSaveFailed(generation, NOW, failure);
        pulse();

        status.accept(written(CheckpointOrigin.SCHEDULED));
        status.accept(written(CheckpointOrigin.AFTER_SAVE));
        pulse();

        assertThat(onFx(progress::isLastSaveSucceeded)).isFalse();
        assertThat(onFx(progress::getSaveFailureCause)).isSameAs(failure);
        assertThat(onFx(progress::getLastCheckpointTime)).isEqualTo(NOW);
    }

    @Test
    void aHeadSuccessNeverClearsACheckpointFailure() throws Exception {
        IOException failure = new IOException("checkpoints/ is full");
        status.accept(failed(CheckpointOrigin.SCHEDULED, failure));
        pulse();

        progress.recordSaveSucceeded(progress.projectGeneration(), NOW, ProjectOperationProgress.SaveScope.FULL);
        pulse();

        assertThat(onFx(progress::isLastCheckpointSucceeded)).isFalse();
        assertThat(onFx(progress::getCheckpointFailureCause)).isSameAs(failure);
        assertThat(onFx(progress::isLastSaveSucceeded)).isTrue();
        assertThat(onFx(progress::getLastSaveTime)).isEqualTo(NOW);
    }

    @Test
    void aProjectReplacementResetsBothAndNothingOfTheReplacedProjectReachesTheStripAfterIt() throws Exception {
        long replaced = progress.projectGeneration();
        progress.recordSaveFailed(replaced, NOW, new IOException("head"));
        status.accept(failed(CheckpointOrigin.SCHEDULED, new IOException("checkpoint")));
        pulse();
        assertThat(onFx(progress::isLastSaveSucceeded)).as("fixture").isFalse();
        assertThat(onFx(progress::isLastCheckpointSucceeded)).as("fixture").isFalse();

        // A failure of the replaced project posted before the replacement, applied after it.
        progress.recordCheckpointFailed(replaced, NOW, new IOException("late"));
        Instant opened = Instant.parse("2026-10-20T09:15:00Z");
        runOnFx(() -> progress.resetForProject(opened));
        progress.recordSaveFailed(replaced, NOW, new IOException("later still"));
        pulse();

        assertThat(onFx(progress::isLastSaveSucceeded)).isTrue();
        assertThat(onFx(progress::getSaveFailureCause)).isNull();
        assertThat(onFx(progress::isLastCheckpointSucceeded)).isTrue();
        assertThat(onFx(progress::getCheckpointFailureCause)).isNull();
        assertThat(onFx(progress::getLastSaveTime)).as("the replacing project's lastModified").isEqualTo(opened);
        assertThat(onFx(progress::getLastCheckpointTime)).isNull();
    }

    @Test
    void theSecondConsecutiveAutosaveFailureRaisesExactlyOneWarning() throws Exception {
        status.accept(written(CheckpointOrigin.SCHEDULED));
        IOException cause = new IOException("the volume went away");

        status.accept(failed(CheckpointOrigin.SCHEDULED, cause));
        runOnFx(() -> { });
        assertThat(warnings()).as("one failure: the strip only").isEmpty();

        status.accept(failed(CheckpointOrigin.AFTER_SAVE, cause));
        runOnFx(() -> { });
        assertThat(warnings()).as("the second in a row: one warning").singleElement()
                .isEqualTo("Checkpoints are failing: the volume went away. The last good checkpoint is from 14:32.");

        status.accept(failed(CheckpointOrigin.SCHEDULED, cause));
        status.accept(failed(CheckpointOrigin.SCHEDULED, cause));
        runOnFx(() -> { });
        assertThat(warnings()).as("later failures count quietly").hasSize(1);
    }

    @Test
    void aUserCheckpointOrASafetyCopyFailureDoesNotCountAndASuccessOrAReplacementStartsTheStreakOver()
            throws Exception {
        IOException cause = new IOException("no space left");
        status.accept(failed(CheckpointOrigin.SCHEDULED, cause));
        status.accept(failed(CheckpointOrigin.USER, cause));
        status.accept(failed(CheckpointOrigin.SAFETY_COPY, cause));
        runOnFx(() -> { });
        assertThat(warnings()).as("a user's and a safety copy's failures are not autosave's").isEmpty();

        status.accept(written(CheckpointOrigin.USER));
        status.accept(failed(CheckpointOrigin.SCHEDULED, cause));
        runOnFx(() -> { });
        assertThat(warnings()).as("a success started the streak over").isEmpty();

        // Another project opened, its checkpoints started on a folder holding none (ruling K1).
        checkpoints.start(Files.createDirectories(workspace.resolve("Another")))
                .get(SaveFlowFixture.GUARD.toMillis(), TimeUnit.MILLISECONDS);
        runOnFx(() -> progress.resetForProject(null));
        status.accept(failed(CheckpointOrigin.SCHEDULED, cause));
        runOnFx(() -> { });
        assertThat(warnings()).as("so did the replacement").isEmpty();

        status.accept(failed(CheckpointOrigin.SCHEDULED, cause));
        runOnFx(() -> { });
        assertThat(warnings()).singleElement()
                .isEqualTo("Checkpoints are failing: no space left. No earlier checkpoint of this project was found.");
    }

    /** Story 333, ruling R3: the exit's closing checkpoint is not an autosave, so its failure does not count. */
    @Test
    void aClosingCheckpointFailureDoesNotCountTowardTheStreakNorStartItOver() throws Exception {
        IOException cause = new IOException("the volume went away");
        status.accept(failed(CheckpointOrigin.SCHEDULED, cause));
        status.accept(failed(CheckpointOrigin.CLOSING, cause));
        runOnFx(() -> { });
        assertThat(warnings()).as("a closing checkpoint's failure is not autosave's").isEmpty();

        status.accept(failed(CheckpointOrigin.SCHEDULED, cause));
        runOnFx(() -> { });
        assertThat(warnings()).as("nor did it start the streak over: the second automatic failure in a row warns")
                .singleElement().satisfies(warning -> assertThat(warning).startsWith("Checkpoints are failing: the"
                        + " volume went away."));
    }

    private void pulse() throws Exception {
        runOnFx(dispatcher::pulse);
    }

    private List<String> warnings() throws Exception {
        AtomicReference<List<String>> messages = new AtomicReference<>();
        runOnFx(() -> messages.set(shown.getEntries().stream()
                .filter(entry -> entry.level() == NotificationLevel.WARNING)
                .map(NotificationEntry::message).toList()));
        return messages.get();
    }

    /**
     * A checkpoint of the running checkpoints written now, its name stamped in
     * the listener's zone — the zone the application's manager stamps names
     * in and its listener reads them in.
     */
    private CheckpointEvent.Written written(CheckpointOrigin origin) {
        return new CheckpointEvent.Written(running(), "chk-1", origin,
                workspace.resolve(CheckpointFileName.format(1, NOW, ZONE)), 1L);
    }

    private CheckpointEvent.Failed failed(CheckpointOrigin origin, Throwable cause) {
        return new CheckpointEvent.Failed(running(), "chk-2", origin, cause);
    }

    private long running() {
        return checkpoints.runningGeneration().orElseThrow();
    }
}
