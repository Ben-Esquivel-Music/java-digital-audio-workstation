package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.visualization.WaveformData;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.paint.CycleMethod;

/**
 * Canvas-based waveform visualization component with smooth animations.
 *
 * <p>Renders audio waveform overviews using min/max peak envelopes and
 * RMS fills, with configurable colors and animated playback cursor.
 * Supports the waveform visualization requirements from the mastering
 * research (§2 — Critical Listening) and open-source DAW design patterns.</p>
 *
 * <p>Uses JavaFX {@link Canvas} for high-performance rendering without
 * scene graph overhead — ideal for real-time audio display.</p>
 */
public final class WaveformDisplay extends Region {

    private static final Color DEFAULT_PEAK_COLOR = Color.web("#00e5ff");
    private static final Color DEFAULT_RMS_COLOR = Color.web("#00e5ff", 0.4);
    private static final Color DEFAULT_CURSOR_COLOR = Color.web("#ff1744");
    private static final Color DEFAULT_BACKGROUND = Color.web("#1a1a2e");
    private static final Color DEFAULT_CENTER_LINE = Color.web("#ffffff", 0.15);

    private final Canvas canvas;
    private WaveformData data;
    private double cursorPosition; // 0.0 to 1.0

    private Color peakColor = DEFAULT_PEAK_COLOR;
    private Color rmsColor = DEFAULT_RMS_COLOR;
    private Color cursorColor = DEFAULT_CURSOR_COLOR;
    private Color backgroundColor = DEFAULT_BACKGROUND;

    /**
     * Creates a new waveform display.
     */
    public WaveformDisplay() {
        canvas = new Canvas();
        getChildren().add(canvas);

        // Bind canvas size to region size
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        // Re-render on resize
        widthProperty().addListener((_, _, _) -> render());
        heightProperty().addListener((_, _, _) -> render());
    }

    /**
     * Updates the waveform data and re-renders.
     *
     * @param data the waveform data to display
     */
    public void setWaveformData(WaveformData data) {
        this.data = data;
        render();
    }

    /**
     * Sets the playback cursor position (0.0 to 1.0) and re-renders.
     *
     * @param position normalized cursor position
     */
    public void setCursorPosition(double position) {
        this.cursorPosition = Math.max(0.0, Math.min(1.0, position));
        render();
    }

    /**
     * Sets the peak envelope color.
     */
    public void setPeakColor(Color color) {
        this.peakColor = color;
        render();
    }

    /**
     * Sets the RMS fill color.
     */
    public void setRmsColor(Color color) {
        this.rmsColor = color;
        render();
    }

    /**
     * Renders the waveform to the canvas.
     */
    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Clear background
        gc.setFill(backgroundColor);
        gc.fillRect(0, 0, w, h);

        // Center line
        gc.setStroke(DEFAULT_CENTER_LINE);
        gc.setLineWidth(1.0);
        gc.strokeLine(0, h / 2, w, h / 2);

        if (data == null) return;

        double centerY = h / 2.0;
        int columns = data.columns();
        double colWidth = w / columns;

        // Draw RMS fill
        gc.setFill(rmsColor);
        for (int i = 0; i < columns; i++) {
            double x = i * colWidth;
            double rmsHeight = data.rmsValues()[i] * centerY;
            gc.fillRect(x, centerY - rmsHeight, Math.max(colWidth, 1), rmsHeight * 2);
        }

        // Draw peak envelope
        gc.setStroke(peakColor);
        gc.setLineWidth(1.0);
        gc.beginPath();
        for (int i = 0; i < columns; i++) {
            double x = i * colWidth + colWidth / 2;
            double y = centerY - data.maxValues()[i] * centerY;
            if (i == 0) gc.moveTo(x, y);
            else gc.lineTo(x, y);
        }
        gc.stroke();

        gc.beginPath();
        for (int i = 0; i < columns; i++) {
            double x = i * colWidth + colWidth / 2;
            double y = centerY - data.minValues()[i] * centerY;
            if (i == 0) gc.moveTo(x, y);
            else gc.lineTo(x, y);
        }
        gc.stroke();

        // Draw playback cursor
        if (cursorPosition > 0) {
            double cursorX = cursorPosition * w;
            gc.setStroke(cursorColor);
            gc.setLineWidth(2.0);
            gc.strokeLine(cursorX, 0, cursorX, h);
        }
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }
}
