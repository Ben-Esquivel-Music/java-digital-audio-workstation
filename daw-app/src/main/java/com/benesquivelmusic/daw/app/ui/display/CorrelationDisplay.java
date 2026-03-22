package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.visualization.CorrelationData;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
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

    private static final int HISTORY_SIZE = 120;

    private final Canvas canvas;
    private final double[] correlationHistory;
    private int historyIndex;
    private int historyCount;

    private double correlation = 1.0;
    private double midLevel = -120.0;
    private double sideLevel = -120.0;
    private double stereoBalance = 0.0;

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

        // Balance labels
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font(9));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("L", barX, meterY - 4);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("R", barX + barWidth, meterY - 4);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("C", barX + barWidth / 2, meterY - 4);

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

    private Color getCorrelationColor(double c) {
        if (c > 0.5) return POSITIVE_COLOR;
        if (c > 0.0) return NEUTRAL_COLOR;
        return NEGATIVE_COLOR;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }
}
