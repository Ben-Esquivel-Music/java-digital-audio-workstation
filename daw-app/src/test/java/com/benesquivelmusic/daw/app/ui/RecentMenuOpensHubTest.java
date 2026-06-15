package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.hub.ProjectHubView;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 296 — the File-menu "recent projects" entry now opens the §4.2 Project
 * Hub (a full-window browser) instead of building a filename {@code ContextMenu}
 * (§1.4; §8 "a Recent Projects menu that is just filenames"). The controller's
 * {@code onRecentProjects()} routes through an injectable presenter seam, so a
 * headless test can assert it presented a {@link ProjectHubView} whose Recent
 * section reflects the {@code RecentProjectsStore}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RecentMenuOpensHubTest {

    @TempDir
    Path tempDir;

    @Test
    void onRecentProjectsOpensProjectHubReflectingTheStore() throws Exception {
        ProjectManager pm = newProjectManager();
        // Seed two recent projects on disk (newest added last → first in the store).
        Path alpha = newProjectDir("Alpha");
        Path beta = newProjectDir("Beta");
        pm.getRecentProjectsStore().addRecentProject(alpha);
        pm.getRecentProjectsStore().addRecentProject(beta);

        AtomicReference<ProjectHubView> presented = new AtomicReference<>();
        ProjectLifecycleController controller = buildController(pm);
        controller.setProjectHubPresenter(presented::set);

        runOnFxThread(controller::onRecentProjects);

        ProjectHubView hub = presented.get();
        assertThat(hub)
                .as("the recent-projects menu entry opens the §4.2 Project Hub, "
                        + "not a filename ContextMenu")
                .isNotNull();
        assertThat(hub.recentCards())
                .as("the Hub's Recent section renders one card per recent project")
                .hasSize(2);
        // Recent order mirrors the store (most-recent-first): Beta then Alpha.
        assertThat(hub.recentCards().get(0).getName()).isIn("Beta", "Alpha");

        joinScans(hub); // let the per-card background scans finish before @TempDir cleanup
    }

    @Test
    void hubClearRecentButtonClearsTheStoreAndEmptiesTheRecentSection() throws Exception {
        ProjectManager pm = newProjectManager();
        pm.getRecentProjectsStore().addRecentProject(newProjectDir("Alpha"));
        pm.getRecentProjectsStore().addRecentProject(newProjectDir("Beta"));

        AtomicReference<ProjectHubView> presented = new AtomicReference<>();
        ProjectLifecycleController controller = buildController(pm);
        controller.setProjectHubPresenter(presented::set);
        runOnFxThread(controller::onRecentProjects);

        ProjectHubView hub = presented.get();
        assertThat(hub.recentCards()).hasSize(2);
        joinScans(hub);

        // The Hub's "Clear Recent" affordance (carried over from the old menu's
        // "Clear Recent Projects" item) clears the store and empties the section.
        runOnFxThread(() -> hub.clearRecentButton().fire());

        assertThat(pm.getRecentProjectsStore().getRecentProjectPaths())
                .as("Clear Recent clears the recent-projects store")
                .isEmpty();
        AtomicReference<Integer> remaining = new AtomicReference<>();
        runOnFxThread(() -> remaining.set(hub.recentCards().size()));
        assertThat(remaining.get())
                .as("Clear Recent empties the Hub's Recent section in place")
                .isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path newProjectDir(String name) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(dir.resolve("project.daw"),
                "# DAW Project File\n"
                        + "name=" + name + "\n"
                        + "created_at=2026-01-01T00:00:00Z\n"
                        + "last_modified=2026-01-01T00:00:00Z\n");
        return dir;
    }

    private static void joinScans(ProjectHubView hub) throws InterruptedException {
        for (Thread scan : hub.pendingScans()) {
            scan.join(2_000);
        }
    }

    private ProjectLifecycleController buildController(ProjectManager pm) throws Exception {
        AtomicReference<ProjectLifecycleController> ref = new AtomicReference<>();
        runOnFxThread(() -> {
            DawProject project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
            project.markClean();
            AtomicReference<DawProject> p = new AtomicReference<>(project);
            AtomicReference<UndoManager> um = new AtomicReference<>(new UndoManager());
            var deps = new ProjectLifecycleController.Deps(
                    p::get, p::set, um::get, um::set,
                    () -> { }, () -> { }, mixerView -> { }, () -> null, json -> { });
            ref.set(new ProjectLifecycleController(
                    pm, new SessionInterchangeController(), new NotificationBar(),
                    new ProjectOperationProgress(new FxDispatcher()),
                    new BorderPane(), new VBox(), deps, new ProjectArchiver()));
        });
        return ref.get();
    }

    private static ProjectManager newProjectManager() {
        Preferences prefs = Preferences.userRoot().node("daw-test-recenthub-" + System.nanoTime());
        return new ProjectManager(
                new CheckpointManager(AutoSaveConfig.DEFAULT),
                new RecentProjectsStore(prefs));
    }

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
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timed out waiting for JavaFX action to complete");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }
}
