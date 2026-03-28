package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.spatial.SpatialPannerData;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPosition;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * Canvas-based 3D spatial panner widget for immersive audio positioning.
 *
 * <p>Renders a top-down (X/Y) view of the spatial environment showing:
 * <ul>
 *   <li>Speaker positions as labeled reference points</li>
 *   <li>Draggable source position indicator</li>
 *   <li>Distance attenuation ring at the reference distance</li>
 *   <li>Per-speaker gain visualization (size proportional to gain)</li>
 *   <li>Numeric readouts for azimuth, elevation, and distance</li>
 * </ul>
 *
 * <p>The side view (X/Z) is rendered in the right portion of the widget
 * when height (elevation) information is relevant, enabling full 3D
 * positioning control.</p>
 *
 * <p>Mouse drag on the top-down area adjusts azimuth and distance;
 * mouse drag on the side view adjusts elevation. The controller wires
 * these interactions to the underlying {@code VbapPanner} or
 * {@code AmbisonicEncoder}.</p>
 */
public final class SpatialPannerDisplay extends Region {

    // ── Color palette ─────────────────────────────────────────────
    private static final Color BACKGROUND = Color.web("#0a0a1e");
    private static final Color GRID_COLOR = Color.web("#ffffff", 0.08);
    private static final Color RING_COLOR = Color.web("#ffffff", 0.15);
    private static final Color SPEAKER_COLOR = Color.web("#69f0ae");
    private static final Color SPEAKER_LABEL_COLOR = Color.web("#69f0ae", 0.8);
    private static final Color SOURCE_COLOR = Color.web("#ff4081");
    private static final Color SOURCE_GLOW = Color.web("#ff4081", 0.3);
    private static final Color GAIN_COLOR = Color.web("#00e5ff", 0.5);
    private static final Color TEXT_COLOR = Color.web("#e0e0e0");
    private static final Color READOUT_BG = Color.web("#1a1a3a", 0.85);
    private static final Color DIVIDER_COLOR = Color.web("#ffffff", 0.12);
    private static final Color LISTENER_COLOR = Color.web("#7c4dff");
    private static final Color SIDE_VIEW_LABEL = Color.web("#ffea00", 0.7);

    // ── Layout constants ──────────────────────────────────────────
    public static final double TOP_VIEW_RATIO = 0.65;
    public static final double SIDE_VIEW_RATIO = 0.35;
    public static final double READOUT_HEIGHT = 28.0;
    public static final double PADDING = 12.0;
    static final double SOURCE_RADIUS = 8.0;
    static final double SPEAKER_RADIUS = 5.0;
    static final double LISTENER_RADIUS = 4.0;
    static final double MAX_GAIN_RADIUS = 14.0;
    public static final double MAX_DISPLAY_DISTANCE = 10.0;

    private static final Font LABEL_FONT = Font.font("Monospace", 9);
    private static final Font READOUT_FONT = Font.font("Monospace", 11);
    private static final Font HEADER_FONT = Font.font("Monospace", 10);

    private final Canvas canvas;

    private SpatialPannerData pannerData;

    /**
     * Creates a new spatial panner display.
     */
    public SpatialPannerDisplay() {
        canvas = new Canvas();
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((obs, oldVal, newVal) -> render());
        heightProperty().addListener((obs, oldVal, newVal) -> render());
        getStyleClass().add("spatial-panner-display");
    }

    /**
     * Updates the display with new panner data and re-renders.
     *
     * @param data the current panner state snapshot
     */
    public void update(SpatialPannerData data) {
        this.pannerData = data;
        render();
    }

    /**
     * Returns the current panner data, or {@code null} if none has been set.
     *
     * @return the panner data
     */
    public SpatialPannerData getPannerData() {
        return pannerData;
    }

    /**
     * Returns the canvas used for rendering. Package-private for testing.
     *
     * @return the canvas
     */
    Canvas getCanvas() {
        return canvas;
    }

    // ── Coordinate mapping (package-private for testing) ──────────

