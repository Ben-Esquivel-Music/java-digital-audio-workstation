package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.sdk.visualization.CorrelationData;
import com.benesquivelmusic.daw.sdk.visualization.GoniometerData;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Stereo correlation vectorscope / goniometer display.
 *
 * <p>Renders the stereo field as a circular display showing:
 * <ul>
 *   <li>Correlation coefficient indicator (-1 to +1)</li>
 *   <li>Mid/side level balance</li>
 *   <li>Stereo balance indicator</li>
 *   <li>Phase correlation history</li>
 *   <li>Goniometer (Lissajous XY) visualization</li>
 * </ul>
 *
 * <p>Supports the stereo imaging monitoring described in the
 * mastering-techniques research (§7 — Stereo Imaging), including
 * correlation metering for mono compatibility verification.</p>
 */
public final class CorrelationDisplay extends Region {

    private static final Color BACKGROUND = Color.web("#0d0d1a");
    private static final Color RING_COLOR = Color.web("#ffffff", 0.15);
    private static final Color POSITIVE_COLOR = Color.web("#00e676");
    private static final Color NEUTRAL_COLOR = Color.web("#ffea00");
    private static final Color NEGATIVE_COLOR = Color.web("#ff1744");
    private static final Color TEXT_COLOR = Color.web("#ffffff", 0.5);
    private static final Color BALANCE_COLOR = Color.web("#00e5ff");
    private static final Color GONIOMETER_DOT_COLOR = Color.web("#00e5ff", 0.6);
    private static final Color GONIOMETER_AXIS_COLOR = Color.web("#ffffff", 0.1);

    private static final int HISTORY_SIZE = 120;

    private static final double OVERLAY_ICON_SIZE = 9;

    private final Canvas canvas;
    private final double[] correlationHistory;
    private int historyIndex;
    private int historyCount;

    /** Icon overlay label for the left channel end of the stereo balance bar. */
    private final Label leftLabel;
    /** Icon overlay label for the right channel end of the stereo balance bar. */
    private final Label rightLabel;
    /** Icon overlay label for the center position of the stereo balance bar. */
    private final Label centerLabel;
    /** Icon overlay label for the Mid axis of the goniometer (only visible in goniometer mode). */
    private final Label midLabel;
    /** Icon overlay label for the Side axis of the goniometer (only visible in goniometer mode). */
    private final Label sideLabel;

    private double correlation = 1.0;
    private double midLevel = -120.0;
    private double sideLevel = -120.0;
    private double stereoBalance = 0.0;

    private boolean goniometerMode;
    private GoniometerData goniometerData;

    /**
     * Creates a new correlation display.
     */
    public CorrelationDisplay() {
        canvas = new Canvas();
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((_, _, _) -> render());
        heightProperty().addListener((_, _, _) -> render());

        correlationHistory = new double[HISTORY_SIZE];
        java.util.Arrays.fill(correlationHistory, 1.0);

        // Icon overlay labels — replace the single-letter "L", "R", "C", "M", "S" canvas text.
        // BACK (◄) = left channel, FORWARD (►) = right channel, PAN = centre position.
        leftLabel   = makeIconLabel(DawIcon.BACK,    OVERLAY_ICON_SIZE);
        rightLabel  = makeIconLabel(DawIcon.FORWARD, OVERLAY_ICON_SIZE);
        centerLabel = makeIconLabel(DawIcon.PAN,     OVERLAY_ICON_SIZE);
        // MONO = Mid (mono sum), STEREO = Side (stereo difference).
        midLabel    = makeIconLabel(DawIcon.MONO,    OVERLAY_ICON_SIZE);
        sideLabel   = makeIconLabel(DawIcon.STEREO,  OVERLAY_ICON_SIZE);

        // Mid/side labels are only relevant in goniometer mode; hide until enabled.
        midLabel.setVisible(false);
        sideLabel.setVisible(false);

        getChildren().addAll(leftLabel, rightLabel, centerLabel, midLabel, sideLabel);
    }

