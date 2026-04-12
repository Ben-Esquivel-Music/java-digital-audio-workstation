package com.benesquivelmusic.daw.app.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Renders a min/max-summary waveform overview inside the bounds of an
 * audio clip on the arrangement canvas.
 *
 * <p>The renderer is stateless — all drawing state is passed in as
 * arguments — so it can be reused across clips without retained state
 * and unit-tested with a mock {@link GraphicsContext}.</p>
 */
final class ClipWaveformRenderer {

    static final Color WAVEFORM_COLOR = Color.web("#ffffff", 0.5);

    private static final int WAVEFORM_MIN_WIDTH = 4;

    private ClipWaveformRenderer() {
    }

    /**
     * Draws the waveform overview for the given audio data inside the
     * rectangle {@code (clipX, clipY, clipWidth, clipHeight)}, clamped to
     * the visible canvas range.
     *
     * @param gc           the graphics context to draw on
     * @param audioData    interleaved channel buffers (channel 0 is used)
     * @param clipX        clip left edge in canvas pixels
     * @param clipY        clip top edge in canvas pixels
     * @param clipWidth    clip width in pixels
     * @param clipHeight   clip height in pixels
     * @param canvasWidth  total canvas width (used for visible clamping)
     */
    static void draw(GraphicsContext gc, float[][] audioData,
                     double clipX, double clipY,
                     double clipWidth, double clipHeight,
                     double canvasWidth) {
        if (audioData == null || audioData.length == 0 || audioData[0].length == 0) {
            return;
        }
        long totalPixelWidthLong = (long) Math.floor(clipWidth);
        if (totalPixelWidthLong < WAVEFORM_MIN_WIDTH) {
            return;
        }
        if (totalPixelWidthLong > Integer.MAX_VALUE) {
            totalPixelWidthLong = Integer.MAX_VALUE;
        }
        int totalPixelWidth = (int) totalPixelWidthLong;

        double rawVisibleStart = -clipX;
        double rawVisibleEnd = canvasWidth - clipX;

        long visibleStartLong = (long) Math.floor(rawVisibleStart);
        long visibleEndLong = (long) Math.floor(rawVisibleEnd);

        if (visibleEndLong <= 0) {
            return;
        }
        if (visibleStartLong < 0) {
            visibleStartLong = 0;
        }
        if (visibleEndLong > totalPixelWidthLong) {
            visibleEndLong = totalPixelWidthLong;
        }
        if (visibleStartLong >= visibleEndLong) {
            return;
        }

        int visibleStart = (int) Math.min(visibleStartLong, (long) Integer.MAX_VALUE);
        int visibleEnd = (int) Math.min(visibleEndLong, (long) Integer.MAX_VALUE);
        float[] channel = audioData[0];
        int totalSamples = channel.length;
        double centerY = clipY + clipHeight / 2.0;
        double halfHeight = (clipHeight - 8.0) / 2.0;

        gc.setStroke(WAVEFORM_COLOR);
        gc.setLineWidth(1.0);

        for (int px = visibleStart; px < visibleEnd; px++) {
            int sampleStart = (int) ((long) px * totalSamples / totalPixelWidth);
            int sampleEnd = (int) ((long) (px + 1) * totalSamples / totalPixelWidth);
            sampleEnd = Math.min(sampleEnd, totalSamples);

            if (sampleStart >= sampleEnd) {
                continue;
            }
            float min = channel[sampleStart];
            float max = channel[sampleStart];
            for (int s = sampleStart + 1; s < sampleEnd; s++) {
                float val = channel[s];
                if (val < min) {
                    min = val;
                }
                if (val > max) {
                    max = val;
                }
            }

            double x = clipX + px;
            double y1 = centerY - max * halfHeight;
            double y2 = centerY - min * halfHeight;
            gc.strokeLine(x, y1, x, y2);
        }
    }
}
