package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Coordinates view switching, edit-tool selection, snap/grid controls, and
 * zoom controls for the main DAW window.
 *
 * <p>Extracted from {@link MainController} to isolate the "what is the user
 * currently looking at and editing" concern into a dedicated, independently
 * testable class. All dependencies are received via constructor injection.</p>
 */
final class ViewNavigationController {

    private static final Logger LOG = Logger.getLogger(ViewNavigationController.class.getName());

    /**
     * Callback interface implemented by the host controller to provide
     * state and coordination methods that remain in the top-level controller.
     */
    interface Host {
        DawProject project();
        UndoManager undoManager();
        void onEditorTrim();
        void onEditorFadeIn();
        void onEditorFadeOut();
        void markProjectDirty();

        // ── Workshop view (story 281) ─────────────────────────────────────
        /**
         * @return the shared {@link com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel}
         *         held by {@code InspectorDrawer}, or {@code null} if the
         *         inspector has not been wired yet — Workshop binds its
         *         right-pane plugin focus to this single source of truth
         *         (story 272)
         */
        com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel inspectorSelectionModel();

        // ── Performance Stage (story 280) ─────────────────────────────────
        /**
         * @return the {@code Messages} resource bundle for Performance
         *         Stage user-facing strings (Skill §14)
         */
        java.util.ResourceBundle messages();
        /**
         * @return the standard toolbar transport time display {@link Label}
         *         whose text continues updating while hidden (hide-not-unload);
         *         the Performance Stage clock binds to its {@code textProperty}
         */
        Label timeDisplay();
        /** Toggle play / pause — drives the same transport as the toolbar. */
        void onPlay();
        /** Stop transport — drives the same transport as the toolbar. */
        void onStop();
        /** Toggle record-arm — drives the same transport as the toolbar. */
        void onRecord();
        /** Toggle loop — drives the same transport as the toolbar. */
        void onToggleLoop();
        /** Open the Audio Settings dialog (Performance Stage ☰ overlay). */
        void onOpenAudioSettings();
        /** New project — Performance Stage ☰ file sub-overlay. */
        void onNewProject();
        /** Open project — Performance Stage ☰ file sub-overlay. */
        void onOpenProject();
        /** Save project — Performance Stage ☰ file sub-overlay. */
        void onSaveProject();
        /** Recent projects — Performance Stage ☰ file sub-overlay. */
        void onRecentProjects();
    }

    // ── Dependencies ─────────────────────────────────────────────────────────

    private final BorderPane rootPane;
    private final Label statusBarLabel;
    private final ToolbarStateStore toolbarStateStore;
    private final Host host;

    // Snap button (top toolbar)
    private final Button snapButton;

    // ── State ────────────────────────────────────────────────────────────────

    /** Caches each view's content node so switching back preserves state. */
    private final Map<DawView, Node> viewCache = new EnumMap<>(DawView.class);
    /** The currently active view. */
    private DawView activeView;
    /** The mixer view panel — refreshed when tracks are added or removed. */
    private MixerView mixerView;
    /** The editor view panel — shows MIDI piano roll or audio waveform. */
    private EditorView editorView;
    /** The mastering view panel — mastering chain with presets and A/B comparison. */
    private MasteringView masteringView;
    /**
     * The Workshop view (story 281, UI Design Book §4 Concept F). Lazily
     * constructed on first switch to {@link DawView#WORKSHOP} because it
     * needs the inspector selection model, which is wired after this
     * controller is built. Set once, then cached in {@link #viewCache}.
     */
    private com.benesquivelmusic.daw.app.ui.views.WorkshopView workshopView;

    /**
     * Story 281 Task 2 — selection-driven push of the focused
     * plugin GUI / clip-detail editor into the Workshop right pane.
     * Built lazily alongside {@link #workshopView} on first switch to
     * {@link DawView#WORKSHOP}; held here so it can outlive the user
     * exiting and re-entering Workshop (the cache survives).
     */
    private com.benesquivelmusic.daw.app.ui.views.WorkshopSelectionHostController
            workshopSelectionHostController;

