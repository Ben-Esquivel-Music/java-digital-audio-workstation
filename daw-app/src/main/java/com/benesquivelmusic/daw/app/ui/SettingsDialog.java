package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.density.DensityMode;
import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.settings.SettingDescriptor;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;
import com.benesquivelmusic.daw.sdk.event.UiEvent;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCombination;
import javafx.util.StringConverter;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Function;
import java.util.function.Supplier;

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

    // ── Callback ─────────────────────────────────────────────────────────────
    private SettingsChangeListener settingsChangeListener;
    private AudioEngineController audioEngineController;
    private final Button openAudioDeviceDialogButton;

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

        // The kept Audio-category trailing node (story-306 non-goal: the
        // Audio Device Settings dialog is absorbed in story 307).
        openAudioDeviceDialogButton = new Button("Audio Device Settings…");
        openAudioDeviceDialogButton.setGraphic(
                DawgIcon.of("headphones", DawgIcon.Size.SIZE_16));
        openAudioDeviceDialogButton.getStyleClass().addAll("dawg-button", "size-default");
        openAudioDeviceDialogButton.setDisable(audioEngineController == null);
        openAudioDeviceDialogButton.setOnAction(_ -> openAudioDeviceDialog());

        shell = new SettingsShell(catalogue,
                this::currentPersistedValue,
                buildControlHints(),
                Map.of("audio", openAudioDeviceDialogButton));

        shell.setOnApply(this::applySettings);
        shell.setOnOk(() -> {
            applySettings();
            closeShell();
        });
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
    }

    /**
     * Sets the listener to be notified after settings are applied.
     *
     * @param listener the settings change listener, or {@code null} to clear
     */
    public void setSettingsChangeListener(SettingsChangeListener listener) {
        this.settingsChangeListener = listener;
    }

    /**
     * Attaches an {@link AudioEngineController} used by the "Audio Device
     * Settings" button to open the dedicated {@link AudioSettingsDialog}.
     * When {@code null} the button is disabled.
     *
     * @param controller the controller, or {@code null} to detach
     */
    public void setAudioEngineController(AudioEngineController controller) {
        this.audioEngineController = controller;
        openAudioDeviceDialogButton.setDisable(controller == null);
    }

    private void openAudioDeviceDialog() {
        if (audioEngineController == null) {
            return;
        }
        AudioSettingsDialog dialog = new AudioSettingsDialog(model, audioEngineController);
        dialog.initOwner(getDialogPane().getScene() != null
                ? getDialogPane().getScene().getWindow() : null);
        dialog.showAndWait();
        // The audio dialog may have persisted new values — re-seed every
        // row from the authorities (replaces the old three-combo refresh).
        shell.refreshFromPersisted();
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
            // Defensive: an id this dialog does not know reads as its
            // catalogue default so the row still renders something sane.
            default -> catalogue.byId(id)
                    .map(SettingDescriptor::defaultValue).orElse(null);
        };
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
     * until story 307 absorbs the audio dialog).
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
            default -> null;
        };
    }

    /**
     * Policy options are the persisted short-name Strings: current +
     * catalogue default (no full enumeration surface until a later story).
     */
    private List<String> policyOptions() {
        String current = SettingsModel.policyName(model.getMasterCpuBudgetPolicy());
        String defaultName = (String) catalogue.byId("audio.masterCpuBudgetPolicy")
                .orElseThrow().defaultValue();
        return current.equals(defaultName)
                ? List.of(current)
                : List.of(current, defaultName);
    }

    /**
     * Slider lower bounds: uiScale mirrors the old {@code Slider(0.5, 3.0)}
     * / the {@code setUiScale} guard; the CPU-budget fraction mirrors the
     * {@code AudioSettingsDialog} slider's 0.01 floor — the closest
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

    /** Slider upper bounds — see {@link #sliderMinFor(String)}. */
    private static double sliderMaxFor(String id) {
        return switch (id) {
            case "appearance.uiScale" -> 3.0;
            case "audio.masterCpuBudgetFraction" -> 1.0;
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
            case ThemeManager.PREF_KEY -> displayConverter(value ->
                    value instanceof ThemeManager.Theme theme
                            ? ThemeManager.displayName(theme)
                            : String.valueOf(value));
            case DensityManager.PREF_KEY -> displayConverter(value ->
                    value instanceof DensityMode mode
                            ? DensityManager.displayName(mode)
                            : String.valueOf(value));
            default -> null;
        };
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
     * The shared dirty guard for every close path. Clean → close allowed.
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
     * @return {@code true} when closing may proceed
     */
    private boolean confirmClose() {
        if (!shell.dirtyProperty().get()) {
            return true;
        }
        DirtyChoice choice = dirtyClosePrompt.get();
        return switch (choice == null ? DirtyChoice.CANCEL : choice) {
            case APPLY -> {
                applySettings();
                yield true;
            }
            case DISCARD -> {
                // Re-seeding from the untouched authorities IS the
                // discard — nothing is written to model or managers.
                shell.refreshFromPersisted();
                yield true;
            }
            case CANCEL -> false;
        };
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
        Map<String, Object> pendingEdits = shell.pendingEdits();

        for (Map.Entry<String, Object> edit : pendingEdits.entrySet()) {
            applyModelSetting(edit.getKey(), edit.getValue());
        }

        applyKeyBindings(pendingEdits);

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

        if (settingsChangeListener != null) {
            settingsChangeListener.onSettingsChanged(model);
        }

        shell.refreshFromPersisted();
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
                // Theme/density/motion (SettingsApplied publish) and
                // keybinding.* (applyKeyBindings) are handled elsewhere.
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
}
