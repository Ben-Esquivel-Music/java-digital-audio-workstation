package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationReport;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;

import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probe (story 323 review, verification round 2) of the project-change guard
 * ({@link ProjectLifecycleController#PROJECT_CHANGE_WHILE_WRITING_MESSAGE}):
 * two of its call sites promise more than
 * {@code Story323ProjectChangeWhileTakeIsWrittenTest} checks.
 *
 * <ul>
 *   <li>The migration roll-back has two branches. With a backup it copies the
 *       backup over {@code project.daw} and reloads; with none (the migrated
 *       project lives only in memory) it abandons the open project and puts
 *       an empty one in its place directly — through
 *       {@code resetProjectState()}, not the guarded
 *       {@code loadProjectFromPath}. The existing guard test drives only the
 *       first branch; this one drives the second, through the two-argument
 *       overload that the migration report dialog's roll-back calls (it looks
 *       for the newest backup and finds none).</li>
 *   <li>Recovery is refused "before the scan". The existing guard test checks
 *       the refusal in the FX turn that asked; a refusal evaluated after the
 *       scan thread was started passes that check, and the scan then reports
 *       on a later turn (here: a second refusal; with a journal, the recovery
 *       dialog). This one waits {@link #SCAN_WINDOW} for such a report, and
 *       its last leg shows that a scan that is started does report within
 *       that window.</li>
 * </ul>
 *
 * <p>Every FX action runs through {@link Platform#runLater} and is bounded at
 * 5 s; the waits for a scan are on the test thread and bounded by
 * {@link #SCAN_WINDOW}; nothing blocks the FX thread and no dialog is
 * shown.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story323ProjectChangeGuardProbeTest {

    /** How long a recovery scan is given to report; the non-vacuity leg shows a started one does within it. */
    private static final Duration SCAN_WINDOW = Duration.ofSeconds(3);

    @TempDir
    Path workspace;

    private final AtomicBoolean takeBeingWritten = new AtomicBoolean(true);
    private final AtomicReference<DawProject> project =
            new AtomicReference<>(new DawProject("Song A", AudioFormat.CD_QUALITY));
    private final AtomicInteger rebuilds = new AtomicInteger();
    private final NotificationHistoryService shown = new NotificationHistoryService();

    private ProjectManager projectManager;
    private Path songA;
    private ProjectLifecycleController lifecycle;

    @BeforeEach
    void openSongA() throws Exception {
        projectManager = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        songA = projectManager.createProject("Song A", workspace).projectPath();
        project.get().setMetadata(project.get().getMetadata().withPath(songA));
        runOnFx(() -> {
            NotificationBar notificationBar = new NotificationBar();
            notificationBar.setAnimated(false);
            notificationBar.setHistoryService(shown);
            AtomicReference<UndoManager> undoManager = new AtomicReference<>(new UndoManager());
            lifecycle = new ProjectLifecycleController(projectManager, new SessionInterchangeController(),
                    notificationBar, new ProjectOperationProgress(new FxDispatcher()), new BorderPane(),
                    new VBox(),
                    new ProjectLifecycleController.Deps(project::get, project::set, undoManager::get,
                            undoManager::set, () -> { }, () -> { }, rebuilds::incrementAndGet,
                            () -> null, _ -> { }),
                    new ProjectArchiver());
            lifecycle.setTakeBeingWrittenCheck(takeBeingWritten::get);
        });
    }

    @AfterEach
    void closeTheOpenProject() {
        projectManager.abandonProject();
    }

    @Test
    void aMigrationRollBackWithNoBackupIsRefusedBeforeTheInMemoryProjectIsAbandoned() throws Exception {
        MigrationReport report = new MigrationReport(0, 1, List.of(), Instant.now());
        assertThat(backupsIn(songA)).as("fixture: no backup, so the roll-back takes its abandon branch").isEmpty();
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.rollbackMigration(songA, report));

        assertRefusedOnce();
        assertSongAIsStillOpen(songAModel);

        // Non-vacuity: the same call, once the take is written, abandons the
        // open project and replaces it — the branch the guard must cover.
        takeBeingWritten.set(false);
        runOnFx(() -> lifecycle.rollbackMigration(songA, report));
        assertThat(project.get()).as("the in-memory project is discarded once the take is written")
                .isNotSameAs(songAModel);
        assertThat(projectManager.getCurrentProject()).as("and the open project abandoned").isNull();
        assertThat(rebuilds).hasValue(1);
    }

    @Test
    void aRefusedRecoveryStartsNoScanThatReportsOnALaterTurn() throws Exception {
        Path songC = anotherProjectOnDisk("Song C");
        DawProject songAModel = project.get();

        runOnFx(() -> lifecycle.beginRecovery(songC));
        assertRefusedOnce();

        boolean reportedLater = awaitWithinTheScanWindow(
                () -> shown.getEntries().size() > 1 || rebuilds.get() > 0);
        assertThat(reportedLater).as("a refused recovery never reports again: no scan was started").isFalse();
        assertRefusedOnce();
        assertSongAIsStillOpen(songAModel);

        // Non-vacuity: once the take is written, the same recovery starts its
        // scan, and the scan reports (it opens Song C) within the window.
        takeBeingWritten.set(false);
        runOnFx(() -> lifecycle.beginRecovery(songC));
        assertThat(awaitWithinTheScanWindow(() -> rebuilds.get() > 0))
                .as("a started scan reports within %s", SCAN_WINDOW).isTrue();
        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songC);
    }

    /** Polls {@code condition} (after an FX turn each time) until it holds or {@link #SCAN_WINDOW} ends. */
    private static boolean awaitWithinTheScanWindow(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + SCAN_WINDOW.toNanos();
        while (System.nanoTime() < deadline) {
            runOnFx(() -> { });
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        runOnFx(() -> { });
        return condition.getAsBoolean();
    }

    private void assertRefusedOnce() {
        assertThat(shown.getEntries()).as("one WARNING toast, no modal dialog").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message())
                            .isEqualTo(ProjectLifecycleController.PROJECT_CHANGE_WHILE_WRITING_MESSAGE);
                });
    }

    private void assertSongAIsStillOpen(DawProject songAModel) {
        assertThat(project.get()).as("the open project model is not replaced").isSameAs(songAModel);
        assertThat(projectManager.getCurrentProject()).as("the open project is not abandoned").isNotNull();
        assertThat(projectManager.getCurrentProject().projectPath()).isEqualTo(songA);
        assertThat(rebuilds).as("no UI rebuild for another project").hasValue(0);
    }

    private static List<Path> backupsIn(Path projectDir) throws IOException {
        try (Stream<Path> entries = Files.list(projectDir)) {
            return entries.filter(p -> p.getFileName().toString().endsWith(".bak")).toList();
        }
    }

    /** A saved project on disk, created and closed by a manager of its own. */
    private Path anotherProjectOnDisk(String name) throws IOException {
        ProjectManager other = new ProjectManager(new CheckpointManager(AutoSaveConfig.DEFAULT));
        Path directory = other.createProject(name, workspace).projectPath();
        other.closeProject();
        return directory;
    }

    /** Runs {@code action} on the FX thread and rethrows what it threw; bounded at 5 s. */
    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).as("the FX action returned within 5 s").isTrue();
        if (thrown.get() instanceof Error error) {
            throw error;
        }
        if (thrown.get() != null) {
            throw new AssertionError("the FX action threw", thrown.get());
        }
    }
}
