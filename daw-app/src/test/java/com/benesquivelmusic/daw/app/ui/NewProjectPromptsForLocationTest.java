package com.benesquivelmusic.daw.app.ui;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 296 — the New-project flow prompts for a real destination directory
 * once and writes there, replacing the §8 / §1.1 silent
 * {@code Files.createTempDirectory} first-save (the "silent first-save into
 * /tmp" rejection). With a stubbed directory chooser, {@code createProject}
 * receives the chosen path; cancelling the prompt creates no project at all.
 *
 * <p>{@code MainController} is never FXML-loaded in tests, so this exercises
 * {@link ProjectLifecycleController} directly with an injected location-prompt
 * seam ({@link ProjectLifecycleController#setNewProjectLocationPrompt}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NewProjectPromptsForLocationTest {

    @TempDir
    Path tempDir;

    @Test
    void onNewProjectCreatesProjectAtThePromptedLocation() throws Exception {
        ProjectManager pm = newProjectManager();
        Path chosenParent = Files.createDirectories(tempDir.resolve("DAW Projects"));
        AtomicBoolean promptConsulted = new AtomicBoolean(false);

        ProjectLifecycleController controller = buildController(pm);
        controller.setNewProjectLocationPrompt(() -> {
            promptConsulted.set(true);
            return chosenParent;
        });

        try {
            runOnFxThread(controller::onNewProject);

            assertThat(promptConsulted)
                    .as("the New-project flow consults the location prompt")
                    .isTrue();
            assertThat(pm.getCurrentProject())
                    .as("createProject ran for the prompted location")
                    .isNotNull();
            assertThat(pm.getCurrentProject().projectPath().getParent())
                    .as("createProject received the CHOSEN parent directory — not a "
                            + "Files.createTempDirectory system-temp dir (§8 / §1.1)")
                    .isEqualTo(chosenParent);
            assertThat(pm.getCurrentProject().projectPath())
                    .as("the new project lives under the chosen workspace")
                    .startsWith(chosenParent);
        } finally {
            pm.abandonProject(); // release lock + stop heartbeat/checkpoints
        }
    }

    @Test
    void cancellingTheLocationPromptCreatesNoProjectAndNoTempDir() throws Exception {
        ProjectManager pm = newProjectManager();

        ProjectLifecycleController controller = buildController(pm);
        controller.setNewProjectLocationPrompt(() -> null); // user cancels the chooser

        runOnFxThread(controller::onNewProject);

        assertThat(pm.getCurrentProject())
                .as("cancelling the location prompt creates NO project — the old code "
                        + "would have silently created one in a temp dir on first save")
                .isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ProjectLifecycleController buildController(ProjectManager pm) throws Exception {
        AtomicReference<ProjectLifecycleController> ref = new AtomicReference<>();
        runOnFxThread(() -> {
            DawProject project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
            project.markClean(); // clean → confirmDiscardUnsavedChanges() won't block
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
        Preferences prefs = Preferences.userRoot().node("daw-test-newproj-" + System.nanoTime());
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
