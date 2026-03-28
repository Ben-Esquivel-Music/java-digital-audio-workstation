package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;
import com.benesquivelmusic.daw.sdk.visualization.StereoMode;

import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Standalone floating window wrapping a {@link SpectrumDisplay}.
 *
 * <p>Provides the same spectrum analyzer visualization as the embedded
 * tile, but in a freely movable and resizable window. This allows
 * engineers to position the spectrum analyzer anywhere on screen while
 * working in the arrangement or mixer view.</p>
 *
 * <p>Usage:
 * <pre>{@code
 * SpectrumDisplayWindow window = new SpectrumDisplayWindow();
 * window.show();
 * window.updateSpectrum(spectrumData);
 * }</pre>
 */
public final class SpectrumDisplayWindow {

    private static final double DEFAULT_WIDTH = 640;
    private static final double DEFAULT_HEIGHT = 360;

    private final Stage stage;
    private final SpectrumDisplay display;

    /**
     * Creates a new floating spectrum display window with the specified
     * number of display bars.
     *
     * @param displayBars the number of visible frequency bars
     */
    public SpectrumDisplayWindow(int displayBars) {
        display = new SpectrumDisplay(displayBars);
        stage = new Stage(StageStyle.UTILITY);
        stage.setTitle("Spectrum Analyzer");

        Scene scene = new Scene(display, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(Color.web("#0d0d1a"));
        stage.setScene(scene);
        stage.setMinWidth(400);
        stage.setMinHeight(240);
    }

    /**
     * Creates a new floating spectrum display window with 64 bars.
     */
    public SpectrumDisplayWindow() {
        this(64);
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
     * Updates the display with new spectrum data.
     *
     * @param data the latest spectrum snapshot
     */
    public void updateSpectrum(SpectrumData data) {
        display.updateSpectrum(data);
    }

    /**
     * Sets the pre-EQ spectrum data for overlay display.
     *
     * @param preEqData the pre-EQ spectrum snapshot, or {@code null} to clear
     */
    public void setPreEqData(SpectrumData preEqData) {
        display.setPreEqData(preEqData);
    }

    /**
     * Sets the stereo display mode.
     *
     * @param mode the stereo mode
     */
    public void setStereoMode(StereoMode mode) {
        display.setStereoMode(mode);
    }

    /**
     * Sets the right channel spectrum data for stereo display.
     *
     * @param rightData the right channel spectrum snapshot
     */
    public void setRightChannelData(SpectrumData rightData) {
        display.setRightChannelData(rightData);
    }

    /**
     * Returns the underlying {@link SpectrumDisplay} component.
     *
     * @return the spectrum display
     */
    public SpectrumDisplay getDisplay() {
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
