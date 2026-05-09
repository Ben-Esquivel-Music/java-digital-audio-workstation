package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.StretchQuality;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.Objects;
import java.util.Optional;

/**
 * Modal dialog that lets the user configure a non-destructive
 * <em>pitch-shift</em> on the currently-selected audio clip(s).
 *
 * <p>UI surface for the engine pieces shipped under
 * <strong>Story 042 — Audio Time-Stretching and Pitch-Shifting</strong>:
 * {@link com.benesquivelmusic.daw.core.audio.PitchShiftClipAction},
 * {@link com.benesquivelmusic.daw.core.dsp.PitchShiftProcessor},
 * {@link StretchQuality}.</p>
 *
 * <p>Two coupled controls:</p>
 * <ul>
 *   <li><strong>Semitones</strong> in {@code [-24, +24]} (integer step);</li>
 *   <li><strong>Cents</strong> fine-tune in {@code [-100, +100]}.</li>
 * </ul>
 * The combined offset surfaced through {@link Result#totalSemitones()} is
 * the value passed to {@link com.benesquivelmusic.daw.core.audio.PitchShiftClipAction}.
 */
public final class PitchShiftClipDialog {

    /** Lower clamp on combined semitones (engine constraint). */
    public static final double MIN_SEMITONES = -24.0;
    /** Upper clamp on combined semitones (engine constraint). */
    public static final double MAX_SEMITONES = 24.0;

    private PitchShiftClipDialog() { }

    /**
     * The settings selected by the user. {@code null} from
     * {@link #showAndWait(Window, Result)} when the dialog is cancelled.
     *
     * @param semitones integer semitones in {@code [-24, +24]}.
     * @param cents     fine-tune in {@code [-100, +100]}.
     * @param quality   algorithm quality; forwarded to
     *                  {@link com.benesquivelmusic.daw.core.audio.PitchShiftClipAction}.
     */
    public record Result(int semitones, int cents, StretchQuality quality) {
        public Result {
            Objects.requireNonNull(quality, "quality must not be null");
            if (semitones < -24 || semitones > 24) {
                throw new IllegalArgumentException(
                        "semitones must be in [-24, 24]: " + semitones);
            }
            if (cents < -100 || cents > 100) {
                throw new IllegalArgumentException(
                        "cents must be in [-100, 100]: " + cents);
            }
        }

        /** Default settings (no shift, MEDIUM quality). */
        public static Result defaults() {
            return new Result(0, 0, StretchQuality.MEDIUM);
        }

        /**
         * Returns the combined semitone offset (semitones + cents/100) clamped
         * to the engine-supported range. This is the value that should be
         * handed to {@link com.benesquivelmusic.daw.core.audio.PitchShiftClipAction}.
         */
        public double totalSemitones() {
            double total = semitones + (cents / 100.0);
            if (total < MIN_SEMITONES) return MIN_SEMITONES;
            if (total > MAX_SEMITONES) return MAX_SEMITONES;
            return total;
        }
    }

    /**
     * Shows the modal dialog and blocks until the user accepts or cancels.
     *
     * <p>This call must run on the JavaFX application thread. Tests should
     * exercise {@link Result#totalSemitones()} directly without instantiating
     * the dialog.</p>
     *
     * @param owner   owner window (may be {@code null})
     * @param initial the values to pre-populate the dialog with
     * @return the chosen {@link Result}, or {@link Optional#empty()} if cancelled
     */
    public static Optional<Result> showAndWait(Window owner, Result initial) {
        Objects.requireNonNull(initial, "initial must not be null");

        Dialog<Result> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Pitch-Shift Clip");
        dialog.setHeaderText("Shift the selected clip's pitch.");

        Spinner<Integer> semitonesSpinner = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(-24, 24, initial.semitones()));
        semitonesSpinner.setEditable(true);

        Spinner<Integer> centsSpinner = new Spinner<>(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(-100, 100, initial.cents(), 5));
        centsSpinner.setEditable(true);

        ChoiceBox<StretchQuality> qualityBox = new ChoiceBox<>();
        qualityBox.getItems().setAll(StretchQuality.values());
        qualityBox.setValue(initial.quality());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int row = 0;
        grid.add(new Label("Semitones:"), 0, row);
        grid.add(semitonesSpinner, 1, row);
        grid.add(new Label("(-24 to +24)"), 2, row++);
        grid.add(new Label("Cents:"), 0, row);
        grid.add(centsSpinner, 1, row);
        grid.add(new Label("(-100 to +100)"), 2, row++);
        grid.add(new Label("Quality:"), 0, row);
        grid.add(qualityBox, 1, row++);

        // Preview button — present per the spec. It must not close the dialog
        // or mutate the source clip.
        ButtonType previewType = new ButtonType("Preview", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().setAll(
                previewType, ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().lookupButton(previewType).addEventFilter(
                javafx.event.ActionEvent.ACTION, javafx.event.Event::consume);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            return new Result(
                    semitonesSpinner.getValue(),
                    centsSpinner.getValue(),
                    qualityBox.getValue());
        });

        return dialog.showAndWait();
    }
}
