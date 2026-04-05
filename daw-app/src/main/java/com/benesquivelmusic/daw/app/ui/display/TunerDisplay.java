package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.core.plugin.TunerPlugin.TuningResult;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Chromatic tuner display with note name, octave, frequency, and cents meter.
 *
 * <p>Renders a real-time tuner visualization showing:
 * <ul>
 *   <li>Large centered note name (e.g., "A", "C#")</li>
 *   <li>Octave number (e.g., 4 for A4)</li>
 *   <li>Detected frequency in Hz</li>
 *   <li>Cents offset bar meter (−50 to +50 cents)</li>
 *   <li>In-tune zone (±3 cents highlighted green)</li>
 *   <li>"No Signal" state when no pitched signal is detected</li>
 * </ul>
 *
 * <p>The cents bar is color-coded:
 * <ul>
 *   <li>Green — within ±3 cents (in tune)</li>
 *   <li>Yellow — within ±15 cents (close)</li>
 *   <li>Red — beyond ±15 cents (out of tune)</li>
 * </ul>
 */
public final class TunerDisplay extends Region {

    private static final Color BACKGROUND = Color.web("#0d0d1a");
    private static final Color TEXT_COLOR = Color.web("#ffffff", 0.5);
    private static final Color NOTE_COLOR = Color.web("#ffffff", 0.9);
    private static final Color IN_TUNE_COLOR = Color.web("#00e676");
    private static final Color CLOSE_COLOR = Color.web("#ffea00");
    private static final Color OUT_OF_TUNE_COLOR = Color.web("#ff1744");
    private static final Color METER_BG_COLOR = Color.web("#ffffff", 0.08);
    private static final Color IN_TUNE_ZONE_COLOR = Color.web("#00e676", 0.15);
    private static final Color TICK_COLOR = Color.web("#ffffff", 0.2);
    private static final Color CENTER_LINE_COLOR = Color.web("#ffffff", 0.4);
    private static final Color NO_SIGNAL_COLOR = Color.web("#ffffff", 0.3);

    private static final int[] TICK_CENTS = {-50, -25, 0, 25, 50};

    private static final double CLOSE_CENTS = 15.0;
    private static final double IN_TUNE_CENTS = 3.0;

    private final Canvas canvas;
    private TuningResult currentResult;

    /**
     * Creates a new tuner display.
     */
    public TunerDisplay() {
        canvas = new Canvas();
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((_, _, _) -> render());
        heightProperty().addListener((_, _, _) -> render());
    }