    private static Label makeIconLabel(DawIcon icon, double size) {
        Label label = new Label();
        label.setGraphic(IconNode.of(icon, size));
        label.setMouseTransparent(true);
        label.setManaged(false);
        return label;
    }

    /**
     * Updates the display with new correlation data.
     *
     * @param data the latest correlation measurement
     */
    public void update(CorrelationData data) {
        if (data == null) return;
        correlation = data.correlation();
        midLevel = data.midLevel();
        sideLevel = data.sideLevel();
        stereoBalance = data.stereoBalance();

        correlationHistory[historyIndex] = correlation;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
        historyCount = Math.min(historyCount + 1, HISTORY_SIZE);

        render();
    }

    /**
     * Updates the display with new goniometer data.
     *
     * @param data the latest goniometer data for Lissajous display
     */
    public void updateGoniometer(GoniometerData data) {
        this.goniometerData = data;
        render();
    }

    /**
     * Sets whether to show the goniometer (Lissajous) overlay.
     *
     * @param enabled {@code true} to show goniometer visualization
     */
    public void setGoniometerMode(boolean enabled) {
        this.goniometerMode = enabled;
        midLabel.setVisible(enabled);
        sideLabel.setVisible(enabled);
        render();
    }

    /** Returns whether goniometer mode is active. */
    public boolean isGoniometerMode() {
        return goniometerMode;
    }

