package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.fx.GpuCanvas;
import com.benesquivelmusic.daw.fx.GpuRenderContext;
import com.benesquivelmusic.daw.sdk.visualization.LevelData;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

/**
 * Animated peak/RMS level meter display with professional ballistics.
 *
 * <p>Renders a vertical or horizontal level meter with gradient bar
 * (green → yellow → red), peak-hold indicator, RMS bar, clip indicator,
 * and dB scale markings.
 *
 * <p>This display composes a {@link GpuCanvas} from the {@code daw-fx}
 * module: the GpuCanvas owns the size binding, per-frame
 * {@link javafx.animation.AnimationTimer}, scene-attachment gating, and
 * background clear, so the display itself only contributes the per-frame
 * draw routine. The {@link MeterAnimator} ballistics are advanced from
 * {@link GpuRenderContext#deltaSeconds()} on each frame.
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

    private final GpuCanvas gpuCanvas;
    private final MeterAnimator rmsAnimator;
    private final MeterAnimator peakAnimator;
    private final boolean vertical;

    private double pendingRmsDb = -120.0;
    private double pendingPeakDb = -120.0;
    private boolean clipping;
    private boolean disposed;

    /**
     * Creates a level meter display.
     *
     * @param vertical {@code true} for vertical orientation, {@code false} for horizontal
     */
    public LevelMeterDisplay(boolean vertical) {
        this.vertical = vertical;
        rmsAnimator = new MeterAnimator(0.01, 0.15, 0);
        peakAnimator = new MeterAnimator(0.001, 0.5, 1.5);

        gpuCanvas = GpuCanvas.create()
                .renderer(this::renderFrame)
                .clearColor(BACKGROUND)
                .animated(true)
                .build();
        getChildren().add(gpuCanvas);
    }

    /**
     * Creates a vertical level meter display.
     */
    public LevelMeterDisplay() {
        this(true);
    }

    /**
     * Updates the meter with a new level snapshot.
     *
     * <p>Stores the snapshot for the next render frame; the GpuCanvas-driven
     * per-frame loop advances the {@link MeterAnimator} ballistics using
     * {@link GpuRenderContext#deltaSeconds()}.
     *
     * @param data the current level data
     */
    public void update(LevelData data) {
        if (data == null || disposed) return;
        pendingRmsDb = data.rmsDb();
        pendingPeakDb = data.peakDb();
        clipping = data.clipping();
        // Request an immediate render so the new value appears even when the
        // animation timer is gated off (e.g. one-shot updates from tests).
        gpuCanvas.requestRender();
    }

    /**
     * Returns the embedded {@link GpuCanvas} that owns the per-frame render
     * loop and off-heap pixel surface. Visible for tests.
     */
    GpuCanvas getGpuCanvas() {
        return gpuCanvas;
    }

    /** Returns the RMS ballistic animator. Visible for tests. */
    MeterAnimator getRmsAnimator() {
        return rmsAnimator;
    }

    /** Returns the peak ballistic animator. Visible for tests. */
    MeterAnimator getPeakAnimator() {
        return peakAnimator;
    }

    /**
     * Stops the GpuCanvas render loop and releases its off-heap surface.
     * Must be called from the JavaFX Application Thread. Safe to call
     * multiple times.
     */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        gpuCanvas.setAnimated(false);
        gpuCanvas.dispose();
    }

    private void renderFrame(GpuRenderContext ctx) {
        // Advance ballistics from the host's per-frame delta. The MeterAnimator
        // API operates on deltaNanos, so convert from deltaSeconds.
        long deltaNanos = (long) (ctx.deltaSeconds() * 1_000_000_000.0);
        double rmsNorm = dbToNormalized(pendingRmsDb);
        double peakNorm = dbToNormalized(pendingPeakDb);
        rmsAnimator.update(rmsNorm, deltaNanos);
        peakAnimator.update(peakNorm, deltaNanos);

        renderInto(ctx.gc(), ctx.width(), ctx.height());
    }

    /**
     * Renders the meter into the supplied graphics context. Background fill
     * is provided by {@link GpuCanvas#setClearColor(Color)} so we do not
     * issue a redundant background {@code fillRect} here.
     */
    private void renderInto(GraphicsContext gc, double w, double h) {
        if (w <= 0 || h <= 0) return;

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
        // GpuCanvas is itself a Region and re-renders on its own size change
        // listeners, so we just resize it here to fill the display.
        gpuCanvas.resizeRelocate(0, 0, getWidth(), getHeight());
    }
}
