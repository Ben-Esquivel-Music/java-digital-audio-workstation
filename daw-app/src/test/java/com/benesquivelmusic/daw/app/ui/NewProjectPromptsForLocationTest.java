package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.ProjectMetadata;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.session.SessionManager;
import com.benesquivelmusic.daw.core.session.SessionManifestStore;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 296 — the New-project flow prompts for a real destination directory
 * once and writes there, replacing the §8 / §1.1 silent
 * {@code Files.createTempDirectory} first-save (the "silent first-save into
 * /tmp" rejection). With a stubbed directory chooser, {@code createProject}
 * receives the chosen path; cancelling the prompt creates no project at all.
 *
 * <p>Story 333 — the new project's folder is created off the FX thread, after
 * the UI was rebuilt for the new project: the tests wait for the create, and
 * the folder-dependent part of that rebuild — the folder's checkpoints
 * registered with the snapshot browser, its session manifest opened — runs
 * once the folder exists, through {@code MainController}'s own
 * folder-changed step, the hook the lifecycle controller runs then.</p>
 *
 * <p>{@code MainController} is never FXML-loaded in tests, so this exercises
 * {@link ProjectLifecycleController} directly with an injected location-prompt
 * seam ({@link ProjectLifecycleController#setNewProjectLocationPrompt}), and a
 * {@code MainController} built without FXML whose non-final fields the last
 * test sets (never a final one, JEP 500).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NewProjectPromptsForLocationTest {

    private static final Duration GUARD = SaveFlowFixture.GUARD;

    @TempDir
    Path tempDir;

    @Test
    void onNewProjectCreatesProjectAtThePromptedLocation() throws Exception {
        ProjectManager pm = newProjectManager();
        Path chosenParent = Files.createDirectories(tempDir.resolve("DAW Projects"));
        AtomicBoolean promptConsulted = new AtomicBoolean(false);
        List<Path> folderWhenTheHookRan = new CopyOnWriteArrayList<>();

        ProjectLifecycleController controller = buildController(pm);
        controller.setNewProjectLocationPrompt(() -> {
            promptConsulted.set(true);
            return chosenParent;
        });
        runOnFxThread(() -> controller.setProjectFolderChangedHook(() -> {
            ProjectMetadata current = pm.getCurrentProject();
            folderWhenTheHookRan.add(current == null ? Path.of("none") : current.projectPath());
        }));

        try {
            runOnFxThread(controller::onNewProject);

            assertThat(promptConsulted)
                    .as("the New-project flow consults the location prompt")
                    .isTrue();
            awaitTheInstallationEnded(controller);
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
            assertThat(folderWhenTheHookRan)
                    .as("the folder-changed hook ran once, when the new folder was the open project's")
                    .containsExactly(pm.getCurrentProject().projectPath());
        } finally {
            try {
                // Story 331 — the new project's first save is written off the FX thread.
                SaveOutcomes.awaitIdle(pm);
            } finally {
                pm.closeProject().get(GUARD.toMillis(), TimeUnit.MILLISECONDS); // release lock + stop heartbeat/checkpoints
            }
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
        assertThat(onFx(controller::isProjectInstallationPending)).as("and nothing is being installed").isFalse();
    }

    /**
     * Story 333 (COV-S-3): New used to create its folder before the rebuild,
     * which registered the folder's checkpoints and opened its session
     * manifest; the folder is now created off the FX thread after the
     * rebuild, so {@code MainController}'s folder-changed step does that
     * part once the folder exists. A source pin keeps the production wiring
     * pointed at that step. Kills: a create whose completion runs no hook
     * (no session manifest, no registration), and a hook that runs before
     * the folder exists.
     */
    @Test
    void theNewFoldersCheckpointsAreRegisteredAndItsSessionManifestOpensOnceTheFolderExists() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(tempDir, AutoSaveConfig.DEFAULT)) {
            Path parent = Files.createDirectories(tempDir.resolve("new"));
            MainController main = onFx(() -> {
                MainController controller = new MainController(new SessionManager());
                setField(controller, "projectManager", fixture.projectManager);
                setField(controller, "snapshotsController", fixture.snapshots());
                setField(controller, "sessionManagerDock", new SessionManagerDock());
                setField(controller, "project", fixture.project.get());
                return controller;
            });
            Method folderChanged = MainController.class.getDeclaredMethod("projectFolderChanged");
            folderChanged.setAccessible(true);

            runOnFx(() -> {
                fixture.lifecycle().setProjectFolderChangedHook(() -> {
                    try {
                        folderChanged.invoke(main);
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                });
                fixture.lifecycle().setNewProjectLocationPrompt(() -> parent);
                fixture.lifecycle().onNewProject();
            });
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending()
                    && fixture.projectManager.getCurrentProject() != null
                    && parent.equals(fixture.projectManager.getCurrentProject().projectPath().getParent()),
                    "the new project, installed in its folder");
            Path created = fixture.projectManager.getCurrentProject().projectPath();

            assertThat(onFx(() -> fixture.snapshots().service().getAutosaveDirectories()))
                    .as("the new folder's checkpoints are in the snapshot browser")
                    .contains(created.resolve("checkpoints"));
            Path sessions = created.resolve(SessionManifestStore.SESSIONS_DIR_NAME);
            await(() -> manifestsIn(sessions) > 0, "a session manifest in the new folder");
        }
    }

    /**
     * Story 333 — the new project's folder is created off the FX thread
     * under the installation fence, raised by New itself without the
     * recovery gate: until the replacement finished, the project actions are
     * fenced (a Ctrl+S asks nothing and saves nothing), and the close
     * protocol reads New as the switch pending. Kills: an ungated New whose
     * create runs unfenced.
     */
    @Test
    void newsFolderIsCreatedUnderTheInstallationFence() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(tempDir, AutoSaveConfig.DEFAULT)) {
            Path parent = Files.createDirectories(tempDir.resolve("fenced"));
            SaveFlowFixture.Publishes.Hold create = fixture.publishes.holdNext(
                    target -> SaveFlowFixture.isHead(target) && target.startsWith(parent));
            List<Path> prompted = new CopyOnWriteArrayList<>();
            try {
                runOnFx(() -> {
                    fixture.lifecycle().setNewProjectLocationPrompt(() -> {
                        prompted.add(parent);
                        return parent;
                    });
                    fixture.lifecycle().onNewProject();
                });
                Thread creator = create.awaitHeld();

                assertThat(creator.getName()).isEqualTo(ProjectLifecycleController.PROJECT_CREATE_WORKER);
                assertThat(onFx(fixture.lifecycle()::isProjectInstallationPending)).as("fenced while it is created")
                        .isTrue();
                assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending()))
                        .isEqualTo(new ProjectLifecycleController.PendingSwitch.Installing("New Project"));
                assertThat(onFx(() -> fixture.lifecycle().switchPendingProperty().get())).isTrue();
                runOnFx(() -> fixture.lifecycle().onSaveProject());
                assertThat(prompted).as("a Ctrl+S meanwhile asks nothing").hasSize(1);
            } finally {
                create.release();
            }
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending(), "the replacement finished");
            assertThat(onFx(() -> fixture.lifecycle().switchPendingProperty().get())).isFalse();
            assertThat(fixture.projectManager.getCurrentProject().projectPath().getParent()).isEqualTo(parent);
        }
    }

    /**
     * Stage-4 review S2: with the recovery gate on, as in production, a gated
     * replacement also keeps Recover Session's flag set on its worker; the
     * close protocol still reads the installation by its door — "New
     * Project", "Open Project" — never "Recover Session".
     */
    @Test
    void aGatedNewOrOpenIsReadAsItsOwnDoorWhileItsInstallationIsFenced() throws Exception {
        try (SaveFlowFixture fixture = new SaveFlowFixture(tempDir, AutoSaveConfig.DEFAULT)) {
            java.util.concurrent.atomic.AtomicBoolean holdTheJournalClose = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.CountDownLatch closeReached = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch closeReleased = new java.util.concurrent.CountDownLatch(1);
            runOnFx(() -> fixture.lifecycle().enableRecoveryGate(() -> true, () -> () -> {
                if (holdTheJournalClose.get()) {
                    // The outgoing journal's close, on the gated Open's worker: held, as a slow disk would.
                    closeReached.countDown();
                    try {
                        closeReleased.await(SaveFlowFixture.GUARD.toMillis() * 3,
                                java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, _ -> { }));
            Path parent = Files.createDirectories(tempDir.resolve("gated"));
            SaveFlowFixture.Publishes.Hold create = fixture.publishes.holdNext(
                    target -> SaveFlowFixture.isHead(target) && target.startsWith(parent));
            try {
                runOnFx(() -> {
                    fixture.lifecycle().setNewProjectLocationPrompt(() -> parent);
                    fixture.lifecycle().onNewProject();
                });
                create.awaitHeld();
                assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending())).as("a gated New")
                        .isEqualTo(new ProjectLifecycleController.PendingSwitch.Installing("New Project"));
            } finally {
                create.release();
            }
            fixture.awaitOnFx(() -> !fixture.lifecycle().isProjectInstallationPending()
                    && fixture.lifecycle().closeReadiness().pending()
                    instanceof ProjectLifecycleController.PendingSwitch.None, "the gated New finished");

            Path songB = fixture.anotherProjectOnDisk("Song B");
            holdTheJournalClose.set(true);
            try {
                runOnFx(() -> {
                    fixture.lifecycle().setOpenProjectPrompt(() -> songB);
                    fixture.lifecycle().onOpenProject();
                });
                assertThat(closeReached.await(SaveFlowFixture.GUARD.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS)).as("the gated Open's worker under way").isTrue();
                assertThat(onFx(() -> fixture.lifecycle().closeReadiness().pending())).as("a gated Open")
                        .isEqualTo(new ProjectLifecycleController.PendingSwitch.Installing("Open Project"));
            } finally {
                closeReleased.countDown();
            }
            fixture.awaitOnFx(() -> fixture.currentProjectIs(songB)
                    && !fixture.lifecycle().isProjectInstallationPending(), "Song B opened");
        }
    }

    @Test
    void mainControllerRunsItsFolderChangedStepWhenTheLifecycleControllerSaysTheFolderChanged() throws Exception {
        Path source = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        String code = SourceScanSupport.stripComments(Files.readString(source, StandardCharsets.UTF_8));

        assertThat(code).as("the production wiring of the folder-changed hook")
                .contains("projectLifecycleController.setProjectFolderChangedHook(this::projectFolderChanged);");
        String step = code.substring(code.indexOf("private void projectFolderChanged()"));
        step = step.substring(0, step.indexOf("\n    }") + 6);
        assertThat(step).as("the folder-dependent part of the rebuild")
                .contains("snapshotsController.registerCurrentProjectDirectory();")
                .contains("refreshSessionManager(true);")
                .contains("applyProjectInfoLabels();")
                .contains("repaintLockIndicator();");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static long manifestsIn(Path sessions) throws Exception {
        if (!Files.isDirectory(sessions)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(sessions)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".session.xml")).count();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean holds() throws Exception;
    }

    /**
     * Waits, bounded by {@link #GUARD}, until the controller's project
     * installation has ended — a listener on the fact that re-reads it, the
     * fact read again right after the registration — with no poll (the
     * story-333 conformance review N1).
     */
    private static void awaitTheInstallationEnded(ProjectLifecycleController controller) throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        javafx.beans.InvalidationListener listener = observable -> {
            if (!((javafx.beans.value.ObservableBooleanValue) observable).get()) {
                ended.countDown();
            }
        };
        runOnFxThread(() -> {
            controller.projectInstallationPendingProperty().addListener(listener);
            if (!controller.isProjectInstallationPending()) {
                ended.countDown();
            }
        });
        try {
            assertThat(ended.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the new project's installation within %s", GUARD).isTrue();
        } finally {
            runOnFxThread(() -> controller.projectInstallationPendingProperty().removeListener(listener));
        }
    }

    /**
     * Polls {@code condition} until it holds, bounded by {@link #GUARD}: the
     * house's bounded poll, kept where no observable fact exists (the session
     * manifest on disk).
     */
    private static void await(Condition condition, String what) throws Exception {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (!condition.holds()) {
            assertThat(System.nanoTime() - deadline < 0).as("%s within %s", what, GUARD).isTrue();
            Thread.sleep(5);
        }
    }

    private static void setField(MainController target, String name, Object value) {
        try {
            Field field = MainController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private ProjectLifecycleController buildController(ProjectManager pm) throws Exception {
        AtomicReference<ProjectLifecycleController> ref = new AtomicReference<>();
        runOnFxThread(() -> {
            DawProject project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
            project.markClean(); // clean → the project-close gate does not prompt
            AtomicReference<DawProject> p = new AtomicReference<>(project);
            AtomicReference<UndoManager> um = new AtomicReference<>(new UndoManager());
            var deps = new ProjectLifecycleController.Deps(
                    p::get, p::set, um::get, um::set,
                    () -> { }, () -> { }, () -> { }, () -> null, json -> { }, () -> { });
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
