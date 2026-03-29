package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for the {@link ProjectLifecycleController} helper logic that can be
 * exercised without a live JavaFX scene or toolkit.
 */
class ProjectLifecycleControllerTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void initToolkit() throws Exception {
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized
        } catch (UnsupportedOperationException ignored) {
            // No display available (headless CI environment)
            return;
        }
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) {
            }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

    // ── Constructor null-safety ──────────────────────────────────────────────

    @Test
    void constructorRejectsNullProjectManager() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        null, dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        dummyButton(), dummyVBox(), dummyHost()))
                .withMessageContaining("projectManager"));
    }

    @Test
    void constructorRejectsNullSessionInterchangeController() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), null, dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        dummyButton(), dummyVBox(), dummyHost()))
                .withMessageContaining("sessionInterchangeController"));
    }

    @Test
    void constructorRejectsNullNotificationBar() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), null,
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        dummyButton(), dummyVBox(), dummyHost()))
                .withMessageContaining("notificationBar"));
    }

    @Test
    void constructorRejectsNullStatusBarLabel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        null, dummyLabel(), dummyBorderPane(),
                        dummyButton(), dummyVBox(), dummyHost()))
                .withMessageContaining("statusBarLabel"));
    }

    @Test
    void constructorRejectsNullCheckpointLabel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), null, dummyBorderPane(),
                        dummyButton(), dummyVBox(), dummyHost()))
                .withMessageContaining("checkpointLabel"));
    }

    @Test
    void constructorRejectsNullRootPane() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), null,
                        dummyButton(), dummyVBox(), dummyHost()))
                .withMessageContaining("rootPane"));
    }

    @Test
    void constructorRejectsNullRecentProjectsButton() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        null, dummyVBox(), dummyHost()))
                .withMessageContaining("recentProjectsButton"));
    }

    @Test
    void constructorRejectsNullTrackListPanel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        dummyButton(), null, dummyHost()))
                .withMessageContaining("trackListPanel"));
    }

    @Test
    void constructorRejectsNullHost() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() ->
            assertThatNullPointerException()
                .isThrownBy(() -> new ProjectLifecycleController(
                        dummyProjectManager(), dummySessionInterchange(), dummyNotificationBar(),
                        dummyLabel(), dummyLabel(), dummyBorderPane(),
                        dummyButton(), dummyVBox(), null))
                .withMessageContaining("host"));
    }

    // ── resetProjectState ────────────────────────────────────────────────────

    @Test
    void resetProjectStateClosesCurrentProject() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        Path tempDir = Files.createTempDirectory("plc-test-");
        ProjectManager pm = dummyProjectManager();
        pm.createProject("Test", tempDir);

        assertThat(pm.getCurrentProject()).isNotNull();

        AtomicReference<ProjectLifecycleController> ref = new AtomicReference<>();
        runOnFxThread(() -> ref.set(new ProjectLifecycleController(
                pm, dummySessionInterchange(), dummyNotificationBar(),
                dummyLabel(), dummyLabel(), dummyBorderPane(),
                dummyButton(), dummyVBox(), dummyHost())));

        ref.get().resetProjectState();

        assertThat(pm.getCurrentProject()).isNull();
    }

    @Test
    void resetProjectStateDoesNothingWhenNoProject() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        ProjectManager pm = dummyProjectManager();

        AtomicReference<ProjectLifecycleController> ref = new AtomicReference<>();
        runOnFxThread(() -> ref.set(new ProjectLifecycleController(
                pm, dummySessionInterchange(), dummyNotificationBar(),
                dummyLabel(), dummyLabel(), dummyBorderPane(),
                dummyButton(), dummyVBox(), dummyHost())));

        ref.get().resetProjectState();
        assertThat(pm.getCurrentProject()).isNull();
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
        latch.await(5, TimeUnit.SECONDS);
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

    private static javafx.scene.control.Button dummyButton() {
        return new javafx.scene.control.Button();
    }

    private static javafx.scene.layout.VBox dummyVBox() {
        return new javafx.scene.layout.VBox();
    }

    private static ProjectLifecycleController.Host dummyHost() {
        return new ProjectLifecycleController.Host() {
            private DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
            private UndoManager undoManager = new UndoManager();
            private boolean dirty;

            @Override public DawProject project() { return project; }
            @Override public void setProject(DawProject p) { project = p; }
            @Override public UndoManager undoManager() { return undoManager; }
            @Override public void setUndoManager(UndoManager um) { undoManager = um; }
            @Override public boolean isProjectDirty() { return dirty; }
            @Override public void setProjectDirty(boolean d) { dirty = d; }
            @Override public void resetTrackCounters() { }
            @Override public void rebuildHistoryPanel() { }
            @Override public void onProjectUIRebuild(MixerView mixerView) { }
        };
    }
}

