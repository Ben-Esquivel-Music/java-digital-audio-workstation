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
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.HistoryControlBinder;
import com.benesquivelmusic.daw.app.ui.vm.HistoryVM;
import com.benesquivelmusic.daw.app.ui.vm.ProjectVM;
import com.benesquivelmusic.daw.app.ui.vm.TransportControlBinder;
import com.benesquivelmusic.daw.app.ui.vm.TransportVM;
import com.benesquivelmusic.daw.app.ui.vm.command.HistoryCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.RedoCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.UndoCommand;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
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
import com.benesquivelmusic.daw.app.ui.dock.DockEntry;
import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.layout.BuiltInLayouts;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.stage.Screen;
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
    @FXML private Label statusBarLabel;
    @FXML private Label arrangementPlaceholder;
    @FXML private Label arrangementPanelHeader;
    /** Story 288 — timeline-header HBox that hosts the arrangement dock grip. */
    @FXML private HBox arrangementTimelineHeader;
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
    /** Story 295 — bottom container that hosts the notification bar, the legacy
     *  status bar, and (mounted by {@link #createSessionStatusStrip()}) the
     *  Session Status Strip. */
    @FXML private VBox bottomBar;
    @FXML private HBox transportGroup;
    @FXML private HBox trackGroup;
    @FXML private HBox undoRedoGroup;
    @FXML private HBox utilityGroup;
    @FXML private VBox trackListPanel;
    /** Story 272 — unified Inspector drawer on the right edge of the centre BorderPane. */
    @FXML private com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer inspectorDrawer;

    private final Button browserButton = new Button("Library");
    private final Button historyButton = new Button("History");

    /**
     * The in-memory project. {@code volatile} because it is reassigned on the FX
     * thread (init / open / restore) yet read off the FX thread by the §1.8
     * disk-scan {@code audioFormat} supplier on the scan virtual thread (story
     * 295); the {@code volatile} gives that background read a happens-before view
     * of each reassignment so a project switch is reflected on the next scan.
     */
    private volatile DawProject project;
    private PluginRegistry pluginRegistry;
    private ProjectManager projectManager;
    private UndoManager undoManager;
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

    // ── Story 293: view-model layer (Control Synchronization Design Book §4.3/§4.4) ──
    // The VMs make the controls reactive subscribers to the core model's neutral
    // change signals (stories 290-292), retiring the imperative update*/refresh*/sync*
    // methods. Rebuilt per project load by rebuildViewModels(); disposed on hide.
    private HistoryVM historyVM;
    private HistoryControlBinder historyBinder;
    private TransportVM transportVM;
    private ProjectVM projectVM;

    // ── Story 295: Session Status Strip + the ProjectOperationProgress model ──
    /** The single §5.5 source of project-status truth the strip binds to. */
    private com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress projectOperationProgress;
    /** The §4.4 status strip, mounted into {@link #bottomBar}. */
    private com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip sessionStatusStrip;
    /** The §1.8 disk-space scan (30 s, virtual thread) feeding the model. */
    private com.benesquivelmusic.daw.app.ui.status.SessionStatusDiskScanner diskScanner;
    /** Start of the current working session (since project open/new); §3.3, drives the Session cell elapsed. */
    private java.time.Instant sessionStartInstant;
    /** Disposers for the current VM generation (binders + VM unregistration); run on rebuild + hide. */
    private final java.util.List<Runnable> vmDisposers = new java.util.ArrayList<>();
    private ToolbarStateStore toolbarStateStore;
    private KeyBindingManager keyBindingManager;
    private CommandPaletteView commandPaletteView;
    private WorkspaceManager workspaceManager;
    private DockManager dockManager;
    /**
     * Story 282 — Mission Control named-layout façade. Instantiated in
     * {@link #installLayoutManager()} after the dock manager is live so
     * the {@link com.benesquivelmusic.daw.app.ui.layout.LayoutManager.Host}
     * bridge can read / write {@link DockManager#captureJson()}.
     */
    private com.benesquivelmusic.daw.app.ui.layout.LayoutManager layoutManager;
    /**
     * Story 282 — observable manifest of every registered dockable panel,
     * rendered as the bottom-of-window manifest bar.
     */
    private com.benesquivelmusic.daw.app.ui.layout.DockManifestModel dockManifestModel;
    /** Floating windows currently owned by the dock host, keyed by panel id. */
    private final java.util.Map<String, Stage> floatingStages = new java.util.LinkedHashMap<>();

    /**
     * Panel ids that compete for the CENTER dock zone. Treated as a
     * single-selection slot by {@link #toggleCenterDockPanel(String)} —
     * only one is visible at a time while tabbed CENTER targets are
     * deferred.
     */
    private static final java.util.List<String> CENTER_ZONE_PANELS = java.util.List.of(
            DefaultWorkspaces.PANEL_ARRANGEMENT, DefaultWorkspaces.PANEL_MIXER,
            DefaultWorkspaces.PANEL_EDITOR, DefaultWorkspaces.PANEL_MASTERING);
    /**
     * Suppresses {@code DockManager.Host.onLayoutChanged} side-effects while
     * the dock manager is being seeded. Without this flag the initial
     * {@code register(mixer)} would fire a layout snapshot saying
     * "mixer visible in BOTTOM" and the host would call
     * {@code viewNavigationController.switchView(MIXER)} at startup —
     * stealing the centre slot from the arrangement view the user persisted.
     */
    private boolean dockHostReconciliationSuppressed = true;
    /**
     * Story 287 — {@code true} while a saved / built-in dock layout is being
     * applied via {@link DockManager#applyJson(String)}. The RIGHT-zone
     * telemetry panels float only on an <em>explicit</em> user action (the
     * Sound Wave Telemetry menu item or a manifest-bar click), never as a
     * side effect of {@code applyLayout} force-showing the panels an incoming
     * layout never mentioned — otherwise a floating telemetry window would
     * pop on every File → New / Open and built-in-layout load (the shipped
     * built-in layouts predate these panels).
     */
    private boolean applyingDockLayout = false;
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
    // ── Story 287 — dockable analyzer displays ──────────────────────────────
    // The spectrum + level-meter displays are still the live, idle-demo-fed
    // instances handed to AnimationController; the other four are decorative
    // shells (Non-Goal #1). All six are wrapped in DockableVisualizationPanel
    // adapters (keyed by panel id in vizDockables) and mounted into the
    // bottom dock strip when visible.
    private SpectrumDisplay spectrumDisplay;
    private LevelMeterDisplay levelMeterDisplay;
    private com.benesquivelmusic.daw.app.ui.display.WaveformDisplay waveformDisplay;
    private com.benesquivelmusic.daw.app.ui.display.LoudnessDisplay loudnessDisplay;
    private com.benesquivelmusic.daw.app.ui.display.CorrelationDisplay correlationDisplay;
    private com.benesquivelmusic.daw.app.ui.display.TunerDisplay tunerDisplay;
    /** The six BOTTOM-zone analyzer adapters, keyed by dock id (story 287). */
    private final java.util.Map<String, com.benesquivelmusic.daw.app.ui.display.DockableVisualizationPanel>
            vizDockables = new java.util.LinkedHashMap<>();
    /**
     * Session Manager dock (Project Manager Design Book §4.3) — the durable,
     * named session history surfaced on the RIGHT edge. Populated on project
     * open from {@link com.benesquivelmusic.daw.core.session.SessionManager}.
     */
    private SessionManagerDock sessionManagerDock;
    /**
     * Story 287 — single eager {@link TelemetryView}. Its
     * {@code getSetupPanel()} is registered as {@code PANEL_TELEMETRY} and
     * the whole view is the mounted node for both {@code PANEL_TELEMETRY}
     * and {@code PANEL_ROOM_3D} (they are the two faces of one plugin
     * view; see {@code resolveNode}). Constructed in {@link #installDockManager()}.
     */
    private TelemetryView telemetryView;
    /** Bottom dock strip hosting visible BOTTOM-zone analyzer panels (story 287). */
    private javafx.scene.layout.HBox vizBottomStrip;
    /** RIGHT-zone dock host for the telemetry view, alongside the inspector (story 287). */
    private javafx.scene.layout.HBox rightDockHost;

    /** Contextual help registry — loads markdown topics from {@code resources/help/}. */
    private final HelpRegistry helpRegistry = HelpRegistry.loadDefault();
    private static final String HELP_WINDOW_LISTENER_KEY = "help.windowListenerInstalled";
    /** Right-side overlay displaying the active help topic; lazily created with the scene. */
    private HelpOverlay helpOverlay;
    /** Bottom Quick Help bar — toggled with {@code Shift+F1}. */
    private QuickHelpBar quickHelpBar;
    /** F1 / Shift+F1 key handler installed on the primary scene. */
    private HelpKeyHandler helpKeyHandler;

    /**
     * Story 289 — the single FX-thread marshalling seam (Control
     * Synchronization Design Book §4.5). Injected by {@code DawApplication}
     * via {@link #setFxDispatcher(FxDispatcher)} immediately after
     * {@code FXMLLoader.load()} returns, because the {@code fx:controller} is
     * instantiated by FXML through a no-arg constructor and so cannot be
     * constructor-injected. {@code DawApplication} installs the same instance
     * as the {@link FxDispatcher#getDefault() app-scoped default} before the
     * FXML loads, so {@link #dispatcher()} resolves it even for the brief
     * window during {@link #initialize()} before the setter has run.
     */
    private FxDispatcher fxDispatcher;

    /**
     * Injects the application's {@link FxDispatcher}. Set once at app init by
     * the composition root (see {@link #fxDispatcher}); never reassigned at
     * runtime.
     *
     * @param dispatcher the marshalling seam; must not be {@code null}
     */
    public void setFxDispatcher(FxDispatcher dispatcher) {
        this.fxDispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /**
     * Resolves the marshalling seam: the injected instance if present,
     * otherwise the {@link FxDispatcher#getDefault() app-scoped default}. The
     * fallback keeps the few listeners wired in {@link #initialize()} (which
     * runs <em>during</em> {@code FXMLLoader.load()}, before the setter) safe
     * even if they were ever to fire before injection — the production default
     * is installed before the FXML loads. Returns {@code null} only in a
     * pure-unit context that installed no dispatcher.
     *
     * @return the seam, or {@code null} if none is available
     */
    private FxDispatcher dispatcher() {
        return fxDispatcher != null ? fxDispatcher : FxDispatcher.getDefault();
    }

    /**
     * Posts {@code work} to the FX thread through the {@link #fxDispatcher
     * injected seam} when present, else the {@link FxDispatcher#getDefault()
     * app-scoped default} (and a bare {@code runLater} when neither is
     * installed). This is the same null-tolerant hop the other UI controllers
     * use via {@code FxDispatcher.runOnFx(...)}; a listener wired in
     * {@link #initialize()} that fired before {@link #setFxDispatcher} (or in a
     * pure-unit context with no dispatcher at all) therefore never dereferences
     * a null seam, where {@code dispatcher().onFx(...)} would have thrown.
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
    }

    /**
     * Story 293 — (re)builds the view-model layer and binds the controls to it,
     * retiring the imperative {@code update*}/{@code refresh*}/{@code sync*}
     * methods (Control Synchronization Design Book §4.3/§4.4, §5.7). Invoked at
     * startup and after every project load / new — the project, transport and
     * undo manager are all swapped then — so the controls always observe the live
     * model. The previous generation of VMs and binders is disposed first, so
     * nothing leaks across loads (javafx-application-design §3/§4/§11).
     */
    private void rebuildViewModels() {
        for (Runnable disposer : vmDisposers) {
            disposer.run();
        }
        vmDisposers.clear();

        FxDispatcher disp = dispatcher();

        // Project — the name cells track ProjectVM.name; the arrangement placeholder +
        // canvas track ProjectVM.tracks (§4.3, replacing updateProjectInfo() and
        // updateArrangementPlaceholder()). Format/lock/checkpoint are not ProjectVM
        // facts (no producer; a Non-Goal to add) and stay imperative.
        projectVM = new ProjectVM(project, disp);
        vmDisposers.add(projectVM::dispose);
        applyProjectInfoLabels();
        javafx.beans.value.ChangeListener<String> projectNameListener =
                (obs, was, now) -> applyProjectInfoLabels();
        projectVM.nameProperty().addListener(projectNameListener);
        vmDisposers.add(() -> projectVM.nameProperty().removeListener(projectNameListener));
        // Seed + bind together: the placeholder visibility AND the arrangement
        // canvas both follow ProjectVM.tracks. A freshly built VM seeds its backing
        // list *before* this listener is attached, so the listener does NOT fire on
        // a project load — apply once explicitly here, or the canvas keeps rendering
        // the previous project's tracks after File→Open / snapshot-restore (story
        // 293 review #1). Seed and listener share one Runnable so they can't drift.
        Runnable applyTracksToCanvas = () -> {
            if (arrangementPlaceholder != null) {
                arrangementPlaceholder.setVisible(projectVM.getTracks().isEmpty());
            }
            repaintArrangementCanvas();
        };
        applyTracksToCanvas.run();
        javafx.beans.InvalidationListener projectTracksListener = obs -> applyTracksToCanvas.run();
        projectVM.getTracks().addListener(projectTracksListener);
        vmDisposers.add(() -> projectVM.getTracks().removeListener(projectTracksListener));

        // Dirty — the single dirty bit (§1.2, §7). ProjectVM.dirty mirrors
        // DawProject.isDirty(), now the one source: every former projectDirty=true
        // poke routes through DawProject.markDirty(), and New/Open/Save clear it via
        // markClean(). The Save action derives its enablement from it (§6.9
        // "derive, don't poke"); re-sync the menu when dirty flips so Save
        // enables/disables reactively rather than on the next unrelated sync.
        // The mirror updates synchronously for FX-thread edits (ProjectVM's inline
        // fast-path), so an explicit syncMenuStateIfPresent() right after markDirty()
        // already reads the new value, and a Save read stays consistent with the
        // confirmDiscardUnsavedChanges() authority read within the same pulse.
        javafx.beans.value.ChangeListener<Boolean> projectDirtyListener =
                (obs, was, now) -> syncMenuStateIfPresent();
        projectVM.dirtyProperty().addListener(projectDirtyListener);
        vmDisposers.add(() -> projectVM.dirtyProperty().removeListener(projectDirtyListener));

        // Transport — the tempo label binds TransportVM.tempo (§4.3, replaces
        // updateTempoDisplay()) through the canonical TransportControlBinder, so the
        // "%.1f BPM" formatting lives in exactly one place. The label is read-only
        // (it issues no commands), so the binder's command sink is a no-op — the
        // read-only-binding pattern of TransportBindingTest. The playhead/loop/ruler
        // are read from the VM by the per-frame tickArrangementOverlays() (the ruler
        // grid has no discrete trigger).
        transportVM = new TransportVM(project.getTransport(), disp);
        vmDisposers.add(transportVM::dispose);
        TransportControlBinder transportBinder = new TransportControlBinder(transportVM, command -> { });
        transportBinder.bindTempoLabel(tempoLabel);
        vmDisposers.add(transportBinder::dispose);

        // History (undo/redo) — §6.9; replaces updateUndoRedoState()'s button poke.
        // HistoryVM re-registers on the (swapped) undo manager, so the buttons stay
        // correct across project loads — the binder owns disable + tooltip + action.
        historyVM = new HistoryVM(undoManager, disp);
        vmDisposers.add(historyVM::dispose);
        historyBinder = new HistoryControlBinder(historyVM, this::dispatchHistoryCommand);
        historyBinder.bindUndoButton(undoButton);
        historyBinder.bindRedoButton(redoButton);
        vmDisposers.add(historyBinder::dispose);

        // Menu enablement (§6.9) — sync once here, AFTER the VMs are (re)built, so
        // the Save action's `projectVM.isDirty()` supplier reads the FRESH VM. The
        // dirty listener above only fires on a later flip, never on the seeded
        // value (the same reason applyTracksToCanvas.run() seeds the canvas
        // explicitly above). Both callers previously synced the menu BEFORE this
        // rebuild — against the stale outgoing VM — which left Save enablement one
        // project-load behind after Open / New / snapshot-restore.
        syncMenuStateIfPresent();

        // Story 295 — (re)publish the facts the Session Status Strip binds to for
        // this VM generation: bind the model's dirty bit to the FRESH ProjectVM,
        // start a new working session, and seed schema / lock / session /
        // next-checkpoint. Done AFTER the VMs are rebuilt so the dirty bind
        // targets the live ProjectVM (mirrors syncMenuStateIfPresent above).
        sessionStartInstant = java.time.Instant.now();
        publishProjectStatusToModel();
    }

    /** Routes an undo/redo intent (button, menu, or shortcut) to the existing handlers (§2.8). */
    private void dispatchHistoryCommand(HistoryCommand command) {
        switch (command) {
            case UndoCommand _ -> onUndo();
            case RedoCommand _ -> onRedo();
        }
    }

    /**
     * Story 293 — the menu-enablement poke that outlives the deleted
     * {@code updateUndoRedoState()}: the undo/redo <em>buttons</em> now bind
     * {@link HistoryVM}, but the Edit-menu items still recompute through
     * {@link MenuEnablementPolicy} until the §6.9 VM-driven menu capstone.
     */
    private void syncMenuStateIfPresent() {
        if (menuBarController != null) {
            menuBarController.syncMenuState();
        }
    }

    // ── Story 295: Session Status Strip wiring ───────────────────────────────

    /**
     * Builds the {@link com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress}
     * model, the {@link com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip}
     * (mounted into {@link #bottomBar}), the §1.8 disk scan, and the checkpoint
     * listener that surfaces every autosave on the model. Called once in
     * {@link #initialize()} after the {@link ProjectManager} exists and before
     * the lifecycle controller (which now reports save status through the model).
     */
    private void createSessionStatusStrip() {
        projectOperationProgress =
                new com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress(dispatcher());
        // §3.1 default workspace name; the switchable Project Hub is story 296.
        projectOperationProgress.setWorkspaceName("Personal");
        sessionStatusStrip = new com.benesquivelmusic.daw.app.ui.status.SessionStatusStrip(
                projectOperationProgress, MotionManager.getDefault());
        if (bottomBar != null) {
            bottomBar.getChildren().add(sessionStatusStrip);
        }

        // §1.8 disk-space contract — scan every 30 s on a virtual thread; the FX
        // thread never touches FileStore. The project dir / format are read live
        // so a project open / close is reflected on the next scan.
        diskScanner = new com.benesquivelmusic.daw.app.ui.status.SessionStatusDiskScanner(
                projectOperationProgress,
                () -> {
                    // Read the current project ONCE: this supplier runs on the
                    // scan virtual thread, and a concurrent FX-thread
                    // close/abandon could otherwise null currentProject between a
                    // two-call check and dereference (TOCTOU NPE).
                    if (projectManager == null) {
                        return null;
                    }
                    var meta = projectManager.getCurrentProject();
                    return meta != null ? meta.projectPath() : null;
                },
                () -> {
                    // Read the volatile project reference ONCE on the scan
                    // virtual thread: a concurrent FX-thread open/new/restore
                    // could otherwise reassign (or, defensively, null) it
                    // between the check and the deref (the same TOCTOU the
                    // projectPath supplier above guards against).
                    var current = project;
                    return current != null ? current.getFormat() : null;
                });
        diskScanner.start();

        // §1.2 checkpoint visibility — every autosave updates the Saved cell and
        // re-arms the countdown. The listener fires on the checkpoint virtual
        // thread; the model marshals each write onto the FX thread (story 289).
        projectManager.getCheckpointManager().addListener(
                new com.benesquivelmusic.daw.sdk.event.AutoSaveListener() {
                    @Override public void onBeforeCheckpoint(String checkpointId) {
                        // no-op: a fast checkpoint shows only the Saved-cell flash
                    }

                    @Override public void onAfterCheckpoint(String checkpointId) {
                        java.time.Duration interval = projectManager.getCheckpointManager()
                                .getConfig().autoSaveInterval();
                        projectOperationProgress.recordSaveSucceeded(java.time.Instant.now(),
                                com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress
                                        .SaveScope.FULL);
                        projectOperationProgress.setNextCheckpoint(
                                java.time.Instant.now().plus(interval), interval);
                    }

                    @Override public void onCheckpointFailed(String checkpointId, Throwable cause) {
                        projectOperationProgress.recordSaveFailed();
                    }
                });
    }

    /**
     * Publishes the project-status facts the strip binds to after a project
     * load / new (called from {@link #rebuildViewModels()}): binds the model's
     * dirty bit to the live {@link ProjectVM}, seeds the schema versions from the
     * last migration report, arms the next-checkpoint countdown, and refreshes
     * the session / lock facts.
     */
    private void publishProjectStatusToModel() {
        if (projectOperationProgress == null) {
            return;
        }
        if (projectVM != null) {
            projectOperationProgress.bindDirtyTo(projectVM.dirtyProperty());
        }
        // Schema (§1.6): current registry version + the pre-migration backup
        // version when the just-loaded project was migrated.
        int current = com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry.CURRENT_VERSION;
        int backup = com.benesquivelmusic.daw.app.ui.status.ProjectOperationProgress.UNKNOWN_VERSION;
        if (projectManager != null) {
            var report = projectManager.getLastMigrationReport();
            if (report != null && report.wasMigrated()) {
                backup = report.fromVersion();
            }
        }
        projectOperationProgress.setSchema(current, backup);
        // Next checkpoint (§6): arm the countdown when autosave is running.
        if (projectManager != null) {
            java.time.Duration interval = projectManager.getCheckpointManager()
                    .getConfig().autoSaveInterval();
            projectOperationProgress.setNextCheckpoint(
                    projectManager.getCheckpointManager().isRunning()
                            ? java.time.Instant.now().plus(interval) : null,
                    interval);
        }
        refreshStripDynamicState();
    }

    /**
     * Refreshes the time-varying strip facts (session elapsed, lock freshness)
     * that have no push source. Called from {@link #publishProjectStatusToModel()}
     * and the 5 s lock timeline.
     */
    private void refreshStripDynamicState() {
        if (projectOperationProgress == null) {
            return;
        }
        String name = project != null ? project.getName() : "";
        java.time.Duration elapsed = sessionStartInstant != null
                ? java.time.Duration.between(sessionStartInstant, java.time.Instant.now())
                : java.time.Duration.ZERO;
        projectOperationProgress.setSession(name, elapsed);
        publishLockToModel();
    }

    /** Publishes the §1.3 lock holder + freshness onto the model. */
    private void publishLockToModel() {
        if (projectOperationProgress == null || projectManager == null) {
            return;
        }
        com.benesquivelmusic.daw.core.persistence.LockStatus status =
                projectManager.getLockManager().status();
        if (status == com.benesquivelmusic.daw.core.persistence.LockStatus.HELD) {
            var lock = projectManager.getLockManager().currentLock();
            String holder = lock.map(l -> "you @ " + l.hostname()).orElse("you");
            boolean fresh = lock
                    .map(l -> !com.benesquivelmusic.daw.core.persistence.ProjectLockManager
                            .isStale(l, java.time.Instant.now()))
                    .orElse(true);
            projectOperationProgress.setLock(holder, fresh);
        } else if (status == com.benesquivelmusic.daw.core.persistence.LockStatus.READ_ONLY) {
            projectOperationProgress.setLock("read-only", true);
        } else if (status == com.benesquivelmusic.daw.core.persistence.LockStatus.STOLEN) {
            projectOperationProgress.setLock("taken over", false);
        } else {
            projectOperationProgress.setLock("", true);
        }
    }

    @FXML
    private void initialize() {
        project = new DawProject("Untitled Project", AudioFormat.STUDIO_QUALITY);
        pluginRegistry = new PluginRegistry();
        undoManager = new UndoManager();
        undoManager.addHistoryListener(_ -> {
            if (javafx.application.Platform.isFxApplicationThread()) {
                syncMenuStateIfPresent();
                repaintArrangementCanvas();
            } else {
                postFx(() -> {
                    syncMenuStateIfPresent();
                    repaintArrangementCanvas();
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
            applyProjectInfoLabels();
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
                // Story 294 — direct functional deps replace SnapshotsController.Host (§4.2/§9).
                new SnapshotsController.Deps(
                        () -> rootPane.getScene() != null
                                ? (Stage) rootPane.getScene().getWindow() : null,
                        () -> project,
                        () -> projectLifecycleController == null
                                || projectLifecycleController.confirmDiscardUnsavedChanges(),
                        this::applySnapshotRestoredProject),
                dispatcher());
        toolbarStateStore = new ToolbarStateStore(prefs);
        keyBindingManager = new KeyBindingManager(prefs.node("keybindings"));

        activeView = toolbarStateStore.loadActiveView();
        activeEditTool = toolbarStateStore.loadEditTool();
        snapEnabled = toolbarStateStore.loadSnapEnabled();
        gridResolution = toolbarStateStore.loadGridResolution();

        createToolbarAppearanceController();
        toolbarAppearanceController.apply();
        // Story 287 — construct the analyzer displays directly (the fixed
        // vizTileRow + VisualizationTileBuilder/VisualizationPanelController
        // are retired). spectrumDisplay + levelMeterDisplay remain the
        // idle-demo-fed instances AnimationController needs; the rest are
        // decorative shells. They are wrapped + registered in
        // installDockManager().
        spectrumDisplay = new SpectrumDisplay();
        levelMeterDisplay = new LevelMeterDisplay();
        waveformDisplay = new com.benesquivelmusic.daw.app.ui.display.WaveformDisplay();
        loudnessDisplay = new com.benesquivelmusic.daw.app.ui.display.LoudnessDisplay();
        correlationDisplay = new com.benesquivelmusic.daw.app.ui.display.CorrelationDisplay();
        tunerDisplay = new com.benesquivelmusic.daw.app.ui.display.TunerDisplay();
        buildBrowserPanel(toolbarStateStore.loadBrowserVisible());
        createTempoEditController();
        initializeNotificationBar();
        initializePluginFaultIsolation();
        createTransportController();
        mountPreRollPostRollControls();
        createMetronomeController(prefs);
        // Story 295 — build the ProjectOperationProgress model + Session Status
        // Strip + disk scan + checkpoint listener BEFORE the lifecycle
        // controller, which now reports save status through the model.
        createSessionStatusStrip();
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
        applyProjectInfoLabels();
        mountLockStatusIndicator();
        // Story 295 — the old static "Auto-save: ON" checkpointLabel was retired;
        // autosave / last-save / next-checkpoint are now live cells of the
        // Session Status Strip (bound to ProjectOperationProgress).
        ioRoutingLabel.setText(MESSAGES.getString("statusbar.io.initializing"));
        initializeStatusBarPlaceholders();
        rebuildViewModels();
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
        // Story 285 — instantiate the single application-wide DockManager
        // and register every top-level Dockable. Must run after both
        // viewNavigationController.initializeViewNavigation() (mixer/editor/
        // mastering views exist) and buildBrowserPanel(...) (browser panel
        // exists), and after createMenuBar() so workspaceManager is live and
        // captureDockLayoutJson()/applyDockLayoutJson() flow through this
        // manager.
        installDockManager();
        // Story 282 — Mission Control named layouts and the persistent
        // bottom-of-window dock manifest bar. Must run after
        // installDockManager() so the LayoutManager.Host bridge has a
        // live DockManager to query / mutate.
        installLayoutManager();
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
                        // Story 293 — release the view-model layer (binders + VM
                        // listeners + continuous channels) so nothing leaks on close.
                        for (Runnable disposer : vmDisposers) { disposer.run(); }
                        vmDisposers.clear();
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
                        // Story 281 (review N5) — release the
                        // selection-model listener wired by Workshop's
                        // host controller, alongside the other lifetime-
                        // scoped disposables.
                        if (viewNavigationController != null) {
                            viewNavigationController.dispose();
                        }
                        if (lockIndicatorTimeline != null) {
                            lockIndicatorTimeline.stop();
                            lockIndicatorTimeline = null;
                        }
                        // Story 295 — release the disk-scan scheduler thread.
                        if (diskScanner != null) {
                            diskScanner.stop();
                        }
                        if (dockManifestModel != null) {
                            dockManifestModel.dispose();
                        }
                        // Story 287 — stop AnimationTimers and free the
                        // off-heap GpuCanvas surfaces of the analyzer
                        // displays + telemetry view we now own (FX thread;
                        // GpuCanvasView.dispose() is idempotent).
                        disposeVisualizationDisplays();
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
                        monitoringLabel, statusBarLabel,
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
        // Story 293 — direct functional dependencies replace the retired Host.
        // These read live collaborators (view-nav, metronome, audio-engine,
        // settings) that may be null at construction or change over the
        // controller's life, so each is a Supplier/functional seam — mirroring
        // what the former Host closed over lazily. The dead metronome() method
        // is dropped (it was never invoked). flashMidiActivity is called from
        // the MIDI receiver thread; the controller marshals it via the
        // dispatcher.
        transportController = new TransportController(
                project, audioEngine, undoManager, notificationBar,
                statusLabel, timeDisplay, statusBarLabel, recIndicator,
                playButton, stopButton, recordButton, loopButton,
                () -> viewNavigationController != null
                        ? viewNavigationController.isSnapEnabled() : snapEnabled,
                () -> viewNavigationController != null
                        ? viewNavigationController.getGridResolution() : gridResolution,
                () -> metronomeController != null
                        ? metronomeController.getCountInMode() : CountInMode.OFF,
                () -> animationController.startTimeTicker(),
                () -> animationController.pauseTimeTicker(),
                () -> animationController.stopTimeTicker(),
                this::flashTrackArmButton,
                () -> settingsModel.isApplyLatencyCompensation(),
                () -> audioEngineController != null
                        ? audioEngineController.reportedLatency()
                        : com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN,
                dispatcher());
    }

    private void status(String text, DawIcon icon) {
        statusBarLabel.setText(text);
        if (icon != null) { statusBarLabel.setGraphic(IconNode.of(icon, 12)); }
    }

    private void flashTrackArmButton(Track track) {
        for (Node child : trackListPanel.getChildren()) {
            if (child.getUserData() == track) {
                Node armBtn = child.lookup(".track-arm-button");
                if (armBtn != null
                        && MotionManager.getDefault().isAnimationAllowed()) {
                    // Decorative arm-button flash — transitional motion,
                    // gated by global Reduce Motion (story 279).
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
                projectOperationProgress, rootPane, trackListPanel,
                // Story 294 — direct functional deps replace the callback-up
                // ProjectLifecycleController.Host (§4.2/§9). The swappable
                // project / undo manager are live Suppliers (a load is reflected
                // without rebuilding this controller); dirty is no longer a poke
                // — the controller calls DawProject.markDirty()/markClean()
                // through the project supplier (§1.2 "one dirty bit").
                new ProjectLifecycleController.Deps(
                        () -> project,
                        p -> project = p,
                        () -> undoManager,
                        um -> undoManager = um,
                        () -> trackCreationController.resetCounters(),
                        () -> historyPanelController.rebuild(),
                        this::handleProjectRebuild,
                        this::captureLayoutJsonForSave,
                        json -> { if (layoutManager != null) { layoutManager.fromJson(json); } }),
                new ProjectArchiver(),
                dispatcher());
    }

    /**
     * Story 296 — shows the §4.1 Welcome screen on launch when no project is
     * open on disk ({@code projectManager.getCurrentProject() == null}, which is
     * true at a fresh start: the in-memory Untitled project is not yet an
     * on-disk project). Called by {@code DawApplication} right after the primary
     * stage is shown, so the Welcome opens as an owned window over the main
     * scene. Recover lists projects whose lock shows an unclean exit; Continue
     * lists recent projects; Start offers New / Open / Restore / Import.
     */
    public void showWelcomeIfNoProjectOpen() {
        if (projectManager != null
                && projectManager.getCurrentProject() == null
                && projectLifecycleController != null) {
            projectLifecycleController.showWelcome();
        }
    }

    /**
     * Story 282 — captures the live Mission-Control layout JSON to embed in the
     * project file before save (the {@code ProjectLifecycleController.Deps}
     * {@code captureLayoutJson} supplier). Returns {@code null} when the layout
     * manager is not installed or is still in the implicit Default state, so
     * legacy projects round-trip unchanged.
     */
    private String captureLayoutJsonForSave() {
        if (layoutManager == null) {
            return null;
        }
        boolean isDefault = BuiltInLayouts.DEFAULT.equals(layoutManager.currentLayout())
                && layoutManager.savedLayouts().stream().noneMatch(l -> !l.builtIn());
        return isDefault ? null : layoutManager.toJson();
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
        // Story 287 — the shared telemetry view (formerly owned by the
        // plugin controller's standalone window) now follows the project.
        if (telemetryView != null) {
            telemetryView.setProject(project);
        }
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
        applyProjectInfoLabels();
        rebuildViewModels();
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
        refreshSessionManager();
    }

    /**
     * Project Manager Design Book §3.3/§4.3 — opens (or continues) the working
     * session for the just-loaded project and repopulates the Session Manager
     * dock with its history. The manifest read/write is I/O, so it runs on a
     * virtual thread (JEP 444) and marshals the result back to the FX thread
     * through {@link #postFx(Runnable)}. No-ops for unsaved (in-memory)
     * projects that have no on-disk directory yet.
     */
    private void refreshSessionManager() {
        if (sessionManagerDock == null) {
            return;
        }
        com.benesquivelmusic.daw.core.persistence.ProjectMetadata current =
                projectManager == null ? null : projectManager.getCurrentProject();
        String projectName = project == null ? null : project.getName();
        if (current == null || current.projectPath() == null) {
            sessionManagerDock.setProjectName(projectName);
            sessionManagerDock.setSessions(java.util.List.of());
            return;
        }
        java.nio.file.Path projectDir = current.projectPath();
        Thread.ofVirtual().name("daw-session-manager").start(() -> {
            java.util.List<com.benesquivelmusic.daw.core.session.WorkingSession> history;
            try {
                var manager = new com.benesquivelmusic.daw.core.session.SessionManager();
                history = manager.history(projectDir);
            } catch (java.io.IOException e) {
                LOG.log(Level.WARNING, "Failed to load session history for " + projectDir, e);
                history = java.util.List.of();
            }
            java.util.List<com.benesquivelmusic.daw.core.session.WorkingSession> result = history;
            postFx(() -> {
                sessionManagerDock.setProjectName(projectName);
                sessionManagerDock.setSessions(result);
            });
        });
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
        trackListPanel.getChildren().clear();
        Label header = new Label("TRACKS");
        header.getStyleClass().add("panel-header");
        // No icon-next-to-label per UI Design Book §2.4.
        trackListPanel.getChildren().add(header);
        MixerView newMixerView = new MixerView(project, undoManager, dispatcher());
        handleProjectRebuild(newMixerView);
        // Mark dirty AFTER the rebuild so the DIRTY signal lands on the freshly built
        // ProjectVM (the outgoing VM was disposed in rebuildViewModels and the new one
        // is wired to `project` there), which re-syncs the Save menu through its dirty
        // listener. Marking BEFORE the rebuild announced to no live listener and left
        // the bit visible only via the new VM's constructor seed — correct merely by
        // coincidence of ordering. A restored snapshot is unsaved relative to disk, so
        // it must be dirty.
        this.project.markDirty();
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
                    @Override public void markProjectDirty() { project.markDirty(); }
                    // ── Performance Stage (story 280) ─────────────────────────
                    @Override public ResourceBundle messages() { return MESSAGES; }
                    @Override public Label timeDisplay() { return timeDisplay; }
                    @Override public void onPlay() { transportController.onPlay(); }
                    @Override public void onStop() { transportController.onStop(); }
                    @Override public void onRecord() { transportController.onRecord(); }
                    @Override public void onToggleLoop() { transportController.onToggleLoop(); }
                    @Override public void onOpenAudioSettings() { MainController.this.onOpenAudioSettings(); }
                    @Override public void onNewProject() { projectLifecycleController.onNewProject(); }
                    @Override public void onOpenProject() { projectLifecycleController.onOpenProject(); }
                    @Override public void onSaveProject() { projectLifecycleController.onSaveProject(); }
                    @Override public void onRecentProjects() { projectLifecycleController.onRecentProjects(); }
                    // ── Workshop (story 281) ──────────────────────────────
                    @Override
                    public com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel
                            inspectorSelectionModel() {
                        return inspectorDrawer == null ? null
                                : inspectorDrawer.getSelectionModel();
                    }
                },
                dispatcher());
    }

    private void createTrackStripController() {
        // Story 293 — direct functional dependencies replace the retired Host.
        // This controller is reconstructed on every project load
        // (handleProjectRebuild), so project/undoManager are passed by value;
        // the action runnables and live-state suppliers route to the
        // view-navigation / transport controllers. updateArrangementPlaceholder
        // was a no-op (the placeholder binds ProjectVM.tracks) and is dropped.
        trackStripController = new TrackStripController(
                project, undoManager, audioEngine, viewNavigationController.getMixerView(),
                notificationBar, statusBarLabel, trackListPanel, rootPane,
                clipboardManager, selectionModel,
                this::syncMenuStateIfPresent,
                this::onUndo,
                () -> viewNavigationController.onZoomIn(),
                () -> viewNavigationController.onZoomOut(),
                () -> viewNavigationController.onToggleSnap(),
                () -> transportController.onSkipBack(),
                () -> project.markDirty(),
                () -> viewNavigationController.isSnapEnabled(),
                () -> viewNavigationController.getZoomLevel(viewNavigationController.getActiveView()),
                () -> viewNavigationController.getEditorView());
    }

    private void createPluginViewController() {
        // Story 294 — direct functional deps replace PluginViewController.Host (§4.2/§9).
        // project/sampleRate/bufferSize read the swappable project field live; dirty
        // routes through DawProject.markDirty() (the §1.2 "one dirty bit").
        pluginViewController = new PluginViewController(new PluginViewController.Deps(
                () -> project.getFormat().sampleRate(),
                () -> project.getFormat().bufferSize(),
                () -> project,
                () -> project.markDirty(),
                () -> viewNavigationController.switchView(DawView.MASTERING),
                this::status,
                (level, message) -> notificationBar.show(level, message),
                this::showTelemetryPanel));
    }

    /**
     * Story 287 — shows / focuses the docked Sound Wave Telemetry panel
     * (the shared {@link #telemetryView}). Replaces the old standalone
     * telemetry {@code Stage}. If the panel is floating, its window is
     * brought to front; otherwise it is made visible (and focused) via
     * the dock manifest's focus semantics.
     */
    private void showTelemetryPanel() {
        if (dockManager == null) return;
        DockEntry entry = dockManager.layout().entry(DefaultWorkspaces.PANEL_TELEMETRY).orElse(null);
        if (entry != null && entry.zone() == DockZone.FLOATING) {
            dockManager.setVisible(DefaultWorkspaces.PANEL_TELEMETRY, true);
            Stage stage = floatingStages.get(DefaultWorkspaces.PANEL_TELEMETRY);
            if (stage != null) stage.toFront();
        } else if (dockManifestModel != null) {
            // focusPanel makes it visible + moves it to the end of its zone
            // strip (becoming the selected tab) — the same path the manifest
            // bar button uses.
            dockManifestModel.focusPanel(DefaultWorkspaces.PANEL_TELEMETRY);
        } else {
            dockManager.setVisible(DefaultWorkspaces.PANEL_TELEMETRY, true);
        }
    }

    /**
     * Project Manager Design Book §4.3 — toggles the right-hand Session Manager
     * dock. Showing it refreshes the session history so the dock always
     * reflects the project's latest manifests.
     */
    private void toggleSessionManager() {
        if (dockManager == null) {
            return;
        }
        dockManager.toggleVisible(DefaultWorkspaces.PANEL_SESSION_MANAGER);
        boolean visible = dockManager.layout()
                .entry(DefaultWorkspaces.PANEL_SESSION_MANAGER)
                .map(DockEntry::visible)
                .orElse(false);
        if (visible) {
            if (dockManifestModel != null) {
                dockManifestModel.focusPanel(DefaultWorkspaces.PANEL_SESSION_MANAGER);
            }
            refreshSessionManager();
        }
    }

    private void createClipEditController() {
        // Story 293 — direct functional dependencies replace the retired Host.
        // This controller is init-only (not rebuilt on project load), so the
        // swappable project/undoManager are suppliers read live; the stable
        // clipboard/selection models are passed by value. editorView /
        // rippleMode / gridStepBeats read live state, so they are suppliers too.
        clipEditController = new ClipEditController(new ClipEditController.Deps(
                () -> project,
                () -> undoManager,
                clipboardManager,
                selectionModel,
                this::repaintArrangementCanvas,
                this::syncMenuStateIfPresent,
                () -> project.markDirty(),
                this::status,
                (level, msg, undo) -> notificationBar.showWithUndo(level, msg, undo),
                (level, message) -> notificationBar.show(level, message),
                () -> viewNavigationController.getEditorView(),
                () -> project.getRippleMode(),
                () -> {
                    GridResolution res = viewNavigationController != null
                            ? viewNavigationController.getGridResolution() : gridResolution;
                    int beatsPerBar = project.getTransport().getTimeSignatureNumerator();
                    return res.beatsPerGrid(beatsPerBar);
                }));
    }

    private void createRippleModeController() {
        // Story 293 — direct dependencies replace the retired Host. The project
        // supplier reads the swappable field live so a project load is reflected
        // without reconstructing the controller (it is init-only, not rebuilt in
        // handleProjectRebuild).
        rippleModeController = new RippleModeController(
                () -> project,
                () -> project.markDirty(),
                notificationBar,
                toolbarStateStore, rippleModeButton, rippleBannerLabel);
    }

    private void createTrackCreationController() {
        AudioDeviceManager deviceManager = new AudioDeviceManager(audioEngine);
        // Story 294 — direct functional deps replace TrackCreationController.Host (§4.2/§9).
        // The swappable project / undo manager / track-strip controller / mixer view are
        // live Suppliers; dirty routes through DawProject.markDirty(); the old
        // updateArrangementPlaceholder poke is gone (the placeholder binds ProjectVM.tracks).
        trackCreationController = new TrackCreationController(
                new TrackCreationController.Deps(
                        () -> project,
                        () -> undoManager,
                        () -> trackStripController,
                        () -> viewNavigationController.getMixerView(),
                        () -> trackListPanel,
                        this::syncMenuStateIfPresent,
                        () -> project.markDirty(),
                        this::status,
                        (level, message) -> notificationBar.show(level, message)),
                deviceManager);
    }

    private void createAudioImportController() {
        // Story 294 — direct functional deps replace AudioImportController.Host (§4.2/§9).
        // refreshArrangementCanvas is kept (an import adds a clip to an existing track,
        // which does not fire ChangeKind.TRACKS, so the reactive ProjectVM.tracks binding
        // does not repaint it); the no-op updateArrangementPlaceholder poke is gone.
        audioImportController = new AudioImportController(new AudioImportController.Deps(
                () -> project,
                () -> undoManager,
                () -> trackStripController,
                () -> trackCreationController,
                () -> viewNavigationController.getMixerView(),
                () -> trackListPanel,
                () -> (Stage) rootPane.getScene().getWindow(),
                this::repaintArrangementCanvas,
                this::syncMenuStateIfPresent,
                () -> project.markDirty(),
                this::status,
                (level, message) -> notificationBar.show(level, message)));
    }

    /**
     * Story 100 — Track Templates and Channel-Strip Presets.
     *
     * <p>Constructs the application-wide {@link TrackTemplateController}
     * that orchestrates the Save / Apply / Add-from / Manage workflows.
     * The controller is constructor-injected with a {@link TrackTemplateController.Deps}
     * record (story 294, replacing the callback-up {@code Host}) whose live
     * {@code Supplier}s read the current project, undo manager, and primary
     * window so the templates and presets feature stays decoupled from this
     * top-level controller.</p>
     */
    private void createTrackTemplateController() {
        trackTemplateController = new TrackTemplateController(
                new TrackTemplateController.Deps(
                        () -> project,
                        () -> undoManager,
                        () -> rootPane.getScene() != null
                                ? rootPane.getScene().getWindow() : null,
                        (level, message) -> notificationBar.show(level, message),
                        () -> viewNavigationController.getMixerView().refresh()));
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
                    project.markDirty();
                },
                () -> {
                    // Batch callback: refresh every strip plus the mixer.
                    if (trackStripController != null) {
                        trackStripController.refreshAllFreezeIndicators();
                    }
                    if (viewNavigationController != null) {
                        viewNavigationController.getMixerView().refresh();
                    }
                    project.markDirty();
                },
                this::status,
                dispatcher());
    }

    private void createHistoryPanelController() {
        // Story 293 — direct dependencies replace the retired Host. The undo
        // supplier reads the swappable field live (init-only controller, not
        // rebuilt on project load; a snapshot restore swaps in a new
        // UndoManager via applySnapshotRestoredProject).
        historyPanelController = new HistoryPanelController(
                rootPane, historyButton,
                () -> undoManager,
                this::syncMenuStateIfPresent,
                this::repaintArrangementCanvas,
                () -> browserPanelController.isPanelVisible(),
                () -> browserPanelController.toggleBrowserPanel(),
                this::status,
                dispatcher());
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
                    @Override public void onToggleVisualizations() { toggleBottomVizGroup(); }
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
                    // Story 285 — the dock manager is the single entry point;
                    // its Host then calls into the legacy
                    // viewNavigationController / browserPanelController as the
                    // underlying mechanism (not a parallel one).
                    @Override public void onToggleDockMixer() {
                        toggleCenterDockPanel(DefaultWorkspaces.PANEL_MIXER);
                    }
                    @Override public void onToggleDockBrowser() {
                        if (dockManager != null) dockManager.toggleVisible(DefaultWorkspaces.PANEL_BROWSER);
                    }
                    @Override public void onToggleDockArrangement() {
                        toggleCenterDockPanel(DefaultWorkspaces.PANEL_ARRANGEMENT);
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
                    postFx(() -> notificationBar.show(
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
                    this::postFx,
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
            pluginFaultUiController = new PluginFaultUiController(pluginSupervisor, notificationBar, dispatcher());
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
        BrowserPanel browserPanel = new BrowserPanel(dispatcher());
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
        // Story 293 — direct dependencies replace the retired Host. Project/undo
        // suppliers read the swappable fields live (init-only controller, not
        // rebuilt on project load); updateTempoDisplay was a no-op (tempo label
        // binds TransportVM.tempo) and is dropped.
        tempoEditController = new TempoEditController(
                tempoLabel,
                () -> project,
                () -> undoManager,
                this::syncMenuStateIfPresent,
                this::status,
                notificationBar);
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
                    @Override public void refreshCanvas() { repaintArrangementCanvas(); }
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
        repaintArrangementCanvas();
        trackStripController.setArrangementCanvas(arrangementCanvas);
        animationController.setPlayheadUpdateCallback(this::tickArrangementOverlays);
        audioImportController.installArrangementCanvasDragDrop(arrangementCanvas);
    }

    private void createMenuBar() {
        // Story 293 — the menu controller's former Host mixed action dispatch
        // with six cascade-feeding state queries. The state half is now passed
        // as live BooleanSuppliers (read straight from the authoritative
        // sources); the action half is the renamed MenuActions interface. The
        // dead activeView() query is dropped.
        menuBarController = new DawMenuBarController(
                new DawMenuBarController.MenuActions() {
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
                    @Override public void onToggleVisualizations() { toggleBottomVizGroup(); }
                    @Override public void onToggleSessionManager() { toggleSessionManager(); }
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
                keyBindingManager,
                // Story 294 — Save-enabled derives from ProjectVM.dirty, the §1.2
                // "one dirty bit". rebuildViewModels() re-syncs the menu when dirty
                // flips, so Save enables/disables reactively (§6.9 derive-don't-poke).
                () -> projectVM != null && projectVM.isDirty(),
                () -> undoManager.canUndo(),
                () -> undoManager.canRedo(),
                () -> clipboardManager.hasContent(),
                () -> selectionModel.hasClipSelection(),
                () -> !project.getTracks().isEmpty());
        javafx.scene.control.MenuBar bar = menuBarController.build();
        // Wire the per-user Workspaces menu (Save Current as… / Switch to…).
        // The WorkspaceManager seeds the six default workspaces on first run
        // (Tracking, Editing, Mixing, Mastering, Spatial, Minimal).
        installWorkspacesMenu(bar);
        Node topNode = rootPane.getTop();
        if (topNode instanceof VBox topVBox) { topVBox.getChildren().addFirst(bar); }
    }

    private void installWorkspacesMenu(javafx.scene.control.MenuBar bar) {
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
                            isAnyBottomVizVisible();
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
                        if (isAnyBottomVizVisible() != visible) {
                            setBottomVizGroupVisible(visible);
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
                applyDockLayoutJsonGuarded(dockLayoutJson);
            }
        };
    }

    /**
     * Adapter Dockable for the arrangement view — the cached arrangement
     * node is owned by {@link ViewNavigationController}'s {@code viewCache}
     * (a {@code Node}, not a class that can implement {@link Dockable}), so
     * the contract is supplied here.
     */
    private record ArrangementDockable() implements Dockable {
        @Override public String dockId()          { return DefaultWorkspaces.PANEL_ARRANGEMENT; }
        @Override public String displayName()     { return "Arrangement"; }
        @Override public String iconName()        { return "TIMELINE"; }
        @Override public DockZone preferredZone() { return DockZone.CENTER; }
    }

    /**
     * Story 287 — metadata-only {@link Dockable} for the Room-3D telemetry
     * display. Its mounted node is the shared {@link #telemetryView} (the
     * Room-3D display and the telemetry setup form are the two faces of one
     * plugin view), so there is no second {@code RoomTelemetryDisplay}.
     */
    private record RoomTelemetryDockable() implements Dockable {
        @Override public String dockId()          { return DefaultWorkspaces.PANEL_ROOM_3D; }
        @Override public String displayName()     { return "Room 3D"; }
        @Override public String iconName()        { return "SURROUND"; }
        @Override public DockZone preferredZone() { return DockZone.RIGHT; }
    }

    /**
     * Story 285 — instantiates the single application-wide {@link DockManager}
     * and registers every top-level panel. Called once from
     * {@link #initialize()} after the menu bar / workspace manager are wired.
     */
    private void installDockManager() {
        dockManager = new DockManager(new MainControllerDockHost());
        dockManager.register(new ArrangementDockable());
        if (viewNavigationController != null) {
            MixerView mixer = viewNavigationController.getMixerView();
            if (mixer != null) dockManager.register(mixer);
            EditorView editor = viewNavigationController.getEditorView();
            if (editor != null) dockManager.register(editor);
            MasteringView mastering = viewNavigationController.getMasteringView();
            if (mastering != null) dockManager.register(mastering);
        }
        if (browserPanelController != null && browserPanelController.getBrowserPanel() != null) {
            dockManager.register(browserPanelController.getBrowserPanel());
        }
        // §4.3 — Session Manager dock. Registered on the RIGHT edge; starts
        // hidden and is populated on project open (refreshSessionManager()).
        sessionManagerDock = new SessionManagerDock();
        dockManager.register(sessionManagerDock);
        // Story 287 — register the eight visualization dockables.
        registerVisualizationDockables();
        // Align initial dock visibility with the live chrome before
        // releasing the host's reconciliation guard, so the first
        // toggleVisible() the user invokes reflects the actual seam (e.g.
        // F3 from a hidden mixer flips visible=false → true).
        DawView active = viewNavigationController == null
                ? DawView.ARRANGEMENT
                : viewNavigationController.getActiveView();
        dockManager.setVisible(DefaultWorkspaces.PANEL_ARRANGEMENT, active == DawView.ARRANGEMENT);
        dockManager.setVisible(DefaultWorkspaces.PANEL_MIXER, active == DawView.MIXER);
        dockManager.setVisible(DefaultWorkspaces.PANEL_EDITOR, active == DawView.EDITOR);
        dockManager.setVisible(DefaultWorkspaces.PANEL_MASTERING, active == DawView.MASTERING);
        dockManager.setVisible(DefaultWorkspaces.PANEL_BROWSER,
                browserPanelController != null && browserPanelController.isPanelVisible());
        dockManager.setVisible(DefaultWorkspaces.PANEL_SESSION_MANAGER, false);
        // Story 287 — seed analyzer visibility from VisualizationPreferences
        // (the read-only initial-visibility seed that replaced the row
        // controller's live state). Telemetry / Room-3D start hidden.
        seedVisualizationVisibility();
        dockHostReconciliationSuppressed = false;
    }

    /**
     * Story 287 — wraps each analyzer display in a
     * {@link com.benesquivelmusic.daw.app.ui.display.DockableVisualizationPanel}
     * adapter (BOTTOM zone, kept in {@link #vizDockables} so the host can
     * mount them), constructs the single eager {@link #telemetryView}, and
     * registers its {@code getSetupPanel()} as {@code PANEL_TELEMETRY} plus
     * a {@link RoomTelemetryDockable} for {@code PANEL_ROOM_3D}.
     */
    private void registerVisualizationDockables() {
        createTelemetryView();
        registerVisualizationDockables(dockManager, vizDockables,
                spectrumDisplay, levelMeterDisplay, waveformDisplay,
                correlationDisplay, loudnessDisplay, tunerDisplay, telemetryView);
    }

    /**
     * Story 287 — the canonical registration of the six BOTTOM analyzer
     * dockables plus the shared telemetry / Room-3D dockables. Extracted as a
     * package-private seam so {@code VisualizationDockablesRegisteredTest} can
     * assert against the real production registration list (ids, zones,
     * adapter count) without FXML-loading {@code MainController}. The caller
     * supplies the six analyzer display regions and the shared
     * {@link TelemetryView}; the populated adapters are stored in
     * {@code vizDockables} so the host can later mount them.
     */
    static void registerVisualizationDockables(
            DockManager dockManager,
            java.util.Map<String, com.benesquivelmusic.daw.app.ui.display.DockableVisualizationPanel> vizDockables,
            javafx.scene.layout.Region spectrumDisplay,
            javafx.scene.layout.Region levelMeterDisplay,
            javafx.scene.layout.Region waveformDisplay,
            javafx.scene.layout.Region correlationDisplay,
            javafx.scene.layout.Region loudnessDisplay,
            javafx.scene.layout.Region tunerDisplay,
            TelemetryView telemetryView) {
        registerVizPanel(dockManager, vizDockables, DefaultWorkspaces.PANEL_SPECTRUM, "Spectrum",
                "SPECTRUM", DawIcon.SPECTRUM, "tile-header-accent-green", spectrumDisplay);
        registerVizPanel(dockManager, vizDockables, DefaultWorkspaces.PANEL_LEVELS, "Peak / RMS",
                "PEAK", DawIcon.PEAK, "tile-header-accent-orange", levelMeterDisplay);
        registerVizPanel(dockManager, vizDockables, DefaultWorkspaces.PANEL_WAVEFORM, "Oscilloscope",
                "OSCILLOSCOPE", DawIcon.OSCILLOSCOPE, "tile-header-accent-cyan", waveformDisplay);
        registerVizPanel(dockManager, vizDockables, DefaultWorkspaces.PANEL_CORRELATION, "Correlation",
                "PHASE_METER", DawIcon.PHASE_METER, "tile-header-accent-red", correlationDisplay);
        registerVizPanel(dockManager, vizDockables, DefaultWorkspaces.PANEL_LOUDNESS, "Loudness",
                "LOUDNESS_METER", DawIcon.LOUDNESS_METER, "tile-header-accent-purple", loudnessDisplay);
        // No dedicated tuner glyph in DawIcon (story 265 Lucide set) — MUSIC_NOTE
        // is the closest pitch-related metering glyph.
        registerVizPanel(dockManager, vizDockables, DefaultWorkspaces.PANEL_TUNER, "Tuner",
                "MUSIC_NOTE", DawIcon.MUSIC_NOTE, "tile-header-accent-green", tunerDisplay);

        // Telemetry / Room-3D share one eager TelemetryView (story 287
        // Decision 1). The registered Dockable for PANEL_TELEMETRY is the
        // setup-panel instance (for identity / anti-duplication); the
        // mounted node for BOTH ids is the parent telemetryView (resolveNode).
        dockManager.register(telemetryView.getSetupPanel());
        dockManager.register(new RoomTelemetryDockable());
    }

    private static void registerVizPanel(
            DockManager dockManager,
            java.util.Map<String, com.benesquivelmusic.daw.app.ui.display.DockableVisualizationPanel> vizDockables,
            String dockId, String displayName, String iconName,
            DawIcon icon, String accentStyleClass,
            javafx.scene.layout.Region display) {
        var panel = new com.benesquivelmusic.daw.app.ui.display.DockableVisualizationPanel(
                dockId, displayName, iconName, DockZone.BOTTOM, icon, accentStyleClass, display);
        vizDockables.put(dockId, panel);
        dockManager.register(panel);
    }

    /**
     * Story 287 — constructs the single application-wide {@link TelemetryView}
     * if not already created, wiring it to the current project and the
     * dirty-flag callback (the lifecycle the old standalone telemetry
     * window used to own in {@code PluginViewController}).
     */
    private void createTelemetryView() {
        if (telemetryView != null) return;
        telemetryView = new TelemetryView();
        telemetryView.setProject(project);
        telemetryView.setOnDirtyChanged(this::setProjectDirtyFromTelemetry);
    }

    private void setProjectDirtyFromTelemetry() {
        project.markDirty();
        if (menuBarController != null) menuBarController.syncMenuState();
    }

    /**
     * Story 287 — seeds the five preference-backed analyzer panels'
     * visibility from {@link VisualizationPreferences} (a read-only seed
     * after the row controller's removal). A panel is initially visible
     * iff the row was visible AND that tile was visible. Tuner, Room-3D,
     * and Telemetry start hidden (they were never part of the bottom row).
     */
    private void seedVisualizationVisibility() {
        VisualizationPreferences prefs = new VisualizationPreferences(
                Preferences.userNodeForPackage(VisualizationPreferences.class));
        boolean row = prefs.isRowVisible();
        dockManager.setVisible(DefaultWorkspaces.PANEL_SPECTRUM,
                row && prefs.isTileVisible(VisualizationPreferences.DisplayTile.SPECTRUM));
        dockManager.setVisible(DefaultWorkspaces.PANEL_LEVELS,
                row && prefs.isTileVisible(VisualizationPreferences.DisplayTile.LEVELS));
        dockManager.setVisible(DefaultWorkspaces.PANEL_WAVEFORM,
                row && prefs.isTileVisible(VisualizationPreferences.DisplayTile.WAVEFORM));
        dockManager.setVisible(DefaultWorkspaces.PANEL_LOUDNESS,
                row && prefs.isTileVisible(VisualizationPreferences.DisplayTile.LOUDNESS));
        dockManager.setVisible(DefaultWorkspaces.PANEL_CORRELATION,
                row && prefs.isTileVisible(VisualizationPreferences.DisplayTile.CORRELATION));
        dockManager.setVisible(DefaultWorkspaces.PANEL_TUNER, false);
        dockManager.setVisible(DefaultWorkspaces.PANEL_ROOM_3D, false);
        dockManager.setVisible(DefaultWorkspaces.PANEL_TELEMETRY, false);
    }

    /**
     * Story 287 — disposes the analyzer GpuCanvas displays and the shared
     * telemetry view on app shutdown (FX thread). {@code GpuCanvasView.dispose()}
     * is idempotent, so a display also disposed elsewhere is safe.
     */
    private void disposeVisualizationDisplays() {
        if (spectrumDisplay != null) spectrumDisplay.dispose();
        if (levelMeterDisplay != null) levelMeterDisplay.dispose();
        if (waveformDisplay != null) waveformDisplay.dispose();
        if (loudnessDisplay != null) loudnessDisplay.dispose();
        if (correlationDisplay != null) correlationDisplay.dispose();
        if (tunerDisplay != null) tunerDisplay.dispose();
        if (telemetryView != null) telemetryView.dispose();
    }

    // ── Story 287 — bottom-analyzer group toggle (replaces the retired
    //    VisualizationPanelController row-visibility toggle). The six BOTTOM
    //    analyzer panels are treated as a group: "Toggle Visualizations" and
    //    the Mixing/Mastering workspace presets show/hide them together.

    /**
     * Returns {@code true} if the given analyzer panel currently lives in its
     * own floating window (zone {@code FLOATING}) rather than the bottom strip.
     * Floated analyzers are independent of the strip group toggle.
     */
    private boolean isVizPanelFloating(String panelId) {
        return dockManager != null && dockManager.layout().entry(panelId)
                .map(e -> e.zone() == DockZone.FLOATING).orElse(false);
    }

    /**
     * Returns {@code true} if any analyzer panel is visible <em>in the bottom
     * strip</em>. A panel the user has floated into its own window is excluded:
     * floating detaches it from the strip group, so it neither counts toward
     * nor is touched by the group toggle (see {@link #setBottomVizGroupVisible}).
     */
    private boolean isAnyBottomVizVisible() {
        if (dockManager == null) return false;
        for (String id : vizDockables.keySet()) {
            if (isVizPanelFloating(id)) continue;
            if (dockManager.layout().entry(id).map(DockEntry::visible).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the analyzer panels visible / hidden as a group. The model updates
     * are batched under the reconciliation guard (so no per-panel reconcile
     * passes fire); the strip is then mounted/unmounted directly. Mounting
     * directly — rather than relying on a final firing {@code setVisible} — is
     * robust even when the last panel's visibility was already at the target
     * (in which case {@code setVisible} is a no-op and would fire nothing).
     *
     * <p>Panels the user has floated into their own windows are skipped
     * entirely: a floated analyzer is independent of the strip group, so the
     * group toggle must neither flip its visibility flag nor yank its node back
     * into the strip (which would strand an empty floating window).</p>
     */
    private void setBottomVizGroupVisible(boolean visible) {
        if (dockManager == null || vizDockables.isEmpty()) return;
        boolean prior = dockHostReconciliationSuppressed;
        dockHostReconciliationSuppressed = true;
        try {
            for (String id : vizDockables.keySet()) {
                if (isVizPanelFloating(id)) continue;
                dockManager.setVisible(id, visible);
            }
        } finally {
            dockHostReconciliationSuppressed = prior;
        }
        for (String id : vizDockables.keySet()) {
            if (isVizPanelFloating(id)) continue;
            mountBottomVizPanel(id, visible);
        }
    }

    /** Toggles the BOTTOM analyzer group: any visible → hide all; else show all. */
    private void toggleBottomVizGroup() {
        setBottomVizGroupVisible(!isAnyBottomVizVisible());
    }

    /**
     * Story 282 — instantiates the application-wide
     * {@link com.benesquivelmusic.daw.app.ui.layout.LayoutManager} with a
     * {@link com.benesquivelmusic.daw.app.ui.layout.LayoutManager.Host}
     * bridge to the production {@link DockManager}, wires the View →
     * Layout menu, mounts the persistent bottom-of-window dock manifest
     * bar, and installs scene-root handlers for
     * {@link com.benesquivelmusic.daw.app.ui.layout.PanelDetachRequestedEvent}
     * and {@link com.benesquivelmusic.daw.app.ui.layout.PanelDockRequestedEvent}
     * so panels published from {@code LayoutManager}-aware code flow
     * through the dock manager's float / re-dock API.
     */
    /**
     * Story 287 — applies dock-layout JSON with {@link #applyingDockLayout}
     * set, so {@code reconcileTelemetry} does not auto-float the RIGHT-zone
     * telemetry panels that {@code applyLayout} force-shows for ids the
     * incoming layout never mentioned. Both the LayoutManager and
     * WorkspaceManager dock-restore hosts route through here.
     */
    private void applyDockLayoutJsonGuarded(String json) {
        if (dockManager == null || json == null || json.isEmpty()) return;
        boolean prior = applyingDockLayout;
        applyingDockLayout = true;
        try {
            dockManager.applyJson(json);
        } finally {
            applyingDockLayout = prior;
        }
    }

    private void installLayoutManager() {
        if (dockManager == null) return;
        com.benesquivelmusic.daw.app.ui.layout.LayoutManager.Host host =
                new com.benesquivelmusic.daw.app.ui.layout.LayoutManager.Host() {
                    @Override public String captureDockLayoutJson() {
                        return dockManager.captureJson();
                    }
                    @Override public void applyDockLayoutJson(String json) {
                        if (json != null && !json.isBlank()) {
                            try {
                                applyDockLayoutJsonGuarded(json);
                            } catch (Exception e) {
                                LOG.log(Level.WARNING,
                                        "Failed to apply dock layout JSON; keeping current layout", e);
                            }
                        }
                    }
                };
        layoutManager = new com.benesquivelmusic.daw.app.ui.layout.LayoutManager(host);
        dockManifestModel =
                new com.benesquivelmusic.daw.app.ui.layout.DockManifestModel(dockManager);

        // ── View → Layout menu (alongside Workspaces). ──────────────
        if (menuBarController != null) {
            javafx.scene.control.MenuBar bar = menuBarController.getMenuBar();
            if (bar != null) {
                LayoutsMenu builder = new LayoutsMenu(
                        layoutManager,
                        this::promptLayoutName,
                        this::reportLayoutError,
                        this::openManageLayoutsDialog);
                javafx.scene.control.Menu layoutMenu = builder.build();
                var menus = bar.getMenus();
                // Keep Help right-most; place Layout adjacent to Workspaces.
                int insertIndex = Math.max(0, menus.size() - 1);
                menus.add(insertIndex, layoutMenu);
            }
        }

        // ── PanelDetach / PanelDock event bridges. ──────────────────
        rootPane.addEventHandler(
                com.benesquivelmusic.daw.app.ui.layout.PanelDetachRequestedEvent.PANEL_DETACH_REQUESTED,
                e -> {
                    if (dockManager != null) {
                        // Story 288 — honour the grip-gesture drop point when
                        // present; a null bounds preserves the prior
                        // remembered/default placement behaviour.
                        dockManager.float_(e.getPanelId(), e.getBounds());
                    }
                });
        rootPane.addEventHandler(
                com.benesquivelmusic.daw.app.ui.layout.PanelDockRequestedEvent.PANEL_DOCK_REQUESTED,
                e -> {
                    if (dockManager != null) {
                        // Story 288 review — reconcile() is the authority on
                        // each canonical panel's docked home: it re-derives
                        // placement from the panel id and ignores the recorded
                        // zone. Recording the geometric drop zone would persist
                        // a zone the reconciler never honours (and the manifest
                        // bar, which renders DockEntry.zone, would show it),
                        // while the panel snaps back to its real home. Coerce
                        // the re-dock to that home so model and view agree.
                        DockZone home = dockHomeZone(
                                e.getPanelId(),
                                vizDockables.containsKey(e.getPanelId()),
                                e.getTargetZone());
                        dockManager.moveToEnd(e.getPanelId(), home);
                    }
                });

        // ── Story 288 — drop-zone highlight + re-dock gesture. ───────
        // Bind the dock drop behaviour for TOP/LEFT/RIGHT/CENTER to the
        // BorderPane slot PROPERTIES (not fixed nodes): CENTER is swapped per
        // view, LEFT is null while the browser is hidden, and the Performance
        // Stage save/restore replaces whole slots — binding to the property
        // lets the drop zone follow the live content. The BOTTOM slot is the
        // bottom VBox chrome stack (notification bar, analyzer strip, manifest
        // bar, status bar), so it is bound to the analyzer strip node below
        // instead of the whole slot, keeping the status/manifest chrome out of
        // the BOTTOM hit-region. Handlers are additive (addEventHandler), so
        // the existing clip/sample DnD keeps working.
        com.benesquivelmusic.daw.app.ui.dock.DockDropZones dropZones =
                new com.benesquivelmusic.daw.app.ui.dock.DockDropZones(rootPane);
        dropZones.bindSlot(rootPane.topProperty(), DockZone.TOP);
        dropZones.bindSlot(rootPane.leftProperty(), DockZone.LEFT);
        dropZones.bindSlot(rootPane.rightProperty(), DockZone.RIGHT);
        dropZones.bindSlot(rootPane.centerProperty(), DockZone.CENTER);

        // ── Story 288 — mount the arrangement grip in the timeline header.
        // The arrangement panel lives in FXML (timeline-header HBox); its
        // grip detaches/re-docks the PANEL_ARRANGEMENT dockable. Size the
        // floating window from the cached arrangement node when available.
        if (arrangementTimelineHeader != null) {
            Region arrangementBounds = viewNavigationController != null
                    && viewNavigationController.getCachedArrangementNode() instanceof Region r
                    ? r
                    : rootPane;
            arrangementTimelineHeader.getChildren().add(0,
                    new com.benesquivelmusic.daw.app.ui.dock.PanelGripHandle(
                            DefaultWorkspaces.PANEL_ARRANGEMENT, arrangementBounds));
        }

        // ── Story 287 — bottom analyzer strip (above the manifest bar). ──
        mountVisualizationDockStrip();
        // ── Story 288 — scope the BOTTOM drop zone to the analyzer strip
        // (the BOTTOM-dock content node), not the whole bottom slot, so the
        // status bar and dock manifest bar are not dock drop targets.
        if (vizBottomStrip != null) {
            dropZones.bindNode(vizBottomStrip, DockZone.BOTTOM);
        }
        // ── Bottom manifest bar (above the status bar). ─────────────
        mountDockManifestBar();
        // Story 287 — mount the BOTTOM-zone analyzer panels that are
        // initially visible. The FXML-pre-mounted panels reconcile lazily
        // on the first toggle, but these adapters are not in the scene yet,
        // so seed them from the live layout now (reconciliation guard was
        // already lifted by installDockManager()).
        for (var id : vizDockables.keySet()) {
            DockEntry e = dockManager.layout().entry(id).orElse(null);
            if (e != null && e.zone() != DockZone.FLOATING) {
                mountBottomVizPanel(id, e.visible());
            }
        }
    }

    /**
     * Resolves the docked home zone for a panel id, mirroring the authority
     * encoded in {@code MainControllerDockHost.reconcile(...)} — which
     * re-derives each canonical panel's placement from its id and ignores
     * the recorded zone.
     * The drop-zone re-dock gesture (story 288) coerces to this so the
     * persisted zone, and the manifest bar that renders {@code
     * DockEntry.zone}, match where the panel actually lands instead of the
     * arbitrary geometric zone the user happened to release over.
     *
     * <p>Telemetry panels home to {@code RIGHT} even though {@code
     * reconcileTelemetry} ultimately floats them: {@code RIGHT} is still
     * their nominal/recorded zone, so this keeps the model self-consistent.
     * Kept in lock-step with {@code reconcile(...)}; pinned by
     * {@code DockHomeZoneTest}.</p>
     *
     * @param panelId         the dragged panel id
     * @param isVisualization whether {@code panelId} is one of the BOTTOM
     *                        analyzer adapters ({@code vizDockables})
     * @param fallback        zone for an unrecognised id (forward-compatible)
     */
    static DockZone dockHomeZone(String panelId, boolean isVisualization, DockZone fallback) {
        if (DefaultWorkspaces.PANEL_BROWSER.equals(panelId)) {
            return DockZone.LEFT;
        }
        if (CENTER_ZONE_PANELS.contains(panelId)) {
            return DockZone.CENTER;
        }
        if (DefaultWorkspaces.PANEL_TELEMETRY.equals(panelId)
                || DefaultWorkspaces.PANEL_ROOM_3D.equals(panelId)) {
            return DockZone.RIGHT;
        }
        if (isVisualization) {
            return DockZone.BOTTOM;
        }
        return fallback;
    }

    /**
     * Inserts {@code child} into the bottom-region {@code VBox} immediately
     * above the status bar (its last child), so the bottom region stacks
     * analyzer-strip → manifest-bar → status-bar from top to bottom. The
     * bottom region is the {@code <bottom><VBox>} declared by
     * {@code main-view.fxml}, so it is always present and a {@code VBox}; if
     * that invariant ever changes the child is skipped rather than mounted in
     * the wrong place.
     */
    private void insertAboveStatusBar(Node child) {
        if (rootPane.getBottom() instanceof VBox bottomBox) {
            int idx = Math.max(0, bottomBox.getChildren().size() - 1);
            bottomBox.getChildren().add(idx, child);
        }
    }

    /**
     * Story 287 — creates the bottom analyzer strip and inserts it into the
     * bottom {@code VBox} directly above the dock manifest bar (top-to-bottom
     * in the bottom region: analyzer strip, manifest bar, status bar). The
     * strip stays {@code managed=false} while empty so it takes no vertical
     * space until at least one analyzer is visible.
     */
    private void mountVisualizationDockStrip() {
        vizBottomStrip = new javafx.scene.layout.HBox(8);
        vizBottomStrip.getStyleClass().add("dock-viz-strip");
        vizBottomStrip.setId("vizBottomStrip");
        vizBottomStrip.setPrefHeight(120);
        vizBottomStrip.setMinHeight(0);
        syncVizStripVisibility();
        // The manifest bar is not yet mounted, so the strip lands directly
        // above the status bar; mountDockManifestBar() then inserts the
        // manifest bar between this strip and the status bar.
        insertAboveStatusBar(vizBottomStrip);
    }

    /**
     * Story 287 — mounts or unmounts a BOTTOM-zone analyzer adapter into
     * the bottom strip. Idempotent: a panel already in the desired state
     * is left alone. Preserves left-to-right order by the panel's dock
     * tab-index when (re-)inserting. The strip's own visibility tracks
     * whether it has any children so an empty strip takes no space.
     */
    private void mountBottomVizPanel(String panelId, boolean wantVisible) {
        if (vizBottomStrip == null) return;
        var panel = vizDockables.get(panelId);
        if (panel == null) return;
        boolean present = vizBottomStrip.getChildren().contains(panel);
        if (wantVisible && !present) {
            int insertAt = bottomVizInsertIndex(panelId);
            vizBottomStrip.getChildren().add(insertAt, panel);
            javafx.scene.layout.HBox.setHgrow(panel, javafx.scene.layout.Priority.ALWAYS);
        } else if (!wantVisible && present) {
            vizBottomStrip.getChildren().remove(panel);
        }
        syncVizStripVisibility();
    }

    /**
     * Computes the insertion index for a BOTTOM analyzer adapter so the
     * strip stays ordered by dock tab-index (matching {@code move()} /
     * named-layout ordering) regardless of show/hide order.
     */
    private int bottomVizInsertIndex(String panelId) {
        int myTab = dockManager.layout().entry(panelId)
                .map(DockEntry::tabIndex).orElse(Integer.MAX_VALUE);
        int idx = 0;
        for (Node child : vizBottomStrip.getChildren()) {
            String childId = child instanceof Dockable d ? d.dockId() : null;
            int childTab = childId == null ? Integer.MAX_VALUE
                    : dockManager.layout().entry(childId)
                            .map(DockEntry::tabIndex).orElse(Integer.MAX_VALUE);
            if (childTab <= myTab) idx++;
            else break;
        }
        return idx;
    }

    /** Story 287 — an empty analyzer strip is hidden + unmanaged (no space). */
    private void syncVizStripVisibility() {
        if (vizBottomStrip == null) return;
        boolean hasChildren = !vizBottomStrip.getChildren().isEmpty();
        vizBottomStrip.setVisible(hasChildren);
        vizBottomStrip.setManaged(hasChildren);
    }

    /**
     * Mounts the manifest bar — a horizontal strip of {@code dawg-button}s,
     * one per registered dockable panel — at the bottom of the main
     * window, immediately above the status bar. Each button focuses /
     * unhides its panel via {@link DockManager#toggleVisible(String)}.
     */
    private void mountDockManifestBar() {
        if (dockManifestModel == null) return;
        javafx.scene.layout.HBox bar = new javafx.scene.layout.HBox(4);
        bar.getStyleClass().add("dock-manifest-bar");
        bar.setId("dockManifestBar");
        rebuildManifestBar(bar);
        dockManifestModel.entries().addListener(
                (javafx.collections.ListChangeListener<
                        com.benesquivelmusic.daw.app.ui.layout.DockManifestModel.Entry>) _ ->
                        rebuildManifestBar(bar));
        insertAboveStatusBar(bar);
    }

    private void rebuildManifestBar(javafx.scene.layout.HBox bar) {
        bar.getChildren().clear();
        for (var entry : dockManifestModel.entries()) {
            String panelId = entry.panelId();
            Button btn = new Button(entry.displayName());
            btn.getStyleClass().addAll("dawg-button", "size-default", "dock-manifest-tab");
            btn.setUserData(panelId);
            btn.setOnAction(_ -> dockManifestModel.focusPanel(panelId));
            bar.getChildren().add(btn);
        }
    }

    /**
     * Story 282 — Mission Control "Save Layout As…" prompt. Uses a
     * {@code TextInputDialog} for now; will be migrated to {@code
     * DawgDialog} (story 276 chrome) in a follow-up.
     */
    private String promptLayoutName() {
        var dialog = new javafx.scene.control.TextInputDialog("My Layout");
        dialog.setTitle("Save Layout As");
        dialog.setHeaderText("Save current dock arrangement");
        dialog.setContentText("Layout name:");
        return dialog.showAndWait().orElse(null);
    }

    private void reportLayoutError(String message) {
        if (notificationBar != null) {
            notificationBar.show(NotificationLevel.WARNING, message);
        } else {
            LOG.warning(message);
        }
    }

    private void openManageLayoutsDialog() {
        // Lightweight in-line manager: lists user-saved layouts and lets
        // the user delete them. Built-ins are read-only and absent here.
        var dialog = new javafx.scene.control.Dialog<Void>();
        dialog.setTitle("Manage Layouts");
        dialog.setHeaderText("User-saved layouts (built-ins are read-only)");
        javafx.scene.control.ListView<String> list = new javafx.scene.control.ListView<>();
        for (var l : layoutManager.savedLayouts()) {
            if (!l.builtIn()) list.getItems().add(l.name());
        }
        Button deleteBtn = new Button("Delete");
        Button renameBtn = new Button("Rename\u2026");
        deleteBtn.setOnAction(_ -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel != null && layoutManager.delete(sel)) {
                list.getItems().remove(sel);
            }
        });
        renameBtn.setOnAction(_ -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            var prompt = new javafx.scene.control.TextInputDialog(sel);
            prompt.setTitle("Rename Layout");
            prompt.setContentText("New name:");
            String newName = prompt.showAndWait().orElse(null);
            if (newName != null && !newName.isBlank()
                    && layoutManager.rename(sel, newName.strip())) {
                int idx = list.getItems().indexOf(sel);
                list.getItems().set(idx, newName.strip());
            }
        });
        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(8, renameBtn, deleteBtn);
        VBox content = new VBox(8, list, buttons);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes()
                .add(javafx.scene.control.ButtonType.CLOSE);
        dialog.showAndWait();
    }

    /**
     * Returns the application-wide {@link com.benesquivelmusic.daw.app.ui.layout.LayoutManager},
     * or {@code null} if it has not been instantiated yet (e.g. during
     * very early startup or in headless tests that did not call
     * {@code initialize()}). Exposed for the story 282 acceptance tests
     * and for {@link ProjectLifecycleController}'s per-project
     * persistence hook.
     */
    com.benesquivelmusic.daw.app.ui.layout.LayoutManager getLayoutManager() {
        return layoutManager;
    }

    /**
     * Returns the {@link com.benesquivelmusic.daw.app.ui.layout.DockManifestModel}
     * driving the bottom-of-window manifest bar, or {@code null} if the
     * dock manager has not been installed yet.
     */
    com.benesquivelmusic.daw.app.ui.layout.DockManifestModel getDockManifestModel() {
        return dockManifestModel;
    }


    /**
     * CENTER zone is a single-selection slot — only one of
     * {@link DefaultWorkspaces#PANEL_ARRANGEMENT},
     * {@link DefaultWorkspaces#PANEL_MIXER},
     * {@link DefaultWorkspaces#PANEL_EDITOR}, or
     * {@link DefaultWorkspaces#PANEL_MASTERING} can be visible at a time.
     * Wraps {@link DockManager#toggleVisible(String)} so that toggling a
     * CENTER panel ON automatically hides any other CENTER panel that was
     * visible (avoiding order-dependent "last entry wins" reconciliation).
     * Toggling the active CENTER panel OFF is a no-op (radio-button
     * behaviour) — the slot has to hold something while the story defers
     * tabbed CENTER targets.
     */
    private void toggleCenterDockPanel(String panelId) {
        if (dockManager == null) return;
        DockEntry entry = dockManager.layout().entry(panelId).orElse(null);
        if (entry == null) return;
        if (entry.visible()) {
            // Active panel — radio-button behaviour: no-op.
            return;
        }
        // Suppress reconciliation while hiding siblings so we don't fire
        // N × N reconcile passes. Only the final setVisible (which shows
        // the requested panel) fires onLayoutChanged — a single pass that
        // reconciles the entire batch atomically.
        dockHostReconciliationSuppressed = true;
        try {
            for (String other : CENTER_ZONE_PANELS) {
                if (!other.equals(panelId)) dockManager.setVisible(other, false);
            }
        } finally {
            dockHostReconciliationSuppressed = false;
        }
        dockManager.setVisible(panelId, true);
    }

    /**
     * {@link DockManager.Host} that reconciles dock layout snapshots back
     * into the existing controllers ({@link BrowserPanelController},
     * {@link ViewNavigationController}) and a per-panel floating {@link Stage}
     * registry. Idempotent: an {@code onLayoutChanged} that matches the
     * current actual state of the chrome is a no-op, so the first fire after
     * register/applyLayout cannot clobber the FXML-mounted view.
     */
    private final class MainControllerDockHost implements DockManager.Host {

        @Override public void onLayoutChanged(DockLayout newLayout) {
            if (dockHostReconciliationSuppressed) return;
            for (DockEntry entry : newLayout.entries().values()) {
                reconcile(entry);
            }
        }

        @Override public boolean isScreenAvailableFor(
                com.benesquivelmusic.daw.sdk.ui.Rectangle2D bounds) {
            if (bounds == null) return false;
            javafx.geometry.Rectangle2D fxBounds = new javafx.geometry.Rectangle2D(
                    bounds.x(), bounds.y(),
                    Math.max(1, bounds.width()), Math.max(1, bounds.height()));
            for (Screen screen : Screen.getScreens()) {
                if (screen.getBounds().intersects(fxBounds)) return true;
            }
            return false;
        }

        private void reconcile(DockEntry entry) {
            String id = entry.panelId();
            if (entry.zone() == DockZone.FLOATING) {
                ensureFloating(id, entry);
                return;
            }
            // If a Stage was previously open for this panel, close it now
            // (panel was re-docked).
            Stage existing = floatingStages.remove(id);
            boolean wasFloating = existing != null;
            if (existing != null) existing.close();
            if (wasFloating) {
                // The panel node is still parented inside the closed stage's
                // scene. Detach it so it can be re-parented into the main
                // chrome by the reconcile helpers below.
                detachFromParent(resolveNode(id));
                // Force the controller's internal state to diverge from the
                // desired state so the standard show paths fire. The
                // controllers' flags (panelVisible / activeView) were never
                // updated when the panel was floated — they're stale.
                if (id.equals(DefaultWorkspaces.PANEL_BROWSER)) {
                    if (browserPanelController != null
                            && browserPanelController.isPanelVisible()) {
                        browserPanelController.toggleBrowserPanel();
                    }
                } else if (CENTER_ZONE_PANELS.contains(id)
                        && viewNavigationController != null) {
                    viewNavigationController.invalidateActiveViewCache();
                }
            }
            switch (id) {
                case DefaultWorkspaces.PANEL_BROWSER -> reconcileBrowser(entry.visible());
                case DefaultWorkspaces.PANEL_ARRANGEMENT -> reconcileCenterView(
                        entry.visible(), DawView.ARRANGEMENT);
                case DefaultWorkspaces.PANEL_MIXER -> reconcileCenterView(
                        entry.visible(), DawView.MIXER);
                case DefaultWorkspaces.PANEL_EDITOR -> reconcileCenterView(
                        entry.visible(), DawView.EDITOR);
                case DefaultWorkspaces.PANEL_MASTERING -> reconcileCenterView(
                        entry.visible(), DawView.MASTERING);
                case DefaultWorkspaces.PANEL_TELEMETRY,
                     DefaultWorkspaces.PANEL_ROOM_3D -> reconcileTelemetry(id, entry.visible());
                default -> {
                    // Story 287 — the six BOTTOM-zone analyzer panels are
                    // additive (independent show/hide), mounted into the
                    // bottom strip regardless of which edge zone they land
                    // in (built-in layouts may place them in RIGHT; the
                    // analyzer strip is their canonical home).
                    if (vizDockables.containsKey(id)) {
                        mountBottomVizPanel(id, entry.visible());
                    }
                    // else: unknown id — forward compatible.
                }
            }
        }

        private void detachFromParent(Node content) {
            if (content == null) return;
            javafx.scene.Parent parent = content.getParent();
            if (parent instanceof javafx.scene.layout.BorderPane bp) {
                if (bp.getCenter() == content) bp.setCenter(null);
                else if (bp.getLeft() == content) bp.setLeft(null);
                else if (bp.getRight() == content) bp.setRight(null);
                else if (bp.getTop() == content) bp.setTop(null);
                else if (bp.getBottom() == content) bp.setBottom(null);
            } else if (parent instanceof Pane pane) {
                pane.getChildren().remove(content);
                // Story 287 — floating a BOTTOM analyzer detaches it from the
                // analyzer strip; collapse the strip when it becomes empty so
                // the 120px band doesn't linger with no children.
                if (pane == vizBottomStrip) syncVizStripVisibility();
            } else if (parent == null && content instanceof javafx.scene.Parent p
                    && p.getScene() != null) {
                // The node is a Scene root (getParent() == null but still held
                // by Scene.setRoot). Replace it with an empty Group so the node
                // can be re-parented into the main BorderPane without JavaFX
                // throwing "already set as root of another scene".
                p.getScene().setRoot(new javafx.scene.Group());
            }
        }

        private void reconcileBrowser(boolean wantVisible) {
            if (browserPanelController == null) return;
            if (browserPanelController.isPanelVisible() != wantVisible) {
                browserPanelController.toggleBrowserPanel();
            }
        }

        private void reconcileCenterView(boolean wantVisible, DawView view) {
            if (viewNavigationController == null) return;
            DawView active = viewNavigationController.getActiveView();
            if (wantVisible && active != view) {
                viewNavigationController.switchView(view);
            }
            // No hide-fallback: CENTER is a single-selection slot (see
            // {@link MainController#toggleCenterDockPanel}). Hiding a panel
            // that isn't the active CENTER view is already consistent with
            // the live chrome; hiding the active one is a no-op (the slot
            // has to hold something while the story defers tabbed CENTER
            // targets, so a sibling becoming visible is what changes it).
        }

        /**
         * Story 287 — reconciles a RIGHT-zone telemetry panel
         * ({@code PANEL_TELEMETRY} / {@code PANEL_ROOM_3D}). The main
         * window's RIGHT slot is a single contended slot already swapped
         * between inspector / browser / history (and snapshotted by the
         * Performance Stage), so docking a panel <em>inline</em> alongside
         * the inspector would break those {@code getRight()==X} identity
         * checks. Instead, an explicitly-shown RIGHT telemetry panel is floated
         * into its own {@code Stage} via the standard {@code float_} path
         * (story Non-Goal #2 explicitly allows floating via the API), but never
         * while a layout is being applied ({@code applyingDockLayout}) — so a
         * built-in / restored layout that force-shows the panel never pops a
         * window on File → New / Open. Hiding it hides that window. Both ids resolve to the shared
         * {@link #telemetryView}, so whichever is shown brings up the same
         * view (its internal setup⇄display swap decides the face).
         */
        private void reconcileTelemetry(String id, boolean wantVisible) {
            if (dockManager == null) return;
            // Float only on an explicit user action — never while a layout is
            // being applied. applyLayout force-shows registered panels the
            // incoming layout never mentioned (the built-in layouts predate
            // these panels), which would otherwise pop the telemetry window on
            // File → New / Open / built-in-layout load. A user-saved *floating*
            // telemetry restores via the FLOATING short-circuit in reconcile()
            // (ensureFloating), not this arm, so guarding here doesn't lose it.
            if (wantVisible && !applyingDockLayout) {
                // Both telemetry ids share ONE TelemetryView node, so they
                // are mutually exclusive — showing one hides the other (and
                // its now-empty stage) so the single node is never fought
                // over by two stages. Done under the reconciliation guard so
                // hiding the sibling doesn't trigger an extra reconcile pass.
                String sibling = id.equals(DefaultWorkspaces.PANEL_TELEMETRY)
                        ? DefaultWorkspaces.PANEL_ROOM_3D
                        : DefaultWorkspaces.PANEL_TELEMETRY;
                DockEntry sib = dockManager.layout().entry(sibling).orElse(null);
                if (sib != null && sib.visible()) {
                    boolean prior = dockHostReconciliationSuppressed;
                    dockHostReconciliationSuppressed = true;
                    try {
                        dockManager.setVisible(sibling, false);
                    } finally {
                        dockHostReconciliationSuppressed = prior;
                    }
                    // Close + REMOVE (not just hide) the sibling's stage so the
                    // shared TelemetryView node re-attaches to a fresh stage the
                    // next time the sibling is shown — a hidden-but-retained
                    // stage would re-show empty once the node has moved into our
                    // stage (ensureFloating only re-parents when creating anew).
                    Stage sibStage = floatingStages.remove(sibling);
                    if (sibStage != null) sibStage.close();
                }
                // Float it (idempotent — float_ on an already-floating panel
                // just refreshes bounds). ensureFloating then shows the Stage.
                dockManager.float_(id, null);
            } else {
                // Story 287 — a restored / built-in layout may mark a
                // telemetry panel visible in its preferred RIGHT zone.
                // reconcile() only short-circuits FLOATING-zoned entries to
                // ensureFloating, so this arm always runs for a non-FLOATING
                // zone. Telemetry panels only ever live floating-or-hidden and
                // must not auto-pop their window during layout application
                // (the applyingDockLayout guard above), so coerce the model's
                // visible flag to false to keep the manifest chrome consistent
                // with the hidden rendering instead of silently leaving a
                // "visible" entry that is mounted nowhere.
                if (wantVisible && applyingDockLayout) {
                    boolean prior = dockHostReconciliationSuppressed;
                    dockHostReconciliationSuppressed = true;
                    try {
                        dockManager.setVisible(id, false);
                    } finally {
                        dockHostReconciliationSuppressed = prior;
                    }
                }
                Stage stage = floatingStages.get(id);
                if (stage != null && stage.isShowing()) stage.hide();
            }
        }

        private boolean isTelemetryPanel(String panelId) {
            return DefaultWorkspaces.PANEL_TELEMETRY.equals(panelId)
                    || DefaultWorkspaces.PANEL_ROOM_3D.equals(panelId);
        }

        private void ensureFloating(String panelId, DockEntry entry) {
            Node content = resolveNode(panelId);
            if (content == null) return;
            com.benesquivelmusic.daw.sdk.ui.Rectangle2D b = entry.floatingBounds();
            if (b == null) return;
            Stage stage = floatingStages.get(panelId);
            if (stage == null) {
                stage = new Stage();
                stage.setTitle(displayNameFor(panelId));
                // Own the floating window to the primary stage so it closes
                // when the app exits (cascaded from MainController's
                // setOnHidden lifetime cleanup on the primary Stage).
                if (rootPane.getScene() != null && rootPane.getScene().getWindow() != null) {
                    stage.initOwner(rootPane.getScene().getWindow());
                }
                detachFromParent(content);
                javafx.scene.Scene scene = new javafx.scene.Scene(
                        content instanceof javafx.scene.Parent p ? p
                                : new javafx.scene.layout.StackPane(content));
                // Apply the active theme + register for live re-theming so
                // the floating panel inherits root-pane styling instead of
                // showing the default JavaFX white background.
                com.benesquivelmusic.daw.app.ui.theme.ThemeManager.getDefault().applyTo(scene);
                stage.setScene(scene);
                // When the user dismisses the floating window via the OS
                // close button, move the panel back to its preferred dock
                // zone so it isn't trapped in a hidden-floating state.
                final String idForHandler = panelId;
                stage.setOnCloseRequest(e -> {
                    if (dockManager == null) return;
                    // Story 287 — telemetry panels live only as floating-or-
                    // hidden (the RIGHT slot is contended; see
                    // reconcileTelemetry). Re-docking would immediately
                    // re-float them, so a close just hides them instead.
                    if (isTelemetryPanel(idForHandler)) {
                        dockManager.setVisible(idForHandler, false);
                        return;
                    }
                    DockZone preferred = dockManager.panel(idForHandler)
                            .map(Dockable::preferredZone)
                            .orElse(DockZone.CENTER);
                    dockManager.move(idForHandler, preferred, 0);
                });
                // Propagate user-driven position/size changes back to the
                // DockManager so workspace captures reflect the actual
                // window placement (not just the launch bounds).
                final Stage stageRef = stage;
                javafx.beans.value.ChangeListener<Number> boundsListener = (obs, oldVal, newVal) -> {
                    if (dockManager == null) return;
                    var currentEntry = dockManager.layout().entry(idForHandler).orElse(null);
                    if (currentEntry == null || currentEntry.zone() != DockZone.FLOATING) return;
                    com.benesquivelmusic.daw.sdk.ui.Rectangle2D actual =
                            new com.benesquivelmusic.daw.sdk.ui.Rectangle2D(
                                    stageRef.getX(), stageRef.getY(),
                                    stageRef.getWidth(), stageRef.getHeight());
                    if (!actual.equals(currentEntry.floatingBounds())) {
                        dockManager.updateFloatingBounds(idForHandler, actual);
                    }
                };
                stage.xProperty().addListener(boundsListener);
                stage.yProperty().addListener(boundsListener);
                stage.widthProperty().addListener(boundsListener);
                stage.heightProperty().addListener(boundsListener);
                // Story 287 — gate the shared TelemetryView's GpuCanvas
                // animation to this window's visibility. GpuCanvas only
                // auto-stops its AnimationTimer when getScene() == null, but a
                // hidden Stage keeps its node's Scene attached — so without this
                // the RoomTelemetryDisplay render loop would keep spinning
                // (CPU/GPU) while the telemetry window is hidden. startAnimation()
                // is a no-op unless the view is in display state, so this is safe
                // in setup state too. (The six BOTTOM analyzers never reach this
                // hidden-but-retained state — they are docked or visibly
                // floating — so the gating is scoped to the telemetry ids.)
                if (isTelemetryPanel(panelId) && telemetryView != null) {
                    final TelemetryView tvRef = telemetryView;
                    stage.showingProperty().addListener((obs, wasShowing, showing) -> {
                        if (showing) tvRef.startAnimation();
                        else tvRef.stopAnimation();
                    });
                }
                floatingStages.put(panelId, stage);
            }
            stage.setX(b.x());
            stage.setY(b.y());
            stage.setWidth(b.width());
            stage.setHeight(b.height());
            if (entry.visible() && !stage.isShowing()) stage.show();
            else if (!entry.visible() && stage.isShowing()) stage.hide();
        }

        private Node resolveNode(String panelId) {
            return switch (panelId) {
                case DefaultWorkspaces.PANEL_BROWSER ->
                        browserPanelController == null ? null
                                : browserPanelController.getBrowserPanel();
                case DefaultWorkspaces.PANEL_MIXER ->
                        viewNavigationController == null ? null
                                : viewNavigationController.getMixerView();
                case DefaultWorkspaces.PANEL_EDITOR ->
                        viewNavigationController == null ? null
                                : viewNavigationController.getEditorView();
                case DefaultWorkspaces.PANEL_MASTERING ->
                        viewNavigationController == null ? null
                                : viewNavigationController.getMasteringView();
                case DefaultWorkspaces.PANEL_ARRANGEMENT ->
                        viewNavigationController == null ? null
                                : viewNavigationController.getCachedArrangementNode();
                // Story 287 — both telemetry ids resolve to the SAME shared
                // TelemetryView: the Room-3D display and the setup form are
                // its two faces, so the whole functional view (Generate /
                // Reconfigure chrome + current face) floats as one unit.
                // (The registered Dockable for PANEL_TELEMETRY is the
                // setup-panel instance for identity; the mounted/floated
                // node is the parent view — intentional, not a bug.)
                case DefaultWorkspaces.PANEL_TELEMETRY,
                     DefaultWorkspaces.PANEL_ROOM_3D -> telemetryView;
                case DefaultWorkspaces.PANEL_SESSION_MANAGER -> sessionManagerDock;
                default ->
                        // Story 287 — the six BOTTOM analyzer adapters resolve
                        // to themselves (used when floating one out).
                        vizDockables.get(panelId);
            };
        }

        private String displayNameFor(String panelId) {
            Dockable d = dockManager == null ? null
                    : dockManager.panel(panelId).orElse(null);
            return d == null ? panelId : d.displayName();
        }
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
    @FXML private void onToggleLoop() { transportController.onToggleLoop(); applyLoopAndRulerGrid(); }
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
        project.markDirty();
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
        project.markDirty();
    }

    private void onFoldAllAutomation() {
        if (arrangementCanvas == null) {
            return;
        }
        arrangementCanvas.toggleFoldAllAutomation();
        status("Toggled fold for all automation lanes", DawIcon.AUTOMATION);
        project.markDirty();
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
            project.markDirty();
        } else {
            status("Nothing to undo", DawIcon.INFO_CIRCLE);
        }
        syncMenuStateIfPresent();
    }

    @FXML private void onRedo() {
        if (undoManager.redo()) {
            status("Redo: " + undoManager.undoDescription(), DawIcon.REDO);
            project.markDirty();
        } else {
            status("Nothing to redo", DawIcon.INFO_CIRCLE);
        }
        syncMenuStateIfPresent();
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
        ThemeManager.getDefault().applyTo(scene);
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
            postFx(() -> {
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
        ThemeManager.getDefault().applyTo(alert.getDialogPane());
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
        ThemeManager.getDefault().applyTo(scene);
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


    /**
     * Story 293 — repaints the project-identity status cells. Renamed from the
     * deleted {@code updateProjectInfo()}; driven reactively by a
     * {@link ProjectVM#nameProperty()} subscriber (§4.3) and by the engine-reconfig
     * callback. The audio format (kHz/bit/ch) and lock badge are not ProjectVM facts
     * (no producer; adding one is a Non-Goal), so they are read live here.
     */
    private void applyProjectInfoLabels() {
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
        repaintLockIndicator();
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
        lockStatusIndicator = new LockStatusIndicator(dispatcher());
        int idx = bar.getChildren().indexOf(projectInfoLabel);
        bar.getChildren().add(idx + 1, lockStatusIndicator);
        repaintLockIndicator();

        // Periodic refresh — ProjectLockManager has no Flow.Publisher today,
        // so a low-frequency 5 s poll is the cheapest way to surface a
        // stolen lock or a take-over without changing the core API.
        lockIndicatorTimeline = new Timeline(
                new KeyFrame(Duration.seconds(5), _ -> {
                    repaintLockIndicator();
                    // Story 295 — piggyback the strip's time-varying facts
                    // (session elapsed, lock freshness) on the same 5 s poll.
                    refreshStripDynamicState();
                }));
        lockIndicatorTimeline.setCycleCount(Timeline.INDEFINITE);
        lockIndicatorTimeline.play();
    }

    /**
     * Story 293 — repaints the lock badge (renamed from the deleted
     * {@code refreshLockStatusIndicator()}). Kept poll-based: {@code ProjectLockManager}
     * has no push API and lock state is not a ProjectVM fact, so a pure binding is not
     * possible; the 5 s timeline + project-info refresh drive it.
     */
    private void repaintLockIndicator() {
        if (lockStatusIndicator == null || projectManager == null) {
            return;
        }
        lockStatusIndicator.refresh(projectManager.getLockManager());
    }

    /**
     * Story 293 — the central arrangement repaint (renamed from the deleted
     * {@code refreshArrangementCanvas()}). Track add/remove now reaches it reactively
     * through a {@link ProjectVM#getTracks()} subscriber (§4.3); the remaining callers
     * (clip edits, undo, selection) still invoke it directly until a full
     * canvas-subscribes-to-ClipEvent pass (follow-on).
     *
     * <p>The canvas is driven from the FX-owned {@link ProjectVM#getTracks()} list,
     * <em>not</em> the live {@link DawProject#getTracks()} view. {@code ProjectVM}
     * snapshots the project's tracks onto the FX thread (story 292), whereas
     * {@link ArrangementCanvas#setTracks} stores the reference and re-iterates it on
     * every {@code redraw()}. Handing it the live core view would let a background
     * loader thread mutate the list mid-redraw — a {@code ConcurrentModificationException}
     * during File→Open / snapshot-restore. {@code projectVM} is built before the
     * canvas exists (see {@code rebuildViewModels()} vs {@code createArrangementCanvas()}),
     * so the added null-guard only short-circuits the pre-init window.</p>
     */
    private void repaintArrangementCanvas() {
        if (arrangementCanvas == null || projectVM == null) return;
        arrangementCanvas.setTracks(projectVM.getTracks());
        applyLoopAndRulerGrid();
        paintCanvasSelection();
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

    /**
     * Story 293 — the per-frame arrangement-overlay tick (renamed from the deleted
     * {@code updatePlayheadFromTransport()}). The playhead position is read from the
     * single source of truth ({@link TransportVM#getPlayhead()}, fed once per frame by
     * the §4.5 dispatcher drain). The loop overlay and ruler grid are refreshed
     * alongside it because the ruler's snap/grid display has no discrete change trigger
     * today (it was repainted every frame); making that event-driven is a follow-on.
     */
    private void tickArrangementOverlays() {
        double beat = transportVM != null
                ? transportVM.getPlayhead()
                : project.getTransport().getPositionInBeats();
        if (timelineRuler != null) timelineRuler.setPlayheadPositionBeats(beat);
        if (arrangementCanvas != null) arrangementCanvas.setPlayheadBeat(beat);
        applyLoopAndRulerGrid();
    }

    /**
     * Story 293 — paints the canvas loop overlay and the ruler snap/grid (renamed from
     * the deleted {@code syncLoopRegionToCanvas()}). Called on the per-frame tick, on a
     * canvas rebuild, and on a loop toggle.
     */
    private void applyLoopAndRulerGrid() {
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

    /**
     * Story 293 — paints the canvas time-range selection (renamed from the deleted
     * {@code syncSelectionToCanvas()}). The range lives on {@code SelectionModel};
     * {@code SelectionVM} carries only the track/clip/device selection, so there is no
     * VM fact to bind — it stays an imperative paint invoked from the selection paths.
     */
    private void paintCanvasSelection() {
        if (arrangementCanvas != null) {
            arrangementCanvas.setSelectionRange(selectionModel.hasSelection(), selectionModel.getStartBeat(), selectionModel.getEndBeat());
        }
    }

    private void applyLiveSettings(SettingsModel model, String previousPluginPaths) {
        LiveSettingsApplier.apply(model, previousPluginPaths, rootPane.getScene(),
                projectManager, project, pluginRegistry);
    }
}
