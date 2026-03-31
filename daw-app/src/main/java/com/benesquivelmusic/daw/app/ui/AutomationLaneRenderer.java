package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.AutomationParameter;
import com.benesquivelmusic.daw.core.automation.AutomationPoint;
import com.benesquivelmusic.daw.core.automation.InterpolationMode;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * Renders an automation lane's envelope line and breakpoint nodes onto a
 * {@link GraphicsContext}.
 *
 * <p>The renderer draws:</p>
 * <ul>
 *   <li>A semi-transparent background for the automation lane area</li>
 *   <li>A colored polyline connecting breakpoint nodes using the
 *       {@link InterpolationMode} of each segment (linear or curved)</li>
 *   <li>Circular breakpoint handles that can be hit-tested for mouse
 *       interaction</li>
 *   <li>A parameter name label at the left edge of the lane</li>
 * </ul>
 */
final class AutomationLaneRenderer {

    /** Default height in pixels for an automation lane. */
    static final double AUTOMATION_LANE_HEIGHT = 60.0;

    /** Radius of breakpoint handle circles in pixels. */
    static final double BREAKPOINT_RADIUS = 5.0;

    /** Hit-test tolerance for breakpoint selection (pixels). */
    static final double HIT_TOLERANCE = 8.0;

    /** Number of interpolation steps for curved segments. */
    private static final int CURVE_STEPS = 20;

    static final Color LANE_BACKGROUND = Color.web("#1a1a2e", 0.6);
    static final Color LANE_BORDER = Color.web("#333355", 0.5);
    static final Color ENVELOPE_COLOR = Color.web("#00E5FF", 0.9);
    static final Color BREAKPOINT_FILL = Color.web("#00E5FF");
    static final Color BREAKPOINT_STROKE = Color.web("#ffffff", 0.9);
    static final Color LABEL_COLOR = Color.web("#aaaacc", 0.8);

    private static final Font LABEL_FONT = Font.font("SansSerif", 10);

    private AutomationLaneRenderer() {
        // utility class
    }

    /**
     * Draws the automation lane background, envelope polyline, and breakpoint
     * nodes for the given lane. The lane is drawn starting at X = 0 and
     * extending to {@code laneWidth}.
     *
     * @param gc            the graphics context to draw on
     * @param lane          the automation lane to render
     * @param laneY         the top edge of the automation lane area (pixels)
     * @param laneWidth     the visible width (pixels)
     * @param laneHeight    the height of the automation lane (pixels)
     * @param pixelsPerBeat horizontal scale
     * @param scrollXBeats  horizontal scroll offset in beats
     */
    static void draw(GraphicsContext gc, AutomationLane lane,
                     double laneY, double laneWidth, double laneHeight,
                     double pixelsPerBeat, double scrollXBeats) {

        // Background
        gc.setFill(LANE_BACKGROUND);
        gc.fillRect(0, laneY, laneWidth, laneHeight);

        // Bottom border
        gc.setStroke(LANE_BORDER);
        gc.setLineWidth(1.0);
        gc.strokeLine(0, laneY + laneHeight, laneWidth, laneY + laneHeight);

        // Parameter label
        AutomationParameter param = lane.getParameter();
        gc.setFont(LABEL_FONT);
        gc.setFill(LABEL_COLOR);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(param.name(), 4, laneY + 12);

        List<AutomationPoint> points = lane.getPoints();
        if (points.isEmpty()) {
            // Draw a flat line at the default value
            double defaultY = valueToY(param.getDefaultValue(), param, laneY, laneHeight);
            gc.setStroke(ENVELOPE_COLOR);
            gc.setLineWidth(1.5);
            gc.strokeLine(0, defaultY, laneWidth, defaultY);
            return;
        }

        // Draw envelope polyline
        gc.setStroke(ENVELOPE_COLOR);
        gc.setLineWidth(1.5);

        // Extend from left edge to first point
        AutomationPoint first = points.getFirst();
        double firstX = beatToX(first.getTimeInBeats(), pixelsPerBeat, scrollXBeats);
        double firstY = valueToY(first.getValue(), param, laneY, laneHeight);
        if (firstX > 0) {
            gc.strokeLine(0, firstY, firstX, firstY);
        }

        // Draw segments between points
        for (int i = 0; i < points.size() - 1; i++) {
            AutomationPoint left = points.get(i);
            AutomationPoint right = points.get(i + 1);

            double x1 = beatToX(left.getTimeInBeats(), pixelsPerBeat, scrollXBeats);
            double y1 = valueToY(left.getValue(), param, laneY, laneHeight);
            double x2 = beatToX(right.getTimeInBeats(), pixelsPerBeat, scrollXBeats);
            double y2 = valueToY(right.getValue(), param, laneY, laneHeight);

            if (left.getInterpolationMode() == InterpolationMode.CURVED) {
                drawCurvedSegment(gc, x1, y1, x2, y2);
            } else {
                gc.strokeLine(x1, y1, x2, y2);
            }
        }

        // Extend from last point to right edge
        AutomationPoint last = points.getLast();
        double lastX = beatToX(last.getTimeInBeats(), pixelsPerBeat, scrollXBeats);
        double lastY = valueToY(last.getValue(), param, laneY, laneHeight);
        if (lastX < laneWidth) {
            gc.strokeLine(lastX, lastY, laneWidth, lastY);
        }

        // Draw breakpoint handles
        for (AutomationPoint point : points) {
            double px = beatToX(point.getTimeInBeats(), pixelsPerBeat, scrollXBeats);
            double py = valueToY(point.getValue(), param, laneY, laneHeight);

            // Only draw if within visible area
            if (px >= -BREAKPOINT_RADIUS && px <= laneWidth + BREAKPOINT_RADIUS) {
                gc.setFill(BREAKPOINT_FILL);
                gc.fillOval(px - BREAKPOINT_RADIUS, py - BREAKPOINT_RADIUS,
                        BREAKPOINT_RADIUS * 2, BREAKPOINT_RADIUS * 2);
                gc.setStroke(BREAKPOINT_STROKE);
                gc.setLineWidth(1.0);
                gc.strokeOval(px - BREAKPOINT_RADIUS, py - BREAKPOINT_RADIUS,
                        BREAKPOINT_RADIUS * 2, BREAKPOINT_RADIUS * 2);
            }
        }
    }