    /** The currently active edit tool. */
    private EditTool activeEditTool;

    /** Optional callback invoked after the active tool changes. */
    private Runnable onEditToolChanged;

    /** Optional callback invoked after the active view changes. */
    private Runnable onViewChanged;

    /** Whether snap-to-grid is enabled. */
    private boolean snapEnabled;
    /** The currently active grid resolution. */
    private GridResolution gridResolution;

    /** Per-view zoom levels — preserved when switching between views. */
    private final Map<DawView, ZoomLevel> viewZoomLevels = new EnumMap<>(DawView.class);

    // ── Performance Stage state (story 280) ──────────────────────────────────

    /**
     * The active Performance Stage view, or {@code null} when the standard
     * chrome is showing. Non-null exactly while
     * {@link #isPerformanceStageActive()} is {@code true}.
     */
    private com.benesquivelmusic.daw.app.ui.views.PerformanceStageView performanceStageView;
    /**
     * The standard view that was active when Performance Stage was entered,
     * so {@code Esc} / "Exit Performance Stage" can restore it.
     */
    private DawView preStageView;
    /**
     * Snapshot of the standard chrome ({@code BorderPane} top / left /
     * right / center) taken on activation and restored on deactivation.
     *
     * <p>Lifecycle decision — the standard chrome is <strong>hidden, not
     * unloaded</strong>: the nodes are detached from the {@code BorderPane}
     * but kept alive in these fields, so exiting Performance Stage restores
     * the arrangement view with all its state (scroll, selection, zoom)
     * intact and incurs no rebuild cost. Unloading would force a full
     * rebuild of the toolbar / track list / inspector on every exit for no
     * benefit.</p>
     */
    private Node savedTop;
    private Node savedLeft;
    private Node savedRight;
    private Node savedCenter;
    /** {@code Esc} filter installed on the scene while the stage is active. */
    private javafx.event.EventHandler<javafx.scene.input.KeyEvent> stageEscFilter;

    ViewNavigationController(BorderPane rootPane,
                             Label statusBarLabel,
                             ToolbarStateStore toolbarStateStore,
                             Button snapButton,
                             DawView initialView,
                             EditTool initialEditTool,
                             boolean initialSnapEnabled,
                             GridResolution initialGridResolution,
                             Host host) {
        this.rootPane = Objects.requireNonNull(rootPane, "rootPane must not be null");
        this.statusBarLabel = Objects.requireNonNull(statusBarLabel, "statusBarLabel must not be null");
        this.toolbarStateStore = Objects.requireNonNull(toolbarStateStore, "toolbarStateStore must not be null");
        this.snapButton = Objects.requireNonNull(snapButton, "snapButton must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");

        this.activeView = Objects.requireNonNull(initialView, "initialView must not be null");
        this.activeEditTool = Objects.requireNonNull(initialEditTool, "initialEditTool must not be null");
        this.snapEnabled = initialSnapEnabled;
        this.gridResolution = Objects.requireNonNull(initialGridResolution, "initialGridResolution must not be null");
    }

    // ── View navigation ──────────────────────────────────────────────────────

    /**
     * Sets up the view cache with the initial arrangement content and placeholder
     * nodes for the mixer and editor views, then wires the sidebar view buttons.
     */
    void initializeViewNavigation() {
        // Cache the current center content as the arrangement view
        viewCache.put(DawView.ARRANGEMENT, rootPane.getCenter());

        // Mixer view — real channel-strip mixer panel
        mixerView = new MixerView(host.project(), host.undoManager());
        viewCache.put(DawView.MIXER, mixerView);

        // Editor view — MIDI piano-roll / audio waveform editor panel
        editorView = new EditorView();
        editorView.setActiveEditTool(activeEditTool);
        editorView.setOnToolChanged(this::selectEditTool);
        editorView.setOnTrimAction(host::onEditorTrim);
        editorView.setOnFadeInAction(host::onEditorFadeIn);
        editorView.setOnFadeOutAction(host::onEditorFadeOut);
        editorView.setSnapState(snapEnabled, gridResolution,
                host.project().getTransport().getTimeSignatureNumerator());
        viewCache.put(DawView.EDITOR, editorView);

        // Mastering view — mastering chain with presets and A/B comparison
        masteringView = new MasteringView();
        viewCache.put(DawView.MASTERING, masteringView);

        // Restore persisted active view (activeView was set in the constructor)
        if (activeView != DawView.ARRANGEMENT) {
            rootPane.setCenter(viewCache.get(activeView));
        }
    }

