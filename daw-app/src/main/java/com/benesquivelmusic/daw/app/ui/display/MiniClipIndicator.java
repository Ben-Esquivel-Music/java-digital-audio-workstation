package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.fx.GpuCanvas;
import com.benesquivelmusic.daw.fx.GpuRenderContext;
import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;

import java.util.Objects;
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;

/**
 * Miniature clip indicator shown in the arrangement-view track header
 * (user story 137).
 *
 * <p>Mirrors the latching clip state of the same
 * {@link InputLevelMonitor} that drives the mixer's
 * {@link InputMeterStrip}, so engineers can see a clip warning while
 * looking at the timeline without having to switch to the mixer view.</p>
 *
 * <p>Click to reset the bound monitor's latch; {@code Alt+click} resets
 * every monitor in the bound registry.</p>
 *
 * <p>Composes a {@link GpuCanvas} from the {@code daw-fx} module so the
 * per-frame poll, scene-attachment timer gate, and surface lifecycle are
 * delegated to the shared substrate.</p>
 */
@HardcodedColorAllowed("story 277 follow-up: migrate Canvas/inline paints to resolved -token CSS")
public final class MiniClipIndicator extends GpuCanvasView {

    private static final double SIZE = 10.0;

    private static final Color OFF = Color.web("#2a0000");
    private static final Color ON = Color.web("#ff1744");
    private static final Color BORDER = Color.web("#000000", 0.5);

    private final InputLevelMonitor monitor;
    private final InputLevelMonitorRegistry registry;
    private InputLevelMeter lastSnapshot = InputLevelMeter.SILENCE;

    public MiniClipIndicator(InputLevelMonitor monitor,
                             InputLevelMonitorRegistry registry) {
        // Transparent background — the round LED is drawn over a transparent
        // rect so any parent background shows through.
        super(null, true);
        this.monitor = Objects.requireNonNull(monitor, "monitor must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");

        setPrefSize(SIZE, SIZE);
        setMinSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);

        setRenderer(this::renderFrame);
        gpuCanvas().setPrefSize(SIZE, SIZE);

        setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (event.isAltDown()) {
                registry.resetAll();
            } else {
                monitor.reset();
            }
            lastSnapshot = monitor.snapshot();
            gpuCanvas().requestRender();
        });
    }

    /**
     * Starts the redraw loop (re-enables the GpuCanvas {@code AnimationTimer}).
     * Idempotent; the underlying timer is gated by Scene attachment.
     */
    public void start() {
        if (isDisposed()) return;
        gpuCanvas().setAnimated(true);
    }

    /**
     * Stops the redraw loop and disposes the GpuCanvas so this indicator can
     * be garbage-collected cleanly. After {@code stop()} the indicator will
     * no longer paint or poll the monitor.
     */
    public void stop() {
        dispose();
    }

    /** Returns the monitor this indicator is bound to. */
    public InputLevelMonitor getMonitor() {
        return monitor;
    }

    /** Returns the embedded {@link GpuCanvas}. Visible for tests. */
    GpuCanvas getGpuCanvas() {
        return gpuCanvas();
    }

    private void renderFrame(GpuRenderContext ctx) {
        // Poll the monitor each frame. Even when the indicator is hidden the
        // timer is gated by Scene attachment (see GpuCanvas), so a temporarily
        // hidden indicator can still latch a clip the moment it becomes
        // visible again. snapshot() is guaranteed non-null (returns
        // InputLevelMeter.SILENCE before first process()).
        lastSnapshot = monitor.snapshot();
        renderInto(ctx.gc(), ctx.width(), ctx.height());
    }

    private void renderInto(GraphicsContext gc, double w, double h) {
        if (w <= 0 || h <= 0) return;
        boolean clipped = lastSnapshot.clippedSinceReset();
        gc.setFill(clipped ? ON : OFF);
        gc.fillOval(1, 1, w - 2, h - 2);
        gc.setStroke(BORDER);
        gc.setLineWidth(0.8);
        gc.strokeOval(1, 1, w - 2, h - 2);
    }
}
