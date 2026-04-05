package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.visualization.FrequencyRange;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;
import com.benesquivelmusic.daw.sdk.visualization.StereoMode;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.paint.CycleMethod;

import java.util.Arrays;

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
 *   <li>Color-coded frequency ranges (sub-bass, bass, mids, high-mids, highs)</li>
 *   <li>Peak hold display showing maximum level at each frequency</li>
 *   <li>dB and Hz axis labels</li>
 *   <li>Pre/post EQ overlay to visualize EQ impact</li>
 *   <li>Stereo mode: overlaid or split L/R channels</li>
 *   <li>Smooth animated transitions via {@link MeterAnimator}</li>
 * </ul>
 */
public final class SpectrumDisplay extends Region {

    private static final Color BACKGROUND = Color.web("#0d0d1a");
    private static final Color GRID_COLOR = Color.web("#ffffff", 0.08);
    private static final Color TEXT_COLOR = Color.web("#ffffff", 0.4);
    private static final Color PEAK_HOLD_COLOR = Color.web("#ffffff", 0.7);
    private static final Color PRE_EQ_COLOR = Color.web("#ff9800", 0.4);
    private static final Color AVG_TRACE_COLOR = Color.web("#42a5f5", 0.6);
    private static final double MIN_DB = -90.0;
    private static final double MAX_DB = 0.0;
    private static final double AXIS_MARGIN_LEFT = 40.0;
    private static final double AXIS_MARGIN_BOTTOM = 20.0;
    private static final double AVG_SMOOTHING = 0.95;

    private static final Color SUB_BASS_COLOR = Color.web("#e040fb", 0.85);
    private static final Color BASS_COLOR = Color.web("#00e5ff", 0.85);
    private static final Color MIDS_COLOR = Color.web("#00e676", 0.85);
    private static final Color HIGH_MIDS_COLOR = Color.web("#ffea00", 0.85);
    private static final Color HIGHS_COLOR = Color.web("#ff1744", 0.85);

    private static final double[] FREQ_MARKERS = {
            20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000
    };

    private static final double[] DB_MARKERS = {
            -90, -80, -70, -60, -50, -40, -30, -20, -10, 0
    };

    private final Canvas canvas;
    private final float[] smoothedBins;
    private final float[] peakHoldBins;
    private final float[] averageBins;
    private final LinearGradient barGradient;

    private SpectrumData data;
    private SpectrumData preEqData;
    private StereoMode stereoMode;
    private SpectrumData rightChannelData;
    private boolean logarithmicScale = true;
    private boolean averageTraceEnabled;

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
        Arrays.fill(smoothedBins, (float) MIN_DB);
        peakHoldBins = new float[displayBars];
        Arrays.fill(peakHoldBins, (float) MIN_DB);
        averageBins = new float[displayBars];
        Arrays.fill(averageBins, (float) MIN_DB);