    /**
     * Switches the center content of the main {@link BorderPane} to the given view.
     *
     * <p>Each view's content node is created once and cached so switching back
     * preserves state (scroll position, selection, etc.).</p>
     *
     * @param view the view to activate
     */
    void switchView(DawView view) {
        // Story 280 — Performance Stage is a mode, not a centre-content
        // swap. F11 / the menu toggle it: requesting it while active exits
        // back to the previously active standard view.
        if (view == DawView.PERFORMANCE_STAGE) {
            if (isPerformanceStageActive()) {
                deactivatePerformanceStage();
            } else {
                activatePerformanceStage();
            }
            return;
        }
        // Switching to a standard view while the stage is active first
        // tears the stage down so the chrome is restored before the swap.
        if (isPerformanceStageActive()) {
            deactivatePerformanceStage();
        }
        if (view == activeView) {
            return;
        }
        // Story 281 — re-parent the arrangement node into Workshop's left
        // pane on entry, and pull it back into the standard ARRANGEMENT
        // slot on exit. This honours the story's "reuse existing
        // arrangement panel components verbatim" rule: there is only one
        // arrangement Node, owned by viewCache.get(ARRANGEMENT), and it
        // floats between the two homes.
        DawView previousView = activeView;
        if (previousView == DawView.WORKSHOP && workshopView != null) {
            // Pull arrangement back out of Workshop's left pane before
            // switching elsewhere.
            workshopView.setArrangementContent(null);
            // Don't clear clip detail on exit — the selection model still
            // holds the clip reference and applyPendingOnWorkshopActivation
            // skips re-applying an unchanged selection (lastApplied memo).
            // Instead, reset the controller's lastApplied so the next
            // activation unconditionally re-applies the current selection.
            if (workshopSelectionHostController != null) {
                workshopSelectionHostController.resetLastApplied();
            }
        }
        if (view == DawView.WORKSHOP) {
            ensureWorkshopBuilt();
            // Detach the arrangement node from its current parent before
            // re-parenting it into Workshop (JavaFX throws
            // IllegalArgumentException if a Node is added to a new parent
            // while still attached to another). Only clear center when it
            // actually holds the arrangement node to avoid a visible
            // blank/flicker when switching from other views (e.g. Mixer).
            if (rootPane.getCenter() == viewCache.get(DawView.ARRANGEMENT)) {
                rootPane.setCenter(null);
            }
            // Move the cached arrangement node into Workshop's left pane.
            workshopView.setArrangementContent(viewCache.get(DawView.ARRANGEMENT));
            // Story 281 Task 2 — apply any selection the user made while
            // Workshop was inactive (e.g. clicking an insert in Arrangement
            // / Mixer view) so the focused plugin appears on entry.
            if (workshopSelectionHostController != null) {
                workshopSelectionHostController.applyPendingOnWorkshopActivation();
            }
        }
        activeView = view;
        toolbarStateStore.saveActiveView(view);
        Node target = viewCache.get(view);
        rootPane.setCenter(target);
        playViewSwitchTransition(target);
        statusBarLabel.setText("Switched to " + view.name().charAt(0)
                + view.name().substring(1).toLowerCase() + " view");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
        if (onViewChanged != null) {
            onViewChanged.run();
        }
        LOG.fine(() -> "Switched to view: " + view);
    }

