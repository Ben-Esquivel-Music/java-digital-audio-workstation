package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.StretchQuality;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Modal dialog that lets the user configure a non-destructive
 * <em>time-stretch</em> on the currently-selected audio clip(s).
 *
 * <p>UI surface for the engine pieces shipped under
 * <strong>Story 042 — Audio Time-Stretching and Pitch-Shifting</strong>:
 * {@link com.benesquivelmusic.daw.core.audio.TimeStretchClipAction},
 * {@link com.benesquivelmusic.daw.core.dsp.TimeStretchProcessor},
 * {@link StretchQuality}.</p>
 *
 * <p>Two equivalent ways of expressing the operation are exposed:</p>
 * <ul>
 *   <li><strong>Stretch ratio</strong> in {@code [0.5, 2.0]} (engine
 *       supports {@code [0.25, 4.0]} but the dialog clamps to the range
 *       called out in the issue);</li>
 *   <li><strong>Target duration</strong> formatted as {@code mm:ss.ms}.
 *       Editing one updates the other via {@link #ratioFromDuration(double, double)}
 *       / {@link #durationFromRatio(double, double)}.</li>
 * </ul>
 *
 * <p>The math helpers are {@code public static} so headless tests can
 * exercise them without needing a JavaFX scene — the dialog window
 * itself is only constructed inside {@link #showAndWait(Window, Result, double)}.</p>
 */
@com.benesquivelmusic.daw.app.ui.dialogs.LegacyDialog(
        "migrate to DawgDialog — story 276 follow-up; not a Dialog "
        + "subclass (static helper that builds a Dialog internally)")
public final class TimeStretchClipDialog {

    /** Lower clamp on the stretch ratio surfaced to the user. */
    public static final double MIN_RATIO = 0.5;
    /** Upper clamp on the stretch ratio surfaced to the user. */
    public static final double MAX_RATIO = 2.0;

    private TimeStretchClipDialog() { }

    /**
     * The settings selected by the user. {@code null} from
     * {@link #showAndWait(Window, Result, double)} when the dialog is
     * cancelled.
     *
     * @param ratio             stretch ratio (1.0 = no change, 2.0 = twice as long).
     *                          Always in {@code [MIN_RATIO, MAX_RATIO]}.
     * @param quality           algorithm quality.
     */
    public record Result(double ratio, StretchQuality quality) {
        public Result {
            Objects.requireNonNull(quality, "quality must not be null");
            if (!(ratio >= MIN_RATIO && ratio <= MAX_RATIO)) {
                throw new IllegalArgumentException(
                        "ratio must be in [" + MIN_RATIO + ", " + MAX_RATIO + "]: " + ratio);
            }
        }

        /** Default settings ({@code 1.0×}, MEDIUM quality). */
        public static Result defaults() {
            return new Result(1.0, StretchQuality.MEDIUM);
        }
    }

    /**
     * Computes the stretch ratio implied by a target duration (in seconds)
     * relative to the source duration.
     *
     * @param targetSeconds the desired clip duration in seconds (must be > 0)
     * @param sourceSeconds the source clip duration in seconds (must be > 0)
     * @return the stretch ratio clamped to {@code [MIN_RATIO, MAX_RATIO]}
     */
    public static double ratioFromDuration(double targetSeconds, double sourceSeconds) {
        if (sourceSeconds <= 0.0 || targetSeconds <= 0.0
                || Double.isNaN(targetSeconds) || Double.isNaN(sourceSeconds)) {
            return 1.0;
        }
        double r = targetSeconds / sourceSeconds;
        if (r < MIN_RATIO) return MIN_RATIO;
        if (r > MAX_RATIO) return MAX_RATIO;
        return r;
    }

    /** Inverse of {@link #ratioFromDuration(double, double)}. */
    public static double durationFromRatio(double ratio, double sourceSeconds) {
        if (sourceSeconds <= 0.0) return 0.0;
        return ratio * sourceSeconds;
    }

    /** Formats {@code seconds} as {@code mm:ss.mmm}. */
    public static String formatDuration(double seconds) {
        if (seconds < 0.0 || Double.isNaN(seconds)) seconds = 0.0;
        int totalMs = (int) Math.round(seconds * 1000.0);
        int minutes = totalMs / 60_000;
        int secs = (totalMs % 60_000) / 1000;
        int ms = totalMs % 1000;
        return String.format(Locale.ROOT, "%d:%02d.%03d", minutes, secs, ms);
    }

    /**
     * Parses a duration string of the form {@code mm:ss[.ms]} or plain
     * seconds (e.g. {@code "12.5"}) and returns it in seconds, or
     * {@link Double#NaN} if the input cannot be interpreted.
     */
    public static double parseDuration(String text) {
        if (text == null) return Double.NaN;
        String s = text.trim();
        if (s.isEmpty()) return Double.NaN;
        try {
            int colon = s.indexOf(':');
            if (colon < 0) {
                return Double.parseDouble(s);
            }
            double minutes = Double.parseDouble(s.substring(0, colon));
            double secs = Double.parseDouble(s.substring(colon + 1));
            return minutes * 60.0 + secs;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Shows the modal dialog and blocks until the user accepts or cancels.
     *
     * <p>This call must run on the JavaFX application thread. Tests should
     * exercise the static helpers ({@link #ratioFromDuration},
     * {@link #durationFromRatio}, {@link #parseDuration}) instead.</p>
     *
     * @param owner          owner window (may be {@code null})
     * @param initial        the values to pre-populate the dialog with
     * @param sourceSeconds  the source clip duration; used to translate
     *                       between ratio and target-duration fields
     * @return the chosen {@link Result}, or {@link Optional#empty()} if cancelled
     */
    public static Optional<Result> showAndWait(Window owner, Result initial, double sourceSeconds) {
        Objects.requireNonNull(initial, "initial must not be null");

        Dialog<Result> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Time-Stretch Clip");
        dialog.setHeaderText("Stretch the selected clip without changing pitch.");

        TextField ratioField = new TextField(String.format(Locale.ROOT, "%.4f", initial.ratio()));
        ratioField.setPrefColumnCount(8);

        double initialDuration = durationFromRatio(initial.ratio(), sourceSeconds);
        TextField durationField = new TextField(formatDuration(initialDuration));
        durationField.setPrefColumnCount(10);

        ChoiceBox<StretchQuality> qualityBox = new ChoiceBox<>();
        qualityBox.getItems().setAll(StretchQuality.values());
        qualityBox.setValue(initial.quality());

        // Two-way sync: when the user edits one, recompute the other.
        // Guard against feedback loops with a flag.
        boolean[] syncing = {false};
        ratioField.textProperty().addListener((_, _, v) -> {
            if (syncing[0]) return;
            try {
                double r = Double.parseDouble(v.trim());
                if (!Double.isNaN(r) && r > 0) {
                    syncing[0] = true;
                    durationField.setText(formatDuration(durationFromRatio(r, sourceSeconds)));
                    syncing[0] = false;
                }
            } catch (NumberFormatException ignored) { /* user mid-edit */ }
        });
        durationField.textProperty().addListener((_, _, v) -> {
            if (syncing[0]) return;
            double secs = parseDuration(v);
            if (!Double.isNaN(secs) && secs > 0) {
                syncing[0] = true;
                ratioField.setText(String.format(Locale.ROOT, "%.4f",
                        ratioFromDuration(secs, sourceSeconds)));
                syncing[0] = false;
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int row = 0;
        grid.add(new Label("Stretch ratio:"), 0, row);
        grid.add(ratioField, 1, row);
        grid.add(new Label("(" + MIN_RATIO + "–" + MAX_RATIO + ")"), 2, row++);
        grid.add(new Label("Target duration:"), 0, row);
        grid.add(durationField, 1, row);
        grid.add(new Label("mm:ss.ms"), 2, row++);
        grid.add(new Label("Quality:"), 0, row);
        grid.add(qualityBox, 1, row++);

        // Preview button — present per the issue spec. It is wired so it
        // never closes the dialog and never mutates the source clip; the
        // actual 2-second audition would be hooked by a controller callback
        // in a follow-up.
        ButtonType previewType = new ButtonType("Preview", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().setAll(
                previewType, ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().lookupButton(previewType).addEventFilter(
                javafx.event.ActionEvent.ACTION, javafx.event.Event::consume);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            try {
                double r = Double.parseDouble(ratioField.getText().trim());
                r = Math.max(MIN_RATIO, Math.min(MAX_RATIO, r));
                return new Result(r, qualityBox.getValue());
            } catch (NumberFormatException e) {
                return null;
            }
        });

        return dialog.showAndWait();
    }
}
