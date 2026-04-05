package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin.TuningResult;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.function.Consumer;

/**
 * Standalone floating window wrapping a {@link TunerDisplay}.
 *
 * <p>Provides a chromatic tuner visualization in a freely movable and
 * resizable window. Includes a toolbar with a reference pitch control
 * (adjustable A4 frequency). This allows musicians to tune instruments
 * to alternate standards such as A4 = 432 Hz or A4 = 443 Hz.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * TunerDisplayWindow window = new TunerDisplayWindow();
 * window.setOnReferencePitchChanged(hz -> tunerPlugin.setReferencePitchHz(hz));
 * window.show();
 * window.update(tuningResult);
 * }</pre>
 */
public final class TunerDisplayWindow {

    private static final double DEFAULT_WIDTH = 480;
    private static final double DEFAULT_HEIGHT = 360;

    private final Stage stage;
    private final TunerDisplay display;

    private Consumer<Double> onReferencePitchChanged;

    /**
     * Creates a new floating tuner display window.
     */
    public TunerDisplayWindow() {
        display = new TunerDisplay();

        // Reference pitch spinner (415–466 Hz, default 440, step 1)
        Spinner<Integer> refPitchSpinner = new Spinner<>();
        SpinnerValueFactory.IntegerSpinnerValueFactory factory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(415, 466, 440);
        refPitchSpinner.setValueFactory(factory);
        refPitchSpinner.setPrefWidth(80);
        refPitchSpinner.setEditable(true);
        refPitchSpinner.valueProperty().addListener((_, _, newVal) -> {
            if (onReferencePitchChanged != null && newVal != null) {
                int clamped = Math.max(415, Math.min(466, newVal));
                if (clamped != newVal) {
                    refPitchSpinner.getValueFactory().setValue(clamped);
                    return;
                }
                onReferencePitchChanged.accept((double) clamped);
            }
        });

        Label refLabel = createToolbarLabel("A4 =");
        Label hzLabel = createToolbarLabel("Hz");

        HBox toolbar = new HBox(6, refLabel, refPitchSpinner, hzLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.setStyle("-fx-background-color: #1a1a2e;");

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(display);

        stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Chromatic Tuner");

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(Color.web("#0d0d1a"));
        DarkThemeHelper.applyTo(scene);
        stage.setScene(scene);
        stage.setMinWidth(360);
        stage.setMinHeight(280);
    }

    /**
     * Sets a callback invoked when the user changes the reference pitch.
     *
     * @param listener the callback receiving the new reference pitch in Hz
     */
    public void setOnReferencePitchChanged(Consumer<Double> listener) {
        this.onReferencePitchChanged = listener;
    }

    /**
     * Shows the floating window.
     */
    public void show() {
        stage.show();
        stage.toFront();
    }

    /**
     * Hides the floating window.
     */
    public void hide() {
        stage.hide();
    }

    /**
     * Returns whether the floating window is currently showing.
     *
     * @return {@code true} if visible
     */
    public boolean isShowing() {
        return stage.isShowing();
    }

    /**
     * Updates the display with a new tuning result.
     *
     * @param result the latest tuning result, or {@code null} for no signal
     */
    public void update(TuningResult result) {
        display.update(result);
    }

    /**
     * Returns the underlying {@link TunerDisplay} component.
     *
     * @return the tuner display
     */
    public TunerDisplay getDisplay() {
        return display;
    }

    /**
     * Returns the underlying {@link Stage}.
     *
     * @return the stage
     */
    public Stage getStage() {
        return stage;
    }

    private static Label createToolbarLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 10px;");
        return label;
    }
}