    /**
     * Lazily constructs and caches the Workshop view (story 281). Needs
     * the inspector selection model from {@link Host#inspectorSelectionModel()},
     * which is wired by {@code MainController} after this controller's
     * constructor returns — hence lazy.
     */
    private void ensureWorkshopBuilt() {
        if (workshopView != null) {
            return;
        }
        com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel sm =
                host.inspectorSelectionModel();
        if (sm == null) {
            // Fail-fast (review S9): a missing selection model means the
            // inspector wasn't wired in time. Silently falling back to a
            // private model produced a Workshop view that looked correct
            // but never reflected outside selections — the bug would only
            // surface when a user clicked an insert and the right pane did
            // nothing. Throwing here forces the wiring order to be fixed
            // at construction time.
            throw new IllegalStateException(
                    "WorkshopView requires a shared InspectorSelectionModel; "
                            + "host.inspectorSelectionModel() returned null. "
                            + "Ensure the inspector is wired before activating "
                            + "the Workshop view.");
        }
        workshopView = new com.benesquivelmusic.daw.app.ui.views.WorkshopView(
                host.messages());

        // Story 281 — hydrate the persisted divider position before the
        // view's first layout pass, then write back whenever the user
        // drags the divider. Range-clamping happens inside the store on
        // load (a corrupt or extreme value would collapse one pane).
        workshopView.setDividerPosition(
                toolbarStateStore.loadWorkshopDividerPosition());
        var dividers = workshopView.splitPane().getDividers();
        if (!dividers.isEmpty()) {
            dividers.get(0).positionProperty().addListener(
                    (obs, oldPos, newPos) -> toolbarStateStore
                            .saveWorkshopDividerPosition(newPos.doubleValue()));
        }

        // Story 281 Task 2 — wire selection-driven plugin / clip-detail
        // push from the shared selection model into the right pane.
        // Built once, reused across Workshop enter/exit cycles so its
        // plugin-panel cache survives.
        workshopSelectionHostController =
                new com.benesquivelmusic.daw.app.ui.views.WorkshopSelectionHostController(
                        workshopView, sm, host::project,
                        () -> activeView == DawView.WORKSHOP,
                        host.messages(),
                        EventBusPublisher.getDefault());

        viewCache.put(DawView.WORKSHOP, workshopView);
    }

    /**
     * Test seam — exposes the Workshop selection-host controller so
     * verification tests can interrogate its cache size and observe
     * push behaviour.
     *
     * @return the controller, or {@code null} when Workshop has not
     *         been built yet
     */
    com.benesquivelmusic.daw.app.ui.views.WorkshopSelectionHostController
            workshopSelectionHostController() {
        return workshopSelectionHostController;
    }

    /**
     * Disposes per-view controllers held by this navigation controller —
     * called from the primary {@code Stage}'s {@code setOnHidden} hook in
     * {@code MainController} alongside the other application-lifetime
     * disposables (review N5). At present only the Workshop selection
     * host controller has a {@link
     * com.benesquivelmusic.daw.app.ui.views.WorkshopSelectionHostController#dispose()
     * dispose()} contract — the standard {@code Mixer}/{@code Editor}/
     * {@code Mastering} views and {@code Performance Stage} hold no
     * cross-lifetime listeners. Idempotent: re-calling is safe.
     */
    void dispose() {
        if (workshopSelectionHostController != null) {
            workshopSelectionHostController.dispose();
            workshopSelectionHostController = null;
        }
    }

    /**
     * Plays the §3.5 view-switch transition (180&nbsp;ms {@code EASE_OUT}
     * fade-in) on the given node, honouring the Reduce Motion flag
     * (story 279): when Reduce Motion is on the transition is skipped and
     * the node is shown immediately. Used for every standard centre-content
     * view swap — Arrangement / Mixer / Editor / Mastering / Workshop —
     * so the experience is consistent across the menu.
     *
     * @param node the freshly installed content node
     */
    private void playViewSwitchTransition(Node node) {
        if (node == null) {
            return;
        }
        if (!com.benesquivelmusic.daw.app.ui.motion.MotionManager
                .getDefault().isAnimationAllowed()) {
            node.setOpacity(1.0);
            return;
        }
        node.setOpacity(0.0);
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(180), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
        fade.play();
    }

