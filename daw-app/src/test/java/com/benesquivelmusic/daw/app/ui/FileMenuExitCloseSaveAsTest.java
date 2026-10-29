package com.benesquivelmusic.daw.app.ui;

import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Story 333, Goals — Tests, "Menu-existence" (design §4.1; it feeds story
 * 345's menu-truth conformance): File ▸ Save As…, File ▸ Close Project and
 * File ▸ Exit exist and land on live handlers. Save As follows Save
 * Project, with no shortcut; Close Project follows Recent Projects, and
 * Exit ends the File menu alone, after a separator, with Shortcut+Shift+W
 * and Shortcut+Q (ruling R12 #5; the studio engineer's stage-7 S2);
 * each fires its own host method, and the shortcuts and the Command Palette
 * reach the same host methods (Control Synchronization book §2.8). Close
 * Project is disabled for a pristine Untitled project and enabled with a
 * folder or with unsaved changes — and the palette's entry with it, carrying
 * the ruled reason only while disabled (ruling R10; ruling 2 OQ-2). The
 * labels come from the bundle.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FileMenuExitCloseSaveAsTest {

    private static final String NOTHING_TO_CLOSE =
            "Nothing to close — this project has never been saved and has no changes.";

    private Preferences preferences;

    @BeforeEach
    void keyBindings() {
        preferences = Preferences.userRoot().node("daw-file-menu-doors-" + UUID.randomUUID());
    }

    @AfterEach
    void forgetKeyBindings() throws Exception {
        preferences.removeNode();
    }

    @Test
    void saveAsFollowsSaveProjectCloseProjectFollowsRecentProjectsAndExitEndsTheFileMenuAlone() throws Exception {
        DawMenuBarControllerTest.StubHost host = new DawMenuBarControllerTest.StubHost();
        Menu file = onFx(() -> menuBar(host).build().getMenus().getFirst());

        List<MenuItem> items = file.getItems();
        List<String> texts = items.stream().filter(item -> !(item instanceof SeparatorMenuItem))
                .map(MenuItem::getText).toList();
        assertThat(texts.getLast()).as("Exit is the File menu's last item").isEqualTo("Exit");
        assertThat(items.get(items.size() - 2)).as("alone, after a separator").isInstanceOf(SeparatorMenuItem.class);
        assertThat(texts.subList(0, 4)).as("Close Project with New, Open and Recent (the studio engineer's stage-7 S2)")
                .containsExactly("New Project", "Open Project…", "Recent Projects", "Close Project");
        assertThat(texts.get(texts.indexOf("Save Project") + 1)).isEqualTo("Save As…");
        MenuItem closeProject = item(file, "Close Project");
        assertThat(closeProject.getAccelerator()).isEqualTo(
                new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        assertThat(item(file, "Exit").getAccelerator())
                .isEqualTo(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        assertThat(item(file, "Save As…").getAccelerator()).as("ruling R12 #5: no Save As shortcut").isNull();
        assertThat(List.of(closeProject.getText(), item(file, "Exit").getText(), item(file, "Save As…").getText()))
                .as("the labels are the bundle's")
                .containsExactly(ProjectCloseText.text("menu.file.closeProject"), ProjectCloseText.text("menu.file.exit"),
                        ProjectCloseText.text("menu.file.saveAs"));
    }

    @Test
    void eachOfTheThreeItemsFiresItsOwnHostMethod() throws Exception {
        DawMenuBarControllerTest.StubHost host = new DawMenuBarControllerTest.StubHost();
        host.closeProjectAvailable = true;
        Menu file = onFx(() -> menuBar(host).build().getMenus().getFirst());

        runOnFx(() -> item(file, "Save As…").fire());
        assertThat(List.of(host.saveProjectAsCalls, host.closeProjectCalls, host.exitCalls, host.saveProjectCalls))
                .containsExactly(1, 0, 0, 0);
        runOnFx(() -> item(file, "Close Project").fire());
        assertThat(List.of(host.saveProjectAsCalls, host.closeProjectCalls, host.exitCalls)).containsExactly(1, 1, 0);
        runOnFx(() -> item(file, "Exit").fire());
        assertThat(List.of(host.saveProjectAsCalls, host.closeProjectCalls, host.exitCalls)).containsExactly(1, 1, 1);
    }

    @Test
    void closeProjectIsEnabledExactlyWhileThereIsSomethingToCloseAndThePalettesEntryWithIt() throws Exception {
        DawMenuBarControllerTest.StubHost host = new DawMenuBarControllerTest.StubHost();
        KeyBindingManager keys = new KeyBindingManager(preferences);
        Map<DawAction, Runnable> handlers = onFx(() -> new KeyboardShortcutController(keys,
                mock(KeyboardShortcutController.Host.class)).buildActionHandlers());
        DawMenuBarController menus = onFx(() -> menuBar(host));
        Menu file = onFx(() -> menus.build().getMenus().getFirst());
        MenuItem closeProject = item(file, "Close Project");

        // A pristine Untitled project: nothing to close.
        runOnFx(menus::syncMenuState);
        assertThat(closeProject.isDisable()).isTrue();
        CommandPaletteEntry disabled = closeProjectEntry(handlers, keys, () -> host.closeProjectAvailable);
        assertThat(disabled.enabled()).as("the palette follows the menu (CS book §2.8)").isFalse();
        assertThat(disabled.disabledReason()).as("ruling 2 OQ-2's exact text").isEqualTo(NOTHING_TO_CLOSE);
        assertThat(disabled.shortcut()).isEqualTo(keys.getDisplayText(DawAction.CLOSE_PROJECT));

        // A folder open, or unsaved changes: something to close.
        host.closeProjectAvailable = true;
        runOnFx(menus::syncMenuState);
        assertThat(closeProject.isDisable()).isFalse();
        CommandPaletteEntry enabled = closeProjectEntry(handlers, keys, () -> host.closeProjectAvailable);
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.disabledReason()).as("the reason shows only while disabled").isEmpty();
        assertThat(item(file, "Exit").isDisable()).as("Exit is always there").isFalse();
        List<CommandPaletteEntry> entries = onFx(() -> MainController.commandPaletteEntries(handlers, keys,
                () -> false));
        assertThat(entries).filteredOn(entry -> entry.id().equals("EXIT")).singleElement()
                .satisfies(exit -> assertThat(exit.enabled()).isTrue());
    }

    @Test
    void theShortcutsAndThePaletteReachTheSameHostMethodsAsTheMenu() throws Exception {
        KeyBindingManager keys = new KeyBindingManager(preferences);
        KeyboardShortcutController.Host host = mock(KeyboardShortcutController.Host.class);
        runOnFx(() -> {
            KeyboardShortcutController shortcuts = new KeyboardShortcutController(keys, host);
            Scene scene = new Scene(new StackPane());
            shortcuts.register(scene);
            scene.getAccelerators().get(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN,
                    KeyCombination.SHIFT_DOWN)).run();
            scene.getAccelerators().get(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN)).run();
            verify(host).onCloseProject();
            verify(host).onExit();
            List<CommandPaletteEntry> entries = MainController.commandPaletteEntries(shortcuts.buildActionHandlers(),
                    keys, () -> true);
            entries.stream().filter(entry -> List.of("CLOSE_PROJECT", "EXIT", "SAVE_AS").contains(entry.id()))
                    .forEach(entry -> entry.handler().run());
            verify(host, org.mockito.Mockito.times(2)).onCloseProject();
            verify(host, org.mockito.Mockito.times(2)).onExit();
            verify(host).onSaveProjectAs();
            verifyNoMoreInteractions(host);
        });
    }

    private DawMenuBarController menuBar(DawMenuBarControllerTest.StubHost host) {
        return new DawMenuBarController(host, new KeyBindingManager(preferences),
                () -> false, () -> false, () -> false, () -> false, () -> false, () -> false, () -> false,
                () -> host.closeProjectAvailable);
    }

    private static CommandPaletteEntry closeProjectEntry(Map<DawAction, Runnable> handlers, KeyBindingManager keys,
                                                         java.util.function.BooleanSupplier available) throws Exception {
        return onFx(() -> MainController.commandPaletteEntries(handlers, keys, available).stream()
                .filter(entry -> entry.id().equals(DawAction.CLOSE_PROJECT.name())).findFirst().orElseThrow());
    }

    private static MenuItem item(Menu menu, String text) {
        return menu.getItems().stream().filter(candidate -> text.equals(candidate.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + text + " in the File menu"));
    }
}