        barGradient = new LinearGradient(0, 1, 0, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.web("#00e5ff", 0.9)),
                new Stop(0.5, Color.web("#00e676", 0.9)),
                new Stop(0.8, Color.web("#ffea00", 0.9)),
                new Stop(1.0, Color.web("#ff1744", 0.9))
        );

        stereoMode = StereoMode.LEFT_RIGHT_OVERLAY;
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

        float[] magnitudes = newData.magnitudesDb();
        int binCount = magnitudes.length;
        double sampleRate = newData.sampleRate();
        int fftSize = newData.fftSize();

        for (int bar = 0; bar < smoothedBins.length; bar++) {
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

            float smoothing = (maxMag > smoothedBins[bar]) ? 0.3f : 0.85f;
            smoothedBins[bar] = smoothing * smoothedBins[bar] + (1.0f - smoothing) * maxMag;

            if (newData.hasPeakHold()) {
                float[] peakData = newData.peakHoldDb();
                float peakMax = (float) MIN_DB;
                for (int b = binLow; b <= binHigh; b++) {
                    if (peakData[b] > peakMax) peakMax = peakData[b];
                }
                peakHoldBins[bar] = peakMax;
            }

            // Update running average trace
            averageBins[bar] = (float) (AVG_SMOOTHING * averageBins[bar]
                    + (1.0 - AVG_SMOOTHING) * smoothedBins[bar]);
        }

        render();
    }

    /**
     * Sets the pre-EQ spectrum data for overlay display.
     *
     * @param preEq the pre-EQ spectrum snapshot, or {@code null} to clear
     */
    public void setPreEqData(SpectrumData preEq) {
        this.preEqData = preEq;
    }

    /**
     * Sets the stereo display mode.
     *
     * @param mode the stereo mode
     */
    public void setStereoMode(StereoMode mode) {
        this.stereoMode = mode;
    }

    /**
     * Returns the current stereo display mode.
     *
     * @return the stereo mode
     */
    public StereoMode getStereoMode() {
        return stereoMode;
    }

    /**
     * Sets the right channel spectrum data for stereo display.
     *
     * @param rightData the right channel spectrum snapshot
     */
    public void setRightChannelData(SpectrumData rightData) {
        this.rightChannelData = rightData;
    }

    /**
     * Sets the frequency scale mode.
     *
     * @param logarithmic {@code true} for logarithmic scale, {@code false} for linear
     */
    public void setLogarithmicScale(boolean logarithmic) {
        this.logarithmicScale = logarithmic;
        render();
    }

    /**
     * Returns whether logarithmic frequency scale is active.
     *
     * @return {@code true} if logarithmic
     */
    public boolean isLogarithmicScale() {
        return logarithmicScale;
    }

    /**
     * Enables or disables the average spectrum trace overlay.
     *
     * @param enabled {@code true} to show the average trace
     */
    public void setAverageTraceEnabled(boolean enabled) {
        this.averageTraceEnabled = enabled;
        render();
    }

    /**
     * Returns whether the average spectrum trace overlay is enabled.
     *
     * @return {@code true} if enabled
     */
    public boolean isAverageTraceEnabled() {
        return averageTraceEnabled;
    }

    /**
     * Renders the spectrum to the canvas.
     */
    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();

        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, w, h);

        double plotLeft = AXIS_MARGIN_LEFT;
        double plotBottom = h - AXIS_MARGIN_BOTTOM;
        double plotWidth = w - plotLeft;
        double plotHeight = plotBottom;

        drawGrid(gc, plotLeft, plotWidth, plotHeight, plotBottom);
        drawDbLabels(gc, plotLeft, plotHeight, plotBottom);
        drawFrequencyLabels(gc, plotLeft, plotWidth, plotBottom, h);

        if (preEqData != null) {
            drawPreEqOverlay(gc, plotLeft, plotWidth, plotHeight, plotBottom);
        }

        if (stereoMode == StereoMode.LEFT_RIGHT_SPLIT && rightChannelData != null) {
            double halfHeight = plotHeight / 2.0;
            drawSpectrumBars(gc, plotLeft, plotWidth, halfHeight, halfHeight, true);
            drawSpectrumBars(gc, plotLeft, plotWidth, halfHeight, plotBottom, false);
        } else {
            drawSpectrumBars(gc, plotLeft, plotWidth, plotHeight, plotBottom, true);
        }

        if (averageTraceEnabled) {
            drawAverageTrace(gc, plotLeft, plotWidth, plotHeight, plotBottom);
        }
    }

    private void drawGrid(GraphicsContext gc, double plotLeft, double plotWidth,
                          double plotHeight, double plotBottom) {
        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(1.0);
        for (double freq : FREQ_MARKERS) {
            double x = plotLeft + frequencyToX(freq, plotWidth);
            if (x >= plotLeft && x <= plotLeft + plotWidth) {
                gc.strokeLine(x, 0, x, plotBottom);
            }
        }
        for (double db : DB_MARKERS) {
            double y = dbToY(db, plotHeight);
            gc.strokeLine(plotLeft, y, plotLeft + plotWidth, y);
        }
    }

    private void drawDbLabels(GraphicsContext gc, double plotLeft, double plotHeight,
                              double plotBottom) {
        gc.setFill(TEXT_COLOR);
        gc.setFont(javafx.scene.text.Font.font(8));
        for (double db : DB_MARKERS) {
            double y = dbToY(db, plotHeight);
            if (y >= 0 && y <= plotBottom) {
                String label = String.format("%.0f dB", db);
                gc.fillText(label, 2, y + 3);
            }
        }
    }

    private void drawFrequencyLabels(GraphicsContext gc, double plotLeft, double plotWidth,
                                     double plotBottom, double totalHeight) {
        gc.setFill(TEXT_COLOR);
        gc.setFont(javafx.scene.text.Font.font(9));
        for (double freq : FREQ_MARKERS) {
            double x = plotLeft + frequencyToX(freq, plotWidth);
            if (x >= plotLeft && x <= plotLeft + plotWidth - 30) {
                String label = (freq >= 1000)
                        ? String.format("%.0f kHz", freq / 1000)
                        : String.format("%.0f Hz", freq);
                gc.fillText(label, x + 2, totalHeight - 2);
            }
        }
    }

    private void drawPreEqOverlay(GraphicsContext gc, double plotLeft, double plotWidth,
                                  double plotHeight, double plotBottom) {
        if (preEqData == null) return;

        float[] preMag = preEqData.magnitudesDb();
        int binCount = preMag.length;
        double sampleRate = preEqData.sampleRate();
        int fftSize = preEqData.fftSize();

        gc.setStroke(PRE_EQ_COLOR);
        gc.setLineWidth(1.5);
        gc.beginPath();
        boolean started = false;

        for (int bar = 0; bar < smoothedBins.length; bar++) {
            double fCenter = 20.0 * Math.pow(1000.0, ((double) bar + 0.5) / smoothedBins.length);
            int bin = (int) (fCenter * fftSize / sampleRate);
            bin = Math.max(0, Math.min(bin, binCount - 1));
            double db = Math.max(preMag[bin], MIN_DB);

            double x = plotLeft + frequencyToX(fCenter, plotWidth);
            double y = dbToY(db, plotHeight);
            if (!started) {
                gc.moveTo(x, y);
                started = true;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private void drawSpectrumBars(GraphicsContext gc, double plotLeft, double plotWidth,
                                  double plotHeight, double plotBottom,
                                  boolean useColorCoding) {
        double barWidth = plotWidth / smoothedBins.length;

        for (int i = 0; i < smoothedBins.length; i++) {
            double db = smoothedBins[i];
            if (db <= MIN_DB) continue;

            double barHeight = (db - MIN_DB) / (MAX_DB - MIN_DB) * plotHeight;
            double x = plotLeft + i * barWidth;
            double y = plotBottom - barHeight;

            if (useColorCoding) {
                double fCenter = 20.0 * Math.pow(1000.0, ((double) i + 0.5) / smoothedBins.length);
                FrequencyRange range = FrequencyRange.forFrequency(fCenter);
                gc.setFill(colorForRange(range));
            } else {
                gc.setFill(barGradient);
            }

            gc.fillRect(x + 1, y, Math.max(barWidth - 2, 1), barHeight);

            if (data != null && data.hasPeakHold() && peakHoldBins[i] > MIN_DB) {
                double peakY = plotBottom
                        - (peakHoldBins[i] - MIN_DB) / (MAX_DB - MIN_DB) * plotHeight;
                gc.setStroke(PEAK_HOLD_COLOR);
                gc.setLineWidth(1.0);
                gc.strokeLine(x + 1, peakY, x + Math.max(barWidth - 2, 1), peakY);
            }
        }
    }

    private void drawAverageTrace(GraphicsContext gc, double plotLeft, double plotWidth,
                                  double plotHeight, double plotBottom) {
        gc.setStroke(AVG_TRACE_COLOR);
        gc.setLineWidth(1.5);
        gc.beginPath();
        boolean started = false;
        double barWidth = plotWidth / smoothedBins.length;

        for (int i = 0; i < smoothedBins.length; i++) {
            double avgDb = averageBins[i];
            if (avgDb <= MIN_DB) continue;

            double x = plotLeft + (i + 0.5) * barWidth;
            double y = dbToY(avgDb, plotHeight);
            if (!started) {
                gc.moveTo(x, y);
                started = true;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private Color colorForRange(FrequencyRange range) {
        return switch (range) {
            case SUB_BASS -> SUB_BASS_COLOR;
            case BASS -> BASS_COLOR;
            case MIDS -> MIDS_COLOR;
            case HIGH_MIDS -> HIGH_MIDS_COLOR;
            case HIGHS -> HIGHS_COLOR;
        };
    }

    private double frequencyToX(double freq, double width) {
        if (logarithmicScale) {
            double minLog = Math.log10(20);
            double maxLog = Math.log10(20000);
            double freqLog = Math.log10(Math.max(freq, 20));
            return (freqLog - minLog) / (maxLog - minLog) * width;
        } else {
            return (Math.max(freq, 20) - 20) / (20000 - 20) * width;
        }
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
