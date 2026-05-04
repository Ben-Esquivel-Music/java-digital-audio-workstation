package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.Objects;

/**
 * Modal popup surfacing the three driver-reported round-trip latency
 * components (input, output, safety offset) alongside the total
 * round-trip in milliseconds at the active sample rate. Opens when
 * the user clicks the {@code "I/O 5.3 ms"} indicator in the transport
 * bar (story 217). Hosts a "Calibrate&hellip;" button that delegates
 * to {@link LatencyCalibrationDialog}.
 *
 * <p>The popup also surfaces a source label that reflects whether
 * the displayed total comes from the driver, from a per-device
 * calibration override the user accepted, or whether the active
 * backend has no driver report ({@link RoundTripLatency#UNKNOWN}).
 * When an override is active the badge reads
 * <em>"calibrated by user"</em> and the override total is shown
 * alongside the unchanged driver-reported components.</p>
 *
 * <p>This dialog never performs audio I/O itself — it is a passive
 * read-only window over the controller-supplied state. Only the
 * embedded "Calibrate&hellip;" button can mutate engine state, and
 * only by delegating to the supplied
 * {@link Runnable openCalibration} callback (the {@code MainController}
 * wires this to a fresh {@link LatencyCalibrationDialog}).</p>
 */
public final class IoLatencyDetailsPopup extends Dialog<Void> {

    /** Identifies how the latency total surfaced in the popup was obtained. */
    public enum SourceLabel {
        /** The displayed total comes from the active backend's driver. */
        DRIVER_REPORTED("reported by driver"),
        /** The user accepted a calibration override for the active device. */
        CALIBRATED("calibrated by user"),
        /** The active backend has no driver report ({@link RoundTripLatency#UNKNOWN}). */
        UNKNOWN("no driver report");

        private final String text;

        SourceLabel(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    private static final double HEADER_ICON_SIZE = 24;

    private final Button calibrateButton;
    private final Label sourceBadge;
    private final Label totalLabel;

    /**
     * Creates a new I/O latency details popup.
     *
     * @param driverReported   the driver-reported round-trip latency; the
     *                         three components are always shown regardless
     *                         of whether an override is active. Must not be
     *                         null. Pass {@link RoundTripLatency#UNKNOWN}
     *                         when the active backend has no report.
     * @param overrideFrames   the user's calibration override for the
     *                         active device, or {@code null} if no
     *                         override is active. When non-null this is
     *                         the value displayed as the total; the source
     *                         badge reads <em>"calibrated by user"</em>.
     * @param sampleRateHz     active sample rate (Hz); must be positive
     * @param openCalibration  callback invoked when the user clicks
     *                         "Calibrate&hellip;"; must not be null.
     *                         May open a {@link LatencyCalibrationDialog}.
     */
    public IoLatencyDetailsPopup(RoundTripLatency driverReported,
                                 Integer overrideFrames,
                                 double sampleRateHz,
                                 Runnable openCalibration) {
        Objects.requireNonNull(driverReported, "driverReported must not be null");
        Objects.requireNonNull(openCalibration, "openCalibration must not be null");
        if (!(sampleRateHz > 0)) {
            throw new IllegalArgumentException(
                    "sampleRateHz must be positive: " + sampleRateHz);
        }

        SourceLabel source = sourceLabel(driverReported, overrideFrames);
        int totalFrames = (overrideFrames != null) ? overrideFrames.intValue()
                : driverReported.totalFrames();

        setTitle("I/O Latency");
        setHeaderText("Driver-reported round-trip latency");
        setGraphic(IconNode.of(DawIcon.CLOCK, HEADER_ICON_SIZE));

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.setPadding(new Insets(4, 0, 4, 0));
        addRow(grid, 0, "Input", driverReported.inputFrames(), sampleRateHz);
        addRow(grid, 1, "Output", driverReported.outputFrames(), sampleRateHz);
        addRow(grid, 2, "Safety offset", driverReported.safetyOffsetFrames(), sampleRateHz);

        totalLabel = new Label(formatTotalLine(totalFrames, sampleRateHz));
        totalLabel.setStyle("-fx-font-weight: bold;");

        sourceBadge = new Label(source.text());
        sourceBadge.setStyle("-fx-text-fill: " + badgeColor(source) + ";");
        sourceBadge.setGraphic(IconNode.of(badgeIcon(source), 12));

        calibrateButton = new Button("Calibrate\u2026");
        calibrateButton.setGraphic(IconNode.of(DawIcon.MICROPHONE, 12));
        calibrateButton.setOnAction(e -> {
            try {
                openCalibration.run();
            } finally {
                close();
            }
        });

        HBox bottomRow = new HBox(8, sourceBadge);
        bottomRow.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, grid, totalLabel, bottomRow, calibrateButton);
        content.setPadding(new Insets(12));
        content.setPrefWidth(420);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        DarkThemeHelper.applyTo(this);
        setResultConverter(button -> null);
    }