    /**
     * Renders the correlation display.
     */
    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Background
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, w, h);

        if (goniometerMode && goniometerData != null && goniometerData.pointCount() > 0) {
            renderGoniometer(gc, w, h);
        }

        double centerX = w / 2;
        double topSection = h * 0.6;
        double meterY = topSection + 10;
        double historyY = meterY + 30;

        // --- Correlation arc meter ---
        double arcRadius = Math.min(centerX - 20, topSection - 30) * 0.8;
        double arcCenterY = topSection - 10;

        // Draw arc background
        gc.setStroke(RING_COLOR);
        gc.setLineWidth(3);
        gc.strokeArc(centerX - arcRadius, arcCenterY - arcRadius,
                arcRadius * 2, arcRadius * 2, 0, 180, javafx.scene.shape.ArcType.OPEN);

        // Draw scale markings (-1, 0, +1)
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("-1", centerX - arcRadius - 5, arcCenterY + 15);
        gc.fillText("0", centerX, arcCenterY - arcRadius - 5);
        gc.fillText("+1", centerX + arcRadius + 5, arcCenterY + 15);

        // Draw correlation indicator needle
        double angle = Math.PI * (1.0 - (correlation + 1.0) / 2.0); // Map [-1,1] to [PI, 0]
        double needleX = centerX + arcRadius * Math.cos(angle);
        double needleY = arcCenterY - arcRadius * Math.sin(angle);

        Color needleColor = getCorrelationColor(correlation);
        gc.setStroke(needleColor);
        gc.setLineWidth(2.5);
        gc.strokeLine(centerX, arcCenterY, needleX, needleY);

        // Needle tip dot
        gc.setFill(needleColor);
        gc.fillOval(needleX - 4, needleY - 4, 8, 8);

        // Correlation value text
        gc.setFill(needleColor);
        gc.setFont(Font.font(14));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(String.format("%.2f", correlation), centerX, arcCenterY + 5);

        // --- Stereo balance bar ---
        double barWidth = w - 40;
        double barHeight = 8;
        double barX = 20;

        gc.setFill(Color.web("#ffffff", 0.08));
        gc.fillRect(barX, meterY, barWidth, barHeight);

        // Balance indicator
        double balanceX = barX + (stereoBalance + 1.0) / 2.0 * barWidth;
        gc.setFill(BALANCE_COLOR);
        gc.fillRect(balanceX - 3, meterY - 2, 6, barHeight + 4);

        // Balance labels — replaced by icon overlay labels (leftLabel, centerLabel, rightLabel)
        // positioned in layoutChildren().

        // --- Correlation history ---
        if (historyCount > 1 && h > historyY + 20) {
            double histH = h - historyY - 5;
            double colW = barWidth / HISTORY_SIZE;

            for (int i = 0; i < historyCount; i++) {
                int idx = (historyIndex - historyCount + i + HISTORY_SIZE) % HISTORY_SIZE;
                double val = correlationHistory[idx];
                double x = barX + i * colW;
                double barH = ((val + 1.0) / 2.0) * histH;

                gc.setFill(getCorrelationColor(val).deriveColor(0, 1, 1, 0.6));
                gc.fillRect(x, historyY + histH - barH, Math.max(colW - 0.5, 0.5), barH);
            }
        }
    }

    private void renderGoniometer(GraphicsContext gc, double w, double h) {
        double centerX = w / 2;
        double centerY = h * 0.3;
        double radius = Math.min(centerX - 10, h * 0.25);

        // Draw crosshair axes
        gc.setStroke(GONIOMETER_AXIS_COLOR);
        gc.setLineWidth(1);
        gc.strokeLine(centerX - radius, centerY, centerX + radius, centerY);
        gc.strokeLine(centerX, centerY - radius, centerX, centerY + radius);

        // Draw goniometer points
        gc.setFill(GONIOMETER_DOT_COLOR);
        float[] xPts = goniometerData.xPoints();
        float[] yPts = goniometerData.yPoints();
        int count = goniometerData.pointCount();

        for (int i = 0; i < count; i++) {
            double px = centerX + xPts[i] * radius;
            double py = centerY - yPts[i] * radius;
            gc.fillOval(px - 1, py - 1, 2, 2);
        }

        // Labels — replaced by icon overlay labels (midLabel, sideLabel) positioned in layoutChildren().
    }

    private Color getCorrelationColor(double c) {
        if (c > 0.5) return POSITIVE_COLOR;
        if (c > 0.0) return NEUTRAL_COLOR;
        return NEGATIVE_COLOR;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();

        // The canvas width/height are already bound to the region dimensions; no explicit resize needed.
        // Position icon overlay labels at the same coordinates used by the old fillText() calls.
        double topSection = h * 0.6;
        double meterY     = topSection + 10;

        double barWidth  = w - 40;
        double barX      = 20;
        double iconHalf  = OVERLAY_ICON_SIZE / 2;

        // Left-channel icon: left-aligned at the start of the balance bar.
        placeLabel(leftLabel,   barX - iconHalf,              meterY - OVERLAY_ICON_SIZE - 2);
        // Right-channel icon: right-aligned at the end of the balance bar.
        placeLabel(rightLabel,  barX + barWidth - iconHalf,   meterY - OVERLAY_ICON_SIZE - 2);
        // Centre-pan icon: centred on the midpoint of the balance bar.
        placeLabel(centerLabel, barX + barWidth / 2 - iconHalf, meterY - OVERLAY_ICON_SIZE - 2);

        if (goniometerMode) {
            double centerX = w / 2;
            double centerY = h * 0.3;
            double radius  = Math.min(centerX - 10, h * 0.25);

            // Mid icon above the top of the goniometer circle.
            placeLabel(midLabel,  centerX - iconHalf,           centerY - radius - OVERLAY_ICON_SIZE - 2);
            // Side icon to the right of the goniometer circle.
            placeLabel(sideLabel, centerX + radius + 2,         centerY - iconHalf);
        }

        render();
    }

    /** Moves a non-managed label to the given (x, y) top-left position. */
    private static void placeLabel(Label label, double x, double y) {
        double pw = label.prefWidth(-1);
        double ph = label.prefHeight(-1);
        label.resizeRelocate(x, y, pw, ph);
    }
}
