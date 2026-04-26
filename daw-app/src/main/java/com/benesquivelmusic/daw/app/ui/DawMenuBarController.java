package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import javafx.scene.control.MenuBar;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Builds and manages a traditional DAW menu bar with File, Edit, Tracks,
 * Plugins, Window, and Help menus.
 *
 * <p>Decomposed into three focused collaborators (issue: "Decompose
 * Remaining God-Class Controllers into Focused Services"):</p>
 * <ul>
 *   <li>{@link MenuConstructionService} — builds the menu hierarchy
 *       (geometry, ordering, icons, accelerators).</li>
 *   <li>{@link MenuEnablementPolicy} — pure-logic mapping from project
 *       state to menu item enable/disable flags.</li>
 *   <li>{@link Host} callback — owns action dispatch and project state
 *       queries.</li>
 * </ul>
 *
 * <p>This controller is now a thin facade that wires those collaborators
 * together. Dark-theme styling is achieved via the
 * {@code .daw-menu-bar} CSS class and its children.</p>
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
        void onOpenAudioSettings();
        void onActivateBuiltInPlugin(Class<? extends BuiltInDawPlugin> pluginClass);

        // Window actions
        void onSwitchView(DawView view);
        void onToggleBrowser();
        void onToggleHistory();
        void onToggleNotificationHistory();
        void onToggleVisualizations();

        // Track actions (Issue 568 — lane folding)
        void onToggleFoldFocusedTrack();
        void onToggleFoldSelectedTracks();
        void onFoldAllAutomation();

        // Help actions
        void onHelp();
    }

    private final Host host;
    private final MenuConstructionService constructionService;
    private final MenuBar menuBar;

    private MenuConstructionService.SyncableItems syncableItems;

    /**
     * Creates a new menu bar controller.
     *
     * @param host              the host providing actions and state queries
     * @param keyBindingManager the key binding manager for shortcut display
     */
    public DawMenuBarController(Host host, KeyBindingManager keyBindingManager) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.constructionService = new MenuConstructionService(host,
                Objects.requireNonNull(keyBindingManager, "keyBindingManager must not be null"));
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
        MenuConstructionService.Result result = constructionService.build();
        this.syncableItems = result.syncableItems();
        menuBar.getMenus().setAll(result.menus());
        syncMenuState();
        LOG.fine("DAW menu bar built with File, Edit, Tracks, Plugins, Window, Help menus");
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
        if (syncableItems == null) {
            return;
        }
        MenuEnablementPolicy.MenuEnablement enablement = MenuEnablementPolicy.compute(
                new MenuEnablementPolicy.MenuState(
                        host.isProjectDirty(),
                        host.canUndo(),
                        host.canRedo(),
                        host.hasClipboardContent(),
                        host.hasSelection(),
                        !host.project().getTracks().isEmpty()));

        syncableItems.saveItem().setDisable(enablement.saveDisabled());
        syncableItems.exportSessionItem().setDisable(enablement.exportSessionDisabled());

        syncableItems.undoItem().setDisable(enablement.undoDisabled());
        syncableItems.redoItem().setDisable(enablement.redoDisabled());
        syncableItems.copyItem().setDisable(enablement.copyDisabled());
        syncableItems.cutItem().setDisable(enablement.cutDisabled());
        syncableItems.pasteItem().setDisable(enablement.pasteDisabled());
        syncableItems.duplicateItem().setDisable(enablement.duplicateDisabled());
        syncableItems.deleteItem().setDisable(enablement.deleteDisabled());
    }
}