    /**
     * Maps a spatial X coordinate (right axis, meters) to a pixel X
     * within the top-down view area.
     *
     * @param spatialX the spatial X in meters
     * @param viewCenterX the pixel X of the view center
     * @param viewRadius the pixel radius of the view
     * @return the pixel X coordinate
     */
    public static double spatialXToPixelX(double spatialX, double viewCenterX, double viewRadius) {
        return viewCenterX + (spatialX / MAX_DISPLAY_DISTANCE) * viewRadius;
    }

    /**
     * Maps a spatial Y coordinate (front axis, meters) to a pixel Y
     * within the top-down view area. Y increases upward in spatial
     * coordinates but downward in pixel coordinates.
     *
     * @param spatialY the spatial Y in meters
     * @param viewCenterY the pixel Y of the view center
     * @param viewRadius the pixel radius of the view
     * @return the pixel Y coordinate
     */
    public static double spatialYToPixelY(double spatialY, double viewCenterY, double viewRadius) {
        return viewCenterY - (spatialY / MAX_DISPLAY_DISTANCE) * viewRadius;
    }

    /**
     * Maps a pixel X within the top-down view back to a spatial X
     * coordinate in meters.
     *
     * @param pixelX the pixel X
     * @param viewCenterX the pixel X of the view center
     * @param viewRadius the pixel radius of the view
     * @return the spatial X in meters
     */
    public static double pixelXToSpatialX(double pixelX, double viewCenterX, double viewRadius) {
        if (viewRadius < 1e-9) {
            return 0.0;
        }
        return ((pixelX - viewCenterX) / viewRadius) * MAX_DISPLAY_DISTANCE;
    }

    /**
     * Maps a pixel Y within the top-down view back to a spatial Y
     * coordinate in meters.
     *
     * @param pixelY the pixel Y
     * @param viewCenterY the pixel Y of the view center
     * @param viewRadius the pixel radius of the view
     * @return the spatial Y in meters
     */
    public static double pixelYToSpatialY(double pixelY, double viewCenterY, double viewRadius) {
        if (viewRadius < 1e-9) {
            return 0.0;
        }
        return ((viewCenterY - pixelY) / viewRadius) * MAX_DISPLAY_DISTANCE;
    }

    /**
     * Maps a spatial Z coordinate (up axis, meters) to a pixel Y in the
     * side view area. Z increases upward in spatial coordinates but
     * downward in pixel coordinates.
     *
     * @param spatialZ the spatial Z in meters
     * @param viewCenterY the pixel Y of the side view center
     * @param viewRadius the pixel radius of the side view
     * @return the pixel Y coordinate
     */
    public static double spatialZToPixelY(double spatialZ, double viewCenterY, double viewRadius) {
        return viewCenterY - (spatialZ / MAX_DISPLAY_DISTANCE) * viewRadius;
    }

    /**
     * Maps a pixel Y in the side view back to a spatial Z coordinate in meters.
     *
     * @param pixelY the pixel Y
     * @param viewCenterY the pixel Y of the side view center
     * @param viewRadius the pixel radius of the side view
     * @return the spatial Z in meters
     */
    public static double pixelYToSpatialZ(double pixelY, double viewCenterY, double viewRadius) {
        if (viewRadius < 1e-9) {
            return 0.0;
        }
        return ((viewCenterY - pixelY) / viewRadius) * MAX_DISPLAY_DISTANCE;
    }

    /**
     * Clamps a value to the given range.
     *
     * @param value the value
     * @param min   the minimum
     * @param max   the maximum
     * @return the clamped value
     */
    public static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    /**
     * Formats a degree value for display.
     *
     * @param degrees the angle in degrees
     * @return a formatted string like "45.0°"
     */
    public static String formatDegrees(double degrees) {
        return String.format("%.1f\u00B0", degrees);
    }

    /**
     * Formats a distance value for display.
     *
     * @param meters the distance in meters
     * @return a formatted string like "1.50 m"
     */
    public static String formatDistance(double meters) {
        return String.format("%.2f m", meters);
    }

