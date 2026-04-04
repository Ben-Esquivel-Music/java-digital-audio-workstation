package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.project.DawProject;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCombination;

import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Builds and manages a traditional DAW menu bar with File, Edit, Plugins,
 * Window, and Help menus.
 *
 * <p>Menu items are synchronized to the current project state so that
 * un-actionable items (e.g., Undo when the history is empty, or Save
 * when the project is not dirty) are disabled automatically.</p>
 *
 * <p>Dark-theme styling is achieved via the {@code .daw-menu-bar} CSS class
 * and its children.</p>
 */
public final class DawMenuBarController {

    private static final Logger LOG = Logger.getLogger(DawMenuBarController.class.getName());

    /** Icon size for menu-item graphics. */
    static final double MENU_ICON_SIZE = 14;

    /**
     * Callback interface for menu actions. Implemented by the host
     * controller that owns the project and UI state.
     */
    interface Host {
        DawProject project();
        boolean isProjectDirty();
        boolean canUndo();
        boolean canRedo();
        boolean hasClipboardContent();
        boolean hasSelection();
        DawView activeView();

        // File actions
        void onNewProject();
        void onOpenProject();
        void onSaveProject();
        void onRecentProjects();
        void onImportSession();
        void onExportSession();
        void onImportAudioFile();

        // Edit actions
        void onUndo();
        void onRedo();
        void onCopy();
        void onCut();
        void onPaste();
        void onDuplicate();
        void onDeleteSelection();
        void onToggleSnap();

        // Plugin actions
        void onManagePlugins();
        void onOpenSettings();

        // Window actions
        void onSwitchView(DawView view);
        void onToggleBrowser();
        void onToggleHistory();
        void onToggleNotificationHistory();
        void onToggleVisualizations();
        void onToggleToolbar();

        // Help actions
        void onHelp();
    }

    private final Host host;
    private final KeyBindingManager keyBindingManager;
    private final MenuBar menuBar;

    // File menu items needing state sync
    private MenuItem saveItem;
    private MenuItem exportSessionItem;
    private MenuItem importAudioFileItem;

    // Edit menu items needing state sync
    private MenuItem undoItem;
    private MenuItem redoItem;
    private MenuItem copyItem;
    private MenuItem cutItem;
    private MenuItem pasteItem;
    private MenuItem duplicateItem;
    private MenuItem deleteItem;