    /** Visible-for-tests accessor — the {@code Calibrate&hellip;} action button. */
    Button calibrateButton() {
        return calibrateButton;
    }

    /** Visible-for-tests accessor — the source-of-truth badge label. */
    Label sourceBadge() {
        return sourceBadge;
    }

    /** Visible-for-tests accessor — the total-latency label (frames + ms). */
    Label totalLabel() {
        return totalLabel;
    }

    /**
     * Returns the source label that describes how a round-trip total
     * was determined — driver-reported (default), calibrated by the
     * user when an override is active, or {@link SourceLabel#UNKNOWN}
     * when the backend has no driver report and no override.
     *
     * <p>Pure function — exposed for headless test verification of the
     * label-selection rules.</p>
     */
    public static SourceLabel sourceLabel(RoundTripLatency driverReported, Integer overrideFrames) {
        Objects.requireNonNull(driverReported, "driverReported must not be null");
        if (overrideFrames != null) {
            return SourceLabel.CALIBRATED;
        }
        if (driverReported.totalFrames() == 0
                && driverReported.inputFrames() == 0
                && driverReported.outputFrames() == 0
                && driverReported.safetyOffsetFrames() == 0) {
            return SourceLabel.UNKNOWN;
        }
        return SourceLabel.DRIVER_REPORTED;
    }

    /**
     * Formats one row of the components grid as the test-friendly string
     * {@code "<N> frames (<MS> ms)"} at the given sample rate. Pure
     * function — exposed so headless tests can assert formatting without
     * driving the FX toolkit.
     *
     * @param frames        component count in sample frames; must be {@code &ge; 0}
     * @param sampleRateHz  sample rate in Hz; must be positive
     * @return formatted "{@code N frames (M.MM ms)}" string
     */
    public static String formatComponent(int frames, double sampleRateHz) {
        if (frames < 0) {
            throw new IllegalArgumentException("frames must be >= 0: " + frames);
        }
        if (!(sampleRateHz > 0)) {
            throw new IllegalArgumentException(
                    "sampleRateHz must be positive: " + sampleRateHz);
        }
        double ms = (frames * 1000.0) / sampleRateHz;
        return String.format(Locale.ROOT, "%d frames (%.2f ms)", frames, ms);
    }

    /**
     * Formats the total-line label, e.g.
     * {@code "Total round-trip: 256 frames (5.33 ms)"}. Exposed for tests.
     */
    public static String formatTotalLine(int totalFrames, double sampleRateHz) {
        return "Total round-trip: " + formatComponent(totalFrames, sampleRateHz);
    }

    private static void addRow(GridPane grid, int row, String label, int frames, double sampleRateHz) {
        Label name = new Label(label);
        Label value = new Label(formatComponent(frames, sampleRateHz));
        grid.add(name, 0, row);
        grid.add(value, 1, row);
    }

    private static String badgeColor(SourceLabel s) {
        return switch (s) {
            case DRIVER_REPORTED -> "#a0a0a0";
            case CALIBRATED      -> "#5da8ff";
            case UNKNOWN         -> "#c08020";
        };
    }

    private static DawIcon badgeIcon(SourceLabel s) {
        return switch (s) {
            case DRIVER_REPORTED -> DawIcon.INFO;
            case CALIBRATED      -> DawIcon.STATUS;
            case UNKNOWN         -> DawIcon.WARNING;
        };
    }
}
