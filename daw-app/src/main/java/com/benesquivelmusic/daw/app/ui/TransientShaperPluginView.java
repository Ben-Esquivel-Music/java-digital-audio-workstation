package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.dynamics.TransientShaperProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.Objects;

/**
 * JavaFX view for the built-in {@link TransientShaperProcessor}.
 *
 * <p>Lays out two large knob-style sliders (ATTACK, SUSTAIN) flanking an
 * output trim, an input-monitor toggle, a channel-link slider, and a small
 * row of meters — input level, output level, and the transient-detection
 * envelope which lets users see when transients are being detected.</p>
 *
 * <p>Parameter changes are written directly to the processor on the JavaFX
 * application thread; the processor picks them up on its next audio-thread
 * buffer.</p>
 */
public final class TransientShaperPluginView extends VBox {

    /** Maximum displayed level on the input/output meters, in dBFS (top of bar). */
    static final double METER_MAX_DB         = 0.0;
    /** Minimum displayed level on the input/output meters, in dBFS (bottom of bar). */
    static final double METER_MIN_DB         = -60.0;
    /** Maximum displayed transient-detection magnitude, in dB. */
    static final double TRANSIENT_METER_MAX  = 12.0;

    private final TransientShaperProcessor processor;
    private final Canvas inputMeter;
    private final Canvas outputMeter;
    private final Canvas transientMeter;
    private final AnimationTimer meterTimer;

    /**
     * Creates a new transient-shaper view bound to the given processor.
     *
     * @param processor the processor to control; must not be {@code null}
     */
    public TransientShaperPluginView(TransientShaperProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor must not be null");

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_CENTER);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("Transient Shaper");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── Big knobs (ATTACK and SUSTAIN as bipolar sliders 0 detent at center) ──
        Slider attack = bigBipolarSlider(processor.getAttackPercent());
        attack.valueProperty().addListener((_, _, v) -> processor.setAttackPercent(v.doubleValue()));

        Slider sustain = bigBipolarSlider(processor.getSustainPercent());
        sustain.valueProperty().addListener((_, _, v) -> processor.setSustainPercent(v.doubleValue()));

        // ── Output trim (-12..+12 dB) ──
        Slider output = new Slider(-12.0, 12.0, processor.getOutputDb());
        output.setPrefWidth(120);
        output.setShowTickMarks(true);
        output.valueProperty().addListener((_, _, v) -> processor.setOutputDb(v.doubleValue()));

        // ── Channel link (0..1) ──
        Slider link = new Slider(0.0, 1.0, processor.getChannelLink());
        link.setPrefWidth(120);
        link.valueProperty().addListener((_, _, v) -> processor.setChannelLink(v.doubleValue()));

        // ── Input-monitor toggle ──
        CheckBox monitor = new CheckBox("MONITOR");
        monitor.setSelected(processor.isInputMonitor());
        monitor.setStyle("-fx-text-fill: #ffcc66; -fx-font-weight: bold;");
        monitor.selectedProperty().addListener((_, _, v) -> processor.setInputMonitor(v));

        VBox attackBox  = labelled("ATTACK",  attack);
        VBox sustainBox = labelled("SUSTAIN", sustain);
        attackBox.setStyle("-fx-border-color: #444; -fx-border-radius: 6; -fx-padding: 8;");
        sustainBox.setStyle("-fx-border-color: #444; -fx-border-radius: 6; -fx-padding: 8;");

        HBox controls = new HBox(16,
                attackBox,
                sustainBox,
                labelled("Output (dB)",   output),
                labelled("Channel Link",  link),
                monitor);
        controls.setAlignment(Pos.CENTER_LEFT);

        // ── Meters row ──
        inputMeter     = new Canvas(20, 180);
        outputMeter    = new Canvas(20, 180);
        transientMeter = new Canvas(20, 180);

        HBox metersHbox = new HBox(8,
                meterColumn("IN",    inputMeter),
                meterColumn("OUT",   outputMeter),
                meterColumn("TRANS", transientMeter));
        metersHbox.setAlignment(Pos.CENTER);

        HBox main = new HBox(20, controls, metersHbox);
        main.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, main);

        drawAll(PluginMeterSnapshot.SILENT);
        meterTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                drawAll(processor.getMeterSnapshot());
            }
        };
        meterTimer.start();
    }

    /** Stops the meter's animation timer. Call when the view is closed. */
    public void dispose() {
        meterTimer.stop();
    }

    // ── UI helpers ──────────────────────────────────────────────────────────

    private static Slider bigBipolarSlider(double initial) {
        Slider s = new Slider(-100.0, 100.0, initial);
        s.setPrefWidth(220);
        s.setShowTickMarks(true);
        s.setShowTickLabels(true);
        s.setMajorTickUnit(50);
        s.setMinorTickCount(4);
        s.setBlockIncrement(5);
        return s;
    }

    private static VBox labelled(String text, javafx.scene.Node node) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #ccc; -fx-font-weight: bold;");
        VBox box = new VBox(2, l, node);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static VBox meterColumn(String text, Canvas c) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #ccc; -fx-font-size: 10px;");
        VBox box = new VBox(2, c, l);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void drawAll(PluginMeterSnapshot snapshot) {
        drawLevelMeter(inputMeter,  snapshot.inputLevelDb(),  Color.rgb( 90, 180, 240));
        drawLevelMeter(outputMeter, snapshot.outputLevelDb(), Color.rgb(120, 220, 120));
        drawTransientMeter(transientMeter, snapshot.gainReductionDb());
    }

    private static void drawLevelMeter(Canvas canvas, double levelDb, Color barColor) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        double clamped = Math.max(METER_MIN_DB, Math.min(METER_MAX_DB, levelDb));
        double fraction = (clamped - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB);
        double barHeight = fraction * (h - 4);
        g.setFill(barColor);
        g.fillRect(2, h - 2 - barHeight, w - 4, barHeight);
    }

    private static void drawTransientMeter(Canvas canvas, double transientDb) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        double clamped = Math.max(0.0, Math.min(TRANSIENT_METER_MAX, transientDb));
        double fraction = clamped / TRANSIENT_METER_MAX;
        double barHeight = fraction * (h - 4);
        g.setFill(Color.rgb(230, 140, 50));  // amber, like a VU peak meter
        g.fillRect(2, h - 2 - barHeight, w - 4, barHeight);
    }
}
