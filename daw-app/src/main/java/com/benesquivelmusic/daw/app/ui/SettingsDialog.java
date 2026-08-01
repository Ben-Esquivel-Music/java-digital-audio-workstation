package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.density.DensityMode;
import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.settings.ApplyClass;
import com.benesquivelmusic.daw.app.ui.settings.BackupSettingsAccess;
import com.benesquivelmusic.daw.app.ui.settings.DeviceEnumerationTask;
import com.benesquivelmusic.daw.app.ui.settings.DiskUsageTask;
import com.benesquivelmusic.daw.app.ui.settings.RecordingSettingsAccess;
import com.benesquivelmusic.daw.app.ui.settings.SettingDescriptor;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.persistence.backup.ProjectDiskUsage;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent;
import com.benesquivelmusic.daw.sdk.audio.ClockSource;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;
import com.benesquivelmusic.daw.sdk.event.UiEvent;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Modal dialog for configuring application settings — the story-306
 * Rail &amp; Pane shell over the story-305 descriptor catalogue
 * (Settings View Design Book §4.A, §7 Stage 2).
 *
 * <p>The old {@code TabPane} body is gone: the dialog's content is one
 * {@link SettingsShell} rendering <em>every</em> catalogue descriptor
 * through generic {@link SettingRow}s, with always-on search (§6.1),
 * per-setting reset (§5.7 tier 1) and a generalised dirty/apply model
 * (§6.2). This class owns exactly what the shell cannot: the persisted
 * read path ({@link SettingsShell.ValueAccess} over {@link SettingsModel}
 * and the three live managers), the per-descriptor render hints, and the
 * write path {@link #applySettings()} — which writes through the SAME
 * authorities as before (model setters, {@code KeyBindingManager}
 * clear-all-then-set, the unconditional {@link UiEvent.SettingsApplied}
 * publish of story 294).</p>
 *
 * <p>An optional {@link SettingsChangeListener} can be registered to
 * receive a callback after settings are applied, allowing the caller to
 * propagate changes to the running audio engine, transport, UI, or other
 * subsystems.</p>
 *
 * <h2>Close paths (§6.2)</h2>
 *
 * <p>The shell owns the visible Cancel / Apply / OK footer; the dialog
 * keeps one hidden {@code ButtonType.CANCEL} so Esc/[X] still close.
 * Every close path — footer Cancel, the hidden cancel button, and the
 * window close request — routes through the same dirty guard: with
 * pending edits, an Apply / Discard / Cancel prompt runs first (the
 * {@link #setDirtyClosePrompt(Supplier)} seam replaces the modal in
 * headless tests).</p>
 */
public final class SettingsDialog extends DawgDialog<Void> {

    /**
     * Callback interface invoked after settings are applied.
     *
     * <p>Implementations should propagate the updated settings to the
     * appropriate subsystems (e.g., audio engine, transport, UI scale).</p>
     */
    @FunctionalInterface
    public interface SettingsChangeListener {

        /**
         * Called after all settings have been written to the {@link SettingsModel}.
         *
         * @param model the updated settings model
         */
        void onSettingsChanged(SettingsModel model);
    }

    /** The §6.2 dirty-close prompt outcomes (Apply / Discard / Cancel). */
    enum DirtyChoice { APPLY, DISCARD, CANCEL }

    private static final String KEYBINDING_PREFIX = "keybinding.";
    private static final String AUDIO_PREFIX = "audio.";
    // Story 308 — absorbed Recording/Backups ids (external stores, not
    // SettingsModel; see the catalogue Javadoc).
    private static final String METRONOME_PREFIX = "metronome.";
    private static final String BACKUPS_PREFIX = "backups.";
    private static final List<String> RECORDING_SETTING_IDS = List.of(
            "metronome.enabled", "metronome.countIn", "metronome.clickChannel",
            "metronome.clickGain", "metronome.clickSideOutput",
            "metronome.clickMainMix", "metronome.clickCueBus");
    private static final List<String> BACKUP_SETTING_IDS = List.of(
            "backups.keepRecent", "backups.keepHourly", "backups.keepDaily",
            "backups.keepWeekly", "backups.maxAgeDays", "backups.maxDiskGiB");
    private static final long BYTES_PER_GIB = 1024L * 1024L * 1024L;
    private static final Logger LOG = Logger.getLogger(SettingsDialog.class.getName());

    /**
     * Resource bundle for localized strings (Skill §14) — uses
     * {@link Locale#ROOT} to match the codebase-wide convention (see
     * {@code BrowserPanel}, {@code MainController}, {@code DawgDialog}).
     * If/when a locale-aware strategy is adopted it should be changed
     * globally, not per-class.
     */
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    private final SettingsModel model;

    // Story 305 — descriptor metadata (labels / defaults / validators)
    // comes from the settings catalogue; SettingsModel stays the write path.
    private final SettingsCatalogue catalogue;

    // Captured-once manager singletons (stories 277/278/279) — the
    // project rule: capture a swappable singleton reference at
    // construction, never re-resolve getDefault() per use.
    private final ThemeManager themeManager;
    private final DensityManager densityManager;
    private final MotionManager motionManager;

    private final SettingsShell shell;
    private final Label activeBackendValue = new Label();
    private final Label latencyValue = new Label();
    private final Label cpuLoadValue = new Label();
    private final Label threadsInUseValue = new Label();
    private final Button testToneButton = new Button(msg("audio.utility.testTone"));
    private final Button controlPanelButton = new Button(msg("audio.utility.controlPanel"));
    private final CheckBox wasapiExclusiveCheck =
            new CheckBox(msg("audio.utility.exclusiveMode"));
    private final ComboBox<ClockSource> clockSourceCombo = new ComboBox<>();
    private final Timeline audioTelemetryTimer = new Timeline(
            new KeyFrame(Duration.seconds(0.5), _ -> refreshAudioTelemetry()));
    private List<ClockSource> currentClockSources = List.of();
    private boolean refreshingClockSources;
    private boolean refreshingWasapiMode;

    // ── Callback ─────────────────────────────────────────────────────────────
    private SettingsChangeListener settingsChangeListener;
    private AudioEngineController audioEngineController;
    private DeviceEnumerationTask deviceEnumerationTask;
    // Story 308 — Recording/Backups access seams + the §5.8 disk-usage
    // group visual. The pie node exists from construction (rendered even
    // by seam-less test dialogs); the scan starts when a Backups seam is
    // attached and is cancelled with the rest of the background work.
    private RecordingSettingsAccess recordingSettingsAccess;
    private BackupSettingsAccess backupSettingsAccess;
    private DiskUsageTask diskUsageTask;
    private final PieChart diskUsagePie = new PieChart();
    /** UUID-string → "label  (Out A/B)" display for the cue-bus CHOICE row. */
    private final Map<String, String> cueBusDisplayById = new HashMap<>();
    private final AtomicReference<CapabilityEventSubscriber> deviceEventSubscriber =
            new AtomicReference<>();
    private final Map<String, Object> asynchronousPersistedValues =
            new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<AudioReconfigureSnapshot> audioReconfigureQueue =
            new ConcurrentLinkedQueue<>();
    private final AtomicReference<Thread> audioReconfigureWorker = new AtomicReference<>();
    private final AtomicBoolean audioReconfigureWorkerRunning = new AtomicBoolean();
    private final AtomicInteger outstandingAudioReconfigures = new AtomicInteger();
    private final AtomicInteger outstandingAudioUtilityOperations = new AtomicInteger();
    private final ReentrantLock audioControllerOperationLock = new ReentrantLock(true);
    private final Set<Thread> audioUtilityWorkers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean audioQueueFailed = new AtomicBoolean();
    private boolean closeAfterAudioApply;
    private volatile LiveAudioEndpoint liveAudioEndpoint;
    private volatile AudioRuntimeState audioRuntimeState;
    private volatile boolean backgroundWorkClosed;

    /**
     * The §6.2 dirty-close prompt. Defaults to a real three-button modal
     * ({@link #promptDirtyClose()}); headless tests inject a stub via
     * {@link #setDirtyClosePrompt(Supplier)}.
     */
    private Supplier<DirtyChoice> dirtyClosePrompt = this::promptDirtyClose;

    /**
     * The appearance values the last {@link #applySettings()} published.
     * The story-294 {@link UiEvent.SettingsApplied} publish is delivered
     * asynchronously (the managers subscribe {@code ON_UI_THREAD}, i.e. a
     * later FX pulse), so ThemeManager/DensityManager/MotionManager still
     * hold pre-apply state when the synchronous post-apply
     * {@code shell.refreshFromPersisted()} re-seeds the rows. These fields
     * make {@link #currentPersistedValue(String)} authoritative for what
     * this dialog just published — without them the edited appearance rows
     * would visibly snap back to the stale manager values on Apply. The
     * manager getters remain the fallback before the first Apply.
     */
    private ThemeManager.Theme lastAppliedTheme;
    private DensityMode lastAppliedDensity;
    private Boolean lastAppliedReduceMotion;

    /**
     * Story 308 — the same lastApplied-overlay rule for the absorbed
     * categories: the retention write runs asynchronously on the
     * controller's scheduler and the metronome JSON-store save is
     * fire-and-forget, so the post-apply {@code refreshFromPersisted()}
     * must read what this dialog just applied, not a possibly-stale
     * re-read of the stores. The access seams remain the fallback
     * before the first Apply.
     */
    private RecordingSettingsAccess.Snapshot lastAppliedRecording;
    private BackupRetentionPolicy lastAppliedBackupPolicy;

    /**
     * Creates a new settings dialog backed by the given model.
     *
     * @param model the settings model to read from and write to
     */
    public SettingsDialog(SettingsModel model) {
        this.model = model;
        catalogue = SettingsCatalogue.create();
        themeManager = ThemeManager.getDefault();
        densityManager = DensityManager.getDefault();
        motionManager = MotionManager.getDefault();

        setTitle("Settings");
        setHeaderText("Application Preferences");
        // DawgIcon (Lucide) replaces the legacy IconNode/DawIcon graphics
        // (UI Design Book §3.6); a non-null header graphic also keeps
        // DawgDialog's close glyph retracted.
        setGraphic(DawgIcon.of("settings", DawgIcon.Size.SIZE_24));

        initializeAudioUtilityControls();
        shell = new SettingsShell(catalogue,
                this::currentPersistedValue,
                buildControlHints(),
                Map.of(),
                Map.of(
                        "audio/device", buildAudioDeviceUtilities(),
                        "audio/performance", buildAudioPerformanceTelemetry(),
                        "backups/diskUsage", buildDiskUsageVisual()));
        shell.settingRow("audio.backend").orElseThrow().valueProperty()
                .addListener((_, _, backend) -> {
                    refreshWasapiMode(backend);
                    restartDeviceEnumeration(backend, "");
                });
        shell.settingRow("audio.outputDevice").orElseThrow().valueProperty()
                .addListener((_, _, output) -> restartDeviceEnumeration(
                        shell.settingRow("audio.backend").orElseThrow().getValue(), output));
        shell.settingRow("audio.bufferSize").orElseThrow().valueProperty()
                .addListener((_, _, _) -> refreshLatencyReadout());
        shell.settingRow("audio.sampleRate").orElseThrow().valueProperty()
                .addListener((_, _, _) -> refreshLatencyReadout());
        wireAudioUtilityControls();

        shell.setOnApply(this::applySettings);
        shell.setOnOk(this::applyAndCloseWhenReady);
        shell.setOnCancel(() -> {
            if (confirmClose()) {
                closeShell();
            }
        });

        getDialogPane().setContent(shell);
        // story 276 §5.9 width bands — the shell needs the LARGE band
        // (800); its pref height rides on .settings-shell in styles.css.
        sized(DawgDialog.Size.LARGE);

        // The shell owns the visible footer; ONE hidden CANCEL keeps the
        // Esc/[X] plumbing alive. Its action is filtered through the same
        // §6.2 dirty guard so an Esc that fires it cannot bypass the prompt.
        getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Button hiddenCancel = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        hiddenCancel.setVisible(false);
        hiddenCancel.setManaged(false);
        hiddenCancel.addEventFilter(ActionEvent.ACTION, event -> {
            if (!confirmClose()) {
                event.consume();
            }
        });

        // story 278 — register the dialog pane with DensityManager so the
        // Preferences dialog itself reflects the active density and
        // live-updates when the user selects a different density mode.
        densityManager.applyTo(getDialogPane());

        setResultConverter(_ -> null);

        // Window [X] (and any Esc path that surfaces as a close request)
        // routes through the same dirty guard; CANCEL keeps the dialog open.
        setOnCloseRequest(event -> {
            if (!confirmClose()) {
                event.consume();
            }
        });
        setOnShown(_ -> {
            refreshAudioTelemetry();
            audioTelemetryTimer.play();
        });
        setOnHidden(_ -> {
            audioTelemetryTimer.stop();
            closeBackgroundWork();
        });
    }

    private void initializeAudioUtilityControls() {
        activeBackendValue.getStyleClass().add("numeric-value");
        latencyValue.getStyleClass().add("numeric-value");
        cpuLoadValue.getStyleClass().add("numeric-value");
        threadsInUseValue.getStyleClass().add("numeric-value");
        audioTelemetryTimer.setCycleCount(Timeline.INDEFINITE);

        wasapiExclusiveCheck.setTooltip(new Tooltip(msg("audio.utility.exclusiveMode.help")));
        clockSourceCombo.setTooltip(new Tooltip(msg("audio.utility.clockSource.unavailable")));
        clockSourceCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ClockSource source) {
                return source == null ? ""
                        : source.name() + " (" + source.kind().shortLabel() + ")";
            }

            @Override
            public ClockSource fromString(String text) {
                return null;
            }
        });
        controlPanelButton.setDisable(true);
        refreshWasapiMode(model.getAudioBackend());
        refreshAudioTelemetry();
    }

    private Node buildAudioDeviceUtilities() {
        VBox utilities = new VBox(
                utilityLine(msg("audio.utility.activeBackend"), activeBackendValue),
                utilityLine(msg("audio.utility.clockSource"), clockSourceCombo),
                utilityLine(msg("audio.utility.latency"), latencyValue),
                wasapiExclusiveCheck,
                new HBox(testToneButton, controlPanelButton));
        utilities.getStyleClass().add("settings-audio-utilities");
        ((HBox) utilities.getChildren().getLast()).getStyleClass()
                .add("settings-audio-actions");
        return utilities;
    }

    private Node buildAudioPerformanceTelemetry() {
        HBox telemetry = new HBox(cpuLoadValue, threadsInUseValue);
        telemetry.getStyleClass().add("settings-audio-telemetry");
        return telemetry;
    }

    /**
     * Story 308 §5.8 — the disk-usage pie is a group-level visual (never
     * a Control of its own), mounted after the Backups "Disk usage"
     * group header. Slice/legend colours ride the {@code -chart-series-*}
     * tokens via the {@code settings-disk-pie} scope in styles.css.
     */
    private Node buildDiskUsageVisual() {
        diskUsagePie.getStyleClass().add("settings-disk-pie");
        diskUsagePie.setLegendVisible(true);
        diskUsagePie.setLabelsVisible(false);
        diskUsagePie.setPrefSize(260, 200);
        return diskUsagePie;
    }

    private static HBox utilityLine(String labelText, Node value) {
        Label label = new Label(labelText);
        label.getStyleClass().add("settings-audio-utility-label");
        HBox line = new HBox(label, value);
        line.getStyleClass().add("settings-audio-utility-line");
        return line;
    }

    private void wireAudioUtilityControls() {
        testToneButton.setOnAction(_ -> playTestTone());
        controlPanelButton.setOnAction(_ -> openDriverControlPanel());
        wasapiExclusiveCheck.selectedProperty().addListener((_, _, selected) -> {
            if (refreshingWasapiMode) {
                return;
            }
            SettingRow backendRow = shell.settingRow("audio.backend").orElseThrow();
            String backend = Objects.toString(backendRow.getValue(), "");
            if (!isWasapiBackend(backend)) {
                return;
            }
            String base = backend.replace(" (Exclusive)", "");
            backendRow.setValue(selected ? base + " (Exclusive)" : base);
        });
        clockSourceCombo.valueProperty().addListener((_, _, source) -> {
            if (!refreshingClockSources && source != null) {
                selectClockSource(source);
            }
        });
    }

    private void playTestTone() {
        AudioEngineController controller = audioEngineController;
        if (controller == null) {
            return;
        }
        String output = Objects.toString(shell.settingRow("audio.outputDevice")
                .map(SettingRow::getValue).orElse(""), "");
        startSerializedAudioUtilityOperation(
                "settings-audio-test-tone",
                "Test tone playback failed",
                msg("audio.utility.testTone.failure") + " ",
                () -> controller.playTestTone(output));
    }

    private void openDriverControlPanel() {
        AudioEngineController controller = audioEngineController;
        Optional<Runnable> action = controller == null
                ? Optional.empty() : controller.openControlPanel();
        if (action.isEmpty()) {
            return;
        }
        startSerializedAudioUtilityOperation(
                "settings-audio-control-panel",
                "Driver control panel failed",
                "Could not open the driver control panel: ",
                action.orElseThrow());
    }

    private void selectClockSource(ClockSource source) {
        AudioEngineController controller = audioEngineController;
        if (controller == null) {
            return;
        }
        String backend = effectiveBackendForCapabilities(shell.settingRow("audio.backend")
                .map(SettingRow::getValue).orElse(""));
        String output = Objects.toString(shell.settingRow("audio.outputDevice")
                .map(SettingRow::getValue).orElse(""), "");
        startSerializedAudioUtilityOperation(
                "settings-audio-clock-source",
                "Clock source selection failed",
                "Could not switch the hardware clock: ",
                () -> controller.selectClockSource(backend, output, source.id()));
    }

    private void startSerializedAudioUtilityOperation(
            String threadName, String logMessage, String userMessage, Runnable operation) {
        outstandingAudioUtilityOperations.incrementAndGet();
        updateAudioUtilityDisabledState();
        Thread worker = Thread.ofVirtual().name(threadName).unstarted(() -> {
            Thread current = Thread.currentThread();
            boolean locked = false;
            try {
                audioControllerOperationLock.lockInterruptibly();
                locked = true;
                DeviceEnumerationTask task = deviceEnumerationTask;
                if (task != null) {
                    task.cancelAndAwait();
                }
                operation.run();
            } catch (InterruptedException cancelled) {
                current.interrupt();
            } catch (RuntimeException failure) {
                LOG.log(Level.WARNING, logMessage, failure);
                showAudioOperationFailure(userMessage + failureMessage(failure));
            } finally {
                if (locked) {
                    audioControllerOperationLock.unlock();
                }
                audioUtilityWorkers.remove(current);
                if (outstandingAudioUtilityOperations.decrementAndGet() == 0) {
                    FxDispatcher.runOnFx(() -> {
                        updateAudioUtilityDisabledState();
                        if (!backgroundWorkClosed) {
                            restartDeviceEnumeration(
                                    shell.settingRow("audio.backend")
                                            .map(SettingRow::getValue).orElse(""),
                                    shell.settingRow("audio.outputDevice")
                                            .map(SettingRow::getValue).orElse(""));
                        }
                    });
                }
            }
        });
        audioUtilityWorkers.add(worker);
        worker.start();
    }

    private void updateAudioUtilityDisabledState() {
        boolean busy = outstandingAudioUtilityOperations.get() > 0
                || outstandingAudioReconfigures.get() > 0;
        AudioEngineController controller = audioEngineController;
        testToneButton.setDisable(busy || controller == null);
        controlPanelButton.setDisable(busy || controller == null
                || controller.openControlPanel().isEmpty());
        clockSourceCombo.setDisable(busy || currentClockSources.isEmpty());
        wasapiExclusiveCheck.setDisable(busy);
    }

    private void refreshWasapiMode(Object backendValue) {
        String backend = Objects.toString(backendValue, "");
        boolean wasapi = isWasapiBackend(backend);
        wasapiExclusiveCheck.setManaged(wasapi);
        wasapiExclusiveCheck.setVisible(wasapi);
        refreshingWasapiMode = true;
        try {
            wasapiExclusiveCheck.setSelected(wasapi && backend.contains("Exclusive"));
        } finally {
            refreshingWasapiMode = false;
        }
    }

    private static boolean isWasapiBackend(String backend) {
        return backend != null && backend.startsWith("WASAPI");
    }

    private String effectiveBackendForCapabilities(Object backendValue) {
        String backend = Objects.toString(backendValue, "");
        if (isWasapiBackend(backend) && wasapiExclusiveCheck.isSelected()
                && !backend.contains("Exclusive")) {
            return backend + " (Exclusive)";
        }
        return backend;
    }

    private void refreshAudioTelemetry() {
        AudioEngineController controller = audioEngineController;
        if (controller == null) {
            activeBackendValue.setText(msg("audio.utility.unavailable"));
            cpuLoadValue.setText(msg("audio.utility.cpuUnavailable"));
            threadsInUseValue.setText(msg("audio.utility.threadsUnavailable"));
            latencyValue.setText(msg("audio.utility.unavailable"));
            return;
        }
        activeBackendValue.setText(controller.getActiveBackendName());
        double load = controller.getCpuLoadPercent();
        cpuLoadValue.setText(load < 0 ? msg("audio.utility.cpuUnavailable")
                : String.format(Locale.ROOT, "CPU: %.1f%%", load));
        threadsInUseValue.setText(String.format(Locale.ROOT, "Threads: %d / %d",
                controller.getActiveThreadCount(), controller.getWorkerPoolSize()));
        refreshLatencyReadout();
    }

    private void refreshLatencyReadout() {
        Object rateValue = shell == null ? model.getSampleRate()
                : shell.settingRow("audio.sampleRate").map(SettingRow::getValue)
                        .orElse(model.getSampleRate());
        Object bufferValue = shell == null ? model.getBufferSize()
                : shell.settingRow("audio.bufferSize").map(SettingRow::getValue)
                        .orElse(model.getBufferSize());
        if (!(rateValue instanceof Number rate) || !(bufferValue instanceof Number buffer)
                || rate.doubleValue() <= 0) {
            latencyValue.setText(msg("audio.utility.unavailable"));
            return;
        }
        AudioEngineController controller = audioEngineController;
        var reportedLatency = controller == null
                ? com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN
                : controller.reportedLatency();
        int driverFrames = reportedLatency.totalFrames();
        double milliseconds;
        if (driverFrames > 0) {
            milliseconds = reportedLatency.totalMillis(rate.doubleValue());
        } else {
            milliseconds = buffer.doubleValue() * 2_000.0 / rate.doubleValue();
        }
        int displayedFrames = driverFrames > 0 ? driverFrames : buffer.intValue();
        latencyValue.setText(String.format(Locale.ROOT,
                "%d frames · %.1f ms round-trip @ %.1f kHz",
                displayedFrames, milliseconds, rate.doubleValue() / 1_000.0));
    }

    /**
     * Sets the listener to be notified after settings are applied.
     *
     * @param listener the settings change listener, or {@code null} to clear
     */
    public void setSettingsChangeListener(SettingsChangeListener listener) {
        this.settingsChangeListener = listener;
    }

    /** Deep-links the rail-and-pane surface to one category. */
    public void selectCategory(String categoryId) {
        shell.selectCategory(categoryId);
    }

    /** Attaches the controller used for discovery and live engine re-arming. */
    public void setAudioEngineController(AudioEngineController controller) {
        cancelDeviceEventSubscription();
        if (deviceEnumerationTask != null) {
            deviceEnumerationTask.close();
            deviceEnumerationTask = null;
        }
        this.audioEngineController = controller;
        if (controller == null) {
            currentClockSources = List.of();
            clockSourceCombo.getItems().clear();
        }
        liveAudioEndpoint = controller == null ? null : initialLiveAudioEndpoint(controller);
        audioRuntimeState = controller == null ? null : new AudioRuntimeState(
                model.getSampleRate(), model.getBufferSize(), model.getBitDepth(),
                model.getWorkerPoolSize(), model.getMixPrecision(), model.getSrcQuality());
        if (controller == null || backgroundWorkClosed) {
            updateAudioUtilityDisabledState();
            return;
        }
        deviceEnumerationTask = new DeviceEnumerationTask(controller,
                this::applyDeviceEnumerationResult,
                busy -> shell.setGroupBusy("audio", "device", busy),
                failure -> LOG.log(Level.WARNING, "Audio device enumeration failed", failure));
        restartDeviceEnumeration(
                shell.settingRow("audio.backend").map(SettingRow::getValue).orElse(""),
                shell.settingRow("audio.outputDevice").map(SettingRow::getValue).orElse(""));
        subscribeToDeviceCapabilityChanges(controller);
        updateAudioUtilityDisabledState();
        refreshAudioTelemetry();
    }

    /**
     * Attaches the Recording (metronome) authorities — story 308. The
     * seven {@code metronome.*} rows re-seed from the live snapshot and
     * the "Send click to" options rebuild from the live cue buses.
     * {@code null} detaches (rows fall back to catalogue defaults).
     */
    public void setRecordingSettingsAccess(RecordingSettingsAccess access) {
        this.recordingSettingsAccess = access;
        lastAppliedRecording = null;
        refreshCueBusChoiceOptions();
        for (String id : RECORDING_SETTING_IDS) {
            shell.refreshSettingFromPersisted(id);
        }
    }

    /**
     * Attaches the Backups (retention + disk usage) authorities — story
     * 308. The six {@code backups.*} rows re-seed from the persisted
     * policy and the §5.8 disk-usage scan starts on a virtual thread
     * (busy affordance on the "Disk usage" group header). {@code null}
     * detaches and cancels any in-flight scan.
     */
    public void setBackupSettingsAccess(BackupSettingsAccess access) {
        DiskUsageTask previousTask = diskUsageTask;
        diskUsageTask = null;
        if (previousTask != null) {
            previousTask.cancel();
        }
        this.backupSettingsAccess = access;
        lastAppliedBackupPolicy = null;
        for (String id : BACKUP_SETTING_IDS) {
            shell.refreshSettingFromPersisted(id);
        }
        if (access == null || backgroundWorkClosed) {
            return;
        }
        Path projectDirectory = access.projectDirectory().orElse(null);
        diskUsageTask = new DiskUsageTask(
                () -> projectDirectory == null
                        ? new ProjectDiskUsage(0, 0, 0)
                        : ProjectDiskUsage.compute(projectDirectory),
                this::applyDiskUsageToPie,
                busy -> shell.setGroupBusy("backups", "diskUsage", busy),
                failure -> {
                    // Mirror the absorbed dialog: a failed scan renders
                    // three zero slices rather than a stale/empty chart.
                    LOG.log(Level.WARNING, "Project disk-usage scan failed", failure);
                    applyDiskUsageToPie(new ProjectDiskUsage(0, 0, 0));
                });
        diskUsageTask.start();
    }

    /** FX-thread only — the task marshals results through FxDispatcher. */
    private void applyDiskUsageToPie(ProjectDiskUsage usage) {
        ObservableList<PieChart.Data> data = FXCollections.observableArrayList();
        for (DiskUsageTask.Slice slice : DiskUsageTask.slices(usage)) {
            data.add(new PieChart.Data(slice.label(), slice.bytes()));
        }
        diskUsagePie.setData(data);
    }

    /**
     * Rebuilds the "Send click to" options from the live cue buses:
     * {@code ""} ("Main mix only") plus one UUID-string entry per bus,
     * displayed as {@code label  (Out A/B)} from the bus's stereo-pair
     * index — the absorbed dialog's exact format (blank labels render
     * as "Cue N").
     */
    private void refreshCueBusChoiceOptions() {
        cueBusDisplayById.clear();
        List<Object> options = new ArrayList<>();
        options.add("");
        RecordingSettingsAccess access = recordingSettingsAccess;
        if (access != null) {
            int index = 1;
            for (CueBus bus : access.cueBuses()) {
                String label = bus.label();
                if (label == null || label.isBlank()) {
                    label = "Cue " + index;
                }
                int outA = bus.hardwareOutputIndex() * 2;
                cueBusDisplayById.put(bus.id().toString(),
                        label + "  (Out " + (outA + 1) + "/" + (outA + 2) + ")");
                options.add(bus.id().toString());
                index++;
            }
        }
        shell.replaceChoiceOptions("metronome.clickCueBus", options, "");
    }

    private void subscribeToDeviceCapabilityChanges(AudioEngineController controller) {
        CapabilityEventSubscriber subscriber = new CapabilityEventSubscriber();
        CapabilityEventSubscriber previous = deviceEventSubscriber.getAndSet(subscriber);
        if (previous != null) {
            previous.close();
        }
        controller.deviceEvents().subscribe(subscriber);
    }

    private void cancelDeviceEventSubscription() {
        CapabilityEventSubscriber subscriber = deviceEventSubscriber.getAndSet(null);
        if (subscriber != null) {
            subscriber.close();
        }
    }

    private void refreshCapabilitiesAfterDeviceEvent() {
        FxDispatcher.runOnFx(() -> {
            if (!backgroundWorkClosed) {
                restartDeviceEnumeration(
                        shell.settingRow("audio.backend").map(SettingRow::getValue).orElse(""),
                        shell.settingRow("audio.outputDevice")
                                .map(SettingRow::getValue).orElse(""));
            }
        });
    }

    private final class CapabilityEventSubscriber
            implements Flow.Subscriber<AudioDeviceEvent>, AutoCloseable {
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void onSubscribe(Flow.Subscription newSubscription) {
            Objects.requireNonNull(newSubscription, "subscription must not be null");
            if (closed.get() || backgroundWorkClosed
                    || !subscription.compareAndSet(null, newSubscription)) {
                newSubscription.cancel();
                return;
            }
            if (closed.get() && subscription.compareAndSet(newSubscription, null)) {
                newSubscription.cancel();
                return;
            }
            newSubscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AudioDeviceEvent event) {
            if (closed.get()) {
                return;
            }
            // Exhaustive sealed-event switch (JEP 441, final in Java 21) with
            // unnamed patterns (JEP 456, final in Java 22).
            switch (event) {
                case AudioDeviceEvent.DeviceArrived _,
                     AudioDeviceEvent.DeviceRemoved _,
                     AudioDeviceEvent.DeviceFormatChanged _,
                     AudioDeviceEvent.FormatChangeRequested _ ->
                        refreshCapabilitiesAfterDeviceEvent();
            }
        }

        @Override
        public void onError(Throwable failure) {
            if (!closed.get()) {
                LOG.log(Level.WARNING, "Audio device event subscription failed", failure);
            }
        }

        @Override
        public void onComplete() {
            close();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Flow.Subscription current = subscription.getAndSet(null);
            if (current != null) {
                current.cancel();
            }
        }
    }

    private LiveAudioEndpoint initialLiveAudioEndpoint(AudioEngineController controller) {
        String activeBackend = controller.getActiveBackendName();
        if (Objects.equals(activeBackend, model.getAudioBackend())) {
            return new LiveAudioEndpoint(activeBackend,
                    model.getAudioInputDevice(), model.getAudioOutputDevice());
        }
        // Persisted endpoints belong to a backend awaiting restart. Passing
        // either name to the currently active backend is unsafe; automatic
        // endpoints are the only backend-neutral fallback.
        return new LiveAudioEndpoint(activeBackend, "", "");
    }

    private void restartDeviceEnumeration(Object backend, Object outputDevice) {
        if (outstandingAudioReconfigures.get() > 0
                || outstandingAudioUtilityOperations.get() > 0) {
            return;
        }
        DeviceEnumerationTask task = deviceEnumerationTask;
        if (task != null) {
            task.start(effectiveBackendForCapabilities(backend),
                    outputDevice instanceof String name ? name : "");
        }
    }

    private void applyDeviceEnumerationResult(DeviceEnumerationTask.Result result) {
        Object previousInput = shell.settingRow("audio.inputDevice")
                .map(SettingRow::getValue).orElse("");
        Object previousOutput = shell.settingRow("audio.outputDevice")
                .map(SettingRow::getValue).orElse("");
        Object previousRate = shell.settingRow("audio.sampleRate")
                .map(SettingRow::getValue).orElse(result.preferredSampleRate());
        Object previousBuffer = shell.settingRow("audio.bufferSize")
                .map(SettingRow::getValue).orElse(result.preferredBufferSize());
        shell.replaceChoiceOptions("audio.backend", result.backendNames());
        shell.replaceChoiceOptions("audio.inputDevice", result.inputDeviceNames(), "");
        shell.replaceChoiceOptions("audio.outputDevice", result.outputDeviceNames(), "");
        List<Double> unavailableRates = result.sampleRates().stream()
                .filter(rate -> !result.supportedSampleRates().contains(rate))
                .toList();
        shell.replaceChoiceOptions("audio.sampleRate", result.sampleRates(),
                result.preferredSampleRate(), unavailableRates);
        shell.replaceChoiceOptions("audio.bufferSize", result.bufferSizes(),
                result.preferredBufferSize());
        refreshingClockSources = true;
        try {
            currentClockSources = result.clockSources();
            clockSourceCombo.getItems().setAll(currentClockSources);
            ClockSource selected = currentClockSources.stream()
                    .filter(ClockSource::current).findFirst()
                    .orElse(currentClockSources.isEmpty()
                            ? null : currentClockSources.getFirst());
            clockSourceCombo.setValue(selected);
            updateAudioUtilityDisabledState();
            clockSourceCombo.setTooltip(new Tooltip(currentClockSources.isEmpty()
                    ? msg("audio.utility.clockSource.unavailable")
                    : msg("audio.utility.clockSource.available")));
        } finally {
            refreshingClockSources = false;
        }
        logCapabilityFallback(msg("audio.inputDevice.label"), previousInput,
                shell.settingRow("audio.inputDevice").orElseThrow().getValue());
        logCapabilityFallback(msg("audio.outputDevice.label"), previousOutput,
                shell.settingRow("audio.outputDevice").orElseThrow().getValue());
        logCapabilityFallback(msg("audio.sampleRate.label"), previousRate,
                shell.settingRow("audio.sampleRate").orElseThrow().getValue());
        logCapabilityFallback(msg("audio.bufferSize.label"), previousBuffer,
                shell.settingRow("audio.bufferSize").orElseThrow().getValue());
        refreshLatencyReadout();
        refreshAudioTelemetry();
    }

    private void logCapabilityFallback(String setting, Object previous, Object replacement) {
        if (!Objects.equals(previous, replacement)) {
            String message = formatMessage(
                    "settings.audio.capabilityFallback", setting, previous, replacement);
            LOG.warning(message);
            shell.showOperationNotice(message);
        }
    }

    private void closeBackgroundWork() {
        backgroundWorkClosed = true;
        cancelDeviceEventSubscription();
        DeviceEnumerationTask task = deviceEnumerationTask;
        deviceEnumerationTask = null;
        if (task != null) {
            task.close();
        }
        // Story 308 §2.7 — closing the surface cancels the disk-usage
        // scan cleanly; a late result is discarded, never applied.
        DiskUsageTask usageTask = diskUsageTask;
        diskUsageTask = null;
        if (usageTask != null) {
            usageTask.cancel();
        }
        for (Thread worker : List.copyOf(audioUtilityWorkers)) {
            worker.interrupt();
        }
    }

    // ── Persisted read path (SettingsShell.ValueAccess) ──────────────────────

    /**
     * The persisted value per descriptor id: {@link SettingsModel}
     * getters for the 17 model-backed ids, the three live managers for
     * theme/density/motion (overlaid by {@link #lastAppliedTheme} &amp; co.
     * once an Apply has published — the managers only catch up on a later
     * FX pulse), and {@code KeyBindingManager} for the key-binding ids.
     * The CPU-budget policy reads as its persisted short-name String via
     * the package-private {@link SettingsModel#policyName} mapper (same
     * package by design).
     */
    private Object currentPersistedValue(String id) {
        Object asynchronousValue = asynchronousPersistedValues.get(id);
        if (asynchronousValue != null) {
            return asynchronousValue;
        }
        return storedValue(id);
    }

    /** Reads the real persistence authority, bypassing queued UI overlays. */
    private Object storedValue(String id) {
        if (id.startsWith(KEYBINDING_PREFIX)) {
            DawAction action = DawAction.valueOf(id.substring(KEYBINDING_PREFIX.length()));
            return model.getKeyBindingManager().getBinding(action).orElse(null);
        }
        return switch (id) {
            case "audio.sampleRate" -> model.getSampleRate();
            case "audio.bitDepth" -> model.getBitDepth();
            case "audio.bufferSize" -> model.getBufferSize();
            case "audio.mixPrecision" -> model.getMixPrecision();
            case "audio.srcQuality" -> model.getSrcQuality();
            case "audio.backend" -> model.getAudioBackend();
            case "audio.inputDevice" -> model.getAudioInputDevice();
            case "audio.outputDevice" -> model.getAudioOutputDevice();
            case "audio.applyLatencyCompensation" -> model.isApplyLatencyCompensation();
            case "audio.workerPoolSize" -> model.getWorkerPoolSize();
            case "audio.masterCpuBudgetFraction" -> model.getMasterCpuBudgetFraction();
            case "audio.masterCpuBudgetPolicy" ->
                    SettingsModel.policyName(model.getMasterCpuBudgetPolicy());
            case "project.autoSaveIntervalSeconds" -> model.getAutoSaveIntervalSeconds();
            case "project.defaultTempo" -> model.getDefaultTempo();
            case "project.useJournaledPersistence" -> model.isUseJournaledPersistence();
            case "appearance.uiScale" -> model.getUiScale();
            case "plugins.scanPaths" -> model.getPluginScanPaths();
            // Last-published overlay first: after an Apply the managers
            // are still one async SettingsApplied delivery behind.
            case ThemeManager.PREF_KEY -> lastAppliedTheme != null
                    ? lastAppliedTheme : themeManager.getActiveTheme();
            case DensityManager.PREF_KEY -> lastAppliedDensity != null
                    ? lastAppliedDensity : densityManager.getActiveDensity();
            case MotionManager.PREF_KEY -> lastAppliedReduceMotion != null
                    ? lastAppliedReduceMotion : motionManager.isReduceMotion();
            // Story 308 — absorbed Recording/Backups ids read their live
            // authorities through the access seams, with the lastApplied
            // overlay bridging the asynchronous store writes (same rule
            // as the appearance managers above). Seam-less dialogs fall
            // back to catalogue defaults.
            case "metronome.enabled" -> recordingSnapshot()
                    .<Object>map(RecordingSettingsAccess.Snapshot::enabled)
                    .orElseGet(() -> catalogueDefault(id));
            case "metronome.countIn" -> recordingSnapshot()
                    .<Object>map(RecordingSettingsAccess.Snapshot::countIn)
                    .orElseGet(() -> catalogueDefault(id));
            case "metronome.clickChannel" -> recordingSnapshot()
                    .<Object>map(snapshot -> snapshot.clickOutput().hardwareChannelIndex())
                    .orElseGet(() -> catalogueDefault(id));
            case "metronome.clickGain" -> recordingSnapshot()
                    .<Object>map(snapshot -> snapshot.clickOutput().gain())
                    .orElseGet(() -> catalogueDefault(id));
            case "metronome.clickSideOutput" -> recordingSnapshot()
                    .<Object>map(snapshot -> snapshot.clickOutput().sideOutputEnabled())
                    .orElseGet(() -> catalogueDefault(id));
            case "metronome.clickMainMix" -> recordingSnapshot()
                    .<Object>map(snapshot -> snapshot.clickOutput().mainMixEnabled())
                    .orElseGet(() -> catalogueDefault(id));
            case "metronome.clickCueBus" -> recordingSnapshot()
                    .<Object>map(snapshot ->
                            snapshot.cueBusId().map(UUID::toString).orElse(""))
                    .orElseGet(() -> catalogueDefault(id));
            case "backups.keepRecent" -> backupPolicySnapshot()
                    .<Object>map(BackupRetentionPolicy::keepRecent)
                    .orElseGet(() -> catalogueDefault(id));
            case "backups.keepHourly" -> backupPolicySnapshot()
                    .<Object>map(BackupRetentionPolicy::keepHourly)
                    .orElseGet(() -> catalogueDefault(id));
            case "backups.keepDaily" -> backupPolicySnapshot()
                    .<Object>map(BackupRetentionPolicy::keepDaily)
                    .orElseGet(() -> catalogueDefault(id));
            case "backups.keepWeekly" -> backupPolicySnapshot()
                    .<Object>map(BackupRetentionPolicy::keepWeekly)
                    .orElseGet(() -> catalogueDefault(id));
            case "backups.maxAgeDays" -> backupPolicySnapshot()
                    .<Object>map(policy -> (double) policy.maxAge().toDays())
                    .orElseGet(() -> catalogueDefault(id));
            case "backups.maxDiskGiB" -> backupPolicySnapshot()
                    .<Object>map(policy -> (double) (policy.maxBytes() / BYTES_PER_GIB))
                    .orElseGet(() -> catalogueDefault(id));
            // Defensive: an id this dialog does not know reads as its
            // catalogue default so the row still renders something sane.
            default -> catalogueDefault(id);
        };
    }

    private Object catalogueDefault(String id) {
        return catalogue.byId(id).map(SettingDescriptor::defaultValue).orElse(null);
    }

    /** lastApplied overlay first, then the live seam, then empty. */
    private Optional<RecordingSettingsAccess.Snapshot> recordingSnapshot() {
        if (lastAppliedRecording != null) {
            return Optional.of(lastAppliedRecording);
        }
        RecordingSettingsAccess access = recordingSettingsAccess;
        return access == null ? Optional.empty() : Optional.of(access.current());
    }

    /** lastApplied overlay first, then the live seam, then empty. */
    private Optional<BackupRetentionPolicy> backupPolicySnapshot() {
        if (lastAppliedBackupPolicy != null) {
            return Optional.of(lastAppliedBackupPolicy);
        }
        BackupSettingsAccess access = backupSettingsAccess;
        return access == null ? Optional.empty() : Optional.of(access.currentPolicy());
    }

    // ── Render hints ─────────────────────────────────────────────────────────

    /**
     * Per-descriptor {@link SettingRow.ControlHints}: choice options in
     * the row's VALUE type (so {@code Objects.equals} works against
     * persisted values), slider bounds mirroring the setter guards,
     * display converters, localized apply-class badge text (§6.4), and
     * the shared ↺ reset graphic factory (§5.7 tier 1).
     */
    private Map<String, SettingRow.ControlHints> buildControlHints() {
        String engineBadge = msg("settings.badge.engineReconfigure");
        String restartBadge = msg("settings.badge.restartRequired");
        Supplier<Node> resetGraphic = () -> DawgIcon.of("rotate-ccw", DawgIcon.Size.SIZE_16);

        Map<String, SettingRow.ControlHints> hints = new HashMap<>();
        for (SettingDescriptor<?> descriptor : catalogue.descriptors()) {
            String id = descriptor.id();
            String badge = switch (descriptor.applyClass()) {
                case LIVE -> null; // badge omitted entirely (§6.4)
                case ENGINE_RECONFIGURE -> engineBadge;
                case RESTART_REQUIRED -> restartBadge;
            };
            hints.put(id, new SettingRow.ControlHints(
                    choiceOptionsFor(id),
                    sliderMinFor(id), sliderMaxFor(id),
                    converterFor(id),
                    badge, resetGraphic));
        }
        return hints;
    }

    /**
     * Choice options per id. Backend/input/output devices deliberately
     * get none (the row shows the current value only — no enumeration
     * before asynchronous controller discovery replaces the list).
     */
    private List<?> choiceOptionsFor(String id) {
        return switch (id) {
            case "audio.sampleRate" ->
                    List.of(44100.0, 48000.0, 88200.0, 96000.0, 176400.0, 192000.0);
            case "audio.bitDepth" -> List.of(16, 24, 32);
            case "audio.bufferSize" -> List.of(64, 128, 256, 512, 1024, 2048);
            case "audio.mixPrecision" -> List.of(MixPrecision.values());
            case "audio.srcQuality" -> List.of(QualityTier.values());
            // The current value is appended by the row when missing.
            case "audio.workerPoolSize" -> List.of(1, 2, 4, 6, 8, 12, 16);
            case "audio.masterCpuBudgetPolicy" -> policyOptions();
            case "project.autoSaveIntervalSeconds" -> List.of(30, 60, 120, 300, 600);
            case ThemeManager.PREF_KEY -> List.of(ThemeManager.Theme.values());
            case DensityManager.PREF_KEY -> List.of(DensityMode.values());
            case "metronome.countIn" -> List.of(CountInMode.values());
            // "" = "Main mix only"; the live bus list replaces this when
            // a Recording access seam attaches (refreshCueBusChoiceOptions).
            case "metronome.clickCueBus" -> List.of("");
            default -> null;
        };
    }

    /**
     * Policy options are the persisted short-name Strings: current +
     * catalogue default (no full enumeration surface until a later story).
     */
    private List<String> policyOptions() {
        return List.of("DoNothing", "BypassExpensive", "ReduceOversampling",
                "SubstituteSimpleKernel");
    }

    /**
     * Slider lower bounds: uiScale mirrors the old {@code Slider(0.5, 3.0)}
     * / the {@code setUiScale} guard; the CPU-budget fraction mirrors the
     * former audio surface's 0.01 floor — the closest
     * expressible bound to {@code setMasterCpuBudgetFraction}'s exclusive
     * {@code (0.0, 1.0]} guard (a 0.0 slider stop would be rejected on
     * Apply).
     */
    private static double sliderMinFor(String id) {
        return switch (id) {
            case "appearance.uiScale" -> 0.5;
            case "audio.masterCpuBudgetFraction" -> 0.01;
            default -> 0;
        };
    }

    /**
     * Slider/stepper upper bounds — see {@link #sliderMinFor(String)}.
     * The story-308 absorbed bounds mirror the deleted dialogs' spinner
     * and slider ranges (the model authorities only enforce &gt;= 0);
     * {@code metronome.clickGain} rides the (0, 1) default.
     */
    private static double sliderMaxFor(String id) {
        return switch (id) {
            case "appearance.uiScale" -> 3.0;
            case "audio.masterCpuBudgetFraction" -> 1.0;
            case "metronome.clickChannel" -> 63;
            case "backups.keepRecent" -> 100;
            case "backups.keepHourly" -> 168;
            case "backups.keepDaily" -> 60;
            case "backups.keepWeekly" -> 52;
            case "backups.maxAgeDays" -> 365;
            case "backups.maxDiskGiB" -> 64;
            default -> 1;
        };
    }

    /**
     * Display converters for the choice rows whose value's
     * {@code toString()} is not presentable: sample rate renders as a
     * whole number ("44100"), theme/density through their managers'
     * localized {@code displayName}. Enum-valued rows (mix precision,
     * SRC quality) read fine as names and get none.
     */
    private StringConverter<Object> converterFor(String id) {
        return switch (id) {
            case "audio.sampleRate" -> displayConverter(value ->
                    value instanceof Double d
                            ? String.valueOf((int) d.doubleValue())
                            : String.valueOf(value));
            case "audio.backend", "audio.inputDevice", "audio.outputDevice" ->
                    displayConverter(value ->
                    value instanceof String name && name.isBlank()
                            ? msg("audio.device.automatic")
                            : String.valueOf(value));
            case ThemeManager.PREF_KEY -> displayConverter(value ->
                    value instanceof ThemeManager.Theme theme
                            ? ThemeManager.displayName(theme)
                            : String.valueOf(value));
            case DensityManager.PREF_KEY -> displayConverter(value ->
                    value instanceof DensityMode mode
                            ? DensityManager.displayName(mode)
                            : String.valueOf(value));
            case "metronome.countIn" -> displayConverter(value ->
                    value instanceof CountInMode mode
                            ? formatEnumName(mode.name())
                            : String.valueOf(value));
            // Clones the 307 audio.inputDevice blank-value pattern: ""
            // renders as the localized "Main mix only"; a UUID string
            // renders through the live display map (falling back to the
            // raw id for a bus that vanished between refreshes).
            case "metronome.clickCueBus" -> displayConverter(value -> {
                String text = String.valueOf(value);
                return text.isBlank()
                        ? msg("metronome.clickCueBus.mainMixOnly")
                        : cueBusDisplayById.getOrDefault(text, text);
            });
            default -> null;
        };
    }

    /** "ONE_BAR" → "One bar" (the metronome context-menu formatter idiom). */
    private static String formatEnumName(String name) {
        String lower = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Display-only converter — the combos are non-editable, so {@code fromString} never runs. */
    private static StringConverter<Object> displayConverter(Function<Object, String> display) {
        return new StringConverter<>() {
            @Override
            public String toString(Object object) {
                return object == null ? "" : display.apply(object);
            }

            @Override
            public Object fromString(String string) {
                return null; // display-only; never invoked on a non-editable ComboBox
            }
        };
    }

    // ── Close paths (§6.2) ───────────────────────────────────────────────────

    /**
     * The shared guard for every close path. Clean closes wait for any
     * outstanding audio transaction so a late driver failure remains visible.
     * Dirty → runs the (injectable) Apply / Discard / Cancel prompt:
     * APPLY applies then allows the close, DISCARD drops the pending
     * edits (no write) then allows it, CANCEL keeps the dialog open.
     *
     * <p>APPLY and DISCARD both leave the shell clean on purpose: in
     * JavaFX 26 {@code Dialog.hide()} delegates to {@code close()},
     * which re-fires {@code DIALOG_CLOSE_REQUEST} — i.e. this guard runs
     * again on the actual close. A still-dirty shell would re-prompt;
     * a clean one falls through immediately.</p>
     *
     * @return {@code true} when closing may proceed immediately
     */
    private boolean confirmClose() {
        if (!shell.dirtyProperty().get()) {
            return allowCloseOrWaitForAudio();
        }
        DirtyChoice choice = dirtyClosePrompt.get();
        return switch (choice == null ? DirtyChoice.CANCEL : choice) {
            case APPLY -> {
                applyAndCloseWhenReady();
                yield false;
            }
            case DISCARD -> {
                // Re-seeding from the untouched authorities IS the
                // discard — nothing is written to model or managers.
                shell.refreshFromPersisted();
                yield allowCloseOrWaitForAudio();
            }
            case CANCEL -> false;
        };
    }

    private boolean allowCloseOrWaitForAudio() {
        if (outstandingAudioReconfigures.get() == 0) {
            return true;
        }
        closeAfterAudioApply = true;
        return false;
    }

    /** Programmatic close from the shell's footer callbacks. */
    private void closeShell() {
        setResult(null);
        hide();
    }

    /**
     * The default §6.2 dirty-close prompt: a real three-button
     * {@link DawgDialog} modal (Apply / Discard / Cancel). Dismissing the
     * prompt any other way counts as Cancel (the dialog stays open).
     */
    private DirtyChoice promptDirtyClose() {
        DawgDialog<ButtonType> prompt = new DawgDialog<>();
        prompt.setTitle(msg("settings.dirtyPrompt.title"));
        prompt.setHeaderText(msg("settings.dirtyPrompt.header"));
        ButtonType apply = new ButtonType(msg("dialog.apply"), ButtonBar.ButtonData.OK_DONE);
        ButtonType discard = new ButtonType(msg("settings.dirtyPrompt.discard"),
                ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType(msg("dialog.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        prompt.getDialogPane().getButtonTypes().setAll(discard, cancel, apply);
        prompt.setResultConverter(buttonType -> buttonType);
        prompt.initOwner(getDialogPane().getScene() != null
                ? getDialogPane().getScene().getWindow() : null);
        Optional<ButtonType> pressed = prompt.showAndWait();
        if (pressed.isEmpty()) {
            return DirtyChoice.CANCEL;
        }
        if (pressed.get() == apply) {
            return DirtyChoice.APPLY;
        }
        if (pressed.get() == discard) {
            return DirtyChoice.DISCARD;
        }
        return DirtyChoice.CANCEL;
    }

    /**
     * Injects a headless-test replacement for the dirty-close prompt.
     *
     * @param prompt the stub prompt (must not be {@code null})
     */
    void setDirtyClosePrompt(Supplier<DirtyChoice> prompt) {
        this.dirtyClosePrompt = Objects.requireNonNull(prompt, "prompt must not be null");
    }

    // ── Apply ────────────────────────────────────────────────────────────────

    /**
     * Applies the shell's pending edits through the same authorities as
     * the old tabbed dialog (§6.2 — the manager contract does not
     * change): model setters for every dirty model-backed id
     * (write-on-dirty), {@code KeyBindingManager} clear-all-then-set with
     * the pending-else-persisted effective map, then the UNCONDITIONAL
     * story-294 {@link UiEvent.SettingsApplied} publish carrying
     * pending-else-current theme/density/motion, then the
     * {@link SettingsChangeListener}, then a shell re-seed from the
     * persisted authorities.
     */
    void applySettings() {
        shell.clearOperationNotice();
        Map<String, Object> pendingEdits = shell.pendingEdits();
        // A clean Apply/OK after an error is an explicit retry of the close
        // path. It can race the worker's final outstanding-count decrement;
        // any genuinely later failure will set the flag again.
        if (outstandingAudioReconfigures.get() == 0 || pendingEdits.isEmpty()) {
            audioQueueFailed.set(false);
        }

        AudioEngineController controller = audioEngineController;
        boolean engineReconfigureRequested = controller != null
                && requiresLiveAudioReconfigure(pendingEdits);
        boolean audioWorkAlreadyQueued = !asynchronousPersistedValues.isEmpty();
        Map<String, Object> deferredAudioEdits = controller != null
                && (engineReconfigureRequested || audioWorkAlreadyQueued)
                ? pendingEdits.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(AUDIO_PREFIX))
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, Map.Entry::getValue,
                                (_, replacement) -> replacement, java.util.LinkedHashMap::new))
                : Map.of();
        asynchronousPersistedValues.putAll(deferredAudioEdits);

        for (Map.Entry<String, Object> edit : pendingEdits.entrySet()) {
            if (!deferredAudioEdits.containsKey(edit.getKey())) {
                applyModelSetting(edit.getKey(), edit.getValue());
            }
        }

        applyKeyBindings(pendingEdits);
        applyRecordingSettings(pendingEdits);
        applyBackupSettings(pendingEdits);

        // Story 294 — appearance changes propagate through the shared
        // EventBus instead of this dialog poking the three managers
        // directly (Control Synchronization Design Book §6.7). The
        // publish is UNCONDITIONAL on Apply (today's behaviour), with
        // pending-else-current values so it is always well-formed.
        ThemeManager.Theme theme =
                pendingEdits.get(ThemeManager.PREF_KEY) instanceof ThemeManager.Theme t
                        ? t : themeManager.getActiveTheme();
        DensityMode density =
                pendingEdits.get(DensityManager.PREF_KEY) instanceof DensityMode d
                        ? d : densityManager.getActiveDensity();
        boolean reduceMotion =
                pendingEdits.get(MotionManager.PREF_KEY) instanceof Boolean b
                        ? b : motionManager.isReduceMotion();
        // Captured for currentPersistedValue(): the publish below lands on
        // a later FX pulse, so the shell re-seed at the end of this method
        // must read the just-published values, not the stale managers.
        lastAppliedTheme = theme;
        lastAppliedDensity = density;
        lastAppliedReduceMotion = reduceMotion;
        EventBusPublisher.publish(new UiEvent.SettingsApplied(
                Instant.now(), theme.name(), density.name(), reduceMotion));

        SettingsChangeListener listenerAfterApply = settingsChangeListener;
        if (listenerAfterApply != null && deferredAudioEdits.isEmpty()) {
            listenerAfterApply.onSettingsChanged(model);
        }

        if (controller != null
                && (engineReconfigureRequested || !deferredAudioEdits.isEmpty())) {
            LiveAudioEndpoint liveEndpoint = currentLiveAudioEndpoint(controller);
            boolean backendRemainsActive = !pendingEdits.containsKey("audio.backend")
                    && Objects.equals(model.getAudioBackend(), liveEndpoint.backend());
            String requestedInput = backendRemainsActive
                    && pendingEdits.get("audio.inputDevice") instanceof String input
                            ? input : liveEndpoint.inputDevice();
            String requestedOutput = backendRemainsActive
                    && pendingEdits.get("audio.outputDevice") instanceof String output
                            ? output : liveEndpoint.outputDevice();
            enqueueAudioReconfigure(new AudioReconfigureSnapshot(
                    controller,
                    liveEndpoint.backend(),
                    requestedInput,
                    requestedOutput,
                    effectiveNumber(pendingEdits, "audio.sampleRate").doubleValue(),
                    effectiveNumber(pendingEdits, "audio.bufferSize").intValue(),
                    effectiveNumber(pendingEdits, "audio.bitDepth").intValue(),
                    effectiveNumber(pendingEdits, "audio.workerPoolSize").intValue(),
                    effectiveValue(pendingEdits, "audio.mixPrecision", MixPrecision.class),
                    effectiveValue(pendingEdits, "audio.srcQuality", QualityTier.class),
                    pendingEdits.containsKey("audio.sampleRate"),
                    pendingEdits.containsKey("audio.bufferSize"),
                    pendingEdits.containsKey("audio.bitDepth"),
                    pendingEdits.containsKey("audio.workerPoolSize"),
                    pendingEdits.containsKey("audio.mixPrecision"),
                    pendingEdits.containsKey("audio.srcQuality"),
                    engineReconfigureRequested,
                    deferredAudioEdits,
                    deferredAudioEdits.isEmpty() ? null : listenerAfterApply,
                    engineReconfigureRequested ? deviceEnumerationTask : null));
        }

        shell.refreshFromPersisted();
    }

    /** Applies and delays OK-close until asynchronous audio work succeeds. */
    void applyAndCloseWhenReady() {
        applySettings();
        if (outstandingAudioReconfigures.get() == 0) {
            if (!audioQueueFailed.get()) {
                FxDispatcher.runOnFx(this::closeShell);
            }
        } else {
            closeAfterAudioApply = true;
        }
    }

    private LiveAudioEndpoint currentLiveAudioEndpoint(AudioEngineController controller) {
        String activeBackend = controller.getActiveBackendName();
        LiveAudioEndpoint endpoint = liveAudioEndpoint;
        if (endpoint == null || !Objects.equals(endpoint.backend(), activeBackend)) {
            endpoint = initialLiveAudioEndpoint(controller);
            liveAudioEndpoint = endpoint;
        }
        return endpoint;
    }

    private Number effectiveNumber(Map<String, Object> pendingEdits, String id) {
        Object value = pendingEdits.containsKey(id)
                ? pendingEdits.get(id) : currentPersistedValue(id);
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalStateException("Expected numeric audio setting: " + id);
    }

    private <T> T effectiveValue(Map<String, Object> pendingEdits,
                                 String id,
                                 Class<T> valueType) {
        Object value = pendingEdits.containsKey(id)
                ? pendingEdits.get(id) : currentPersistedValue(id);
        return valueType.cast(value);
    }

    private record LiveAudioEndpoint(
            String backend, String inputDevice, String outputDevice) {}

    private record AudioRuntimeState(
            double sampleRate,
            int bufferFrames,
            int bitDepth,
            int workerPoolSize,
            MixPrecision mixPrecision,
            QualityTier srcQuality) {}

    private boolean requiresLiveAudioReconfigure(Map<String, Object> pendingEdits) {
        boolean backendAwaitsRestart = pendingEdits.containsKey("audio.backend");
        return pendingEdits.keySet().stream()
                .filter(id -> !(backendAwaitsRestart
                        && ("audio.inputDevice".equals(id)
                                || "audio.outputDevice".equals(id))))
                .map(catalogue::byId)
                .flatMap(Optional::stream)
                .anyMatch(descriptor -> descriptor.applyClass() == ApplyClass.ENGINE_RECONFIGURE);
    }

    private record AudioReconfigureSnapshot(
            AudioEngineController controller,
            String activeBackend,
            String inputDevice,
            String outputDevice,
            double sampleRate,
            int bufferFrames,
            int bitDepth,
            int workerPoolSize,
            MixPrecision mixPrecision,
            QualityTier srcQuality,
            boolean applySampleRate,
            boolean applyBufferSize,
            boolean applyBitDepth,
            boolean applyWorkerPoolSize,
            boolean applyMixPrecision,
            boolean applySrcQuality,
            boolean reconfigureEngine,
            Map<String, Object> deferredEdits,
            SettingsChangeListener listenerAfterCommit,
            DeviceEnumerationTask enumerationTask) {

        private AudioReconfigureSnapshot {
            deferredEdits = Map.copyOf(deferredEdits);
        }
    }

    /** FIFO queue over one virtual worker; controller calls never overlap. */
    private void enqueueAudioReconfigure(AudioReconfigureSnapshot snapshot) {
        if (outstandingAudioReconfigures.getAndIncrement() == 0) {
            audioQueueFailed.set(false);
        }
        audioReconfigureQueue.add(snapshot);
        FxDispatcher.runOnFx(this::updateAudioUtilityDisabledState);
        startAudioReconfigureWorker();
    }

    private void startAudioReconfigureWorker() {
        if (!audioReconfigureWorkerRunning.compareAndSet(false, true)) {
            return;
        }
        Thread worker = Thread.ofVirtual().name("settings-audio-reconfigure")
                .unstarted(this::runAudioReconfigureLoop);
        audioReconfigureWorker.set(worker);
        worker.start();
    }

    private void runAudioReconfigureLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                AudioReconfigureSnapshot snapshot = audioReconfigureQueue.poll();
                if (snapshot == null) {
                    return;
                }
                try {
                    applyAudioReconfigure(snapshot);
                } finally {
                    if (outstandingAudioReconfigures.decrementAndGet() == 0) {
                        FxDispatcher.runOnFx(this::updateAudioUtilityDisabledState);
                        restartEnumerationAfterAudioQueue();
                    }
                }
            }
        } finally {
            audioReconfigureWorker.compareAndSet(Thread.currentThread(), null);
            audioReconfigureWorkerRunning.set(false);
            if (!audioReconfigureQueue.isEmpty()) {
                startAudioReconfigureWorker();
            }
        }
    }

    private void applyAudioReconfigure(AudioReconfigureSnapshot snapshot) {
        boolean deferredCommitted = false;
        boolean locked = false;
        boolean sampleRateApplied = false;
        boolean mixPrecisionApplied = false;
        boolean srcQualityApplied = false;
        boolean reconfigureAttempted = false;
        AudioRuntimeState previousState = null;
        LiveAudioEndpoint previousEndpoint = liveAudioEndpoint;
        try {
            audioControllerOperationLock.lockInterruptibly();
            locked = true;
            if (snapshot.enumerationTask() != null) {
                snapshot.enumerationTask().cancelAndAwait();
            }
            previousState = audioRuntimeState != null
                    ? audioRuntimeState
                    : new AudioRuntimeState(snapshot.sampleRate(), snapshot.bufferFrames(),
                            snapshot.bitDepth(), snapshot.workerPoolSize(),
                            model.getMixPrecision(), model.getSrcQuality());
            if (previousEndpoint == null) {
                previousEndpoint = new LiveAudioEndpoint(snapshot.activeBackend(),
                        snapshot.inputDevice(), snapshot.outputDevice());
            }
            double effectiveSampleRate = snapshot.applySampleRate()
                    ? snapshot.sampleRate() : previousState.sampleRate();
            int effectiveBufferFrames = snapshot.applyBufferSize()
                    ? snapshot.bufferFrames() : previousState.bufferFrames();
            int effectiveBitDepth = snapshot.applyBitDepth()
                    ? snapshot.bitDepth() : previousState.bitDepth();
            int effectiveWorkerPoolSize = snapshot.applyWorkerPoolSize()
                    ? snapshot.workerPoolSize() : previousState.workerPoolSize();
            MixPrecision effectiveMixPrecision = snapshot.applyMixPrecision()
                    ? snapshot.mixPrecision() : previousState.mixPrecision();
            QualityTier effectiveSrcQuality = snapshot.applySrcQuality()
                    ? snapshot.srcQuality() : previousState.srcQuality();

            if (snapshot.applySampleRate()) {
                snapshot.controller().setSampleRate(snapshot.activeBackend(),
                        snapshot.outputDevice(), effectiveSampleRate);
                sampleRateApplied = true;
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("audio configuration interrupted");
                }
            }
            if (snapshot.applyMixPrecision()) {
                snapshot.controller().applyMixPrecision(effectiveMixPrecision);
                mixPrecisionApplied = true;
            }
            if (snapshot.applySrcQuality()) {
                snapshot.controller().applySrcQuality(effectiveSrcQuality);
                srcQualityApplied = true;
            }
            if (snapshot.reconfigureEngine()) {
                reconfigureAttempted = true;
                snapshot.controller().applyConfiguration(new AudioEngineController.Request(
                        snapshot.activeBackend(), snapshot.inputDevice(), snapshot.outputDevice(),
                        SampleRate.fromHz((int) effectiveSampleRate), effectiveBufferFrames,
                        effectiveBitDepth, effectiveWorkerPoolSize));
            }
            commitDeferredAudioEdits(snapshot);
            deferredCommitted = true;
            if (snapshot.reconfigureEngine()) {
                liveAudioEndpoint = new LiveAudioEndpoint(snapshot.activeBackend(),
                        snapshot.inputDevice(), snapshot.outputDevice());
            }
            audioRuntimeState = new AudioRuntimeState(effectiveSampleRate,
                    effectiveBufferFrames, effectiveBitDepth, effectiveWorkerPoolSize,
                    effectiveMixPrecision, effectiveSrcQuality);
            notifySettingsListener(snapshot.listenerAfterCommit());
        } catch (AudioBackendException rejected) {
            audioQueueFailed.set(true);
            restoreAudioRuntime(snapshot, previousState, previousEndpoint,
                    sampleRateApplied, mixPrecisionApplied, srcQualityApplied,
                    reconfigureAttempted);
            if (!deferredCommitted && snapshot.applySampleRate() && !sampleRateApplied) {
                LOG.log(Level.WARNING,
                        "Sample rate selection rejected by the audio driver", rejected);
                rejectDeferredAudioEdits(snapshot,
                        "Sample rate " + (int) snapshot.sampleRate()
                                + " Hz was rejected by the audio driver.");
            } else {
                LOG.log(Level.WARNING, "Failed to reconfigure the audio engine", rejected);
                if (!deferredCommitted) {
                    rejectDeferredAudioEdits(snapshot,
                            "Could not apply audio configuration: "
                                    + failureMessage(rejected));
                }
            }
        } catch (InterruptedException cancelled) {
            audioQueueFailed.set(true);
            Thread.currentThread().interrupt();
            restoreAudioRuntime(snapshot, previousState, previousEndpoint,
                    sampleRateApplied, mixPrecisionApplied, srcQualityApplied,
                    reconfigureAttempted);
            if (!deferredCommitted) {
                rejectDeferredAudioEdits(snapshot, "Audio configuration was cancelled.");
            }
        } catch (RuntimeException failure) {
            audioQueueFailed.set(true);
            LOG.log(Level.WARNING, "Failed to reconfigure the audio engine", failure);
            restoreAudioRuntime(snapshot, previousState, previousEndpoint,
                    sampleRateApplied, mixPrecisionApplied, srcQualityApplied,
                    reconfigureAttempted);
            if (!deferredCommitted) {
                rejectDeferredAudioEdits(snapshot,
                        "Could not apply audio configuration: " + failureMessage(failure));
            }
        } finally {
            if (locked) {
                audioControllerOperationLock.unlock();
            }
        }
    }

    private void restoreAudioRuntime(
            AudioReconfigureSnapshot snapshot,
            AudioRuntimeState previousState,
            LiveAudioEndpoint previousEndpoint,
            boolean sampleRateApplied,
            boolean mixPrecisionApplied,
            boolean srcQualityApplied,
            boolean reconfigureAttempted) {
        if (previousState == null || previousEndpoint == null) {
            return;
        }
        if (sampleRateApplied) {
            try {
                snapshot.controller().setSampleRate(previousEndpoint.backend(),
                        previousEndpoint.outputDevice(), previousState.sampleRate());
            } catch (RuntimeException rollbackFailure) {
                LOG.log(Level.WARNING, "Failed to restore the previous sample rate", rollbackFailure);
            }
        }
        if (reconfigureAttempted) {
            try {
                snapshot.controller().applyConfiguration(new AudioEngineController.Request(
                        previousEndpoint.backend(), previousEndpoint.inputDevice(),
                        previousEndpoint.outputDevice(),
                        SampleRate.fromHz((int) previousState.sampleRate()),
                        previousState.bufferFrames(), previousState.bitDepth(),
                        previousState.workerPoolSize()));
            } catch (RuntimeException rollbackFailure) {
                LOG.log(Level.WARNING,
                        "Failed to restore the previous audio-engine configuration",
                        rollbackFailure);
            }
        }
        if (mixPrecisionApplied) {
            try {
                snapshot.controller().applyMixPrecision(previousState.mixPrecision());
            } catch (RuntimeException rollbackFailure) {
                LOG.log(Level.WARNING, "Failed to restore mix precision", rollbackFailure);
            }
        }
        if (srcQualityApplied) {
            try {
                snapshot.controller().applySrcQuality(previousState.srcQuality());
            } catch (RuntimeException rollbackFailure) {
                LOG.log(Level.WARNING, "Failed to restore SRC quality", rollbackFailure);
            }
        }
        liveAudioEndpoint = previousEndpoint;
        audioRuntimeState = previousState;
    }

    private void commitDeferredAudioEdits(AudioReconfigureSnapshot snapshot) {
        Map<String, Object> storedValues = snapshot.deferredEdits().keySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Function.identity(), this::storedValue,
                        (_, replacement) -> replacement, java.util.LinkedHashMap::new));
        List<String> attemptedIds = new java.util.ArrayList<>();
        try {
            for (Map.Entry<String, Object> edit : snapshot.deferredEdits().entrySet()) {
                attemptedIds.add(edit.getKey());
                applyModelSetting(edit.getKey(), edit.getValue());
            }
        } catch (RuntimeException failure) {
            for (int index = attemptedIds.size() - 1; index >= 0; index--) {
                String id = attemptedIds.get(index);
                Object previous = storedValues.get(id);
                try {
                    applyModelSetting(id, previous);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        for (Map.Entry<String, Object> edit : snapshot.deferredEdits().entrySet()) {
            asynchronousPersistedValues.remove(edit.getKey(), edit.getValue());
        }
    }

    private void rejectDeferredAudioEdits(AudioReconfigureSnapshot snapshot, String notice) {
        for (Map.Entry<String, Object> edit : snapshot.deferredEdits().entrySet()) {
            asynchronousPersistedValues.remove(edit.getKey(), edit.getValue());
        }
        FxDispatcher.runOnFx(() -> {
            for (Map.Entry<String, Object> edit : snapshot.deferredEdits().entrySet()) {
                shell.settingRow(edit.getKey()).ifPresent(row -> {
                    if (Objects.equals(row.getValue(), edit.getValue())
                            && !asynchronousPersistedValues.containsKey(edit.getKey())) {
                        shell.refreshSettingFromPersisted(edit.getKey());
                    }
                });
            }
            if (notice != null) {
                shell.showOperationNotice(notice);
            }
        });
    }

    private void notifySettingsListener(SettingsChangeListener listener) {
        if (listener != null) {
            FxDispatcher.runOnFx(() -> notifySettingsListenerOnFx(listener));
        }
    }

    private void notifySettingsListenerOnFx(SettingsChangeListener listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.onSettingsChanged(model);
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "Settings change listener failed", failure);
            audioQueueFailed.set(true);
            shell.showOperationNotice("Settings were saved, but a live update failed: "
                    + failureMessage(failure));
        }
    }

    private void showAudioOperationFailure(String message) {
        FxDispatcher.runOnFx(() -> shell.showOperationNotice(message));
    }

    private void restartEnumerationAfterAudioQueue() {
        FxDispatcher.runOnFx(() -> {
            if (outstandingAudioReconfigures.get() == 0 && closeAfterAudioApply) {
                closeAfterAudioApply = false;
                if (!audioQueueFailed.get()) {
                    closeShell();
                    return;
                }
            }
            if (!backgroundWorkClosed && outstandingAudioReconfigures.get() == 0
                    && deviceEnumerationTask != null) {
                restartDeviceEnumeration(
                        shell.settingRow("audio.backend").map(SettingRow::getValue).orElse(""),
                        shell.settingRow("audio.outputDevice").map(SettingRow::getValue).orElse(""));
            }
        });
    }

    private static String failureMessage(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /**
     * Writes one dirty model-backed setting through its {@link SettingsModel}
     * setter (the same authority as before). Theme/density/motion ride the
     * SettingsApplied publish instead of a model write, and key bindings
     * are batched in {@link #applyKeyBindings(Map)} — both are no-ops here.
     */
    private void applyModelSetting(String id, Object value) {
        switch (id) {
            case "audio.sampleRate" -> model.setSampleRate(((Number) value).doubleValue());
            case "audio.bitDepth" -> model.setBitDepth(((Number) value).intValue());
            case "audio.bufferSize" -> model.setBufferSize(((Number) value).intValue());
            case "audio.mixPrecision" -> model.setMixPrecision((MixPrecision) value);
            case "audio.srcQuality" -> model.setSrcQuality((QualityTier) value);
            case "audio.backend" -> model.setAudioBackend((String) value);
            case "audio.inputDevice" -> model.setAudioInputDevice((String) value);
            case "audio.outputDevice" -> model.setAudioOutputDevice((String) value);
            case "audio.applyLatencyCompensation" ->
                    model.setApplyLatencyCompensation((Boolean) value);
            case "audio.workerPoolSize" -> model.setWorkerPoolSize(((Number) value).intValue());
            case "audio.masterCpuBudgetFraction" ->
                    model.setMasterCpuBudgetFraction(((Number) value).doubleValue());
            case "audio.masterCpuBudgetPolicy" ->
                    model.setMasterCpuBudgetPolicy(SettingsModel.parsePolicy((String) value));
            case "project.autoSaveIntervalSeconds" ->
                    model.setAutoSaveIntervalSeconds(((Number) value).intValue());
            case "project.defaultTempo" -> applyDefaultTempo(value);
            case "project.useJournaledPersistence" ->
                    model.setUseJournaledPersistence((Boolean) value);
            case "appearance.uiScale" -> model.setUiScale(((Number) value).doubleValue());
            case "plugins.scanPaths" -> {
                if (value != null) {
                    model.setPluginScanPaths((String) value);
                }
            }
            default -> {
                // Theme/density/motion (SettingsApplied publish),
                // keybinding.* (applyKeyBindings) and the story-308
                // metronome.*/backups.* ids (applyRecordingSettings /
                // applyBackupSettings) are handled elsewhere.
            }
        }
    }

    /**
     * The default-tempo write keeps the pre-306 semantics: the TEXT row
     * delivers a raw String; blank and unparseable text are silently
     * dropped, and the parsed value passes the explicit NaN check plus
     * the descriptor's setter-delegating validator before the write.
     * (Story 305 — the validator's range comparisons cannot express NaN
     * rejection, both being false for NaN, so the call site keeps the
     * explicit check.)
     */
    private void applyDefaultTempo(Object value) {
        double tempo;
        if (value instanceof Number number) {
            tempo = number.doubleValue();
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                tempo = Double.parseDouble(text);
            } catch (NumberFormatException unparseable) {
                return; // silent drop — invalid text never reaches the model
            }
        } else {
            return;
        }
        if (!Double.isNaN(tempo)
                && catalogue.byId("project.defaultTempo").orElseThrow().accepts(tempo)) {
            model.setDefaultTempo(tempo);
        }
    }

    /**
     * Story 308 — routes every pending {@code metronome.*} edit into ONE
     * Recording apply: the edited {@link ClickOutput} is built over the
     * current snapshot (unedited fields keep their current values), a
     * blank cue-bus value means "Main mix only". Write-on-dirty: skipped
     * entirely when no metronome row is dirty or no access seam is
     * attached (bare test dialogs edit rows without a live authority).
     */
    private void applyRecordingSettings(Map<String, Object> pendingEdits) {
        boolean touched = pendingEdits.keySet().stream()
                .anyMatch(id -> id.startsWith(METRONOME_PREFIX));
        RecordingSettingsAccess access = recordingSettingsAccess;
        if (!touched || access == null) {
            return;
        }
        RecordingSettingsAccess.Snapshot current =
                recordingSnapshot().orElseGet(access::current);
        boolean enabled = pendingEdits.get("metronome.enabled") instanceof Boolean b
                ? b : current.enabled();
        CountInMode countIn = pendingEdits.get("metronome.countIn") instanceof CountInMode mode
                ? mode : current.countIn();
        ClickOutput clickOutput = current.clickOutput();
        if (pendingEdits.get("metronome.clickChannel") instanceof Number channel) {
            clickOutput = clickOutput.withHardwareChannelIndex(
                    Math.max(0, channel.intValue()));
        }
        if (pendingEdits.get("metronome.clickGain") instanceof Number gain) {
            clickOutput = clickOutput.withGain(Math.clamp(gain.doubleValue(), 0.0, 1.0));
        }
        if (pendingEdits.get("metronome.clickSideOutput") instanceof Boolean sideOutput) {
            clickOutput = clickOutput.withSideOutputEnabled(sideOutput);
        }
        if (pendingEdits.get("metronome.clickMainMix") instanceof Boolean mainMix) {
            clickOutput = clickOutput.withMainMixEnabled(mainMix);
        }
        Optional<UUID> cueBusId = current.cueBusId();
        if (pendingEdits.containsKey("metronome.clickCueBus")) {
            cueBusId = parseCueBusId(pendingEdits.get("metronome.clickCueBus"));
        }
        RecordingSettingsAccess.Snapshot edited = new RecordingSettingsAccess.Snapshot(
                enabled, countIn, clickOutput, cueBusId);
        // Overlay BEFORE the write: the metronome JSON-store save is
        // fire-and-forget, and the post-apply refreshFromPersisted()
        // must re-seed from what was just applied.
        lastAppliedRecording = edited;
        access.apply(edited);
    }

    private static Optional<UUID> parseCueBusId(Object value) {
        String text = value == null ? "" : value.toString();
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty(); // a vanished/garbled bus id = main mix only
        }
    }

    /**
     * Story 308 — routes every pending {@code backups.*} edit into ONE
     * {@link BackupRetentionPolicy} (days → whole-day Duration, GiB →
     * bytes; unedited fields keep their current values) and hands it to
     * the asynchronous controller apply. Write-on-dirty, seam-tolerant —
     * same contract as {@link #applyRecordingSettings(Map)}.
     */
    private void applyBackupSettings(Map<String, Object> pendingEdits) {
        boolean touched = pendingEdits.keySet().stream()
                .anyMatch(id -> id.startsWith(BACKUPS_PREFIX));
        BackupSettingsAccess access = backupSettingsAccess;
        if (!touched || access == null) {
            return;
        }
        BackupRetentionPolicy policy = backupPolicySnapshot().orElseGet(access::currentPolicy);
        if (pendingEdits.get("backups.keepRecent") instanceof Number keepRecent) {
            policy = policy.withKeepRecent(Math.max(0, keepRecent.intValue()));
        }
        if (pendingEdits.get("backups.keepHourly") instanceof Number keepHourly) {
            policy = policy.withKeepHourly(Math.max(0, keepHourly.intValue()));
        }
        if (pendingEdits.get("backups.keepDaily") instanceof Number keepDaily) {
            policy = policy.withKeepDaily(Math.max(0, keepDaily.intValue()));
        }
        if (pendingEdits.get("backups.keepWeekly") instanceof Number keepWeekly) {
            policy = policy.withKeepWeekly(Math.max(0, keepWeekly.intValue()));
        }
        if (pendingEdits.get("backups.maxAgeDays") instanceof Number maxAgeDays) {
            policy = policy.withMaxAge(java.time.Duration.ofDays(
                    Math.max(0L, Math.round(maxAgeDays.doubleValue()))));
        }
        if (pendingEdits.get("backups.maxDiskGiB") instanceof Number maxDiskGib) {
            policy = policy.withMaxBytes(
                    Math.max(0L, Math.round(maxDiskGib.doubleValue())) * BYTES_PER_GIB);
        }
        // Overlay BEFORE the write: the retention save+prune runs on the
        // controller's scheduler thread; the synchronous post-apply
        // re-seed must not re-read the not-yet-written store.
        lastAppliedBackupPolicy = policy;
        access.applyPolicy(policy);
    }

    /**
     * Persists the effective key bindings — pending edit if present,
     * else the currently persisted binding — with the existing
     * clear-all-then-set loop (no transient conflicts while re-assigning).
     *
     * @param pendingEdits the shell's pending snapshot; a {@code null}
     *                     value clears that action's binding
     */
    private void applyKeyBindings(Map<String, Object> pendingEdits) {
        KeyBindingManager keyBindingManager = model.getKeyBindingManager();

        // Effective map FIRST (reads must precede the clear).
        Map<DawAction, KeyCombination> effective = new EnumMap<>(DawAction.class);
        for (DawAction action : DawAction.values()) {
            String id = KEYBINDING_PREFIX + action.name();
            KeyCombination combo;
            if (pendingEdits.containsKey(id)) {
                combo = (KeyCombination) pendingEdits.get(id);
            } else {
                combo = keyBindingManager.getBinding(action).orElse(null);
            }
            if (combo != null) {
                effective.put(action, combo);
            }
        }

        // First clear all bindings so we can re-assign without transient conflicts
        for (DawAction action : DawAction.values()) {
            keyBindingManager.setBinding(action, null);
        }
        // Then apply the effective bindings
        for (DawAction action : DawAction.values()) {
            KeyCombination combo = effective.get(action);
            if (combo != null) {
                keyBindingManager.setBinding(action, combo);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolves a localized string from the shared {@code Messages}
     * bundle, falling back to the raw key if absent (mirrors the
     * {@code DawgDialog#msg} / {@code BrowserPanel#msg} pattern —
     * Skill §14).
     */
    private static String msg(String key) {
        try {
            return MESSAGES.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    private static String formatMessage(String key, Object... arguments) {
        return new MessageFormat(msg(key), MESSAGES.getLocale()).format(arguments);
    }

    /**
     * Returns the settings model backing this dialog (for testing).
     */
    SettingsModel getModel() {
        return model;
    }

    /**
     * Returns the shell rendering this dialog's body (for testing —
     * dialog-level tests drive rows and the dirty state through it).
     */
    SettingsShell getShell() {
        return shell;
    }

    Button getTestToneButton() {
        return testToneButton;
    }

    Button getControlPanelButton() {
        return controlPanelButton;
    }

    CheckBox getWasapiExclusiveCheck() {
        return wasapiExclusiveCheck;
    }

    ComboBox<ClockSource> getClockSourceCombo() {
        return clockSourceCombo;
    }

    Label getLatencyValue() {
        return latencyValue;
    }

    Label getCpuLoadValue() {
        return cpuLoadValue;
    }

    Label getThreadsInUseValue() {
        return threadsInUseValue;
    }
}
