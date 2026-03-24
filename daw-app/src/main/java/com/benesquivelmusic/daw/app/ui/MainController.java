package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.CorrelationDisplay;
import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.display.LoudnessDisplay;
import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplay;
import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;
import com.benesquivelmusic.daw.sdk.visualization.LevelData;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;

import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.collections.ObservableMap;

/**
 * Controller for the main DAW window.
 *
 * <p>Manages the project lifecycle, transport controls, track list,
 * and coordinates with the {@link ProjectManager} for auto-save
 * during long-running recording sessions. Uses the {@link DawIcon}
 * icon pack throughout the UI via {@link IconNode}.</p>
 */
public final class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());

    /** Icon size for transport-bar buttons (play, stop, record). */
    private static final double TRANSPORT_ICON_SIZE = 18;
    /** Icon size for toolbar buttons (add track, save, plugins). */
    private static final double TOOLBAR_ICON_SIZE = 16;
    /** Icon size for track-strip controls (mute, solo, arm). */
    private static final double TRACK_CONTROL_ICON_SIZE = 14;
    /** Icon size for track-type indicators. */
    private static final double TRACK_TYPE_ICON_SIZE = 18;
    /** Icon size for panel-header labels. */
    private static final double PANEL_ICON_SIZE = 16;
    /** Show delay for all tooltips (300ms for quick discoverability). */
    private static final Duration TOOLTIP_SHOW_DELAY = Duration.millis(300);

    @FXML private BorderPane rootPane;
    @FXML private Button skipBackButton;
    @FXML private Button skipForwardButton;
    @FXML private Button loopButton;
    @FXML private Button playButton;
    @FXML private Button pauseButton;
    @FXML private Button stopButton;
    @FXML private Button recordButton;
    @FXML private Button addAudioTrackButton;
    @FXML private Button addMidiTrackButton;
    @FXML private Button undoButton;
    @FXML private Button redoButton;
    @FXML private Button snapButton;
    @FXML private Button saveButton;
    @FXML private Button pluginsButton;
    @FXML private Label statusLabel;
    @FXML private Label tempoLabel;
    @FXML private Label timeDisplay;
    @FXML private Label projectInfoLabel;
    @FXML private Label monitoringLabel;
    @FXML private Label checkpointLabel;
    @FXML private Label statusBarLabel;
    @FXML private Label arrangementPlaceholder;
    @FXML private Label arrangementPanelHeader;
    @FXML private Label tracksPanelHeader;
    @FXML private Label ioRoutingLabel;
    @FXML private VBox trackListPanel;
    @FXML private HBox vizTileRow;
    @FXML private VBox sidebarToolbar;
    @FXML private Button homeButton;
    @FXML private Button arrangementViewButton;
    @FXML private Button mixerViewButton;
    @FXML private Button editorViewButton;
    @FXML private Button telemetryViewButton;
    @FXML private Button newProjectButton;
    @FXML private Button openProjectButton;
    @FXML private Button saveProjectButton;
    @FXML private Button recentProjectsButton;
    @FXML private Button browserButton;
    @FXML private Button searchButton;
    @FXML private Button pluginsSidebarButton;
    @FXML private Button visualizationsButton;
    @FXML private Button settingsButton;
    @FXML private Button expandCollapseButton;
    @FXML private Button helpButton;
    @FXML private Button pointerToolButton;
    @FXML private Button pencilToolButton;
    @FXML private Button eraserToolButton;
    @FXML private Button scissorsToolButton;
    @FXML private Button glueToolButton;
    @FXML private Button zoomInButton;
    @FXML private Button zoomOutButton;
    @FXML private Button zoomToFitButton;

    private DawProject project;
    private PluginRegistry pluginRegistry;
    private ProjectManager projectManager;
    private UndoManager undoManager;
    private int audioTrackCounter;
    private int midiTrackCounter;
    private boolean loopEnabled;
    private boolean snapEnabled = true;
    private GridResolution gridResolution = GridResolution.QUARTER;
    private boolean projectDirty;
    private AudioEngine audioEngine;
    private RecordingPipeline recordingPipeline;

    // ── View navigation state ────────────────────────────────────────────────
    /** Caches each view's content node so switching back preserves state. */
    private final Map<DawView, Node> viewCache = new EnumMap<>(DawView.class);
    /** The currently active view. */
    private DawView activeView = DawView.ARRANGEMENT;
    /** The mixer view panel — refreshed when tracks are added or removed. */
    private MixerView mixerView;
    /** The editor view panel — shows MIDI piano roll or audio waveform. */
    private EditorView editorView;
    /** The telemetry view panel — sound wave telemetry room visualizer. */
    private TelemetryView telemetryView;

    // ── Edit tool state ──────────────────────────────────────────────────────
    /** The currently active edit tool. Defaults to {@link EditTool#POINTER}. */
    private EditTool activeEditTool = EditTool.POINTER;

    // ── Zoom state ───────────────────────────────────────────────────────────
    /** Per-view zoom levels — preserved when switching between views. */
    private final Map<DawView, ZoomLevel> viewZoomLevels = new EnumMap<>(DawView.class);

    // ── Visualization panel controller ───────────────────────────────────────
    /** Controls the visualization row toggle, context menu, and persistence. */
    private VisualizationPanelController vizPanelController;

    // ── Browser panel controller ─────────────────────────────────────────────
    /** Controls the browser/library side panel toggle. */
    private BrowserPanelController browserPanelController;

    // ── Toolbar collapse controller ──────────────────────────────────────────
    /** Controls the sidebar toolbar collapse/expand toggle and persistence. */
    private ToolbarCollapseController toolbarCollapseController;

    // ── Responsive toolbar controller ────────────────────────────────────────
    /** Auto-collapses/expands the sidebar based on window width. */
    private ResponsiveToolbarController responsiveToolbarController;

    // ── Toolbar context menu controller ──────────────────────────────────────
    /** Attaches right-click context menus to toolbar sections. */
    private ToolbarContextMenuController toolbarContextMenuController;

    // ── Toolbar state persistence ────────────────────────────────────────────
    /** Persists toolbar state (view, tool, snap, grid, browser) across sessions. */
    private ToolbarStateStore toolbarStateStore;

    // ── Animation state ──────────────────────────────────────────────────────
    /** Drives all continuous frame-by-frame animations at ~60 fps. */
    private AnimationTimer mainAnimTimer;
    /** Accumulated phase (seconds) for the idle visualization waveform simulation. */
    private double idleAnimPhase;
    /** Accumulated phase (seconds) for the transport-state glow animations. */
    private double glowAnimPhase;
    /** Nanosecond timestamp when playback/recording started; used for time display. */
    private long timeTickerStartNanos;
    /** Whether the time ticker is actively counting up. */
    private boolean timeTickerRunning;
    /** Cached elapsed nanoseconds before the last pause (for correct resume). */
    private long timeTickerPausedElapsedNanos;

    /** Reference kept for the idle demo animation. */
    private SpectrumDisplay spectrumDisplay;
    /** Reference kept for the idle demo animation. */
    private LevelMeterDisplay levelMeterDisplay;
    /** Reusable bin buffer for the idle spectrum animation — avoids per-frame heap allocation. */
    private static final int IDLE_FFT_SIZE = 1024;
    private final float[] idleSpectrumBins = new float[IDLE_FFT_SIZE / 2];

    @FXML
    private void initialize() {
        project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        pluginRegistry = new PluginRegistry();
        undoManager = new UndoManager();
        audioEngine = new AudioEngine(project.getFormat());

        CheckpointManager checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        Preferences prefs = Preferences.userNodeForPackage(MainController.class);
        RecentProjectsStore recentProjectsStore = new RecentProjectsStore(prefs);
        projectManager = new ProjectManager(checkpointManager, recentProjectsStore);
        toolbarStateStore = new ToolbarStateStore(prefs);

        // Restore persisted toolbar state before UI initialization
        activeView = toolbarStateStore.loadActiveView();
        activeEditTool = toolbarStateStore.loadEditTool();
        snapEnabled = toolbarStateStore.loadSnapEnabled();
        gridResolution = toolbarStateStore.loadGridResolution();

        audioTrackCounter = 0;
        midiTrackCounter = 0;

        applyIcons();
        applyTooltips();
        applyButtonPressAnimations();
        preventButtonTruncation();
        buildVisualizationTiles();
        buildBrowserPanel(toolbarStateStore.loadBrowserVisible());
        setupTempoEditor();
        updateStatus();
        updateTempoDisplay();
        updateProjectInfo();
        updateCheckpointStatus();
        updateUndoRedoState();
        startMainAnimTimer();
        initializeViewNavigation();
        initializeEditTools();
        initializeSnapControls();
        initializeZoomControls();
        initializeToolbarCollapse(prefs);
        initializeToolbarContextMenus();

        // Register keyboard shortcuts after the scene is available
        playButton.sceneProperty().addListener((_, _, scene) -> {
            if (scene != null) {
                registerKeyboardShortcuts();
            }
        });

        LOG.info("DAW initialized with studio quality format");
    }

    // ── View navigation ──────────────────────────────────────────────────────

    /**
     * Sets up the view cache with the initial arrangement content and placeholder
     * nodes for the mixer and editor views, then wires the sidebar view buttons.
     */
    private void initializeViewNavigation() {
        // Cache the current center content as the arrangement view
        viewCache.put(DawView.ARRANGEMENT, rootPane.getCenter());

        // Mixer view — real channel-strip mixer panel
        mixerView = new MixerView(project);
        viewCache.put(DawView.MIXER, mixerView);

        // Editor view — MIDI piano-roll / audio waveform editor panel
        editorView = new EditorView();
        viewCache.put(DawView.EDITOR, editorView);

        // Telemetry view — sound wave telemetry room visualizer
        telemetryView = new TelemetryView();
        viewCache.put(DawView.TELEMETRY, telemetryView);

        // Wire sidebar view buttons
        arrangementViewButton.setOnAction(event -> switchView(DawView.ARRANGEMENT));
        mixerViewButton.setOnAction(event -> switchView(DawView.MIXER));
        editorViewButton.setOnAction(event -> switchView(DawView.EDITOR));
        telemetryViewButton.setOnAction(event -> switchView(DawView.TELEMETRY));

        // Restore persisted active view (activeView was loaded in initialize())
        if (activeView != DawView.ARRANGEMENT) {
            rootPane.setCenter(viewCache.get(activeView));
        }
        // Start telemetry animation if telemetry view is active on startup
        if (activeView == DawView.TELEMETRY) {
            telemetryView.startAnimation();
        }

        // Set the active view styling
        updateToolbarActiveState();
    }

    /**
     * Switches the center content of the main {@link BorderPane} to the given view.
     *
     * <p>Each view's content node is created once and cached so switching back
     * preserves state (scroll position, selection, etc.).</p>
     *
     * @param view the view to activate
     */
    private void switchView(DawView view) {
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
        updateToolbarActiveState();
        // Start telemetry animation when entering telemetry view
        if (view == DawView.TELEMETRY && telemetryView != null) {
            telemetryView.startAnimation();
        }
        statusBarLabel.setText("Switched to " + view.name().charAt(0)
                + view.name().substring(1).toLowerCase() + " view");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
        LOG.fine(() -> "Switched to view: " + view);
    }

    /**
     * Applies the {@code .toolbar-button-active} CSS class to the sidebar button
     * corresponding to the active view and removes it from all others.
     */
    private void updateToolbarActiveState() {
        Button[] viewButtons = { arrangementViewButton, mixerViewButton, editorViewButton, telemetryViewButton };
        DawView[] views = DawView.values();
        for (int i = 0; i < viewButtons.length; i++) {
            if (views[i] == activeView) {
                if (!viewButtons[i].getStyleClass().contains("toolbar-button-active")) {
                    viewButtons[i].getStyleClass().add("toolbar-button-active");
                }
            } else {
                viewButtons[i].getStyleClass().remove("toolbar-button-active");
            }
        }
    }

    // ── Edit tool selection ──────────────────────────────────────────────────

    /**
     * Wires the edit tool buttons and sets the default active tool styling.
     */
    private void initializeEditTools() {
        pointerToolButton.setOnAction(event -> selectEditTool(EditTool.POINTER));
        pencilToolButton.setOnAction(event -> selectEditTool(EditTool.PENCIL));
        eraserToolButton.setOnAction(event -> selectEditTool(EditTool.ERASER));
        scissorsToolButton.setOnAction(event -> selectEditTool(EditTool.SCISSORS));
        glueToolButton.setOnAction(event -> selectEditTool(EditTool.GLUE));

        updateEditToolActiveState();
    }

    /**
     * Selects the given edit tool and updates the toolbar styling.
     *
     * @param tool the tool to activate
     */
    private void selectEditTool(EditTool tool) {
        if (tool == activeEditTool) {
            return;
        }
        activeEditTool = tool;
        toolbarStateStore.saveEditTool(tool);
        updateEditToolActiveState();
        statusBarLabel.setText("Selected " + tool.name().charAt(0)
                + tool.name().substring(1).toLowerCase() + " tool");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
        LOG.fine(() -> "Selected edit tool: " + tool);
    }

    /**
     * Returns the currently active edit tool.
     *
     * @return the active {@link EditTool}
     */
    public EditTool getActiveEditTool() {
        return activeEditTool;
    }

    /**
     * Applies the {@code .toolbar-button-active} CSS class to the edit tool button
     * corresponding to the active tool and removes it from all others.
     */
    private void updateEditToolActiveState() {
        Button[] toolButtons = {
                pointerToolButton, pencilToolButton, eraserToolButton,
                scissorsToolButton, glueToolButton
        };
        EditTool[] tools = EditTool.values();
        for (int i = 0; i < toolButtons.length; i++) {
            if (tools[i] == activeEditTool) {
                if (!toolButtons[i].getStyleClass().contains("toolbar-button-active")) {
                    toolButtons[i].getStyleClass().add("toolbar-button-active");
                }
            } else {
                toolButtons[i].getStyleClass().remove("toolbar-button-active");
            }
        }
    }

    // ── Snap / grid controls ─────────────────────────────────────────────────

    /**
     * Wires the snap toggle button and builds the grid-resolution context menu
     * shown on right-click.
     */
    private void initializeSnapControls() {
        snapButton.setOnAction(event -> onToggleSnap());
        updateSnapButtonStyle();
        buildGridResolutionContextMenu();
    }

    /**
     * Toggles snap-to-grid on or off and updates the visual state.
     */
    private void onToggleSnap() {
        snapEnabled = !snapEnabled;
        toolbarStateStore.saveSnapEnabled(snapEnabled);
        updateSnapButtonStyle();
        String snapState = snapEnabled ? "Snap to grid enabled" : "Snap to grid disabled";
        statusBarLabel.setText(snapState);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SNAP, 12));
        LOG.fine(snapState);
    }

    /**
     * Toggles the sidebar toolbar between expanded and collapsed states.
     * Delegates to {@link ToolbarCollapseController} for animation and persistence.
     */
    private void onToggleToolbar() {
        toolbarCollapseController.toggle();
        boolean collapsed = toolbarCollapseController.isCollapsed();
        String state = collapsed ? "Toolbar collapsed" : "Toolbar expanded";
        statusBarLabel.setText(state);
        statusBarLabel.setGraphic(IconNode.of(
                collapsed ? DawIcon.COLLAPSE : DawIcon.EXPAND, 12));
        LOG.fine(state);
    }

    /**
     * Initializes the toolbar collapse controller, wiring it to the sidebar
     * and the expand/collapse button, and restoring persisted state.
     * Also sets up the responsive toolbar controller that auto-collapses
     * the sidebar at narrow window widths and expands it at wider sizes.
     *
     * @param prefs the preferences node used for state persistence
     */
    private void initializeToolbarCollapse(Preferences prefs) {
        toolbarCollapseController = new ToolbarCollapseController(
                sidebarToolbar, expandCollapseButton, prefs);
        toolbarCollapseController.initialize();

        responsiveToolbarController =
                new ResponsiveToolbarController(toolbarCollapseController);
        sidebarToolbar.sceneProperty().addListener((_, _, scene) -> {
            if (scene != null) {
                responsiveToolbarController.attach(scene);
            }
        });

        LOG.fine("Toolbar collapse controller initialized");
    }

    /**
     * Creates and wires the toolbar context menu controller, attaching
     * right-click context menus to the Views, Project, and Tools sidebar
     * sections.
     */
    private void initializeToolbarContextMenus() {
        Button[] viewBtns = { arrangementViewButton, mixerViewButton, editorViewButton };
        Button[] projectBtns = { newProjectButton, openProjectButton, saveProjectButton, recentProjectsButton };
        Button[] toolBtns = { pluginsSidebarButton, settingsButton };

        toolbarContextMenuController = new ToolbarContextMenuController(
                viewBtns,
                projectBtns,
                toolBtns,
                projectManager,
                text -> {
                    statusBarLabel.setText(text);
                    statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
                },
                this::resetViewLayout,
                this::loadProjectFromPath
        );
        toolbarContextMenuController.initialize();
        LOG.fine("Toolbar context menus initialized");
    }

    /**
     * Resets the split pane divider positions to their default values.
     */
    private void resetViewLayout() {
        Node center = rootPane.getCenter();
        if (center instanceof VBox vbox) {
            for (Node child : vbox.getChildren()) {
                if (child instanceof javafx.scene.control.SplitPane splitPane) {
                    splitPane.setDividerPositions(0.22);
                }
            }
        }
        LOG.fine("View layout reset to defaults");
    }

    /**
     * Applies a highlight style to the snap button when snap is enabled.
     */
    private void updateSnapButtonStyle() {
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
    private void selectGridResolution(GridResolution resolution) {
        gridResolution = resolution;
        toolbarStateStore.saveGridResolution(resolution);
        statusBarLabel.setText("Grid: " + resolution.displayName());
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SNAP, 12));
        LOG.fine(() -> "Grid resolution changed to: " + resolution.displayName());
    }

    /**
     * Returns whether snap-to-grid is currently enabled.
     *
     * @return {@code true} if snap is enabled
     */
    public boolean isSnapEnabled() {
        return snapEnabled;
    }

    /**
     * Returns the currently active grid resolution.
     *
     * @return the active {@link GridResolution}
     */
    public GridResolution getGridResolution() {
        return gridResolution;
    }

    // ── Zoom controls ────────────────────────────────────────────────────────

    /**
     * Initializes zoom state for all views and wires the sidebar zoom buttons.
     * Each view maintains its own independent zoom level.
     */
    private void initializeZoomControls() {
        for (DawView view : DawView.values()) {
            viewZoomLevels.put(view, new ZoomLevel());
        }

        zoomInButton.setOnAction(event -> onZoomIn());
        zoomOutButton.setOnAction(event -> onZoomOut());
        zoomToFitButton.setOnAction(event -> onZoomToFit());

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
    private void onZoomIn() {
        ZoomLevel zoom = viewZoomLevels.get(activeView);
        zoom.zoomIn();
        updateZoomStatus("Zoom in: " + zoom.toPercentageString(), DawIcon.ZOOM_IN);
    }

    /**
     * Zooms out on the active view.
     */
    private void onZoomOut() {
        ZoomLevel zoom = viewZoomLevels.get(activeView);
        zoom.zoomOut();
        updateZoomStatus("Zoom out: " + zoom.toPercentageString(), DawIcon.ZOOM_OUT);
    }

    /**
     * Resets the active view's zoom to fit all content.
     */
    private void onZoomToFit() {
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
    public ZoomLevel getZoomLevel(DawView view) {
        return viewZoomLevels.get(view);
    }

    /**
     * Applies SVG icons from the DAW icon pack to all UI controls.
     *
     * <p>Icons are drawn from every category in the pack to provide rich visual
     * feedback: playback controls use the <em>Playback</em> category; track-type
     * indicators pull from <em>Media</em> and <em>Instruments</em>; status labels
     * reference <em>Notifications</em>; I/O routing uses <em>Connectivity</em>;
     * and so on across all 14 categories.</p>
     */
    private void applyIcons() {
        // ── Transport controls (Playback category) ──────────────────────────
        skipBackButton.setGraphic(IconNode.of(DawIcon.SKIP_BACK, TRANSPORT_ICON_SIZE));
        playButton.setGraphic(IconNode.of(DawIcon.PLAY, TRANSPORT_ICON_SIZE));
        pauseButton.setGraphic(IconNode.of(DawIcon.PAUSE, TRANSPORT_ICON_SIZE));
        stopButton.setGraphic(IconNode.of(DawIcon.STOP, TRANSPORT_ICON_SIZE));
        recordButton.setGraphic(IconNode.of(DawIcon.RECORD, TRANSPORT_ICON_SIZE));
        skipForwardButton.setGraphic(IconNode.of(DawIcon.SKIP_FORWARD, TRANSPORT_ICON_SIZE));
        loopButton.setGraphic(IconNode.of(DawIcon.LOOP, TRANSPORT_ICON_SIZE));

        // ── Toolbar buttons (mixed categories) ─────────────────────────────
        addAudioTrackButton.setGraphic(IconNode.of(DawIcon.MICROPHONE, TOOLBAR_ICON_SIZE));
        addMidiTrackButton.setGraphic(IconNode.of(DawIcon.KEYBOARD, TOOLBAR_ICON_SIZE));
        undoButton.setGraphic(IconNode.of(DawIcon.UNDO, TOOLBAR_ICON_SIZE));
        redoButton.setGraphic(IconNode.of(DawIcon.REDO, TOOLBAR_ICON_SIZE));
        snapButton.setGraphic(IconNode.of(DawIcon.SNAP, TOOLBAR_ICON_SIZE));
        saveButton.setGraphic(IconNode.of(DawIcon.DOWNLOAD, TOOLBAR_ICON_SIZE));
        pluginsButton.setGraphic(IconNode.of(DawIcon.EQ, TOOLBAR_ICON_SIZE));

        // ── Time display — timer icon prefix (General category) ────────────
        timeDisplay.setGraphic(IconNode.of(DawIcon.TIMER, PANEL_ICON_SIZE));

        // ── Panel headers ───────────────────────────────────────────────────
        tracksPanelHeader.setGraphic(IconNode.of(DawIcon.MIXER, PANEL_ICON_SIZE));
        arrangementPanelHeader.setGraphic(IconNode.of(DawIcon.TIMELINE, PANEL_ICON_SIZE));

        // ── Arrangement placeholder (Media category) ────────────────────────
        arrangementPlaceholder.setGraphic(IconNode.of(DawIcon.MUSIC_NOTE, 24));

        // ── Status bar icons ────────────────────────────────────────────────
        monitoringLabel.setGraphic(IconNode.of(DawIcon.HEADPHONES, 12));
        checkpointLabel.setGraphic(IconNode.of(DawIcon.SYNC, 12));
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
        ioRoutingLabel.setGraphic(IconNode.of(DawIcon.USB, 12));

        // ── Sidebar toolbar buttons ─────────────────────────────────────────
        homeButton.setGraphic(IconNode.of(DawIcon.HOME, TOOLBAR_ICON_SIZE));
        arrangementViewButton.setGraphic(IconNode.of(DawIcon.TIMELINE, TOOLBAR_ICON_SIZE));
        mixerViewButton.setGraphic(IconNode.of(DawIcon.MIXER, TOOLBAR_ICON_SIZE));
        editorViewButton.setGraphic(IconNode.of(DawIcon.WAVEFORM, TOOLBAR_ICON_SIZE));
        telemetryViewButton.setGraphic(IconNode.of(DawIcon.OSCILLOSCOPE, TOOLBAR_ICON_SIZE));
        newProjectButton.setGraphic(IconNode.of(DawIcon.FOLDER, TOOLBAR_ICON_SIZE));
        openProjectButton.setGraphic(IconNode.of(DawIcon.FOLDER, TOOLBAR_ICON_SIZE));
        saveProjectButton.setGraphic(IconNode.of(DawIcon.DOWNLOAD, TOOLBAR_ICON_SIZE));
        recentProjectsButton.setGraphic(IconNode.of(DawIcon.HISTORY, TOOLBAR_ICON_SIZE));
        browserButton.setGraphic(IconNode.of(DawIcon.LIBRARY, TOOLBAR_ICON_SIZE));
        searchButton.setGraphic(IconNode.of(DawIcon.SEARCH, TOOLBAR_ICON_SIZE));
        pluginsSidebarButton.setGraphic(IconNode.of(DawIcon.EQUALIZER, TOOLBAR_ICON_SIZE));
        visualizationsButton.setGraphic(IconNode.of(DawIcon.SPECTRUM, TOOLBAR_ICON_SIZE));
        settingsButton.setGraphic(IconNode.of(DawIcon.SETTINGS, TOOLBAR_ICON_SIZE));
        expandCollapseButton.setGraphic(IconNode.of(DawIcon.EXPAND, TOOLBAR_ICON_SIZE));
        helpButton.setGraphic(IconNode.of(DawIcon.INFO, TOOLBAR_ICON_SIZE));

        // ── Edit tool buttons (Editing category) ───────────────────────────
        pointerToolButton.setGraphic(IconNode.of(DawIcon.MOVE, TOOLBAR_ICON_SIZE));
        pencilToolButton.setGraphic(IconNode.of(DawIcon.MARKER, TOOLBAR_ICON_SIZE));
        eraserToolButton.setGraphic(IconNode.of(DawIcon.DELETE, TOOLBAR_ICON_SIZE));
        scissorsToolButton.setGraphic(IconNode.of(DawIcon.SPLIT, TOOLBAR_ICON_SIZE));
        glueToolButton.setGraphic(IconNode.of(DawIcon.CROSSFADE, TOOLBAR_ICON_SIZE));

        // ── Zoom buttons (Editing + Navigation categories) ─────────────────
        zoomInButton.setGraphic(IconNode.of(DawIcon.ZOOM_IN, TOOLBAR_ICON_SIZE));
        zoomOutButton.setGraphic(IconNode.of(DawIcon.ZOOM_OUT, TOOLBAR_ICON_SIZE));
        zoomToFitButton.setGraphic(IconNode.of(DawIcon.FULLSCREEN, TOOLBAR_ICON_SIZE));

        LOG.fine("Applied SVG icons from DAW icon pack");
    }

    /**
     * Applies descriptive tooltips with keyboard shortcut hints to all UI controls.
     *
     * <p>Tooltips follow the format {@code "Action Name (Shortcut)"} and use a
     * 300&nbsp;ms show delay for quick discoverability. Ambiguous buttons include
     * a brief description separated by an em-dash.</p>
     */
    private void applyTooltips() {
        // ── Transport controls ──────────────────────────────────────────────
        skipBackButton.setTooltip(styledTooltip("Skip to Beginning (Home)"));
        playButton.setTooltip(styledTooltip("Play (Space)"));
        pauseButton.setTooltip(styledTooltip("Pause"));
        stopButton.setTooltip(styledTooltip("Stop (Escape)"));
        recordButton.setTooltip(styledTooltip("Record (R)"));
        skipForwardButton.setTooltip(styledTooltip("Skip Forward (End)"));
        loopButton.setTooltip(styledTooltip("Toggle Loop (L)"));

        // ── Toolbar buttons ─────────────────────────────────────────────────
        addAudioTrackButton.setTooltip(styledTooltip("Add Audio Track (Ctrl+Shift+A)"));
        addMidiTrackButton.setTooltip(styledTooltip("Add MIDI Track (Ctrl+Shift+M)"));
        undoButton.setTooltip(styledTooltip("Undo (Ctrl+Z)"));
        redoButton.setTooltip(styledTooltip("Redo (Ctrl+Shift+Z)"));
        snapButton.setTooltip(styledTooltip(
                "Toggle Snap (Ctrl+Shift+S) \u00b7 Right-click for grid resolution"));
        saveButton.setTooltip(styledTooltip("Save Project (Ctrl+S)"));
        pluginsButton.setTooltip(styledTooltip(
                "Manage Plugins \u2014 Add, remove, and configure audio plugins"));

        // ── Sidebar view buttons ────────────────────────────────────────────
        homeButton.setTooltip(styledTooltip(
                "Home \u2014 Return to the default view"));
        arrangementViewButton.setTooltip(styledTooltip("Arrangement View (Ctrl+1)"));
        mixerViewButton.setTooltip(styledTooltip("Mixer View (Ctrl+2)"));
        editorViewButton.setTooltip(styledTooltip("Editor View (Ctrl+3)"));
        telemetryViewButton.setTooltip(styledTooltip("Sound Wave Telemetry View (Ctrl+4)"));
        newProjectButton.setTooltip(styledTooltip("New Project (Ctrl+N)"));
        openProjectButton.setTooltip(styledTooltip("Open Project (Ctrl+O)"));
        saveProjectButton.setTooltip(styledTooltip("Save Project (Ctrl+S)"));
        recentProjectsButton.setTooltip(styledTooltip(
                "Recent Projects \u2014 Open a recently saved project"));
        browserButton.setTooltip(styledTooltip(
                "Browser \u2014 Browse samples, presets, and project files (Ctrl+B)"));
        searchButton.setTooltip(styledTooltip(
                "Search \u2014 Find tracks, clips, and project items"));
        pluginsSidebarButton.setTooltip(styledTooltip(
                "Plugins \u2014 Browse and manage audio plugins"));
        visualizationsButton.setTooltip(styledTooltip(
                "Visualizations \u2014 Toggle audio visualization panels (Ctrl+Shift+V)"));
        settingsButton.setTooltip(styledTooltip("Settings (Ctrl+,)"));
        expandCollapseButton.setTooltip(styledTooltip(
                "Collapse/Expand Toolbar (Ctrl+T)"));
        helpButton.setTooltip(styledTooltip(
                "Help \u2014 View documentation and keyboard shortcuts"));

        // ── Edit tool buttons ───────────────────────────────────────────────
        pointerToolButton.setTooltip(styledTooltip("Pointer Tool (V)"));
        pencilToolButton.setTooltip(styledTooltip("Pencil Tool (P)"));
        eraserToolButton.setTooltip(styledTooltip("Eraser Tool (E)"));
        scissorsToolButton.setTooltip(styledTooltip("Scissors Tool (C)"));
        glueToolButton.setTooltip(styledTooltip("Glue Tool (G)"));

        // ── Zoom buttons ────────────────────────────────────────────────────
        zoomInButton.setTooltip(styledTooltip("Zoom In (Ctrl+=)"));
        zoomOutButton.setTooltip(styledTooltip("Zoom Out (Ctrl+-)"));
        zoomToFitButton.setTooltip(styledTooltip("Zoom to Fit (Ctrl+0)"));
    }

    /**
     * Creates a {@link Tooltip} with the given text and a fast show delay
     * matching the application's dark theme.
     */
    private static Tooltip styledTooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(TOOLTIP_SHOW_DELAY);
        return tooltip;
    }

    /**
     * Registers global keyboard shortcuts for transport and project actions.
     */
    private void registerKeyboardShortcuts() {
        Scene scene = playButton.getScene();
        if (scene == null) {
            return;
        }
        ObservableMap<KeyCombination, Runnable> accelerators = scene.getAccelerators();

        // Home — skip to beginning
        accelerators.put(
                new KeyCodeCombination(KeyCode.HOME),
                this::onSkipBack);

        // End — skip forward
        accelerators.put(
                new KeyCodeCombination(KeyCode.END),
                this::onSkipForward);

        // L — toggle loop
        accelerators.put(
                new KeyCodeCombination(KeyCode.L),
                this::onToggleLoop);

        // Space — toggle play/stop
        accelerators.put(
                new KeyCodeCombination(KeyCode.SPACE),
                () -> {
                    if (project.getTransport().getState() == TransportState.PLAYING) {
                        onStop();
                    } else {
                        onPlay();
                    }
                });

        // Escape — stop
        accelerators.put(
                new KeyCodeCombination(KeyCode.ESCAPE),
                this::onStop);

        // R — record
        accelerators.put(
                new KeyCodeCombination(KeyCode.R),
                this::onRecord);

        // Ctrl+S — save
        accelerators.put(
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                this::onSaveProject);

        // Ctrl+Shift+A — add audio track
        accelerators.put(
                new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                this::onAddAudioTrack);

        // Ctrl+Shift+M — add MIDI track
        accelerators.put(
                new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                this::onAddMidiTrack);

        // Ctrl+Z — undo
        accelerators.put(
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN),
                this::onUndo);

        // Ctrl+Shift+Z — redo
        accelerators.put(
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                this::onRedo);

        // V — Pointer tool
        accelerators.put(
                new KeyCodeCombination(KeyCode.V),
                () -> selectEditTool(EditTool.POINTER));

        // P — Pencil tool
        accelerators.put(
                new KeyCodeCombination(KeyCode.P),
                () -> selectEditTool(EditTool.PENCIL));

        // E — Eraser tool
        accelerators.put(
                new KeyCodeCombination(KeyCode.E),
                () -> selectEditTool(EditTool.ERASER));

        // C — Scissors tool
        accelerators.put(
                new KeyCodeCombination(KeyCode.C),
                () -> selectEditTool(EditTool.SCISSORS));

        // G — Glue tool
        accelerators.put(
                new KeyCodeCombination(KeyCode.G),
                () -> selectEditTool(EditTool.GLUE));

        // Ctrl+= — Zoom in
        accelerators.put(
                new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.SHORTCUT_DOWN),
                this::onZoomIn);

        // Ctrl+- — Zoom out
        accelerators.put(
                new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN),
                this::onZoomOut);

        // Ctrl+0 — Zoom to fit
        accelerators.put(
                new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN),
                this::onZoomToFit);

        // Ctrl+1 — Arrangement View
        accelerators.put(
                new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN),
                () -> switchView(DawView.ARRANGEMENT));

        // Ctrl+2 — Mixer View
        accelerators.put(
                new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN),
                () -> switchView(DawView.MIXER));

        // Ctrl+3 — Editor View
        accelerators.put(
                new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.SHORTCUT_DOWN),
                () -> switchView(DawView.EDITOR));

        // Ctrl+4 — Telemetry View
        accelerators.put(
                new KeyCodeCombination(KeyCode.DIGIT4, KeyCombination.SHORTCUT_DOWN),
                () -> switchView(DawView.TELEMETRY));

        // Ctrl+B — Toggle Browser
        accelerators.put(
                new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN),
                () -> browserPanelController.toggleBrowserPanel());

        // Ctrl+Shift+V — Toggle Visualizations
        accelerators.put(
                new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                () -> vizPanelController.toggleRowVisibility());

        // Ctrl+N — New Project
        accelerators.put(
                new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN),
                this::onNewProject);

        // Ctrl+O — Open Project
        accelerators.put(
                new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN),
                this::onOpenProject);

        // Ctrl+, — Settings
        accelerators.put(
                new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN),
                this::onOpenSettings);

        // Ctrl+Shift+S — Toggle Snap
        accelerators.put(
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                this::onToggleSnap);

        // Ctrl+T — Collapse/Expand Toolbar
        accelerators.put(
                new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN),
                this::onToggleToolbar);

        LOG.fine("Registered keyboard shortcuts");
    }

    /**
     * Configures the tempo label to become editable on double-click,
     * allowing users to type a new BPM value.
     */
    private void setupTempoEditor() {
        tempoLabel.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                startTempoEdit();
            }
        });
        tempoLabel.setTooltip(new Tooltip("Double-click to edit tempo"));
    }

    private void startTempoEdit() {
        Parent parent = tempoLabel.getParent();
        if (!(parent instanceof HBox hbox)) {
            return;
        }
        int index = hbox.getChildren().indexOf(tempoLabel);
        if (index < 0) {
            return;
        }

        TextField editor = new TextField(String.format("%.1f", project.getTransport().getTempo()));
        editor.getStyleClass().add("tempo-editor");
        editor.setPrefWidth(80);

        // Commit on Enter
        editor.setOnAction(_ -> commitTempoEdit(editor, hbox, index));

        // Commit on focus loss
        editor.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                commitTempoEdit(editor, hbox, index);
            }
        });

        hbox.getChildren().set(index, editor);
        editor.requestFocus();
        editor.selectAll();
    }

    private void commitTempoEdit(TextField editor, HBox hbox, int index) {
        try {
            double newTempo = Double.parseDouble(editor.getText().strip());
            double oldTempo = project.getTransport().getTempo();
            if (Double.compare(newTempo, oldTempo) != 0) {
                undoManager.execute(new UndoableAction() {
                    @Override public String description() {
                        return String.format("Set Tempo to %.1f BPM", newTempo);
                    }
                    @Override public void execute() {
                        project.getTransport().setTempo(newTempo);
                    }
                    @Override public void undo() {
                        project.getTransport().setTempo(oldTempo);
                    }
                });
                updateUndoRedoState();
            }
            statusBarLabel.setText(String.format("Tempo set to %.1f BPM", newTempo));
            statusBarLabel.setGraphic(IconNode.of(DawIcon.METRONOME, 12));
        } catch (IllegalArgumentException e) {
            statusBarLabel.setText("Invalid tempo — must be 20–999 BPM");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.ALERT, 12));
        }
        updateTempoDisplay();
        hbox.getChildren().set(index, tempoLabel);
    }

    /**
     * Builds the visualization tile row at the bottom of the main content area.
     * Each tile is a styled card containing a live display component.
     *
     * <p>Uses icons from the <em>Metering</em> and <em>DAW</em> categories to
     * label each tile with a contextually appropriate icon. After building
     * the tiles, wires a {@link VisualizationPanelController} to the toolbar
     * button for toggle/context-menu control and preference persistence.</p>
     */
    private void buildVisualizationTiles() {
        vizTileRow.setPrefHeight(120);
        vizTileRow.setMinHeight(100);

        spectrumDisplay    = new SpectrumDisplay();
        levelMeterDisplay  = new LevelMeterDisplay();
        WaveformDisplay waveformDisplay   = new WaveformDisplay();
        LoudnessDisplay loudnessDisplay   = new LoudnessDisplay();
        CorrelationDisplay correlationDisplay = new CorrelationDisplay();

        VBox spectrumTile     = createVizTile("SPECTRUM",     DawIcon.SPECTRUM,       "tile-header-accent-green",  spectrumDisplay);
        VBox levelsTile       = createVizTile("PEAK / RMS",   DawIcon.PEAK,           "tile-header-accent-orange", levelMeterDisplay);
        VBox waveformTile     = createVizTile("OSCILLOSCOPE", DawIcon.OSCILLOSCOPE,   "tile-header-accent-cyan",   waveformDisplay);
        VBox loudnessTile     = createVizTile("LOUDNESS",     DawIcon.LOUDNESS_METER, "tile-header-accent-purple", loudnessDisplay);
        VBox correlationTile  = createVizTile("PHASE",        DawIcon.PHASE_METER,    "tile-header-accent-red",    correlationDisplay);

        vizTileRow.getChildren().addAll(
                waveformTile, spectrumTile, levelsTile, loudnessTile, correlationTile
        );

        // Wire up the visualization panel controller for toggle and context menu
        Map<VisualizationPreferences.DisplayTile, Node> tileLookup = new EnumMap<>(VisualizationPreferences.DisplayTile.class);
        tileLookup.put(VisualizationPreferences.DisplayTile.SPECTRUM,    spectrumTile);
        tileLookup.put(VisualizationPreferences.DisplayTile.LEVELS,      levelsTile);
        tileLookup.put(VisualizationPreferences.DisplayTile.WAVEFORM,    waveformTile);
        tileLookup.put(VisualizationPreferences.DisplayTile.LOUDNESS,    loudnessTile);
        tileLookup.put(VisualizationPreferences.DisplayTile.CORRELATION, correlationTile);

        Preferences vizPrefs = Preferences.userNodeForPackage(VisualizationPreferences.class);
        VisualizationPreferences vizPreferences = new VisualizationPreferences(vizPrefs);
        vizPanelController = new VisualizationPanelController(
                vizTileRow, visualizationsButton, vizPreferences, tileLookup);
        vizPanelController.initialize();

        LOG.fine("Built visualization tile row with 5 display tiles");
    }

    /**
     * Builds and wires the browser/library side panel and its toolbar controller.
     *
     * @param initiallyVisible whether to show the browser panel on startup
     */
    private void buildBrowserPanel(boolean initiallyVisible) {
        BrowserPanel browserPanel = new BrowserPanel();
        browserPanelController = new BrowserPanelController(
                browserPanel, browserButton, rootPane);
        browserPanelController.setOnVisibilityChanged(
                () -> toolbarStateStore.saveBrowserVisible(browserPanelController.isPanelVisible()));
        browserPanelController.initialize();

        if (initiallyVisible) {
            browserPanelController.toggleBrowserPanel();
        }

        LOG.fine("Built browser panel with toolbar toggle");
    }

    /**
     * Creates a single visualization tile with a header label and a live display component.
     */
    private VBox createVizTile(String title, DawIcon icon, String accentClass, Region displayComponent) {
        Label header = new Label(title);
        header.getStyleClass().addAll("viz-tile-label", accentClass);
        header.setGraphic(IconNode.of(icon, 12));

        displayComponent.setMinHeight(0);
        VBox.setVgrow(displayComponent, Priority.ALWAYS);

        VBox tile = new VBox(4, header, displayComponent);
        tile.getStyleClass().add("viz-tile");
        tile.setPadding(new Insets(8));
        HBox.setHgrow(tile, Priority.ALWAYS);

        return tile;
    }

    @FXML
    private void onPlay() {
        project.getTransport().play();
        startTimeTicker();
        updateStatus();
        statusBarLabel.setText("Playing...");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PLAY_CIRCLE, 12));
    }

    @FXML
    private void onStop() {
        // Finalize recording if a recording pipeline is active
        if (recordingPipeline != null && recordingPipeline.isActive()) {
            List<AudioClip> recordedClips = recordingPipeline.stop();
            if (!recordedClips.isEmpty()) {
                // Register undo action for the recorded clips
                Map<Track, AudioClip> clipMap = Map.copyOf(recordingPipeline.getRecordedClips());
                undoManager.execute(new UndoableAction() {
                    @Override
                    public String description() { return "Record Audio"; }

                    @Override
                    public void execute() {
                        // Clips are already added by the pipeline on first execution;
                        // on redo, re-add them.
                        for (var entry : clipMap.entrySet()) {
                            if (!entry.getKey().getClips().contains(entry.getValue())) {
                                entry.getKey().addClip(entry.getValue());
                            }
                        }
                    }

                    @Override
                    public void undo() {
                        for (var entry : clipMap.entrySet()) {
                            entry.getKey().removeClip(entry.getValue());
                        }
                    }
                });
                int segmentCount = clipMap.values().size();
                statusBarLabel.setText("Recording stopped — " + segmentCount + " clip"
                        + (segmentCount > 1 ? "s" : "") + " created");
            }
            recordingPipeline = null;
        }

        project.getTransport().stop();
        stopTimeTicker();
        updateStatus();
        timeDisplay.setText("00:00:00.0");
        if (statusBarLabel.getText() == null || !statusBarLabel.getText().startsWith("Recording stopped")) {
            statusBarLabel.setText("Stopped");
        }
        statusBarLabel.setGraphic(IconNode.of(DawIcon.POWER, 12));
        // Restore button appearance in case the record blink was active
        recordButton.setOpacity(1.0);
        recordButton.setStyle("");
    }

    @FXML
    private void onPause() {
        project.getTransport().pause();
        pauseTimeTicker();
        updateStatus();
        statusBarLabel.setText("Paused");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PAUSE_CIRCLE, 12));
    }

    @FXML
    private void onRecord() {
        // Validate that at least one track is armed for recording
        List<Track> armedTracks = RecordingPipeline.findArmedTracks(project.getTracks());
        if (armedTracks.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    "No tracks are armed for recording. Please arm at least one track before recording.",
                    ButtonType.OK);
            alert.setTitle("Cannot Record");
            alert.setHeaderText("No Armed Tracks");
            alert.showAndWait();
            return;
        }

        // Create output directory for recording segments
        Path outputDir;
        try {
            outputDir = Files.createTempDirectory("daw-recording-");
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to create recording output directory", e);
            statusBarLabel.setText("Recording failed — could not create output directory");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
            return;
        }

        // Create and start the recording pipeline
        recordingPipeline = new RecordingPipeline(
                audioEngine, project.getTransport(), project.getFormat(), outputDir, armedTracks);
        recordingPipeline.start();

        startTimeTicker();
        updateStatus();
        int trackCount = armedTracks.size();
        statusBarLabel.setText("Recording — " + trackCount + " track"
                + (trackCount > 1 ? "s" : "") + " armed — auto-save active");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PHANTOM_POWER, 12));
    }

    @FXML
    private void onSkipBack() {
        project.getTransport().setPositionInBeats(0.0);
        stopTimeTicker();
        timeDisplay.setText("00:00:00.0");
        statusBarLabel.setText("Skipped to beginning");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SKIP_BACK, 12));
    }

    @FXML
    private void onSkipForward() {
        Transport transport = project.getTransport();
        double jump = 4.0 * transport.getTimeSignatureNumerator();
        transport.setPositionInBeats(transport.getPositionInBeats() + jump);
        statusBarLabel.setText("Skipped forward");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SKIP_FORWARD, 12));
    }

    @FXML
    private void onToggleLoop() {
        loopEnabled = !loopEnabled;
        loopButton.setStyle(loopEnabled
                ? "-fx-background-color: #b388ff; -fx-text-fill: #0d0d0d;" : "");
        String loopState = loopEnabled ? "Loop: ON" : "Loop: OFF";
        statusBarLabel.setText(loopState);
        statusBarLabel.setGraphic(IconNode.of(DawIcon.LOOP, 12));
        LOG.fine(loopState);
    }

    @FXML
    private void onAddAudioTrack() {
        // Enumerate available audio input devices from the backend (empty list if no backend)
        List<AudioDeviceInfo> devices = List.of();
        NativeAudioBackend backend = audioEngine.getAudioBackend();
        if (backend != null) {
            try {
                devices = backend.getAvailableDevices();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to enumerate audio devices", e);
            }
        }

        var dialog = new InputPortSelectionDialog(devices, Track.NO_INPUT_DEVICE);
        Optional<AudioDeviceInfo> selected = dialog.showAndWait();
        if (selected.isEmpty()) {
            return; // user cancelled — abort track creation
        }

        AudioDeviceInfo selectedDevice = selected.get();
        audioTrackCounter++;
        String name = "Audio " + audioTrackCounter;
        undoManager.execute(new UndoableAction() {
            private Track track;
            private HBox trackItem;
            private boolean initialExecute = true;
            @Override public String description() { return "Add Audio Track: " + name; }
            @Override public void execute() {
                if (initialExecute) {
                    track = project.createAudioTrack(name);
                    track.setInputDeviceIndex(selectedDevice.index());
                    trackItem = addTrackToUI(track);
                    initialExecute = false;
                } else {
                    project.addTrack(track);
                    trackListPanel.getChildren().add(trackItem);
                }
                updateArrangementPlaceholder();
                mixerView.refresh();
            }
            @Override public void undo() {
                project.removeTrack(track);
                trackListPanel.getChildren().remove(trackItem);
                audioTrackCounter--;
                updateArrangementPlaceholder();
                mixerView.refresh();
            }
        });
        updateUndoRedoState();
        statusBarLabel.setText("Added audio track: " + name + " ← " + selectedDevice.name());
        statusBarLabel.setGraphic(IconNode.of(DawIcon.INPUT, 12));
        projectDirty = true;
        LOG.fine(() -> "Added audio track: " + name + " with input: " + selectedDevice.name());
    }

    @FXML
    private void onAddMidiTrack() {
        var dialog = new MidiInputPortSelectionDialog(null);
        Optional<javax.sound.midi.MidiDevice.Info> selected = dialog.showAndWait();
        if (selected.isEmpty()) {
            return; // user cancelled — abort track creation
        }

        javax.sound.midi.MidiDevice.Info selectedMidi = selected.get();
        midiTrackCounter++;
        String name = "MIDI " + midiTrackCounter;
        undoManager.execute(new UndoableAction() {
            private Track track;
            private HBox trackItem;
            private boolean initialExecute = true;
            @Override public String description() { return "Add MIDI Track: " + name; }
            @Override public void execute() {
                if (initialExecute) {
                    track = project.createMidiTrack(name);
                    trackItem = addTrackToUI(track);
                    initialExecute = false;
                } else {
                    project.addTrack(track);
                    trackListPanel.getChildren().add(trackItem);
                }
                updateArrangementPlaceholder();
                mixerView.refresh();
            }
            @Override public void undo() {
                project.removeTrack(track);
                trackListPanel.getChildren().remove(trackItem);
                midiTrackCounter--;
                updateArrangementPlaceholder();
                mixerView.refresh();
            }
        });
        updateUndoRedoState();
        statusBarLabel.setText("Added MIDI track: " + name + " ← " + selectedMidi.getName());
        statusBarLabel.setGraphic(IconNode.of(DawIcon.MUSIC_NOTE, 12));
        projectDirty = true;
        LOG.fine(() -> "Added MIDI track: " + name + " with input: " + selectedMidi.getName());
    }

    @FXML
    private void onSaveProject() {
        try {
            if (projectManager.getCurrentProject() == null) {
                Path tempDir = Files.createTempDirectory("daw-project-");
                projectManager.createProject(project.getName(), tempDir.getParent());
            }
            projectManager.saveProject();
            projectDirty = false;
            int count = projectManager.getCheckpointManager().getCheckpointCount();
            checkpointLabel.setText("Saved (checkpoint #" + count + ")");
            checkpointLabel.setGraphic(IconNode.of(DawIcon.SUCCESS, 12));
            statusBarLabel.setText("Project saved");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.UPLOAD, 12));
            LOG.info("Project saved successfully");
        } catch (IOException e) {
            statusBarLabel.setText("Save failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            LOG.log(Level.WARNING, "Failed to save project", e);
        }
    }

    @FXML
    private void onNewProject() {
        if (!confirmDiscardUnsavedChanges()) {
            return;
        }
        resetProjectState();
        project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        undoManager = new UndoManager();
        audioTrackCounter = 0;
        midiTrackCounter = 0;
        projectDirty = false;
        rebuildUI();
        statusBarLabel.setText("New project created");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.FOLDER, 12));
        LOG.info("Created new project");
    }

    @FXML
    private void onOpenProject() {
        if (!confirmDiscardUnsavedChanges()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Project");
        Stage stage = (Stage) rootPane.getScene().getWindow();
        java.io.File selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }
        loadProjectFromPath(selected.toPath());
    }

    @FXML
    private void onRecentProjects() {
        List<Path> recentPaths = projectManager.getRecentProjectPaths();
        ContextMenu menu = new ContextMenu();
        if (recentPaths.isEmpty()) {
            MenuItem emptyItem = new MenuItem("No recent projects");
            emptyItem.setDisable(true);
            menu.getItems().add(emptyItem);
        } else {
            for (Path path : recentPaths) {
                MenuItem item = new MenuItem(path.getFileName().toString());
                item.setOnAction(_ -> {
                    if (confirmDiscardUnsavedChanges()) {
                        loadProjectFromPath(path);
                    }
                });
                menu.getItems().add(item);
            }
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem clearItem = new MenuItem("Clear Recent Projects");
            clearItem.setOnAction(_ -> {
                RecentProjectsStore store = projectManager.getRecentProjectsStore();
                if (store != null) {
                    store.clear();
                }
                statusBarLabel.setText("Recent projects cleared");
                statusBarLabel.setGraphic(IconNode.of(DawIcon.DELETE, 12));
            });
            menu.getItems().add(clearItem);
        }
        menu.show(recentProjectsButton,
                javafx.geometry.Side.RIGHT, 0, 0);
    }

    /**
     * Prompts the user to save unsaved changes before a destructive operation.
     *
     * @return {@code true} if the operation should proceed (saved, discarded, or no changes),
     *         {@code false} if the user cancelled
     */
    private boolean confirmDiscardUnsavedChanges() {
        if (!projectDirty) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText("You have unsaved changes.");
        alert.setContentText("Do you want to save before continuing?");
        ButtonType saveBtn = new ButtonType("Save");
        ButtonType discardBtn = new ButtonType("Discard");
        ButtonType cancelBtn = ButtonType.CANCEL;
        alert.getButtonTypes().setAll(saveBtn, discardBtn, cancelBtn);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancelBtn) {
            return false;
        }
        if (result.get() == saveBtn) {
            onSaveProject();
        }
        return true;
    }

    private void loadProjectFromPath(Path projectDir) {
        try {
            resetProjectState();
            projectManager.openProject(projectDir);
            project = new DawProject(
                    projectManager.getCurrentProject().name(),
                    AudioFormat.STUDIO_QUALITY);
            undoManager = new UndoManager();
            audioTrackCounter = 0;
            midiTrackCounter = 0;
            projectDirty = false;
            rebuildUI();
            statusBarLabel.setText("Opened: " + projectDir.getFileName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FOLDER, 12));
            LOG.info("Opened project from " + projectDir);
        } catch (IOException e) {
            statusBarLabel.setText("Open failed: " + e.getMessage());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WARNING, 12));
            LOG.log(Level.WARNING, "Failed to open project", e);
        }
    }

    private void resetProjectState() {
        try {
            if (projectManager.getCurrentProject() != null) {
                projectManager.closeProject();
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to close current project", e);
        }
    }

    private void rebuildUI() {
        trackListPanel.getChildren().clear();
        Label header = new Label("TRACKS");
        header.getStyleClass().add("panel-header");
        header.setGraphic(IconNode.of(DawIcon.MIXER, PANEL_ICON_SIZE));
        trackListPanel.getChildren().add(header);
        mixerView = new MixerView(project);
        viewCache.put(DawView.MIXER, mixerView);
        updateProjectInfo();
        updateTempoDisplay();
        updateUndoRedoState();
        updateArrangementPlaceholder();
        if (activeView == DawView.MIXER) {
            rootPane.setCenter(mixerView);
        }
    }

    @FXML
    private void onManagePlugins() {
        statusBarLabel.setText("Opening plugin manager...");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.MENU, 12));
        PluginManagerDialog dialog = new PluginManagerDialog(pluginRegistry);
        dialog.showAndWait();
        statusBarLabel.setText("Plugin manager closed");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SETTINGS, 12));
    }

    @FXML
    private void onOpenSettings() {
        statusBarLabel.setText("Opening settings...");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SETTINGS, 12));
        Preferences settingsPrefs = Preferences.userNodeForPackage(SettingsModel.class);
        SettingsModel settingsModel = new SettingsModel(settingsPrefs);
        SettingsDialog dialog = new SettingsDialog(settingsModel);
        dialog.showAndWait();
        statusBarLabel.setText("Settings closed");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
    }

    @FXML
    private void onUndo() {
        if (undoManager.undo()) {
            statusBarLabel.setText("Undo: " + undoManager.redoDescription());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.UNDO, 12));
            updateTempoDisplay();
            projectDirty = true;
        } else {
            statusBarLabel.setText("Nothing to undo");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.INFO_CIRCLE, 12));
        }
        updateUndoRedoState();
    }

    @FXML
    private void onRedo() {
        if (undoManager.redo()) {
            statusBarLabel.setText("Redo: " + undoManager.undoDescription());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.REDO, 12));
            updateTempoDisplay();
            projectDirty = true;
        } else {
            statusBarLabel.setText("Nothing to redo");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.INFO_CIRCLE, 12));
        }
        updateUndoRedoState();
    }

    private HBox addTrackToUI(Track track) {
        HBox trackItem = new HBox(8);
        trackItem.getStyleClass().add("track-item");
        trackItem.setPadding(new Insets(6, 8, 6, 8));
        trackItem.setAlignment(Pos.CENTER_LEFT);

        // Track type icon — pulls from Media, Instruments, DAW, and Volume categories
        Node typeIcon = switch (track.getType()) {
            case AUDIO        -> IconNode.of(DawIcon.MICROPHONE, TRACK_TYPE_ICON_SIZE);
            case MIDI         -> IconNode.of(DawIcon.PIANO, TRACK_TYPE_ICON_SIZE);
            case AUX          -> IconNode.of(DawIcon.MIXER, TRACK_TYPE_ICON_SIZE);
            case MASTER       -> IconNode.of(DawIcon.SPEAKER, TRACK_TYPE_ICON_SIZE);
            case BED_CHANNEL  -> IconNode.of(DawIcon.SURROUND, TRACK_TYPE_ICON_SIZE);
            case AUDIO_OBJECT -> IconNode.of(DawIcon.PAN, TRACK_TYPE_ICON_SIZE);
        };

        Label nameLabel = new Label(track.getName());
        nameLabel.getStyleClass().add("track-name");

        // Double-click to rename the track
        nameLabel.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                startTrackRename(track, nameLabel, trackItem);
            }
        });
        nameLabel.setTooltip(new Tooltip("Double-click to rename"));

        // ── I/O routing indicator (Connectivity category) ───────────────────
        DawIcon ioIcon = switch (track.getType()) {
            case AUDIO        -> DawIcon.XLR;
            case MIDI         -> DawIcon.MIDI_CABLE;
            case AUX, MASTER  -> DawIcon.LINK;
            case BED_CHANNEL  -> DawIcon.SPDIF;
            case AUDIO_OBJECT -> DawIcon.HDMI;
        };
        Label ioLabel = new Label();
        ioLabel.setGraphic(IconNode.of(ioIcon, 10));
        ioLabel.setTooltip(new Tooltip("I/O: " + ioIcon.name().replace('_', ' ')
                + " — Double-click to change input"));
        ioLabel.getStyleClass().add("status-bar-label");

        // Double-click to re-open input port selection dialog
        if (track.getType() == TrackType.AUDIO) {
            ioLabel.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    List<AudioDeviceInfo> devices = List.of();
                    NativeAudioBackend backend = audioEngine.getAudioBackend();
                    if (backend != null) {
                        try {
                            devices = backend.getAvailableDevices();
                        } catch (Exception e) {
                            LOG.log(Level.WARNING, "Failed to enumerate audio devices", e);
                        }
                    }
                    var dialog = new InputPortSelectionDialog(devices, track.getInputDeviceIndex());
                    dialog.showAndWait().ifPresent(device -> {
                        track.setInputDeviceIndex(device.index());
                        ioLabel.setTooltip(new Tooltip("Input: " + device.name()));
                        statusBarLabel.setText("Input changed: " + track.getName()
                                + " ← " + device.name());
                        statusBarLabel.setGraphic(IconNode.of(DawIcon.INPUT, 12));
                        projectDirty = true;
                    });
                }
            });
        } else if (track.getType() == TrackType.MIDI) {
            ioLabel.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    var dialog = new MidiInputPortSelectionDialog(null);
                    dialog.showAndWait().ifPresent(midiInfo -> {
                        ioLabel.setTooltip(new Tooltip("MIDI Input: " + midiInfo.getName()));
                        statusBarLabel.setText("MIDI input changed: " + track.getName()
                                + " ← " + midiInfo.getName());
                        statusBarLabel.setGraphic(IconNode.of(DawIcon.MIDI, 12));
                        projectDirty = true;
                    });
                }
            });
        }

        // ── Volume slider with icon decorations (Volume category) ───────────
        Slider volumeSlider = new Slider(0.0, 1.0, track.getVolume());
        volumeSlider.getStyleClass().add("track-volume-slider");
        volumeSlider.setPrefWidth(80);
        volumeSlider.setTooltip(new Tooltip("Volume"));
        volumeSlider.valueProperty().addListener((_, _, newVal) -> {
            track.setVolume(newVal.doubleValue());
        });
        HBox volRow = new HBox(4,
                IconNode.of(DawIcon.VOLUME_DOWN, TRACK_CONTROL_ICON_SIZE),
                volumeSlider,
                IconNode.of(DawIcon.VOLUME_UP, TRACK_CONTROL_ICON_SIZE));
        volRow.setAlignment(Pos.CENTER_LEFT);

        // ── Pan slider with audio-balance icon (Volume category) ────────────
        Slider panSlider = new Slider(-1.0, 1.0, track.getPan());
        panSlider.getStyleClass().add("track-volume-slider");
        panSlider.setPrefWidth(60);
        panSlider.setTooltip(new Tooltip("Pan (L/R)"));
        panSlider.valueProperty().addListener((_, _, newVal) -> {
            track.setPan(newVal.doubleValue());
        });
        HBox panRow = new HBox(4,
                IconNode.of(DawIcon.AUDIO_BALANCE, TRACK_CONTROL_ICON_SIZE),
                panSlider);
        panRow.setAlignment(Pos.CENTER_LEFT);

        // ── DSP insert chain indicators (DAW category) ──────────────────────
        // Shows placeholder inserts that represent the default signal chain
        HBox insertChain = new HBox(2);
        insertChain.setAlignment(Pos.CENTER_LEFT);
        if (track.getType() == TrackType.AUDIO || track.getType() == TrackType.MASTER) {
            Node gainIcon = IconNode.of(DawIcon.GAIN, 10);
            Tooltip.install(gainIcon, new Tooltip("Gain"));
            Node gateIcon = IconNode.of(DawIcon.NOISE_GATE, 10);
            Tooltip.install(gateIcon, new Tooltip("Gate"));
            Node compIcon = IconNode.of(DawIcon.COMPRESSOR, 10);
            Tooltip.install(compIcon, new Tooltip("Compressor"));
            Node eqIcon = IconNode.of(DawIcon.HIGH_PASS, 10);
            Tooltip.install(eqIcon, new Tooltip("High-Pass Filter"));
            Node limiterIcon = IconNode.of(DawIcon.LIMITER, 10);
            Tooltip.install(limiterIcon, new Tooltip("Limiter"));
            insertChain.getChildren().addAll(gainIcon, gateIcon, compIcon, eqIcon, limiterIcon);
        } else if (track.getType() == TrackType.MIDI) {
            // MIDI tracks get instrument-category hint icons
            DawIcon instrIcon = midiInstrumentIcon(track.getName());
            Node instrNode = IconNode.of(instrIcon, 10);
            Tooltip.install(instrNode, new Tooltip("Instrument: " + instrIcon.name().replace('_', ' ')));
            Node velocityIcon = IconNode.of(DawIcon.NORMALIZE, 10);
            Tooltip.install(velocityIcon, new Tooltip("Velocity / Normalize"));
            insertChain.getChildren().addAll(instrNode, velocityIcon);
        } else {
            Node routeIcon = IconNode.of(DawIcon.CROSSFADE, 10);
            Tooltip.install(routeIcon, new Tooltip("Crossfade routing"));
            insertChain.getChildren().add(routeIcon);
        }

        // ── Output assignment indicator (Recording category) ────────────────
        Label outputLabel = new Label();
        outputLabel.setGraphic(IconNode.of(DawIcon.OUTPUT, 10));
        outputLabel.setTooltip(new Tooltip("Output: Master"));
        outputLabel.getStyleClass().add("status-bar-label");

        // ── Mute button with icon (Recording category) ──────────────────────
        Button muteBtn = new Button();
        muteBtn.setGraphic(IconNode.of(DawIcon.MUTE, TRACK_CONTROL_ICON_SIZE));
        muteBtn.getStyleClass().add("track-mute-button");
        muteBtn.setTooltip(new Tooltip("Mute"));
        muteBtn.setOnAction(_ -> {
            track.setMuted(!track.isMuted());
            muteBtn.setStyle(track.isMuted()
                    ? "-fx-background-color: #ff9100; -fx-text-fill: #0d0d0d;" : "");
            // Volume-category feedback: use VOLUME_MUTE or VOLUME_OFF
            statusBarLabel.setText(track.isMuted()
                    ? "Muted: " + track.getName()
                    : "Unmuted: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(
                    track.isMuted() ? DawIcon.VOLUME_MUTE : DawIcon.VOLUME_SLIDER, 12));
        });

        // ── Solo button with icon (Recording category) ──────────────────────
        Button soloBtn = new Button();
        soloBtn.setGraphic(IconNode.of(DawIcon.SOLO, TRACK_CONTROL_ICON_SIZE));
        soloBtn.getStyleClass().add("track-solo-button");
        soloBtn.setTooltip(new Tooltip("Solo"));
        soloBtn.setOnAction(_ -> {
            track.setSolo(!track.isSolo());
            soloBtn.setStyle(track.isSolo()
                    ? "-fx-background-color: #00e676; -fx-text-fill: #0d0d0d;" : "");
            statusBarLabel.setText(track.isSolo()
                    ? "Solo: " + track.getName()
                    : "Unsolo: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.SOLO, 12));
        });

        // ── Arm button with icon and toggle action (Recording category) ─────
        Button armBtn = new Button();
        armBtn.setGraphic(IconNode.of(DawIcon.ARM_TRACK, TRACK_CONTROL_ICON_SIZE));
        armBtn.getStyleClass().add("track-arm-button");
        armBtn.setTooltip(new Tooltip("Arm for Recording"));
        armBtn.setOnAction(_ -> {
            track.setArmed(!track.isArmed());
            armBtn.setStyle(track.isArmed()
                    ? "-fx-background-color: #ff1744; -fx-text-fill: #ffffff;" : "");
            statusBarLabel.setText(track.isArmed()
                    ? "Armed: " + track.getName()
                    : "Disarmed: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(
                    track.isArmed() ? DawIcon.BELL_RING : DawIcon.ARM_TRACK, 12));
        });

        // ── Phase invert toggle (Recording category) ────────────────────────
        Button phaseBtn = new Button();
        phaseBtn.setGraphic(IconNode.of(DawIcon.PHASE, TRACK_CONTROL_ICON_SIZE));
        phaseBtn.getStyleClass().add("track-mute-button");
        phaseBtn.setTooltip(new Tooltip("Phase Invert (Ø)"));
        phaseBtn.setOnAction(_ -> {
            statusBarLabel.setText("Phase inverted: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PHASE, 12));
        });

        // ── Remove button (undoable) ────────────────────────────────────────
        Button removeBtn = new Button();
        removeBtn.setGraphic(IconNode.of(DawIcon.DELETE, TRACK_CONTROL_ICON_SIZE));
        removeBtn.getStyleClass().add("track-remove-button");
        removeBtn.setTooltip(new Tooltip("Remove Track"));
        removeBtn.setOnAction(_ -> {
            int uiIndex = trackListPanel.getChildren().indexOf(trackItem);
            undoManager.execute(new UndoableAction() {
                @Override public String description() { return "Remove Track: " + track.getName(); }
                @Override public void execute() {
                    project.removeTrack(track);
                    trackListPanel.getChildren().remove(trackItem);
                    updateArrangementPlaceholder();
                    mixerView.refresh();
                }
                @Override public void undo() {
                    project.addTrack(track);
                    if (uiIndex >= 0 && uiIndex < trackListPanel.getChildren().size()) {
                        trackListPanel.getChildren().add(uiIndex, trackItem);
                    } else {
                        trackListPanel.getChildren().add(trackItem);
                    }
                    updateArrangementPlaceholder();
                    mixerView.refresh();
                }
            });
            updateUndoRedoState();
            statusBarLabel.setText("Removed track: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.CUT, 12));
            LOG.fine(() -> "Removed track: " + track.getName());
        });

        // Spacer pushes controls to the right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ── Right-click context menu with editing actions (Editing category) ─
        ContextMenu contextMenu = buildTrackContextMenu(track, nameLabel, trackItem);
        trackItem.setOnContextMenuRequested(e ->
                contextMenu.show(trackItem, e.getScreenX(), e.getScreenY()));

        trackItem.getChildren().addAll(
                typeIcon, ioLabel, nameLabel, insertChain, volRow, panRow, spacer,
                outputLabel, phaseBtn, muteBtn, soloBtn, armBtn, removeBtn);
        trackListPanel.getChildren().add(trackItem);

        // Slide-fade entry animation: item slides in from the left and fades in
        trackItem.setTranslateX(-24);
        trackItem.setOpacity(0.0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(200), trackItem);
        slide.setToX(0.0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        FadeTransition fade = new FadeTransition(Duration.millis(200), trackItem);
        fade.setToValue(1.0);
        new ParallelTransition(slide, fade).play();

        return trackItem;
    }

    /**
     * Selects an instrument-category icon based on the MIDI track name.
     *
     * <p>Scans the track name for common instrument keywords and returns
     * the matching {@link DawIcon} from the <em>Instruments</em> category.
     * Falls back to {@link DawIcon#PIANO} for unrecognized names.</p>
     */
    private static DawIcon midiInstrumentIcon(String trackName) {
        String lower = trackName.toLowerCase();
        if (lower.contains("drum") || lower.contains("perc")) return DawIcon.DRUMS;
        if (lower.contains("guitar"))    return DawIcon.GUITAR;
        if (lower.contains("bass"))      return DawIcon.BASS_GUITAR;
        if (lower.contains("violin") || lower.contains("string")) return DawIcon.VIOLIN;
        if (lower.contains("cello"))     return DawIcon.CELLO;
        if (lower.contains("sax"))       return DawIcon.SAXOPHONE;
        if (lower.contains("trumpet"))   return DawIcon.TRUMPET;
        if (lower.contains("trombone"))  return DawIcon.TROMBONE;
        if (lower.contains("tuba"))      return DawIcon.TUBA;
        if (lower.contains("flute"))     return DawIcon.FLUTE;
        if (lower.contains("clarinet"))  return DawIcon.CLARINET;
        if (lower.contains("harp"))      return DawIcon.HARP;
        if (lower.contains("harmonica")) return DawIcon.HARMONICA;
        if (lower.contains("banjo"))     return DawIcon.BANJO;
        if (lower.contains("mandolin"))  return DawIcon.MANDOLIN;
        if (lower.contains("ukulele") || lower.contains("uke")) return DawIcon.UKULELE;
        if (lower.contains("accordion")) return DawIcon.ACCORDION;
        if (lower.contains("xylo") || lower.contains("marimba")) return DawIcon.XYLOPHONE;
        if (lower.contains("bongo"))     return DawIcon.BONGOS;
        if (lower.contains("djembe"))    return DawIcon.DJEMBE;
        if (lower.contains("maraca"))    return DawIcon.MARACAS;
        if (lower.contains("tambourine")) return DawIcon.TAMBOURINE;
        if (lower.contains("electric"))  return DawIcon.ELECTRIC_GUITAR;
        if (lower.contains("acoustic"))  return DawIcon.ACOUSTIC_GUITAR;
        if (lower.contains("organ") || lower.contains("key")) return DawIcon.KEYBOARD;
        if (lower.contains("synth"))     return DawIcon.EQUALIZER;
        if (lower.contains("pad"))       return DawIcon.PAD;
        return DawIcon.PIANO;
    }

    /**
     * Builds a right-click context menu for a track strip, providing editing
     * operations from the <em>Editing</em>, <em>Navigation</em>, <em>Social</em>,
     * <em>General</em>, and <em>File Types</em> icon categories.
     */
    private ContextMenu buildTrackContextMenu(Track track, Label nameLabel, HBox trackItem) {
        ContextMenu menu = new ContextMenu();

        // ── Editing operations ──────────────────────────────────────────────
        MenuItem copyItem = new MenuItem("Copy Track");
        copyItem.setGraphic(IconNode.of(DawIcon.COPY, 14));
        copyItem.setOnAction(_ -> { statusBarLabel.setText("Copied: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.COPY, 12)); });

        MenuItem pasteItem = new MenuItem("Paste Over");
        pasteItem.setGraphic(IconNode.of(DawIcon.PASTE, 14));
        pasteItem.setOnAction(_ -> { statusBarLabel.setText("Pasted into: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PASTE, 12)); });

        MenuItem splitItem = new MenuItem("Split at Playhead");
        splitItem.setGraphic(IconNode.of(DawIcon.SPLIT, 14));
        splitItem.setOnAction(_ -> { statusBarLabel.setText("Split: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.SPLIT, 12)); });

        MenuItem trimItem = new MenuItem("Trim to Selection");
        trimItem.setGraphic(IconNode.of(DawIcon.TRIM, 14));
        trimItem.setOnAction(_ -> { statusBarLabel.setText("Trimmed: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.TRIM, 12)); });

        MenuItem cropItem = new MenuItem("Crop");
        cropItem.setGraphic(IconNode.of(DawIcon.CROP, 14));
        cropItem.setOnAction(_ -> { statusBarLabel.setText("Cropped: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.CROP, 12)); });

        MenuItem moveItem = new MenuItem("Move");
        moveItem.setGraphic(IconNode.of(DawIcon.MOVE, 14));
        moveItem.setOnAction(_ -> { statusBarLabel.setText("Moving: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.MOVE, 12)); });

        MenuItem reverseItem = new MenuItem("Reverse");
        reverseItem.setGraphic(IconNode.of(DawIcon.REVERSE, 14));
        reverseItem.setOnAction(_ -> { statusBarLabel.setText("Reversed: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.REVERSE, 12)); });

        MenuItem selectAllItem = new MenuItem("Select All");
        selectAllItem.setGraphic(IconNode.of(DawIcon.SELECT_ALL, 14));
        selectAllItem.setOnAction(_ -> { statusBarLabel.setText("Selected all in: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.SELECT_ALL, 12)); });

        // ── Fade operations (Editing category) ──────────────────────────────
        MenuItem fadeInItem = new MenuItem("Fade In");
        fadeInItem.setGraphic(IconNode.of(DawIcon.FADE_IN, 14));
        fadeInItem.setOnAction(_ -> { statusBarLabel.setText("Fade in: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FADE_IN, 12)); });

        MenuItem fadeOutItem = new MenuItem("Fade Out");
        fadeOutItem.setGraphic(IconNode.of(DawIcon.FADE_OUT, 14));
        fadeOutItem.setOnAction(_ -> { statusBarLabel.setText("Fade out: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FADE_OUT, 12)); });

        // ── Zoom controls (Editing category) ────────────────────────────────
        MenuItem zoomInItem = new MenuItem("Zoom In");
        zoomInItem.setGraphic(IconNode.of(DawIcon.ZOOM_IN, 14));
        zoomInItem.setOnAction(_ -> { statusBarLabel.setText("Zoomed in on: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.ZOOM_IN, 12)); });

        MenuItem zoomOutItem = new MenuItem("Zoom Out");
        zoomOutItem.setGraphic(IconNode.of(DawIcon.ZOOM_OUT, 14));
        zoomOutItem.setOnAction(_ -> { statusBarLabel.setText("Zoomed out: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.ZOOM_OUT, 12)); });

        // ── Snap toggle (Editing category) ──────────────────────────────────
        MenuItem snapItem = new MenuItem(snapEnabled ? "Snap: ON" : "Snap: OFF");
        snapItem.setGraphic(IconNode.of(DawIcon.SNAP, 14));
        snapItem.setOnAction(_ -> {
            snapEnabled = !snapEnabled;
            updateSnapButtonStyle();
            snapItem.setText(snapEnabled ? "Snap: ON" : "Snap: OFF");
            statusBarLabel.setText(snapEnabled ? "Snap to grid enabled" : "Snap to grid disabled");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.SNAP, 12));
        });

        // ── Alignment (Editing category) ────────────────────────────────────
        MenuItem alignItem = new MenuItem("Align to Grid");
        alignItem.setGraphic(IconNode.of(DawIcon.ALIGN_CENTER, 14));
        alignItem.setOnAction(_ -> { statusBarLabel.setText("Aligned: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.ALIGN_CENTER, 12)); });

        MenuItem alignLeftItem = new MenuItem("Align Left");
        alignLeftItem.setGraphic(IconNode.of(DawIcon.ALIGN_LEFT, 14));
        alignLeftItem.setOnAction(_ -> { statusBarLabel.setText("Aligned left: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.ALIGN_LEFT, 12)); });

        MenuItem alignRightItem = new MenuItem("Align Right");
        alignRightItem.setGraphic(IconNode.of(DawIcon.ALIGN_RIGHT, 14));
        alignRightItem.setOnAction(_ -> { statusBarLabel.setText("Aligned right: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.ALIGN_RIGHT, 12)); });

        // ── View controls (Navigation category) ─────────────────────────────
        MenuItem expandItem = new MenuItem("Expand Track");
        expandItem.setGraphic(IconNode.of(DawIcon.EXPAND, 14));
        expandItem.setOnAction(_ -> { statusBarLabel.setText("Expanded: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.EXPAND, 12)); });

        MenuItem collapseItem = new MenuItem("Collapse Track");
        collapseItem.setGraphic(IconNode.of(DawIcon.COLLAPSE, 14));
        collapseItem.setOnAction(_ -> { statusBarLabel.setText("Collapsed: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.COLLAPSE, 12)); });

        MenuItem fullscreenItem = new MenuItem("Fullscreen Editor");
        fullscreenItem.setGraphic(IconNode.of(DawIcon.FULLSCREEN, 14));
        fullscreenItem.setOnAction(_ -> { statusBarLabel.setText("Fullscreen: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FULLSCREEN, 12)); });

        MenuItem minimizeItem = new MenuItem("Minimize");
        minimizeItem.setGraphic(IconNode.of(DawIcon.MINIMIZE, 14));
        minimizeItem.setOnAction(_ -> { statusBarLabel.setText("Minimized: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.MINIMIZE, 12)); });

        MenuItem homeItem = new MenuItem("Go to Start");
        homeItem.setGraphic(IconNode.of(DawIcon.HOME, 14));
        homeItem.setOnAction(_ -> { onSkipBack();
            statusBarLabel.setGraphic(IconNode.of(DawIcon.HOME, 12)); });

        MenuItem pipItem = new MenuItem("Picture-in-Picture");
        pipItem.setGraphic(IconNode.of(DawIcon.PIP, 14));
        pipItem.setOnAction(_ -> { statusBarLabel.setText("PiP mode: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PIP, 12)); });

        // ── Social/sharing (Social category) ────────────────────────────────
        MenuItem shareItem = new MenuItem("Share Track");
        shareItem.setGraphic(IconNode.of(DawIcon.SHARE, 14));
        shareItem.setOnAction(_ -> { statusBarLabel.setText("Sharing: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.SHARE, 12)); });

        MenuItem broadcastItem = new MenuItem("Broadcast");
        broadcastItem.setGraphic(IconNode.of(DawIcon.BROADCAST, 14));
        broadcastItem.setOnAction(_ -> { statusBarLabel.setText("Broadcasting: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.BROADCAST, 12)); });

        MenuItem streamItem = new MenuItem("Stream");
        streamItem.setGraphic(IconNode.of(DawIcon.STREAM, 14));
        streamItem.setOnAction(_ -> { statusBarLabel.setText("Streaming: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.STREAM, 12)); });

        MenuItem rateItem = new MenuItem("Rate Track");
        rateItem.setGraphic(IconNode.of(DawIcon.RATE, 14));
        rateItem.setOnAction(_ -> { statusBarLabel.setText("Rated: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.LIKE, 12)); });

        MenuItem dislikeItem = new MenuItem("Dislike Track");
        dislikeItem.setGraphic(IconNode.of(DawIcon.DISLIKE, 14));
        dislikeItem.setOnAction(_ -> { statusBarLabel.setText("Disliked: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.DISLIKE, 12)); });

        MenuItem commentItem = new MenuItem("Add Comment");
        commentItem.setGraphic(IconNode.of(DawIcon.COMMENT, 14));
        commentItem.setOnAction(_ -> { statusBarLabel.setText("Comment added to: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.COMMENT, 12)); });

        MenuItem followItem = new MenuItem("Follow Track");
        followItem.setGraphic(IconNode.of(DawIcon.FOLLOW, 14));
        followItem.setOnAction(_ -> { statusBarLabel.setText("Following: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FOLLOW, 12)); });

        // ── Export sub-options (File Types category) ─────────────────────────
        MenuItem exportWav = new MenuItem("Export as WAV");
        exportWav.setGraphic(IconNode.of(DawIcon.WAV, 14));
        exportWav.setOnAction(_ -> { statusBarLabel.setText("Exporting WAV: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WAV, 12)); });

        MenuItem exportMp3 = new MenuItem("Export as MP3");
        exportMp3.setGraphic(IconNode.of(DawIcon.MP3, 14));
        exportMp3.setOnAction(_ -> { statusBarLabel.setText("Exporting MP3: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.MP3, 12)); });

        MenuItem exportAac = new MenuItem("Export as AAC");
        exportAac.setGraphic(IconNode.of(DawIcon.AAC, 14));
        exportAac.setOnAction(_ -> { statusBarLabel.setText("Exporting AAC: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.AAC, 12)); });

        MenuItem exportMidi = new MenuItem("Export as MIDI");
        exportMidi.setGraphic(IconNode.of(DawIcon.MIDI_FILE, 14));
        exportMidi.setOnAction(_ -> { statusBarLabel.setText("Exporting MIDI: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.MIDI_FILE, 12)); });

        MenuItem exportWma = new MenuItem("Export as WMA");
        exportWma.setGraphic(IconNode.of(DawIcon.WMA, 14));
        exportWma.setOnAction(_ -> { statusBarLabel.setText("Exporting WMA: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WMA, 12)); });

        // ── General category items ──────────────────────────────────────────
        MenuItem favoriteItem = new MenuItem("Add to Favorites");
        favoriteItem.setGraphic(IconNode.of(DawIcon.FAVORITE, 14));
        favoriteItem.setOnAction(_ -> { statusBarLabel.setText("Favorited: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FAVORITE, 12)); });

        MenuItem playlistItem = new MenuItem("Add to Playlist");
        playlistItem.setGraphic(IconNode.of(DawIcon.PLAYLIST, 14));
        playlistItem.setOnAction(_ -> { statusBarLabel.setText("Added to playlist: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.PLAYLIST, 12)); });

        MenuItem filmScoreItem = new MenuItem("Film Score Mode");
        filmScoreItem.setGraphic(IconNode.of(DawIcon.FILM, 14));
        filmScoreItem.setOnAction(_ -> { statusBarLabel.setText("Film score mode: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.FILM, 12)); });

        MenuItem notifyItem = new MenuItem("Set Alert");
        notifyItem.setGraphic(IconNode.of(DawIcon.BELL, 14));
        notifyItem.setOnAction(_ -> { statusBarLabel.setText("Alert set for: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.BADGE, 12)); });

        MenuItem repeatOneItem = new MenuItem("Repeat Once");
        repeatOneItem.setGraphic(IconNode.of(DawIcon.REPEAT_ONE, 14));
        repeatOneItem.setOnAction(_ -> { statusBarLabel.setText("Repeat once: " + track.getName());
            statusBarLabel.setGraphic(IconNode.of(DawIcon.REPEAT_ONE, 12)); });

        MenuItem renameItem = new MenuItem("Rename");
        renameItem.setGraphic(IconNode.of(DawIcon.BOOKMARK, 14));
        renameItem.setOnAction(_ -> startTrackRename(track, nameLabel, trackItem));

        menu.getItems().addAll(
                copyItem, pasteItem, new SeparatorMenuItem(),
                splitItem, trimItem, cropItem, moveItem, reverseItem, new SeparatorMenuItem(),
                fadeInItem, fadeOutItem, new SeparatorMenuItem(),
                selectAllItem, alignItem, alignLeftItem, alignRightItem, snapItem, new SeparatorMenuItem(),
                zoomInItem, zoomOutItem, new SeparatorMenuItem(),
                expandItem, collapseItem, fullscreenItem, minimizeItem, pipItem, homeItem, new SeparatorMenuItem(),
                exportWav, exportMp3, exportAac, exportMidi, exportWma, new SeparatorMenuItem(),
                shareItem, broadcastItem, streamItem, rateItem, dislikeItem, commentItem, followItem, new SeparatorMenuItem(),
                favoriteItem, playlistItem, filmScoreItem, notifyItem, repeatOneItem, renameItem);

        return menu;
    }

    /**
     * Replaces the track name label with a text field for inline renaming.
     */
    private void startTrackRename(Track track, Label nameLabel, HBox trackItem) {
        int labelIndex = trackItem.getChildren().indexOf(nameLabel);
        if (labelIndex < 0) {
            return;
        }

        TextField editor = new TextField(track.getName());
        editor.getStyleClass().add("tempo-editor");
        editor.setPrefWidth(120);

        Runnable commit = () -> {
            String newName = editor.getText().strip();
            if (!newName.isEmpty() && !newName.equals(track.getName())) {
                String oldName = track.getName();
                undoManager.execute(new UndoableAction() {
                    @Override public String description() {
                        return "Rename Track: " + oldName + " → " + newName;
                    }
                    @Override public void execute() {
                        track.setName(newName);
                        nameLabel.setText(newName);
                    }
                    @Override public void undo() {
                        track.setName(oldName);
                        nameLabel.setText(oldName);
                    }
                });
                updateUndoRedoState();
                statusBarLabel.setText("Renamed track: " + oldName + " → " + newName);
                statusBarLabel.setGraphic(IconNode.of(DawIcon.BOOKMARK, 12));
            }
            trackItem.getChildren().set(labelIndex, nameLabel);
        };

        editor.setOnAction(_ -> commit.run());
        editor.focusedProperty().addListener((_, _, focused) -> {
            if (!focused) {
                commit.run();
            }
        });

        trackItem.getChildren().set(labelIndex, editor);
        editor.requestFocus();
        editor.selectAll();
    }

    // ── Animation helpers ────────────────────────────────────────────────────

    /**
     * Starts the single {@link AnimationTimer} that drives all continuous
     * frame-by-frame animations: idle visualization demo, transport glow, and
     * the time-display ticker.
     */
    private void startMainAnimTimer() {
        mainAnimTimer = new AnimationTimer() {
            private long lastNanos = 0;

            @Override
            public void handle(long now) {
                if (lastNanos == 0) {
                    lastNanos = now;
                    return;
                }
                double delta = (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;

                // Advance animation phases
                idleAnimPhase += delta;
                glowAnimPhase += delta;

                TransportState state = project.getTransport().getState();

                // Time ticker: update time display while playing or recording
                if (timeTickerRunning) {
                    long elapsedNanos = timeTickerPausedElapsedNanos + (now - timeTickerStartNanos);
                    refreshTimeDisplay(elapsedNanos);
                }

                // Transport glow on play and record buttons
                applyTransportGlow(state);

                // Idle visualization (always runs to keep displays alive)
                tickIdleVisualization(delta);
            }
        };
        mainAnimTimer.start();
    }

    /**
     * Applies a pulsing glow to the play button while playing and a blink
     * to the record button while recording.
     */
    private void applyTransportGlow(TransportState state) {
        if (state == TransportState.PLAYING) {
            double pulse = 0.5 + 0.5 * Math.sin(glowAnimPhase * Math.PI * 1.4);
            double radius = 8 + pulse * 14;
            double spread = 0.05 + pulse * 0.25;
            playButton.setStyle(String.format(
                    "-fx-effect: dropshadow(gaussian, #00e676, %.1f, %.2f, 0, 0);",
                    radius, spread));
            recordButton.setStyle("");
        } else if (state == TransportState.RECORDING) {
            // Blink record button: full opacity <-> dim, at ~2 Hz
            double blink = 0.5 + 0.5 * Math.sin(glowAnimPhase * Math.PI * 4.0);
            double opacity = 0.4 + blink * 0.6;
            recordButton.setOpacity(opacity);
            double glowRadius = 8 + blink * 16;
            double glowSpread = 0.1 + blink * 0.3;
            recordButton.setStyle(String.format(
                    "-fx-effect: dropshadow(gaussian, #ff1744, %.1f, %.2f, 0, 0);",
                    glowRadius, glowSpread));
            playButton.setStyle("");
        } else {
            playButton.setStyle("");
            recordButton.setOpacity(1.0);
            recordButton.setStyle("");
        }
    }

    /**
     * Generates synthetic spectrum and level data for the idle demo animation so
     * the visualization displays stay visually alive when no audio is being processed.
     */
    private void tickIdleVisualization(double deltaSeconds) {
        if (spectrumDisplay == null || levelMeterDisplay == null) {
            return;
        }

        // ── Spectrum: pink-noise shape with gentle wobble ──────────────────
        int binCount = idleSpectrumBins.length;
        for (int i = 1; i < binCount; i++) {
            // Logarithmic position: 0.0 (low) → 1.0 (high)
            double t = Math.log((double) i / binCount + 1.0) / Math.log(2.0);
            // Pink-noise baseline: gentle downward slope
            double base = -28.0 - t * 30.0;
            // Slow wobble across the frequency range
            double wobble = 7.0 * Math.sin(idleAnimPhase * 0.9 + t * 5.5);
            // Low-mid bump that breathes
            double bump = 5.0 * Math.exp(-Math.pow((t - 0.25), 2) / 0.01)
                    * (0.5 + 0.5 * Math.sin(idleAnimPhase * 0.6));
            idleSpectrumBins[i] = (float) Math.max(-90.0, base + wobble + bump);
        }
        idleSpectrumBins[0] = idleSpectrumBins[1];
        spectrumDisplay.updateSpectrum(new SpectrumData(idleSpectrumBins, IDLE_FFT_SIZE, 44100.0));

        // ── Level meter: gentle breathing RMS with occasional peaks ──────
        double rmsLinear = 0.18 + 0.12 * Math.abs(Math.sin(idleAnimPhase * 0.75));
        double peakBoost = 1.0 + 0.25 * Math.abs(Math.sin(idleAnimPhase * 1.8));
        double peakLinear = Math.min(rmsLinear * peakBoost * 1.3, 0.85);
        double dbRms = 20.0 * Math.log10(Math.max(rmsLinear, 1e-9));
        double dbPeak = 20.0 * Math.log10(Math.max(peakLinear, 1e-9));
        levelMeterDisplay.update(
                new LevelData(peakLinear, rmsLinear, dbPeak, dbRms, false),
                (long) (deltaSeconds * 1_000_000_000L));
    }

    /** Updates the time display label from the given elapsed nanosecond count. */
    private void refreshTimeDisplay(long elapsedNanos) {
        long elapsedMs = elapsedNanos / 1_000_000L;
        long tenths = (elapsedMs % 1000) / 100;
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long hours = minutes / 60;
        timeDisplay.setText(String.format("%02d:%02d:%02d.%d",
                hours, minutes % 60, totalSeconds % 60, tenths));
    }

    /** Starts the time ticker from zero (or resumes from a paused position). */
    private void startTimeTicker() {
        timeTickerStartNanos = System.nanoTime();
        timeTickerPausedElapsedNanos = 0;
        timeTickerRunning = true;
    }

    /** Pauses the time ticker, preserving elapsed time for clean resume. */
    private void pauseTimeTicker() {
        if (timeTickerRunning) {
            timeTickerPausedElapsedNanos += System.nanoTime() - timeTickerStartNanos;
            timeTickerRunning = false;
        }
    }

    /** Stops and resets the time ticker. */
    private void stopTimeTicker() {
        timeTickerRunning = false;
        timeTickerPausedElapsedNanos = 0;
    }

    /**
     * Adds a scale-bounce press/release animation to every transport button so
     * clicks feel tactile and immediate.
     */
    private void applyButtonPressAnimations() {
        for (Button btn : new Button[]{
                skipBackButton, playButton, pauseButton, stopButton, recordButton,
                skipForwardButton, loopButton,
                addAudioTrackButton, addMidiTrackButton,
                undoButton, redoButton, snapButton, saveButton, pluginsButton}) {
            applyPressAnimation(btn);
        }
    }

    /**
     * Attaches a subtle scale-down-then-spring-back animation to a single button.
     */
    private void applyPressAnimation(Button btn) {
        ScaleTransition pressDown = new ScaleTransition(Duration.millis(70), btn);
        pressDown.setToX(0.90);
        pressDown.setToY(0.90);
        pressDown.setInterpolator(Interpolator.EASE_IN);

        ScaleTransition springBack = new ScaleTransition(Duration.millis(130), btn);
        springBack.setToX(1.0);
        springBack.setToY(1.0);
        springBack.setInterpolator(Interpolator.EASE_OUT);

        btn.setOnMousePressed(_ -> {
            springBack.stop();
            pressDown.playFromStart();
        });
        btn.setOnMouseReleased(_ -> {
            pressDown.stop();
            springBack.playFromStart();
        });
    }

    /**
     * Prevents transport-bar buttons and the status label from truncating their
     * text by setting each control's minimum width to its preferred width.
     */
    private void preventButtonTruncation() {
        for (Button btn : new Button[]{
                skipBackButton, playButton, pauseButton, stopButton, recordButton,
                skipForwardButton, loopButton,
                addAudioTrackButton, addMidiTrackButton,
                undoButton, redoButton, snapButton, saveButton, pluginsButton}) {
            btn.setMinWidth(Region.USE_PREF_SIZE);
        }
        statusLabel.setMinWidth(Region.USE_PREF_SIZE);
    }

    // ── Status update ────────────────────────────────────────────────────────

    private void updateStatus() {
        Transport transport = project.getTransport();
        TransportState state = transport.getState();

        statusLabel.setText(state.name());
        statusLabel.getStyleClass().removeAll(
                "status-recording", "status-playing", "status-stopped", "status-paused");
        switch (state) {
            case RECORDING -> {
                statusLabel.getStyleClass().add("status-recording");
                statusLabel.setGraphic(IconNode.of(DawIcon.LIVE, 12));
            }
            case PLAYING -> {
                statusLabel.getStyleClass().add("status-playing");
                statusLabel.setGraphic(IconNode.of(DawIcon.PLAY, 12));
            }
            case PAUSED -> {
                statusLabel.getStyleClass().add("status-paused");
                statusLabel.setGraphic(IconNode.of(DawIcon.PAUSE, 12));
            }
            default -> {
                statusLabel.getStyleClass().add("status-stopped");
                statusLabel.setGraphic(IconNode.of(DawIcon.POWER, 12));
            }
        }

        // Smooth fade-in so the status label change feels polished
        statusLabel.setOpacity(0.0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), statusLabel);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.play();

        playButton.setDisable(state == TransportState.PLAYING);
        pauseButton.setDisable(state == TransportState.STOPPED || state == TransportState.PAUSED);
        recordButton.setDisable(state == TransportState.RECORDING);
        stopButton.setDisable(state == TransportState.STOPPED);
    }

    private void updateTempoDisplay() {
        tempoLabel.setText(String.format("%.1f BPM", project.getTransport().getTempo()));
        tempoLabel.setGraphic(IconNode.of(DawIcon.KNOB, PANEL_ICON_SIZE));
    }

    private void updateProjectInfo() {
        AudioFormat fmt = project.getFormat();
        projectInfoLabel.setText(String.format("%s  ·  %.0f kHz / %d-bit / %dch",
                project.getName(),
                fmt.sampleRate() / 1000.0,
                fmt.bitDepth(),
                fmt.channels()));
        // Use a FILE_TYPES icon appropriate to the audio format's bit depth
        DawIcon fmtIcon = switch (fmt.bitDepth()) {
            case 32 -> DawIcon.AIFF;
            case 24 -> DawIcon.FLAC;
            case 16 -> DawIcon.WAV;
            default -> DawIcon.OGG;
        };
        projectInfoLabel.setGraphic(IconNode.of(fmtIcon, 12));

        // Monitoring label indicates channel configuration (Volume category)
        DawIcon channelIcon = switch (fmt.channels()) {
            case 1 -> DawIcon.MONO;
            case 2 -> DawIcon.STEREO;
            default -> DawIcon.SURROUND;
        };
        monitoringLabel.setGraphic(IconNode.of(channelIcon, 12));
        monitoringLabel.setText(switch (fmt.channels()) {
            case 1 -> "Mono";
            case 2 -> "Stereo";
            default -> fmt.channels() + "ch Surround";
        });

        // I/O routing label — sample rate determines interface type hint
        DawIcon routingIcon = (fmt.sampleRate() >= 96_000.0) ? DawIcon.THUNDERBOLT : DawIcon.USB;
        ioRoutingLabel.setGraphic(IconNode.of(routingIcon, 12));
        ioRoutingLabel.setText(String.format("%.0f kHz I/O", fmt.sampleRate() / 1000.0));
    }

    private void updateCheckpointStatus() {
        checkpointLabel.setText("Auto-save: ON");
        checkpointLabel.setGraphic(IconNode.of(DawIcon.HISTORY, 12));
        // I/O routing label gets initial clock-based icon to indicate connection active
        ioRoutingLabel.setGraphic(IconNode.of(DawIcon.CLOCK, 12));
        ioRoutingLabel.setText("Initializing I/O...");
    }

    private void updateArrangementPlaceholder() {
        arrangementPlaceholder.setVisible(project.getTracks().isEmpty());
    }

    private void updateUndoRedoState() {
        undoButton.setDisable(!undoManager.canUndo());
        redoButton.setDisable(!undoManager.canRedo());

        String undoTip = undoManager.canUndo()
                ? "Undo: " + undoManager.undoDescription() + " (Ctrl+Z)"
                : "Nothing to undo";
        String redoTip = undoManager.canRedo()
                ? "Redo: " + undoManager.redoDescription() + " (Ctrl+Shift+Z)"
                : "Nothing to redo";
        undoButton.setTooltip(new Tooltip(undoTip));
        redoButton.setTooltip(new Tooltip(redoTip));
    }
}
