package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.dynamics.TruePeakLimiterProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.Objects;

/**
 * JavaFX view for the built-in {@link TruePeakLimiterProcessor}.
 *
 * <p>Provides ceiling and release sliders, a lookahead slider, an
 * oversampling-factor selector ({@code 2× / 4× / 8×}), a stereo channel-link
 * percentage, and side-by-side metering: a vertical gain-reduction meter plus
 * horizontal true-peak input and output meters that show inter-sample peaks.
 * An <em>A/B</em> toggle bypasses the limiter for instant null-comparison.</p>
 *
 * <p>Parameter changes are written directly to the processor on the JavaFX
 * application thread; the processor picks them up on its next audio-thread
 * buffer (scalar writes are naturally thread-safe for the simple primitives
 * used by {@link TruePeakLimiterProcessor}).</p>
 */
public final class TruePeakLimiterPluginView extends VBox {

    /** Maximum gain-reduction shown on the meter, in dB. */
    static final double GR_METER_MAX_DB = 20.0;

    /** dBFS range shown on the input/output peak meters (top = 0 dBTP). */
    static final double PEAK_METER_FLOOR_DB = -40.0;

    private final TruePeakLimiterProcessor processor;
    private final Canvas grCanvas;
    private final Canvas inCanvas;
    private final Canvas outCanvas;
    private final AnimationTimer meterTimer;

    /**
     * Creates a new true-peak limiter view bound to the given processor.
     *
     * @param processor the processor to control; must not be {@code null}
     */
    public TruePeakLimiterPluginView(TruePeakLimiterProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor must not be null");

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_CENTER);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("True-Peak Limiter — Mastering");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── Ceiling (-3 to 0 dBTP) ─────────────────────────────────────
        Slider ceiling = slider(-3.0, 0.0, processor.getCeilingDb());
        ceiling.valueProperty().addListener(
                (_, _, v) -> processor.setCeilingDb(v.doubleValue()));

        // ── Release (1–1000 ms, log-friendly tick spacing) ────────────
        Slider release = slider(1.0, 1000.0, processor.getReleaseMs());
        release.valueProperty().addListener(
                (_, _, v) -> processor.setReleaseMs(v.doubleValue()));

        // ── Lookahead (1–10 ms; reports PDC) ──────────────────────────
        Slider lookahead = slider(1.0, 10.0, processor.getLookaheadMs());
        lookahead.valueProperty().addListener(
                (_, _, v) -> processor.setLookaheadMs(v.doubleValue()));

        // ── ISR (oversampling factor selector) ────────────────────────
        ComboBox<Integer> isr = new ComboBox<>();
        for (int v : TruePeakLimiterProcessor.OVERSAMPLE_STEPS) isr.getItems().add(v);
        isr.setValue(processor.getIsr());
        isr.valueProperty().addListener((_, _, v) -> { if (v != null) processor.setIsr(v); });

        // ── Channel link (0–100%) ─────────────────────────────────────
        Slider link = slider(0.0, 100.0, processor.getChannelLinkPercent());
        link.valueProperty().addListener(
                (_, _, v) -> processor.setChannelLinkPercent(v.doubleValue()));

        // ── A/B compare (bypass) ──────────────────────────────────────
        ToggleButton ab = new ToggleButton("A/B");
        ab.setSelected(processor.isBypass());
        ab.setStyle("-fx-text-fill: #ffcc66; -fx-font-weight: bold;");
        ab.selectedProperty().addListener((_, _, v) -> processor.setBypass(v));

        HBox controls = new HBox(12,
                labelled("Ceiling (dBTP)", ceiling),
                labelled("Release (ms)",   release),
                labelled("Lookahead (ms)", lookahead),
                labelled("ISR",            isr),
                labelled("Link (%)",       link),
                ab);
        controls.setAlignment(Pos.CENTER_LEFT);

        // ── Meters: GR (vertical) + IN/OUT true-peak (horizontal) ─────
        grCanvas  = new Canvas(40, 180);
        inCanvas  = new Canvas(180, 14);
        outCanvas = new Canvas(180, 14);

        Label grLabel  = new Label("GR (dB)");
        grLabel.setStyle("-fx-text-fill: #ccc;");
        VBox grBox = new VBox(4, grLabel, grCanvas);
        grBox.setAlignment(Pos.CENTER);

        Label inLabel  = new Label("IN (dBTP)");
        Label outLabel = new Label("OUT (dBTP)");
        inLabel.setStyle("-fx-text-fill: #ccc;");
        outLabel.setStyle("-fx-text-fill: #ccc;");
        VBox peakBox = new VBox(6, inLabel, inCanvas, outLabel, outCanvas);
        peakBox.setAlignment(Pos.CENTER_LEFT);

        HBox main = new HBox(20, controls, grBox, peakBox);
        main.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, main);

        drawMeters(PluginMeterSnapshot.SILENT);
        meterTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                drawMeters(processor.getMeterSnapshot());
            }
        };
        meterTimer.start();
    }

    /** Stops the meter's animation timer. Call when the view is closed. */
    public void dispose() {
        meterTimer.stop();
    }

    private static Slider slider(double min, double max, double initial) {
        Slider s = new Slider(min, max, initial);
        s.setPrefWidth(120);
        s.setShowTickMarks(true);
        return s;
    }

    private static VBox labelled(String text, javafx.scene.Node node) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #ccc;");
        VBox box = new VBox(2, l, node);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void drawMeters(PluginMeterSnapshot snapshot) {
        drawGr(snapshot);
        drawPeak(inCanvas,  snapshot.inputLevelDb(),  Color.rgb(80, 180, 230));
        drawPeak(outCanvas, snapshot.outputLevelDb(), Color.rgb(140, 220, 100));
    }

    private void drawGr(PluginMeterSnapshot snapshot) {
        GraphicsContext g = grCanvas.getGraphicsContext2D();
        double w = grCanvas.getWidth();
        double h = grCanvas.getHeight();
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        double gr = Math.max(-GR_METER_MAX_DB, Math.min(0.0, snapshot.gainReductionDb()));
        double fraction = -gr / GR_METER_MAX_DB;
        double barHeight = fraction * (h - 4);
        g.setFill(Color.rgb(230, 140, 50));
        g.fillRect(2, 2, w - 4, barHeight);
    }

    private static void drawPeak(Canvas c, double levelDb, Color tint) {
        GraphicsContext g = c.getGraphicsContext2D();
        double w = c.getWidth();
        double h = c.getHeight();
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        if (levelDb == Double.NEGATIVE_INFINITY) return;
        double clamped = Math.max(PEAK_METER_FLOOR_DB, Math.min(0.0, levelDb));
        double fraction = (clamped - PEAK_METER_FLOOR_DB)
                / (-PEAK_METER_FLOOR_DB);
        double barWidth = fraction * (w - 4);
        // Red zone above the AES recommended -1 dBTP ceiling.
        Color colour = (levelDb > -1.0) ? Color.rgb(220, 70, 70) : tint;
        g.setFill(colour);
        g.fillRect(2, 2, barWidth, h - 4);
    }
}
