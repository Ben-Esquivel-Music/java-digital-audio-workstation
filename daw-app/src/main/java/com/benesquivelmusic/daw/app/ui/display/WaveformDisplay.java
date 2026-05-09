package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.fx.GpuCanvas;
import com.benesquivelmusic.daw.fx.GpuRenderContext;
import com.benesquivelmusic.daw.sdk.visualization.WaveformData;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * Canvas-based waveform visualization component with smooth animations.
 *
 * <p>Renders audio waveform overviews using min/max peak envelopes and
 * RMS fills, with configurable colors and an animated playback cursor.
 * Supports the waveform visualization requirements from the mastering
 * research (§2 — Critical Listening) and open-source DAW design patterns.</p>
 *
 * <p>This display composes a {@link GpuCanvas} from the {@code daw-fx}
 * module: the GpuCanvas owns the size binding, the per-frame
 * {@link javafx.animation.AnimationTimer}, scene-attachment gating, and
 * the background clear, so the display itself only contributes the
 * per-frame draw routine. Property setters call
 * {@link GpuCanvas#requestRender()} which coalesces multiple calls in a
 * single FX pulse into one redraw — a sample-preview cursor that is
 * updated 50 times per frame issues exactly one renderer invocation per
 * frame, not 50.</p>
 *
 * <p>While a cursor is being animated (e.g. during browser sample preview
 * playback) call {@link #setAnimated(boolean) setAnimated(true)} to drive
 * the cursor advance off the GpuCanvas timer; the cursor velocity is
 * integrated against {@link GpuRenderContext#deltaSeconds()} so the cursor
 * speed is correct regardless of frame rate.</p>
 */
public final class WaveformDisplay extends Region {

    private static final Color DEFAULT_PEAK_COLOR = Color.web("#00e5ff");
    private static final Color DEFAULT_RMS_COLOR = Color.web("#00e5ff", 0.4);
    private static final Color DEFAULT_CURSOR_COLOR = Color.web("#ff1744");
    private static final Color DEFAULT_BACKGROUND = Color.web("#1a1a2e");
    private static final Color DEFAULT_CENTER_LINE = Color.web("#ffffff", 0.15);

    private final GpuCanvas gpuCanvas;

    private WaveformData data;
    private double cursorPosition; // 0.0 to 1.0
    private double cursorVelocity; // normalized units per second; integrated when animated

    private Color peakColor = DEFAULT_PEAK_COLOR;
    private Color rmsColor = DEFAULT_RMS_COLOR;
    private Color cursorColor = DEFAULT_CURSOR_COLOR;

    private boolean disposed;

    /**
     * Creates a new waveform display.
     */
    public WaveformDisplay() {
        gpuCanvas = GpuCanvas.create()
                .renderer(this::renderFrame)
                .clearColor(DEFAULT_BACKGROUND)
                .animated(false)
                .build();
        getChildren().add(gpuCanvas);
    }

    /**
     * Updates the waveform data and requests a re-render.
     *
     * @param data the waveform data to display
     */
    public void setWaveformData(WaveformData data) {
        this.data = data;
        gpuCanvas.requestRender();
    }

    /**
     * Sets the playback cursor position (0.0 to 1.0) and requests a re-render.
     *
     * <p>Multiple calls within the same FX pulse are coalesced by
     * {@link GpuCanvas#requestRender()} into a single renderer invocation.</p>
     *
     * @param position normalized cursor position
     */
    public void setCursorPosition(double position) {
        this.cursorPosition = clampPosition(position);
        gpuCanvas.requestRender();
    }

    /**
     * Returns the current normalized cursor position (0.0 to 1.0).
     */
    public double getCursorPosition() {
        return cursorPosition;
    }

    /**
     * Sets the cursor advance velocity in normalized units per second.
     * When the GpuCanvas is animated ({@link #setAnimated(boolean)}), the
     * cursor position is advanced by {@code velocity * deltaSeconds} every
     * frame and clamped to {@code [0, 1]}. A velocity of {@code 0} (the
     * default) freezes the cursor at its current position.
     *
     * @param velocity normalized units per second
     */
    public void setCursorVelocity(double velocity) {
        this.cursorVelocity = velocity;
    }

    /**
     * Returns the current cursor velocity in normalized units per second.
     */
    public double getCursorVelocity() {
        return cursorVelocity;
    }

    /**
     * Enables or disables the GpuCanvas animation timer. While animated
     * the cursor is advanced off the FX pulse using the configured
     * {@linkplain #setCursorVelocity(double) cursor velocity}; one-off
     * renders triggered by setters do not advance the cursor.
     *
     * @param animated {@code true} to drive cursor animation off the FX
     *                 timer, {@code false} to render only on demand
     */
    public void setAnimated(boolean animated) {
        gpuCanvas.setAnimated(animated);
    }

    /**
     * Returns whether the GpuCanvas animation timer is currently running.
     */
    public boolean isAnimated() {
        return gpuCanvas.isAnimated();
    }

    /**
     * Sets the peak envelope color.
     */
    public void setPeakColor(Color color) {
        this.peakColor = color;
        gpuCanvas.requestRender();
    }

    /**
     * Sets the RMS fill color.
     */
    public void setRmsColor(Color color) {
        this.rmsColor = color;
        gpuCanvas.requestRender();
    }

    /**
     * Sets the cursor color.
     */
    public void setCursorColor(Color color) {
        this.cursorColor = color;
        gpuCanvas.requestRender();
    }

    /**
     * Sets the background color. The clear is performed by
     * {@link GpuCanvas#setClearColor(Color)} so the renderer no longer
     * issues a per-frame background fill; the GpuCanvas already requests
     * a redraw when the clear color changes.
     */
    public void setBackgroundColor(Color color) {
        gpuCanvas.setClearColor(color);
    }

    /**
     * Forces a re-render of the waveform display.
     *
     * <p>Call this after an editing operation (trim, fade) has modified the
     * underlying audio data so the visual representation is updated.</p>
     */
    public void refresh() {
        gpuCanvas.requestRender();
    }

    /**
     * Returns the embedded {@link GpuCanvas}. Visible for tests.
     */
    GpuCanvas getGpuCanvas() {
        return gpuCanvas;
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

    // ── Rendering ───────────────────────────────────────────────────────

    private void renderFrame(GpuRenderContext ctx) {
        // Advance the cursor only when the timer drives this frame
        // (deltaSeconds > 0). One-off requestRender() calls always supply
        // deltaSeconds == 0 per the GpuCanvas contract, so coalesced
        // setter-driven renders do not double-integrate the cursor.
        if (ctx.deltaSeconds() > 0.0 && cursorVelocity != 0.0) {
            cursorPosition = clampPosition(cursorPosition + cursorVelocity * ctx.deltaSeconds());
        }
        renderInto(ctx.gc(), ctx.width(), ctx.height());
    }

    /**
     * Renders the waveform into the supplied graphics context. Background
     * fill is provided by {@link GpuCanvas#setClearColor(Color)} so this
     * method must not paint the background itself.
     */
    private void renderInto(GraphicsContext gc, double w, double h) {
        if (w <= 0 || h <= 0) return;

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

    private static double clampPosition(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    @Override
    protected void layoutChildren() {
        gpuCanvas.resizeRelocate(0, 0, getWidth(), getHeight());
    }
}
