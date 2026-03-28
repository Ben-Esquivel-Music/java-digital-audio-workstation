package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;

import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Standalone floating window wrapping a {@link LoudnessDisplay}.
 *
 * <p>Provides the same LUFS metering visualization as the embedded
 * tile, but in a freely movable and resizable window. This allows
 * engineers to position the loudness meter anywhere on screen while
 * working in the arrangement or mixer view.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * LoudnessDisplayWindow window = new LoudnessDisplayWindow();
 * window.show();
 * window.update(loudnessData);
 * }</pre>
 */
public final class LoudnessDisplayWindow {

    private static final double DEFAULT_WIDTH = 480;
    private static final double DEFAULT_HEIGHT = 280;

    private final Stage stage;
    private final LoudnessDisplay display;

    /**
     * Creates a new floating loudness display window.
     */
    public LoudnessDisplayWindow() {
        display = new LoudnessDisplay();
        stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("LUFS Loudness Meter");

        Scene scene = new Scene(display, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(Color.web("#0d0d1a"));
        stage.setScene(scene);
        stage.setMinWidth(320);
        stage.setMinHeight(200);
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
     * Updates the display with new loudness data.
     *
     * @param data the latest loudness measurement
     */
    public void update(LoudnessData data) {
        display.update(data);
    }

    /**
     * Sets the platform target preset for the display.
     *
     * @param target the platform loudness target preset
     */
    public void setTarget(LoudnessTarget target) {
        display.setTarget(target);
    }

    /**
     * Returns the underlying {@link LoudnessDisplay} component.
     *
     * @return the loudness display
     */
    public LoudnessDisplay getDisplay() {
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
