package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplay;
import com.benesquivelmusic.daw.app.ui.help.HelpControls;
import com.benesquivelmusic.daw.app.ui.help.HelpKeyHandler;
import com.benesquivelmusic.daw.app.ui.help.HelpOverlay;
import com.benesquivelmusic.daw.app.ui.help.HelpRegistry;
import com.benesquivelmusic.daw.app.ui.help.OnboardingState;
import com.benesquivelmusic.daw.app.ui.help.OnboardingTour;
import com.benesquivelmusic.daw.app.ui.help.QuickHelpBar;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.audio.AudioBackendFactory;
import com.benesquivelmusic.daw.core.audio.AudioDeviceManager;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.export.RenderQueue;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.ChannelNameSnapshotReconciler;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.persistence.RecentProjectsStore;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
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
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
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

    /** Resource bundle for status-bar chrome strings (story 274 / Skill
     *  §14) — Locale.ROOT, mirroring NotificationPill / InspectorDrawer. */
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    @FXML private BorderPane rootPane;
    @FXML private Button skipBackButton;
    @FXML private Button skipForwardButton;
    @FXML private Button loopButton;
    @FXML private Button playButton;
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
    // Story 274 — status-bar cells. cpuLabel/memLabel/dskLabel are STATIC
    // PLACEHOLDER cells: no system CPU/memory/disk telemetry source exists
    // in this codebase (PerformanceMonitor tracks only audio-DSP-thread
    // load). Their text comes from Messages.properties (Skill §14).
    // TODO story-274 follow-on: wire cpu/mem/dsk to a real telemetry
    // source (out of scope per story Non-Goals — no polling Service /
    // OperatingSystemMXBean / Runtime probe is introduced here).
    @FXML private Label cpuLabel;
    @FXML private Label memLabel;
    @FXML private Label dskLabel;
    @FXML private Label recIndicator;
    @FXML private HBox notificationBarContainer;
    @FXML private HBox transportGroup;
    @FXML private HBox trackGroup;
    @FXML private HBox undoRedoGroup;
    @FXML private HBox utilityGroup;
    @FXML private VBox trackListPanel;
    @FXML private HBox vizTileRow;
    /** Story 272 — unified Inspector drawer on the right edge of the centre BorderPane. */
    @FXML private com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer inspectorDrawer;

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
    /** Per-track CPU budget enforcer wired into the engine (story 129 UI). */
    private com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer cpuBudgetEnforcer;
    /** UI binding that subscribes to enforcer events and surfaces badges/notifications. */
    private TrackBudgetUiBinding trackBudgetUiBinding;
    /** Cached settings model for transport-controller access to latency compensation toggle. */
    private SettingsModel settingsModel;
    private NotificationBar notificationBar;
    private Metronome metronome;
    private PluginInvocationSupervisor pluginSupervisor;
    private PluginFaultUiController pluginFaultUiController;

    /**
     * Story 187 — title-bar lock state badge mounted next to the project
     * name. Created in {@link #initialize()} and inserted into the status
     * bar HBox immediately after {@link #projectInfoLabel}; refreshed via
     * a {@link #lockIndicatorTimeline 5 s timer} and after every project
     * open / save so the user always sees lock state at a glance.
     */
    private LockStatusIndicator lockStatusIndicator;
    /** Periodic refresher for {@link #lockStatusIndicator} (5 s, per spec). */
    private Timeline lockIndicatorTimeline;

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
    private CommandPaletteView commandPaletteView;
    private WorkspaceManager workspaceManager;
    private com.benesquivelmusic.daw.app.ui.dock.DockManager dockManager;
    /**
     * Story 190 — Snapshot History Browser. Owns the data-only
     * SnapshotBrowserService and the lazy SnapshotBrowser dialog;
     * surfaces "File → Snapshots…" and "File → Create Checkpoint"
     * (Ctrl+Alt+S) into the application.
     */
    private SnapshotsController snapshotsController;
    /**
     * Story 100 — Track Templates and Channel-Strip Presets. Owns the
     * {@code TrackTemplateStore} (under {@code ~/.daw/templates} and
     * {@code .../presets}) and routes Save/Apply/Manage menu actions
     * through the undo manager so user-facing workflows are reversible.
     */
    private TrackTemplateController trackTemplateController;
    /**
     * Story 035 — Track Freeze and Unfreeze for CPU Management. Wires
     * the per-track ❄ snowflake glyph, the Tracks menu freeze entries,
     * the right-click context menus, and the modeless task-progress
     * indicator that appears during the offline render.
     */
    private TrackFreezeController trackFreezeController;
    /**
     * Story 191 — Auto-Backup Rotation. Owns the persisted retention
     * policy, runs a periodic prune of {@code ~/.daw/autosaves/}, and
     * surfaces the {@link BackupSettingsDialog} from the Edit menu.
     */
    private BackupRetentionController backupRetentionController;
    /**
     * Story 175 — Atmos A/B comparison view. Created on demand when the
     * user opens "QC → Immersive A/B…" and disposed when the window is
     * closed.
     */
    private com.benesquivelmusic.daw.app.ui.spatial.AtmosAbView atmosAbView;
    /** The floating window hosting {@link #atmosAbView}. */
    private Stage atmosAbStage;

    /**
     * Story 186 — Offline Render Queue (singleton, scoped to the app
     * lifetime — the queue is a tool, not a project state, so it
     * survives project changes). Lazily composed on first use; persisted
     * on shutdown via {@code RenderQueuePersistence}.
     */
    private RenderQueue renderQueue;
    /** Floating window hosting the {@link com.benesquivelmusic.daw.app.ui.export.RenderQueueView}. */
    private Stage renderQueueStage;
    /** The current view (re-created if the user closes its window). */
    private com.benesquivelmusic.daw.app.ui.export.RenderQueueView renderQueueView;

    private ArrangementCanvas arrangementCanvas;
    private ClipInteractionController clipInteractionController;
    private TimelineRuler timelineRuler;
    private SpectrumDisplay spectrumDisplay;
    private LevelMeterDisplay levelMeterDisplay;

    /** Contextual help registry — loads markdown topics from {@code resources/help/}. */
    private final HelpRegistry helpRegistry = HelpRegistry.loadDefault();
    private static final String HELP_WINDOW_LISTENER_KEY = "help.windowListenerInstalled";
    /** Right-side overlay displaying the active help topic; lazily created with the scene. */
    private HelpOverlay helpOverlay;
    /** Bottom Quick Help bar — toggled with {@code Shift+F1}. */
    private QuickHelpBar quickHelpBar;
    /** F1 / Shift+F1 key handler installed on the primary scene. */
    private HelpKeyHandler helpKeyHandler;

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
        audioEngineController = new DefaultAudioEngineController(audioEngine, () -> {
            updateProjectInfo();
            // Story 129 (UI): reinstall the per-track CPU budget enforcer
            // whenever the engine is reconfigured (sample rate / buffer size
            // change in AudioSettingsDialog → applyConfiguration) so the
            // enforcer's blockBudgetNanos stays in sync with the live format.
            installTrackCpuBudgetEnforcer();
        });

        // Apply the persisted mix precision from user preferences to the
        // project's mixer so that a previously-saved FLOAT_32 choice is
        // honoured on restart rather than silently reverting to the default.
        SettingsModel startupSettings = new SettingsModel(Preferences.userNodeForPackage(SettingsModel.class));
        this.settingsModel = startupSettings;
        project.getMixer().setMixPrecision(startupSettings.getMixPrecision());

        CheckpointManager checkpointManager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        // Story 190: wire a project data supplier so on-disk checkpoint
        // files contain the full serialized project XML (not a text
        // summary). This allows SnapshotsController.loadFromEntry() to
        // deserialize checkpoint files via ProjectDeserializer.
        com.benesquivelmusic.daw.core.persistence.ProjectSerializer checkpointSerializer =
                new com.benesquivelmusic.daw.core.persistence.ProjectSerializer();
        checkpointManager.setProjectDataSupplier(() -> {
            try {
                return checkpointSerializer.serialize(project);
            } catch (java.io.IOException e) {
                LOG.log(Level.WARNING, "Failed to serialize project for on-disk checkpoint", e);
                return null;
            }
        });
        Preferences prefs = Preferences.userNodeForPackage(MainController.class);
        RecentProjectsStore recentProjectsStore = new RecentProjectsStore(prefs);
        projectManager = new ProjectManager(checkpointManager, recentProjectsStore);
        // Story 187 — install the JavaFX lock-conflict dialog so opening a
        // project that is already locked by another session prompts the
        // user with Open Read-Only / Take Over / Cancel rather than
        // throwing ProjectLockedException unconditionally.
        projectManager.setLockConflictHandler(new LockConflictDialog());
        // Story 190 — Snapshot History Browser. The data-only service
        // and the (lazy) browser dialog are owned by SnapshotsController,
        // which is composed here once for the lifetime of the session
        // and reused after every project open / new.
        snapshotsController = new SnapshotsController(
                new com.benesquivelmusic.daw.core.snapshot.SnapshotBrowserService(),
                checkpointManager,
                projectManager,
                new SnapshotsController.Host() {
                    @Override public Stage ownerStage() {
                        return rootPane.getScene() != null
                                ? (Stage) rootPane.getScene().getWindow() : null;
                    }
                    @Override public DawProject currentProject() { return project; }
                    @Override public boolean confirmDiscardUnsavedChanges() {
                        return projectLifecycleController == null
                                || projectLifecycleController.confirmDiscardUnsavedChanges();
                    }
                    @Override public void applyRestoredProject(DawProject restored, String label) {
                        applySnapshotRestoredProject(restored, label);
                    }
                });
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
        mountPreRollPostRollControls();
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
        transportController.syncLoopButtonState();
        updateTempoDisplay();
        updateProjectInfo();
        mountLockStatusIndicator();
        updateCheckpointStatus();
        initializeStatusBarPlaceholders();
        updateUndoRedoState();
        installIoLatencyClickHandler();
        animationController.start();
        viewNavigationController.getMixerView().setPluginRegistry(pluginRegistry);
        // Story 197 — share the single DragVisualAdvisor / AnimationProfile
        // with the mixer so plugin reorder-drag gestures use the unified
        // visual feedback layer.
        viewNavigationController.getMixerView()
                .setDragVisualAdvisor(animationController.dragVisualAdvisor());
        // Story 197 — share the single advisor with the browser panel so
        // sample-drag gestures use the unified visual feedback layer.
        browserPanelController.getBrowserPanel()
                .setDragVisualAdvisor(animationController.dragVisualAdvisor());
        // Story 137: bind the input-level-monitor registry into the mixer
        // so armed-track strips grow a second meter column with a latching
        // clip LED, and into the track-strip controller so armed tracks
        // also show the miniature clip indicator in the arrangement view.
        viewNavigationController.getMixerView()
                .setInputLevelMonitorRegistry(inputLevelMonitorRegistry);
        // Story 215: wire driver-reported input/output channel-info
        // suppliers into the mixer so per-track routing dropdowns render
        // "Mic/Line 1" / "S/PDIF L" / "Phones 1 L" rather than the
        // generic "Input N" labels. Each supplier reads the live SDK
        // backend so a future driver-side rename is reflected on the
        // next dropdown rebuild without restarting the DAW.
        installChannelInfoSuppliers(viewNavigationController.getMixerView());
        if (trackStripController != null) {
            trackStripController.setInputLevelMonitorRegistry(inputLevelMonitorRegistry);
        }
        // Story 100: wire the templates controller into the track-list
        // right-click menu and the mixer per-channel right-click menu.
        // Both views check for a null controller and hide the items, so
        // tests and non-UI callers continue to work unchanged.
        createTrackTemplateController();
        if (trackStripController != null) {
            trackStripController.setTrackTemplateController(trackTemplateController);
        }
        viewNavigationController.getMixerView()
                .setTrackTemplateController(trackTemplateController);
        // Story 035 — wire the per-track freeze workflow into the track
        // strip context menu, the mixer channel context menu, and the
        // Tracks menu before the menu bar is constructed below.
        createTrackFreezeController();
        if (trackStripController != null) {
            trackStripController.setTrackFreezeController(trackFreezeController);
        }
        viewNavigationController.getMixerView()
                .setTrackFreezeController(trackFreezeController);
        // Story 129 (UI): construct the per-track CPU budget enforcer
        // and wire it into the engine + the mixer view so the policy
        // actually engages and the UI surfaces a "⚠" badge on degraded
        // strips. Composed here (after the MixerView is alive but
        // before transport/menu wiring) so every later refresh sees
        // the binding.
        installTrackCpuBudgetEnforcer();
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
                installContextualHelp(scene);
                if (scene.getWindow() instanceof Stage primaryStage) {
                    if (commandPaletteView != null) {
                        commandPaletteView.setOwner(primaryStage);
                    }
                    primaryStage.setOnHidden(_ -> {
                        disposeRenderQueue();
                        pluginViewController.dispose();
                        if (pluginFaultUiController != null) {
                            pluginFaultUiController.dispose();
                        }
                        if (pluginSupervisor != null) {
                            pluginSupervisor.close();
                        }
                        if (backupRetentionController != null) {
                            backupRetentionController.shutdown();
                        }
                        if (lockIndicatorTimeline != null) {
                            lockIndicatorTimeline.stop();
                            lockIndicatorTimeline = null;
                        }
                    });
                }
            }
        });
        LOG.info("DAW initialized with studio quality format");
        // Story 191 — Auto-Backup Rotation. Initialize the global retention
        // store and schedule the hourly periodic prune. The initial
        // applyNow() runs on the controller's daemon scheduler thread
        // (not the FX thread) to avoid blocking startup on directory
        // scanning and filesystem I/O.
        backupRetentionController = new BackupRetentionController();
        try {
            backupRetentionController.start();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to start backup retention controller", e);
        }

        // Story 272 — wire source-side typed selection events
        // (TrackSelectionEvent / InsertSelectedEvent / SendSelectedEvent)
        // into the unified Inspector drawer's selection model. The
        // drawer fires InspectorSelectionEvent.SELECTION_CHANGED on the
        // standard event dispatch chain in response.
        if (inspectorDrawer != null && rootPane != null) {
            inspectorDrawer.installSourceEventForwarding(rootPane);
        }
    }

    private void createToolbarAppearanceController() {
        toolbarAppearanceController = new ToolbarAppearanceController(
                new ToolbarAppearanceController.TransportButtons(
                        skipBackButton, playButton, stopButton,
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
     * Story 134 — Pre-Roll / Post-Roll transport bar controls. Builds the
     * toggle buttons + bar-count spinners and inserts them into
     * {@link #transportGroup}, immediately after the existing transport
     * buttons (skip / play / pause / stop / record / loop). On subsequent
     * project rebuilds the previous controls are replaced so the new
     * {@link TransportController} owns the listener wiring.
     */
    private HBox preRollPostRollControlsContainer;

    private void mountPreRollPostRollControls() {
        if (transportGroup == null || transportController == null) {
            return;
        }
        Node parent = transportGroup.getParent();
        if (!(parent instanceof HBox transportBar)) {
            return;
        }
        if (preRollPostRollControlsContainer != null) {
            transportBar.getChildren().remove(preRollPostRollControlsContainer);
        }
        preRollPostRollControlsContainer =
                transportController.createPreRollPostRollControls();
        int idx = transportBar.getChildren().indexOf(transportGroup);
        transportBar.getChildren().add(idx + 1, preRollPostRollControlsContainer);
    }

    private void createTransportController() {
        transportController = new TransportController(
                project, audioEngine, undoManager, notificationBar,
                statusLabel, timeDisplay, statusBarLabel, recIndicator,
                playButton, stopButton, recordButton, loopButton,
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
                    @Override public boolean isApplyLatencyCompensation() {
                        return settingsModel.isApplyLatencyCompensation();
                    }
                    @Override public com.benesquivelmusic.daw.sdk.audio.RoundTripLatency reportedLatency() {
                        return audioEngineController != null
                                ? audioEngineController.reportedLatency()
                                : com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN;
                    }
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
        // Story 135 — share a single MetronomeSideOutputRouter across the app
        // so the click-to-cue routing chosen in MetronomeSettingsDialog
        // survives project reloads and is observable by the audio engine
        // through the same instance.
        if (metronomeSideOutputRouter == null) {
            metronomeSideOutputRouter =
                    new com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter();
        }
        // Story 136 — wire the metronome, the side-output router, and the
        // project's cue bus manager into the audio engine so its
        // per-buffer render path invokes router.route(...) on every
        // scheduled beat. The MetronomeController and the engine now
        // share the same router instance, so dialog edits take effect
        // on the next audio block without restart.
        if (audioEngine != null) {
            audioEngine.setMetronome(metronome);
            audioEngine.setMetronomeSideOutputRouter(metronomeSideOutputRouter);
            audioEngine.setCueBusManager(
                    project == null ? null : project.getCueBusManager());
        }
        metronomeController = new MetronomeController(
                metronome, metronomeButton, notificationBar,
                statusBarLabel, prefs.node("metronome"),
                new MetronomeSettingsStore(),
                () -> project == null ? null : project.getCueBusManager(),
                () -> metronomeSideOutputRouter);
    }

    /** Story 135 — shared side-output router; lazily created in
     *  {@link #createMetronomeController(Preferences)}. */
    private com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter
            metronomeSideOutputRouter;

    private void createAnimationController() {
        animationController = new AnimationController(
                spectrumDisplay, levelMeterDisplay, timeDisplay,
                playButton, recordButton,
                new Button[]{
                        skipBackButton, playButton, stopButton, recordButton,
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
                },
                new ProjectArchiver());
    }

    private void handleProjectRebuild(MixerView newMixerView) {
        newMixerView.setPluginRegistry(pluginRegistry);
        // Story 100: re-attach the templates controller so the freshly
        // built MixerView's per-channel right-click menu still exposes
        // "Save channel strip\u2026" and "Apply channel strip\u2026".
        newMixerView.setTrackTemplateController(trackTemplateController);
        // Story 137: a fresh project means fresh tracks — drop the old
        // per-track input monitors and let the engine/UI recreate them
        // lazily as tracks are armed.
        inputLevelMonitorRegistry.clear();
        newMixerView.setInputLevelMonitorRegistry(inputLevelMonitorRegistry);
        // Story 215: the freshly-built MixerView starts with the default
        // empty supplier — re-wire the live driver-channel suppliers so
        // routing dropdowns show driver-reported names.
        installChannelInfoSuppliers(newMixerView);
        // Story 215: a project loaded from disk carries channelNameSnapshot
        // values in its track / mixer-channel routing display names.
        // Compare against what the live driver reports right now and
        // surface a single notification per project load if any name has
        // drifted (e.g., the user renamed "Mic 3" to "Hi-Z Inst 3" in
        // the driver since saving).
        notifyChannelNameMismatchOnce();
        viewNavigationController.setMixerView(newMixerView);
        viewNavigationController.onProjectChanged();
        // Story 129 (UI): a fresh project means a fresh set of tracks
        // and possibly a different sample-rate / buffer-size — rewire
        // the per-track CPU budget enforcer and its UI binding.
        installTrackCpuBudgetEnforcer();
        pluginViewController.onProjectChanged(project);
        metronome = new Metronome(project.getFormat().sampleRate(), project.getFormat().channels());
        createTransportController();
        mountPreRollPostRollControls();
        createMetronomeController(Preferences.userNodeForPackage(MainController.class));
        transportController.updateStatus();
        transportController.syncLoopButtonState();
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
        // Story 190 — re-register the freshly-loaded project's
        // checkpoints/ directory with the snapshot service so its
        // history shows up immediately in the browser.
        if (snapshotsController != null) {
            snapshotsController.registerCurrentProjectDirectory();
        }
    }

    /**
     * Story 190 — applies a project restored from the snapshot browser.
     * Mirrors {@link ProjectLifecycleController#loadProjectFromPath} but
     * skips the on-disk read because the {@link DawProject} has already
     * been deserialized from the snapshot's stored XML.
     */
    private void applySnapshotRestoredProject(DawProject restored, String label) {
        if (restored == null) return;
        this.project = restored;
        this.undoManager = new UndoManager();
        if (historyPanelController != null) historyPanelController.rebuild();
        if (trackCreationController != null) trackCreationController.resetCounters();
        this.projectDirty = true;
        trackListPanel.getChildren().clear();
        Label header = new Label("TRACKS");
        header.getStyleClass().add("panel-header");
        // No icon-next-to-label per UI Design Book §2.4.
        trackListPanel.getChildren().add(header);
        MixerView newMixerView = new MixerView(project, undoManager);
        handleProjectRebuild(newMixerView);
        if (notificationBar != null && label != null) {
            notificationBar.show(NotificationLevel.INFO,
                    "Restored snapshot: " + label);
        }
    }

    /**
     * Wires the {@link MixerView}'s driver-reported input/output
     * channel-info suppliers (story 215) to the live SDK
     * {@link AudioBackend} on the audio engine. Each supplier is a
     * closure over {@code audioEngine}, so a future driver-side rename
     * is reflected on the next dropdown rebuild without restarting the
     * DAW. When no SDK backend is wired (e.g., the user is on legacy
     * PortAudio / Java Sound), the suppliers return an empty list and
     * the dropdown falls back to its legacy "Input N" labels.
     */
    private void installChannelInfoSuppliers(MixerView mixerView) {
        mixerView.setInputChannelInfoSupplier(
                () -> liveChannelInfo(/*input*/ true));
        mixerView.setOutputChannelInfoSupplier(
                () -> liveChannelInfo(/*input*/ false));
    }

    private List<AudioChannelInfo> liveChannelInfo(boolean isInput) {
        AudioBackend backend = audioEngine == null ? null : audioEngine.getBackend();
        if (backend == null) {
            return List.of();
        }
        DeviceId device = audioEngineController != null
                ? audioEngineController.getActiveDevice().orElse(null)
                : null;
        if (device == null) {
            // No active device bound yet — use a placeholder that
            // satisfies the non-null contract; ASIO's implementation
            // enumerates the currently-open device regardless of the id.
            device = DeviceId.defaultFor(backend.name());
        }
        try {
            return isInput
                    ? backend.inputChannels(device)
                    : backend.outputChannels(device);
        } catch (RuntimeException e) {
            // Never let a transient backend / FFM glitch crash the UI —
            // fall back to the empty list which keeps the routing
            // dropdown on its legacy labels.
            LOG.log(Level.FINE, "Live channel-info lookup failed", e);
            return List.of();
        }
    }

    /**
     * Reconciles each track's saved {@code inputRoutingDisplayName} (and
     * each mixer channel's saved {@code outputRoutingDisplayName})
     * against the live driver-reported names using the existing
     * {@link ChannelNameSnapshotReconciler}, and surfaces at most one
     * warning notification per project load (story 215).
     *
     * <p>The reconciler also rewrites snapshots to the live names as a
     * side-effect, so the next save carries the up-to-date names and a
     * subsequent load will not warn again.</p>
     */
    private void notifyChannelNameMismatchOnce() {
        if (project == null || notificationBar == null) {
            return;
        }
        List<AudioChannelInfo> liveInputs = liveChannelInfo(true);
        List<AudioChannelInfo> liveOutputs = liveChannelInfo(false);
        if (liveInputs.isEmpty() && liveOutputs.isEmpty()) {
            return;
        }
        ChannelNameSnapshotReconciler.reconcile(project, liveInputs, liveOutputs)
                .warning()
                .ifPresent(msg -> notificationBar.show(NotificationLevel.WARNING, msg));
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

    /**
     * Story 100 — Track Templates and Channel-Strip Presets.
     *
     * <p>Constructs the application-wide {@link TrackTemplateController}
     * that orchestrates the Save / Apply / Add-from / Manage workflows.
     * The controller is constructor-injected with a {@link TrackTemplateController.Host}
     * adapter that snapshots the current project, undo manager, primary
     * stage, and notification bar so the templates and presets feature
     * stays decoupled from this top-level controller.</p>
     */
    private void createTrackTemplateController() {
        trackTemplateController = new TrackTemplateController(
                new TrackTemplateController.Host() {
                    @Override public DawProject project() { return project; }
                    @Override public UndoManager undoManager() { return undoManager; }
                    @Override public javafx.stage.Window window() {
                        return rootPane.getScene() != null
                                ? rootPane.getScene().getWindow() : null;
                    }
                    @Override public void showNotification(NotificationLevel level, String message) {
                        notificationBar.show(level, message);
                    }
                    @Override public void refreshMixer() {
                        viewNavigationController.getMixerView().refresh();
                    }
                });
    }

    /**
     * Story 035 — Track Freeze and Unfreeze for CPU Management.
     *
     * <p>Builds the {@link TrackFreezeController} that backs every
     * freeze/unfreeze entry-point in the application: the Tracks menu,
     * the Track List right-click menu, and the mixer channel context
     * menu. Refresh callbacks rebuild the affected track strip and
     * mixer channel so the ❄ snowflake glyph reflects the current
     * frozen state of every track.</p>
     */
    private void createTrackFreezeController() {
        trackFreezeController = new TrackFreezeController(
                project,
                undoManager,
                rootPane.getScene() != null ? rootPane.getScene().getWindow() : null,
                track -> {
                    // Single-track callback: refresh the affected strip
                    // and the corresponding mixer channel so the ❄
                    // glyph appears/disappears immediately.
                    if (trackStripController != null) {
                        trackStripController.refreshFreezeIndicator(track);
                    }
                    if (viewNavigationController != null) {
                        viewNavigationController.getMixerView().refresh();
                    }
                    projectDirty = true;
                },
                () -> {
                    // Batch callback: refresh every strip plus the mixer.
                    if (trackStripController != null) {
                        trackStripController.refreshAllFreezeIndicators();
                    }
                    if (viewNavigationController != null) {
                        viewNavigationController.getMixerView().refresh();
                    }
                    projectDirty = true;
                },
                this::status);
    }

    private void createHistoryPanelController() {
        historyPanelController = new HistoryPanelController(
                rootPane, historyButton,
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
                    @Override public void onPlayWithPreRoll() { transportController.onPlayWithPreRoll(); }
                    @Override public void onTogglePreRoll() { transportController.onTogglePreRoll(); }
                    @Override public void onTogglePostRoll() { transportController.onTogglePostRoll(); }
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
                    @Override public void onArchiveProject() { projectLifecycleController.onArchiveProject(); }
                    @Override public void onRestoreFromArchive() { projectLifecycleController.onRestoreFromArchive(); }
                    @Override public void onOpenRenderQueue() { MainController.this.onOpenRenderQueue(); }
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
                    @Override public void onToggleNotificationHistory() { toggleNotificationHistory(); }
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
                    @Override public void onNudgeLeft() { clipEditController.onNudgeLeft(); }
                    @Override public void onNudgeRight() { clipEditController.onNudgeRight(); }
                    @Override public void onNudgeLeftLarge() { clipEditController.onNudgeLeftLarge(); }
                    @Override public void onNudgeRightLarge() { clipEditController.onNudgeRightLarge(); }
                    @Override public void onNudgeLeftSample() { clipEditController.onNudgeLeftSample(); }
                    @Override public void onNudgeRightSample() { clipEditController.onNudgeRightSample(); }
                    @Override public void onToggleFoldFocusedTrack() { MainController.this.onToggleFoldFocusedTrack(); }
                    @Override public void onToggleFoldSelectedTracks() { MainController.this.onToggleFoldSelectedTracks(); }
                    @Override public void onFoldAllAutomation() { MainController.this.onFoldAllAutomation(); }
                    @Override public void onFreezeFocusedTrack() { MainController.this.onFreezeFocusedTrack(); }
                    @Override public void onUnfreezeFocusedTrack() { MainController.this.onUnfreezeFocusedTrack(); }
                    @Override public void onFreezeSelectedTracks() { MainController.this.onFreezeSelectedTracks(); }
                    @Override public void onUnfreezeSelectedTracks() { MainController.this.onUnfreezeSelectedTracks(); }
                    @Override public void onTimeStretchClip() { MainController.this.onTimeStretchClip(); }
                    @Override public void onPitchShiftClip() { MainController.this.onPitchShiftClip(); }
                    @Override public void onOpenImmersiveAb() { MainController.this.onOpenImmersiveAb(); }
                    @Override public void onImmersiveAbToggle() { MainController.this.onImmersiveAbToggle(); }
                    @Override public void onToggleCommandPalette() {
                        if (commandPaletteView != null) commandPaletteView.toggle();
                    }
                    @Override public void onSwitchToWorkspaceSlot(int slotIndex) {
                        if (workspaceManager != null) workspaceManager.switchToSlot(slotIndex);
                    }
                    @Override public void onSaveWorkspaceAs() {
                        if (workspaceManager == null) return;
                        String name = promptWorkspaceName();
                        if (name != null && !name.isBlank()) {
                            workspaceManager.saveCurrentAs(name.trim());
                        }
                    }
                    // ── Dockable panels (F3 / F4 / F5) ────────────────────
                    // Preserve legacy visible behavior unless the docking
                    // path is the only available way to service the toggle.
                    @Override public void onToggleDockMixer() {
                        if (viewNavigationController != null) {
                            viewNavigationController.switchView(DawView.MIXER);
                        } else if (dockManager != null
                                && dockManager.layout().contains(DefaultWorkspaces.PANEL_MIXER)) {
                            dockManager.toggleVisible(DefaultWorkspaces.PANEL_MIXER);
                        }
                    }
                    @Override public void onToggleDockBrowser() {
                        if (browserPanelController != null) {
                            browserPanelController.toggleBrowserPanel();
                        } else if (dockManager != null
                                && dockManager.layout().contains(DefaultWorkspaces.PANEL_BROWSER)) {
                            dockManager.toggleVisible(DefaultWorkspaces.PANEL_BROWSER);
                        }
                    }
                    @Override public void onToggleDockArrangement() {
                        if (viewNavigationController != null) {
                            viewNavigationController.switchView(DawView.ARRANGEMENT);
                        } else if (dockManager != null
                                && dockManager.layout().contains(DefaultWorkspaces.PANEL_ARRANGEMENT)) {
                            dockManager.toggleVisible(DefaultWorkspaces.PANEL_ARRANGEMENT);
                        }
                    }
                    @Override public void onMixerToggleAB() {
                        if (viewNavigationController != null
                                && viewNavigationController.getMixerView() != null) {
                            viewNavigationController.getMixerView().toggleAB();
                        }
                    }
                    @Override public void onCreateCheckpoint() {
                        if (snapshotsController != null) snapshotsController.createCheckpoint();
                    }
                    @Override public void onOpenSnapshots() {
                        if (snapshotsController != null) snapshotsController.openBrowser();
                    }
                });
        createCommandPaletteView();
    }

    /**
     * Builds the {@link CommandPaletteView} using the same action-handler
     * map as {@link KeyboardShortcutController}. The entry supplier
     * re-evaluates the {@link KeyBindingManager} display text on each
     * invocation, so rebound shortcuts are reflected when the palette opens.
     */
    private void createCommandPaletteView() {
        if (keyboardShortcutController == null) {
            return;
        }
        java.util.Map<DawAction, Runnable> handlers = keyboardShortcutController.buildActionHandlers();
        CommandPaletteRecentsStore recentsStore = new CommandPaletteRecentsStore();
        commandPaletteView = new CommandPaletteView(
                () -> {
                    java.util.List<CommandPaletteEntry> entries = new java.util.ArrayList<>();
                    for (DawAction action : DawAction.values()) {
                        if (action == DawAction.OPEN_COMMAND_PALETTE) {
                            // Don't list the palette itself.
                            continue;
                        }
                        Runnable h = handlers.get(action);
                        if (h == null) continue;
                        String shortcut = keyBindingManager == null ? ""
                                : keyBindingManager.getDisplayText(action);
                        entries.add(CommandPaletteEntry.of(
                                action.name(),
                                action.displayName(),
                                shortcut,
                                action.category().displayName(),
                                null,
                                h));
                    }
                    return entries;
                },
                recentsStore);
    }

    private void initializeNotificationBar() {
        notificationBar = new NotificationBar();
        notificationBar.setHistoryService(notificationHistoryService);
        notificationBarContainer.getChildren().add(notificationBar);
        HBox.setHgrow(notificationBar, Priority.ALWAYS);
        // Story 273 — the transient toast and the inspector Notifications
        // section share the one notification log so there is exactly one
        // notification stream feeding both surfaces (§7.8).
        if (inspectorDrawer != null) {
            inspectorDrawer.setNotificationHistoryService(notificationHistoryService);
        }
    }

    /**
     * Reveals (or, when already shown, collapses) the inspector
     * Notifications section — story 273 replaces the former standalone
     * notification-history panel toggle.
     */
    private void toggleNotificationHistory() {
        if (inspectorDrawer == null) {
            return;
        }
        if (isNotificationHistoryVisible()) {
            revealNotifications(false);
            status("Notifications collapsed", DawIcon.BELL_RING);
        } else {
            revealNotifications(true);
            status("Notifications opened", DawIcon.BELL_RING);
        }
    }

    /**
     * @return whether the inspector Notifications section is currently
     *         revealed (drawer expanded and the section expanded).
     */
    private boolean isNotificationHistoryVisible() {
        return inspectorDrawer != null
                && inspectorDrawer.isExpanded()
                && inspectorDrawer.getNotificationsSection().isExpanded();
    }

    /** Expands or collapses the inspector Notifications section. */
    private void setNotificationHistoryVisible(boolean visible) {
        revealNotifications(visible);
    }

    /**
     * Single source of truth for revealing/hiding the inspector
     * Notifications section, shared by the toggle command and
     * workspace-restore so the two cannot drift. Expanding also expands
     * the drawer; collapsing leaves the drawer itself untouched.
     */
    private void revealNotifications(boolean visible) {
        if (inspectorDrawer == null) {
            return;
        }
        if (visible) {
            inspectorDrawer.setExpanded(true);
            inspectorDrawer.getNotificationsSection().setExpanded(true);
        } else {
            inspectorDrawer.getNotificationsSection().setExpanded(false);
        }
    }

    /**
     * Story 129 (UI) — Composes a {@link com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer
     * TrackCpuBudgetEnforcer} for the current project, registers every
     * mixer channel with its persisted budget, attaches a
     * {@link TrackBudgetUiBinding} that surfaces toast notifications and
     * "⚠" badges, and installs the enforcer on the live audio engine.
     *
     * <p>Re-entrant: when a project is reloaded, the previous binding
     * and enforcer are closed before fresh ones are constructed.</p>
     */
    private void installTrackCpuBudgetEnforcer() {
        try {
            // Tear down any pre-existing enforcer / binding so a project
            // reload does not leak subscribers.
            if (trackBudgetUiBinding != null) {
                trackBudgetUiBinding.close();
                trackBudgetUiBinding = null;
            }
            if (cpuBudgetEnforcer != null) {
                if (audioEngine != null) {
                    audioEngine.setCpuBudgetEnforcer(null);
                }
                cpuBudgetEnforcer.close();
                cpuBudgetEnforcer = null;
            }

            double sampleRate = audioEngine.getFormat().sampleRate();
            int bufferSize = audioEngine.getFormat().bufferSize();
            double masterFraction = settingsModel != null
                    ? settingsModel.getMasterCpuBudgetFraction()
                    : SettingsModel.DEFAULT_MASTER_CPU_BUDGET_FRACTION;
            // Clamp defensively to the enforcer's legal interval —
            // zero / NaN / >1 would throw and crash startup.
            if (Double.isNaN(masterFraction) || masterFraction <= 0.0 || masterFraction > 1.0) {
                masterFraction = 1.0;
            }
            cpuBudgetEnforcer = new com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer(
                    sampleRate, bufferSize, masterFraction, System::nanoTime);

            // Register every existing mixer channel with its persisted
            // budget. Channels created later (new tracks) get registered
            // through the same call site after creation.
            registerAllChannelsWithEnforcer();

            // Subscribe a UI binding that throttles toast notifications
            // (one per track per 30 s) and refreshes the mixer view's
            // degraded badge set on the JavaFX thread.
            NotificationManager toastSink = message -> {
                if (notificationBar != null) {
                    javafx.application.Platform.runLater(() -> notificationBar.show(
                            NotificationLevel.WARNING, message));
                }
            };
            trackBudgetUiBinding = new TrackBudgetUiBinding(
                    toastSink,
                    this::trackNameFor,
                    _ -> {
                        MixerView mv = viewNavigationController != null
                                ? viewNavigationController.getMixerView()
                                : null;
                        if (mv != null) {
                            mv.refresh();
                        }
                    },
                    javafx.application.Platform::runLater,
                    System::nanoTime);
            cpuBudgetEnforcer.performanceEvents().subscribe(trackBudgetUiBinding);

            // Wire the predicate and the "CPU Budget…" menu handler
            // into the mixer view so degraded strips render the badge
            // and the user can edit per-channel budgets.
            MixerView mv = viewNavigationController != null
                    ? viewNavigationController.getMixerView()
                    : null;
            if (mv != null) {
                final TrackBudgetUiBinding binding = trackBudgetUiBinding;
                mv.setDegradedTrackPredicate(binding::isDegraded);
                mv.setOnConfigureCpuBudget(this::openChannelCpuBudgetDialog);
            }

            audioEngine.setCpuBudgetEnforcer(cpuBudgetEnforcer);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to install per-track CPU budget enforcer", e);
        }
    }

    /** Re-registers every mixer channel with the live enforcer. */
    private void registerAllChannelsWithEnforcer() {
        if (cpuBudgetEnforcer == null || project == null) {
            return;
        }
        // Use the track id as the enforcer's track id so the binding's
        // notification text and the strip badge line up with the mixer.
        for (Track track : project.getTracks()) {
            com.benesquivelmusic.daw.core.mixer.MixerChannel ch =
                    project.getMixerChannelForTrack(track);
            if (ch != null) {
                cpuBudgetEnforcer.registerTrack(track.getId(), ch.getCpuBudget());
            }
        }
    }

    /** Returns the human display name for the given track id, or the id itself if unknown. */
    private String trackNameFor(String trackId) {
        if (project == null || trackId == null) {
            return trackId;
        }
        for (Track track : project.getTracks()) {
            if (trackId.equals(track.getId())) {
                return track.getName();
            }
        }
        return trackId;
    }

    /** Opens the per-channel CPU-budget dialog. Re-registers the channel on Apply. */
    private void openChannelCpuBudgetDialog(com.benesquivelmusic.daw.core.mixer.MixerChannel channel) {
        if (channel == null) {
            return;
        }
        ChannelCpuBudgetDialog dialog = new ChannelCpuBudgetDialog(channel, () -> {
            if (cpuBudgetEnforcer == null || project == null) {
                return;
            }
            // Find the matching track id so the enforcer key stays
            // aligned with the mixer/binding.
            for (Track track : project.getTracks()) {
                if (project.getMixerChannelForTrack(track) == channel) {
                    cpuBudgetEnforcer.registerTrack(track.getId(), channel.getCpuBudget());
                    return;
                }
            }
        });
        dialog.showAndWait();
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
        // Per-row audition wiring (story 275) — single-channel preview
        // engine from daw.core (com.benesquivelmusic.daw.core.browser).
        browserPanel.setSampleAuditioner(new SamplePreviewAuditioner());
        browserPanelController = new BrowserPanelController(browserPanel, browserButton, rootPane);
        browserPanelController.setOnVisibilityChanged(() -> {
            toolbarStateStore.saveBrowserVisible(browserPanelController.isPanelVisible());
            if (browserPanelController.isPanelVisible() && historyPanelController != null) {
                if (historyPanelController.isHistoryPanelVisible()) {
                    historyPanelController.setHistoryPanelVisible(false);
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
                    @Override public void onTimeStretchClip() { MainController.this.onTimeStretchClip(); }
                    @Override public void onPitchShiftClip() { MainController.this.onPitchShiftClip(); }
                },
                this::seekToPosition);
        arrangementCanvas = result.canvas();
        timelineRuler = result.ruler();
        clipInteractionController = result.clipInteraction();
        if (animationController != null) {
            // Story 197 — install the shared advisor so clip-drag gestures
            // emit ghost previews, drop-zone highlights, snap indicators
            // and modifier-cursor changes via the unified visual layer.
            clipInteractionController.setDragVisualAdvisor(
                    animationController.dragVisualAdvisor());
        }
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
                    @Override public void onOpenRenderQueue() { MainController.this.onOpenRenderQueue(); }
                    @Override public void onImportAudioFile() { audioImportController.onImportAudioFile(); }
                    @Override public void onOpenSnapshots() {
                        if (snapshotsController != null) snapshotsController.openBrowser();
                    }
                    @Override public void onCreateCheckpoint() {
                        if (snapshotsController != null) snapshotsController.createCheckpoint();
                    }
                    @Override public void onArchiveProject() { projectLifecycleController.onArchiveProject(); }
                    @Override public void onRestoreFromArchive() { projectLifecycleController.onRestoreFromArchive(); }
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
                    @Override public void onOpenBackupSettings() { MainController.this.onOpenBackupSettings(); }
                    @Override public void onActivateBuiltInPlugin(Class<? extends BuiltInDawPlugin> pluginClass) {
                        pluginViewController.onActivateBuiltInPlugin(pluginClass);
                    }
                    @Override public void onSwitchView(DawView view) { viewNavigationController.switchView(view); }
                    @Override public void onToggleBrowser() {
                        if (historyPanelController.isHistoryPanelVisible()) { historyPanelController.toggleHistoryPanel(); }
                        browserPanelController.toggleBrowserPanel();
                    }
                    @Override public void onToggleHistory() { historyPanelController.toggleHistoryPanel(); }
                    @Override public void onToggleNotificationHistory() { toggleNotificationHistory(); }
                    @Override public void onToggleVisualizations() { vizPanelController.toggleRowVisibility(); }
                    @Override public void onToggleFoldFocusedTrack() { MainController.this.onToggleFoldFocusedTrack(); }
                    @Override public void onToggleFoldSelectedTracks() { MainController.this.onToggleFoldSelectedTracks(); }
                    @Override public void onFoldAllAutomation() { MainController.this.onFoldAllAutomation(); }
                    @Override public void onAddTrackFromTemplate() {
                        if (trackTemplateController != null) {
                            trackTemplateController.addTrackFromTemplate();
                        }
                    }
                    @Override public void onManageTemplates() {
                        if (trackTemplateController != null) {
                            trackTemplateController.openManager();
                        }
                    }
                    @Override public void onFreezeFocusedTrack() { MainController.this.onFreezeFocusedTrack(); }
                    @Override public void onUnfreezeFocusedTrack() { MainController.this.onUnfreezeFocusedTrack(); }
                    @Override public void onFreezeSelectedTracks() { MainController.this.onFreezeSelectedTracks(); }
                    @Override public void onUnfreezeSelectedTracks() { MainController.this.onUnfreezeSelectedTracks(); }
                    @Override public void onTimeStretchClip() { MainController.this.onTimeStretchClip(); }
                    @Override public void onPitchShiftClip() { MainController.this.onPitchShiftClip(); }
                    @Override public void onOpenImmersiveAb() { MainController.this.onOpenImmersiveAb(); }
                    @Override public void onHelp() { MainController.this.onHelp(); }
                },
                keyBindingManager);
        javafx.scene.control.MenuBar bar = menuBarController.build();
        // Wire the per-user Workspaces menu (Save Current as… / Switch to…).
        // The WorkspaceManager seeds the six default workspaces on first run
        // (Tracking, Editing, Mixing, Mastering, Spatial, Minimal).
        installWorkspacesMenu(bar);
        Node topNode = rootPane.getTop();
        if (topNode instanceof VBox topVBox) { topVBox.getChildren().addFirst(bar); }
    }

    private void installWorkspacesMenu(javafx.scene.control.MenuBar bar) {
        // Do not create/register a DockManager until the JavaFX docking
        // adapter is available to reconcile layout changes back into the
        // existing panel controllers. Creating it early would make workspace
        // apply and keyboard actions mutate dock state without updating the
        // visible UI, effectively bypassing legacy behavior.
        workspaceManager = new WorkspaceManager(buildWorkspaceHost());
        WorkspacesMenu menuBuilder = new WorkspacesMenu(
                workspaceManager,
                keyBindingManager,
                this::promptWorkspaceName,
                this::exportWorkspaceWithChooser,
                this::importWorkspaceWithChooser);
        javafx.scene.control.Menu workspacesMenu = menuBuilder.build();
        // Insert before the last (Help) menu so Help stays right-most.
        var menus = bar.getMenus();
        int insertIndex = Math.max(0, menus.size() - 1);
        menus.add(insertIndex, workspacesMenu);
    }

    private WorkspaceManager.Host buildWorkspaceHost() {
        return new WorkspaceManager.Host() {
            @Override public java.util.List<String> knownPanelIds() {
                return DefaultWorkspaces.panelIds();
            }
            @Override public boolean isPanelVisible(String panelId) {
                return switch (panelId) {
                    case DefaultWorkspaces.PANEL_BROWSER ->
                            browserPanelController != null && browserPanelController.isPanelVisible();
                    case DefaultWorkspaces.PANEL_HISTORY ->
                            historyPanelController != null && historyPanelController.isHistoryPanelVisible();
                    case DefaultWorkspaces.PANEL_NOTIFICATIONS ->
                            isNotificationHistoryVisible();
                    case DefaultWorkspaces.PANEL_VISUALIZATIONS ->
                            vizPanelController != null
                                    && vizPanelController.isRowVisible();
                    case DefaultWorkspaces.PANEL_ARRANGEMENT ->
                            viewNavigationController != null
                                    && viewNavigationController.getActiveView() == DawView.ARRANGEMENT;
                    case DefaultWorkspaces.PANEL_MIXER ->
                            viewNavigationController != null
                                    && viewNavigationController.getActiveView() == DawView.MIXER;
                    case DefaultWorkspaces.PANEL_EDITOR ->
                            viewNavigationController != null
                                    && viewNavigationController.getActiveView() == DawView.EDITOR;
                    case DefaultWorkspaces.PANEL_MASTERING ->
                            viewNavigationController != null
                                    && viewNavigationController.getActiveView() == DawView.MASTERING;
                    default -> false;
                };
            }
            @Override public void setPanelVisible(String panelId, boolean visible) {
                switch (panelId) {
                    case DefaultWorkspaces.PANEL_BROWSER -> {
                        if (browserPanelController != null
                                && browserPanelController.isPanelVisible() != visible) {
                            browserPanelController.toggleBrowserPanel();
                        }
                    }
                    case DefaultWorkspaces.PANEL_HISTORY -> {
                        if (historyPanelController != null
                                && historyPanelController.isHistoryPanelVisible() != visible) {
                            historyPanelController.toggleHistoryPanel();
                        }
                    }
                    case DefaultWorkspaces.PANEL_NOTIFICATIONS -> {
                        if (isNotificationHistoryVisible() != visible) {
                            setNotificationHistoryVisible(visible);
                        }
                    }
                    case DefaultWorkspaces.PANEL_VISUALIZATIONS -> {
                        if (vizPanelController != null
                                && vizPanelController.isRowVisible() != visible) {
                            vizPanelController.toggleRowVisibility();
                        }
                    }
                    case DefaultWorkspaces.PANEL_ARRANGEMENT -> {
                        if (visible && viewNavigationController != null) {
                            viewNavigationController.switchView(DawView.ARRANGEMENT);
                        }
                    }
                    case DefaultWorkspaces.PANEL_MIXER -> {
                        if (visible && viewNavigationController != null) {
                            viewNavigationController.switchView(DawView.MIXER);
                        }
                    }
                    case DefaultWorkspaces.PANEL_EDITOR -> {
                        if (visible && viewNavigationController != null) {
                            viewNavigationController.switchView(DawView.EDITOR);
                        }
                    }
                    case DefaultWorkspaces.PANEL_MASTERING -> {
                        if (visible && viewNavigationController != null) {
                            viewNavigationController.switchView(DawView.MASTERING);
                        }
                    }
                    default -> { /* unknown panel id — forward compatible */ }
                }
            }
            // ── Dock layout integration ─────────────────────────────────
            @Override public String captureDockLayoutJson() {
                return dockManager == null ? "" : dockManager.captureJson();
            }
            @Override public void applyDockLayoutJson(String dockLayoutJson) {
                if (dockManager != null && dockLayoutJson != null && !dockLayoutJson.isEmpty()) {
                    dockManager.applyJson(dockLayoutJson);
                }
            }
        };
    }

    /**
     * Registers the well-known top-level panels (arrangement, mixer,
     * editor, mastering, browser) with the {@link
     * com.benesquivelmusic.daw.app.ui.dock.DockManager} so workspace
     * switches can save/restore their dock placement. Each registration
     * is a lightweight {@link com.benesquivelmusic.daw.app.ui.dock.Dockable}
     * — a record carrying the stable id, display name, icon, and
     * preferred zone.
     */
    private void registerDockablePanels(com.benesquivelmusic.daw.app.ui.dock.DockManager dm) {
        record Panel(String dockId, String displayName, String iconName,
                     com.benesquivelmusic.daw.app.ui.dock.DockZone preferredZone)
                implements com.benesquivelmusic.daw.app.ui.dock.Dockable { }
        dm.register(new Panel(DefaultWorkspaces.PANEL_ARRANGEMENT, "Arrangement",
                "TIMELINE", com.benesquivelmusic.daw.app.ui.dock.DockZone.CENTER));
        dm.register(new Panel(DefaultWorkspaces.PANEL_MIXER, "Mixer",
                "MIXER", com.benesquivelmusic.daw.app.ui.dock.DockZone.BOTTOM));
        dm.register(new Panel(DefaultWorkspaces.PANEL_EDITOR, "Editor",
                "EDITOR", com.benesquivelmusic.daw.app.ui.dock.DockZone.CENTER));
        dm.register(new Panel(DefaultWorkspaces.PANEL_MASTERING, "Mastering",
                "MASTERING", com.benesquivelmusic.daw.app.ui.dock.DockZone.CENTER));
        dm.register(new Panel(DefaultWorkspaces.PANEL_BROWSER, "Browser",
                "BROWSER", com.benesquivelmusic.daw.app.ui.dock.DockZone.LEFT));
    }

    private String promptWorkspaceName() {
        var dialog = new javafx.scene.control.TextInputDialog("My Workspace");
        dialog.setTitle("Save Workspace");
        dialog.setHeaderText("Save current panel arrangement");
        dialog.setContentText("Workspace name:");
        return dialog.showAndWait().orElse(null);
    }

    private void exportWorkspaceWithChooser(String workspaceName) {
        var chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Export Workspace");
        chooser.setInitialFileName(workspaceName + ".json");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File file = chooser.showSaveDialog(rootPane.getScene() != null
                ? rootPane.getScene().getWindow() : null);
        if (file == null) return;
        try {
            workspaceManager.exportTo(workspaceName, file.toPath());
        } catch (java.io.IOException e) {
            LOG.log(Level.WARNING, "Failed to export workspace " + workspaceName, e);
        }
    }

    private void importWorkspaceWithChooser() {
        var chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Import Workspace");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File file = chooser.showOpenDialog(rootPane.getScene() != null
                ? rootPane.getScene().getWindow() : null);
        if (file == null) return;
        try {
            workspaceManager.importFrom(file.toPath());
        } catch (java.io.IOException e) {
            LOG.log(Level.WARNING, "Failed to import workspace from " + file, e);
        }
    }

    @FXML private void onPlay() { transportController.onPlay(); }
    @FXML private void onStop() { transportController.onStop(); }
    @FXML private void onRecord() { transportController.onRecord(); }
    @FXML private void onSkipBack() { transportController.onSkipBack(); }
    @FXML private void onSkipForward() { transportController.onSkipForward(); }
    @FXML private void onToggleLoop() { transportController.onToggleLoop(); syncLoopRegionToCanvas(); }
    @FXML private void onToggleMetronome() { metronomeController.onToggleMetronome(); }
    @FXML private void onAddAudioTrack() { trackCreationController.onAddAudioTrack(); }
    @FXML private void onAddMidiTrack() { trackCreationController.onAddMidiTrack(); }

    // ── Lane folding (Issue 568) ────────────────────────────────────────────
    private void onToggleFoldFocusedTrack() {
        if (arrangementCanvas == null) {
            return;
        }
        Track focused = selectionModel.getFocusedTrack();
        if (focused == null) {
            status("No focused track to fold", DawIcon.INFO_CIRCLE);
            return;
        }
        arrangementCanvas.toggleAllFoldsForTrack(focused);
        status((focused.getFoldState().isFullyFolded() ? "Folded: " : "Unfolded: ")
                + focused.getName(), DawIcon.AUTOMATION);
        projectDirty = true;
    }

    private void onToggleFoldSelectedTracks() {
        if (arrangementCanvas == null) {
            return;
        }
        var tracks = selectionModel.getTracksInClipSelection();
        if (tracks.isEmpty()) {
            status("No selected tracks to fold", DawIcon.INFO_CIRCLE);
            return;
        }
        // Route through the canvas API so lane-Y caches are invalidated
        // alongside the fold-state mutations — keeps multi-track folds
        // consistent with single-track toggles.
        boolean targetFolded = arrangementCanvas.toggleAllFoldsForTracks(tracks);
        status((targetFolded ? "Folded " : "Unfolded ")
                + tracks.size() + " selected track(s)", DawIcon.AUTOMATION);
        projectDirty = true;
    }

    private void onFoldAllAutomation() {
        if (arrangementCanvas == null) {
            return;
        }
        arrangementCanvas.toggleFoldAllAutomation();
        status("Toggled fold for all automation lanes", DawIcon.AUTOMATION);
        projectDirty = true;
    }

    // ── Track Freeze and Unfreeze (Story 035) ──────────────────────────────
    // Per-track and batch entry-points wired to the Tracks menu, the
    // Track List right-click menu, and the mixer channel context menu.
    // Each routes through TrackFreezeController which performs the
    // offline render on a virtual thread, surfaces a modeless
    // TaskProgressIndicator, and registers a single undo step.

    private void onFreezeFocusedTrack() {
        if (trackFreezeController == null) return;
        trackFreezeController.freezeTrack(selectionModel.getFocusedTrack());
    }

    private void onUnfreezeFocusedTrack() {
        if (trackFreezeController == null) return;
        trackFreezeController.unfreezeTrack(selectionModel.getFocusedTrack());
    }

    private void onFreezeSelectedTracks() {
        if (trackFreezeController == null) return;
        var tracks = selectionModel.getTracksInClipSelection();
        if (tracks.isEmpty()) {
            // Fall back to the focused track if there is no multi-track
            // selection so the menu entry is never silently a no-op.
            Track focused = selectionModel.getFocusedTrack();
            if (focused != null) {
                trackFreezeController.freezeTrack(focused);
                return;
            }
        }
        trackFreezeController.freezeTracks(tracks);
    }

    private void onUnfreezeSelectedTracks() {
        if (trackFreezeController == null) return;
        var tracks = selectionModel.getTracksInClipSelection();
        if (tracks.isEmpty()) {
            Track focused = selectionModel.getFocusedTrack();
            if (focused != null) {
                trackFreezeController.unfreezeTrack(focused);
                return;
            }
        }
        trackFreezeController.unfreezeTracks(tracks);
    }

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

    /**
     * Story 191 — opens {@link BackupSettingsDialog} bound to the persisted
     * {@link com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy}.
     * On Apply the new policy is saved through
     * {@link com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionPolicyStore}
     * and applied immediately to {@code ~/.daw/autosaves/}.
     */
    void onOpenBackupSettings() {
        status("Opening backup settings...", DawIcon.FOLDER);
        if (backupRetentionController == null) {
            // Defensive: should always be created during initialize().
            backupRetentionController = new BackupRetentionController();
        }
        var owner = rootPane != null && rootPane.getScene() != null
                ? rootPane.getScene().getWindow() : null;
        Path projectDir = null;
        if (projectManager != null && projectManager.getCurrentProject() != null) {
            projectDir = projectManager.getCurrentProject().projectPath();
        }
        backupRetentionController.openDialog(owner, projectDir);
        status("Backup settings closed", DawIcon.STATUS);
    }

    // ── Story 042 — Time-Stretch / Pitch-Shift dispatch ─────────────────────

    /**
     * Surfaces {@link TimeStretchClipDialog} for the current selection and
     * delegates the actual application of the chosen settings to
     * {@link ClipEditController#onTimeStretchSelected}. The compound undo
     * step is built by the controller. Story 042.
     */
    void onTimeStretchClip() {
        var owner = rootPane != null && rootPane.getScene() != null
                ? rootPane.getScene().getWindow() : null;
        clipEditController.onTimeStretchSelected(sourceSeconds ->
                TimeStretchClipDialog.showAndWait(owner,
                        TimeStretchClipDialog.Result.defaults(), sourceSeconds));
    }

    /**
     * Surfaces {@link PitchShiftClipDialog} for the current selection and
     * delegates the actual application of the chosen settings to
     * {@link ClipEditController#onPitchShiftSelected}. Story 042.
     */
    void onPitchShiftClip() {
        var owner = rootPane != null && rootPane.getScene() != null
                ? rootPane.getScene().getWindow() : null;
        clipEditController.onPitchShiftSelected(() ->
                PitchShiftClipDialog.showAndWait(owner,
                        PitchShiftClipDialog.Result.defaults()));
    }

    // ── Story 175 — Immersive A/B Comparison ────────────────────────────────

    /**
     * Opens the {@link com.benesquivelmusic.daw.app.ui.spatial.AtmosAbView}
     * in a separate utility window. The view is constructed on demand and
     * disposed when the window is closed. Using a separate {@link Stage}
     * avoids conflicts with the main view-navigation center pane.
     */
    void onOpenImmersiveAb() {
        if (atmosAbStage != null) {
            atmosAbStage.toFront();
            atmosAbStage.requestFocus();
            return;
        }
        atmosAbView = new com.benesquivelmusic.daw.app.ui.spatial.AtmosAbView(
                project.getReferenceTrackManager());
        atmosAbStage = new Stage(javafx.stage.StageStyle.UTILITY);
        atmosAbStage.setTitle("Immersive A/B — QC");
        javafx.scene.Scene scene = new javafx.scene.Scene(atmosAbView);
        DarkThemeHelper.applyTo(scene);
        atmosAbStage.setScene(scene);
        atmosAbStage.setMinWidth(600);
        atmosAbStage.setMinHeight(320);
        atmosAbStage.setOnHidden(_ -> {
            atmosAbView = null;
            atmosAbStage = null;
        });
        atmosAbStage.show();
        atmosAbStage.toFront();
    }

    // ── Story 186 — Offline Render Queue ────────────────────────────────

    /**
     * Lazily compose the singleton {@link RenderQueue}. The queue is
     * scoped to the application lifetime — it survives project changes
     * because batch renders are a tool, not project state. On startup we
     * load any persisted snapshots and prompt the user if a non-empty
     * queue is found ("Resume / Retry / Clear").
     */
    private RenderQueue ensureRenderQueue() {
        if (renderQueue != null) return renderQueue;
        // Default worker count = 1 to prevent disk contention. A future
        // Settings → Performance → "Render queue parallelism" knob can
        // re-create the queue at a different worker count.
        renderQueue = new RenderQueue(
                new com.benesquivelmusic.daw.app.ui.export.DefaultRenderJobRunner(), 1);
        // Per-job completion notification through NotificationBar
        // (the project's notification surface). Failures must never
        // break the queue, so we route every outcome through Platform.
        renderQueue.setCompletionNotifier(outcome -> {
            String msg = switch (outcome.phase()) {
                case COMPLETED -> "Render completed: " + outcome.job().displayName();
                case FAILED    -> {
                    String detail = "";
                    if (outcome.error() != null) {
                        String emsg = outcome.error().getMessage();
                        detail = " (" + (emsg != null && !emsg.isBlank()
                                ? emsg : outcome.error().getClass().getSimpleName()) + ")";
                    }
                    yield "Render failed: " + outcome.job().displayName() + detail;
                }
                case CANCELLED -> "Render cancelled: " + outcome.job().displayName();
                default        -> "Render: " + outcome.job().displayName();
            };
            NotificationLevel level = switch (outcome.phase()) {
                case COMPLETED -> NotificationLevel.SUCCESS;
                case FAILED    -> NotificationLevel.ERROR;
                case CANCELLED -> NotificationLevel.WARNING;
                default        -> NotificationLevel.INFO;
            };
            javafx.application.Platform.runLater(() -> {
                if (notificationBar != null) {
                    notificationBar.show(level, msg);
                }
                // Optional OS-level audio cue. Runs off-thread because
                // AWT Toolkit initialization can be heavy on first call.
                Thread.ofVirtual().name("render-queue-beep").start(() -> {
                    try {
                        java.awt.Toolkit.getDefaultToolkit().beep();
                    } catch (RuntimeException ignored) {
                        // Best-effort — headless or audio-disabled environments.
                    }
                });
            });
        });
        // Prompt Resume / Retry / Clear if a non-empty persisted queue
        // exists from a prior session. The actual render configs are not
        // persisted today, so all three options are an acknowledgement;
        // "Clear" deletes the persisted snapshot file.
        try {
            var snapshots = renderQueue.loadPersisted();
            boolean hasUnfinished = snapshots.stream()
                    .anyMatch(s -> !s.phase().isTerminal()
                            || s.phase() == com.benesquivelmusic.daw.sdk.export.JobProgress.Phase.FAILED);
            if (hasUnfinished) {
                promptResumeRetryClear(snapshots.size());
            }
        } catch (java.io.IOException e) {
            LOG.log(Level.WARNING, "Failed to load persisted render queue", e);
        }
        return renderQueue;
    }

    private void promptResumeRetryClear(int jobCount) {
        javafx.scene.control.ButtonType resume = new javafx.scene.control.ButtonType("Resume");
        javafx.scene.control.ButtonType retry  = new javafx.scene.control.ButtonType("Retry");
        javafx.scene.control.ButtonType clear  = new javafx.scene.control.ButtonType("Clear",
                javafx.scene.control.ButtonBar.ButtonData.OTHER);
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        if (rootPane.getScene() != null && rootPane.getScene().getWindow() != null) {
            alert.initOwner(rootPane.getScene().getWindow());
        }
        alert.setTitle("Resume Render Queue");
        alert.setHeaderText("Found " + jobCount + " job(s) from previous session");
        alert.setContentText(
                "Resume — keep the previous queue snapshot.\n"
              + "Retry — keep the snapshot (retry not yet implemented).\n"
              + "Clear — discard the persisted queue file.");
        alert.getButtonTypes().setAll(resume, retry, clear);
        DarkThemeHelper.applyTo(alert);
        alert.showAndWait().ifPresent(choice -> {
            // All three choices clear the persisted snapshot to avoid a
            // recurring prompt on every restart. Resume / Retry are
            // otherwise no-ops in this MVP — full restart of failed jobs
            // requires re-creating the original RenderJob (which carries
            // the export config); that wiring will land alongside the
            // per-dialog "Add to queue" buttons.
            if (renderQueue != null) {
                try {
                    renderQueue.clearPersisted();
                } catch (java.io.IOException e) {
                    LOG.log(Level.WARNING, "Failed to clear persisted render queue", e);
                }
            }
        });
    }

    /**
     * Open (or focus) the {@link com.benesquivelmusic.daw.app.ui.export.RenderQueueView}
     * in a UTILITY-style floating window. The view subscribes to the
     * singleton {@link RenderQueue}'s progress publisher so per-job
     * progress bars update live.
     */
    void onOpenRenderQueue() {
        if (renderQueueStage != null) {
            renderQueueStage.toFront();
            renderQueueStage.requestFocus();
            return;
        }
        RenderQueue queue = ensureRenderQueue();
        renderQueueView = new com.benesquivelmusic.daw.app.ui.export.RenderQueueView(queue);
        renderQueueStage = new Stage(javafx.stage.StageStyle.UTILITY);
        renderQueueStage.setTitle("Render Queue");
        if (rootPane.getScene() != null && rootPane.getScene().getWindow() != null) {
            renderQueueStage.initOwner(rootPane.getScene().getWindow());
        }
        javafx.scene.Scene scene = new javafx.scene.Scene(renderQueueView);
        DarkThemeHelper.applyTo(scene);
        renderQueueStage.setScene(scene);
        renderQueueStage.setMinWidth(560);
        renderQueueStage.setMinHeight(320);
        renderQueueStage.setOnHidden(_ -> {
            if (renderQueueView != null) renderQueueView.dispose();
            renderQueueView = null;
            renderQueueStage = null;
        });
        renderQueueStage.show();
        renderQueueStage.toFront();
    }

    /**
     * Persist the queue and shut down its workers. Invoked from the
     * primary stage's {@code setOnHidden} hook so the queue's state
     * survives an app restart.
     */
    private void disposeRenderQueue() {
        // Close the queue view window first so the user cannot interact
        // with a shutting-down queue.
        if (renderQueueStage != null) {
            renderQueueStage.close();
        }
        if (renderQueue == null) return;
        try {
            renderQueue.persist();
        } catch (java.io.IOException e) {
            LOG.log(Level.WARNING, "Failed to persist render queue on shutdown", e);
        }
        renderQueue.shutdown();
        renderQueue = null;
    }

    /**
     * Toggles A/B monitoring between the DAW's render and the reference
     * playback. Delegates to the {@link com.benesquivelmusic.daw.app.ui.spatial.AtmosAbView}
     * when it exists, otherwise directly toggles via the
     * {@link com.benesquivelmusic.daw.core.reference.ReferenceTrackManager}.
     */
    void onImmersiveAbToggle() {
        if (atmosAbView != null) {
            atmosAbView.toggleAb();
        } else {
            project.getReferenceTrackManager().toggleAB();
        }
    }

    /**
     * Installs the click handler on the transport-bar I/O latency
     * indicator (story 217). A primary-button click opens an
     * {@link IoLatencyDetailsPopup} surfacing the three driver-reported
     * components and an embedded "Calibrate&hellip;" button that opens
     * a {@link LatencyCalibrationDialog} on the same input device list.
     *
     * <p>The label retains its existing tooltip and styling — only its
     * cursor and on-click behaviour are augmented.</p>
     */
    private void installIoLatencyClickHandler() {
        if (ioRoutingLabel == null) {
            return;
        }
        ioRoutingLabel.setCursor(javafx.scene.Cursor.HAND);
        ioRoutingLabel.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                openIoLatencyDetailsPopup();
            }
        });
    }

    /**
     * Composes and shows the I/O latency details popup. Reads the
     * driver-reported latency and any active per-device override from
     * the {@link AudioEngineController}, and wires the embedded
     * "Calibrate&hellip;" button to {@link #openLatencyCalibrationDialog()}.
     */
    void openIoLatencyDetailsPopup() {
        com.benesquivelmusic.daw.sdk.audio.RoundTripLatency driver =
                audioEngineController != null
                        ? audioEngineController.driverReportedLatency()
                        : com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN;
        Integer override = audioEngineController != null
                ? audioEngineController.latencyOverrideFrames().orElse(null)
                : null;
        double sampleRate = project.getFormat().sampleRate();
        IoLatencyDetailsPopup popup = new IoLatencyDetailsPopup(
                driver, override, sampleRate, this::openLatencyCalibrationDialog);
        popup.showAndWait();
    }

    /**
     * Composes and shows the latency calibration dialog. Hooks the
     * dialog's {@link LatencyCalibrationDialog.CalibrationRunner} to
     * the live audio engine and persists any accepted override via
     * {@link AudioEngineController#setLatencyOverrideFrames(java.util.Optional)}.
     *
     * <p>The default runner used by production wiring plays a
     * single-sample impulse via {@link com.benesquivelmusic.daw.sdk.audio.LatencyCalibration#generateImpulse(int)}
     * and captures it back through the active audio engine. When no
     * audio backend is bound (test stubs) the runner reports an
     * inconclusive result rather than throwing.</p>
     */
    void openLatencyCalibrationDialog() {
        if (audioEngineController == null) {
            return;
        }
        java.util.List<com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo> inputs =
                listInputChannelsForCalibration();
        double sampleRate = project.getFormat().sampleRate();
        com.benesquivelmusic.daw.sdk.audio.RoundTripLatency driver =
                audioEngineController.driverReportedLatency();
        LatencyCalibrationDialog.CalibrationRunner runner = input -> {
            // Production stub: return an inconclusive result (no impulse
            // detected) until the end-to-end audio loopback capture is
            // wired in a follow-up story. This keeps the dialog functional
            // and surfaces a graceful "no impulse detected" result panel.
            return new com.benesquivelmusic.daw.sdk.audio.LatencyCalibration.CalibrationResult(
                    0, driver.totalFrames(), false);
        };
        LatencyCalibrationDialog dialog = new LatencyCalibrationDialog(inputs, sampleRate, runner);
        java.util.Optional<LatencyCalibrationDialog.Result> result = dialog.showAndWait();
        if (result.isPresent()) {
            switch (result.get()) {
                case LatencyCalibrationDialog.Result.AcceptOverride accept ->
                        audioEngineController.setLatencyOverrideFrames(
                                java.util.Optional.of(accept.frames()));
                case LatencyCalibrationDialog.Result.ClearOverride _ ->
                        audioEngineController.setLatencyOverrideFrames(
                                java.util.Optional.empty());
                case LatencyCalibrationDialog.Result.Cancelled _ -> { /* no-op */ }
            }
        }
    }

    /**
     * Returns the input channels the calibration dialog offers in its
     * source combo. Queries the active backend's input channels for the
     * currently bound device (story 215 / 223). Falls back to a single
     * synthetic "Loopback / measurement input" entry when no real
     * channel info is available, so the dialog is always usable.
     */
    private java.util.List<com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo>
            listInputChannelsForCalibration() {
        // Try to query the real backend's input channels for the active device.
        if (audioEngineController != null) {
            com.benesquivelmusic.daw.core.audio.AudioEngine engine = audioEngine;
            com.benesquivelmusic.daw.sdk.audio.AudioBackend backend = engine.getBackend();
            if (backend != null) {
                com.benesquivelmusic.daw.sdk.audio.DeviceId device =
                        audioEngineController.getActiveDevice().orElse(null);
                if (device != null) {
                    java.util.List<com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo> channels =
                            backend.inputChannels(device);
                    if (!channels.isEmpty()) {
                        return channels;
                    }
                }
            }
        }
        // Fallback: synthetic pseudo-channel so the dialog is usable.
        return java.util.List.of(new com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo(
                0, "Loopback / measurement input"));
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

    /**
     * Installs contextual help on {@code scene} the first time the play
     * button enters the scene graph: lazy-initialises the {@link HelpOverlay}
     * + {@link QuickHelpBar}, registers the F1 / Shift+F1 key handler, and
     * tags transport / arrangement / mixer controls with help topics.
     *
     * <p>This is a single, idempotent entry-point — calling it twice (e.g.
     * if the rootPane briefly leaves and re-enters its scene) is safe.</p>
     */
    private void installContextualHelp(javafx.scene.Scene scene) {
        if (helpOverlay == null) {
            helpOverlay = new HelpOverlay(helpRegistry);
            quickHelpBar = new QuickHelpBar(helpRegistry);
            helpKeyHandler = new HelpKeyHandler(helpRegistry, helpOverlay, quickHelpBar);

            // Mount the Quick Help bar into the bottom VBox so it is visible.
            Node bottom = rootPane.getBottom();
            if (bottom instanceof VBox bottomBox) {
                bottomBox.getChildren().addFirst(quickHelpBar);
            }
        }
        helpKeyHandler.installOn(scene);
        quickHelpBar.attachTo(scene);

        // Tag the most prominent controls so F1 lands on a useful topic.
        tagHelpTopic(playButton, "transport");
        tagHelpTopic(stopButton, "transport");
        tagHelpTopic(recordButton, "transport");
        tagHelpTopic(loopButton, "transport");
        tagHelpTopic(metronomeButton, "transport");
        tagHelpTopic(skipBackButton, "transport");
        tagHelpTopic(skipForwardButton, "transport");
        tagHelpTopic(snapButton, "arrangement");
        tagHelpTopic(addAudioTrackButton, "arrangement");
        tagHelpTopic(addMidiTrackButton, "arrangement");

        // Register control IDs in the registry so other code can resolve
        // controls by ID (e.g. the command palette → "Help on…" entries).
        registerHelpControl(playButton, "transport");
        registerHelpControl(stopButton, "transport");
        registerHelpControl(recordButton, "transport");
        registerHelpControl(loopButton, "transport");
        registerHelpControl(metronomeButton, "transport");
        registerHelpControl(skipBackButton, "transport");
        registerHelpControl(skipForwardButton, "transport");
        registerHelpControl(snapButton, "arrangement");
        registerHelpControl(addAudioTrackButton, "arrangement");
        registerHelpControl(addMidiTrackButton, "arrangement");

        // Anchor overlay and start the onboarding tour once the window is
        // available.  Use a scene property key to prevent duplicate listeners
        // if installContextualHelp is called again for the same scene.
        String listenerKey = HELP_WINDOW_LISTENER_KEY;
        if (scene.getProperties().containsKey(listenerKey)) {
            return;
        }
        scene.getProperties().put(listenerKey, Boolean.TRUE);

        Runnable onWindowReady = () -> {
            Window window = scene.getWindow();
            helpOverlay.anchorTo(window);

            // First-launch onboarding tour — highlights the main controls
            // and opens the help topic for each in sequence.
            var onboardingState = OnboardingState.defaultLocation();
            if (onboardingState.shouldRunTour()) {
                var tour = new OnboardingTour(helpOverlay, onboardingState)
                        .addStep("transport", playButton)
                        .addStep("arrangement", snapButton)
                        .addStep("mixer", null)
                        .addStep("browser", null);
                tour.start(false);
            }
        };

        if (scene.getWindow() != null) {
            onWindowReady.run();
        } else {
            scene.windowProperty().addListener((_, _, w) -> {
                if (w != null) { onWindowReady.run(); }
            });
        }
    }

    private void registerHelpControl(Node node, String slug) {
        if (node != null && node.getId() != null) {
            helpRegistry.registerControl(node.getId(), slug);
        }
    }

    private void tagHelpTopic(Node node, String slug) {
        if (node != null) {
            HelpControls.setHelpTopic(node, slug);
        }
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
        // Icon dropped per UI Design Book §2.4 — labelled status displays
        // keep text only; icon-next-to-label is forbidden.
    }

    private void updateProjectInfo() {
        AudioFormat fmt = project.getFormat();
        // Story 274 \u2014 projectInfoLabel is the FIRST status-bar cell, so it
        // carries NO leading "\u00b7 " dot (JavaFX CSS has no :first-child, \u00a76).
        // Later non-first cells carry it: monitoring chrome words come from
        // the bundle (Skill \u00a714) already dot-prefixed; the kHz I/O figure is
        // a dynamic numeric value (\u00a75.11) so it stays a code-formatted
        // string with its own dot.
        projectInfoLabel.setText(String.format(Locale.ROOT, "%s  \u00b7  %.0f kHz / %d-bit / %dch",
                project.getName(), fmt.sampleRate() / 1000.0, fmt.bitDepth(), fmt.channels()));
        monitoringLabel.setText(switch (fmt.channels()) {
            case 1 -> MESSAGES.getString("statusbar.monitoring.mono");
            case 2 -> MESSAGES.getString("statusbar.monitoring.stereo");
            default -> new MessageFormat(
                    MESSAGES.getString("statusbar.monitoring.surround"), Locale.ROOT)
                    .format(new Object[]{fmt.channels()});
        });
        ioRoutingLabel.setText(String.format(Locale.ROOT,
                StatusCellLabel.CELL_SEPARATOR + "%.0f kHz I/O", fmt.sampleRate() / 1000.0));
        refreshLockStatusIndicator();
    }

    /**
     * Story 274 \u2014 fills the static placeholder status-bar cells
     * (CPU / MEM / DSK) from {@link #MESSAGES}. These are
     * design-time placeholders only; the bundle value is the runtime
     * authoritative text (Skill \u00a714). Each carries its own leading "\u00b7 "
     * dot in the bundle string because every cell after projectInfoLabel
     * is dot-prefixed (the dot is part of the text \u2014 \u00a76).
     *
     * <p><b>TODO story-274 follow-on:</b> CPU / MEM / DSK have no live
     * telemetry source today (PerformanceMonitor tracks only audio-DSP
     * load; there is no system mem/disk probe and one is explicitly out
     * of scope per the story Non-Goals). Wiring them to a real source is
     * a tracked follow-on.
     */
    private void initializeStatusBarPlaceholders() {
        cpuLabel.setText(MESSAGES.getString("statusbar.cpu"));
        memLabel.setText(MESSAGES.getString("statusbar.mem"));
        dskLabel.setText(MESSAGES.getString("statusbar.dsk"));
    }

    /**
     * Story 187 — inserts the {@link LockStatusIndicator} into the status
     * bar HBox immediately after {@link #projectInfoLabel} (the project-name
     * label) and starts a 5 s refresh timeline so the badge stays in sync
     * with {@link com.benesquivelmusic.daw.core.persistence.ProjectLockManager}.
     */
    private void mountLockStatusIndicator() {
        if (lockStatusIndicator != null || projectInfoLabel == null) {
            return;
        }
        Node parent = projectInfoLabel.getParent();
        if (!(parent instanceof HBox bar)) {
            return;
        }
        lockStatusIndicator = new LockStatusIndicator();
        int idx = bar.getChildren().indexOf(projectInfoLabel);
        bar.getChildren().add(idx + 1, lockStatusIndicator);
        refreshLockStatusIndicator();

        // Periodic refresh — ProjectLockManager has no Flow.Publisher today,
        // so a low-frequency 5 s poll is the cheapest way to surface a
        // stolen lock or a take-over without changing the core API.
        lockIndicatorTimeline = new Timeline(
                new KeyFrame(Duration.seconds(5), _ -> refreshLockStatusIndicator()));
        lockIndicatorTimeline.setCycleCount(Timeline.INDEFINITE);
        lockIndicatorTimeline.play();
    }

    /** Repaints the lock badge from the current {@code ProjectLockManager} status. */
    private void refreshLockStatusIndicator() {
        if (lockStatusIndicator == null || projectManager == null) {
            return;
        }
        lockStatusIndicator.refresh(projectManager.getLockManager());
    }

    private void updateCheckpointStatus() {
        // Story 274 — static chrome strings from the bundle (Skill §14).
        // checkpointLabel = autosave state; ioRoutingLabel = transient I/O
        // init message. Both are non-first cells, dot-prefixed in the
        // bundle value (checkpointLabel is also a StatusCellLabel, which
        // re-asserts the dot for its ~30 dynamic writers).
        checkpointLabel.setText(MESSAGES.getString("statusbar.autosave.on"));
        ioRoutingLabel.setText(MESSAGES.getString("statusbar.io.initializing"));
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
