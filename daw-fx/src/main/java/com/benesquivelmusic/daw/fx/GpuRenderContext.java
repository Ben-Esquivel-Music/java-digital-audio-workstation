package com.benesquivelmusic.daw.fx;

import javafx.scene.canvas.GraphicsContext;

/**
 * Per-frame context handed to a {@link GpuRenderer}.
 *
 * @param gc            the canvas's {@code GraphicsContext}; calls are issued
 *                      against Prism's hardware pipeline
 * @param width         current canvas width in logical pixels
 * @param height        current canvas height in logical pixels
 * @param nowNanos      timestamp from {@code AnimationTimer#handle}, or
 *                      {@link System#nanoTime()} for one-off renders
 * @param deltaSeconds  seconds elapsed since the previous frame (0.0 for the
 *                      very first frame and for one-off renders)
 * @param frameNumber   monotonically increasing frame index, starting at 0
 */
public record GpuRenderContext(
        GraphicsContext gc,
        double width,
        double height,
        long nowNanos,
        double deltaSeconds,
        long frameNumber) {
}
