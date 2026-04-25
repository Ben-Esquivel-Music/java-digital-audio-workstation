package com.benesquivelmusic.daw.app.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Renders the thin "summary strip" drawn in place of a folded automation
 * lane. Folded lanes collapse from {@link AutomationLaneRenderer#AUTOMATION_LANE_HEIGHT}
 * down to {@link com.benesquivelmusic.daw.core.track.TrackFoldState#SUMMARY_STRIP_HEIGHT_PX}
 * pixels — just enough for the user to see that the lane exists without
 * burning vertical real estate (Issue 568).
 *
 * <p>Stateless utility class. The strip is drawn as a tinted band the
 * width of the canvas, with the same accent colour as the automation
 * envelope so the eye recognises it as automation data.</p>
 */
final class AutomationLaneSummaryRenderer {

    /** Fill colour used when the strip indicates folded automation data. */
    static final Color SUMMARY_FILL = Color.web("#00E5FF", 0.55);

    /** Border drawn beneath the strip to delimit it from the next track. */
    static final Color SUMMARY_BORDER = Color.web("#333355", 0.5);

    private AutomationLaneSummaryRenderer() {
        // utility class
    }

    /**
     * Draws a folded-automation summary strip across the full canvas
     * width.
     *
     * @param gc           the graphics context
     * @param laneY        the top Y of the strip (canvas space)
     * @param canvasWidth  the visible width in pixels
     * @param laneHeight   the strip's height in pixels (typically 3 px)
     */
    static void draw(GraphicsContext gc, double laneY,
                     double canvasWidth, double laneHeight) {
        if (laneHeight <= 0 || canvasWidth <= 0) {
            return;
        }
        gc.setFill(SUMMARY_FILL);
        gc.fillRect(0, laneY, canvasWidth, laneHeight);
        // Hairline border at the bottom so the strip reads as a divider.
        gc.setStroke(SUMMARY_BORDER);
        gc.setLineWidth(1.0);
        gc.strokeLine(0, laneY + laneHeight, canvasWidth, laneY + laneHeight);
    }
}
