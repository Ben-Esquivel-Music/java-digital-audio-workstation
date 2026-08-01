package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.density.DensityMode;
import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The §4.D first-run wizard — story 309, Concept D: a thin LINEAR
 * sequencer over the SAME story-305 catalogue descriptors, never a
 * parallel form. Three steps — audio device → appearance → project
 * defaults — then "you're set up". No branching, no per-feature
 * onboarding beyond first sound.
 *
 * <p>Each step renders fresh generic {@link SettingRow}s (the §5.4 rows
 * contract) for its descriptor set; the audio step REUSES the story-307
 * {@link DeviceEnumerationTask} for off-thread device discovery (a
 * {@code null} {@link AudioEngineController} skips enumeration
 * gracefully — the rows still render with the persisted values).
 * Completing OR skipping sets the {@link SettingsModel} first-run flag,
 * so the wizard never auto-shows again; the {@code onFinished} callback
 * hands the collected values to a caller-side glue that seeds the
 * settings dialog's shell rows and drives the existing
 * {@code applySettings()} path (the Control-Sync cross-reference — no
 * parallel apply path).</p>
 *
 * <p>Structure for testability: this pane owns the step state
 * ({@link #currentStepProperty()}, {@link #nextStep()},
 * {@link #previousStep()}, {@link #skip()}, {@link #finish()}); the
 * window wrapper is caller-side ({@code MainController} hosts it in a
 * {@code DawgDialog}), and the auto-show decision is the pure seam
 * {@link #shouldAutoShow(SettingsModel)}.</p>
 *
 * <p>Chrome: plain label step titles — never an accent fill (§5.3,
 * rejection #6); tokens only; every progress affordance is instant and
 * Reduce-Motion-safe (no animation of any kind).</p>
 */
public final class FirstRunWizard extends BorderPane implements AutoCloseable {

    /** Stable style class — selectable as {@code .first-run-wizard}. */
    public static final String DEFAULT_STYLE_CLASS = "first-run-wizard";

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
    private static final Logger LOG = Logger.getLogger(FirstRunWizard.class.getName());

    /** The three descriptor steps (§4.D), in the fixed linear order. */
    private static final List<List<String>> STEP_IDS = List.of(
            List.of("audio.backend", "audio.inputDevice", "audio.outputDevice",
                    "audio.applyLatencyCompensation"),
            List.of(ThemeManager.PREF_KEY, DensityManager.PREF_KEY,
                    "appearance.uiScale", MotionManager.PREF_KEY),
            List.of("project.defaultTempo", "project.autoSaveIntervalSeconds"));
    private static final List<String> STEP_TITLE_KEYS = List.of(
            "wizard.step.audio", "wizard.step.appearance", "wizard.step.project");
    /** The trailing "you're set up" page index. */
    private static final int DONE_STEP = STEP_IDS.size();

    private final SettingsModel model;
    // Captured-once manager singletons (the capture-swappable-singleton
    // discipline) — current-value reads for the appearance step.
    private final ThemeManager themeManager;
    private final DensityManager densityManager;
    private final MotionManager motionManager;

    private final Map<String, SettingRow> rowsById = new LinkedHashMap<>();
    private final List<List<SettingRow>> stepRows = new ArrayList<>();

    private final ReadOnlyIntegerWrapper currentStep =
            new ReadOnlyIntegerWrapper(this, "currentStep", 0);

    private final Label titleLabel = new Label();
    private final Label captionLabel = new Label();
    private final Label enumerationProgress = new Label();
    private final VBox contentBox = new VBox();
    private final Button backButton = new Button(msg("wizard.back"));
    private final Button nextButton = new Button(msg("wizard.next"));
    private final Button skipButton = new Button(msg("wizard.skip"));

    private final DeviceEnumerationTask enumerationTask;

    private Consumer<Map<String, Object>> onFinished;
    private Runnable onSkipped;
    private boolean closed;
    /** {@code true} once {@link #finish()} or {@link #skip()} ran. */
    private boolean outcomeRecorded;

    /**
     * The pure auto-show seam: show the wizard iff the first-run flag is
     * unset. Completing or skipping sets the flag — the wizard never
     * auto-shows twice.
     *
     * @param model the shared settings model; not null
     * @return {@code true} when the wizard should auto-show at startup
     */
    public static boolean shouldAutoShow(SettingsModel model) {
        Objects.requireNonNull(model, "model must not be null");
        return !model.isFirstRunWizardCompleted();
    }

    /**
     * Builds the wizard over the catalogue descriptors, seeded with the
     * currently persisted values.
     *
     * @param catalogue  the story-305 catalogue; not null
     * @param model      the shared settings model (current values + the
     *                   first-run flag); not null
     * @param controller the live audio controller for the audio step's
     *                   device enumeration, or {@code null} to skip
     *                   enumeration (rows still render)
     */
    public FirstRunWizard(SettingsCatalogue catalogue,
                          SettingsModel model,
                          AudioEngineController controller) {
        Objects.requireNonNull(catalogue, "catalogue must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.themeManager = ThemeManager.getDefault();
        this.densityManager = DensityManager.getDefault();
        this.motionManager = MotionManager.getDefault();
        getStyleClass().add(DEFAULT_STYLE_CLASS);

        buildRows(catalogue);
        buildChrome();

        if (controller == null) {
            enumerationTask = null;
        } else {
            enumerationTask = new DeviceEnumerationTask(controller,
                    this::applyEnumerationResult,
                    this::showEnumerationProgress,
                    failure -> LOG.log(Level.WARNING,
                            "First-run wizard device enumeration failed", failure));
            enumerationTask.start(
                    Objects.toString(rowsById.get("audio.backend").getValue(), ""),
                    Objects.toString(rowsById.get("audio.outputDevice").getValue(), ""));
            rowsById.get("audio.backend").valueProperty().addListener(
                    (_, _, backend) -> enumerationTask.start(
                            Objects.toString(backend, ""),
                            Objects.toString(
                                    rowsById.get("audio.outputDevice").getValue(), "")));
        }

        showStep(0);
    }

    // ── Construction ─────────────────────────────────────────────────────────

    /**
     * One fresh {@link SettingRow} per wizard descriptor — the same
     * generic row the shell renders (§5.4), never a new form. Hints are
     * a wizard-local subset of the dialog's (choice options, slider
     * bounds, display converters, apply-class badges) for exactly these
     * ten descriptors; display names delegate to the manager authorities.
     */
    private void buildRows(SettingsCatalogue catalogue) {
        for (List<String> ids : STEP_IDS) {
            List<SettingRow> rows = new ArrayList<>();
            for (String id : ids) {
                SettingDescriptor<?> descriptor = catalogue.byId(id).orElseThrow(
                        () -> new IllegalStateException("Not catalogued: " + id));
                SettingRow row = new SettingRow(descriptor, currentValue(id), hintsFor(descriptor));
                // §3.2 cue parity with the shell — keyed off Scope only.
                switch (descriptor.scope()) {
                    case PROJECT_DEFAULTS ->
                            row.setScopeCueText(msg("settings.scope.cue.projectDefaults"));
                    case THIS_PROJECT ->
                            row.setScopeCueText(msg("settings.scope.cue.thisProject"));
                    case APPLICATION, AUDIO_DEVICE -> { }
                }
                rowsById.put(id, row);
                rows.add(row);
            }
            stepRows.add(List.copyOf(rows));
        }
    }

    /** The persisted value per wizard descriptor id (model + managers). */
    private Object currentValue(String id) {
        return switch (id) {
            case "audio.backend" -> model.getAudioBackend();
            case "audio.inputDevice" -> model.getAudioInputDevice();
            case "audio.outputDevice" -> model.getAudioOutputDevice();
            case "audio.applyLatencyCompensation" -> model.isApplyLatencyCompensation();
            case ThemeManager.PREF_KEY -> themeManager.getActiveTheme();
            case DensityManager.PREF_KEY -> densityManager.getActiveDensity();
            case MotionManager.PREF_KEY -> motionManager.isReduceMotion();
            case "appearance.uiScale" -> model.getUiScale();
            case "project.defaultTempo" -> model.getDefaultTempo();
            case "project.autoSaveIntervalSeconds" -> model.getAutoSaveIntervalSeconds();
            default -> throw new IllegalStateException("Not a wizard setting: " + id);
        };
    }

    private SettingRow.ControlHints hintsFor(SettingDescriptor<?> descriptor) {
        String badge = switch (descriptor.applyClass()) {
            case LIVE -> null; // badge omitted entirely (§6.4)
            case ENGINE_RECONFIGURE -> msg("settings.badge.engineReconfigure");
            case RESTART_REQUIRED -> msg("settings.badge.restartRequired");
        };
        List<?> options = switch (descriptor.id()) {
            case ThemeManager.PREF_KEY -> List.of(ThemeManager.Theme.values());
            case DensityManager.PREF_KEY -> List.of(DensityMode.values());
            case "project.autoSaveIntervalSeconds" -> List.of(30, 60, 120, 300, 600);
            default -> null; // device combos wait for asynchronous discovery
        };
        double sliderMin = "appearance.uiScale".equals(descriptor.id()) ? 0.5 : 0;
        double sliderMax = "appearance.uiScale".equals(descriptor.id()) ? 3.0 : 1;
        StringConverter<Object> converter = switch (descriptor.id()) {
            case "audio.backend", "audio.inputDevice", "audio.outputDevice" ->
                    displayConverter(value -> value instanceof String name && name.isBlank()
                            ? msg("audio.device.automatic") : String.valueOf(value));
            case ThemeManager.PREF_KEY ->
                    displayConverter(value -> value instanceof ThemeManager.Theme theme
                            ? ThemeManager.displayName(theme) : String.valueOf(value));
            case DensityManager.PREF_KEY ->
                    displayConverter(value -> value instanceof DensityMode mode
                            ? DensityManager.displayName(mode) : String.valueOf(value));
            default -> null;
        };
        return new SettingRow.ControlHints(options, sliderMin, sliderMax, converter,
                badge, () -> DawgIcon.of("rotate-ccw", DawgIcon.Size.SIZE_16));
    }

    /** Display-only converter — the combos are non-editable. */
    private static StringConverter<Object> displayConverter(Function<Object, String> display) {
        return new StringConverter<>() {
            @Override
            public String toString(Object object) {
                return object == null ? "" : display.apply(object);
            }

            @Override
            public Object fromString(String string) {
                return null; // never invoked on a non-editable ComboBox
            }
        };
    }

    private void buildChrome() {
        titleLabel.getStyleClass().add("first-run-wizard-title");
        captionLabel.getStyleClass().add("first-run-wizard-caption");
        enumerationProgress.setText(msg("settings.group.recomputing"));
        enumerationProgress.getStyleClass().add("first-run-wizard-progress");
        enumerationProgress.setManaged(false);
        enumerationProgress.setVisible(false);
        VBox header = new VBox(titleLabel, captionLabel);
        header.getStyleClass().add("first-run-wizard-header");
        setTop(header);

        contentBox.getStyleClass().add("first-run-wizard-rows");
        setCenter(contentBox);

        skipButton.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        skipButton.setId("first-run-wizard-skip");
        skipButton.setOnAction(_ -> skip());
        backButton.getStyleClass().addAll("dawg-button", "size-default", "secondary");
        backButton.setId("first-run-wizard-back");
        backButton.setOnAction(_ -> previousStep());
        nextButton.getStyleClass().addAll("dawg-button", "size-default", "primary");
        nextButton.setId("first-run-wizard-next");
        nextButton.setOnAction(_ -> {
            if (currentStep.get() == DONE_STEP) {
                finish();
            } else {
                nextStep();
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(skipButton, spacer, backButton, nextButton);
        buttons.getStyleClass().add("first-run-wizard-buttons");
        setBottom(buttons);
    }

    // ── Step state (linear, no branching) ────────────────────────────────────

    /** @return the current page index: steps 0–2, then {@code 3} = done */
    public ReadOnlyIntegerProperty currentStepProperty() {
        return currentStep.getReadOnlyProperty();
    }

    /** @return the current page index */
    public int getCurrentStep() {
        return currentStep.get();
    }

    /** @return the number of pages (three descriptor steps + done) */
    public int stepCount() {
        return DONE_STEP + 1;
    }

    /**
     * @return the current step's rows (empty on the done page) — the
     *         same generic {@link SettingRow} instances the step renders
     */
    public List<SettingRow> currentStepRows() {
        int step = currentStep.get();
        return step < DONE_STEP ? stepRows.get(step) : List.of();
    }

    /** Advances one page (clamped at the done page). */
    public void nextStep() {
        showStep(Math.min(currentStep.get() + 1, DONE_STEP));
    }

    /** Goes back one page (clamped at the first step). */
    public void previousStep() {
        showStep(Math.max(currentStep.get() - 1, 0));
    }

    private void showStep(int step) {
        currentStep.set(step);
        boolean done = step == DONE_STEP;
        titleLabel.setText(done ? msg("wizard.step.done") : msg(STEP_TITLE_KEYS.get(step)));
        captionLabel.setVisible(!done);
        captionLabel.setManaged(!done);
        if (!done) {
            captionLabel.setText(MessageFormat.format(
                    msg("wizard.step.caption"), step + 1, STEP_IDS.size()));
        }
        if (done) {
            Label doneMessage = new Label(msg("wizard.done.message"));
            doneMessage.getStyleClass().add("first-run-wizard-done");
            doneMessage.setWrapText(true);
            contentBox.getChildren().setAll(doneMessage);
        } else {
            List<Node> children = new ArrayList<>(stepRows.get(step));
            if (step == 0 && enumerationTask != null) {
                children.add(enumerationProgress);
            }
            contentBox.getChildren().setAll(children);
        }
        backButton.setDisable(step == 0);
        nextButton.setText(done ? msg("wizard.finish") : msg("wizard.next"));
        skipButton.setVisible(!done);
        skipButton.setManaged(!done);
    }

    // ── Outcomes ─────────────────────────────────────────────────────────────

    /**
     * @return every wizard row's current value, id → value in step order.
     *         Values identical to the persisted ones become non-pending
     *         when seeded into the shell, so an untouched wizard writes
     *         nothing (the §6.2 write-on-dirty contract).
     */
    public Map<String, Object> collectedEdits() {
        Map<String, Object> edits = new LinkedHashMap<>();
        rowsById.forEach((id, row) -> edits.put(id, row.getValue()));
        return edits;
    }

    /** @param callback receives {@link #collectedEdits()} on Finish; may be null */
    public void setOnFinished(Consumer<Map<String, Object>> callback) {
        this.onFinished = callback;
    }

    /** @param callback runs on Skip (nothing is applied); may be null */
    public void setOnSkipped(Runnable callback) {
        this.onSkipped = callback;
    }

    /**
     * Completes the wizard: sets the first-run flag (never auto-shows
     * again) and hands the collected values to {@code onFinished} — the
     * caller applies them through the settings dialog's existing Apply
     * path. Also tears down the audio-step enumeration.
     */
    public void finish() {
        outcomeRecorded = true;
        model.setFirstRunWizardCompleted(true);
        Map<String, Object> edits = collectedEdits();
        close();
        if (onFinished != null) {
            onFinished.accept(edits);
        }
    }

    /**
     * Skips the wizard: sets the first-run flag (never auto-shows again)
     * and applies NOTHING. Also tears down the audio-step enumeration.
     */
    public void skip() {
        outcomeRecorded = true;
        model.setFirstRunWizardCompleted(true);
        close();
        if (onSkipped != null) {
            onSkipped.run();
        }
    }

    /**
     * @return whether {@link #finish()} or {@link #skip()} has recorded
     *         this wizard's outcome (the story-309 abnormal-close guard)
     */
    public boolean hasOutcome() {
        return outcomeRecorded;
    }

    /**
     * Story 309 — the abnormal-close safety net. A wizard dismissed
     * without Finish or Skip (window [X], the host dialog's header close
     * glyph) would otherwise leave the first-run flag unset and auto-show
     * again on EVERY launch — an auto-nag the "never auto-shows again"
     * contract forbids. Dismissal is a choice: treat it as {@link #skip()}
     * (the wizard stays re-openable from General ▸ Startup). A no-op once
     * an outcome is recorded; the host calls this after its
     * {@code showAndWait()} returns.
     */
    public void skipIfNoOutcome() {
        if (!outcomeRecorded) {
            skip();
        }
    }

    /** @return the resolved wizard window title (for the host window) */
    public String title() {
        return msg("wizard.firstRun.title");
    }

    // ── Device enumeration (story 307 reuse) ─────────────────────────────────

    /** FX thread — the task marshals results through FxDispatcher. */
    private void applyEnumerationResult(DeviceEnumerationTask.Result result) {
        rowsById.get("audio.backend").replaceChoiceOptions(result.backendNames());
        rowsById.get("audio.inputDevice")
                .replaceChoiceOptions(result.inputDeviceNames(), "");
        rowsById.get("audio.outputDevice")
                .replaceChoiceOptions(result.outputDeviceNames(), "");
    }

    private void showEnumerationProgress(boolean running) {
        enumerationProgress.setManaged(running);
        enumerationProgress.setVisible(running);
    }

    /** Tears down the audio-step enumeration; idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (enumerationTask != null) {
            enumerationTask.close();
        }
    }

    private static String msg(String key) {
        try {
            return MESSAGES.getString(key);
        } catch (MissingResourceException missing) {
            return key;
        }
    }
}