    // ── Rendering ─────────────────────────────────────────────────

    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Clear background
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, w, h);

        double drawHeight = h - READOUT_HEIGHT;
        if (drawHeight <= 0) {
            return;
        }

        double topViewWidth = w * TOP_VIEW_RATIO;
        double sideViewWidth = w * SIDE_VIEW_RATIO;

        // Top-down view geometry
        double topCenterX = topViewWidth / 2.0;
        double topCenterY = drawHeight / 2.0;
        double topRadius = Math.min(topViewWidth, drawHeight) / 2.0 - PADDING;

        // Side view geometry
        double sideCenterX = topViewWidth + sideViewWidth / 2.0;
        double sideCenterY = drawHeight / 2.0;
        double sideRadius = Math.min(sideViewWidth, drawHeight) / 2.0 - PADDING;

        // Draw top-down view
        renderTopView(gc, topCenterX, topCenterY, topRadius);

        // Divider between views
        gc.setStroke(DIVIDER_COLOR);
        gc.setLineWidth(1.0);
        gc.strokeLine(topViewWidth, 0, topViewWidth, drawHeight);

        // Draw side view (X/Z)
        renderSideView(gc, sideCenterX, sideCenterY, sideRadius, topCenterX, topRadius);

        // Readouts at the bottom
        renderReadouts(gc, w, drawHeight);
    }

    private void renderTopView(GraphicsContext gc, double cx, double cy, double radius) {
        if (radius <= 0) {
            return;
        }

        // Grid circles
        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(0.5);
        for (int ring = 1; ring <= 4; ring++) {
            double r = radius * ring / 4.0;
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
        }

        // Crosshair
        gc.strokeLine(cx - radius, cy, cx + radius, cy);
        gc.strokeLine(cx, cy - radius, cx, cy + radius);

        // Listener at center
        gc.setFill(LISTENER_COLOR);
        gc.fillOval(cx - LISTENER_RADIUS, cy - LISTENER_RADIUS,
                LISTENER_RADIUS * 2, LISTENER_RADIUS * 2);

        // "Top" label
        gc.setFill(SIDE_VIEW_LABEL);
        gc.setFont(HEADER_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("TOP (X/Y)", cx, 14);

        if (pannerData == null) {
            return;
        }

        List<SpatialPosition> speakers = pannerData.speakerPositions();
        double[] gains = pannerData.speakerGains();

        // Draw speaker positions with gain indicators
        for (int i = 0; i < speakers.size(); i++) {
            SpatialPosition sp = speakers.get(i);
            double px = spatialXToPixelX(sp.x(), cx, radius);
            double py = spatialYToPixelY(sp.y(), cy, radius);

            // Gain ring
            if (i < gains.length && gains[i] > 0.01) {
                double gainRadius = SPEAKER_RADIUS + gains[i] * MAX_GAIN_RADIUS;
                gc.setFill(GAIN_COLOR);
                gc.fillOval(px - gainRadius, py - gainRadius,
                        gainRadius * 2, gainRadius * 2);
            }

            // Speaker dot
            gc.setFill(SPEAKER_COLOR);
            gc.fillOval(px - SPEAKER_RADIUS, py - SPEAKER_RADIUS,
                    SPEAKER_RADIUS * 2, SPEAKER_RADIUS * 2);

            // Speaker label
            gc.setFill(SPEAKER_LABEL_COLOR);
            gc.setFont(LABEL_FONT);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("S" + i, px, py - SPEAKER_RADIUS - 3);
        }

        // Distance ring (reference distance)
        double refDist = 1.0;
        double refPixelRadius = (refDist / MAX_DISPLAY_DISTANCE) * radius;
        gc.setStroke(RING_COLOR);
        gc.setLineWidth(1.0);
        gc.setLineDashes(4, 4);
        gc.strokeOval(cx - refPixelRadius, cy - refPixelRadius,
                refPixelRadius * 2, refPixelRadius * 2);
        gc.setLineDashes(null);

        // Source position
        SpatialPosition source = pannerData.sourcePosition();
        double srcPx = spatialXToPixelX(source.x(), cx, radius);
        double srcPy = spatialYToPixelY(source.y(), cy, radius);

        // Source glow
        RadialGradient glow = new RadialGradient(
                0, 0, srcPx, srcPy, SOURCE_RADIUS * 2.5,
                false, CycleMethod.NO_CYCLE,
                new Stop(0.0, SOURCE_GLOW),
                new Stop(1.0, Color.TRANSPARENT));
        gc.setFill(glow);
        gc.fillOval(srcPx - SOURCE_RADIUS * 2.5, srcPy - SOURCE_RADIUS * 2.5,
                SOURCE_RADIUS * 5, SOURCE_RADIUS * 5);

        // Source dot
        gc.setFill(SOURCE_COLOR);
        gc.fillOval(srcPx - SOURCE_RADIUS, srcPy - SOURCE_RADIUS,
                SOURCE_RADIUS * 2, SOURCE_RADIUS * 2);
    }

    private void renderSideView(GraphicsContext gc, double cx, double cy,
                                double radius, double topCx, double topRadius) {
        if (radius <= 0) {
            return;
        }

        // Grid
        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(0.5);
        for (int ring = 1; ring <= 4; ring++) {
            double r = radius * ring / 4.0;
            gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
        }
        gc.strokeLine(cx - radius, cy, cx + radius, cy);
        gc.strokeLine(cx, cy - radius, cx, cy + radius);

        // Listener at center
        gc.setFill(LISTENER_COLOR);
        gc.fillOval(cx - LISTENER_RADIUS, cy - LISTENER_RADIUS,
                LISTENER_RADIUS * 2, LISTENER_RADIUS * 2);

        // "Side" label
        gc.setFill(SIDE_VIEW_LABEL);
        gc.setFont(HEADER_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        double labelXPos = cx;
        gc.fillText("SIDE (X/Z)", labelXPos, 14);

        if (pannerData == null) {
            return;
        }

        // Speakers in side view (X/Z projection)
        List<SpatialPosition> speakers = pannerData.speakerPositions();
        for (int i = 0; i < speakers.size(); i++) {
            SpatialPosition sp = speakers.get(i);
            double px = cx + (sp.x() / MAX_DISPLAY_DISTANCE) * radius;
            double py = spatialZToPixelY(sp.z(), cy, radius);

            gc.setFill(SPEAKER_COLOR);
            gc.fillOval(px - SPEAKER_RADIUS, py - SPEAKER_RADIUS,
                    SPEAKER_RADIUS * 2, SPEAKER_RADIUS * 2);
        }

        // Source in side view
        SpatialPosition source = pannerData.sourcePosition();
        double srcPx = cx + (source.x() / MAX_DISPLAY_DISTANCE) * radius;
        double srcPy = spatialZToPixelY(source.z(), cy, radius);

        gc.setFill(SOURCE_COLOR);
        gc.fillOval(srcPx - SOURCE_RADIUS, srcPy - SOURCE_RADIUS,
                SOURCE_RADIUS * 2, SOURCE_RADIUS * 2);
    }

    private void renderReadouts(GraphicsContext gc, double totalWidth, double drawHeight) {
        // Readout background
        gc.setFill(READOUT_BG);
        gc.fillRect(0, drawHeight, totalWidth, READOUT_HEIGHT);

        gc.setFont(READOUT_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(TEXT_COLOR);

        if (pannerData == null) {
            gc.fillText("  Az: ---  El: ---  Dist: ---  Gain: ---", 8, drawHeight + 18);
            return;
        }

        SpatialPosition source = pannerData.sourcePosition();
        String azText = "Az: " + formatDegrees(source.azimuthDegrees());
        String elText = "El: " + formatDegrees(source.elevationDegrees());
        String distText = "Dist: " + formatDistance(source.distanceMeters());
        String gainText = String.format("Gain: %.1f dB",
                20.0 * Math.log10(Math.max(pannerData.distanceGain(), 1e-10)));

        double spacing = totalWidth / 4.0;
        gc.fillText(azText, 8, drawHeight + 18);
        gc.fillText(elText, spacing + 8, drawHeight + 18);
        gc.fillText(distText, spacing * 2 + 8, drawHeight + 18);
        gc.fillText(gainText, spacing * 3 + 8, drawHeight + 18);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }
}
