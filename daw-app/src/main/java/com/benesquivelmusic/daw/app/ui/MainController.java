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
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;

import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import javafx.scene.transform.Scale;
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

    /** Icon size for panel-header labels — shared with {@link ToolbarAppearanceController}. */
    private static final double PANEL_ICON_SIZE = ToolbarAppearanceController.PANEL_ICON_SIZE;

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
    @FXML private Label recIndicator;
    @FXML private HBox notificationBarContainer;
    @FXML private HBox transportGroup;
    @FXML private HBox trackGroup;
    @FXML private HBox undoRedoGroup;
    @FXML private HBox utilityGroup;
    @FXML private VBox trackListPanel;
    @FXML private HBox vizTileRow;
    @FXML private VBox sidebarToolbar;
    @FXML private Button homeButton;
    @FXML private Button arrangementViewButton;
    @FXML private Button mixerViewButton;
    @FXML private Button editorViewButton;
    @FXML private Button telemetryViewButton;
    @FXML private Button masteringViewButton;
    @FXML private Button newProjectButton;
    @FXML private Button openProjectButton;
    @FXML private Button saveProjectButton;
    @FXML private Button recentProjectsButton;
    @FXML private Button importSessionButton;
    @FXML private Button exportSessionButton;
    @FXML private Button browserButton;
    @FXML private Button searchButton;
    @FXML private Button historyButton;
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
    private boolean snapEnabled = true;
    private GridResolution gridResolution = GridResolution.QUARTER;
    private boolean projectDirty;
    private AudioEngine audioEngine;
    private NotificationBar notificationBar;

    // ── Transport controller ─────────────────────────────────────────────────
    /** Manages transport actions, time ticker, and recording pipeline. */
    private TransportController transportController;

    // ── Project lifecycle controller ─────────────────────────────────────────
    /** Manages project open/save/import/export and associated state resets. */
    private ProjectLifecycleController projectLifecycleController;

    // ── Session interchange ──────────────────────────────────────────────────
    /** Handles DAWproject import/export logic without JavaFX dependencies. */
    private final SessionInterchangeController sessionInterchangeController =
            new SessionInterchangeController();
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
    /** The mastering view panel — mastering chain with presets and A/B comparison. */
    private MasteringView masteringView;

    // ── Clipboard & selection state ─────────────────────────────────────────
    /** Tracks whether the in-app clipboard has content for paste operations. */
    private final ClipboardManager clipboardManager = new ClipboardManager();
    /** Tracks the current time selection range for trim/crop operations. */
    private final SelectionModel selectionModel = new SelectionModel();

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

    // ── Undo history panel ───────────────────────────────────────────────────
    /** The undo history panel displayed on the right side of the root pane. */
    private UndoHistoryPanel undoHistoryPanel;
    /** Whether the undo history panel is currently visible. */
    private boolean historyPanelVisible;

    // ── Notification history ─────────────────────────────────────────────────
    /** In-memory store for warning/error notifications. */
    private final NotificationHistoryService notificationHistoryService =
            new NotificationHistoryService();
    /** The notification history panel displayed on the right side of the root pane. */
    private NotificationHistoryPanel notificationHistoryPanel;
    /** Whether the notification history panel is currently visible. */
    private boolean notificationHistoryPanelVisible;

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

    // ── Key binding manager ──────────────────────────────────────────────────
    /** Manages customizable keyboard shortcuts for DAW actions. */
    private KeyBindingManager keyBindingManager;

    // ── Track-strip controller ──────────────────────────────────────────────
    /** Builds and manages individual track strips in the arrangement track list. */
    private TrackStripController trackStripController;

    // ── Animation controller ────────────────────────────────────────────────
    /** Encapsulates all frame-by-frame and transition-based animations. */
    private AnimationController animationController;

    // ── Toolbar appearance controller ────────────────────────────────────────
    /** Applies icons, tooltips, and overflow behavior to the toolbar. */
    private ToolbarAppearanceController toolbarAppearanceController;

    /** Reference kept for the idle demo animation. */
    private SpectrumDisplay spectrumDisplay;
    /** Reference kept for the idle demo animation. */
    private LevelMeterDisplay levelMeterDisplay;

    @FXML
    private void initialize() {
        project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        pluginRegistry = new PluginRegistry();
        undoManager = new UndoManager();
        undoManager.addHistoryListener(manager -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                updateUndoRedoState();
            } else {
                javafx.application.Platform.runLater(this::updateUndoRedoState);
            }
        });
        audioEngine = new AudioEngine(project.getFormat());

        CheckpointManager checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        Preferences prefs = Preferences.userNodeForPackage(MainController.class);
        RecentProjectsStore recentProjectsStore = new RecentProjectsStore(prefs);
        projectManager = new ProjectManager(checkpointManager, recentProjectsStore);
        toolbarStateStore = new ToolbarStateStore(prefs);
        keyBindingManager = new KeyBindingManager(prefs.node("keybindings"));

        // Restore persisted toolbar state before UI initialization
        activeView = toolbarStateStore.loadActiveView();
        activeEditTool = toolbarStateStore.loadEditTool();
        snapEnabled = toolbarStateStore.loadSnapEnabled();
        gridResolution = toolbarStateStore.loadGridResolution();

        audioTrackCounter = 0;
        midiTrackCounter = 0;

        createToolbarAppearanceController();
        toolbarAppearanceController.apply();
        buildVisualizationTiles();
        buildBrowserPanel(toolbarStateStore.loadBrowserVisible());
        buildHistoryPanel();
        setupTempoEditor();
        initializeNotificationBar();
        createTransportController();
        createProjectLifecycleController();
        createAnimationController();
        animationController.applyButtonPressAnimations();
        transportController.updateStatus();
        updateTempoDisplay();
        updateProjectInfo();
        updateCheckpointStatus();
        updateUndoRedoState();
        animationController.start();
        initializeViewNavigation();
        createTrackStripController();
        initializeEditTools();
        initializeSnapControls();
        initializeZoomControls();
        initializeToolbarCollapse(prefs);
        initializeToolbarContextMenus();
        initializeSidebarActions();

        // Register keyboard shortcuts after the scene is available
        playButton.sceneProperty().addListener((_, _, scene) -> {
            if (scene != null) {
                registerKeyboardShortcuts();
            }
        });

        LOG.info("DAW initialized with studio quality format");
    }

    /**
     * Creates the {@link NotificationBar} and adds it to the notification container
     * in the bottom section, just above the status bar. Also wires the
     * {@link NotificationHistoryService} so that warnings and errors are
     * recorded for the notification history panel.
     */
    private void initializeNotificationBar() {
        notificationBar = new NotificationBar();
        notificationBar.setHistoryService(notificationHistoryService);
        notificationBarContainer.getChildren().add(notificationBar);
        HBox.setHgrow(notificationBar, Priority.ALWAYS);
        notificationHistoryPanel = new NotificationHistoryPanel(notificationHistoryService);
    }

    /**
     * Creates the {@link ToolbarAppearanceController} with all button, label,
     * and overflow-group references needed for icon, tooltip, and overflow
     * initialization.  Must be called after the {@link KeyBindingManager} is
     * available.
     */
    private void createToolbarAppearanceController() {
        toolbarAppearanceController = new ToolbarAppearanceController(
                new ToolbarAppearanceController.TransportButtons(
                        skipBackButton, playButton, pauseButton, stopButton,
                        recordButton, skipForwardButton, loopButton),
                new ToolbarAppearanceController.ToolbarButtons(
                        addAudioTrackButton, addMidiTrackButton, undoButton,
                        redoButton, snapButton, saveButton, pluginsButton),
                new ToolbarAppearanceController.SidebarButtons(
                        homeButton, arrangementViewButton, mixerViewButton,
                        editorViewButton, telemetryViewButton, masteringViewButton,
                        newProjectButton, openProjectButton, saveProjectButton,
                        recentProjectsButton, importSessionButton, exportSessionButton,
                        browserButton, searchButton, historyButton,
                        pluginsSidebarButton, visualizationsButton, settingsButton,
                        expandCollapseButton, helpButton),
                new ToolbarAppearanceController.EditToolButtons(
                        pointerToolButton, pencilToolButton, eraserToolButton,
                        scissorsToolButton, glueToolButton),
                new ToolbarAppearanceController.ZoomButtons(
                        zoomInButton, zoomOutButton, zoomToFitButton),
                new ToolbarAppearanceController.AppearanceLabels(
                        statusLabel, timeDisplay, tracksPanelHeader,
                        arrangementPanelHeader, arrangementPlaceholder,
                        monitoringLabel, checkpointLabel, statusBarLabel,
                        ioRoutingLabel, recIndicator),
                new ToolbarAppearanceController.OverflowGroups(
                        utilityGroup, undoRedoGroup),
                rootPane, keyBindingManager);
    }

    /**
     * Creates the {@link TransportController} with the current project,
     * audio engine, undo manager, and UI elements.  Must be called after
     * {@code initializeNotificationBar()}.
     */
    private void createTransportController() {
        transportController = new TransportController(
                project, audioEngine, undoManager, notificationBar,
                statusLabel, timeDisplay, statusBarLabel, recIndicator,
                playButton, pauseButton, stopButton, recordButton, loopButton,
                new TransportController.Host() {
                    @Override public boolean isSnapEnabled() { return snapEnabled; }
                    @Override public GridResolution gridResolution() { return gridResolution; }
                    @Override public void startTimeTicker() { animationController.startTimeTicker(); }
                    @Override public void pauseTimeTicker() { animationController.pauseTimeTicker(); }
                    @Override public void stopTimeTicker() { animationController.stopTimeTicker(); }
                });
    }

    /**
     * Creates the {@link AnimationController} with the visualization displays,
     * time display label, transport buttons, and all animated buttons.  Must be
     * called after {@code buildVisualizationTiles()} and {@code createTransportController()}.
     */
    private void createAnimationController() {
        animationController = new AnimationController(
                spectrumDisplay, levelMeterDisplay, timeDisplay,
                playButton, recordButton,
                new Button[]{
                        skipBackButton, playButton, pauseButton, stopButton, recordButton,
                        skipForwardButton, loopButton,
                        addAudioTrackButton, addMidiTrackButton,
                        undoButton, redoButton, snapButton, saveButton, pluginsButton},
                () -> project.getTransport().getState());
    }

    /**
     * Creates (or recreates) the {@link TrackStripController} with the current
     * project, undo manager, mixer view, and other dependencies. Must be called
     * after {@code initializeViewNavigation()} and again after {@code rebuildUI()}.
     */
    private void createTrackStripController() {
        trackStripController = new TrackStripController(
                project, undoManager, audioEngine, mixerView,
                notificationBar, statusBarLabel, trackListPanel, rootPane,
                clipboardManager, selectionModel,
                new TrackStripController.Host() {
                    @Override public void updateArrangementPlaceholder() {
                        MainController.this.updateArrangementPlaceholder();
                    }
                    @Override public void updateUndoRedoState() {
                        MainController.this.updateUndoRedoState();
                    }
                    @Override public void undoLastAction() { onUndo(); }
                    @Override public void zoomIn() { onZoomIn(); }
                    @Override public void zoomOut() { onZoomOut(); }
                    @Override public void toggleSnap() {
                        snapEnabled = !snapEnabled;
                        updateSnapButtonStyle();
                        syncSnapStateToEditorView();
                    }
                    @Override public void skipToStart() { transportController.onSkipBack(); }
                    @Override public void markProjectDirty() { projectDirty = true; }
                    @Override public boolean isSnapEnabled() { return snapEnabled; }
                    @Override public ZoomLevel currentZoomLevel() {
                        return viewZoomLevels.get(activeView);
                    }
                    @Override public EditorView editorView() { return editorView; }
                });
    }

    /**
     * Creates the {@link ProjectLifecycleController} with the current project
     * manager, session interchange controller, and UI elements. Must be called
     * after {@code initializeNotificationBar()}.
     */
    private void createProjectLifecycleController() {
        projectLifecycleController = new ProjectLifecycleController(
                projectManager, sessionInterchangeController, notificationBar,
                statusBarLabel, checkpointLabel, rootPane, recentProjectsButton,
                trackListPanel,
                new ProjectLifecycleController.Host() {
                    @Override public DawProject project() { return project; }
                    @Override public void setProject(DawProject p) { project = p; }
                    @Override public UndoManager undoManager() { return undoManager; }
                    @Override public void setUndoManager(UndoManager um) { undoManager = um; }
                    @Override public boolean isProjectDirty() { return projectDirty; }
                    @Override public void setProjectDirty(boolean dirty) { projectDirty = dirty; }
                    @Override public void resetTrackCounters() {
                        audioTrackCounter = 0;
                        midiTrackCounter = 0;
                    }
                    @Override public void rebuildHistoryPanel() {
                        MainController.this.rebuildHistoryPanel();
                    }
                    @Override public void onProjectUIRebuild(MixerView newMixerView) {
                        mixerView = newMixerView;
                        viewCache.put(DawView.MIXER, mixerView);
                        createTransportController();
                        transportController.updateStatus();
                        createTrackStripController();
                        updateProjectInfo();
                        updateTempoDisplay();
                        updateUndoRedoState();
                        updateArrangementPlaceholder();
                        if (activeView == DawView.MIXER) {
                            rootPane.setCenter(mixerView);
                        }
                    }
                });
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
        editorView.setActiveEditTool(activeEditTool);
        editorView.setOnToolChanged(this::selectEditTool);
        editorView.setOnTrimAction(this::onEditorTrim);
        editorView.setOnFadeInAction(this::onEditorFadeIn);
        editorView.setOnFadeOutAction(this::onEditorFadeOut);
        editorView.setSnapState(snapEnabled, gridResolution,
                project.getTransport().getTimeSignatureNumerator());
        viewCache.put(DawView.EDITOR, editorView);

        // Telemetry view — sound wave telemetry room visualizer
        telemetryView = new TelemetryView();
        viewCache.put(DawView.TELEMETRY, telemetryView);

        // Mastering view — mastering chain with presets and A/B comparison
        masteringView = new MasteringView();
        viewCache.put(DawView.MASTERING, masteringView);

        // Wire sidebar view buttons
        arrangementViewButton.setOnAction(event -> switchView(DawView.ARRANGEMENT));
        mixerViewButton.setOnAction(event -> switchView(DawView.MIXER));
        editorViewButton.setOnAction(event -> switchView(DawView.EDITOR));
        telemetryViewButton.setOnAction(event -> switchView(DawView.TELEMETRY));
        masteringViewButton.setOnAction(event -> switchView(DawView.MASTERING));

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
        Button[] viewButtons = { arrangementViewButton, mixerViewButton, editorViewButton, telemetryViewButton, masteringViewButton };
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
        if (editorView != null) {
            editorView.setActiveEditTool(tool);
        }
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
        syncSnapStateToEditorView();
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
        Button[] viewBtns = { arrangementViewButton, mixerViewButton, editorViewButton, telemetryViewButton, masteringViewButton };
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
                projectLifecycleController::loadProjectFromPath
        );
        toolbarContextMenuController.initialize();
        LOG.fine("Toolbar context menus initialized");
    }

    /**
     * Wires the Home, Search, and Help sidebar buttons to their action handlers.
     */
    private void initializeSidebarActions() {
        homeButton.setOnAction(event -> onHome());
        searchButton.setOnAction(event -> onSearch());
        helpButton.setOnAction(event -> onHelp());
        LOG.fine("Sidebar action buttons initialized");
    }

    /**
     * Handles the Home button action: switches to the arrangement view,
     * resets zoom to fit, clears the selection, and updates the status bar.
     */
    void onHome() {
        switchView(DawView.ARRANGEMENT);
        ZoomLevel zoom = viewZoomLevels.get(DawView.ARRANGEMENT);
        zoom.zoomToFit();
        selectionModel.clearSelection();
        statusBarLabel.setText("Home \u2014 returned to default arrangement view");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.HOME, 12));
        LOG.fine("Home action: switched to arrangement view, reset zoom, cleared selection");
    }

    /**
     * Handles the Search button action: ensures the browser panel is visible
     * and moves focus to the browser search field.
     */
    void onSearch() {
        if (!browserPanelController.isPanelVisible()) {
            browserPanelController.toggleBrowserPanel();
        }
        BrowserPanel browserPanel = browserPanelController.getBrowserPanel();
        browserPanel.getSearchField().requestFocus();
        statusBarLabel.setText("Search \u2014 browser panel opened");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.SEARCH, 12));
        LOG.fine("Search action: opened browser panel and focused search field");
    }

    /**
     * Handles the Help button action: opens the {@link HelpDialog}.
     */
    void onHelp() {
        statusBarLabel.setText("Opening help...");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.INFO, 12));
        HelpDialog dialog = new HelpDialog();
        dialog.showAndWait();
        statusBarLabel.setText("Help closed");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
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

    /**
     * Pushes the current snap-to-grid state from this controller to the
     * {@link EditorView} so that note placement and other editor operations
     * respect the active snap settings.
     */
    private void syncSnapStateToEditorView() {
        if (editorView != null) {
            editorView.setSnapState(snapEnabled, gridResolution,
                    project.getTransport().getTimeSignatureNumerator());
        }
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
     * Returns the clipboard manager for tracking copy/paste state.
     *
     * @return the clipboard manager
     */
    public ClipboardManager getClipboardManager() {
        return clipboardManager;
    }

    /**
     * Returns the selection model for tracking the current time selection.
     *
     * @return the selection model
     */
    public SelectionModel getSelectionModel() {
        return selectionModel;
    }

    /**
     * Registers global keyboard shortcuts for transport and project actions.
     *
     * <p>Uses the {@link KeyBindingManager} to resolve the current key combination
     * for each {@link DawAction}, allowing shortcuts to be user-customized
     * through the Settings &gt; Key Bindings tab.</p>
     */
    private void registerKeyboardShortcuts() {
        Scene scene = playButton.getScene();
        if (scene == null) {
            return;
        }
        ObservableMap<KeyCombination, Runnable> accelerators = scene.getAccelerators();

        // Build a map from DawAction to its handler
        Map<DawAction, Runnable> actionHandlers = new EnumMap<>(DawAction.class);
        actionHandlers.put(DawAction.PLAY_STOP, () -> {
            if (project.getTransport().getState() == TransportState.PLAYING) {
                onStop();
            } else {
                onPlay();
            }
        });
        actionHandlers.put(DawAction.STOP, this::onStop);
        actionHandlers.put(DawAction.RECORD, this::onRecord);
        actionHandlers.put(DawAction.SKIP_TO_START, this::onSkipBack);
        actionHandlers.put(DawAction.SKIP_TO_END, this::onSkipForward);
        actionHandlers.put(DawAction.TOGGLE_LOOP, this::onToggleLoop);
        actionHandlers.put(DawAction.UNDO, this::onUndo);
        actionHandlers.put(DawAction.REDO, this::onRedo);
        actionHandlers.put(DawAction.SAVE, projectLifecycleController::onSaveProject);
        actionHandlers.put(DawAction.NEW_PROJECT, projectLifecycleController::onNewProject);
        actionHandlers.put(DawAction.OPEN_PROJECT, projectLifecycleController::onOpenProject);
        actionHandlers.put(DawAction.IMPORT_SESSION, projectLifecycleController::onImportSession);
        actionHandlers.put(DawAction.EXPORT_SESSION, projectLifecycleController::onExportSession);
        actionHandlers.put(DawAction.TOGGLE_SNAP, this::onToggleSnap);
        actionHandlers.put(DawAction.ADD_AUDIO_TRACK, this::onAddAudioTrack);
        actionHandlers.put(DawAction.ADD_MIDI_TRACK, this::onAddMidiTrack);
        actionHandlers.put(DawAction.TOOL_POINTER, () -> selectEditTool(EditTool.POINTER));
        actionHandlers.put(DawAction.TOOL_PENCIL, () -> selectEditTool(EditTool.PENCIL));
        actionHandlers.put(DawAction.TOOL_ERASER, () -> selectEditTool(EditTool.ERASER));
        actionHandlers.put(DawAction.TOOL_SCISSORS, () -> selectEditTool(EditTool.SCISSORS));
        actionHandlers.put(DawAction.TOOL_GLUE, () -> selectEditTool(EditTool.GLUE));
        actionHandlers.put(DawAction.ZOOM_IN, this::onZoomIn);
        actionHandlers.put(DawAction.ZOOM_OUT, this::onZoomOut);
        actionHandlers.put(DawAction.ZOOM_TO_FIT, this::onZoomToFit);
        actionHandlers.put(DawAction.VIEW_ARRANGEMENT, () -> switchView(DawView.ARRANGEMENT));
        actionHandlers.put(DawAction.VIEW_MIXER, () -> switchView(DawView.MIXER));
        actionHandlers.put(DawAction.VIEW_EDITOR, () -> switchView(DawView.EDITOR));
        actionHandlers.put(DawAction.VIEW_TELEMETRY, () -> switchView(DawView.TELEMETRY));
        actionHandlers.put(DawAction.VIEW_MASTERING, () -> switchView(DawView.MASTERING));
        actionHandlers.put(DawAction.TOGGLE_BROWSER, () -> {
            if (historyPanelVisible) {
                toggleHistoryPanel();
            }
            browserPanelController.toggleBrowserPanel();
        });
        actionHandlers.put(DawAction.TOGGLE_HISTORY, this::toggleHistoryPanel);
        actionHandlers.put(DawAction.TOGGLE_NOTIFICATION_HISTORY, this::toggleNotificationHistoryPanel);
        actionHandlers.put(DawAction.TOGGLE_VISUALIZATIONS, () -> vizPanelController.toggleRowVisibility());
        actionHandlers.put(DawAction.OPEN_SETTINGS, this::onOpenSettings);
        actionHandlers.put(DawAction.TOGGLE_TOOLBAR, this::onToggleToolbar);

        // Register each action's current binding from the KeyBindingManager
        for (DawAction action : DawAction.values()) {
            Runnable handler = actionHandlers.get(action);
            if (handler == null) {
                continue;
            }
            Optional<KeyCombination> binding = keyBindingManager.getBinding(action);
            binding.ifPresent(kc -> accelerators.put(kc, handler));
        }

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
            notificationBar.show(NotificationLevel.ERROR, "Invalid tempo — must be 20–999 BPM");
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
        browserPanelController.setOnVisibilityChanged(() -> {
            toolbarStateStore.saveBrowserVisible(browserPanelController.isPanelVisible());
            if (browserPanelController.isPanelVisible() && historyPanelVisible) {
                historyPanelVisible = false;
                updateHistoryButtonActiveState();
            }
            if (browserPanelController.isPanelVisible() && notificationHistoryPanelVisible) {
                notificationHistoryPanelVisible = false;
            }
        });
        browserPanelController.initialize();

        if (initiallyVisible) {
            browserPanelController.toggleBrowserPanel();
        }

        LOG.fine("Built browser panel with toolbar toggle");
    }

    /**
     * Builds the undo history panel and wires the sidebar history button.
     */
    private void buildHistoryPanel() {
        undoHistoryPanel = new UndoHistoryPanel(undoManager);
        historyButton.setOnAction(event -> toggleHistoryPanel());
        LOG.fine("Built undo history panel");
    }

    /**
     * Disposes the current history panel and creates a new one bound to the
     * current {@link UndoManager}. Called after creating a new project or
     * opening an existing one.
     */
    private void rebuildHistoryPanel() {
        if (undoHistoryPanel != null) {
            undoHistoryPanel.dispose();
        }
        undoManager.addHistoryListener(manager -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                updateUndoRedoState();
            } else {
                javafx.application.Platform.runLater(this::updateUndoRedoState);
            }
        });
        undoHistoryPanel = new UndoHistoryPanel(undoManager);
        if (historyPanelVisible) {
            rootPane.setRight(undoHistoryPanel);
        }
    }

    /**
     * Toggles the undo history panel on the right side of the root pane.
     * If the browser panel is visible when the history panel is shown,
     * the browser panel is hidden first.
     */
    void toggleHistoryPanel() {
        historyPanelVisible = !historyPanelVisible;
        if (historyPanelVisible) {
            if (browserPanelController.isPanelVisible()) {
                browserPanelController.toggleBrowserPanel();
            }
            if (notificationHistoryPanelVisible) {
                toggleNotificationHistoryPanel();
            }
            undoHistoryPanel.setOpacity(0.0);
            rootPane.setRight(undoHistoryPanel);
            javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(
                            javafx.util.Duration.millis(250),
                            new javafx.animation.KeyValue(undoHistoryPanel.opacityProperty(), 1.0))
            );
            timeline.play();
            statusBarLabel.setText("Undo History panel opened");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.HISTORY, 12));
        } else {
            javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(
                            javafx.util.Duration.millis(250),
                            new javafx.animation.KeyValue(undoHistoryPanel.opacityProperty(), 0.0))
            );
            timeline.setOnFinished(event -> rootPane.setRight(null));
            timeline.play();
            statusBarLabel.setText("Undo History panel closed");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.HISTORY, 12));
        }
        updateHistoryButtonActiveState();
    }

    private void updateHistoryButtonActiveState() {
        List<String> styles = historyButton.getStyleClass();
        if (historyPanelVisible) {
            if (!styles.contains("toolbar-button-active")) {
                styles.add("toolbar-button-active");
            }
        } else {
            styles.remove("toolbar-button-active");
        }
    }

    // ── Notification history panel ───────────────────────────────────────────

    /**
     * Toggles the notification history panel on the right side of the root pane.
     * If the browser panel or undo history panel is visible, it is hidden first.
     */
    void toggleNotificationHistoryPanel() {
        notificationHistoryPanelVisible = !notificationHistoryPanelVisible;
        if (notificationHistoryPanelVisible) {
            if (browserPanelController.isPanelVisible()) {
                browserPanelController.toggleBrowserPanel();
            }
            if (historyPanelVisible) {
                toggleHistoryPanel();
            }
            notificationHistoryPanel.setOpacity(0.0);
            rootPane.setRight(notificationHistoryPanel);
            javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(
                            javafx.util.Duration.millis(250),
                            new javafx.animation.KeyValue(
                                    notificationHistoryPanel.opacityProperty(), 1.0))
            );
            timeline.play();
            statusBarLabel.setText("Notification History panel opened");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.BELL_RING, 12));
        } else {
            javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(
                            javafx.util.Duration.millis(250),
                            new javafx.animation.KeyValue(
                                    notificationHistoryPanel.opacityProperty(), 0.0))
            );
            timeline.setOnFinished(event -> rootPane.setRight(null));
            timeline.play();
            statusBarLabel.setText("Notification History panel closed");
            statusBarLabel.setGraphic(IconNode.of(DawIcon.BELL_RING, 12));
        }
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
        transportController.onPlay();
    }

    @FXML
    private void onStop() {
        transportController.onStop();
    }

    @FXML
    private void onPause() {
        transportController.onPause();
    }

    @FXML
    private void onRecord() {
        transportController.onRecord();
    }

    @FXML
    private void onSkipBack() {
        transportController.onSkipBack();
    }

    @FXML
    private void onSkipForward() {
        transportController.onSkipForward();
    }

    @FXML
    private void onToggleLoop() {
        transportController.onToggleLoop();
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

        InputPortSelectionDialog dialog = new InputPortSelectionDialog(devices, Track.NO_INPUT_DEVICE);
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
                    trackItem = trackStripController.addTrackToUI(track);
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
        notificationBar.show(NotificationLevel.SUCCESS, "Added audio track: " + name);
        projectDirty = true;
        LOG.fine(() -> "Added audio track: " + name + " with input: " + selectedDevice.name());
    }

    @FXML
    private void onAddMidiTrack() {
        MidiInputPortSelectionDialog dialog = new MidiInputPortSelectionDialog(null);
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
                    trackItem = trackStripController.addTrackToUI(track);
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
        notificationBar.show(NotificationLevel.SUCCESS, "Added MIDI track: " + name);
        projectDirty = true;
        LOG.fine(() -> "Added MIDI track: " + name + " with input: " + selectedMidi.getName());
    }

    @FXML
    private void onSaveProject() {
        projectLifecycleController.onSaveProject();
    }

    @FXML
    private void onNewProject() {
        projectLifecycleController.onNewProject();
    }

    @FXML
    private void onOpenProject() {
        projectLifecycleController.onOpenProject();
    }

    @FXML
    private void onRecentProjects() {
        projectLifecycleController.onRecentProjects();
    }

    @FXML
    private void onImportSession() {
        projectLifecycleController.onImportSession();
    }

    @FXML
    private void onExportSession() {
        projectLifecycleController.onExportSession();
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
        String previousPluginPaths = settingsModel.getPluginScanPaths();
        SettingsDialog dialog = new SettingsDialog(settingsModel);
        dialog.setSettingsChangeListener(model -> applyLiveSettings(model, previousPluginPaths));
        dialog.showAndWait();
        statusBarLabel.setText("Settings closed");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.STATUS, 12));
    }

    /**
     * Propagates live settings changes to the running subsystems.
     *
     * <p>Applies the UI scale transform, reconfigures the checkpoint manager
     * auto-save interval, updates the transport tempo, and triggers a plugin
     * re-scan if scan paths changed.</p>
     *
     * @param model               the updated settings model
     * @param previousPluginPaths the plugin scan paths before the change
     */
    private void applyLiveSettings(SettingsModel model, String previousPluginPaths) {
        // UI scale
        Scene scene = rootPane.getScene();
        if (scene != null && scene.getRoot() != null) {
            double scale = model.getUiScale();
            scene.getRoot().getTransforms().clear();
            scene.getRoot().getTransforms().add(new Scale(scale, scale));
        }

        // Auto-save interval
        CheckpointManager checkpointManager = projectManager.getCheckpointManager();
        AutoSaveConfig currentConfig = checkpointManager.getConfig();
        java.time.Duration newInterval = java.time.Duration.ofSeconds(model.getAutoSaveIntervalSeconds());
        if (!currentConfig.autoSaveInterval().equals(newInterval)) {
            AutoSaveConfig newConfig = new AutoSaveConfig(
                    newInterval, currentConfig.maxCheckpoints(), currentConfig.enabled());
            checkpointManager.reconfigure(newConfig);
        }

        // Transport tempo
        project.getTransport().setTempo(model.getDefaultTempo());

        // Plugin re-scan if paths changed
        String newPluginPaths = model.getPluginScanPaths();
        if (!newPluginPaths.equals(previousPluginPaths) && !newPluginPaths.isBlank()) {
            List<Path> paths = new ArrayList<>();
            for (String p : newPluginPaths.split(";")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    paths.add(Path.of(trimmed));
                }
            }
            if (!paths.isEmpty()) {
                pluginRegistry.scanClapPlugins(paths);
            }
        }
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

    // ── Editor audio handle actions ──────────────────────────────────────────

    private void onEditorTrim() {
        Track track = editorView.getSelectedTrack();
        if (track == null || track.getClips().isEmpty()) {
            return;
        }
        List<AudioClip> clips = track.getClips();
        undoManager.execute(new UndoableAction() {
            private final List<double[]> savedState = new ArrayList<>();
            {
                for (AudioClip clip : clips) {
                    savedState.add(new double[]{
                            clip.getStartBeat(), clip.getDurationBeats(),
                            clip.getSourceOffsetBeats()});
                }
            }
            @Override public String description() { return "Trim: " + track.getName(); }
            @Override public void execute() {
                for (AudioClip clip : clips) {
                    double trimAmount = clip.getDurationBeats() * 0.1;
                    if (trimAmount > 0 && clip.getDurationBeats() > trimAmount * 2) {
                        clip.trimTo(clip.getStartBeat() + trimAmount,
                                clip.getEndBeat() - trimAmount);
                    }
                }
            }
            @Override public void undo() {
                for (int i = 0; i < clips.size(); i++) {
                    AudioClip clip = clips.get(i);
                    double[] saved = savedState.get(i);
                    clip.setStartBeat(saved[0]);
                    clip.setDurationBeats(saved[1]);
                    clip.setSourceOffsetBeats(saved[2]);
                }
            }
        });
        updateUndoRedoState();
        editorView.getWaveformDisplay().refresh();
        notificationBar.showWithUndo(NotificationLevel.SUCCESS,
                "Trimmed: " + track.getName(), this::onUndo);
        projectDirty = true;
    }

    private void onEditorFadeIn() {
        Track track = editorView.getSelectedTrack();
        if (track == null || track.getClips().isEmpty()) {
            return;
        }
        List<AudioClip> clips = track.getClips();
        double defaultFadeBeats = 2.0;
        undoManager.execute(new UndoableAction() {
            private final List<double[]> savedFades = new ArrayList<>();
            {
                for (AudioClip clip : clips) {
                    savedFades.add(new double[]{clip.getFadeInBeats()});
                }
            }
            @Override public String description() { return "Fade In: " + track.getName(); }
            @Override public void execute() {
                for (AudioClip clip : clips) {
                    clip.setFadeInBeats(defaultFadeBeats);
                }
            }
            @Override public void undo() {
                for (int i = 0; i < clips.size(); i++) {
                    clips.get(i).setFadeInBeats(savedFades.get(i)[0]);
                }
            }
        });
        updateUndoRedoState();
        editorView.getWaveformDisplay().refresh();
        notificationBar.showWithUndo(NotificationLevel.SUCCESS,
                "Fade in applied: " + track.getName(), this::onUndo);
        projectDirty = true;
    }

    private void onEditorFadeOut() {
        Track track = editorView.getSelectedTrack();
        if (track == null || track.getClips().isEmpty()) {
            return;
        }
        List<AudioClip> clips = track.getClips();
        double defaultFadeBeats = 2.0;
        undoManager.execute(new UndoableAction() {
            private final List<double[]> savedFades = new ArrayList<>();
            {
                for (AudioClip clip : clips) {
                    savedFades.add(new double[]{clip.getFadeOutBeats()});
                }
            }
            @Override public String description() { return "Fade Out: " + track.getName(); }
            @Override public void execute() {
                for (AudioClip clip : clips) {
                    clip.setFadeOutBeats(defaultFadeBeats);
                }
            }
            @Override public void undo() {
                for (int i = 0; i < clips.size(); i++) {
                    clips.get(i).setFadeOutBeats(savedFades.get(i)[0]);
                }
            }
        });
        updateUndoRedoState();
        editorView.getWaveformDisplay().refresh();
        notificationBar.showWithUndo(NotificationLevel.SUCCESS,
                "Fade out applied: " + track.getName(), this::onUndo);
        projectDirty = true;
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
