package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplay;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.audio.AudioBackendFactory;
import com.benesquivelmusic.daw.core.audio.AudioDeviceManager;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSettingsStore;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import javafx.animation.FadeTransition;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Thin coordinator for the main DAW window.
 *
 * <p>Initializes the layout, creates sub-controllers, and wires top-level
 * event handlers. All substantial logic is delegated to specialized
 * controllers created during {@link #initialize()}.</p>
 */
public final class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());
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
    @FXML private Button rippleModeButton;
    @FXML private Label rippleBannerLabel;
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

    private final Button browserButton = new Button("Library");
    private final Button historyButton = new Button("History");

    private DawProject project;
    private PluginRegistry pluginRegistry;
    private ProjectManager projectManager;
    private UndoManager undoManager;
    private boolean projectDirty;
    private AudioEngine audioEngine;
    // Story 137: registry of per-track input-level monitors used by the
    // mixer's input-meter column and the arrangement-view clip indicator.
    private final InputLevelMonitorRegistry inputLevelMonitorRegistry = new InputLevelMonitorRegistry();
    private DefaultAudioEngineController audioEngineController;
    private NotificationBar notificationBar;
    private Metronome metronome;
    private PluginInvocationSupervisor pluginSupervisor;
    private PluginFaultUiController pluginFaultUiController;

    private final ClipboardManager clipboardManager = new ClipboardManager();
    private final SelectionModel selectionModel = new SelectionModel();
    private final SessionInterchangeController sessionInterchangeController =
            new SessionInterchangeController();
    private final NotificationHistoryService notificationHistoryService =
            new NotificationHistoryService();

    private DawView activeView = DawView.ARRANGEMENT;
    private EditTool activeEditTool = EditTool.POINTER;
    private boolean snapEnabled = true;
    private GridResolution gridResolution = GridResolution.QUARTER;

    private TransportController transportController;
    private MetronomeController metronomeController;
    private ProjectLifecycleController projectLifecycleController;
    private ViewNavigationController viewNavigationController;
    private VisualizationPanelController vizPanelController;
    private BrowserPanelController browserPanelController;
    private ToolbarAppearanceController toolbarAppearanceController;
    private TrackStripController trackStripController;
    private AnimationController animationController;
    private DawMenuBarController menuBarController;
    private PluginViewController pluginViewController;
    private ClipEditController clipEditController;
    private RippleModeController rippleModeController;
    private TrackCreationController trackCreationController;
    private KeyboardShortcutController keyboardShortcutController;
    private HistoryPanelController historyPanelController;
    private AudioImportController audioImportController;
    private TempoEditController tempoEditController;
    private ToolbarStateStore toolbarStateStore;
    private KeyBindingManager keyBindingManager;

    private ArrangementCanvas arrangementCanvas;
    private ClipInteractionController clipInteractionController;
    private TimelineRuler timelineRuler;
    private SpectrumDisplay spectrumDisplay;
    private LevelMeterDisplay levelMeterDisplay;

    @FXML
    private void initialize() {
        project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        pluginRegistry = new PluginRegistry();
        undoManager = new UndoManager();
        undoManager.addHistoryListener(_ -> {
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
        // Story 137: bind the input-level-monitor registry so the engine
        // taps the raw input signal per armed track before any processing.
        audioEngine.setInputLevelMonitorRegistry(inputLevelMonitorRegistry);
        metronome = new Metronome(project.getFormat().sampleRate(), project.getFormat().channels());
        try {
            audioEngine.setAudioBackend(AudioBackendFactory.createDefault());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to create audio backend; playback will use UI timer only", e);
        }
        audioEngineController = new DefaultAudioEngineController(audioEngine, this::updateProjectInfo);

        // Apply the persisted mix precision from user preferences to the
        // project's mixer so that a previously-saved FLOAT_32 choice is
        // honoured on restart rather than silently reverting to the default.
        SettingsModel startupSettings = new SettingsModel(Preferences.userNodeForPackage(SettingsModel.class));
        project.getMixer().setMixPrecision(startupSettings.getMixPrecision());

        CheckpointManager checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        Preferences prefs = Preferences.userNodeForPackage(MainController.class);
        RecentProjectsStore recentProjectsStore = new RecentProjectsStore(prefs);
        projectManager = new ProjectManager(checkpointManager, recentProjectsStore);
        toolbarStateStore = new ToolbarStateStore(prefs);
        keyBindingManager = new KeyBindingManager(prefs.node("keybindings"));

        activeView = toolbarStateStore.loadActiveView();
        activeEditTool = toolbarStateStore.loadEditTool();
        snapEnabled = toolbarStateStore.loadSnapEnabled();
        gridResolution = toolbarStateStore.loadGridResolution();

        createToolbarAppearanceController();
        toolbarAppearanceController.apply();
        VisualizationTileBuilder.Result vizResult = VisualizationTileBuilder.build(vizTileRow);
        spectrumDisplay = vizResult.spectrumDisplay();
        levelMeterDisplay = vizResult.levelMeterDisplay();
        vizPanelController = vizResult.panelController();
        buildBrowserPanel(toolbarStateStore.loadBrowserVisible());
        createTempoEditController();
        initializeNotificationBar();
        initializePluginFaultIsolation();
        createTransportController();
        createMetronomeController(prefs);
        createProjectLifecycleController();
        createAnimationController();
        createViewNavigationController();
        // initializeViewNavigation() constructs the MixerView; it must run before
        // createTrackStripController() because TrackStripController requires a
        // non-null MixerView in its constructor.
        viewNavigationController.initializeViewNavigation();
        createTrackStripController();
        createPluginViewController();
        createRippleModeController();
        createClipEditController();
        createTrackCreationController();
        createAudioImportController();
        createHistoryPanelController();
        createKeyboardShortcutController();
        animationController.applyButtonPressAnimations();
        transportController.updateStatus();
        updateTempoDisplay();
        updateProjectInfo();
        updateCheckpointStatus();
        updateUndoRedoState();
        animationController.start();
        viewNavigationController.getMixerView().setPluginRegistry(pluginRegistry);
        // Story 137: wire the input-level-monitor registry into the mixer
        // so armed-track strips grow a second meter column with a latching
        // clip LED, and into the track-strip controller so armed tracks
        // also show the miniature clip indicator in the arrangement view.
        viewNavigationController.getMixerView()
                .setInputLevelMonitorRegistry(inputLevelMonitorRegistry);
        if (trackStripController != null) {
            trackStripController.setInputLevelMonitorRegistry(inputLevelMonitorRegistry);
        }
        createArrangementCanvas();
        viewNavigationController.setOnEditToolChanged(() -> {
            if (clipInteractionController != null) clipInteractionController.updateCursor();
        });
        viewNavigationController.initializeEditTools();
        viewNavigationController.initializeSnapControls();
        viewNavigationController.initializeZoomControls();
        createMenuBar();
        selectionModel.setSelectionChangeListener(() -> {
            if (menuBarController != null) menuBarController.syncMenuState();
        });
        playButton.sceneProperty().addListener((_, _, scene) -> {
            if (scene != null) {
                keyboardShortcutController.register(scene);
                if (scene.getWindow() instanceof Stage primaryStage) {
                    primaryStage.setOnHidden(_ -> {
                        pluginViewController.dispose();
                        if (pluginFaultUiController != null) {
                            pluginFaultUiController.dispose();
                        }
                        if (pluginSupervisor != null) {
                            pluginSupervisor.close();
                        }
                    });
                }
            }
        });
        LOG.info("DAW initialized with studio quality format");
    }

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
                    @Override public void flashMidiActivity(Track track) { flashTrackArmButton(track); }
                });
    }

    private void status(String text, DawIcon icon) {
        statusBarLabel.setText(text);
        if (icon != null) { statusBarLabel.setGraphic(IconNode.of(icon, 12)); }
    }

    private void flashTrackArmButton(Track track) {
        for (Node child : trackListPanel.getChildren()) {
            if (child.getUserData() == track) {
                Node armBtn = child.lookup(".track-arm-button");
                if (armBtn != null) {
                    FadeTransition flash = new FadeTransition(Duration.millis(120), armBtn);
                    flash.setFromValue(0.4);
                    flash.setToValue(1.0);
                    flash.play();
                }
                break;
            }
        }
    }

    private void createMetronomeController(Preferences prefs) {
        metronomeController = new MetronomeController(
                metronome, metronomeButton, notificationBar,
                statusBarLabel, prefs.node("metronome"),
                new MetronomeSettingsStore());
    }

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

    private void createProjectLifecycleController() {
        projectLifecycleController = new ProjectLifecycleController(
                projectManager, sessionInterchangeController, notificationBar,
                statusBarLabel, checkpointLabel, rootPane, trackListPanel,
                new ProjectLifecycleController.Host() {
                    @Override public DawProject project() { return project; }
                    @Override public void setProject(DawProject p) { project = p; }
                    @Override public UndoManager undoManager() { return undoManager; }
                    @Override public void setUndoManager(UndoManager um) { undoManager = um; }
                    @Override public boolean isProjectDirty() { return projectDirty; }
                    @Override public void setProjectDirty(boolean dirty) { projectDirty = dirty; }
                    @Override public void resetTrackCounters() { trackCreationController.resetCounters(); }
                    @Override public void rebuildHistoryPanel() { historyPanelController.rebuild(); }
                    @Override public void onProjectUIRebuild(MixerView newMixerView) {
                        handleProjectRebuild(newMixerView);
                    }
                });
    }

    private void handleProjectRebuild(MixerView newMixerView) {
        newMixerView.setPluginRegistry(pluginRegistry);
        // Story 137: a fresh project means fresh tracks — drop the old
        // per-track input monitors and let the engine/UI recreate them
        // lazily as tracks are armed.
        inputLevelMonitorRegistry.clear();
        newMixerView.setInputLevelMonitorRegistry(inputLevelMonitorRegistry);
        viewNavigationController.setMixerView(newMixerView);
        viewNavigationController.onProjectChanged();
        pluginViewController.onProjectChanged(project);
        metronome = new Metronome(project.getFormat().sampleRate(), project.getFormat().channels());
        createTransportController();
        metronomeController = new MetronomeController(metronome, metronomeButton, notificationBar,
                statusBarLabel, Preferences.userNodeForPackage(MainController.class).node("metronome"),
                new MetronomeSettingsStore());
        transportController.updateStatus();
        createTrackStripController();
        if (trackStripController != null) {
            trackStripController.setInputLevelMonitorRegistry(inputLevelMonitorRegistry);
        }
        updateProjectInfo();
        updateTempoDisplay();
        updateUndoRedoState();
        updateArrangementPlaceholder();
        if (rippleModeController != null) {
            rippleModeController.onProjectChanged();
        }
        if (viewNavigationController.getActiveView() == DawView.MIXER) {
            rootPane.setCenter(newMixerView);
        }
    }

    private void createViewNavigationController() {
        viewNavigationController = new ViewNavigationController(
                rootPane, statusBarLabel, toolbarStateStore, snapButton,
                activeView, activeEditTool, snapEnabled, gridResolution,
                new ViewNavigationController.Host() {
                    @Override public DawProject project() { return project; }
                    @Override public UndoManager undoManager() { return undoManager; }
                    @Override public void onEditorTrim() { clipEditController.onEditorTrim(); }
                    @Override public void onEditorFadeIn() { clipEditController.onEditorFadeIn(); }
                    @Override public void onEditorFadeOut() { clipEditController.onEditorFadeOut(); }
                    @Override public void markProjectDirty() { projectDirty = true; }
                });
    }

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
                    @Override public void toggleSnap() { viewNavigationController.onToggleSnap(); }
                    @Override public void skipToStart() { transportController.onSkipBack(); }
                    @Override public void markProjectDirty() { projectDirty = true; }
                    @Override public boolean isSnapEnabled() { return viewNavigationController.isSnapEnabled(); }
                    @Override public ZoomLevel currentZoomLevel() {
                        return viewNavigationController.getZoomLevel(viewNavigationController.getActiveView());
                    }
                    @Override public EditorView editorView() { return viewNavigationController.getEditorView(); }
                });
    }

    private void createPluginViewController() {
        pluginViewController = new PluginViewController(new PluginViewController.Host() {
            @Override public double sampleRate() { return project.getFormat().sampleRate(); }
            @Override public int bufferSize() { return project.getFormat().bufferSize(); }
            @Override public DawProject project() { return project; }
            @Override public void setProjectDirty() { projectDirty = true; }
            @Override public void switchToMasteringView() { viewNavigationController.switchView(DawView.MASTERING); }
            @Override public void updateStatusBar(String text, DawIcon icon) { status(text, icon); }
            @Override public void showNotification(NotificationLevel level, String message) { notificationBar.show(level, message); }
        });
    }

    private void createClipEditController() {
        clipEditController = new ClipEditController(new ClipEditController.Host() {
            @Override public DawProject project() { return project; }
            @Override public UndoManager undoManager() { return undoManager; }
            @Override public ClipboardManager clipboardManager() { return clipboardManager; }
            @Override public SelectionModel selectionModel() { return selectionModel; }
            @Override public void refreshArrangementCanvas() { MainController.this.refreshArrangementCanvas(); }
            @Override public void updateUndoRedoState() { MainController.this.updateUndoRedoState(); }
            @Override public void syncMenuState() { if (menuBarController != null) menuBarController.syncMenuState(); }
            @Override public void markProjectDirty() { projectDirty = true; }
            @Override public void updateStatusBar(String text, DawIcon icon) { status(text, icon); }
            @Override public void showNotificationWithUndo(NotificationLevel level, String msg, Runnable undo) { notificationBar.showWithUndo(level, msg, undo); }
            @Override public void showNotification(NotificationLevel level, String message) { notificationBar.show(level, message); }
            @Override public EditorView editorView() { return viewNavigationController.getEditorView(); }
            @Override public com.benesquivelmusic.daw.sdk.edit.RippleMode rippleMode() { return project.getRippleMode(); }
            @Override public double gridStepBeats() {
                GridResolution res = viewNavigationController != null
                        ? viewNavigationController.getGridResolution() : gridResolution;
                int beatsPerBar = project.getTransport().getTimeSignatureNumerator();
                return res.beatsPerGrid(beatsPerBar);
            }
        });
    }

    private void createRippleModeController() {
        rippleModeController = new RippleModeController(
                new RippleModeController.Host() {
                    @Override public DawProject project() { return project; }
                    @Override public void markProjectDirty() { projectDirty = true; }
                    @Override public void showNotification(NotificationLevel level, String message) {
                        notificationBar.show(level, message);
                    }
                },
                toolbarStateStore, rippleModeButton, rippleBannerLabel);
    }

    private void createTrackCreationController() {
        AudioDeviceManager deviceManager = new AudioDeviceManager(audioEngine);
        trackCreationController = new TrackCreationController(
                new TrackCreationController.Host() {
                    @Override public DawProject project() { return project; }
                    @Override public UndoManager undoManager() { return undoManager; }
                    @Override public TrackStripController trackStripController() { return trackStripController; }
                    @Override public MixerView mixerView() { return viewNavigationController.getMixerView(); }
                    @Override public VBox trackListPanel() { return trackListPanel; }
                    @Override public void updateArrangementPlaceholder() { MainController.this.updateArrangementPlaceholder(); }
                    @Override public void updateUndoRedoState() { MainController.this.updateUndoRedoState(); }
                    @Override public void markProjectDirty() { projectDirty = true; }
                    @Override public void updateStatusBar(String text, DawIcon icon) { status(text, icon); }
                    @Override public void showNotification(NotificationLevel level, String message) { notificationBar.show(level, message); }
                }, deviceManager);
    }

    private void createAudioImportController() {
        audioImportController = new AudioImportController(new AudioImportController.Host() {
            @Override public DawProject project() { return project; }
            @Override public UndoManager undoManager() { return undoManager; }
            @Override public TrackStripController trackStripController() { return trackStripController; }
            @Override public TrackCreationController trackCreationController() { return trackCreationController; }
            @Override public MixerView mixerView() { return viewNavigationController.getMixerView(); }
            @Override public VBox trackListPanel() { return trackListPanel; }
            @Override public Stage primaryStage() { return (Stage) rootPane.getScene().getWindow(); }
            @Override public void updateArrangementPlaceholder() { MainController.this.updateArrangementPlaceholder(); }
            @Override public void refreshArrangementCanvas() { MainController.this.refreshArrangementCanvas(); }
            @Override public void updateUndoRedoState() { MainController.this.updateUndoRedoState(); }
            @Override public void markProjectDirty() { projectDirty = true; }
            @Override public void updateStatusBar(String text, DawIcon icon) { status(text, icon); }
            @Override public void showNotification(NotificationLevel level, String message) { notificationBar.show(level, message); }
        });
    }

    private void createHistoryPanelController() {
        historyPanelController = new HistoryPanelController(
                rootPane, historyButton, notificationHistoryService,
                new HistoryPanelController.Host() {
                    @Override public UndoManager undoManager() { return undoManager; }
                    @Override public void updateUndoRedoState() { MainController.this.updateUndoRedoState(); }
                    @Override public void refreshArrangementCanvas() { MainController.this.refreshArrangementCanvas(); }
                    @Override public boolean isBrowserPanelVisible() { return browserPanelController.isPanelVisible(); }
                    @Override public void hideBrowserPanel() { browserPanelController.toggleBrowserPanel(); }
                    @Override public void updateStatusBar(String text, DawIcon icon) { status(text, icon); }
                });
        historyPanelController.build();
    }

    private void createKeyboardShortcutController() {
        keyboardShortcutController = new KeyboardShortcutController(keyBindingManager,
                new KeyboardShortcutController.Host() {
                    @Override public TransportState transportState() { return project.getTransport().getState(); }
                    @Override public void onPlay() { MainController.this.onPlay(); }
                    @Override public void onStop() { MainController.this.onStop(); }
                    @Override public void onRecord() { MainController.this.onRecord(); }
                    @Override public void onSkipBack() { transportController.onSkipBack(); }
                    @Override public void onSkipForward() { transportController.onSkipForward(); }
                    @Override public void onToggleLoop() { MainController.this.onToggleLoop(); }
                    @Override public void onToggleMetronome() { metronomeController.onToggleMetronome(); }
                    @Override public void onUndo() { MainController.this.onUndo(); }
                    @Override public void onRedo() { MainController.this.onRedo(); }
                    @Override public void onSaveProject() { projectLifecycleController.onSaveProject(); }
                    @Override public void onNewProject() { projectLifecycleController.onNewProject(); }
                    @Override public void onOpenProject() { projectLifecycleController.onOpenProject(); }
                    @Override public void onImportSession() { projectLifecycleController.onImportSession(); }
                    @Override public void onExportSession() { projectLifecycleController.onExportSession(); }
                    @Override public void onImportAudioFile() { audioImportController.onImportAudioFile(); }
                    @Override public void onToggleSnap() { viewNavigationController.onToggleSnap(); }
                    @Override public void onAddAudioTrack() { trackCreationController.onAddAudioTrack(); }
                    @Override public void onAddMidiTrack() { trackCreationController.onAddMidiTrack(); }
                    @Override public void selectEditTool(EditTool tool) { viewNavigationController.selectEditTool(tool); }
                    @Override public void onZoomIn() { viewNavigationController.onZoomIn(); }
                    @Override public void onZoomOut() { viewNavigationController.onZoomOut(); }
                    @Override public void onZoomToFit() { viewNavigationController.onZoomToFit(); }
                    @Override public void switchView(DawView view) { viewNavigationController.switchView(view); }
                    @Override public void onToggleBrowser() {
                        if (historyPanelController.isHistoryPanelVisible()) { historyPanelController.toggleHistoryPanel(); }
                        browserPanelController.toggleBrowserPanel();
                    }
                    @Override public void onToggleHistory() { historyPanelController.toggleHistoryPanel(); }
                    @Override public void onToggleNotificationHistory() { historyPanelController.toggleNotificationHistoryPanel(); }
                    @Override public void onToggleVisualizations() { vizPanelController.toggleRowVisibility(); }
                    @Override public void onOpenSettings() { MainController.this.onOpenSettings(); }
                    @Override public void onCopy() { clipEditController.onCopy(); }
                    @Override public void onCut() { clipEditController.onCut(); }
                    @Override public void onPaste() { clipEditController.onPaste(); }
                    @Override public void onDuplicate() { clipEditController.onDuplicate(); }
                    @Override public void onDeleteSelection() { clipEditController.onDeleteSelection(); }
                    @Override public void setRippleMode(com.benesquivelmusic.daw.sdk.edit.RippleMode mode) {
                        if (rippleModeController != null) { rippleModeController.setMode(mode); }
                    }
                    @Override public void onSlipLeftByGrid() { clipEditController.onSlipLeftByGrid(); }
                    @Override public void onSlipRightByGrid() { clipEditController.onSlipRightByGrid(); }
                    @Override public void onSlipLeftByFine() { clipEditController.onSlipLeftByFine(); }
                    @Override public void onSlipRightByFine() { clipEditController.onSlipRightByFine(); }
                });
    }

    private void initializeNotificationBar() {
        notificationBar = new NotificationBar();
        notificationBar.setHistoryService(notificationHistoryService);
        notificationBarContainer.getChildren().add(notificationBar);
        HBox.setHgrow(notificationBar, Priority.ALWAYS);
    }

    /**
     * Installs a {@link PluginInvocationSupervisor} on every mixer channel so
     * that an exception thrown by a plugin on the audio thread bypasses the
     * slot and surfaces as a toast/fault-log entry rather than crashing the
     * session. Wrapped in a try/catch so a wiring glitch cannot prevent the
     * main window from opening.
     */
    private void initializePluginFaultIsolation() {
        try {
            pluginSupervisor = new PluginInvocationSupervisor();
            pluginFaultUiController = new PluginFaultUiController(pluginSupervisor, notificationBar);
            // Mixer.setPluginSupervisor both installs the supervisor on every
            // current channel/bus/master AND remembers it so channels added
            // later (new tracks, return buses) inherit it automatically.
            project.getMixer().setPluginSupervisor(pluginSupervisor);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to initialize plugin fault supervisor", e);
        }
    }

    /**
     * Returns the plugin fault UI controller, for menu-bar wiring that opens
     * the fault log dialog. May be {@code null} if initialization failed.
     */
    PluginFaultUiController getPluginFaultUiController() {
        return pluginFaultUiController;
    }

    private void buildBrowserPanel(boolean initiallyVisible) {
        BrowserPanel browserPanel = new BrowserPanel();
        browserPanelController = new BrowserPanelController(browserPanel, browserButton, rootPane);
        browserPanelController.setOnVisibilityChanged(() -> {
            toolbarStateStore.saveBrowserVisible(browserPanelController.isPanelVisible());
            if (browserPanelController.isPanelVisible() && historyPanelController != null) {
                if (historyPanelController.isHistoryPanelVisible()) {
                    historyPanelController.setHistoryPanelVisible(false);
                }
                if (historyPanelController.isNotificationHistoryPanelVisible()) {
                    historyPanelController.setNotificationHistoryPanelVisible(false);
                }
            }
        });
        browserPanelController.initialize();
        if (initiallyVisible) {
            browserPanelController.toggleBrowserPanel();
        }
    }

    private void createTempoEditController() {
        tempoEditController = new TempoEditController(tempoLabel, new TempoEditController.Host() {
            @Override public DawProject project() { return project; }
            @Override public UndoManager undoManager() { return undoManager; }
            @Override public void updateUndoRedoState() { MainController.this.updateUndoRedoState(); }
            @Override public void updateTempoDisplay() { MainController.this.updateTempoDisplay(); }
            @Override public void updateStatusBar(String text, DawIcon icon) { status(text, icon); }
            @Override public void showNotification(NotificationLevel level, String message) { notificationBar.show(level, message); }
        });
        tempoEditController.install();
    }

    private void createArrangementCanvas() {
        ArrangementCanvasFactory.Result result = ArrangementCanvasFactory.create(
                arrangementContentPane,
                new ArrangementCanvasFactory.Host() {
                    @Override public DawProject project() { return project; }
                    @Override public UndoManager undoManager() { return undoManager; }
                    @Override public SelectionModel selectionModel() { return selectionModel; }
                    @Override public EditTool activeEditTool() { return viewNavigationController.getActiveEditTool(); }
                    @Override public boolean isSnapEnabled() { return viewNavigationController.isSnapEnabled(); }
                    @Override public GridResolution gridResolution() { return viewNavigationController.getGridResolution(); }
                    @Override public void refreshCanvas() { refreshArrangementCanvas(); }
                    @Override public void seekToPosition(double beat) { MainController.this.seekToPosition(beat); }
                    @Override public void updateStatusBar(String text) { statusBarLabel.setText(text); }
                    @Override public com.benesquivelmusic.daw.sdk.edit.RippleMode rippleMode() {
                        return project.getRippleMode();
                    }
                    @Override public void showNotification(NotificationLevel level, String message) {
                        notificationBar.show(level, message);
                    }
                },
                this::seekToPosition);
        arrangementCanvas = result.canvas();
        timelineRuler = result.ruler();
        clipInteractionController = result.clipInteraction();
        refreshArrangementCanvas();
        trackStripController.setArrangementCanvas(arrangementCanvas);
        animationController.setPlayheadUpdateCallback(this::updatePlayheadFromTransport);
        audioImportController.installArrangementCanvasDragDrop(arrangementCanvas);
    }

    private void createMenuBar() {
        menuBarController = new DawMenuBarController(
                new DawMenuBarController.Host() {
                    @Override public DawProject project() { return project; }
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
                    @Override public void onImportAudioFile() { audioImportController.onImportAudioFile(); }
                    @Override public void onUndo() { MainController.this.onUndo(); }
                    @Override public void onRedo() { MainController.this.onRedo(); }
                    @Override public void onCopy() { clipEditController.onCopy(); }
                    @Override public void onCut() { clipEditController.onCut(); }
                    @Override public void onPaste() { clipEditController.onPaste(); }
                    @Override public void onDuplicate() { clipEditController.onDuplicate(); }
                    @Override public void onDeleteSelection() { clipEditController.onDeleteSelection(); }
                    @Override public void onToggleSnap() { viewNavigationController.onToggleSnap(); }
                    @Override public void onManagePlugins() { pluginViewController.onManagePlugins(pluginRegistry); }
                    @Override public void onOpenSettings() { MainController.this.onOpenSettings(); }
                    @Override public void onOpenAudioSettings() { MainController.this.onOpenAudioSettings(); }
                    @Override public void onActivateBuiltInPlugin(Class<? extends BuiltInDawPlugin> pluginClass) {
                        pluginViewController.onActivateBuiltInPlugin(pluginClass);
                    }
                    @Override public void onSwitchView(DawView view) { viewNavigationController.switchView(view); }
                    @Override public void onToggleBrowser() {
                        if (historyPanelController.isHistoryPanelVisible()) { historyPanelController.toggleHistoryPanel(); }
                        browserPanelController.toggleBrowserPanel();
                    }
                    @Override public void onToggleHistory() { historyPanelController.toggleHistoryPanel(); }
                    @Override public void onToggleNotificationHistory() { historyPanelController.toggleNotificationHistoryPanel(); }
                    @Override public void onToggleVisualizations() { vizPanelController.toggleRowVisibility(); }
                    @Override public void onHelp() { MainController.this.onHelp(); }
                },
                keyBindingManager);
        javafx.scene.control.MenuBar bar = menuBarController.build();
        Node topNode = rootPane.getTop();
        if (topNode instanceof VBox topVBox) { topVBox.getChildren().addFirst(bar); }
    }

    @FXML private void onPlay() { transportController.onPlay(); }
    @FXML private void onStop() { transportController.onStop(); }
    @FXML private void onPause() { transportController.onPause(); }
    @FXML private void onRecord() { transportController.onRecord(); }
    @FXML private void onSkipBack() { transportController.onSkipBack(); }
    @FXML private void onSkipForward() { transportController.onSkipForward(); }
    @FXML private void onToggleLoop() { transportController.onToggleLoop(); syncLoopRegionToCanvas(); }
    @FXML private void onToggleMetronome() { metronomeController.onToggleMetronome(); }
    @FXML private void onAddAudioTrack() { trackCreationController.onAddAudioTrack(); }
    @FXML private void onAddMidiTrack() { trackCreationController.onAddMidiTrack(); }

    @FXML private void onSaveProject() {
        projectLifecycleController.onSaveProject();
        if (menuBarController != null) menuBarController.syncMenuState();
    }

    @FXML private void onNewProject() {
        projectLifecycleController.onNewProject();
        if (menuBarController != null) menuBarController.syncMenuState();
    }

    @FXML private void onOpenProject() {
        projectLifecycleController.onOpenProject();
        if (menuBarController != null) menuBarController.syncMenuState();
    }

    @FXML private void onRecentProjects() { projectLifecycleController.onRecentProjects(); }
    @FXML private void onImportSession() { projectLifecycleController.onImportSession(); }
    @FXML private void onExportSession() { projectLifecycleController.onExportSession(); }
    @FXML private void onManagePlugins() { pluginViewController.onManagePlugins(pluginRegistry); }

    @FXML private void onUndo() {
        if (undoManager.undo()) {
            status("Undo: " + undoManager.redoDescription(), DawIcon.UNDO);
            updateTempoDisplay();
            projectDirty = true;
        } else {
            status("Nothing to undo", DawIcon.INFO_CIRCLE);
        }
        updateUndoRedoState();
    }

    @FXML private void onRedo() {
        if (undoManager.redo()) {
            status("Redo: " + undoManager.undoDescription(), DawIcon.REDO);
            updateTempoDisplay();
            projectDirty = true;
        } else {
            status("Nothing to redo", DawIcon.INFO_CIRCLE);
        }
        updateUndoRedoState();
    }

    @FXML private void onOpenSettings() {
        status("Opening settings...", DawIcon.SETTINGS);
        SettingsModel settingsModel = new SettingsModel(Preferences.userNodeForPackage(SettingsModel.class));
        String previousPluginPaths = settingsModel.getPluginScanPaths();
        SettingsDialog dialog = new SettingsDialog(settingsModel);
        dialog.setAudioEngineController(audioEngineController);
        dialog.setSettingsChangeListener(model -> applyLiveSettings(model, previousPluginPaths));
        dialog.showAndWait();
        status("Settings closed", DawIcon.STATUS);
    }

    void onOpenAudioSettings() {
        status("Opening audio settings...", DawIcon.HEADPHONES);
        SettingsModel settingsModel = new SettingsModel(Preferences.userNodeForPackage(SettingsModel.class));
        AudioSettingsDialog dialog = new AudioSettingsDialog(settingsModel, audioEngineController);
        dialog.showAndWait();
        status("Audio settings closed", DawIcon.STATUS);
    }

    void onHome() {
        viewNavigationController.switchView(DawView.ARRANGEMENT);
        viewNavigationController.getZoomLevel(DawView.ARRANGEMENT).zoomToFit();
        selectionModel.clearSelection();
        status("Home \u2014 returned to default arrangement view", DawIcon.HOME);
    }

    void onSearch() {
        if (!browserPanelController.isPanelVisible()) { browserPanelController.toggleBrowserPanel(); }
        browserPanelController.getBrowserPanel().getSearchField().requestFocus();
        status("Search \u2014 browser panel opened", DawIcon.SEARCH);
    }

    void onHelp() {
        status("Opening help...", DawIcon.INFO);
        new HelpDialog().showAndWait();
        status("Help closed", DawIcon.STATUS);
    }

    public DawView getActiveView() { return viewNavigationController.getActiveView(); }
    public EditTool getActiveEditTool() { return viewNavigationController.getActiveEditTool(); }
    public boolean isSnapEnabled() { return viewNavigationController.isSnapEnabled(); }
    public GridResolution getGridResolution() { return viewNavigationController.getGridResolution(); }
    public ZoomLevel getZoomLevel(DawView view) { return viewNavigationController.getZoomLevel(view); }
    public ClipboardManager getClipboardManager() { return clipboardManager; }
    public SelectionModel getSelectionModel() { return selectionModel; }

    private void updateTempoDisplay() {
        tempoLabel.setText(String.format("%.1f BPM", project.getTransport().getTempo()));
        tempoLabel.setGraphic(IconNode.of(DawIcon.KNOB, PANEL_ICON_SIZE));
    }

    private void updateProjectInfo() {
        AudioFormat fmt = project.getFormat();
        projectInfoLabel.setText(String.format("%s  \u00b7  %.0f kHz / %d-bit / %dch",
                project.getName(), fmt.sampleRate() / 1000.0, fmt.bitDepth(), fmt.channels()));
        DawIcon fmtIcon = switch (fmt.bitDepth()) { case 32 -> DawIcon.AIFF; case 24 -> DawIcon.FLAC; case 16 -> DawIcon.WAV; default -> DawIcon.OGG; };
        projectInfoLabel.setGraphic(IconNode.of(fmtIcon, 12));
        DawIcon channelIcon = switch (fmt.channels()) { case 1 -> DawIcon.MONO; case 2 -> DawIcon.STEREO; default -> DawIcon.SURROUND; };
        monitoringLabel.setGraphic(IconNode.of(channelIcon, 12));
        monitoringLabel.setText(switch (fmt.channels()) { case 1 -> "Mono"; case 2 -> "Stereo"; default -> fmt.channels() + "ch Surround"; });
        DawIcon routingIcon = (fmt.sampleRate() >= 96_000.0) ? DawIcon.THUNDERBOLT : DawIcon.USB;
        ioRoutingLabel.setGraphic(IconNode.of(routingIcon, 12));
        ioRoutingLabel.setText(String.format("%.0f kHz I/O", fmt.sampleRate() / 1000.0));
    }

    private void updateCheckpointStatus() {
        checkpointLabel.setText("Auto-save: ON");
        checkpointLabel.setGraphic(IconNode.of(DawIcon.HISTORY, 12));
        ioRoutingLabel.setGraphic(IconNode.of(DawIcon.CLOCK, 12));
        ioRoutingLabel.setText("Initializing I/O...");
    }

    private void updateArrangementPlaceholder() {
        arrangementPlaceholder.setVisible(project.getTracks().isEmpty());
        refreshArrangementCanvas();
    }

    private void refreshArrangementCanvas() {
        if (arrangementCanvas == null) return;
        arrangementCanvas.setTracks(project.getTracks());
        syncLoopRegionToCanvas();
        syncSelectionToCanvas();
    }

    private void updateUndoRedoState() {
        undoButton.setDisable(!undoManager.canUndo());
        redoButton.setDisable(!undoManager.canRedo());
        undoButton.setTooltip(new Tooltip(undoManager.canUndo() ? "Undo: " + undoManager.undoDescription() + " (Ctrl+Z)" : "Nothing to undo"));
        redoButton.setTooltip(new Tooltip(undoManager.canRedo() ? "Redo: " + undoManager.redoDescription() + " (Ctrl+Shift+Z)" : "Nothing to redo"));
        if (menuBarController != null) menuBarController.syncMenuState();
    }

    private void seekToPosition(double beat) {
        double position = Math.max(0.0, beat);
        boolean snap = viewNavigationController != null ? viewNavigationController.isSnapEnabled() : snapEnabled;
        if (snap) {
            GridResolution res = viewNavigationController != null ? viewNavigationController.getGridResolution() : gridResolution;
            position = SnapQuantizer.quantize(position, res, project.getTransport().getTimeSignatureNumerator());
        }
        project.getTransport().setPositionInBeats(position);
        if (timelineRuler != null) timelineRuler.setPlayheadPositionBeats(position);
        if (arrangementCanvas != null) arrangementCanvas.setPlayheadBeat(position);
    }

    private void updatePlayheadFromTransport() {
        double beat = project.getTransport().getPositionInBeats();
        if (timelineRuler != null) timelineRuler.setPlayheadPositionBeats(beat);
        if (arrangementCanvas != null) arrangementCanvas.setPlayheadBeat(beat);
        syncLoopRegionToCanvas();
    }

    private void syncLoopRegionToCanvas() {
        Transport transport = project.getTransport();
        if (arrangementCanvas != null) {
            arrangementCanvas.setLoopRegion(transport.isLoopEnabled(), transport.getLoopStartInBeats(), transport.getLoopEndInBeats());
        }
        if (timelineRuler != null) {
            boolean snap = viewNavigationController != null ? viewNavigationController.isSnapEnabled() : snapEnabled;
            GridResolution res = viewNavigationController != null ? viewNavigationController.getGridResolution() : gridResolution;
            timelineRuler.setSnapEnabled(snap);
            timelineRuler.setGridResolution(res);
            timelineRuler.redraw();
        }
    }

    private void syncSelectionToCanvas() {
        if (arrangementCanvas != null) {
            arrangementCanvas.setSelectionRange(selectionModel.hasSelection(), selectionModel.getStartBeat(), selectionModel.getEndBeat());
        }
    }

    private void applyLiveSettings(SettingsModel model, String previousPluginPaths) {
        LiveSettingsApplier.apply(model, previousPluginPaths, rootPane.getScene(),
                projectManager, project, pluginRegistry);
    }
}
