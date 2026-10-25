package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.CheckpointStatusListener;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointEvent;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
import com.benesquivelmusic.daw.core.snapshot.SnapshotEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.GUARD;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 331, the last good checkpoint (ruling K1): until the project's
 * checkpoints write one, it is the newest checkpoint already on disk as they
 * were seeded — its time the stamp in its name, never the file's time — and
 * the autosave failure warning names it, with its date when it is not today;
 * with none on disk, the warning says no earlier checkpoint of the project
 * was found. A snapshot restore keeps the project's checkpoints running, so
 * it keeps the last good checkpoint while it resets both failure states; an
 * open of another project shows that project's own.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LastGoodCheckpointTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Instant TODAY_NOON = LocalDateTime.of(2026, 10, 23, 12, 0).atZone(ZONE).toInstant();

    @TempDir
    Path workspace;

    private final FxDispatcher dispatcher = new FxDispatcher();
    private final ProjectOperationProgress progress = new ProjectOperationProgress(dispatcher);
    private final NotificationHistoryService shown = new NotificationHistoryService();
    private final CheckpointManager checkpoints = new CheckpointManager(AutoSaveConfig.DEFAULT);
    private final IOException cause = new IOException("the volume went away");
    private CheckpointStatusListener status;
    private SaveFlowFixture fixture;

    @AfterEach
    void stop() throws Exception {
        checkpoints.stop();
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void theNewestCheckpointOnDiskIsTheLastGoodOneByItsNameAndTheWarningGivesItsDate() throws Exception {
        Path folder = Files.createDirectories(workspace.resolve("Song A").resolve("checkpoints"));
        Path older = Files.writeString(folder.resolve("checkpoint-046-20261022T175500.daw"), "<daw-project/>");
        Path newest = Files.writeString(folder.resolve("checkpoint-047-20261022T181000.daw"), "<daw-project/>");
        for (Path file : List.of(older, newest)) {
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        }

        startListening(folder.getParent());

        awaitOnTheStrip(() -> newest.equals(progress.getLastCheckpointFile()), "the newest seeded checkpoint");
        assertThat(onFx(progress::getLastCheckpointTime)).as("the stamp in its name, not the file's time")
                .isEqualTo(LocalDateTime.of(2026, 10, 22, 18, 10).atZone(ZONE).toInstant());

        twoAutosaveFailures();
        assertThat(warnings()).singleElement().isEqualTo("Checkpoints are failing: the volume went away. The last"
                + " good checkpoint is from 2026-10-22 18:10.");
        assertThat(onFx(progress::getLastCheckpointFile)).as("still the last good one while its checkpoints run")
                .isEqualTo(newest);
    }

    @Test
    void withNoCheckpointOnDiskThereIsNoneAndTheWarningSaysNoEarlierOneWasFound() throws Exception {
        startListening(Files.createDirectories(workspace.resolve("Song A")));
        runOnFx(() -> { });
        runOnFx(dispatcher::pulse);

        assertThat(onFx(progress::getLastCheckpointFile)).isNull();
        assertThat(onFx(progress::getLastCheckpointTime)).isNull();
        twoAutosaveFailures();
        assertThat(warnings()).singleElement().isEqualTo("Checkpoints are failing: the volume went away. No earlier"
                + " checkpoint of this project was found.");
    }

    @Test
    void aSnapshotRestoreKeepsTheLastGoodCheckpointAndAnOpenOfAnotherProjectShowsItsOwn() throws Exception {
        fixture = new SaveFlowFixture(workspace, AutoSaveConfig.DEFAULT);
        Path songB = fixture.anotherProjectOnDisk("Song B");
        SnapshotEntry entry = onFx(() -> fixture.snapshots().createCheckpointWithLabel("Before the comp"));
        fixture.awaitOnFx(() -> fixture.progress.getLastCheckpointFile() != null, "Song A's user checkpoint");
        Path songACheckpoint = onFx(fixture.progress::getLastCheckpointFile);
        long running = fixture.checkpoints.runningGeneration().orElseThrow();
        fixture.status().accept(new CheckpointEvent.Failed(running, "chk-9", CheckpointOrigin.SCHEDULED, cause));
        fixture.awaitOnFx(() -> !fixture.progress.isLastCheckpointSucceeded(), "fixture: a checkpoint failure");

        runOnFx(() -> fixture.snapshots().restoreEntry(entry));
        fixture.pulse();

        assertThat(onFx(fixture.progress::isLastCheckpointSucceeded)).as("the restore reset the failure").isTrue();
        assertThat(onFx(fixture.progress::getLastCheckpointFile))
                .as("and kept the last good checkpoint: the project's checkpoints still run")
                .isEqualTo(songACheckpoint);

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(songB))).isTrue();
        Path songBNewest = SaveFlowFixture.checkpointFiles(songB).getLast();
        fixture.awaitOnFx(() -> songBNewest.equals(fixture.progress.getLastCheckpointFile()),
                "Song B's newest checkpoint on disk");
    }

    /** Wires the strip's listener to the checkpoints, then starts them on {@code projectDir}. */
    private void startListening(Path projectDir) throws Exception {
        runOnFx(() -> {
            NotificationBar bar = new NotificationBar();
            bar.setAnimated(false);
            bar.setAutoDismissMillis(600_000);
            bar.setHistoryService(shown);
            status = new CheckpointStatusListener(progress, checkpoints, bar, dispatcher,
                    InstantSource.fixed(TODAY_NOON), ZONE);
            checkpoints.addEventListener(status);
        });
        checkpoints.start(projectDir).get(GUARD.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Two failed ticks of the running checkpoints, as the manager reports them. */
    private void twoAutosaveFailures() throws Exception {
        long running = checkpoints.runningGeneration().orElseThrow();
        status.accept(new CheckpointEvent.Failed(running, "chk-1", CheckpointOrigin.SCHEDULED, cause));
        status.accept(new CheckpointEvent.Failed(running, "chk-2", CheckpointOrigin.SCHEDULED, cause));
        runOnFx(() -> { });
        runOnFx(dispatcher::pulse);
    }

    private void awaitOnTheStrip(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (true) {
            runOnFx(dispatcher::pulse);
            if (onFx(condition::getAsBoolean)) {
                return;
            }
            assertThat(System.nanoTime() - deadline < 0).as("%s within %s", what, GUARD).isTrue();
            Thread.sleep(5);
        }
    }

    private List<String> warnings() throws Exception {
        return onFx(() -> shown.getEntries().stream().filter(entry -> entry.level() == NotificationLevel.WARNING)
                .map(NotificationEntry::message).toList());
    }
}
