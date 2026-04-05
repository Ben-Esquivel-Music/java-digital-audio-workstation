package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.CorrelationDisplay;
import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.display.LoudnessDisplay;
import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplay;
import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioBackendFactory;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.CutClipsAction;
import com.benesquivelmusic.daw.core.audio.DuplicateClipsAction;
import com.benesquivelmusic.daw.core.audio.PasteClipsAction;
import com.benesquivelmusic.daw.core.audioimport.AudioFileImporter;
import com.benesquivelmusic.daw.core.audioimport.AudioImportResult;
import com.benesquivelmusic.daw.core.audioimport.SupportedAudioFormat;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
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

import javafx.animation.FadeTransition;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
import javafx.stage.FileChooser;
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
    @FXML private Button metronomeButton;
    @FXML private Label statusLabel;
    @FXML private Label tempoLabel;
    @FXML private Label timeDisplay;
    @FXML private Label projectInfoLabel;
    @FXML private Label monitoringLabel;
    @FXML private Label checkpointLabel;
    @FXML private Label statusBarLabel;
    @FXML private Label arrangementPlaceholder;
    @FXML private Label arrangementPanelHeader;
    @FXML private StackPane arrangementContentPane;
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

    /** Programmatic stand-in for the removed sidebar button (used by {@link BrowserPanelController}). */
    private final Button browserButton = new Button("Library");
    /** Programmatic stand-in for the removed sidebar button (used for undo-history toggle state). */
    private final Button historyButton = new Button("History");

    private DawProject project;
    private PluginRegistry pluginRegistry;
    private ProjectManager projectManager;
    private UndoManager undoManager;
    private int audioTrackCounter;
    private int midiTrackCounter;
    private boolean projectDirty;
    private AudioEngine audioEngine;
    private NotificationBar notificationBar;

    // ── Transport controller ─────────────────────────────────────────────────
    /** Manages transport actions, time ticker, and recording pipeline. */
    private TransportController transportController;

    // ── Metronome controller ─────────────────────────────────────────────────
    /** Manages metronome toggle, configuration context menu, and persistence. */
    private MetronomeController metronomeController;
    private Metronome metronome;

    // ── Project lifecycle controller ─────────────────────────────────────────
    /** Manages project open/save/import/export and associated state resets. */
    private ProjectLifecycleController projectLifecycleController;

    // ── Session interchange ──────────────────────────────────────────────────
    /** Handles DAWproject import/export logic without JavaFX dependencies. */
    private final SessionInterchangeController sessionInterchangeController =
            new SessionInterchangeController();
    // ── View navigation controller ──────────────────────────────────────────
    /** Coordinates view switching, edit tools, snap/grid, and zoom. */
    private ViewNavigationController viewNavigationController;

    // ── Clipboard & selection state ─────────────────────────────────────────
    /** Tracks whether the in-app clipboard has content for paste operations. */
    private final ClipboardManager clipboardManager = new ClipboardManager();
    /** Tracks the current time selection range for trim/crop operations. */
    private final SelectionModel selectionModel = new SelectionModel();

    private DawView activeView = DawView.ARRANGEMENT;
    private EditTool activeEditTool = EditTool.POINTER;
    private boolean snapEnabled = true;
    private GridResolution gridResolution = GridResolution.QUARTER;

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

    // ── Menu bar controller ─────────────────────────────────────────────────
    /** Traditional DAW menu bar (File, Edit, Plugins, Window, Help). */
    private DawMenuBarController menuBarController;

    // ── Toolbar appearance controller ────────────────────────────────────────
    /** Applies icons, tooltips, and overflow behavior to the toolbar. */
    private ToolbarAppearanceController toolbarAppearanceController;

    // ── Arrangement canvas ──────────────────────────────────────────────────
    /** Renders track lanes and clip rectangles in the arrangement view. */
    private ArrangementCanvas arrangementCanvas;
    /** Translates mouse events on the arrangement canvas into clip operations. */
    private ClipInteractionController clipInteractionController;
    /** Timeline ruler with bar numbers and click-to-seek playhead. */
    private TimelineRuler timelineRuler;

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
                refreshArrangementCanvas();
            } else {
                javafx.application.Platform.runLater(() -> {
                    updateUndoRedoState();
                    refreshArrangementCanvas();
                });
            }
        });
        audioEngine = new AudioEngine(project.getFormat());
        metronome = new Metronome(project.getFormat().sampleRate(), project.getFormat().channels());
        try {
            audioEngine.setAudioBackend(AudioBackendFactory.createDefault());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to create audio backend; playback will use UI timer only", e);
        }

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
        createMetronomeController(prefs);
        createProjectLifecycleController();
        createAnimationController();
        animationController.applyButtonPressAnimations();
        transportController.updateStatus();
        updateTempoDisplay();
        updateProjectInfo();
        updateCheckpointStatus();
        updateUndoRedoState();
        animationController.start();
        createViewNavigationController();
        viewNavigationController.initializeViewNavigation();
        createTrackStripController();
        createArrangementCanvas();
        viewNavigationController.setOnEditToolChanged(() -> {
            if (clipInteractionController != null) {
                clipInteractionController.updateCursor();
            }
        });
        viewNavigationController.initializeEditTools();
        viewNavigationController.initializeSnapControls();
        viewNavigationController.initializeZoomControls();
        createMenuBar();
        selectionModel.setSelectionChangeListener(() -> {
            if (menuBarController != null) {
                menuBarController.syncMenuState();
            }
        });

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
                        recordButton, skipForwardButton, loopButton, metronomeButton),
                new ToolbarAppearanceController.ToolbarButtons(
                        addAudioTrackButton, addMidiTrackButton, undoButton,
                        redoButton, snapButton, saveButton, pluginsButton),
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
                    @Override public boolean isSnapEnabled() {
                        return viewNavigationController != null
                                ? viewNavigationController.isSnapEnabled() : snapEnabled;
                    }
                    @Override public GridResolution gridResolution() {
                        return viewNavigationController != null
                                ? viewNavigationController.getGridResolution() : gridResolution;
                    }
                    @Override public Metronome metronome() { return metronome; }
                    @Override public CountInMode countInMode() {
                        return metronomeController != null
                                ? metronomeController.getCountInMode() : CountInMode.OFF;
                    }
                    @Override public void startTimeTicker() { animationController.startTimeTicker(); }
                    @Override public void pauseTimeTicker() { animationController.pauseTimeTicker(); }
                    @Override public void stopTimeTicker() { animationController.stopTimeTicker(); }
                    @Override public void flashMidiActivity(com.benesquivelmusic.daw.core.track.Track track) {
                        for (Node child : trackListPanel.getChildren()) {
                            if (child.getUserData() == track) {
                                Node armBtn = child.lookup(".track-arm-button");
                                if (armBtn != null) {
                                    FadeTransition flash = new FadeTransition(
                                            Duration.millis(120), armBtn);
                                    flash.setFromValue(0.4);
                                    flash.setToValue(1.0);
                                    flash.play();
                                }
                                break;
                            }
                        }
                    }
                });
    }

    /**
     * Creates the {@link MetronomeController} with the metronome, button, notification
     * bar, and preferences for settings persistence.  Must be called after
     * {@code initializeNotificationBar()}.
     */
    private void createMetronomeController(Preferences prefs) {
        metronomeController = new MetronomeController(
                metronome, metronomeButton, notificationBar,
                statusBarLabel, prefs.node("metronome"));
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
                        skipForwardButton, loopButton, metronomeButton,
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
                project, undoManager, audioEngine, viewNavigationController.getMixerView(),
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
                    @Override public void zoomIn() { viewNavigationController.onZoomIn(); }
                    @Override public void zoomOut() { viewNavigationController.onZoomOut(); }
                    @Override public void toggleSnap() {
                        viewNavigationController.onToggleSnap();
                    }
                    @Override public void skipToStart() { transportController.onSkipBack(); }
                    @Override public void markProjectDirty() { projectDirty = true; }
                    @Override public boolean isSnapEnabled() { return viewNavigationController.isSnapEnabled(); }
                    @Override public ZoomLevel currentZoomLevel() {
                        return viewNavigationController.getZoomLevel(viewNavigationController.getActiveView());
                    }
                    @Override public EditorView editorView() { return viewNavigationController.getEditorView(); }
                });
    }

    /**
     * Creates the {@link ArrangementCanvas} and {@link TimelineRuler}, adds
     * them to the arrangement content pane, and wires click-to-seek and
     * playhead-update callbacks.
     */
    private void createArrangementCanvas() {
        arrangementCanvas = new ArrangementCanvas();
        arrangementContentPane.getChildren().addFirst(arrangementCanvas);
        arrangementCanvas.prefWidthProperty().bind(arrangementContentPane.widthProperty());
        arrangementCanvas.prefHeightProperty().bind(arrangementContentPane.heightProperty());
        refreshArrangementCanvas();

        // Connect the track strip controller so it can toggle automation lanes
        trackStripController.setArrangementCanvas(arrangementCanvas);

        // Create timeline ruler and insert it into the arrangement VBox above the canvas
        timelineRuler = new TimelineRuler(project.getTransport());
        javafx.scene.Parent contentParent = arrangementContentPane.getParent();
        if (contentParent instanceof javafx.scene.layout.VBox vbox) {
            int idx = vbox.getChildren().indexOf(arrangementContentPane);
            if (idx >= 0) {
                vbox.getChildren().add(idx, timelineRuler);
            }
        }

        // Wire ruler seek: clicking on the ruler repositions the transport playhead
        timelineRuler.addSeekListener(this::seekToPosition);

        // Register per-frame playhead update callback on the animation controller
        animationController.setPlayheadUpdateCallback(this::updatePlayheadFromTransport);

        clipInteractionController = new ClipInteractionController(arrangementCanvas,
                new ClipInteractionController.Host() {
                    @Override public java.util.List<com.benesquivelmusic.daw.core.track.Track> tracks() {
                        return project.getTracks();
                    }
                    @Override public EditTool activeTool() {
                        return viewNavigationController.getActiveEditTool();
                    }
                    @Override public UndoManager undoManager() { return undoManager; }
                    @Override public double pixelsPerBeat() {
                        return arrangementCanvas.getPixelsPerBeat();
                    }
                    @Override public double scrollXBeats() {
                        return arrangementCanvas.getScrollXBeats();
                    }
                    @Override public double scrollYPixels() {
                        return arrangementCanvas.getScrollYPixels();
                    }
                    @Override public double trackHeight() {
                        return arrangementCanvas.getTrackHeight();
                    }
                    @Override public boolean snapEnabled() {
                        return viewNavigationController.isSnapEnabled();
                    }
                    @Override public GridResolution gridResolution() {
                        return viewNavigationController.getGridResolution();
                    }
                    @Override public int beatsPerBar() {
                        return project.getTransport().getTimeSignatureNumerator();
                    }
                    @Override public void refreshCanvas() { refreshArrangementCanvas(); }
                    @Override public void seekToPosition(double beat) {
                        MainController.this.seekToPosition(beat);
                    }
                    @Override public SelectionModel selectionModel() { return selectionModel; }
                    @Override public void updateStatusBar(String text) {
                        statusBarLabel.setText(text);
                    }
                });
        clipInteractionController.install();

        // Wire drag-and-drop from OS file manager and BrowserPanel onto arrangement canvas
        installArrangementCanvasDragDrop();
    }

    /**
     * Installs drag-and-drop handlers on the arrangement canvas so that audio
     * files can be dropped from the OS file manager or the {@link BrowserPanel}.
     * The dropped file is imported via {@link AudioFileImporter} and placed at
     * the drop position (track lane + beat).
     */
    private void installArrangementCanvasDragDrop() {
        arrangementCanvas.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                boolean hasAudio = event.getDragboard().getFiles().stream()
                        .anyMatch(f -> isSupportedAudioFile(f.toPath()));
                if (hasAudio) {
                    event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                }
            } else if (event.getDragboard().hasString()) {
                String path = event.getDragboard().getString();
                if (isSupportedAudioFile(Path.of(path))) {
                    event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                }
            }
            event.consume();
        });

        arrangementCanvas.setOnDragDropped(event -> {
            boolean success = false;
            List<Path> filesToImport = new ArrayList<>();

            if (event.getDragboard().hasFiles()) {
                for (java.io.File file : event.getDragboard().getFiles()) {
                    if (isSupportedAudioFile(file.toPath())) {
                        filesToImport.add(file.toPath());
                    }
                }
            } else if (event.getDragboard().hasString()) {
                String pathStr = event.getDragboard().getString();
                Path path = Path.of(pathStr);
                if (isSupportedAudioFile(path) && java.nio.file.Files.isRegularFile(path)) {
                    filesToImport.add(path);
                }
            }

            if (!filesToImport.isEmpty()) {
                // Determine which track the user dropped onto (if any)
                int trackIndex = arrangementCanvas.trackIndexAtY(event.getY());
                Track targetTrack = null;
                if (trackIndex >= 0 && trackIndex < project.getTracks().size()) {
                    Track candidate = project.getTracks().get(trackIndex);
                    if (candidate.getType() == TrackType.AUDIO) {
                        targetTrack = candidate;
                    }
                }

                // Import the first audio file at the drop beat position
                double dropBeat = event.getX() / arrangementCanvas.getPixelsPerBeat()
                        + arrangementCanvas.getScrollXBeats();
                dropBeat = Math.max(0.0, dropBeat);

                // Save and restore playhead so import uses the drop position
                double savedPlayhead = project.getTransport().getPositionInBeats();
                project.getTransport().setPositionInBeats(dropBeat);
                try {
                    success = importAudioFile(filesToImport.get(0), targetTrack);
                } finally {
                    project.getTransport().setPositionInBeats(savedPlayhead);
                }
            }

            event.setDropCompleted(success);
            event.consume();
        });
    }

    /**
     * Returns whether the given file path has a supported audio file extension.
     */
    private static boolean isSupportedAudioFile(Path path) {
        return SupportedAudioFormat.isSupported(path);
    }

    /**
     * Creates the {@link ProjectLifecycleController} with the current project
     * manager, session interchange controller, and UI elements. Must be called
     * after {@code initializeNotificationBar()}.
     */
    private void createProjectLifecycleController() {
        projectLifecycleController = new ProjectLifecycleController(
                projectManager, sessionInterchangeController, notificationBar,
                statusBarLabel, checkpointLabel, rootPane,
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
                        viewNavigationController.setMixerView(newMixerView);
                        viewNavigationController.onProjectChanged();
                        metronome = new Metronome(
                                project.getFormat().sampleRate(),
                                project.getFormat().channels());
                        createTransportController();
                        Preferences metroPrefs = Preferences.userNodeForPackage(
                                MainController.class).node("metronome");
                        metronomeController = new MetronomeController(
                                metronome, metronomeButton, notificationBar,
                                statusBarLabel, metroPrefs);
                        transportController.updateStatus();
                        createTrackStripController();
                        updateProjectInfo();
                        updateTempoDisplay();
                        updateUndoRedoState();
                        updateArrangementPlaceholder();
                        if (viewNavigationController.getActiveView() == DawView.MIXER) {
                            rootPane.setCenter(newMixerView);
                        }
                    }
                });
    }

    // ── View navigation controller factory ──────────────────────────────────

    /**
     * Creates the {@link ViewNavigationController} with all button references,
     * persisted state, and the host callback.  Must be called after
     * {@code initializeNotificationBar()} and before {@code createTrackStripController()}.
     */
    private void createViewNavigationController() {
        viewNavigationController = new ViewNavigationController(
                rootPane, statusBarLabel, toolbarStateStore,
                snapButton,
                activeView, activeEditTool, snapEnabled, gridResolution,
                new ViewNavigationController.Host() {
                    @Override public DawProject project() { return project; }
                    @Override public UndoManager undoManager() { return undoManager; }
                    @Override public void onEditorTrim() { MainController.this.onEditorTrim(); }
                    @Override public void onEditorFadeIn() { MainController.this.onEditorFadeIn(); }
                    @Override public void onEditorFadeOut() { MainController.this.onEditorFadeOut(); }
                    @Override public void markProjectDirty() { projectDirty = true; }
                });
    }

    /**
     * Creates the traditional DAW menu bar and inserts it at the very top
     * of the window, above the transport bar.
     */
    private void createMenuBar() {
        menuBarController = new DawMenuBarController(
                new DawMenuBarController.Host() {
                    @Override public com.benesquivelmusic.daw.core.project.DawProject project() { return project; }
                    @Override public boolean isProjectDirty() { return projectDirty; }
                    @Override public boolean canUndo() { return undoManager.canUndo(); }
                    @Override public boolean canRedo() { return undoManager.canRedo(); }
                    @Override public boolean hasClipboardContent() { return clipboardManager.hasContent(); }
                    @Override public boolean hasSelection() { return selectionModel.hasClipSelection(); }
                    @Override public DawView activeView() {
                        return viewNavigationController != null
                                ? viewNavigationController.getActiveView() : activeView;
                    }
                    @Override public void onNewProject() { projectLifecycleController.onNewProject(); }
                    @Override public void onOpenProject() { projectLifecycleController.onOpenProject(); }
                    @Override public void onSaveProject() { projectLifecycleController.onSaveProject(); }
                    @Override public void onRecentProjects() { projectLifecycleController.onRecentProjects(); }
                    @Override public void onImportSession() { projectLifecycleController.onImportSession(); }
                    @Override public void onExportSession() { projectLifecycleController.onExportSession(); }
                    @Override public void onImportAudioFile() { MainController.this.onImportAudioFile(); }
                    @Override public void onUndo() { MainController.this.onUndo(); }
                    @Override public void onRedo() { MainController.this.onRedo(); }
                    @Override public void onCopy() { onCopyClips(); }
                    @Override public void onCut() { onCutClips(); }
                    @Override public void onPaste() { onPasteClips(); }
                    @Override public void onDuplicate() { onDuplicateClips(); }
                    @Override public void onDeleteSelection() { MainController.this.onDeleteSelection(); }
                    @Override public void onToggleSnap() { viewNavigationController.onToggleSnap(); }
                    @Override public void onManagePlugins() { MainController.this.onManagePlugins(); }
                    @Override public void onOpenSettings() { MainController.this.onOpenSettings(); }
                    @Override public void onSwitchView(DawView view) {
                        viewNavigationController.switchView(view);
                    }
                    @Override public void onToggleBrowser() {
                        if (historyPanelVisible) { toggleHistoryPanel(); }
                        browserPanelController.toggleBrowserPanel();
                    }
                    @Override public void onToggleHistory() { toggleHistoryPanel(); }
                    @Override public void onToggleNotificationHistory() {
                        MainController.this.toggleNotificationHistoryPanel();
                    }
                    @Override public void onToggleVisualizations() {
                        vizPanelController.toggleRowVisibility();
                    }
                    @Override public void onHelp() { MainController.this.onHelp(); }
                },
                keyBindingManager);
        javafx.scene.control.MenuBar bar = menuBarController.build();

        // Insert the menu bar at the top of the root pane's top VBox,
        // above the transport bar
        Node topNode = rootPane.getTop();
        if (topNode instanceof VBox topVBox) {
            topVBox.getChildren().addFirst(bar);
        }

        LOG.fine("DAW menu bar created and added to top of window");
    }

    /**
     * Handles the Home button action: switches to the arrangement view,
     * resets zoom to fit, clears the selection, and updates the status bar.
     */
    void onHome() {
        viewNavigationController.switchView(DawView.ARRANGEMENT);
        ZoomLevel zoom = viewNavigationController.getZoomLevel(DawView.ARRANGEMENT);
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
     * Returns the currently active view.
     *
     * @return the active {@link DawView}
     */
    public DawView getActiveView() {
        return viewNavigationController.getActiveView();
    }

    /**
     * Returns the currently active edit tool.
     *
     * @return the active {@link EditTool}
     */
    public EditTool getActiveEditTool() {
        return viewNavigationController.getActiveEditTool();
    }

    /**
     * Returns whether snap-to-grid is currently enabled.
     *
     * @return {@code true} if snap is enabled
     */
    public boolean isSnapEnabled() {
        return viewNavigationController.isSnapEnabled();
    }

    /**
     * Returns the currently active grid resolution.
     *
     * @return the active {@link GridResolution}
     */
    public GridResolution getGridResolution() {
        return viewNavigationController.getGridResolution();
    }

    /**
     * Returns the zoom level for the given view.
     *
     * @param view the view to query
     * @return the zoom level for the view
     */
    public ZoomLevel getZoomLevel(DawView view) {
        return viewNavigationController.getZoomLevel(view);
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
        actionHandlers.put(DawAction.TOGGLE_METRONOME, this::onToggleMetronome);
        actionHandlers.put(DawAction.UNDO, this::onUndo);
        actionHandlers.put(DawAction.REDO, this::onRedo);
        actionHandlers.put(DawAction.SAVE, projectLifecycleController::onSaveProject);
        actionHandlers.put(DawAction.NEW_PROJECT, projectLifecycleController::onNewProject);
        actionHandlers.put(DawAction.OPEN_PROJECT, projectLifecycleController::onOpenProject);
        actionHandlers.put(DawAction.IMPORT_SESSION, projectLifecycleController::onImportSession);
        actionHandlers.put(DawAction.EXPORT_SESSION, projectLifecycleController::onExportSession);
        actionHandlers.put(DawAction.IMPORT_AUDIO_FILE, this::onImportAudioFile);
        actionHandlers.put(DawAction.TOGGLE_SNAP, viewNavigationController::onToggleSnap);
        actionHandlers.put(DawAction.ADD_AUDIO_TRACK, this::onAddAudioTrack);
        actionHandlers.put(DawAction.ADD_MIDI_TRACK, this::onAddMidiTrack);
        actionHandlers.put(DawAction.TOOL_POINTER, () -> viewNavigationController.selectEditTool(EditTool.POINTER));
        actionHandlers.put(DawAction.TOOL_PENCIL, () -> viewNavigationController.selectEditTool(EditTool.PENCIL));
        actionHandlers.put(DawAction.TOOL_ERASER, () -> viewNavigationController.selectEditTool(EditTool.ERASER));
        actionHandlers.put(DawAction.TOOL_SCISSORS, () -> viewNavigationController.selectEditTool(EditTool.SCISSORS));
        actionHandlers.put(DawAction.TOOL_GLUE, () -> viewNavigationController.selectEditTool(EditTool.GLUE));
        actionHandlers.put(DawAction.ZOOM_IN, viewNavigationController::onZoomIn);
        actionHandlers.put(DawAction.ZOOM_OUT, viewNavigationController::onZoomOut);
        actionHandlers.put(DawAction.ZOOM_TO_FIT, viewNavigationController::onZoomToFit);
        actionHandlers.put(DawAction.VIEW_ARRANGEMENT, () -> viewNavigationController.switchView(DawView.ARRANGEMENT));
        actionHandlers.put(DawAction.VIEW_MIXER, () -> viewNavigationController.switchView(DawView.MIXER));
        actionHandlers.put(DawAction.VIEW_EDITOR, () -> viewNavigationController.switchView(DawView.EDITOR));
        actionHandlers.put(DawAction.VIEW_TELEMETRY, () -> viewNavigationController.switchView(DawView.TELEMETRY));
        actionHandlers.put(DawAction.VIEW_MASTERING, () -> viewNavigationController.switchView(DawView.MASTERING));
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
        actionHandlers.put(DawAction.COPY, this::onCopyClips);
        actionHandlers.put(DawAction.CUT, this::onCutClips);
        actionHandlers.put(DawAction.PASTE, this::onPasteClips);
        actionHandlers.put(DawAction.DUPLICATE, this::onDuplicateClips);
        actionHandlers.put(DawAction.DELETE_SELECTION, this::onDeleteSelection);

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
                vizTileRow, vizPreferences, tileLookup);
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
                refreshArrangementCanvas();
            } else {
                javafx.application.Platform.runLater(() -> {
                    updateUndoRedoState();
                    refreshArrangementCanvas();
                });
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
        syncLoopRegionToCanvas();
    }

    @FXML
    private void onToggleMetronome() {
        metronomeController.onToggleMetronome();
    }

    @FXML
    private void onAddAudioTrack() {
        // Enumerate available audio input devices from the backend (empty list if no backend)
        List<AudioDeviceInfo> devices = List.of();
        audioEngine.ensureBackendInitialized();
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
                viewNavigationController.getMixerView().refresh();
            }
            @Override public void undo() {
                project.removeTrack(track);
                trackListPanel.getChildren().remove(trackItem);
                audioTrackCounter--;
                updateArrangementPlaceholder();
                viewNavigationController.getMixerView().refresh();
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
                    track.setMidiInputDeviceName(selectedMidi.getName());
                    trackItem = trackStripController.addTrackToUI(track);
                    initialExecute = false;
                } else {
                    project.addTrack(track);
                    trackListPanel.getChildren().add(trackItem);
                }
                updateArrangementPlaceholder();
                viewNavigationController.getMixerView().refresh();
            }
            @Override public void undo() {
                project.removeTrack(track);
                trackListPanel.getChildren().remove(trackItem);
                midiTrackCounter--;
                updateArrangementPlaceholder();
                viewNavigationController.getMixerView().refresh();
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
        if (menuBarController != null) {
            menuBarController.syncMenuState();
        }
    }

    @FXML
    private void onNewProject() {
        projectLifecycleController.onNewProject();
        if (menuBarController != null) {
            menuBarController.syncMenuState();
        }
    }

    @FXML
    private void onOpenProject() {
        projectLifecycleController.onOpenProject();
        if (menuBarController != null) {
            menuBarController.syncMenuState();
        }
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

    /**
     * Opens a file chooser for importing an audio file in any supported format,
     * places the imported clip at the current playhead position on a new audio
     * track, and wraps the operation in an undoable action.
     */
    private void onImportAudioFile() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Audio File");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio Files",
                        "*.wav", "*.flac", "*.aiff", "*.aif", "*.ogg", "*.mp3"),
                new FileChooser.ExtensionFilter("WAV Files", "*.wav"),
                new FileChooser.ExtensionFilter("FLAC Files", "*.flac"),
                new FileChooser.ExtensionFilter("AIFF Files", "*.aiff", "*.aif"),
                new FileChooser.ExtensionFilter("OGG Files", "*.ogg"),
                new FileChooser.ExtensionFilter("MP3 Files", "*.mp3"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        java.io.File selectedFile = chooser.showOpenDialog(stage);
        if (selectedFile == null) {
            return;
        }
        importAudioFile(selectedFile.toPath(), null);
    }

    /**
     * Imports an audio file onto the given target track (or a new track if
     * {@code null}), places the clip at the current playhead position, wraps
     * in an undoable action, and shows a success notification.
     *
     * @param file        the audio file to import
     * @param targetTrack the track to place the clip on, or {@code null} to create a new track
     * @return {@code true} if the import succeeded, {@code false} otherwise
     */
    boolean importAudioFile(Path file, Track targetTrack) {
        AudioFileImporter importer = new AudioFileImporter(project);
        double playheadBeat = project.getTransport().getPositionInBeats();
        boolean createdNewTrack = (targetTrack == null);

        try {
            AudioImportResult result = importer.importFile(file, playheadBeat, targetTrack);

            // If a new track was created, add it to the UI
            HBox trackItem = createdNewTrack
                    ? trackStripController.addTrackToUI(result.track()) : null;
            if (createdNewTrack) {
                audioTrackCounter++;
            }

            // Wrap in undoable action that manages both the model and the UI strip
            undoManager.execute(new UndoableAction() {
                private boolean initialExecute = true;
                @Override public String description() { return "Import Audio File"; }
                @Override public void execute() {
                    if (initialExecute) {
                        initialExecute = false;
                        return;
                    }
                    if (createdNewTrack) {
                        project.addTrack(result.track());
                        if (trackItem != null) {
                            trackListPanel.getChildren().add(trackItem);
                        }
                        audioTrackCounter++;
                    }
                    result.track().addClip(result.clip());
                    updateArrangementPlaceholder();
                    viewNavigationController.getMixerView().refresh();
                }
                @Override public void undo() {
                    result.track().removeClip(result.clip());
                    if (createdNewTrack) {
                        project.removeTrack(result.track());
                        if (trackItem != null) {
                            trackListPanel.getChildren().remove(trackItem);
                        }
                        audioTrackCounter--;
                    }
                    updateArrangementPlaceholder();
                    viewNavigationController.getMixerView().refresh();
                }
            });

            updateArrangementPlaceholder();
            refreshArrangementCanvas();
            updateUndoRedoState();
            viewNavigationController.getMixerView().refresh();
            projectDirty = true;

            // Show success notification with file name, format, duration, and conversion info
            double durationSeconds = 0.0;
            float[][] audioData = result.clip().getAudioData();
            if (audioData != null && audioData.length > 0) {
                int sampleRate = (int) project.getFormat().sampleRate();
                durationSeconds = (double) audioData[0].length / sampleRate;
            }
            String fileName = file.getFileName().toString();
            String formatName = SupportedAudioFormat.fromPath(file)
                    .map(f -> f.name())
                    .orElse("Audio");
            String durationStr = String.format("%.1fs", durationSeconds);
            String conversionNote = result.wasConverted() ? ", sample rate converted" : "";
            notificationBar.show(NotificationLevel.SUCCESS,
                    "Imported: " + fileName + " (" + formatName + ", " + durationStr + conversionNote + ")");
            statusBarLabel.setText("Imported audio file: " + fileName);
            statusBarLabel.setGraphic(IconNode.of(DawIcon.WAVEFORM, 12));
            LOG.fine(() -> "Imported audio file: " + file);
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to import audio file: " + file, e);
            notificationBar.show(NotificationLevel.ERROR,
                    "Import failed: " + e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Unsupported audio file: " + file, e);
            notificationBar.show(NotificationLevel.ERROR,
                    "Import failed: " + e.getMessage());
            return false;
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

    // ── Multi-clip group operations (keyboard shortcuts) ─────────────────────
    //
    // These handlers operate on audio clip selection only. MIDI clip selection
    // is tracked separately in SelectionModel and is not included in
    // copy/cut/paste/duplicate/delete shortcuts at this time.

    private void onCopyClips() {
        List<ClipboardEntry> selected = selectionModel.getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        clipboardManager.copyClips(selected);
        if (menuBarController != null) {
            menuBarController.syncMenuState();
        }
        statusBarLabel.setText("Copied " + selected.size() + " clip(s)");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.COPY, 12));
    }

    private void onCutClips() {
        List<ClipboardEntry> selected = selectionModel.getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        clipboardManager.copyClips(selected);
        List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
        for (ClipboardEntry entry : selected) {
            entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
        }
        undoManager.execute(new CutClipsAction(entries));
        selectionModel.clearClipSelection();
        refreshArrangementCanvas();
        updateUndoRedoState();
        statusBarLabel.setText("Cut " + entries.size() + " clip(s)");
        statusBarLabel.setGraphic(IconNode.of(DawIcon.CUT, 12));
        projectDirty = true;
    }

    private void onPasteClips() {
        if (!clipboardManager.hasContent()) {
            return;
        }
        List<ClipboardEntry> entries = clipboardManager.getEntries();
        if (entries.isEmpty()) {
            return;
        }
        double playhead = project.getTransport().getPositionInBeats();
        List<Track> currentTracks = project.getTracks();
        List<Map.Entry<Track, AudioClip>> sourceEntries = new ArrayList<>();
        for (ClipboardEntry entry : entries) {
            Track resolved = resolveTrack(entry.sourceTrack(), currentTracks);
            if (resolved != null) {
                sourceEntries.add(Map.entry(resolved, entry.clip()));
            }
        }
        if (sourceEntries.isEmpty()) {
            return;
        }
        undoManager.execute(new PasteClipsAction(sourceEntries, null, playhead));
        refreshArrangementCanvas();
        updateUndoRedoState();
        statusBarLabel.setText("Pasted " + sourceEntries.size() + " clip(s) at beat " + String.format("%.1f", playhead));
        statusBarLabel.setGraphic(IconNode.of(DawIcon.PASTE, 12));
        projectDirty = true;
    }

    /**
     * Resolves a clipboard source track against the current project track
     * list. Returns the same track instance if it still exists, otherwise
     * looks up by track ID, then falls back to the first track of the same
     * type. Returns {@code null} if no suitable track is found.
     */
    private Track resolveTrack(Track source, List<Track> currentTracks) {
        if (currentTracks.contains(source)) {
            return source;
        }
        for (Track t : currentTracks) {
            if (t.getId().equals(source.getId())) {
                return t;
            }
        }
        for (Track t : currentTracks) {
            if (t.getType() == source.getType()) {
                return t;
            }
        }
        return null;
    }

    private void onDuplicateClips() {
        List<ClipboardEntry> selected = selectionModel.getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
        for (ClipboardEntry entry : selected) {
            entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
        }
        undoManager.execute(new DuplicateClipsAction(entries));
        refreshArrangementCanvas();
        updateUndoRedoState();
        statusBarLabel.setText("Duplicated " + entries.size() + " clip(s)");
        projectDirty = true;
    }

    private void onDeleteSelection() {
        List<ClipboardEntry> selected = selectionModel.getSelectedClips();
        if (selected.isEmpty()) {
            return;
        }
        List<Map.Entry<Track, AudioClip>> entries = new ArrayList<>();
        for (ClipboardEntry entry : selected) {
            entries.add(Map.entry(entry.sourceTrack(), entry.clip()));
        }
        undoManager.execute(new CutClipsAction(entries));
        selectionModel.clearClipSelection();
        refreshArrangementCanvas();
        updateUndoRedoState();
        statusBarLabel.setText("Deleted " + entries.size() + " clip(s)");
        projectDirty = true;
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
        EditorView editorView = viewNavigationController.getEditorView();
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
        EditorView editorView = viewNavigationController.getEditorView();
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
        EditorView editorView = viewNavigationController.getEditorView();
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
        refreshArrangementCanvas();
    }

    private void refreshArrangementCanvas() {
        if (arrangementCanvas == null) {
            return;
        }
        arrangementCanvas.setTracks(project.getTracks());
        syncLoopRegionToCanvas();
        syncSelectionToCanvas();
    }

    /**
     * Seeks the transport to the given beat position, applying snap-to-grid
     * if snap is enabled, and updates the playhead in the ruler and canvas.
     *
     * @param beat the raw beat position to seek to
     */
    private void seekToPosition(double beat) {
        double position = Math.max(0.0, beat);
        boolean snap = viewNavigationController != null
                ? viewNavigationController.isSnapEnabled() : snapEnabled;
        if (snap) {
            GridResolution res = viewNavigationController != null
                    ? viewNavigationController.getGridResolution() : gridResolution;
            position = SnapQuantizer.quantize(position, res,
                    project.getTransport().getTimeSignatureNumerator());
        }
        project.getTransport().setPositionInBeats(position);
        if (timelineRuler != null) {
            timelineRuler.setPlayheadPositionBeats(position);
        }
        if (arrangementCanvas != null) {
            arrangementCanvas.setPlayheadBeat(position);
        }
    }

    /**
     * Called each animation frame by the {@link AnimationController} to sync
     * the playhead in the ruler and canvas with the transport's current beat.
     */
    private void updatePlayheadFromTransport() {
        double beat = project.getTransport().getPositionInBeats();
        if (timelineRuler != null) {
            timelineRuler.setPlayheadPositionBeats(beat);
        }
        if (arrangementCanvas != null) {
            arrangementCanvas.setPlayheadBeat(beat);
        }
        syncLoopRegionToCanvas();
    }

    /**
     * Pushes the current loop region state from the transport into the
     * arrangement canvas and timeline ruler so that the loop overlay stays
     * synchronized with the transport model.
     */
    private void syncLoopRegionToCanvas() {
        Transport transport = project.getTransport();
        if (arrangementCanvas != null) {
            arrangementCanvas.setLoopRegion(
                    transport.isLoopEnabled(),
                    transport.getLoopStartInBeats(),
                    transport.getLoopEndInBeats());
        }
        if (timelineRuler != null) {
            boolean snap = viewNavigationController != null
                    ? viewNavigationController.isSnapEnabled() : snapEnabled;
            GridResolution res = viewNavigationController != null
                    ? viewNavigationController.getGridResolution() : gridResolution;
            timelineRuler.setSnapEnabled(snap);
            timelineRuler.setGridResolution(res);
            timelineRuler.redraw();
        }
    }

    private void syncSelectionToCanvas() {
        if (arrangementCanvas != null) {
            arrangementCanvas.setSelectionRange(
                    selectionModel.hasSelection(),
                    selectionModel.getStartBeat(),
                    selectionModel.getEndBeat());
        }
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

        if (menuBarController != null) {
            menuBarController.syncMenuState();
        }
    }
}