    /** @return the Workshop view, or {@code null} if it has not been built yet (test seam). */
    com.benesquivelmusic.daw.app.ui.views.WorkshopView workshopView() {
        return workshopView;
    }

    // ── Performance Stage activation (story 280) ─────────────────────────────

    /** @return whether the Performance Stage view is currently active. */
    boolean isPerformanceStageActive() {
        return performanceStageView != null;
    }

    /**
     * @return the active {@code PerformanceStageView}, or {@code null} when
     *         the standard chrome is showing (test seam)
     */
    com.benesquivelmusic.daw.app.ui.views.PerformanceStageView performanceStageView() {
        return performanceStageView;
    }

    /**
     * Enters Performance Stage (story 280): snapshots the standard chrome
     * (the {@code BorderPane}'s top / left / right / center), detaches it,
     * and installs a freshly built {@code PerformanceStageView} as the sole
     * content. The previously active standard view is recorded so
     * {@code Esc} / "Exit Performance Stage" can restore it.
     *
     * <p>No-op if the stage is already active.</p>
     */
    void activatePerformanceStage() {
        if (isPerformanceStageActive()) {
            return;
        }
        preStageView = activeView;
        // Hide-not-unload: detach the chrome but keep it alive in fields.
        savedTop = rootPane.getTop();
        savedLeft = rootPane.getLeft();
        savedRight = rootPane.getRight();
        savedCenter = rootPane.getCenter();

        performanceStageView = new com.benesquivelmusic.daw.app.ui.views.PerformanceStageView(
                host.project(), host.messages(),
                new com.benesquivelmusic.daw.app.ui.views.PerformanceStageView.Host() {
                    @Override public void onPlay() { host.onPlay(); }
                    @Override public void onStop() { host.onStop(); }
                    @Override public void onRecord() { host.onRecord(); }
                    @Override public void onToggleLoop() { host.onToggleLoop(); }
                    @Override public void onExitPerformanceStage() { deactivatePerformanceStage(); }
                    @Override public void onOpenAudioSettings() { host.onOpenAudioSettings(); }
                    @Override public void onNewProject() { host.onNewProject(); }
                    @Override public void onOpenProject() { host.onOpenProject(); }
                    @Override public void onSaveProject() { host.onSaveProject(); }
                    @Override public void onRecentProjects() { host.onRecentProjects(); }
                });

        rootPane.setTop(null);
        rootPane.setLeft(null);
        rootPane.setRight(null);
        // Bind the stage clock to the standard time display so it updates
        // in real-time even though the toolbar is hidden (hide-not-unload).
        performanceStageView.clockLabel().textProperty()
                .bind(host.timeDisplay().textProperty());

        // Attach first, then fade — a FadeTransition started before the
        // node is in the scene can briefly flash at opacity 0 on the first
        // paint. Symmetric with deactivate (restore-then-fade below).
        rootPane.setCenter(performanceStageView);
        playStageTransition(performanceStageView);

        installStageEscFilter();
        activeView = DawView.PERFORMANCE_STAGE;
        statusBarLabel.setText("Performance Stage");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
        if (onViewChanged != null) {
            onViewChanged.run();
        }
        LOG.fine("Activated Performance Stage view");
    }

    /**
     * Leaves Performance Stage: removes the {@code Esc} filter, restores
     * the snapshotted standard chrome, and re-activates the standard view
     * that was showing when the stage was entered. No-op if the stage is
     * not active.
     */
    void deactivatePerformanceStage() {
        if (!isPerformanceStageActive()) {
            return;
        }
        removeStageEscFilter();
        // Unbind the stage clock before discarding the view.
        performanceStageView.clockLabel().textProperty().unbind();
        rootPane.setCenter(savedCenter);
        rootPane.setTop(savedTop);
        rootPane.setLeft(savedLeft);
        rootPane.setRight(savedRight);
        savedTop = savedLeft = savedRight = savedCenter = null;
        performanceStageView = null;

        DawView restore = preStageView != null ? preStageView : DawView.ARRANGEMENT;
        preStageView = null;
        activeView = restore;
        toolbarStateStore.saveActiveView(restore);
        // The restored centre is whatever standard view was active before
        // staging began — fading it back in matches the §3.5 view-switch
        // transition (180 ms EASE_OUT). Reduce Motion skips the fade in
        // playStageTransition.
        playStageTransition(rootPane.getCenter());
        statusBarLabel.setText("Exited Performance Stage");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
        if (onViewChanged != null) {
            onViewChanged.run();
        }
        LOG.fine("Deactivated Performance Stage view");
    }

