package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.icons.IconNode;
import com.benesquivelmusic.daw.fx.GpuCanvas;
import com.benesquivelmusic.daw.fx.GpuRenderContext;
import com.benesquivelmusic.daw.sdk.visualization.CorrelationData;
import com.benesquivelmusic.daw.sdk.visualization.GoniometerData;

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
 *   <li>Horizontal moving correlation bar (-1 to +1) with color coding</li>
 *   <li>Phase indicator (in-phase / caution / inverted)</li>
 *   <li>Mid/side level balance</li>
 *   <li>Stereo balance indicator</li>
 *   <li>Phase correlation history</li>
 *   <li>Goniometer (Lissajous XY) visualization</li>
 * </ul>
 *
 * <p>The correlation bar is color-coded:
 * <ul>
 *   <li>Green — correlation &gt; 0.5 (good mono compatibility)</li>
 *   <li>Yellow — correlation 0.0 to 0.5 (caution)</li>
 *   <li>Red — correlation &lt; 0.0 (phase issues)</li>
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
    private static final Color PHASE_OK_COLOR = Color.web("#00e676");
    private static final Color PHASE_CAUTION_COLOR = Color.web("#ffea00");
    private static final Color PHASE_INVERTED_COLOR = Color.web("#ff1744");

    private static final int HISTORY_SIZE = 120;

    private static final double OVERLAY_ICON_SIZE = 9;

    /**
     * Phosphor-decay time constant. Chosen so that at a 60 Hz frame interval
     * (deltaSeconds ≈ 1/60) one frame's alpha-fade fill renders the previous
     * frame's pixels at ≈ 50% intensity: {@code (1/60) / ln 2 ≈ 0.024 s}.
     * The per-frame fade alpha is {@code 1 - exp(-deltaSeconds / TAU)}.
     */
    static final double PHOSPHOR_DECAY_TAU_SECONDS = (1.0 / 60.0) / Math.log(2.0);

    /**
     * GpuCanvas host. The renderer field intentionally manages its own
     * alpha-fade fill instead of relying on {@link GpuCanvas#setClearColor}
     * because the goniometer phosphor trail depends on previous-frame
     * pixels — see story 028 (Stereo Correlation Meter and Goniometer).
     */
    private final GpuCanvas gpuCanvas;
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
    private boolean disposed;

    /**
     * Creates a new correlation display.
     */
    public CorrelationDisplay() {
        // Compose a GpuCanvas (daw-fx) — see PHOSPHOR_DECAY_TAU_SECONDS for
        // why clearColor is left null (the renderer issues its own
        // alpha-fade fill so the goniometer phosphor trail is preserved).
        gpuCanvas = GpuCanvas.create()
                .renderer(this::renderFrame)
                .animated(true)
                .build();
        getChildren().add(gpuCanvas);

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

        requestRender();
    }

    /**
     * Updates the display with new goniometer data.
     *
     * @param data the latest goniometer data for Lissajous display
     */
    public void updateGoniometer(GoniometerData data) {
        this.goniometerData = data;
        requestRender();
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
        requestRender();
    }

    /** Returns whether goniometer mode is active. */
    public boolean isGoniometerMode() {
        return goniometerMode;
    }

    /**
     * Returns the embedded {@link GpuCanvas}. Visible for tests.
     */
    GpuCanvas getGpuCanvas() {
        return gpuCanvas;
    }

    /**
     * Stops the GpuCanvas render loop and releases its off-heap surface.
     * Must be called from the JavaFX Application Thread. Safe to call
     * multiple times.
     */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        gpuCanvas.setAnimated(false);
        gpuCanvas.dispose();
    }

    private void requestRender() {
        if (disposed) return;
        gpuCanvas.requestRender();
    }

    /**
     * Computes the per-frame phosphor-fade alpha from the host's
     * {@code deltaSeconds}. Exponential decay with time constant
     * {@link #PHOSPHOR_DECAY_TAU_SECONDS} — at dt = 1/60 s the previous
     * frame's pixels are attenuated by ≈ 50 %.
     *
     * <p>For one-shot renders ({@code deltaSeconds == 0}) the fill is
     * fully opaque so the surface is cleanly cleared.
     */
    static double phosphorFadeAlpha(double deltaSeconds) {
        if (deltaSeconds <= 0.0) return 1.0;
        return 1.0 - Math.exp(-deltaSeconds / PHOSPHOR_DECAY_TAU_SECONDS);
    }

    /**
     * Per-frame draw callback invoked by the GpuCanvas AnimationTimer.
     * Issues an alpha-fade fill (clearColor is left {@code null} so the
     * previous frame's pixels remain on the surface) before drawing the
     * correlation arc, balance bar, history strip, and — when enabled —
     * the goniometer Lissajous trail.
     */
    private void renderFrame(GpuRenderContext ctx) {
        GraphicsContext gc = ctx.gc();
        double w = ctx.width();
        double h = ctx.height();
        if (w <= 0 || h <= 0) return;

        // Phosphor alpha-fade fill: see PHOSPHOR_DECAY_TAU_SECONDS.
        double fadeAlpha = phosphorFadeAlpha(ctx.deltaSeconds());
        gc.setFill(BACKGROUND.deriveColor(0, 1, 1, fadeAlpha));
        gc.fillRect(0, 0, w, h);

        renderInto(gc, w, h);
    }

    private void renderInto(GraphicsContext gc, double w, double h) {
        if (goniometerMode && goniometerData != null && goniometerData.pointCount() > 0) {
            renderGoniometer(gc, w, h);
        }

        double centerX = w / 2;
        double topSection = h * 0.45;
        double meterY = topSection + 80;
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

        // --- Horizontal correlation bar (-1 to +1) ---
        double corrBarWidth = w - 40;
        double corrBarHeight = 6;
        double corrBarX = 20;
        double corrBarY = arcCenterY + 14;

        // Bar background
        gc.setFill(Color.web("#ffffff", 0.08));
        gc.fillRect(corrBarX, corrBarY, corrBarWidth, corrBarHeight);

        // Center line marker
        gc.setFill(Color.web("#ffffff", 0.2));
        gc.fillRect(corrBarX + corrBarWidth / 2 - 0.5, corrBarY - 1, 1, corrBarHeight + 2);

        // Filled region from center to current correlation
        Color corrBarColor = getCorrelationColor(correlation);
        double corrCenter = corrBarX + corrBarWidth / 2;
        double corrPos = corrBarX + (correlation + 1.0) / 2.0 * corrBarWidth;
        double fillX = Math.min(corrCenter, corrPos);
        double fillW = Math.abs(corrPos - corrCenter);
        gc.setFill(corrBarColor.deriveColor(0, 1, 1, 0.8));
        gc.fillRect(fillX, corrBarY, fillW, corrBarHeight);

        // Moving indicator
        gc.setFill(corrBarColor);
        gc.fillRect(corrPos - 2, corrBarY - 2, 4, corrBarHeight + 4);

        // Scale labels for correlation bar
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font(8));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("-1", corrBarX, corrBarY + corrBarHeight + 10);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("0", corrBarX + corrBarWidth / 2, corrBarY + corrBarHeight + 10);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("+1", corrBarX + corrBarWidth, corrBarY + corrBarHeight + 10);

        // --- Phase indicator ---
        double phaseY = corrBarY + corrBarHeight + 16;
        String phaseText;
        Color phaseColor;
        if (correlation > 0.5) {
            phaseText = "\u25CF In Phase";
            phaseColor = PHASE_OK_COLOR;
        } else if (correlation >= 0.0) {
            phaseText = "\u25CF Caution";
            phaseColor = PHASE_CAUTION_COLOR;
        } else {
            phaseText = "\u25CF Phase Inverted";
            phaseColor = PHASE_INVERTED_COLOR;
        }
        gc.setFill(phaseColor);
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(phaseText, centerX, phaseY);

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

        // GpuCanvas is itself a Region — resize it to fill the display.
        // Its own size listeners drive the per-frame redraw.
        gpuCanvas.resizeRelocate(0, 0, w, h);

        // Position icon overlay labels at the same coordinates used by the old fillText() calls.
        double topSection = h * 0.45;
        double meterY     = topSection + 80;

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
    }

    /** Moves a non-managed label to the given (x, y) top-left position. */
    private static void placeLabel(Label label, double x, double y) {
        double pw = label.prefWidth(-1);
        double ph = label.prefHeight(-1);
        label.resizeRelocate(x, y, pw, ph);
    }
}
