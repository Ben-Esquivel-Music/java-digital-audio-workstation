package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
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
                        dummyButton(), dummyVBox(), dummyHost()))
                .withMessageContaining("projectManager"));
    }

    @Test
    void constructorRejectsNullSessionInterchangeController() throws Exception {
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