    /**
     * Plays the §3.5 view-switch transition (180&nbsp;ms {@code EASE_OUT}
     * fade-in) on the given node, honouring the Reduce Motion flag (story
     * 279): when Reduce Motion is on the transition is skipped and the node
     * is shown immediately.
     *
     * @param node the freshly installed content node
     */
    private void playStageTransition(Node node) {
        if (node == null) {
            return;
        }
        if (!com.benesquivelmusic.daw.app.ui.motion.MotionManager
                .getDefault().isAnimationAllowed()) {
            node.setOpacity(1.0);
            return;
        }
        node.setOpacity(0.0);
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(180), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
        fade.play();
    }

    /**
     * Installs a scene-level {@code KEY_PRESSED} filter that exits
     * Performance Stage on {@code Esc} (story 280). A filter (not a
     * handler) is used so the keypress is consumed before the standard
     * {@code Esc}-bound {@code STOP} accelerator can also fire.
     *
     * <p>Assumes the {@code rootPane} stays in the same {@link
     * javafx.scene.Scene} for the duration of a stage session — a scene
     * swap between activate and deactivate would leak the filter on the
     * old scene. Reparenting a live root is not a supported operation in
     * this DAW today.</p>
     */
    private void installStageEscFilter() {
        javafx.scene.Scene scene = rootPane.getScene();
        if (scene == null) {
            return;
        }
        stageEscFilter = event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                event.consume();
                deactivatePerformanceStage();
            }
        };
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, stageEscFilter);
    }

    /** Removes the {@code Esc} filter installed by {@link #installStageEscFilter()}. */
    private void removeStageEscFilter() {
        javafx.scene.Scene scene = rootPane.getScene();
        if (scene != null && stageEscFilter != null) {
            scene.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, stageEscFilter);
        }
        stageEscFilter = null;
    }

    // ── Edit tool selection ──────────────────────────────────────────────────

    /**
     * Initializes edit-tool UI integration for this controller.
     *
     * <p>This method intentionally performs no work because edit tools are
     * available only through menu actions and keyboard shortcuts; there are no
     * dedicated toolbar or sidebar buttons to wire. It is retained to keep the
     * controller initialization flow consistent with the other setup methods.</p>
     */
    void initializeEditTools() {
        // Edit tools are now accessible only via the menu bar and keyboard shortcuts.
        // No sidebar buttons to wire.
    }

    /**
     * Selects the given edit tool and updates the toolbar styling.
     *
     * @param tool the tool to activate
     */
    void selectEditTool(EditTool tool) {
        if (tool == activeEditTool) {
            return;
        }
        activeEditTool = tool;
        toolbarStateStore.saveEditTool(tool);
        if (editorView != null) {
            editorView.setActiveEditTool(tool);
        }
        if (onEditToolChanged != null) {
            onEditToolChanged.run();
        }
        statusBarLabel.setText("Selected " + tool.name().charAt(0)
                + tool.name().substring(1).toLowerCase() + " tool");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
        LOG.fine(() -> "Selected edit tool: " + tool);
    }

    /**
     * Registers a callback invoked whenever the active edit tool changes.
     *
     * @param callback the callback, or {@code null} to clear
     */
    void setOnEditToolChanged(Runnable callback) {
        this.onEditToolChanged = callback;
    }

    /**
     * Registers a callback invoked whenever the active view changes.
     *
     * @param callback the callback, or {@code null} to clear
     */
    void setOnViewChanged(Runnable callback) {
        this.onViewChanged = callback;
    }

    /**
     * Returns the currently active edit tool.
     *
     * @return the active {@link EditTool}
     */
    EditTool getActiveEditTool() {
        return activeEditTool;
    }

    // ── Snap / grid controls ─────────────────────────────────────────────────

    /**
     * Wires the snap toggle button and builds the grid-resolution context menu
     * shown on right-click.
     */
    void initializeSnapControls() {
        snapButton.setOnAction(event -> onToggleSnap());
        updateSnapButtonStyle();
        buildGridResolutionContextMenu();
    }

    /**
     * Toggles snap-to-grid on or off and updates the visual state.
     */
    void onToggleSnap() {
        snapEnabled = !snapEnabled;
        toolbarStateStore.saveSnapEnabled(snapEnabled);
        updateSnapButtonStyle();
        syncSnapStateToEditorView();
        String snapState = snapEnabled ? "Snap to grid enabled" : "Snap to grid disabled";
        statusBarLabel.setText(snapState);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SNAP, 12));
        LOG.fine(snapState);
    }

    /**
     * Applies a highlight style to the snap button when snap is enabled.
     */
    void updateSnapButtonStyle() {
        snapButton.setStyle(snapEnabled
                ? "-fx-background-color: #b388ff; -fx-text-fill: #0d0d0d;" : "");
    }

    /**
     * Builds a right-click context menu on the snap button that allows the user
     * to select a grid resolution.
     */
    private void buildGridResolutionContextMenu() {
        ContextMenu gridMenu = new ContextMenu();
        for (GridResolution resolution : GridResolution.values()) {
            MenuItem item = new MenuItem(resolution.displayName());
            item.setOnAction(event -> selectGridResolution(resolution));
            gridMenu.getItems().add(item);
        }
        snapButton.setContextMenu(gridMenu);
    }

    /**
     * Selects the given grid resolution and updates the status bar.
     *
     * @param resolution the grid resolution to activate
     */
    void selectGridResolution(GridResolution resolution) {
        gridResolution = resolution;
        toolbarStateStore.saveGridResolution(resolution);
        syncSnapStateToEditorView();
        statusBarLabel.setText("Grid: " + resolution.displayName());
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SNAP, 12));
        LOG.fine(() -> "Grid resolution changed to: " + resolution.displayName());
    }

    /**
     * Returns whether snap-to-grid is currently enabled.
     *
     * @return {@code true} if snap is enabled
     */
    boolean isSnapEnabled() {
        return snapEnabled;
    }

    /**
     * Returns the currently active grid resolution.
     *
     * @return the active {@link GridResolution}
     */
    GridResolution getGridResolution() {
        return gridResolution;
    }

    /**
     * Pushes the current snap-to-grid state from this controller to the
     * {@link EditorView} so that note placement and other editor operations
     * respect the active snap settings.
     */
    void syncSnapStateToEditorView() {
        if (editorView != null) {
            editorView.setSnapState(snapEnabled, gridResolution,
                    host.project().getTransport().getTimeSignatureNumerator());
        }
    }

    // ── Zoom controls ────────────────────────────────────────────────────────

    /**
     * Initializes zoom state for all views and wires the sidebar zoom buttons.
     * Each view maintains its own independent zoom level.
     */
    void initializeZoomControls() {
        for (DawView view : DawView.values()) {
            viewZoomLevels.put(view, new ZoomLevel());
        }

        // Wire Ctrl+Scroll zoom on the center content area
        rootPane.centerProperty().addListener((_, _, newCenter) -> {
            if (newCenter != null) {
                wireScrollZoom(newCenter);
            }
        });
        // Wire initial center content
        if (rootPane.getCenter() != null) {
            wireScrollZoom(rootPane.getCenter());
        }
    }

    /**
     * Attaches a Ctrl+Scroll wheel handler to the given node for zooming.
     *
     * @param node the content node to attach scroll-zoom to
     */
    private void wireScrollZoom(Node node) {
        node.setOnScroll(event -> {
            if (event.isControlDown()) {
                if (event.getDeltaY() > 0) {
                    onZoomIn();
                } else if (event.getDeltaY() < 0) {
                    onZoomOut();
                }
                event.consume();
            }
        });
    }

    /**
     * Zooms in on the active view.
     */
    void onZoomIn() {
        ZoomLevel zoom = viewZoomLevels.get(activeView);
        zoom.zoomIn();
        updateZoomStatus("Zoom in: " + zoom.toPercentageString(), DawIcon.ZOOM_IN);
    }

    /**
     * Zooms out on the active view.
     */
    void onZoomOut() {
        ZoomLevel zoom = viewZoomLevels.get(activeView);
        zoom.zoomOut();
        updateZoomStatus("Zoom out: " + zoom.toPercentageString(), DawIcon.ZOOM_OUT);
    }

    /**
     * Resets the active view's zoom to fit all content.
     */
    void onZoomToFit() {
        ZoomLevel zoom = viewZoomLevels.get(activeView);
        zoom.zoomToFit();
        updateZoomStatus("Zoom to fit: " + zoom.toPercentageString(), DawIcon.FULLSCREEN);
    }

    /**
     * Updates the status bar with the given zoom message and icon.
     *
     * @param message the message to display
     * @param icon    the icon to display
     */
    private void updateZoomStatus(String message, DawIcon icon) {
        statusBarLabel.setText(message);
        statusBarLabel.setGraphic(IconNode.of(icon, 12));
        LOG.fine(() -> message + " (" + activeView + ")");
    }

    /**
     * Returns the zoom level for the given view.
     *
     * @param view the view to query
     * @return the zoom level for the view
     */
    ZoomLevel getZoomLevel(DawView view) {
        return viewZoomLevels.get(view);
    }

    // ── Accessors for host coordination ──────────────────────────────────────

    /**
     * Returns the currently active view.
     *
     * @return the active {@link DawView}
     */
    DawView getActiveView() {
        return activeView;
    }

    /**
     * Invalidates the active-view cache so the next {@link #switchView}
     * call re-attaches the panel node even if it matches the stored active
     * view. Used by the dock reconciler when a center panel transitions
     * from FLOATING back to a docked zone.
     */
    void invalidateActiveViewCache() {
        activeView = null;
    }

    /**
     * Returns the mixer view instance.
     *
     * @return the mixer view
     */
    MixerView getMixerView() {
        return mixerView;
    }

    /**
     * Returns the editor view instance.
     *
     * @return the editor view
     */
    EditorView getEditorView() {
        return editorView;
    }

    /**
     * Returns the mastering view instance.
     *
     * @return the mastering view
     */
    MasteringView getMasteringView() {
        return masteringView;
    }

    /**
     * Returns the cached arrangement {@link Node} (FXML-mounted
     * {@code .arrangement-panel}) — story 285 dock host uses this when the
     * arrangement panel is detached into a floating window.
     *
     * @return the arrangement node (may be {@code null} before
     *         {@link #initializeViewNavigation()} has run)
     */
    Node getCachedArrangementNode() {
        return viewCache.get(DawView.ARRANGEMENT);
    }

    /**
     * Replaces the mixer view (e.g. after a project reload) and updates the view cache.
     *
     * @param newMixerView the new mixer view instance
     */
    void setMixerView(MixerView newMixerView) {
        this.mixerView = Objects.requireNonNull(newMixerView, "newMixerView must not be null");
        viewCache.put(DawView.MIXER, mixerView);
    }

    /**
     * Rebinds project-scoped views to the current project. Must be called
     * after the host's project reference is replaced (e.g. on new/open).
     * Views that hold a project reference should be added here as needed.
     */
    void onProjectChanged() {
        // Clear project-scoped caches in the Workshop selection host so
        // stale plugin panels and clip editors (which may hold off-heap
        // GPU/waveform resources) from the prior project are released.
        if (workshopSelectionHostController != null) {
            workshopSelectionHostController.resetForNewProject();
        }
    }
}
