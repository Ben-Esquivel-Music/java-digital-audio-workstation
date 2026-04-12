package com.benesquivelmusic.daw.app.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Renders transport-related full-height overlays on the arrangement
 * canvas: the playhead, the loop region highlight, the time selection
 * highlight with its draggable edge handles, and the rubber-band
 * selection rectangle.
 *
 * <p>Stateless — all positions and transform state are passed in per
 * call so the renderer can be exercised with a mock
 * {@link GraphicsContext} in unit tests.</p>
 */
final class TransportOverlayRenderer {

    static final Color PLAYHEAD_COLOR = Color.web("#ff5555");
    static final Color LOOP_HIGHLIGHT_COLOR = Color.web("#b388ff", 0.08);
    static final Color SELECTION_HIGHLIGHT_COLOR = Color.web("#42A5F5", 0.18);
    static final Color SELECTION_BORDER_COLOR = Color.web("#42A5F5", 0.6);
    static final Color SELECTION_HANDLE_COLOR = Color.web("#42A5F5", 0.9);
    static final Color SELECTION_HANDLE_FILL_COLOR = Color.web("#42A5F5", 0.4);
    static final Color RUBBER_BAND_FILL_COLOR = Color.web("#42A5F5", 0.15);
    static final Color RUBBER_BAND_BORDER_COLOR = Color.web("#42A5F5", 0.7);
    static final Color TRIM_PREVIEW_COLOR = Color.web("#00E5FF", 0.8);

    static final double TRIM_PREVIEW_LINE_WIDTH = 2.0;

    private static final double PLAYHEAD_WIDTH = 2.0;
    private static final double SELECTION_BORDER_WIDTH = 1.0;
    private static final double SELECTION_HANDLE_VISUAL_WIDTH = 4.0;

    private TransportOverlayRenderer() {
    }

    static void drawPlayhead(GraphicsContext gc, double playheadBeat,
                             double scrollXBeats, double pixelsPerBeat,
                             double canvasWidth, double canvasHeight) {
        if (playheadBeat < 0) {
            return;
        }
        double x = (playheadBeat - scrollXBeats) * pixelsPerBeat;
        if (x < 0 || x > canvasWidth) {
            return;
        }
        gc.setFill(PLAYHEAD_COLOR);
        gc.fillRect(x - PLAYHEAD_WIDTH / 2.0, 0, PLAYHEAD_WIDTH, canvasHeight);
    }

    static void drawLoopHighlight(GraphicsContext gc, boolean loopEnabled,
                                  double loopStartBeat, double loopEndBeat,
                                  double scrollXBeats, double pixelsPerBeat,
                                  double canvasWidth, double canvasHeight) {
        if (!loopEnabled) {
            return;
        }
        double x1 = (loopStartBeat - scrollXBeats) * pixelsPerBeat;
        double x2 = (loopEndBeat - scrollXBeats) * pixelsPerBeat;
        double drawX1 = Math.max(0, x1);
        double drawX2 = Math.min(canvasWidth, x2);
        if (drawX2 > drawX1) {
            gc.setFill(LOOP_HIGHLIGHT_COLOR);
            gc.fillRect(drawX1, 0, drawX2 - drawX1, canvasHeight);
        }
    }

    static void drawSelectionHighlight(GraphicsContext gc, boolean selectionActive,
                                       double selectionStartBeat, double selectionEndBeat,
                                       double scrollXBeats, double pixelsPerBeat,
                                       double canvasWidth, double canvasHeight) {
        if (!selectionActive || selectionStartBeat >= selectionEndBeat) {
            return;
        }
        double x1 = (selectionStartBeat - scrollXBeats) * pixelsPerBeat;
        double x2 = (selectionEndBeat - scrollXBeats) * pixelsPerBeat;
        double drawX1 = Math.max(0, x1);
        double drawX2 = Math.min(canvasWidth, x2);
        if (drawX2 <= drawX1) {
            return;
        }

        gc.setFill(SELECTION_HIGHLIGHT_COLOR);
        gc.fillRect(drawX1, 0, drawX2 - drawX1, canvasHeight);

        gc.setStroke(SELECTION_BORDER_COLOR);
        gc.setLineWidth(SELECTION_BORDER_WIDTH);
        if (x1 >= 0 && x1 <= canvasWidth) {
            gc.strokeLine(x1, 0, x1, canvasHeight);
        }
        if (x2 >= 0 && x2 <= canvasWidth) {
            gc.strokeLine(x2, 0, x2, canvasHeight);
        }

        drawSelectionHandle(gc, x1, canvasWidth, canvasHeight);
        drawSelectionHandle(gc, x2, canvasWidth, canvasHeight);
    }

    private static void drawSelectionHandle(GraphicsContext gc, double x,
                                            double canvasWidth, double canvasHeight) {
        if (x < -SELECTION_HANDLE_VISUAL_WIDTH || x > canvasWidth + SELECTION_HANDLE_VISUAL_WIDTH) {
            return;
        }
        double handleHeight = Math.min(40.0, canvasHeight * 0.3);
        double handleY = (canvasHeight - handleHeight) / 2.0;
        gc.setFill(SELECTION_HANDLE_FILL_COLOR);
        gc.fillRoundRect(x - SELECTION_HANDLE_VISUAL_WIDTH / 2.0, handleY,
                SELECTION_HANDLE_VISUAL_WIDTH, handleHeight, 2.0, 2.0);
        gc.setStroke(SELECTION_HANDLE_COLOR);
        gc.setLineWidth(1.0);
        gc.strokeRoundRect(x - SELECTION_HANDLE_VISUAL_WIDTH / 2.0, handleY,
                SELECTION_HANDLE_VISUAL_WIDTH, handleHeight, 2.0, 2.0);
    }

    static void drawRubberBand(GraphicsContext gc, boolean rubberBandActive,
                               double rubberBandX1, double rubberBandY1,
                               double rubberBandX2, double rubberBandY2,
                               double canvasWidth, double canvasHeight) {
        if (!rubberBandActive) {
            return;
        }
        double x = Math.min(rubberBandX1, rubberBandX2);
        double y = Math.min(rubberBandY1, rubberBandY2);
        double w = Math.abs(rubberBandX2 - rubberBandX1);
        double h = Math.abs(rubberBandY2 - rubberBandY1);
        double drawX = Math.max(0, x);
        double drawY = Math.max(0, y);
        double drawRight = Math.min(canvasWidth, x + w);
        double drawBottom = Math.min(canvasHeight, y + h);
        if (drawRight <= drawX || drawBottom <= drawY) {
            return;
        }
        double drawW = drawRight - drawX;
        double drawH = drawBottom - drawY;

        gc.setFill(RUBBER_BAND_FILL_COLOR);
        gc.fillRect(drawX, drawY, drawW, drawH);

        gc.setStroke(RUBBER_BAND_BORDER_COLOR);
        gc.setLineWidth(1.0);
        gc.setLineDashes(4.0, 3.0);
        gc.strokeRect(drawX, drawY, drawW, drawH);
        gc.setLineDashes((double[]) null);
    }
}
