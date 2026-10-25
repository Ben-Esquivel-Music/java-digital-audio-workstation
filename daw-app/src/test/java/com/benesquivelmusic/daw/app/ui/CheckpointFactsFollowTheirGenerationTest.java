package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.CheckpointStatusListener;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent.RearmReason;
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
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, the checkpoint facts and the project they describe: each
 * {@link CheckpointEvent} names the checkpoint generation that reported it,
 * and the strip's listener applies it on the FX thread only while that
 * generation is the one running, as of the project generation it reads in
 * the same runnable. So the new project's first re-arm, delivered after its
 * checkpoints started but before the strip learned of the switch, shows its
 * countdown; and nothing the replaced project's checkpoints report after the
 * switch — a late re-arm, a written or a failed checkpoint — changes the new
 * project's strip.
 *
 * <p>Facts are handed to the listener by the test, as the manager hands them:
 * a project switch is the checkpoint manager's start on the new folder and the
 * strip's replacement, both on the FX thread, as the lifecycle controller
 * makes them.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class CheckpointFactsFollowTheirGenerationTest {

    private static final Instant DUE = Instant.parse("2031-01-01T00:00:00Z");

    @TempDir
    Path workspace;

    private final FxDispatcher dispatcher = new FxDispatcher();
    private final ProjectOperationProgress progress = new ProjectOperationProgress(dispatcher);
    private final NotificationHistoryService shown = new NotificationHistoryService();
    private final CheckpointManager checkpoints = new CheckpointManager(AutoSaveConfig.DEFAULT);
    private CheckpointStatusListener status;
    private Path songA;
    private Path songB;

    @BeforeEach
    void openSongA() throws Exception {
        songA = Files.createDirectories(workspace.resolve("Song A"));
        songB = Files.createDirectories(workspace.resolve("Song B"));
        checkpoints.start(songA).get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        runOnFx(() -> {
            NotificationBar bar = new NotificationBar();
            bar.setAnimated(false);
            bar.setAutoDismissMillis(600_000);
            bar.setHistoryService(shown);
            status = new CheckpointStatusListener(progress, checkpoints, bar, dispatcher, InstantSource.system(),
                    ZoneId.systemDefault());
        });
    }

    @AfterEach
    void stopTheCheckpoints() {
        checkpoints.stop();
    }

    @Test
    void theNewProjectsFirstRearmDeliveredBeforeTheStripLearnsOfTheSwitchShowsItsCountdown() throws Exception {
        runOnFx(() -> {
            // As loadProjectFromPath does it, in one FX step: the new project's checkpoints start...
            checkpoints.start(songB);
            // ...their seeding re-arms the cadence, and the scheduler hands the re-arm over now...
            status.accept(new CheckpointEvent.Rearmed(checkpoints.runningGeneration().orElseThrow(), DUE,
                    Duration.ofMinutes(5), RearmReason.STARTED));
            // ...and only then does the strip learn of the switch.
            progress.resetForProject(null);
        });
        pulse();

        assertThat(onFx(progress::getNextCheckpointInstant)).as("the new project's countdown").isEqualTo(DUE);
    }

    @Test
    void aRearmOfTheReplacedProjectDeliveredAfterTheSwitchShowsNoCountdown() throws Exception {
        long replaced = checkpoints.runningGeneration().orElseThrow();
        switchToSongB();

        // A scheduler thread of Song A's checkpoints, past its last check when they stopped, delivers late.
        CompletableFuture.runAsync(() -> status.accept(new CheckpointEvent.Rearmed(replaced, DUE,
                Duration.ofMinutes(5), RearmReason.WRITTEN))).get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        pulse();

        assertThat(onFx(progress::getNextCheckpointInstant)).as("never Song A's due time on Song B's strip")
                .isNotEqualTo(DUE);
    }

    @Test
    void aWrittenOrFailedCheckpointOfTheReplacedProjectAfterTheSwitchChangesNothingOfTheNewOne()
            throws Exception {
        long replaced = checkpoints.runningGeneration().orElseThrow();
        switchToSongB();
        IOException cause = new IOException("Song A's volume went away");

        CompletableFuture.runAsync(() -> {
            status.accept(new CheckpointEvent.Written(replaced, "chk-1", CheckpointOrigin.SCHEDULED,
                    songA.resolve(CheckpointFileName.format(7, Instant.now(), ZoneId.systemDefault())), 3L));
            status.accept(new CheckpointEvent.Failed(replaced, "chk-2", CheckpointOrigin.SCHEDULED, cause));
            status.accept(new CheckpointEvent.Failed(replaced, "chk-3", CheckpointOrigin.AFTER_SAVE, cause));
        }).get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        pulse();

        assertThat(onFx(progress::getLastCheckpointFile)).as("no last good checkpoint of Song A").isNull();
        assertThat(onFx(progress::isLastCheckpointSucceeded)).as("no failure of Song A").isTrue();
        assertThat(onFx(progress::getCheckpointFailureCause)).isNull();
        assertThat(onFx(() -> shown.getEntries().stream().map(NotificationEntry::message).toList()))
                .as("and no streak warning").isEmpty();

        // The running checkpoints' own fact still applies: the fence is not a blanket drop.
        Path songBCheckpoint = songB.resolve(CheckpointFileName.format(1, Instant.now(), ZoneId.systemDefault()));
        status.accept(new CheckpointEvent.Written(checkpoints.runningGeneration().orElseThrow(), "chk-4",
                CheckpointOrigin.SCHEDULED, songBCheckpoint, 1L));
        pulse();
        assertThat(onFx(progress::getLastCheckpointFile)).isEqualTo(songBCheckpoint);
    }

    /** Song B replaces Song A, in one FX step: its checkpoints start, and the strip starts over. */
    private void switchToSongB() throws Exception {
        runOnFx(() -> {
            checkpoints.start(songB);
            progress.resetForProject(null);
        });
    }

    private void pulse() throws Exception {
        runOnFx(() -> { });
        runOnFx(dispatcher::pulse);
    }
}
