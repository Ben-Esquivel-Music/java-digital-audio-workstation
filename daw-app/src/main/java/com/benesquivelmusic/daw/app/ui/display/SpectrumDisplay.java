package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.paint.CycleMethod;

/**
 * Real-time animated spectrum analyzer display.
 *
 * <p>Renders FFT spectrum data as a smooth bar/line graph with gradient
 * coloring, grid overlay, and logarithmic frequency scaling. Supports
 * the spectrum analysis requirements from the mastering-techniques
 * research (§3 — Equalization, §8 — Loudness Standards).</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Logarithmic frequency scale (20 Hz – 20 kHz)</li>
 *   <li>Configurable dB range (default: -90 dB to 0 dB)</li>
 *   <li>Gradient color fill from low to high frequencies</li>
 *   <li>Grid lines at standard frequency markers</li>
 *   <li>Smooth animated transitions via {@link MeterAnimator}</li>
 * </ul>
 */
public final class SpectrumDisplay extends Region {

    private static final Color BACKGROUND = Color.web("#0d0d1a");
    private static final Color GRID_COLOR = Color.web("#ffffff", 0.08);
    private static final Color TEXT_COLOR = Color.web("#ffffff", 0.4);
    private static final double MIN_DB = -90.0;
    private static final double MAX_DB = 0.0;

    private static final double[] FREQ_MARKERS = {
            20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000
    };

    private final Canvas canvas;
    private final float[] smoothedBins;
    private final LinearGradient barGradient;

    private SpectrumData data;

    /**
     * Creates a new spectrum display with the specified number of display bars.
     *
     * @param displayBars the number of visible frequency bars
     */
    public SpectrumDisplay(int displayBars) {
        if (displayBars <= 0) {
            throw new IllegalArgumentException("displayBars must be positive: " + displayBars);
        }
        canvas = new Canvas();
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((_, _, _) -> render());
        heightProperty().addListener((_, _, _) -> render());

        smoothedBins = new float[displayBars];
        java.util.Arrays.fill(smoothedBins, (float) MIN_DB);

        barGradient = new LinearGradient(0, 1, 0, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#00e5ff", 0.9)),
                new Stop(0.5, Color.web("#00e676", 0.9)),
                new Stop(0.8, Color.web("#ffea00", 0.9)),
                new Stop(1.0, Color.web("#ff1744", 0.9))
        );
    }

    /**
     * Creates a spectrum display with 64 bars.
     */
    public SpectrumDisplay() {
        this(64);
    }

    /**
     * Updates the spectrum data with animated smoothing.
     *
     * @param newData the latest spectrum snapshot
     */
    public void updateSpectrum(SpectrumData newData) {
        this.data = newData;
        if (newData == null) return;

        // Map FFT bins to display bars using logarithmic frequency mapping
        float[] magnitudes = newData.magnitudesDb();
        int binCount = magnitudes.length;
        double sampleRate = newData.sampleRate();
        int fftSize = newData.fftSize();

        for (int bar = 0; bar < smoothedBins.length; bar++) {
            // Logarithmic frequency mapping
            double fLow = 20.0 * Math.pow(1000.0, (double) bar / smoothedBins.length);
            double fHigh = 20.0 * Math.pow(1000.0, (double) (bar + 1) / smoothedBins.length);
            int binLow = (int) (fLow * fftSize / sampleRate);
            int binHigh = (int) (fHigh * fftSize / sampleRate);
            binLow = Math.max(0, Math.min(binLow, binCount - 1));
            binHigh = Math.max(binLow, Math.min(binHigh, binCount - 1));

            float maxMag = (float) MIN_DB;
            for (int b = binLow; b <= binHigh; b++) {
                if (magnitudes[b] > maxMag) maxMag = magnitudes[b];
            }

            // Smooth animation: fast attack, slow release
            float smoothing = (maxMag > smoothedBins[bar]) ? 0.3f : 0.85f;
            smoothedBins[bar] = smoothing * smoothedBins[bar] + (1.0f - smoothing) * maxMag;
        }

        render();
    }

    /**
     * Renders the spectrum to the canvas.
     */
    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Clear background
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, w, h);

        // Draw grid lines at frequency markers
        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(1.0);
        for (double freq : FREQ_MARKERS) {
            double x = frequencyToX(freq, w);
            if (x >= 0 && x <= w) {
                gc.strokeLine(x, 0, x, h);
            }
        }

        // Draw dB grid lines
        for (double db = MIN_DB; db <= MAX_DB; db += 10) {
            double y = dbToY(db, h);
            gc.strokeLine(0, y, w, y);
        }

        // Draw spectrum bars
        double barWidth = w / smoothedBins.length;
        gc.setFill(barGradient);
        for (int i = 0; i < smoothedBins.length; i++) {
            double db = smoothedBins[i];
            if (db <= MIN_DB) continue;

            double barHeight = (db - MIN_DB) / (MAX_DB - MIN_DB) * h;
            double x = i * barWidth;
            double y = h - barHeight;

            gc.fillRect(x + 1, y, Math.max(barWidth - 2, 1), barHeight);
        }

        // Draw frequency labels
        gc.setFill(TEXT_COLOR);
        gc.setFont(javafx.scene.text.Font.font(9));
        for (double freq : FREQ_MARKERS) {
            double x = frequencyToX(freq, w);
            if (x >= 0 && x <= w - 30) {
                String label = (freq >= 1000) ? String.format("%.0fk", freq / 1000) : String.format("%.0f", freq);
                gc.fillText(label, x + 2, h - 4);
            }
        }
    }

    private double frequencyToX(double freq, double width) {
        double minLog = Math.log10(20);
        double maxLog = Math.log10(20000);
        double freqLog = Math.log10(Math.max(freq, 20));
        return (freqLog - minLog) / (maxLog - minLog) * width;
    }

    private double dbToY(double db, double height) {
        return height - (db - MIN_DB) / (MAX_DB - MIN_DB) * height;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }
}
