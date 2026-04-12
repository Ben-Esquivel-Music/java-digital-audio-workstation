package com.benesquivelmusic.daw.app.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Renders the alternating track lane backgrounds and separator lines on
 * the arrangement canvas, plus the empty-area fill below the last track.
 *
 * <p>Stateless — callers precompute each lane's Y offset (typically in a
 * cached array) and pass them in, so the renderer remains agnostic to
 * the canvas's automation-lane bookkeeping.</p>
 */
final class TrackLaneRenderer {

    static final Color LANE_COLOR_EVEN = Color.web("#1c1c2e");
    static final Color LANE_COLOR_ODD = Color.web("#22223a");
    static final Color LANE_SEPARATOR_COLOR = Color.web("#333355", 0.5);

    private TrackLaneRenderer() {
    }

    /**
     * Draws track lane backgrounds, separators, and the fill below the
     * last track.
     *
     * @param gc                 the graphics context
     * @param trackCount         the number of tracks
     * @param laneYCache         per-track cumulative top Y offsets (canvas space)
     * @param trackHeight        the height of a single track lane in pixels
     * @param effectiveHeights   per-track effective height including any
     *                           expanded automation lane
     * @param canvasWidth        canvas width in pixels
     * @param canvasHeight       canvas height in pixels
     * @param contentBottomY     Y of the bottom of the last track lane
     *                           (canvas space, after scroll)
     */
    static void draw(GraphicsContext gc,
                     int trackCount, double[] laneYCache, double trackHeight,
                     double[] effectiveHeights,
                     double canvasWidth, double canvasHeight,
                     double contentBottomY) {
        for (int i = 0; i < trackCount; i++) {
            double y = laneYCache[i];
            double effectiveHeight = effectiveHeights[i];
            if (y + effectiveHeight < 0) {
                continue;
            }
            if (y > canvasHeight) {
                break;
            }
            gc.setFill(i % 2 == 0 ? LANE_COLOR_EVEN : LANE_COLOR_ODD);
            gc.fillRect(0, y, canvasWidth, trackHeight);

            gc.setStroke(LANE_SEPARATOR_COLOR);
            gc.setLineWidth(1.0);
            gc.strokeLine(0, y + trackHeight, canvasWidth, y + trackHeight);
        }

        if (contentBottomY < canvasHeight) {
            gc.setFill(LANE_COLOR_EVEN);
            gc.fillRect(0, contentBottomY, canvasWidth, canvasHeight - contentBottomY);
        }
    }
}
