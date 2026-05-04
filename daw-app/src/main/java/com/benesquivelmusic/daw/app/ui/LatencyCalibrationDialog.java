package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo;
import com.benesquivelmusic.daw.sdk.audio.LatencyCalibration;
import com.benesquivelmusic.daw.sdk.audio.LatencyCalibration.CalibrationResult;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Modal dialog driving the user-facing latency calibration workflow.
 *
 * <p>The user picks an input source — typically a hardware loopback
 * port or a measurement microphone — and clicks "Run calibration".
 * The supplied {@link CalibrationRunner} plays a single-sample impulse
 * out and captures it back, returning a
 * {@link LatencyCalibration.CalibrationResult}. The dialog converts
 * that result into one of three on-screen outcomes:</p>
 * <ol>
 *   <li><b>Inconclusive</b> — capture buffer was silent. Result panel
 *       shows the explanation; user can re-run.</li>
 *   <li><b>Within tolerance</b> — {@code |delta| &le; 64 frames}
 *       (the {@link LatencyCalibration#DEFAULT_NOTIFICATION_THRESHOLD_FRAMES}
 *       limit). Result panel reports the measured value; the
 *       "Use calibrated value as override" button is disabled
 *       because the driver report is already accurate enough.</li>
 *   <li><b>Out of tolerance</b> — {@code |delta| &gt; 64 frames}.
 *       A yellow warning surfaces ("Driver-reported latency may be
 *       off by N samples") and the user is asked to choose
 *       <em>"Use calibrated value as override"</em> or
 *       <em>"Keep driver report"</em>.</li>
 * </ol>
 *
 * <p>The dialog returns the calibration override (in sample frames)
 * when the user accepts it; otherwise it returns
 * {@link Optional#empty()}.</p>
 *
 * <p>Re-entrancy is guarded: while a calibration is running the
 * Run button is disabled and a fresh click is a no-op. This
 * matches the issue's requirement: "the harness must guarantee
 * the calibration cannot be re-entered".</p>
 *
 * <p>The runner is intentionally a SAM so headless tests can supply
 * a deterministic synthetic capture — the issue specifies a
 * 208-frame round-trip impulse for the harness test.</p>
 */
public final class LatencyCalibrationDialog extends Dialog<LatencyCalibrationDialog.Result> {

    /**
     * Tri-state result returned by the dialog so callers can
     * distinguish an accepted override, an explicit "keep driver
     * report" (which clears any pre-existing override), and a
     * simple cancel/dismiss.
     */
    public sealed interface Result {
        /** The user accepted the calibration override. */
        record AcceptOverride(int frames) implements Result {}
        /** The user explicitly chose "Keep driver report" — clear any existing override. */
        record ClearOverride() implements Result {}
        /** The dialog was dismissed without a decision (Close/cancel). */
        record Cancelled() implements Result {}
    }

    /**
     * Stub-friendly seam over the audio-thread impulse playback /
     * capture loop. Implementations run on a worker thread (the
     * dialog uses {@link Thread#ofPlatform()} internally — never
     * blocks the FX thread).
     */
    @FunctionalInterface
    public interface CalibrationRunner {
        /**
         * Runs one impulse-and-capture round-trip and returns the
         * measurement result.
         *
         * @param input the user-selected input source. Never null.
         * @return the calibration result. Never null.
         * @throws Exception when the calibration cannot complete
         *                   (e.g. device disappeared mid-run); the
         *                   dialog surfaces the message in the result
         *                   panel.
         */
        CalibrationResult run(AudioChannelInfo input) throws Exception;
    }

    private static final double HEADER_ICON_SIZE = 24;

    /** Issue-specified default threshold for the "may be off by N samples" warning. */
    static final int WARNING_THRESHOLD_FRAMES =
            LatencyCalibration.DEFAULT_NOTIFICATION_THRESHOLD_FRAMES;

    private final ChoiceBox<AudioChannelInfo> inputCombo;
    private final Button runButton;
    private final Button acceptOverrideButton;
    private final Button keepDriverButton;
    private final Label statusLabel;
    private final Label resultLabel;
    private final Label warningLabel;
    private final ProgressIndicator progressIndicator;
    private final double sampleRateHz;
    private final CalibrationRunner runner;

    /** Reentrancy guard — the issue requires calibration cannot be re-entered. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Last successful result, or null until the user runs. */
    private CalibrationResult lastResult;

    /** Override the user accepted, or null if no override accepted. */
    private Integer acceptedOverrideFrames;

    /** True when the user explicitly chose "Keep driver report" (clear override). */
    private boolean explicitClearOverride;

    /**
     * Creates a new calibration dialog.
     *
     * @param inputs        list of available input sources (loopback
     *                      / measurement microphone); never null. May
     *                      be empty in which case the run button is
     *                      disabled.
     * @param sampleRateHz  the active sample rate (Hz); must be positive
     * @param runner        the calibration runner the dialog invokes
     *                      when the user clicks "Run calibration";
     *                      never null
     */
    public LatencyCalibrationDialog(List<AudioChannelInfo> inputs,
                                    double sampleRateHz,
                                    CalibrationRunner runner) {
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(runner, "runner must not be null");
        if (!(sampleRateHz > 0)) {
            throw new IllegalArgumentException(
                    "sampleRateHz must be positive: " + sampleRateHz);
        }
        this.sampleRateHz = sampleRateHz;
        this.runner = runner;

        setTitle("Latency Calibration");
        setHeaderText("Measure your driver's actual round-trip latency");
        setGraphic(IconNode.of(DawIcon.MICROPHONE, HEADER_ICON_SIZE));

        inputCombo = new ChoiceBox<>();
        inputCombo.getItems().setAll(inputs);
        if (!inputs.isEmpty()) {
            inputCombo.getSelectionModel().selectFirst();
        }
        // Display channel name in the dropdown.
        inputCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(AudioChannelInfo info) {
                return info == null ? "" : info.displayName();
            }
            @Override public AudioChannelInfo fromString(String s) {
                return null; // not editable
            }
        });

        runButton = new Button("Run calibration");
        runButton.setGraphic(IconNode.of(DawIcon.MICROPHONE, 12));
        runButton.setDisable(inputs.isEmpty());
        runButton.setOnAction(e -> startCalibration());

        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(18, 18);
        progressIndicator.setVisible(false);
        progressIndicator.setManaged(false);

        statusLabel = new Label("Connect a loopback cable or measurement mic, then run.");
        statusLabel.setStyle("-fx-text-fill: #a0a0a0;");

        resultLabel = new Label("");
        resultLabel.setWrapText(true);

        warningLabel = new Label("");
        warningLabel.setWrapText(true);
        warningLabel.setVisible(false);
        warningLabel.setManaged(false);
        warningLabel.setStyle("-fx-text-fill: #f0c420;");

        acceptOverrideButton = new Button("Use calibrated value as override");
        acceptOverrideButton.setGraphic(IconNode.of(DawIcon.STATUS, 12));
        acceptOverrideButton.setDisable(true);
        acceptOverrideButton.setOnAction(e -> {
            if (lastResult != null && lastResult.impulseFound()) {
                acceptedOverrideFrames = lastResult.measuredFrames();
                close();
            }
        });

        keepDriverButton = new Button("Keep driver report");
        keepDriverButton.setGraphic(IconNode.of(DawIcon.INFO, 12));
        keepDriverButton.setDisable(true);
        keepDriverButton.setOnAction(e -> {
            explicitClearOverride = true;
            acceptedOverrideFrames = null;
            close();
        });

        HBox runRow = new HBox(8, new Label("Input:"), inputCombo, runButton, progressIndicator);
        runRow.setAlignment(Pos.CENTER_LEFT);

        HBox resultButtons = new HBox(8, acceptOverrideButton, keepDriverButton);
        resultButtons.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, runRow, statusLabel, resultLabel, warningLabel, resultButtons);
        content.setPadding(new Insets(12));
        content.setPrefWidth(520);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        DarkThemeHelper.applyTo(this);

        setResultConverter(button -> {
            if (acceptedOverrideFrames != null) {
                return new Result.AcceptOverride(acceptedOverrideFrames);
            }
            if (explicitClearOverride) {
                return new Result.ClearOverride();
            }
            return new Result.Cancelled();
        });
    }

    /** Visible-for-tests — kicks off a calibration without going through the FX button. */
    void runCalibrationForTest() {
        startCalibration();
    }

    /** Visible-for-tests — synchronously applies a result as if the runner had returned it. */
    void applyResultForTest(CalibrationResult result) {
        Objects.requireNonNull(result, "result must not be null");
        applyResult(result);
    }

    /** Visible-for-tests — simulates clicking "Use calibrated value as override". */
    void clickAcceptOverrideForTest() {
        acceptOverrideButton.fire();
    }

    /** Visible-for-tests — simulates clicking "Keep driver report". */
    void clickKeepDriverForTest() {
        keepDriverButton.fire();
    }

    /** Visible-for-tests accessor for the input source combo. */
    ChoiceBox<AudioChannelInfo> inputCombo() {
        return inputCombo;
    }

    /** Visible-for-tests accessor for the run button. */
    Button runButton() {
        return runButton;
    }

    /** Visible-for-tests accessor for the warning label. */
    Label warningLabel() {
        return warningLabel;
    }

    /** Visible-for-tests accessor for the accept-override button. */
    Button acceptOverrideButton() {
        return acceptOverrideButton;
    }

    /**
     * Returns the override the user has accepted, or
     * {@link Optional#empty()} if the dialog has not yet been
     * dismissed via "Use calibrated value as override".
     */
    public Optional<Integer> acceptedOverride() {
        return Optional.ofNullable(acceptedOverrideFrames);
    }

    /** Returns whether the user explicitly chose "Keep driver report" (clear override). */
    public boolean isExplicitClearOverride() {
        return explicitClearOverride;
    }

    /** Returns whether a calibration is currently in flight. */
    boolean isRunning() {
        return running.get();
    }

    private void startCalibration() {
        if (!running.compareAndSet(false, true)) {
            // Reentrancy guard — silently ignore.
            return;
        }
        runButton.setDisable(true);
        acceptOverrideButton.setDisable(true);
        keepDriverButton.setDisable(true);
        progressIndicator.setVisible(true);
        progressIndicator.setManaged(true);
        statusLabel.setText("Calibrating\u2026 (impulse round-trip in progress)");
        resultLabel.setText("");
        warningLabel.setVisible(false);
        warningLabel.setManaged(false);

        AudioChannelInfo input = inputCombo.getValue();
        Thread worker = Thread.ofPlatform()
                .name("daw-latency-calibration")
                .daemon(true)
                .unstarted(() -> {
                    try {
                        CalibrationResult r = runner.run(input);
                        Platform.runLater(() -> applyResult(r));
                    } catch (Exception ex) {
                        final String msg = ex.getMessage() != null ? ex.getMessage()
                                : ex.getClass().getSimpleName();
                        Platform.runLater(() -> applyError(msg));
                    }
                });
        worker.start();
    }

    private void applyResult(CalibrationResult result) {
        try {
            lastResult = result;
            progressIndicator.setVisible(false);
            progressIndicator.setManaged(false);
            runButton.setDisable(inputCombo.getItems().isEmpty());

            if (!result.impulseFound()) {
                statusLabel.setText("No impulse detected.");
                resultLabel.setText("Capture buffer was silent — check your loopback "
                        + "cable, input gain, and that the right input channel is selected.");
                acceptOverrideButton.setDisable(true);
                keepDriverButton.setDisable(true);
                return;
            }

            int delta = result.deltaFrames();
            statusLabel.setText("Calibration complete.");
            resultLabel.setText(String.format(Locale.ROOT,
                    "Measured round-trip: %d frames (%.2f ms). "
                            + "Driver-reported: %d frames. "
                            + "Delta: %+d frames (%.2f ms).",
                    result.measuredFrames(),
                    (result.measuredFrames() * 1000.0) / sampleRateHz,
                    result.reportedFrames(),
                    delta,
                    (delta * 1000.0) / sampleRateHz));

            if (result.shouldNotify(WARNING_THRESHOLD_FRAMES)) {
                warningLabel.setText("\u26A0 Driver-reported latency may be off by "
                        + Math.abs(delta) + " samples.");
                warningLabel.setVisible(true);
                warningLabel.setManaged(true);
                acceptOverrideButton.setDisable(false);
                keepDriverButton.setDisable(false);
            } else {
                // Within tolerance — driver is accurate enough.
                acceptOverrideButton.setDisable(true);
                keepDriverButton.setDisable(true);
            }
        } finally {
            running.set(false);
        }
    }

    private void applyError(String message) {
        try {
            progressIndicator.setVisible(false);
            progressIndicator.setManaged(false);
            runButton.setDisable(inputCombo.getItems().isEmpty());
            statusLabel.setText("Calibration failed.");
            resultLabel.setText(message);
        } finally {
            running.set(false);
        }
    }
}
