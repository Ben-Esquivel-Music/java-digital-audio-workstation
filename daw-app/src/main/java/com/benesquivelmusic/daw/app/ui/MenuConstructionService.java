package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginCategory;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCombination;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the JavaFX menu hierarchy (File, Edit, Tracks, Plugins, Window,
 * Help) for the DAW menu bar.
 *
 * <p>Extracted from {@link DawMenuBarController} so menu construction
 * (geometry, ordering, icons, accelerators) is separated from the
 * enable-state synchronization handled by {@link MenuEnablementPolicy}
 * and the action dispatch handled by the {@link DawMenuBarController.Host}
 * callback. This keeps the public {@code DawMenuBarController} below the
 * project's ~200-line soft cap for controller classes.</p>
 *
 * <p>Construction is a one-shot operation: each call to {@link #build}
 * returns a fresh {@link Result} containing the populated menus plus
 * direct references to the items whose enable state depends on project
 * state.</p>
 */
final class MenuConstructionService {

    /** Icon size for menu-item graphics. */
    static final double MENU_ICON_SIZE = DawMenuBarController.MENU_ICON_SIZE;

    private final DawMenuBarController.Host host;
    private final KeyBindingManager keyBindingManager;

    MenuConstructionService(DawMenuBarController.Host host,
                            KeyBindingManager keyBindingManager) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.keyBindingManager = Objects.requireNonNull(keyBindingManager,
                "keyBindingManager must not be null");
    }

    /**
     * Result of a {@link #build} invocation: the constructed top-level
     * menus, in display order, plus direct references to items that need
     * runtime enable-state synchronization.
     */
    record Result(List<Menu> menus, SyncableItems syncableItems) { }

    /**
     * References to the menu items whose enable state must be
     * synchronized with project state via {@link MenuEnablementPolicy}.
     */
    record SyncableItems(MenuItem saveItem,
                         MenuItem exportSessionItem,
                         MenuItem importAudioFileItem,
                         MenuItem undoItem,
                         MenuItem redoItem,
                         MenuItem copyItem,
                         MenuItem cutItem,
                         MenuItem pasteItem,
                         MenuItem duplicateItem,
                         MenuItem deleteItem) { }

    /** Builds a fresh top-level menu list. */
    Result build() {
        // File menu items needing state sync
        MenuItem saveItem = menuItem("Save Project", DawIcon.DOWNLOAD,
                DawAction.SAVE, host::onSaveProject);
        MenuItem exportSessionItem = menuItem("Export Session\u2026", DawIcon.UPLOAD,
                DawAction.EXPORT_SESSION, host::onExportSession);
        MenuItem importAudioFileItem = menuItem("Import Audio File\u2026", DawIcon.WAVEFORM,
                DawAction.IMPORT_AUDIO_FILE, host::onImportAudioFile);

        // Edit menu items needing state sync
        MenuItem undoItem = menuItem("Undo", DawIcon.UNDO,
                DawAction.UNDO, host::onUndo);
        MenuItem redoItem = menuItem("Redo", DawIcon.REDO,
                DawAction.REDO, host::onRedo);
        MenuItem copyItem = menuItem("Copy", DawIcon.COPY,
                DawAction.COPY, host::onCopy);
        MenuItem cutItem = menuItem("Cut", DawIcon.CUT,
                DawAction.CUT, host::onCut);
        MenuItem pasteItem = menuItem("Paste", DawIcon.PASTE,
                DawAction.PASTE, host::onPaste);
        MenuItem duplicateItem = menuItem("Duplicate", null,
                DawAction.DUPLICATE, host::onDuplicate);
        MenuItem deleteItem = menuItem("Delete Selection", DawIcon.DELETE,
                DawAction.DELETE_SELECTION, host::onDeleteSelection);

        SyncableItems sync = new SyncableItems(
                saveItem, exportSessionItem, importAudioFileItem,
                undoItem, redoItem, copyItem, cutItem, pasteItem,
                duplicateItem, deleteItem);

        List<Menu> menus = List.of(
                buildFileMenu(saveItem, exportSessionItem, importAudioFileItem),
                buildEditMenu(undoItem, redoItem, copyItem, cutItem,
                        pasteItem, duplicateItem, deleteItem),
                buildTracksMenu(),
                buildPluginsMenu(),
                buildWindowMenu(),
                buildHelpMenu()
        );

        return new Result(menus, sync);
    }

    // ── File Menu ────────────────────────────────────────────────────────────

    private Menu buildFileMenu(MenuItem saveItem,
                               MenuItem exportSessionItem,
                               MenuItem importAudioFileItem) {
        Menu fileMenu = new Menu("File");
        fileMenu.getStyleClass().add("daw-menu");

        MenuItem newProject = menuItem("New Project", DawIcon.FOLDER,
                DawAction.NEW_PROJECT, host::onNewProject);
        MenuItem openProject = menuItem("Open Project\u2026", DawIcon.FOLDER,
                DawAction.OPEN_PROJECT, host::onOpenProject);
        MenuItem recentProjects = menuItem("Recent Projects", DawIcon.HISTORY,
                null, host::onRecentProjects);
        MenuItem importSession = menuItem("Import Session\u2026", DawIcon.DOWNLOAD,
                DawAction.IMPORT_SESSION, host::onImportSession);

        fileMenu.getItems().addAll(
                newProject, openProject, recentProjects,
                new SeparatorMenuItem(),
                saveItem,
                new SeparatorMenuItem(),
                importSession, exportSessionItem,
                new SeparatorMenuItem(),
                importAudioFileItem
        );

        return fileMenu;
    }

    // ── Edit Menu ────────────────────────────────────────────────────────────

    private Menu buildEditMenu(MenuItem undoItem, MenuItem redoItem,
                               MenuItem copyItem, MenuItem cutItem,
                               MenuItem pasteItem, MenuItem duplicateItem,
                               MenuItem deleteItem) {
        Menu editMenu = new Menu("Edit");
        editMenu.getStyleClass().add("daw-menu");

        MenuItem toggleSnap = menuItem("Toggle Snap", DawIcon.SNAP,
                DawAction.TOGGLE_SNAP, host::onToggleSnap);
        MenuItem settings = menuItem("Settings\u2026", DawIcon.SETTINGS,
                DawAction.OPEN_SETTINGS, host::onOpenSettings);
        MenuItem audioSettings = menuItem("Audio Settings\u2026", DawIcon.HEADPHONES,
                null, host::onOpenAudioSettings);

        editMenu.getItems().addAll(
                undoItem, redoItem,
                new SeparatorMenuItem(),
                copyItem, cutItem, pasteItem, duplicateItem,
                new SeparatorMenuItem(),
                deleteItem,
                new SeparatorMenuItem(),
                toggleSnap,
                new SeparatorMenuItem(),
                settings,
                audioSettings
        );

        return editMenu;
    }

    // ── Tracks Menu ──────────────────────────────────────────────────────────

    private Menu buildTracksMenu() {
        Menu tracksMenu = new Menu("Tracks");
        tracksMenu.getStyleClass().add("daw-menu");

        MenuItem foldFocused = menuItem("Toggle Lane Fold (Focused Track)", DawIcon.AUTOMATION,
                DawAction.TOGGLE_FOLD_FOCUSED_TRACK, host::onToggleFoldFocusedTrack);
        MenuItem foldSelected = menuItem("Toggle Lane Fold (Selected Tracks)", DawIcon.AUTOMATION,
                DawAction.TOGGLE_FOLD_SELECTED_TRACKS, host::onToggleFoldSelectedTracks);
        MenuItem foldAllAutomation = menuItem("Toggle Fold All Automation Lanes", DawIcon.AUTOMATION,
                DawAction.FOLD_ALL_AUTOMATION, host::onFoldAllAutomation);

        tracksMenu.getItems().addAll(foldFocused, foldSelected,
                new SeparatorMenuItem(), foldAllAutomation);

        return tracksMenu;
    }

    // ── Plugins Menu ─────────────────────────────────────────────────────────

    private Menu buildPluginsMenu() {
        Menu pluginsMenu = new Menu("Plugins");
        pluginsMenu.getStyleClass().add("daw-menu");

        // Discover built-in plugin metadata and group by category (preserving enum order)
        List<BuiltInDawPlugin.MenuEntry> entries = BuiltInDawPlugin.menuEntries();
        Map<BuiltInPluginCategory, List<BuiltInDawPlugin.MenuEntry>> grouped = new LinkedHashMap<>();
        for (BuiltInPluginCategory cat : BuiltInPluginCategory.values()) {
            List<BuiltInDawPlugin.MenuEntry> inCategory = entries.stream()
                    .filter(e -> e.category() == cat)
                    .toList();
            if (!inCategory.isEmpty()) {
                grouped.put(cat, inCategory);
            }
        }

        boolean firstGroup = true;
        for (var entry : grouped.entrySet()) {
            if (!firstGroup) {
                pluginsMenu.getItems().add(new SeparatorMenuItem());
            }
            firstGroup = false;
            for (BuiltInDawPlugin.MenuEntry menuEntry : entry.getValue()) {
                DawIcon icon = DawIcon.fromFileName(menuEntry.icon()).orElse(null);
                MenuItem item = menuItem(menuEntry.label(), icon,
                        null, () -> host.onActivateBuiltInPlugin(menuEntry.pluginClass()));
                pluginsMenu.getItems().add(item);
            }
        }

        if (!entries.isEmpty()) {
            pluginsMenu.getItems().add(new SeparatorMenuItem());
        }

        MenuItem managePlugins = menuItem("Plugin Manager\u2026", DawIcon.EQ,
                null, host::onManagePlugins);
        pluginsMenu.getItems().add(managePlugins);

        return pluginsMenu;
    }

    // ── Window Menu ──────────────────────────────────────────────────────────

    private Menu buildWindowMenu() {
        Menu windowMenu = new Menu("Window");
        windowMenu.getStyleClass().add("daw-menu");

        MenuItem arrangement = menuItem("Arrangement", DawIcon.TIMELINE,
                DawAction.VIEW_ARRANGEMENT, () -> host.onSwitchView(DawView.ARRANGEMENT));
        MenuItem mixer = menuItem("Mixer", DawIcon.MIXER,
                DawAction.VIEW_MIXER, () -> host.onSwitchView(DawView.MIXER));
        MenuItem editor = menuItem("Editor", DawIcon.WAVEFORM,
                DawAction.VIEW_EDITOR, () -> host.onSwitchView(DawView.EDITOR));
        MenuItem mastering = menuItem("Mastering", DawIcon.LIMITER,
                DawAction.VIEW_MASTERING, () -> host.onSwitchView(DawView.MASTERING));

        MenuItem toggleBrowser = menuItem("Toggle Browser", DawIcon.LIBRARY,
                DawAction.TOGGLE_BROWSER, host::onToggleBrowser);
        MenuItem toggleHistory = menuItem("Toggle Undo History", DawIcon.HISTORY,
                DawAction.TOGGLE_HISTORY, host::onToggleHistory);
        MenuItem toggleNotifications = menuItem("Toggle Notifications", DawIcon.BELL_RING,
                DawAction.TOGGLE_NOTIFICATION_HISTORY, host::onToggleNotificationHistory);
        MenuItem toggleViz = menuItem("Toggle Visualizations", DawIcon.SPECTRUM,
                DawAction.TOGGLE_VISUALIZATIONS, host::onToggleVisualizations);

        windowMenu.getItems().addAll(
                arrangement, mixer, editor, mastering,
                new SeparatorMenuItem(),
                toggleBrowser, toggleHistory, toggleNotifications, toggleViz
        );

        return windowMenu;
    }

    // ── Help Menu ────────────────────────────────────────────────────────────

    private Menu buildHelpMenu() {
        Menu helpMenu = new Menu("Help");
        helpMenu.getStyleClass().add("daw-menu");

        MenuItem helpItem = menuItem("Help\u2026", DawIcon.INFO,
                null, host::onHelp);

        helpMenu.getItems().addAll(helpItem);

        return helpMenu;
    }

    // ── Menu item factory ────────────────────────────────────────────────────

    private MenuItem menuItem(String text, DawIcon icon,
                              DawAction action, Runnable handler) {
        MenuItem item = new MenuItem(text);
        if (icon != null) {
            item.setGraphic(IconNode.of(icon, MENU_ICON_SIZE));
        }
        if (action != null) {
            Optional<KeyCombination> binding = keyBindingManager.getBinding(action);
            binding.ifPresent(item::setAccelerator);
        }
        item.setOnAction(_ -> handler.run());
        return item;
    }
}