    /**
     * Updates the display with a new tuning result.
     *
     * @param result the latest tuning result, or {@code null} for no signal
     */
    public void update(TuningResult result) {
        this.currentResult = result;
        render();
    }

    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Background
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, w, h);

        if (currentResult == null) {
            renderNoSignal(gc, w, h);
        } else {
            renderTuning(gc, w, h);
        }
    }

    private void renderNoSignal(GraphicsContext gc, double w, double h) {
        gc.setTextAlign(TextAlignment.CENTER);

        // "No Signal" text
        double fontSize = Math.min(w, h) * 0.08;
        gc.setFont(Font.font("System", FontWeight.NORMAL, fontSize));
        gc.setFill(NO_SIGNAL_COLOR);
        gc.fillText("No Signal", w / 2, h * 0.45);

        // Hint text
        double hintSize = fontSize * 0.5;
        gc.setFont(Font.font("System", FontWeight.NORMAL, hintSize));
        gc.setFill(NO_SIGNAL_COLOR.deriveColor(0, 1, 1, 0.6));
        gc.fillText("Play a note to begin tuning", w / 2, h * 0.55);

        // Draw empty meter bar
        renderMeterBar(gc, w, h, 0, false);
    }

    private void renderTuning(GraphicsContext gc, double w, double h) {
        gc.setTextAlign(TextAlignment.CENTER);

        double cents = currentResult.centsOffset();
        boolean inTune = currentResult.inTune();
        Color statusColor = statusColor(cents);

        // Note name (large, centered)
        double noteSize = Math.min(w, h) * 0.28;
        gc.setFont(Font.font("System", FontWeight.BOLD, noteSize));
        gc.setFill(inTune ? IN_TUNE_COLOR : NOTE_COLOR);
        gc.fillText(currentResult.noteName(), w / 2, h * 0.38);

        // Octave number (to the right of note)
        double octaveSize = noteSize * 0.35;
        gc.setFont(Font.font("System", FontWeight.NORMAL, octaveSize));
        gc.setFill(TEXT_COLOR);
        double noteWidth = measureText(currentResult.noteName(), noteSize);
        gc.fillText(String.valueOf(currentResult.octave()),
                w / 2 + noteWidth / 2 + octaveSize * 0.3, h * 0.38);

        // Frequency display
        double freqSize = Math.min(w, h) * 0.06;
        gc.setFont(Font.font("System", FontWeight.NORMAL, freqSize));
        gc.setFill(TEXT_COLOR);
        gc.fillText(String.format("%.1f Hz", currentResult.frequencyHz()), w / 2, h * 0.50);

        // Cents offset text
        double centsSize = Math.min(w, h) * 0.05;
        gc.setFont(Font.font("System", FontWeight.NORMAL, centsSize));
        gc.setFill(statusColor);
        String centsText = cents >= 0
                ? String.format("+%.1f cents", cents)
                : String.format("%.1f cents", cents);
        gc.fillText(centsText, w / 2, h * 0.58);

        // Flat/Sharp indicators
        double indicatorSize = Math.min(w, h) * 0.05;
        gc.setFont(Font.font("System", FontWeight.BOLD, indicatorSize));
        double meterLeft = w * 0.10;
        double meterRight = w * 0.90;
        double meterY = h * 0.72;

        gc.setFill(cents < -IN_TUNE_CENTS ? statusColor : TEXT_COLOR.deriveColor(0, 1, 1, 0.3));
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("\u266D", meterLeft - indicatorSize * 0.3, meterY);

        gc.setFill(cents > IN_TUNE_CENTS ? statusColor : TEXT_COLOR.deriveColor(0, 1, 1, 0.3));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("\u266F", meterRight + indicatorSize * 0.3, meterY);

        gc.setTextAlign(TextAlignment.CENTER);

        // Cents meter bar
        renderMeterBar(gc, w, h, cents, true);
    }

    private void renderMeterBar(GraphicsContext gc, double w, double h,
                                double cents, boolean showIndicator) {
        double meterLeft = w * 0.10;
        double meterRight = w * 0.90;
        double meterWidth = meterRight - meterLeft;
        double meterHeight = Math.min(h * 0.06, 16);
        double meterY = h * 0.68;

        // Meter background
        gc.setFill(METER_BG_COLOR);
        gc.fillRoundRect(meterLeft, meterY, meterWidth, meterHeight, 4, 4);

        // In-tune zone highlight (±3 cents = ±3/50 of half-width)
        double centerX = meterLeft + meterWidth / 2;
        double inTuneHalfWidth = (IN_TUNE_CENTS / 50.0) * (meterWidth / 2);
        gc.setFill(IN_TUNE_ZONE_COLOR);
        gc.fillRect(centerX - inTuneHalfWidth, meterY, inTuneHalfWidth * 2, meterHeight);

        // Tick marks at -50, -25, 0, +25, +50
        gc.setStroke(TICK_COLOR);
        gc.setLineWidth(1);
        for (int tickCents : TICK_CENTS) {
            double tickX = centerX + (tickCents / 50.0) * (meterWidth / 2);
            gc.strokeLine(tickX, meterY, tickX, meterY + meterHeight);
        }

        // Center line (slightly more visible)
        gc.setStroke(CENTER_LINE_COLOR);
        gc.setLineWidth(1.5);
        gc.strokeLine(centerX, meterY - 2, centerX, meterY + meterHeight + 2);

        // Indicator needle
        if (showIndicator) {
            double clampedCents = Math.max(-50, Math.min(50, cents));
            double indicatorX = centerX + (clampedCents / 50.0) * (meterWidth / 2);
            Color indicatorColor = statusColor(cents);

            gc.setFill(indicatorColor);
            double indicatorWidth = Math.max(4, meterHeight * 0.3);
            gc.fillRoundRect(indicatorX - indicatorWidth / 2, meterY - 3,
                    indicatorWidth, meterHeight + 6, 3, 3);
        }

        // Tick labels
        double labelSize = Math.min(w, h) * 0.03;
        gc.setFont(Font.font("System", FontWeight.NORMAL, Math.max(labelSize, 8)));
        gc.setFill(TEXT_COLOR);
        gc.setTextAlign(TextAlignment.CENTER);
        double labelY = meterY + meterHeight + labelSize + 4;
        gc.fillText("-50", meterLeft, labelY);
        gc.fillText("-25", meterLeft + meterWidth * 0.25, labelY);
        gc.fillText("0", centerX, labelY);
        gc.fillText("+25", meterLeft + meterWidth * 0.75, labelY);
        gc.fillText("+50", meterRight, labelY);
    }

    private static Color statusColor(double cents) {
        double absCents = Math.abs(cents);
        if (absCents <= IN_TUNE_CENTS) return IN_TUNE_COLOR;
        if (absCents <= CLOSE_CENTS) return CLOSE_COLOR;
        return OUT_OF_TUNE_COLOR;
    }

    /**
     * Approximates text width for octave label positioning.
     * Uses a fixed ratio since {@link GraphicsContext} does not expose
     * text measurement; the approximation is sufficient for the
     * note-name + octave layout.
     */
    private static double measureText(String text, double fontSize) {
        return text.length() * fontSize * 0.6;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }
}
