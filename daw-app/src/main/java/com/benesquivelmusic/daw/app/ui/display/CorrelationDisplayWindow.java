package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.sdk.visualization.CorrelationData;
import com.benesquivelmusic.daw.sdk.visualization.GoniometerData;

import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Standalone floating window wrapping a {@link CorrelationDisplay}.
 *
 * <p>Provides the same stereo correlation vectorscope and goniometer
 * visualization as the embedded tile, but in a freely movable and
 * resizable window. This allows engineers to position the correlation
 * meter anywhere on screen while working in the arrangement or mixer
 * view.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * CorrelationDisplayWindow window = new CorrelationDisplayWindow();
 * window.show();
 * window.update(correlationData);
 * window.updateGoniometer(goniometerData);
 * }</pre>
 */
public final class CorrelationDisplayWindow {

    private static final double DEFAULT_WIDTH = 480;
    private static final double DEFAULT_HEIGHT = 400;

    private final Stage stage;
    private final CorrelationDisplay display;

    /**
     * Creates a new floating correlation display window.
     */
    public CorrelationDisplayWindow() {
        display = new CorrelationDisplay();
        stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Stereo Correlation / Goniometer");

        Scene scene = new Scene(display, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(Color.web("#0d0d1a"));
        DarkThemeHelper.applyTo(scene);
        stage.setScene(scene);
        stage.setMinWidth(320);
        stage.setMinHeight(280);
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
     * Updates the display with new correlation data.
     *
     * @param data the latest correlation measurement
     */
    public void update(CorrelationData data) {
        display.update(data);
    }

    /**
     * Updates the display with new goniometer data.
     *
     * @param data the latest goniometer data for Lissajous display
     */
    public void updateGoniometer(GoniometerData data) {
        display.updateGoniometer(data);
    }

    /**
     * Sets whether to show the goniometer (Lissajous) overlay.
     *
     * @param enabled {@code true} to show goniometer visualization
     */
    public void setGoniometerMode(boolean enabled) {
        display.setGoniometerMode(enabled);
    }

    /**
     * Returns the underlying {@link CorrelationDisplay} component.
     *
     * @return the correlation display
     */
    public CorrelationDisplay getDisplay() {
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
}
