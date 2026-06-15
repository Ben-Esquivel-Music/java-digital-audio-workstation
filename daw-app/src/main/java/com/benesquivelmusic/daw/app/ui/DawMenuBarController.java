package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import javafx.scene.control.MenuBar;

import java.util.Objects;
import java.util.function.BooleanSupplier;
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
 *   <li>{@link MenuActions} callback — owns action dispatch (menu / click /
 *       key are one intent, CONTROL_SYNCHRONIZATION_DESIGN_BOOK §2.8).</li>
 * </ul>
 *
 * <p>Story 293 — the former {@code Host} interface mixed action dispatch with
 * six cascade-feeding state-query methods. Per
 * CONTROL_SYNCHRONIZATION_DESIGN_BOOK §9 ("Callback-up Host interfaces for
 * cross-surface updates → use publish/subscribe") the state-query half is
 * retired: {@link #syncMenuState()} now reads enablement from live
 * {@link BooleanSupplier}s the controller is constructed with, and the residual
 * action interface is renamed {@link MenuActions} (it is legitimate intent
 * routing, not a cascade Host).</p>
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
     * Callback interface for menu actions — one method per user intent. A
     * transport/edit/view action triggered from a menu, a toolbar click, or a
     * keyboard shortcut all converge on the same method
     * (CONTROL_SYNCHRONIZATION_DESIGN_BOOK §2.8). Story 293 renamed this from
     * {@code Host} and removed the cascade-feeding state-query methods (those
     * are now constructor {@link BooleanSupplier}s read by
     * {@link #syncMenuState()}).
     */
    interface MenuActions {
        // File actions
        void onNewProject();
        void onOpenProject();
        void onSaveProject();
        void onRecentProjects();
        void onImportSession();
        void onExportSession();
        /**
         * Story 186 — Offline Render Queue. Opens the {@code RenderQueueView}
         * which displays every queued / running / completed render job and
         * surfaces per-job pause / resume / cancel controls. Default no-op
         * for tests.
         */
        default void onOpenRenderQueue() { }
        void onImportAudioFile();
        // Story 190 — Snapshot History Browser
        /** Open the snapshot history browser dialog. Default no-op for tests. */
        default void onOpenSnapshots() { }
        /** Capture an explicit user checkpoint of the current project. Default no-op for tests. */
        default void onCreateCheckpoint() { }

        // Story 189 — Project Archive (ZIP With Assets)
        /**
         * Bundle the current project plus every referenced asset into a
         * portable {@code .dawz} archive at a user-chosen location.
         * Default no-op for tests.
         */
        default void onArchiveProject() { }
        /**
         * Restore a {@code .dawz} archive into a user-chosen directory and
         * load the restored project. Default no-op for tests.
         */
        default void onRestoreFromArchive() { }

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
        /**
         * Story 191 — Auto-Backup Rotation. Opens
         * {@code BackupSettingsDialog} bound to the persisted retention
         * policy and applies it immediately on Apply. Default no-op for
         * tests.
         */
        default void onOpenBackupSettings() { }
        void onActivateBuiltInPlugin(Class<? extends BuiltInDawPlugin> pluginClass);

        // Window actions
        void onSwitchView(DawView view);
        void onToggleBrowser();
        void onToggleHistory();
        void onToggleNotificationHistory();
        void onToggleVisualizations();
        /**
         * Toggles the right-hand Session Manager dock (Project Manager Design
         * Book §4.3). Default no-op so non-window hosts need not implement it.
         */
        default void onToggleSessionManager() { }

        // Track actions (Issue 568 — lane folding)
        void onToggleFoldFocusedTrack();
        void onToggleFoldSelectedTracks();
        void onFoldAllAutomation();
        // Story 100 — track templates and channel-strip presets
        void onAddTrackFromTemplate();
        void onManageTemplates();
        // Story 035 — Track Freeze and Unfreeze for CPU Management
        void onFreezeFocusedTrack();
        void onUnfreezeFocusedTrack();
        void onFreezeSelectedTracks();
        void onUnfreezeSelectedTracks();

        // Clip actions (Story 042 — Time-Stretching and Pitch-Shifting)
        void onTimeStretchClip();
        void onPitchShiftClip();

        // QC actions (Story 175 — Immersive A/B Comparison)
        default void onOpenImmersiveAb() { }

        // Help actions
        void onHelp();
    }

    private final MenuConstructionService constructionService;
    private final MenuBar menuBar;

    /**
     * Story 293 — the cascade-feeding state queries that the former {@code Host}
     * exposed are now live {@link BooleanSupplier}s. {@link #syncMenuState()}
     * reads them straight from the authoritative sources (undo manager,
     * clipboard, selection model, project dirty flag, project track list) so the
     * menu enablement is derived, never poked across surfaces.
     */
    private final BooleanSupplier projectDirty;
    private final BooleanSupplier canUndo;
    private final BooleanSupplier canRedo;
    private final BooleanSupplier hasClipboardContent;
    private final BooleanSupplier hasSelection;
    private final BooleanSupplier hasTracks;

    private MenuConstructionService.SyncableItems syncableItems;

    /**
     * Creates a new menu bar controller.
     *
     * @param actions             the menu action dispatch callback
     * @param keyBindingManager   the key binding manager for shortcut display
     * @param projectDirty        live "project has unsaved changes" query
     * @param canUndo             live "undo available" query
     * @param canRedo             live "redo available" query
     * @param hasClipboardContent live "clipboard has content" query
     * @param hasSelection        live "a clip selection exists" query
     * @param hasTracks           live "project has at least one track" query
     */
    public DawMenuBarController(MenuActions actions,
                                KeyBindingManager keyBindingManager,
                                BooleanSupplier projectDirty,
                                BooleanSupplier canUndo,
                                BooleanSupplier canRedo,
                                BooleanSupplier hasClipboardContent,
                                BooleanSupplier hasSelection,
                                BooleanSupplier hasTracks) {
        Objects.requireNonNull(actions, "actions must not be null");
        this.constructionService = new MenuConstructionService(actions,
                Objects.requireNonNull(keyBindingManager, "keyBindingManager must not be null"));
        this.projectDirty = Objects.requireNonNull(projectDirty, "projectDirty must not be null");
        this.canUndo = Objects.requireNonNull(canUndo, "canUndo must not be null");
        this.canRedo = Objects.requireNonNull(canRedo, "canRedo must not be null");
        this.hasClipboardContent = Objects.requireNonNull(
                hasClipboardContent, "hasClipboardContent must not be null");
        this.hasSelection = Objects.requireNonNull(hasSelection, "hasSelection must not be null");
        this.hasTracks = Objects.requireNonNull(hasTracks, "hasTracks must not be null");
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
                        projectDirty.getAsBoolean(),
                        canUndo.getAsBoolean(),
                        canRedo.getAsBoolean(),
                        hasClipboardContent.getAsBoolean(),
                        hasSelection.getAsBoolean(),
                        hasTracks.getAsBoolean()));

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
