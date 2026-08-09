package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.density.DensityMode;
import com.benesquivelmusic.daw.app.ui.icons.DawgIcon;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import javafx.application.Platform;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
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
    private final HBox outcomeError = new HBox();
    private final Label outcomeErrorText = new Label();
    private final Label enumerationProgress = new Label();
    private final HBox enumerationError = new HBox();
    private final Label enumerationErrorText = new Label();
    private final Button enumerationRetryButton = new Button(msg("wizard.retry"));
    private final VBox contentBox = new VBox();
    private final Button backButton = new Button(msg("wizard.back"));
    private final Button nextButton = new Button(msg("wizard.next"));
    private final Button skipButton = new Button(msg("wizard.skip"));

    private final DeviceEnumerationTask enumerationTask;

    private Function<Map<String, Object>, CompletionStage<?>> onFinished;
    private Runnable onSkipped;
    private Runnable onOutcomeRecorded;
    private boolean closed;
    private boolean finishPending;
    private Outcome outcome;

    /** A successfully recorded terminal choice. */
    private enum Outcome {
        /** The collected settings were applied successfully. */
        FINISHED,
        /** The user dismissed setup without applying settings. */
        SKIPPED
    }

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
                    this::showEnumerationFailure);
            startDeviceEnumeration();
            rowsById.get("audio.backend").valueProperty().addListener(
                    (_, _, _) -> startDeviceEnumeration());
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
        outcomeError.setId("first-run-wizard-error");
        outcomeError.getStyleClass().add("first-run-wizard-error");
        outcomeError.setManaged(false);
        outcomeError.setVisible(false);
        outcomeErrorText.getStyleClass().add("first-run-wizard-error-text");
        outcomeErrorText.setWrapText(true);
        HBox.setHgrow(outcomeErrorText, Priority.ALWAYS);
        outcomeError.getChildren().addAll(
                DawgIcon.of("alert-triangle", DawgIcon.Size.SIZE_16), outcomeErrorText);
        enumerationProgress.setText(msg("settings.group.recomputing"));
        enumerationProgress.getStyleClass().add("first-run-wizard-progress");
        enumerationProgress.setManaged(false);
        enumerationProgress.setVisible(false);
        enumerationError.setId("first-run-wizard-enumeration-error");
        enumerationError.getStyleClass().add("first-run-wizard-enumeration-error");
        enumerationError.setManaged(false);
        enumerationError.setVisible(false);
        enumerationErrorText.getStyleClass().add("first-run-wizard-enumeration-error-text");
        enumerationErrorText.setWrapText(true);
        HBox.setHgrow(enumerationErrorText, Priority.ALWAYS);
        enumerationRetryButton.setId("first-run-wizard-enumeration-retry");
        enumerationRetryButton.getStyleClass().addAll(
                "dawg-button", "size-default", "secondary");
        enumerationRetryButton.setOnAction(_ -> startDeviceEnumeration());
        enumerationError.getChildren().addAll(
                DawgIcon.of("alert-triangle", DawgIcon.Size.SIZE_16),
                enumerationErrorText, enumerationRetryButton);
        VBox header = new VBox(titleLabel, captionLabel, outcomeError);
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
                children.add(enumerationError);
            }
            contentBox.getChildren().setAll(children);
        }
        backButton.setDisable(finishPending || step == 0);
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

    /**
     * Sets a synchronous Finish callback. This compatibility form is adapted to
     * an already-completed stage; asynchronous apply paths should use
     * {@link #setOnFinishedAsync(Function)}.
     *
     * @param callback receives {@link #collectedEdits()} on Finish; may be null
     */
    public void setOnFinished(Consumer<Map<String, Object>> callback) {
        onFinished = callback == null ? null : edits -> {
            callback.accept(edits);
            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Sets the non-blocking Finish contract. The wizard remains open and does
     * not persist completion until the returned stage succeeds.
     *
     * @param callback starts the apply and returns its actual completion; may be null
     */
    void setOnFinishedAsync(
            Function<Map<String, Object>, ? extends CompletionStage<?>> callback) {
        onFinished = callback == null ? null : edits -> callback.apply(edits);
    }

    /** @param callback runs on Skip (nothing is applied); may be null */
    public void setOnSkipped(Runnable callback) {
        this.onSkipped = callback;
    }

    /**
     * Sets the host-dismiss request that runs only after callback success,
     * flag persistence, outcome recording, and teardown.
     */
    void setOnOutcomeRecorded(Runnable callback) {
        this.onOutcomeRecorded = callback;
    }

    /**
     * Completes the wizard by applying the collected values first, then
     * persisting completion and recording the terminal outcome. A failed
     * apply leaves every terminal marker unset so Finish remains retryable.
     */
    public void finish() {
        if (closed || outcome != null || finishPending) {
            return;
        }
        clearOutcomeError();
        CompletionStage<?> completion;
        try {
            Map<String, Object> edits = collectedEdits();
            completion = onFinished == null
                    ? CompletableFuture.completedFuture(null)
                    : Objects.requireNonNull(onFinished.apply(edits),
                            "Finish callback completion must not be null");
        } catch (RuntimeException failure) {
            showFinishFailure(failure);
            return;
        }
        setFinishPending(true);
        completion.whenComplete((_, failure) -> finishCompleted(failure));
    }

    /**
     * Skips the wizard by running the Skip callback first, then persisting
     * completion and recording the terminal outcome. Nothing is applied.
     */
    public void skip() {
        if (closed || outcome != null || finishPending) {
            return;
        }
        clearOutcomeError();
        try {
            if (onSkipped != null) {
                onSkipped.run();
            }
            model.setFirstRunWizardCompleted(true);
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "First-run wizard could not be skipped", failure);
            showOutcomeError("wizard.skipError", failure);
            return;
        }
        outcome = Outcome.SKIPPED;
        close();
        requestHostDismissal();
    }

    /**
     * @return whether {@link #finish()} or {@link #skip()} has recorded
     *         this wizard's outcome (the story-309 abnormal-close guard)
     */
    public boolean hasOutcome() {
        return outcome != null;
    }

    /** @return whether Finish, rather than Skip, recorded the outcome */
    public boolean wasFinished() {
        return outcome == Outcome.FINISHED;
    }

    /** @return whether Finish is waiting for its asynchronous apply result */
    public boolean isFinishPending() {
        return finishPending;
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
        if (!closed && outcome == null) {
            skip();
        }
    }

    /** @return whether a contained Finish/Skip failure is visible */
    public boolean isOutcomeErrorVisible() {
        return outcomeError.isVisible();
    }

    /** @return the current contained outcome-failure message */
    public String outcomeErrorText() {
        return outcomeErrorText.getText();
    }

    /** @return the resolved wizard window title (for the host window) */
    public String title() {
        return msg("wizard.firstRun.title");
    }

    // ── Device enumeration (story 307 reuse) ─────────────────────────────────

    /** FX thread — the task marshals results through FxDispatcher. */
    private void applyEnumerationResult(DeviceEnumerationTask.Result result) {
        clearEnumerationFailure();
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

    private void startDeviceEnumeration() {
        if (enumerationTask == null || closed) {
            return;
        }
        clearEnumerationFailure();
        enumerationTask.start(
                Objects.toString(rowsById.get("audio.backend").getValue(), ""),
                Objects.toString(rowsById.get("audio.outputDevice").getValue(), ""));
    }

    private void showEnumerationFailure(Throwable failure) {
        LOG.log(Level.WARNING, "First-run wizard device enumeration failed", failure);
        enumerationErrorText.setText(MessageFormat.format(
                msg("wizard.enumerationError"), failureMessage(failure)));
        enumerationError.setManaged(true);
        enumerationError.setVisible(true);
    }

    private void clearEnumerationFailure() {
        enumerationError.setManaged(false);
        enumerationError.setVisible(false);
        enumerationErrorText.setText("");
    }

    /** @return whether device discovery failed visibly in the audio step */
    public boolean isEnumerationErrorVisible() {
        return enumerationError.isVisible();
    }

    /** @return the current device-discovery failure text */
    public String enumerationErrorText() {
        return enumerationErrorText.getText();
    }

    private void showOutcomeError(String messageKey, Throwable failure) {
        outcomeErrorText.setText(MessageFormat.format(
                msg(messageKey), failureMessage(failure)));
        outcomeError.setManaged(true);
        outcomeError.setVisible(true);
    }

    private void clearOutcomeError() {
        outcomeError.setManaged(false);
        outcomeError.setVisible(false);
        outcomeErrorText.setText("");
    }

    private void requestHostDismissal() {
        if (onOutcomeRecorded != null) {
            onOutcomeRecorded.run();
        }
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private void finishCompleted(Throwable failure) {
        Throwable applyFailure = unwrapCompletionFailure(failure);
        Runnable completion = () -> completeFinishOnFx(applyFailure);
        if (Platform.isFxApplicationThread()) {
            completion.run();
        } else {
            FxDispatcher.runOnFx(completion);
        }
    }

    private void completeFinishOnFx(Throwable applyFailure) {
        if (!finishPending || closed || outcome != null) {
            return;
        }
        if (applyFailure != null) {
            setFinishPending(false);
            showFinishFailure(applyFailure);
            return;
        }
        try {
            model.setFirstRunWizardCompleted(true);
        } catch (RuntimeException persistenceFailure) {
            setFinishPending(false);
            showFinishFailure(persistenceFailure);
            return;
        }
        outcome = Outcome.FINISHED;
        setFinishPending(false);
        close();
        requestHostDismissal();
    }

    private void showFinishFailure(Throwable failure) {
        LOG.log(Level.WARNING, "First-run wizard settings could not be applied", failure);
        showOutcomeError("wizard.applyError", failure);
    }

    private void setFinishPending(boolean pending) {
        finishPending = pending;
        backButton.setDisable(pending || currentStep.get() == 0);
        nextButton.setDisable(pending);
        skipButton.setDisable(pending);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Tears down the audio-step enumeration; idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        finishPending = false;
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
