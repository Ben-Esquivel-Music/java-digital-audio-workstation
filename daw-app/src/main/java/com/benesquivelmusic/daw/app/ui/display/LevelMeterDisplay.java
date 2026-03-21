package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.visualization.LevelData;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.paint.CycleMethod;

/**
 * Animated peak/RMS level meter display with professional ballistics.
 *
 * <p>Renders a vertical or horizontal level meter with:
 * <ul>
 *   <li>Gradient-colored bar (green → yellow → red)</li>
 *   <li>Peak-hold indicator with configurable decay</li>
 *   <li>RMS bar for average level visualization</li>
 *   <li>Clip indicator (turns red on signal overload)</li>
 *   <li>dB scale markings</li>
 * </ul>
 *
 * <p>Supports the metering requirements from the mastering-techniques
 * research (§4 — Dynamics Processing, §8 — Loudness Standards).</p>
 */
public final class LevelMeterDisplay extends Region {

    private static final Color BACKGROUND = Color.web("#0d0d1a");
    private static final Color CLIP_COLOR = Color.web("#ff1744");
    private static final Color PEAK_INDICATOR_COLOR = Color.web("#ffffff");
    private static final Color SCALE_COLOR = Color.web("#ffffff", 0.3);

    private static final double MIN_DB = -60.0;
    private static final double MAX_DB = 6.0;

    private final Canvas canvas;
    private final MeterAnimator rmsAnimator;
    private final MeterAnimator peakAnimator;
    private final boolean vertical;

    private double currentRmsDb = -120.0;
    private double currentPeakDb = -120.0;
    private boolean clipping;

    /**
     * Creates a level meter display.
     *
     * @param vertical {@code true} for vertical orientation, {@code false} for horizontal
     */
    public LevelMeterDisplay(boolean vertical) {
        this.vertical = vertical;
        canvas = new Canvas();
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((_, _, _) -> render());
        heightProperty().addListener((_, _, _) -> render());

        rmsAnimator = new MeterAnimator(0.01, 0.15, 0);
        peakAnimator = new MeterAnimator(0.001, 0.5, 1.5);
    }

    /**
     * Creates a vertical level meter display.
     */
    public LevelMeterDisplay() {
        this(true);
    }

    /**
     * Updates the meter with new level data and animates.
     *
     * @param data          the current level data
     * @param deltaNanos    time since last update in nanoseconds
     */
    public void update(LevelData data, long deltaNanos) {
        if (data == null) return;
        currentRmsDb = data.rmsDb();
        currentPeakDb = data.peakDb();
        clipping = data.clipping();

        double rmsNorm = dbToNormalized(currentRmsDb);
        double peakNorm = dbToNormalized(currentPeakDb);
        rmsAnimator.update(rmsNorm, deltaNanos);
        peakAnimator.update(peakNorm, deltaNanos);

        render();
    }

    /**
     * Renders the meter to the canvas.
     */
    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, w, h);

        double rmsLevel = rmsAnimator.getCurrentValue();
        double peakLevel = peakAnimator.getCurrentValue();
        double peakHold = peakAnimator.getPeakValue();

        LinearGradient meterGradient;
        if (vertical) {
            meterGradient = new LinearGradient(0, 1, 0, 0, true, CycleMethod.NO_CYCLE,
                    new Stop(0.0, Color.web("#00e676")),
                    new Stop(0.6, Color.web("#00e676")),
                    new Stop(0.8, Color.web("#ffea00")),
                    new Stop(0.95, Color.web("#ff9100")),
                    new Stop(1.0, CLIP_COLOR)
            );
        } else {
            meterGradient = new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                    new Stop(0.0, Color.web("#00e676")),
                    new Stop(0.6, Color.web("#00e676")),
                    new Stop(0.8, Color.web("#ffea00")),
                    new Stop(0.95, Color.web("#ff9100")),
                    new Stop(1.0, CLIP_COLOR)
            );
        }

        if (vertical) {
            // RMS bar
            double rmsHeight = rmsLevel * h;
            gc.setFill(meterGradient);
            gc.setGlobalAlpha(0.5);
            gc.fillRect(2, h - rmsHeight, w - 4, rmsHeight);

            // Peak bar
            double peakHeight = peakLevel * h;
            gc.setGlobalAlpha(1.0);
            gc.fillRect(2, h - peakHeight, w - 4, peakHeight);

            // Peak hold indicator
            if (peakHold > 0.001) {
                double holdY = h - peakHold * h;
                gc.setStroke(PEAK_INDICATOR_COLOR);
                gc.setLineWidth(2.0);
                gc.strokeLine(2, holdY, w - 2, holdY);
            }

            // Clip indicator
            if (clipping) {
                gc.setFill(CLIP_COLOR);
                gc.fillRect(2, 0, w - 4, 4);
            }

            // dB scale
            gc.setGlobalAlpha(1.0);
            gc.setStroke(SCALE_COLOR);
            gc.setLineWidth(0.5);
            gc.setFill(SCALE_COLOR);
            gc.setFont(javafx.scene.text.Font.font(8));
            for (double db = MIN_DB; db <= MAX_DB; db += 6) {
                double y = h - dbToNormalized(db) * h;
                gc.strokeLine(0, y, 3, y);
            }
        } else {
            // Horizontal orientation
            double rmsWidth = rmsLevel * w;
            gc.setFill(meterGradient);
            gc.setGlobalAlpha(0.5);
            gc.fillRect(0, 2, rmsWidth, h - 4);

            double peakWidth = peakLevel * w;
            gc.setGlobalAlpha(1.0);
            gc.fillRect(0, 2, peakWidth, h - 4);

            if (peakHold > 0.001) {
                double holdX = peakHold * w;
                gc.setStroke(PEAK_INDICATOR_COLOR);
                gc.setLineWidth(2.0);
                gc.strokeLine(holdX, 2, holdX, h - 2);
            }

            if (clipping) {
                gc.setFill(CLIP_COLOR);
                gc.fillRect(w - 4, 2, 4, h - 4);
            }

            gc.setGlobalAlpha(1.0);
        }
    }

    /**
     * Converts a dB value to a normalized [0, 1] range for display.
     */
    static double dbToNormalized(double db) {
        if (db <= MIN_DB) return 0.0;
        if (db >= MAX_DB) return 1.0;
        return (db - MIN_DB) / (MAX_DB - MIN_DB);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }
}
