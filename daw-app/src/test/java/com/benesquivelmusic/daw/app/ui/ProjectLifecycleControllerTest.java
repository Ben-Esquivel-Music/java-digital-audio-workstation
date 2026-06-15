package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for the {@link ProjectLifecycleController} helper logic that require
 * a live JavaFX toolkit but can be exercised without a live JavaFX scene.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectLifecycleControllerTest {

    @TempDir
    Path tempDir;

    // ── Constructor null-safety ──────────────────────────────────────────────

    @Test
    void constructorRejectsNullProjectManager() throws Exception {
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        null, dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        dummyVBox(), dummyDeps(), dummyArchiver()))
                .withMessageContaining("projectManager"));
    }

    @Test
    void constructorRejectsNullSessionInterchangeController() throws Exception {
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), null, dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        dummyVBox(), dummyDeps(), dummyArchiver()))
                .withMessageContaining("sessionInterchangeController"));
    }

    @Test
    void constructorRejectsNullNotificationBar() throws Exception {
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), null,
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        dummyVBox(), dummyDeps(), dummyArchiver()))
                .withMessageContaining("notificationBar"));
    }

    @Test
    void constructorRejectsNullStatusBarLabel() throws Exception {
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        null, dummyLabel(), dummyBorderPane(),
                        dummyVBox(), dummyDeps(), dummyArchiver()))
                .withMessageContaining("statusBarLabel"));
    }

    @Test
    void constructorRejectsNullCheckpointLabel() throws Exception {
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), null, dummyBorderPane(),
                        dummyVBox(), dummyDeps(), dummyArchiver()))
                .withMessageContaining("checkpointLabel"));
    }

    @Test
    void constructorRejectsNullRootPane() throws Exception {
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), null,
                        dummyVBox(), dummyDeps(), dummyArchiver()))
                .withMessageContaining("rootPane"));
    }

    @Test
    void constructorRejectsNullTrackListPanel() throws Exception {
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        null, dummyDeps(), dummyArchiver()))
                .withMessageContaining("trackListPanel"));
    }

    @Test
    void constructorRejectsNullDeps() throws Exception {
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        dummyVBox(), null, dummyArchiver()))
                .withMessageContaining("deps"));
    }

    @Test
    void constructorRejectsNullProjectArchiver() throws Exception {
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        dummyVBox(), dummyDeps(), null))
                .withMessageContaining("projectArchiver"));
    }

    // ── resetProjectState ────────────────────────────────────────────────────

    @Test
    void resetProjectStateClosesCurrentProject() throws Exception {
        ProjectManager pm = dummyProjectManager();
        pm.createProject("Test", tempDir);

        assertThat(pm.getCurrentProject()).isNotNull();

        AtomicReference<ProjectLifecycleController> ref = new AtomicReference<>();
        runOnFxThread(() -> ref.set(new ProjectLifecycleController(
                pm, dummySessionInterchange(), dummyNotificationBar(),
                dummyLabel(), dummyLabel(), dummyBorderPane(),
                dummyVBox(), dummyDeps(), dummyArchiver())));

        ref.get().resetProjectState();

        assertThat(pm.getCurrentProject()).isNull();
    }

    @Test
    void resetProjectStateDoesNothingWhenNoProject() throws Exception {
        ProjectManager pm = dummyProjectManager();

        AtomicReference<ProjectLifecycleController> ref = new AtomicReference<>();
        runOnFxThread(() -> ref.set(new ProjectLifecycleController(
                pm, dummySessionInterchange(), dummyNotificationBar(),
                dummyLabel(), dummyLabel(), dummyBorderPane(),
                dummyVBox(), dummyDeps(), dummyArchiver())));

        ref.get().resetProjectState();
        assertThat(pm.getCurrentProject()).isNull();
    }

    // ── Story 247: migration report dialog wiring ───────────────────────────

    @Test
    void loadProjectFromPathSurfacesSuppressedDialogWithoutBlocking() throws Exception {
        // Build a manager whose registry forces every load to migrate, then
        // pre-suppress the dialog for the project so showIfNeeded()
        // short-circuits before any showAndWait() can block this headless test.
        com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry registry =
                buildForcedMigrationRegistry();
        ProjectManager pm = new ProjectManager(
                new CheckpointManager(AutoSaveConfig.DEFAULT),
                null,
                new com.benesquivelmusic.daw.core.persistence.ProjectLockManager(),
                new com.benesquivelmusic.daw.core.persistence.ProjectDeserializer(registry));

        // Bootstrap a v<CURRENT> project on disk.
        var metadata = pm.createProject("Legacy", tempDir);
        java.nio.file.Path projectFile = metadata.projectPath().resolve("project.daw");
        java.nio.file.Files.writeString(projectFile,
                new com.benesquivelmusic.daw.core.persistence.ProjectSerializer()
                        .serialize(new DawProject("Legacy", AudioFormat.CD_QUALITY)));
        pm.abandonProject();

        // Suppress the dialog for this project at the synthetic target
        // version so showIfNeeded never tries to call showAndWait().
        com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression
                .suppress(metadata.projectPath(),
                        com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry
                                .CURRENT_VERSION + 1);

        AtomicReference<ProjectLifecycleController> ref = new AtomicReference<>();
        AtomicReference<Boolean> loaded = new AtomicReference<>();
        runOnFxThread(() -> {
            ref.set(new ProjectLifecycleController(
                    pm, dummySessionInterchange(), dummyNotificationBar(),
                    dummyLabel(), dummyLabel(), dummyBorderPane(),
                    dummyVBox(), dummyDeps(), dummyArchiver()));
            loaded.set(ref.get().loadProjectFromPath(metadata.projectPath()));
        });

        assertThat(loaded.get()).isTrue();
        assertThat(pm.getLastMigrationReport().wasMigrated()).isTrue();
    }

    @Test
    void rollbackMigrationRestoresExistingBackup() throws Exception {
        com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry registry =
                buildForcedMigrationRegistry();
        ProjectManager pm = new ProjectManager(
                new CheckpointManager(AutoSaveConfig.DEFAULT),
                null,
                new com.benesquivelmusic.daw.core.persistence.ProjectLockManager(),
                new com.benesquivelmusic.daw.core.persistence.ProjectDeserializer(registry));

        // Bootstrap and load the project, then save it once so a .bak
        // is written by the manager's pre-migration backup logic.
        var metadata = pm.createProject("WithBackup", tempDir);
        java.nio.file.Path projectFile = metadata.projectPath().resolve("project.daw");
        java.nio.file.Files.writeString(projectFile,
                new com.benesquivelmusic.daw.core.persistence.ProjectSerializer()
                        .serialize(new DawProject("WithBackup", AudioFormat.CD_QUALITY)));
        String originalContent = java.nio.file.Files.readString(projectFile);
        pm.abandonProject();

        pm.openProject(metadata.projectPath());
        var report = pm.getLastMigrationReport();
        assertThat(report.wasMigrated()).isTrue();
        // Trigger the .bak by saving the migrated project.
        pm.saveDawProject(pm.getCurrentDawProject());
        // Suppress so the post-rollback reload doesn't open another dialog.
        com.benesquivelmusic.daw.core.persistence.migration.MigrationSuppression
                .suppress(metadata.projectPath(), report.toVersion());

        AtomicReference<ProjectLifecycleController> ref = new AtomicReference<>();
        runOnFxThread(() -> ref.set(new ProjectLifecycleController(
                pm, dummySessionInterchange(), dummyNotificationBar(),
                dummyLabel(), dummyLabel(), dummyBorderPane(),
                dummyVBox(), dummyDeps(), dummyArchiver())));

        // Confirm a .bak exists pre-rollback.
        try (var entries = java.nio.file.Files.list(metadata.projectPath())) {
            assertThat(entries.filter(p -> p.getFileName().toString().endsWith(".bak")))
                    .isNotEmpty();
        }

        runOnFxThread(() -> ref.get().rollbackMigration(metadata.projectPath(), report));

        // The on-disk file should match the original (pre-migration) content.
        assertThat(java.nio.file.Files.readString(projectFile)).isEqualTo(originalContent);
    }

    @Test
    void rollbackMigrationWithoutBackupAbandonsInMemoryProject() throws Exception {
        com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry registry =
                buildForcedMigrationRegistry();
        ProjectManager pm = new ProjectManager(
                new CheckpointManager(AutoSaveConfig.DEFAULT),
                null,
                new com.benesquivelmusic.daw.core.persistence.ProjectLockManager(),
                new com.benesquivelmusic.daw.core.persistence.ProjectDeserializer(registry));

        var metadata = pm.createProject("NoBackupYet", tempDir);
        java.nio.file.Path projectFile = metadata.projectPath().resolve("project.daw");
        java.nio.file.Files.writeString(projectFile,
                new com.benesquivelmusic.daw.core.persistence.ProjectSerializer()
                        .serialize(new DawProject("NoBackupYet", AudioFormat.CD_QUALITY)));
        String originalContent = java.nio.file.Files.readString(projectFile);
        pm.abandonProject();

        pm.openProject(metadata.projectPath());
        var report = pm.getLastMigrationReport();
        assertThat(report.wasMigrated()).isTrue();

        AtomicReference<ProjectLifecycleController> ref = new AtomicReference<>();
        runOnFxThread(() -> ref.set(new ProjectLifecycleController(
                pm, dummySessionInterchange(), dummyNotificationBar(),
                dummyLabel(), dummyLabel(), dummyBorderPane(),
                dummyVBox(), dummyDeps(), dummyArchiver())));

        runOnFxThread(() -> ref.get().rollbackMigration(metadata.projectPath(), report));

        // No .bak existed yet, so the on-disk file must be untouched
        // (it is still the pre-migration original).
        assertThat(java.nio.file.Files.readString(projectFile)).isEqualTo(originalContent);
        // And the in-memory project was reset.
        assertThat(pm.getCurrentProject()).isNull();
    }

    /**
     * Builds a registry whose target version is one above
     * {@link com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry#CURRENT_VERSION},
     * populated with no-op step migrations. Forces every load through
     * the migration path.
     */
    private static com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry
            buildForcedMigrationRegistry() {
        int target = com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry
                .CURRENT_VERSION + 1;
        var builder = com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry
                .builder(target);
        for (int v = 1; v < target; v++) {
            int from = v;
            builder.add(com.benesquivelmusic.daw.core.persistence.migration.ProjectMigration
                    .step(from, "v" + from + "->v" + (from + 1), d -> d));
        }
        return builder.build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void runOnFxThread(Runnable action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        if (!completed) {
            throw new RuntimeException("Timed out waiting for JavaFX action to complete");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }

    private static ProjectManager dummyProjectManager() {
        Preferences prefs = Preferences.userRoot().node("daw-test-plc-" + System.nanoTime());
        return new ProjectManager(
                new CheckpointManager(AutoSaveConfig.DEFAULT),
                new RecentProjectsStore(prefs));
    }

    private static SessionInterchangeController dummySessionInterchange() {
        return new SessionInterchangeController();
    }

    private static NotificationBar dummyNotificationBar() {
        return new NotificationBar();
    }

    private static javafx.scene.control.Label dummyLabel() {
        return new javafx.scene.control.Label();
    }

    private static javafx.scene.layout.BorderPane dummyBorderPane() {
        return new javafx.scene.layout.BorderPane();
    }

    private static javafx.scene.layout.VBox dummyVBox() {
        return new javafx.scene.layout.VBox();
    }

    private static ProjectArchiver dummyArchiver() {
        return new ProjectArchiver();
    }

    private static ProjectLifecycleController.Deps dummyDeps() {
        // Story 294 — the Host is retired; the dummy supplies the same swappable
        // project / undo manager via AtomicReference holders so setProject /
        // setUndoManager still swap what the suppliers return. The dirty bit lives on
        // DawProject now (the §1.2 "one dirty bit"), so there is no dirty field — the
        // controller's markClean()/markDirty()/isDirty() operate on the held project.
        AtomicReference<DawProject> project =
                new AtomicReference<>(new DawProject("Test", AudioFormat.CD_QUALITY));
        AtomicReference<UndoManager> undoManager = new AtomicReference<>(new UndoManager());
        return new ProjectLifecycleController.Deps(
                project::get,
                project::set,
                undoManager::get,
                undoManager::set,
                () -> { },
                () -> { },
                mixerView -> { },
                () -> null,
                json -> { });
    }
}