    /**
     * Creates a new menu bar controller.
     *
     * @param host            the host providing actions and state queries
     * @param keyBindingManager the key binding manager for shortcut display
     */
    public DawMenuBarController(Host host, KeyBindingManager keyBindingManager) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.keyBindingManager = Objects.requireNonNull(keyBindingManager,
                "keyBindingManager must not be null");
        this.menuBar = new MenuBar();
        this.menuBar.getStyleClass().add("daw-menu-bar");
        this.menuBar.setUseSystemMenuBar(false);
    }

    /**
     * Builds and returns the fully populated {@link MenuBar}.
     *
     * @return the menu bar ready to be added to the scene graph
     */
    public MenuBar build() {
        menuBar.getMenus().clear();
        menuBar.getMenus().addAll(
                buildFileMenu(),
                buildEditMenu(),
                buildPluginsMenu(),
                buildWindowMenu(),
                buildHelpMenu()
        );
        syncMenuState();
        LOG.fine("DAW menu bar built with File, Edit, Plugins, Window, Help menus");
        return menuBar;
    }

    /**
     * Returns the underlying {@link MenuBar}.
     *
     * @return the menu bar
     */
    public MenuBar getMenuBar() {
        return menuBar;
    }

    /**
     * Synchronizes menu item enabled/disabled states with the current
     * project state. Call this whenever the project state changes
     * (e.g., after undo/redo, save, selection change).
     */
    public void syncMenuState() {
        boolean dirty = host.isProjectDirty();
        boolean canUndo = host.canUndo();
        boolean canRedo = host.canRedo();
        boolean hasClipboard = host.hasClipboardContent();
        boolean hasSel = host.hasSelection();
        boolean hasTracks = !host.project().getTracks().isEmpty();

        saveItem.setDisable(!dirty);
        exportSessionItem.setDisable(!hasTracks);

        undoItem.setDisable(!canUndo);
        redoItem.setDisable(!canRedo);
        copyItem.setDisable(!hasSel);
        cutItem.setDisable(!hasSel);
        pasteItem.setDisable(!hasClipboard);
        duplicateItem.setDisable(!hasSel);
        deleteItem.setDisable(!hasSel);
    }

    // ── File Menu ────────────────────────────────────────────────────────────

    private Menu buildFileMenu() {
        Menu fileMenu = new Menu("File");
        fileMenu.getStyleClass().add("daw-menu");

        MenuItem newProject = menuItem("New Project", DawIcon.FOLDER,
                DawAction.NEW_PROJECT, host::onNewProject);
        MenuItem openProject = menuItem("Open Project\u2026", DawIcon.FOLDER,
                DawAction.OPEN_PROJECT, host::onOpenProject);
        MenuItem recentProjects = menuItem("Recent Projects", DawIcon.HISTORY,
                null, host::onRecentProjects);

        saveItem = menuItem("Save Project", DawIcon.DOWNLOAD,
                DawAction.SAVE, host::onSaveProject);

        MenuItem importSession = menuItem("Import Session\u2026", DawIcon.DOWNLOAD,
                DawAction.IMPORT_SESSION, host::onImportSession);
        exportSessionItem = menuItem("Export Session\u2026", DawIcon.UPLOAD,
                DawAction.EXPORT_SESSION, host::onExportSession);

        importAudioFileItem = menuItem("Import Audio File\u2026", DawIcon.WAVEFORM,
                DawAction.IMPORT_AUDIO_FILE, host::onImportAudioFile);

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

    private Menu buildEditMenu() {
        Menu editMenu = new Menu("Edit");
        editMenu.getStyleClass().add("daw-menu");

        undoItem = menuItem("Undo", DawIcon.UNDO,
                DawAction.UNDO, host::onUndo);
        redoItem = menuItem("Redo", DawIcon.REDO,
                DawAction.REDO, host::onRedo);

        copyItem = menuItem("Copy", DawIcon.COPY,
                DawAction.COPY, host::onCopy);
        cutItem = menuItem("Cut", DawIcon.CUT,
                DawAction.CUT, host::onCut);
        pasteItem = menuItem("Paste", DawIcon.PASTE,
                DawAction.PASTE, host::onPaste);
        duplicateItem = menuItem("Duplicate", null,
                DawAction.DUPLICATE, host::onDuplicate);
        deleteItem = menuItem("Delete Selection", DawIcon.DELETE,
                DawAction.DELETE_SELECTION, host::onDeleteSelection);

        MenuItem toggleSnap = menuItem("Toggle Snap", DawIcon.SNAP,
                DawAction.TOGGLE_SNAP, host::onToggleSnap);

        editMenu.getItems().addAll(
                undoItem, redoItem,
                new SeparatorMenuItem(),
                copyItem, cutItem, pasteItem, duplicateItem,
                new SeparatorMenuItem(),
                deleteItem,
                new SeparatorMenuItem(),
                toggleSnap
        );

        return editMenu;
    }

    // ── Plugins Menu ─────────────────────────────────────────────────────────

    private Menu buildPluginsMenu() {
        Menu pluginsMenu = new Menu("Plugins");
        pluginsMenu.getStyleClass().add("daw-menu");

        MenuItem managePlugins = menuItem("Plugin Manager\u2026", DawIcon.EQ,
                null, host::onManagePlugins);
        MenuItem settings = menuItem("Settings\u2026", DawIcon.SETTINGS,
                DawAction.OPEN_SETTINGS, host::onOpenSettings);

        pluginsMenu.getItems().addAll(managePlugins, new SeparatorMenuItem(), settings);

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
        MenuItem telemetry = menuItem("Telemetry", DawIcon.SURROUND,
                DawAction.VIEW_TELEMETRY, () -> host.onSwitchView(DawView.TELEMETRY));
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
        MenuItem toggleToolbar = menuItem("Toggle Toolbar", DawIcon.COLLAPSE,
                DawAction.TOGGLE_TOOLBAR, host::onToggleToolbar);

        windowMenu.getItems().addAll(
                arrangement, mixer, editor, telemetry, mastering,
                new SeparatorMenuItem(),
                toggleBrowser, toggleHistory, toggleNotifications, toggleViz,
                new SeparatorMenuItem(),
                toggleToolbar
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

    /**
     * Creates a styled {@link MenuItem} with an optional icon, optional
     * accelerator resolved from the key binding manager, and an action handler.
     *
     * @param text   the menu item text
     * @param icon   the icon to display (may be {@code null})
     * @param action the {@link DawAction} for shortcut lookup (may be {@code null})
     * @param handler the action handler
     * @return the configured menu item
     */
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
