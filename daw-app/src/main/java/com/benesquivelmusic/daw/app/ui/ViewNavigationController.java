package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
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
    /** The telemetry view panel — sound wave telemetry room visualizer. */
    private TelemetryView telemetryView;
    /** The mastering view panel — mastering chain with presets and A/B comparison. */
    private MasteringView masteringView;

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

        // Telemetry view — sound wave telemetry room visualizer
        telemetryView = new TelemetryView();
        telemetryView.setProject(host.project());
        telemetryView.setOnDirtyChanged(host::markProjectDirty);
        viewCache.put(DawView.TELEMETRY, telemetryView);

        // Mastering view — mastering chain with presets and A/B comparison
        masteringView = new MasteringView();
        viewCache.put(DawView.MASTERING, masteringView);

        // Restore persisted active view (activeView was set in the constructor)
        if (activeView != DawView.ARRANGEMENT) {
            rootPane.setCenter(viewCache.get(activeView));
        }
        // Start telemetry animation if telemetry view is active on startup
        if (activeView == DawView.TELEMETRY) {
            telemetryView.startAnimation();
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
        if (view == activeView) {
            return;
        }
        // Stop telemetry animation when leaving telemetry view
        if (activeView == DawView.TELEMETRY && telemetryView != null) {
            telemetryView.stopAnimation();
        }
        activeView = view;
        toolbarStateStore.saveActiveView(view);
        rootPane.setCenter(viewCache.get(view));
        // Start telemetry animation when entering telemetry view
        if (view == DawView.TELEMETRY && telemetryView != null) {
            telemetryView.startAnimation();
        }
        statusBarLabel.setText("Switched to " + view.name().charAt(0)
                + view.name().substring(1).toLowerCase() + " view");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
        if (onViewChanged != null) {
            onViewChanged.run();
        }
        LOG.fine(() -> "Switched to view: " + view);
    }

    // ── Edit tool selection ──────────────────────────────────────────────────

    /**
     * Wires the edit tool buttons and sets the default active tool styling.
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
     * Currently rebinds the telemetry view; other views that hold a project
     * reference should be added here as needed.
     */
    void onProjectChanged() {
        if (telemetryView != null) {
            telemetryView.setProject(host.project());
        }
    }
}
