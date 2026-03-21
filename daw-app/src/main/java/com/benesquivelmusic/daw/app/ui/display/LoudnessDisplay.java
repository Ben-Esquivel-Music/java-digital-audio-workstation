package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * LUFS loudness history display with platform target markers.
 *
 * <p>Renders a rolling graph of momentary and short-term LUFS values
 * with horizontal target lines for streaming platform compliance.
 * Supports the loudness standards from the mastering-techniques
 * research (§8 — Loudness Standards and Metering).</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Rolling loudness history graph</li>
 *   <li>Momentary (cyan) and short-term (green) traces</li>
 *   <li>Integrated loudness display</li>
 *   <li>Platform target markers (Spotify, Apple Music, YouTube)</li>
 *   <li>True-peak indicator</li>
 * </ul>
 */
public final class LoudnessDisplay extends Region {

    private static final Color BACKGROUND = Color.web("#0d0d1a");
    private static final Color GRID_COLOR = Color.web("#ffffff", 0.08);
    private static final Color TEXT_COLOR = Color.web("#ffffff", 0.5);
    private static final Color MOMENTARY_COLOR = Color.web("#00e5ff");
    private static final Color SHORT_TERM_COLOR = Color.web("#00e676");
    private static final Color INTEGRATED_COLOR = Color.web("#ffea00");
    private static final Color TARGET_COLOR = Color.web("#ff9100", 0.5);

    private static final double MIN_LUFS = -60.0;
    private static final double MAX_LUFS = 0.0;
    private static final int HISTORY_SIZE = 300;

    private final Canvas canvas;
    private final double[] momentaryHistory;
    private final double[] shortTermHistory;
    private int historyIndex;
    private int historyCount;

    private double integratedLufs = Double.NEGATIVE_INFINITY;
    private double truePeakDbfs = Double.NEGATIVE_INFINITY;
    private double targetLufs = -14.0; // Default: Spotify/YouTube

    /**
     * Creates a new loudness display.
     */
    public LoudnessDisplay() {
        canvas = new Canvas();
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((_, _, _) -> render());
        heightProperty().addListener((_, _, _) -> render());

        momentaryHistory = new double[HISTORY_SIZE];
        shortTermHistory = new double[HISTORY_SIZE];
        java.util.Arrays.fill(momentaryHistory, MIN_LUFS);
        java.util.Arrays.fill(shortTermHistory, MIN_LUFS);
    }

    /**
     * Updates the display with new loudness data.
     *
     * @param data the latest loudness measurement
     */
    public void update(LoudnessData data) {
        if (data == null) return;

        momentaryHistory[historyIndex] = Math.max(data.momentaryLufs(), MIN_LUFS);
        shortTermHistory[historyIndex] = Math.max(data.shortTermLufs(), MIN_LUFS);
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
        historyCount = Math.min(historyCount + 1, HISTORY_SIZE);

        integratedLufs = data.integratedLufs();
        truePeakDbfs = data.truePeakDbfs();

        render();
    }

    /**
     * Sets the target loudness level for compliance markers.
     *
     * @param lufs the target in LUFS (e.g., -14.0 for Spotify)
     */
    public void setTargetLufs(double lufs) {
        this.targetLufs = lufs;
        render();
    }

    /**
     * Renders the loudness display.
     */
    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Background
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, w, h);

        double graphH = h - 30; // Reserve space for labels
        double graphY = 10;

        // Grid lines and dB labels
        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(0.5);
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font(9));
        gc.setTextAlign(TextAlignment.RIGHT);
        for (double lufs = MIN_LUFS; lufs <= MAX_LUFS; lufs += 6) {
            double y = graphY + (1.0 - (lufs - MIN_LUFS) / (MAX_LUFS - MIN_LUFS)) * graphH;
            gc.strokeLine(30, y, w, y);
            gc.fillText(String.format("%.0f", lufs), 28, y + 3);
        }

        // Target line
        double targetY = graphY + (1.0 - (targetLufs - MIN_LUFS) / (MAX_LUFS - MIN_LUFS)) * graphH;
        gc.setStroke(TARGET_COLOR);
        gc.setLineWidth(1.5);
        gc.setLineDashes(6, 4);
        gc.strokeLine(30, targetY, w, targetY);
        gc.setLineDashes(null);

        // Draw history traces
        double colWidth = (w - 30) / HISTORY_SIZE;
        gc.setLineWidth(1.5);

        // Short-term trace
        gc.setStroke(SHORT_TERM_COLOR);
        gc.beginPath();
        for (int i = 0; i < historyCount; i++) {
            int idx = (historyIndex - historyCount + i + HISTORY_SIZE) % HISTORY_SIZE;
            double x = 30 + i * colWidth;
            double v = shortTermHistory[idx];
            double y = graphY + (1.0 - (v - MIN_LUFS) / (MAX_LUFS - MIN_LUFS)) * graphH;
            if (i == 0) gc.moveTo(x, y);
            else gc.lineTo(x, y);
        }
        gc.stroke();

        // Momentary trace
        gc.setStroke(MOMENTARY_COLOR);
        gc.beginPath();
        for (int i = 0; i < historyCount; i++) {
            int idx = (historyIndex - historyCount + i + HISTORY_SIZE) % HISTORY_SIZE;
            double x = 30 + i * colWidth;
            double v = momentaryHistory[idx];
            double y = graphY + (1.0 - (v - MIN_LUFS) / (MAX_LUFS - MIN_LUFS)) * graphH;
            if (i == 0) gc.moveTo(x, y);
            else gc.lineTo(x, y);
        }
        gc.stroke();

        // Integrated loudness text
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFont(Font.font(11));
        gc.setFill(INTEGRATED_COLOR);
        String intText = (integratedLufs > MIN_LUFS)
                ? String.format("INT: %.1f LUFS", integratedLufs)
                : "INT: ---";
        gc.fillText(intText, 35, h - 5);

        // True peak
        gc.setFill(TEXT_COLOR);
        String tpText = (truePeakDbfs > -100)
                ? String.format("TP: %.1f dBFS", truePeakDbfs)
                : "TP: ---";
        gc.fillText(tpText, 170, h - 5);

        // Target label
        gc.setFill(TARGET_COLOR);
        gc.fillText(String.format("Target: %.0f LUFS", targetLufs), w - 120, h - 5);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }
}
