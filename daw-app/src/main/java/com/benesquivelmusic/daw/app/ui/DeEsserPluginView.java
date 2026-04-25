package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.dynamics.DeEsserProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.Objects;

/**
 * JavaFX view for the built-in {@link DeEsserProcessor}.
 *
 * <p>Provides a frequency sweep slider (2–12&nbsp;kHz), a Q control, a
 * threshold slider, a range slider that limits the maximum applied
 * attenuation, a mode selector ({@code WIDEBAND} / {@code SPLIT_BAND}), a
 * <em>Listen</em> toggle that solos the detection band so the user can tune
 * frequency and Q before setting threshold, and a vertical gain-reduction
 * meter driven by a low-priority {@link AnimationTimer}.</p>
 *
 * <p>Parameter changes are written directly to the processor on the JavaFX
 * application thread; the processor picks them up on its next audio-thread
 * buffer (scalar writes are safe for the simple primitives used by
 * {@link DeEsserProcessor}).</p>
 */
public final class DeEsserPluginView extends VBox {

    /** Maximum gain-reduction shown on the meter, in dB (display range: 0 .. -MAX). */
    static final double METER_MAX_DB = 20.0;

    private final DeEsserProcessor processor;
    private final Canvas meterCanvas;
    private final AnimationTimer meterTimer;

    /**
     * Creates a new de-esser view bound to the given processor.
     *
     * @param processor the processor to control; must not be {@code null}
     */
    public DeEsserPluginView(DeEsserProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor must not be null");

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_CENTER);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("De-Esser — Split-Band");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── Frequency sweep slider (2–12 kHz) ──────────────────────────
        Slider frequency = slider(2000.0, 12000.0, processor.getFrequencyHz());
        frequency.valueProperty().addListener(
                (_, _, v) -> processor.setFrequencyHz(v.doubleValue()));

        // ── Q ─────────────────────────────────────────────────────────
        Slider q = slider(0.5, 4.0, processor.getQ());
        q.valueProperty().addListener((_, _, v) -> processor.setQ(v.doubleValue()));

        // ── Threshold ─────────────────────────────────────────────────
        Slider threshold = slider(-60.0, 0.0, processor.getThresholdDb());
        threshold.valueProperty().addListener(
                (_, _, v) -> processor.setThresholdDb(v.doubleValue()));

        // ── Range (max attenuation) ──────────────────────────────────
        Slider range = slider(0.0, 20.0, processor.getRangeDb());
        range.valueProperty().addListener(
                (_, _, v) -> processor.setRangeDb(v.doubleValue()));

        // ── Mode toggle ──────────────────────────────────────────────
        ComboBox<DeEsserProcessor.Mode> mode = new ComboBox<>();
        mode.getItems().addAll(DeEsserProcessor.Mode.values());
        mode.setValue(processor.getMode());
        mode.valueProperty().addListener((_, _, v) -> { if (v != null) processor.setMode(v); });

        // ── Listen button ────────────────────────────────────────────
        ToggleButton listen = new ToggleButton("LISTEN");
        listen.setSelected(processor.isListen());
        listen.setStyle("-fx-text-fill: #ffcc66; -fx-font-weight: bold;");
        listen.selectedProperty().addListener((_, _, v) -> processor.setListen(v));

        HBox controls = new HBox(12,
                labelled("Frequency (Hz)", frequency),
                labelled("Q",              q),
                labelled("Threshold (dB)", threshold),
                labelled("Range (dB)",     range),
                labelled("Mode",           mode),
                listen);
        controls.setAlignment(Pos.CENTER_LEFT);

        // ── Gain-reduction meter ──────────────────────────────────────
        meterCanvas = new Canvas(40, 180);
        Label meterLabel = new Label("GR (dB)");
        meterLabel.setStyle("-fx-text-fill: #ccc;");
        VBox meterBox = new VBox(4, meterLabel, meterCanvas);
        meterBox.setAlignment(Pos.CENTER);

        HBox main = new HBox(20, controls, meterBox);
        main.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, main);

        drawMeter(PluginMeterSnapshot.SILENT);
        meterTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                drawMeter(processor.getMeterSnapshot());
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

    private void drawMeter(PluginMeterSnapshot snapshot) {
        GraphicsContext g = meterCanvas.getGraphicsContext2D();
        double w = meterCanvas.getWidth();
        double h = meterCanvas.getHeight();
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        double gr = Math.max(-METER_MAX_DB, Math.min(0.0, snapshot.gainReductionDb()));
        double fraction = -gr / METER_MAX_DB;  // 0..1 (top-down fill)
        double barHeight = fraction * (h - 4);
        g.setFill(Color.rgb(230, 140, 50));    // amber, mirrors BusCompressorPluginView
        g.fillRect(2, 2, w - 4, barHeight);
    }
}