    /**
     * Converts a beat position to an X pixel coordinate.
     *
     * @param beat          the beat position
     * @param pixelsPerBeat horizontal scale
     * @param scrollXBeats  horizontal scroll offset in beats
     * @return the X pixel coordinate
     */
    static double beatToX(double beat, double pixelsPerBeat, double scrollXBeats) {
        return (beat - scrollXBeats) * pixelsPerBeat;
    }

    /**
     * Converts an X pixel coordinate to a beat position.
     *
     * @param x             the X pixel coordinate
     * @param pixelsPerBeat horizontal scale
     * @param scrollXBeats  horizontal scroll offset in beats
     * @return the beat position
     */
    static double xToBeat(double x, double pixelsPerBeat, double scrollXBeats) {
        return x / pixelsPerBeat + scrollXBeats;
    }

    /**
     * Converts a parameter value to a Y pixel coordinate within the lane.
     * Values are mapped so that max is at the top and min is at the bottom,
     * with a small inset for visual padding.
     *
     * @param value     the parameter value
     * @param param     the automation parameter (provides min/max range)
     * @param laneY     the top of the lane in pixels
     * @param laneHeight the height of the lane in pixels
     * @return the Y pixel coordinate
     */
    static double valueToY(double value, AutomationParameter param,
                           double laneY, double laneHeight) {
        double inset = 6.0;
        double usableHeight = laneHeight - 2 * inset;
        double range = param.getMaxValue() - param.getMinValue();
        if (range <= 0) {
            return laneY + laneHeight / 2.0;
        }
        double normalized = (value - param.getMinValue()) / range;
        // Invert: high values at top, low values at bottom
        return laneY + inset + (1.0 - normalized) * usableHeight;
    }

    /**
     * Converts a Y pixel coordinate within the lane to a parameter value.
     *
     * @param y          the Y pixel coordinate
     * @param param      the automation parameter
     * @param laneY      the top of the lane in pixels
     * @param laneHeight the height of the lane in pixels
     * @return the parameter value, clamped to the parameter's valid range
     */
    static double yToValue(double y, AutomationParameter param,
                           double laneY, double laneHeight) {
        double inset = 6.0;
        double usableHeight = laneHeight - 2 * inset;
        if (usableHeight <= 0) {
            return param.getDefaultValue();
        }
        double normalized = 1.0 - (y - laneY - inset) / usableHeight;
        double value = param.getMinValue() + normalized * (param.getMaxValue() - param.getMinValue());
        return Math.max(param.getMinValue(), Math.min(param.getMaxValue(), value));
    }

    /**
     * Hit-tests breakpoint nodes at the given pixel position.
     *
     * @param lane          the automation lane
     * @param x             the X pixel coordinate to test
     * @param y             the Y pixel coordinate to test
     * @param param         the automation parameter
     * @param laneY         the top of the lane in pixels
     * @param laneHeight    the lane height in pixels
     * @param pixelsPerBeat horizontal scale
     * @param scrollXBeats  horizontal scroll offset
     * @return the automation point under the cursor, or {@code null} if none
     */
    static AutomationPoint hitTestBreakpoint(AutomationLane lane,
                                             double x, double y,
                                             AutomationParameter param,
                                             double laneY, double laneHeight,
                                             double pixelsPerBeat, double scrollXBeats) {
        for (AutomationPoint point : lane.getPoints()) {
            double px = beatToX(point.getTimeInBeats(), pixelsPerBeat, scrollXBeats);
            double py = valueToY(point.getValue(), param, laneY, laneHeight);
            double dx = x - px;
            double dy = y - py;
            if (dx * dx + dy * dy <= HIT_TOLERANCE * HIT_TOLERANCE) {
                return point;
            }
        }
        return null;
    }

    /**
     * Draws a smoothstep-curved segment between two endpoints.
     */
    private static void drawCurvedSegment(GraphicsContext gc,
                                          double x1, double y1,
                                          double x2, double y2) {
        double prevX = x1;
        double prevY = y1;
        for (int step = 1; step <= CURVE_STEPS; step++) {
            double t = (double) step / CURVE_STEPS;
            // Smoothstep: 3t² − 2t³
            double st = t * t * (3.0 - 2.0 * t);
            double cx = x1 + t * (x2 - x1);
            double cy = y1 + st * (y2 - y1);
            gc.strokeLine(prevX, prevY, cx, cy);
            prevX = cx;
            prevY = cy;
        }
    }
}
