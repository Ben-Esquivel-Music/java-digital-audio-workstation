package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * LUFS loudness history display with platform target markers.
 *
 * <p>Renders a rolling graph of momentary, short-term, and integrated LUFS
 * values with horizontal target lines for streaming platform compliance.
 * Supports the loudness standards from the mastering-techniques
 * research (§8 — Loudness Standards and Metering).</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Rolling loudness history graph with three traces</li>
 *   <li>Momentary (cyan), short-term (green), and integrated (yellow) traces</li>
 *   <li>Simultaneous momentary, short-term, and integrated LUFS readouts</li>
 *   <li>Loudness range (LRA) indicator</li>
 *   <li>True-peak indicator</li>
 *   <li>Platform target presets (Spotify, Apple Music, YouTube, CD, Broadcast)</li>
 *   <li>Color-coded integrated reading based on target compliance</li>
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
    private static final Color COMPLIANCE_OK_COLOR = Color.web("#00e676");
    private static final Color COMPLIANCE_WARN_COLOR = Color.web("#ffea00");
    private static final Color COMPLIANCE_FAIL_COLOR = Color.web("#ff1744");
    private static final Color TRUE_PEAK_CLIP_COLOR = Color.web("#ff1744");

    private static final double MIN_LUFS = -60.0;
    private static final double MAX_LUFS = 0.0;
    private static final int HISTORY_SIZE = 300;
    private static final double COMPLIANCE_TOLERANCE_LU = 1.0;

    private final Canvas canvas;
    private final double[] momentaryHistory;
    private final double[] shortTermHistory;
    private final double[] integratedHistory;
    private int historyIndex;
    private int historyCount;

    private double momentaryLufs = Double.NEGATIVE_INFINITY;
    private double shortTermLufs = Double.NEGATIVE_INFINITY;
    private double integratedLufs = Double.NEGATIVE_INFINITY;
    private double loudnessRange = 0.0;
    private double truePeakDbfs = Double.NEGATIVE_INFINITY;
    private double targetLufs = -14.0; // Default: Spotify/YouTube
    private String targetName = "Spotify";

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
        integratedHistory = new double[HISTORY_SIZE];
        java.util.Arrays.fill(momentaryHistory, MIN_LUFS);
        java.util.Arrays.fill(shortTermHistory, MIN_LUFS);
        java.util.Arrays.fill(integratedHistory, MIN_LUFS);
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
        integratedHistory[historyIndex] = Math.max(data.integratedLufs(), MIN_LUFS);
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
        historyCount = Math.min(historyCount + 1, HISTORY_SIZE);

        momentaryLufs = data.momentaryLufs();
        shortTermLufs = data.shortTermLufs();
        integratedLufs = data.integratedLufs();
        loudnessRange = data.loudnessRange();
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
     * Sets the platform target preset for compliance checking and display.
     *
     * @param target the platform loudness target preset
     */
    public void setTarget(LoudnessTarget target) {
        if (target == null) return;
        this.targetLufs = target.targetIntegratedLufs();
        this.targetName = target.displayName();
        render();
    }

    /**
     * Returns the current target name being displayed.
     *
     * @return the target display name
     */
    public String getTargetName() {
        return targetName;
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

        double graphH = h - 48; // Reserve space for three rows of labels
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

        // Integrated trace (drawn first, behind the others)
        gc.setStroke(INTEGRATED_COLOR.deriveColor(0, 1.0, 1.0, 0.5));
        gc.beginPath();
        for (int i = 0; i < historyCount; i++) {
            int idx = (historyIndex - historyCount + i + HISTORY_SIZE) % HISTORY_SIZE;
            double x = 30 + i * colWidth;
            double v = integratedHistory[idx];
            double y = graphY + (1.0 - (v - MIN_LUFS) / (MAX_LUFS - MIN_LUFS)) * graphH;
            if (i == 0) gc.moveTo(x, y);
            else gc.lineTo(x, y);
        }
        gc.stroke();

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

        // --- Row 1: Momentary, Short-Term, Integrated LUFS readouts ---
        double row1Y = h - 34;
        gc.setFont(Font.font(11));
        gc.setTextAlign(TextAlignment.LEFT);

        // Momentary
        gc.setFill(MOMENTARY_COLOR);
        String momText = (momentaryLufs > MIN_LUFS)
                ? String.format("M: %.1f", momentaryLufs)
                : "M: ---";
        gc.fillText(momText, 35, row1Y);

        // Short-term
        gc.setFill(SHORT_TERM_COLOR);
        String stText = (shortTermLufs > MIN_LUFS)
                ? String.format("S: %.1f", shortTermLufs)
                : "S: ---";
        gc.fillText(stText, 35 + (w - 35) * 0.33, row1Y);

        // Integrated (color-coded for compliance)
        Color intColor = getComplianceColor();
        gc.setFill(intColor);
        String intText = (integratedLufs > MIN_LUFS)
                ? String.format("I: %.1f LUFS", integratedLufs)
                : "I: ---";
        gc.fillText(intText, 35 + (w - 35) * 0.66, row1Y);

        // --- Row 2: LRA and True Peak ---
        double row2Y = h - 20;
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.LEFT);

        // LRA
        gc.setFill(TEXT_COLOR);
        String lraText = (loudnessRange > 0.0)
                ? String.format("LRA: %.1f LU", loudnessRange)
                : "LRA: ---";
        gc.fillText(lraText, 35, row2Y);

        // True peak
        boolean truePeakClipping = truePeakDbfs > -1.0;
        gc.setFill(truePeakClipping ? TRUE_PEAK_CLIP_COLOR : TEXT_COLOR);
        String tpText = (truePeakDbfs > -100)
                ? String.format("TP: %.1f dBFS", truePeakDbfs)
                : "TP: ---";
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(tpText, w - 5, row2Y);

        // --- Row 3: Target label ---
        double row3Y = h - 6;
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(TARGET_COLOR);
        gc.fillText(String.format("%s: %.0f LUFS", targetName, targetLufs), 35, row3Y);
    }

    /**
     * Returns a color reflecting how close the integrated loudness is
     * to the current target: green for within ±1 LU, yellow for within
     * ±3 LU, red otherwise.
     */
    private Color getComplianceColor() {
        if (integratedLufs <= MIN_LUFS) {
            return INTEGRATED_COLOR;
        }
        double diff = Math.abs(integratedLufs - targetLufs);
        if (diff <= COMPLIANCE_TOLERANCE_LU) {
            return COMPLIANCE_OK_COLOR;
        } else if (diff <= 3.0) {
            return COMPLIANCE_WARN_COLOR;
        } else {
            return COMPLIANCE_FAIL_COLOR;
        }
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }
}
