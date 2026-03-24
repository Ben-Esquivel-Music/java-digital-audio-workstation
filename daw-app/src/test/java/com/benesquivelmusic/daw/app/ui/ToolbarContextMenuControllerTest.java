package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolbarContextMenuControllerTest {

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

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    private <T> T runOnFxThread(java.util.concurrent.Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (error.get() != null) {
            throw error.get();
        }
        return ref.get();
    }

    private ProjectManager createProjectManager() {
        CheckpointManager checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        return new ProjectManager(checkpointManager);
    }

    private ToolbarContextMenuController createController(
            Button[] viewButtons,
            Button[] projectButtons,
            Button[] toolButtons) {
        List<String> statusMessages = new ArrayList<>();
        return new ToolbarContextMenuController(
                viewButtons,
                projectButtons,
                toolButtons,
                createProjectManager(),
                statusMessages::add,
                () -> {},
                path -> {}
        );
    }

    private ToolbarContextMenuController createController(
            Button[] viewButtons,
            Button[] projectButtons,
            Button[] toolButtons,
            ProjectManager projectManager,
            List<String> statusMessages) {
        return new ToolbarContextMenuController(
                viewButtons,
                projectButtons,
                toolButtons,
                projectManager,
                statusMessages::add,
                () -> {},
                path -> {}
        );
    }

    // ── Constructor null checks ──────────────────────────────────────────────

    @Test
    void shouldRejectNullViewButtons() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new ToolbarContextMenuController(
                    null,
                    new Button[]{new Button()},
                    new Button[]{new Button()},
                    createProjectManager(),
                    text -> {},
                    () -> {},
                    path -> {}
            )).isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullProjectButtons() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new ToolbarContextMenuController(
                    new Button[]{new Button()},
                    null,
                    new Button[]{new Button()},
                    createProjectManager(),
                    text -> {},
                    () -> {},
                    path -> {}
            )).isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullToolButtons() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new ToolbarContextMenuController(
                    new Button[]{new Button()},
                    new Button[]{new Button()},
                    null,
                    createProjectManager(),
                    text -> {},
                    () -> {},
                    path -> {}
            )).isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullProjectManager() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new ToolbarContextMenuController(
                    new Button[]{new Button()},
                    new Button[]{new Button()},
                    new Button[]{new Button()},
                    null,
                    text -> {},
                    () -> {},
                    path -> {}
            )).isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullStatusUpdater() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new ToolbarContextMenuController(
                    new Button[]{new Button()},
                    new Button[]{new Button()},
                    new Button[]{new Button()},
                    createProjectManager(),
                    null,
                    () -> {},
                    path -> {}
            )).isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullResetViewLayoutAction() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new ToolbarContextMenuController(
                    new Button[]{new Button()},
                    new Button[]{new Button()},
                    new Button[]{new Button()},
                    createProjectManager(),
                    text -> {},
                    null,
                    path -> {}
            )).isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void shouldRejectNullLoadProjectAction() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            assertThatThrownBy(() -> new ToolbarContextMenuController(
                    new Button[]{new Button()},
                    new Button[]{new Button()},
                    new Button[]{new Button()},
                    createProjectManager(),
                    text -> {},
                    () -> {},
                    null
            )).isInstanceOf(NullPointerException.class);
        });
    }

    // ── Context menu getters return null before initialization ───────────────

    @Test
    void shouldReturnNullViewContextMenuBeforeShown() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button[] viewButtons = { new Button("Arrangement"), new Button("Mixer") };
            Button[] projectButtons = { new Button("New"), new Button("Open") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            assertThat(controller.getViewContextMenu()).isNull();
        });
    }

    @Test
    void shouldReturnNullProjectContextMenuBeforeShown() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button[] viewButtons = { new Button("Arrangement") };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            assertThat(controller.getProjectContextMenu()).isNull();
        });
    }

    @Test
    void shouldReturnNullToolsContextMenuBeforeShown() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button[] viewButtons = { new Button("Arrangement") };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            assertThat(controller.getToolsContextMenu()).isNull();
        });
    }

    // ── Context menu handlers are wired ─────────────────────────────────────

    @Test
    void shouldWireContextMenuHandlerOnViewButtons() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button viewButton = new Button("Arrangement");
            Button[] viewButtons = { viewButton };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            assertThat(viewButton.getOnContextMenuRequested()).isNotNull();
        });
    }

    @Test
    void shouldWireContextMenuHandlerOnProjectButtons() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button projectButton = new Button("New");
            Button[] viewButtons = { new Button("Arrangement") };
            Button[] projectButtons = { projectButton };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            assertThat(projectButton.getOnContextMenuRequested()).isNotNull();
        });
    }

    @Test
    void shouldWireContextMenuHandlerOnToolButtons() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button toolButton = new Button("Plugins");
            Button[] viewButtons = { new Button("Arrangement") };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { toolButton };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            assertThat(toolButton.getOnContextMenuRequested()).isNotNull();
        });
    }

    // ── View context menu contents ──────────────────────────────────────────

    @Test
    void viewContextMenuShouldContainResetViewLayout() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button[] viewButtons = { new Button("Arrangement") };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            ContextMenu menu = controller.buildViewContextMenu();
            List<String> labels = menu.getItems().stream()
                    .filter(item -> item instanceof MenuItem && !(item instanceof javafx.scene.control.SeparatorMenuItem))
                    .map(MenuItem::getText)
                    .toList();

            assertThat(labels).contains("Reset View Layout");
        });
    }

    @Test
    void viewContextMenuShouldContainDetachViewDisabled() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button[] viewButtons = { new Button("Arrangement") };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            ContextMenu menu = controller.buildViewContextMenu();
            MenuItem detachItem = menu.getItems().stream()
                    .filter(item -> item instanceof MenuItem && "Detach View".equals(item.getText()))
                    .findFirst()
                    .orElse(null);

            assertThat(detachItem).isNotNull();
            assertThat(detachItem.isDisable()).isTrue();
        });
    }

    @Test
    void resetViewLayoutShouldCallAction() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            boolean[] resetCalled = { false };
            List<String> statusMessages = new ArrayList<>();
            ToolbarContextMenuController controller = new ToolbarContextMenuController(
                    new Button[]{ new Button("Arrangement") },
                    new Button[]{ new Button("New") },
                    new Button[]{ new Button("Plugins") },
                    createProjectManager(),
                    statusMessages::add,
                    () -> resetCalled[0] = true,
                    path -> {}
            );
            controller.initialize();

            ContextMenu menu = controller.buildViewContextMenu();
            MenuItem resetItem = menu.getItems().stream()
                    .filter(item -> "Reset View Layout".equals(item.getText()))
                    .findFirst()
                    .orElse(null);

            assertThat(resetItem).isNotNull();
            resetItem.fire();

            assertThat(resetCalled[0]).isTrue();
            assertThat(statusMessages).contains("View layout reset to defaults");
        });
    }

    // ── Project context menu contents ───────────────────────────────────────

    @Test
    void projectContextMenuShouldContainNoRecentProjectsWhenEmpty() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button[] viewButtons = { new Button("Arrangement") };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            ContextMenu menu = controller.buildProjectContextMenu();
            MenuItem emptyItem = menu.getItems().stream()
                    .filter(item -> "No recent projects".equals(item.getText()))
                    .findFirst()
                    .orElse(null);

            assertThat(emptyItem).isNotNull();
            assertThat(emptyItem.isDisable()).isTrue();
        });
    }

    @Test
    void projectContextMenuShouldContainRevealInFileManager() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button[] viewButtons = { new Button("Arrangement") };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            ContextMenu menu = controller.buildProjectContextMenu();
            MenuItem revealItem = menu.getItems().stream()
                    .filter(item -> "Reveal in File Manager".equals(item.getText()))
                    .findFirst()
                    .orElse(null);

            assertThat(revealItem).isNotNull();
            // Disabled when no current project
            assertThat(revealItem.isDisable()).isTrue();
        });
    }

    @Test
    void projectContextMenuShouldContainProjectProperties() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button[] viewButtons = { new Button("Arrangement") };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            ContextMenu menu = controller.buildProjectContextMenu();
            MenuItem propertiesItem = menu.getItems().stream()
                    .filter(item -> "Project Properties".equals(item.getText()))
                    .findFirst()
                    .orElse(null);

            assertThat(propertiesItem).isNotNull();
            // Disabled when no current project
            assertThat(propertiesItem.isDisable()).isTrue();
        });
    }

    // ── Tools context menu contents ─────────────────────────────────────────

    @Test
    void toolsContextMenuShouldContainCustomizeToolsDisabled() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button[] viewButtons = { new Button("Arrangement") };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            ContextMenu menu = controller.buildToolsContextMenu();
            MenuItem customizeItem = menu.getItems().stream()
                    .filter(item -> "Customize Tools".equals(item.getText()))
                    .findFirst()
                    .orElse(null);

            assertThat(customizeItem).isNotNull();
            assertThat(customizeItem.isDisable()).isTrue();
        });
    }

    // ── Multiple view buttons all get handlers ──────────────────────────────

    @Test
    void shouldWireAllViewButtonsWithContextMenuHandler() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        runOnFxThread(() -> {
            Button arrangementBtn = new Button("Arrangement");
            Button mixerBtn = new Button("Mixer");
            Button editorBtn = new Button("Editor");
            Button telemetryBtn = new Button("Telemetry");
            Button[] viewButtons = { arrangementBtn, mixerBtn, editorBtn, telemetryBtn };
            Button[] projectButtons = { new Button("New") };
            Button[] toolButtons = { new Button("Plugins") };
            ToolbarContextMenuController controller = createController(
                    viewButtons, projectButtons, toolButtons);
            controller.initialize();

            assertThat(arrangementBtn.getOnContextMenuRequested()).isNotNull();
            assertThat(mixerBtn.getOnContextMenuRequested()).isNotNull();
            assertThat(editorBtn.getOnContextMenuRequested()).isNotNull();
            assertThat(telemetryBtn.getOnContextMenuRequested()).isNotNull();
        });
    }
}
