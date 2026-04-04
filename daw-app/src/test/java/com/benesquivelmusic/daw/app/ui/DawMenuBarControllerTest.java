package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;

import javafx.application.Platform;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DawMenuBarController}.
 *
 * <p>Verifies menu structure, item counts, accelerator presence, and
 * disabled-state synchronization without requiring a live scene graph.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DawMenuBarControllerTest {

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Minimal stub host that records invocations and provides configurable
     * state for menu sync tests.
     */
    private static final class StubHost implements DawMenuBarController.Host {
        private final DawProject project =
                new DawProject("Test", AudioFormat.STUDIO_QUALITY);
        boolean dirty;
        boolean canUndo;
        boolean canRedo;
        boolean clipboard;
        boolean selection;
        DawView view = DawView.ARRANGEMENT;

        int newProjectCalls;
        int openProjectCalls;
        int saveProjectCalls;
        int recentProjectsCalls;
        int importSessionCalls;
        int exportSessionCalls;
        int importAudioCalls;
        int undoCalls;
        int redoCalls;
        int copyCalls;
        int cutCalls;
        int pasteCalls;
        int duplicateCalls;
        int deleteCalls;
        int toggleSnapCalls;
        int managePluginsCalls;
        int settingsCalls;
        int switchViewCalls;
        int toggleBrowserCalls;
        int toggleHistoryCalls;
        int toggleNotificationsCalls;
        int toggleVisualizationsCalls;
        int toggleToolbarCalls;
        int helpCalls;

        @Override public DawProject project() { return project; }
        @Override public boolean isProjectDirty() { return dirty; }
        @Override public boolean canUndo() { return canUndo; }
        @Override public boolean canRedo() { return canRedo; }
        @Override public boolean hasClipboardContent() { return clipboard; }
        @Override public boolean hasSelection() { return selection; }
        @Override public DawView activeView() { return view; }

        @Override public void onNewProject() { newProjectCalls++; }
        @Override public void onOpenProject() { openProjectCalls++; }
        @Override public void onSaveProject() { saveProjectCalls++; }
        @Override public void onRecentProjects() { recentProjectsCalls++; }
        @Override public void onImportSession() { importSessionCalls++; }
        @Override public void onExportSession() { exportSessionCalls++; }
        @Override public void onImportAudioFile() { importAudioCalls++; }
        @Override public void onUndo() { undoCalls++; }
        @Override public void onRedo() { redoCalls++; }
        @Override public void onCopy() { copyCalls++; }
        @Override public void onCut() { cutCalls++; }
        @Override public void onPaste() { pasteCalls++; }
        @Override public void onDuplicate() { duplicateCalls++; }
        @Override public void onDeleteSelection() { deleteCalls++; }
        @Override public void onToggleSnap() { toggleSnapCalls++; }
        @Override public void onManagePlugins() { managePluginsCalls++; }
        @Override public void onOpenSettings() { settingsCalls++; }
        @Override public void onSwitchView(DawView v) { switchViewCalls++; }
        @Override public void onToggleBrowser() { toggleBrowserCalls++; }
        @Override public void onToggleHistory() { toggleHistoryCalls++; }
        @Override public void onToggleNotificationHistory() { toggleNotificationsCalls++; }
        @Override public void onToggleVisualizations() { toggleVisualizationsCalls++; }
        @Override public void onToggleToolbar() { toggleToolbarCalls++; }
        @Override public void onHelp() { helpCalls++; }
    }

    private KeyBindingManager freshKeyBindingManager() {
        Preferences prefs = Preferences.userRoot()
                .node("dawMenuBarTest_" + System.nanoTime());
        return new KeyBindingManager(prefs);
    }

    // ── Constructor null checks ──────────────────────────────────────────────

    @Test
    void shouldRejectNullHost() {
        assertThatThrownBy(() -> new DawMenuBarController(null, freshKeyBindingManager()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullKeyBindingManager() {
        assertThatThrownBy(() -> new DawMenuBarController(new StubHost(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ── Menu bar structure ───────────────────────────────────────────────────

    @Test
    void shouldBuildMenuBarWithFiveMenus() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            MenuBar bar = controller.build();

            assertThat(bar.getMenus()).hasSize(5);
        });
    }

    @Test
    void shouldHaveCorrectMenuTitles() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            MenuBar bar = controller.build();

            List<String> titles = bar.getMenus().stream()
                    .map(Menu::getText).toList();
            assertThat(titles).containsExactly(
                    "File", "Edit", "Plugins", "Window", "Help");
        });
    }

    @Test
    void shouldHaveDawMenuBarStyleClass() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            MenuBar bar = controller.build();

            assertThat(bar.getStyleClass()).contains("daw-menu-bar");
        });
    }

    // ── File menu ────────────────────────────────────────────────────────────

    @Test
    void fileMenuShouldHaveExpectedItems() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();

            Menu fileMenu = controller.getMenuBar().getMenus().getFirst();
            List<String> itemTexts = fileMenu.getItems().stream()
                    .filter(item -> !(item instanceof SeparatorMenuItem))
                    .map(MenuItem::getText)
                    .toList();

            assertThat(itemTexts).contains(
                    "New Project", "Save Project",
                    "Import Audio File\u2026");
        });
    }

    // ── Edit menu ────────────────────────────────────────────────────────────

    @Test
    void editMenuShouldHaveUndoRedoAndClipboardItems() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();

            Menu editMenu = controller.getMenuBar().getMenus().get(1);
            List<String> itemTexts = editMenu.getItems().stream()
                    .filter(item -> !(item instanceof SeparatorMenuItem))
                    .map(MenuItem::getText)
                    .toList();

            assertThat(itemTexts).contains(
                    "Undo", "Redo", "Copy", "Cut", "Paste",
                    "Duplicate", "Delete Selection", "Toggle Snap");
        });
    }

    // ── Window menu ──────────────────────────────────────────────────────────

    @Test
    void windowMenuShouldHaveViewSwitchItems() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();

            Menu windowMenu = controller.getMenuBar().getMenus().get(3);
            List<String> itemTexts = windowMenu.getItems().stream()
                    .filter(item -> !(item instanceof SeparatorMenuItem))
                    .map(MenuItem::getText)
                    .toList();

            assertThat(itemTexts).contains(
                    "Arrangement", "Mixer", "Editor",
                    "Telemetry", "Mastering",
                    "Toggle Browser", "Toggle Undo History",
                    "Toggle Toolbar");
        });
    }

    // ── Help menu ────────────────────────────────────────────────────────────

    @Test
    void helpMenuShouldHaveHelpItem() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();

            Menu helpMenu = controller.getMenuBar().getMenus().getLast();
            List<String> itemTexts = helpMenu.getItems().stream()
                    .filter(item -> !(item instanceof SeparatorMenuItem))
                    .map(MenuItem::getText)
                    .toList();

            assertThat(itemTexts).containsExactly("Help\u2026");
        });
    }

    // ── State synchronization ────────────────────────────────────────────────

    @Test
    void saveShouldBeDisabledWhenProjectNotDirty() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            host.dirty = false;
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();
            controller.syncMenuState();

            Menu fileMenu = controller.getMenuBar().getMenus().getFirst();
            MenuItem save = fileMenu.getItems().stream()
                    .filter(item -> "Save Project".equals(item.getText()))
                    .findFirst().orElseThrow();
            assertThat(save.isDisable()).isTrue();
        });
    }

    @Test
    void saveShouldBeEnabledWhenProjectDirty() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            host.dirty = true;
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();
            controller.syncMenuState();

            Menu fileMenu = controller.getMenuBar().getMenus().getFirst();
            MenuItem save = fileMenu.getItems().stream()
                    .filter(item -> "Save Project".equals(item.getText()))
                    .findFirst().orElseThrow();
            assertThat(save.isDisable()).isFalse();
        });
    }

    @Test
    void undoShouldBeDisabledWhenNoHistory() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            host.canUndo = false;
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();
            controller.syncMenuState();

            Menu editMenu = controller.getMenuBar().getMenus().get(1);
            MenuItem undo = editMenu.getItems().stream()
                    .filter(item -> "Undo".equals(item.getText()))
                    .findFirst().orElseThrow();
            assertThat(undo.isDisable()).isTrue();
        });
    }

    @Test
    void undoShouldBeEnabledWhenCanUndo() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            host.canUndo = true;
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();
            controller.syncMenuState();

            Menu editMenu = controller.getMenuBar().getMenus().get(1);
            MenuItem undo = editMenu.getItems().stream()
                    .filter(item -> "Undo".equals(item.getText()))
                    .findFirst().orElseThrow();
            assertThat(undo.isDisable()).isFalse();
        });
    }

    @Test
    void copyAndCutShouldBeDisabledWhenNoSelection() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            host.selection = false;
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();
            controller.syncMenuState();

            Menu editMenu = controller.getMenuBar().getMenus().get(1);
            MenuItem copy = editMenu.getItems().stream()
                    .filter(item -> "Copy".equals(item.getText()))
                    .findFirst().orElseThrow();
            MenuItem cut = editMenu.getItems().stream()
                    .filter(item -> "Cut".equals(item.getText()))
                    .findFirst().orElseThrow();
            assertThat(copy.isDisable()).isTrue();
            assertThat(cut.isDisable()).isTrue();
        });
    }

    @Test
    void pasteShouldBeDisabledWhenClipboardEmpty() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            host.clipboard = false;
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();
            controller.syncMenuState();

            Menu editMenu = controller.getMenuBar().getMenus().get(1);
            MenuItem paste = editMenu.getItems().stream()
                    .filter(item -> "Paste".equals(item.getText()))
                    .findFirst().orElseThrow();
            assertThat(paste.isDisable()).isTrue();
        });
    }

    @Test
    void pasteShouldBeEnabledWhenClipboardHasContent() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            host.clipboard = true;
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();
            controller.syncMenuState();

            Menu editMenu = controller.getMenuBar().getMenus().get(1);
            MenuItem paste = editMenu.getItems().stream()
                    .filter(item -> "Paste".equals(item.getText()))
                    .findFirst().orElseThrow();
            assertThat(paste.isDisable()).isFalse();
        });
    }

    // ── Menu item actions ────────────────────────────────────────────────────

    @Test
    void fileMenuNewProjectShouldDelegateToHost() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();

            Menu fileMenu = controller.getMenuBar().getMenus().getFirst();
            MenuItem newProject = fileMenu.getItems().stream()
                    .filter(item -> "New Project".equals(item.getText()))
                    .findFirst().orElseThrow();
            newProject.fire();

            assertThat(host.newProjectCalls).isEqualTo(1);
        });
    }

    @Test
    void editMenuUndoShouldDelegateToHost() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();

            Menu editMenu = controller.getMenuBar().getMenus().get(1);
            MenuItem undo = editMenu.getItems().stream()
                    .filter(item -> "Undo".equals(item.getText()))
                    .findFirst().orElseThrow();
            undo.fire();

            assertThat(host.undoCalls).isEqualTo(1);
        });
    }

    @Test
    void windowMenuShouldDelegateSwitchView() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();

            Menu windowMenu = controller.getMenuBar().getMenus().get(3);
            MenuItem arrangement = windowMenu.getItems().stream()
                    .filter(item -> "Arrangement".equals(item.getText()))
                    .findFirst().orElseThrow();
            arrangement.fire();

            assertThat(host.switchViewCalls).isEqualTo(1);
        });
    }

    @Test
    void helpMenuShouldDelegateToHost() throws Exception {
        runOnFxThread(() -> {
            StubHost host = new StubHost();
            DawMenuBarController controller =
                    new DawMenuBarController(host, freshKeyBindingManager());
            controller.build();

            Menu helpMenu = controller.getMenuBar().getMenus().getLast();
            MenuItem help = helpMenu.getItems().stream()
                    .filter(item -> item.getText().startsWith("Help"))
                    .findFirst().orElseThrow();
            help.fire();

            assertThat(host.helpCalls).isEqualTo(1);
        });
    }

    // ── Constants ────────────────────────────────────────────────────────────

    @Test
    void menuIconSizeShouldBe14() {
        assertThat(DawMenuBarController.MENU_ICON_SIZE).isEqualTo(14);
    }
}
