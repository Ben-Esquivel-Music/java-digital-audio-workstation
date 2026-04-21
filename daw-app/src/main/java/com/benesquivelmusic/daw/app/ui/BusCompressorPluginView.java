package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.dynamics.BusCompressorProcessor;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.Objects;

/**
 * JavaFX view for the built-in SSL-style {@link BusCompressorProcessor}.
 *
 * <p>Provides knob-style sliders for threshold, ratio, attack, release,
 * makeup gain and mix, an AUTO release toggle, a DRIVE switch for harmonic
 * coloration, and a vertical gain-reduction meter that displays the current
 * gain reduction in near-real time via a low-priority {@link AnimationTimer}.</p>
 *
 * <p>Parameter changes are written directly to the processor on the JavaFX
 * application thread; the processor picks them up on its next audio-thread
 * buffer (scalar writes are naturally thread-safe for the simple primitives
 * used by {@link BusCompressorProcessor}).</p>
 */
public final class BusCompressorPluginView extends VBox {

    /** Maximum gain-reduction shown on the meter, in dB (display range: 0 .. -MAX). */
    static final double METER_MAX_DB = 20.0;

    private final BusCompressorProcessor processor;
    private final Canvas meterCanvas;
    private final AnimationTimer meterTimer;

    /**
     * Creates a new bus-compressor view bound to the given processor.
     *
     * @param processor the processor to control; must not be {@code null}
     */
    public BusCompressorPluginView(BusCompressorProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor must not be null");

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_CENTER);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("Bus Compressor — SSL-style");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── Parameter sliders ──────────────────────────────────────────
        Slider threshold = slider(-40.0, 0.0, processor.getThresholdDb());
        threshold.valueProperty().addListener((_, _, v) -> processor.setThresholdDb(v.doubleValue()));

        ComboBox<Double> ratio = new ComboBox<>();
        for (double r : BusCompressorProcessor.RATIO_STEPS) {
            ratio.getItems().add(r);
        }
        ratio.setValue(processor.getRatio());
        ratio.valueProperty().addListener((_, _, v) -> { if (v != null) processor.setRatio(v); });

        ComboBox<Double> attack = new ComboBox<>();
        for (double a : BusCompressorProcessor.ATTACK_STEPS_MS) {
            attack.getItems().add(a);
        }
        attack.setValue(processor.getAttackMs());
        attack.valueProperty().addListener((_, _, v) -> { if (v != null) processor.setAttackMs(v); });

        ComboBox<Double> release = new ComboBox<>();
        for (double r : BusCompressorProcessor.RELEASE_STEPS_S) {
            release.getItems().add(r);
        }
        release.setValue(processor.getReleaseS());
        release.valueProperty().addListener((_, _, v) -> { if (v != null) processor.setReleaseS(v); });

        CheckBox autoRelease = new CheckBox("AUTO");
        autoRelease.setSelected(processor.isReleaseAuto());
        autoRelease.setStyle("-fx-text-fill: #ccc;");
        autoRelease.selectedProperty().addListener((_, _, v) -> processor.setReleaseAuto(v));

        Slider makeup = slider(0.0, 24.0, processor.getMakeupGainDb());
        makeup.valueProperty().addListener((_, _, v) -> processor.setMakeupGainDb(v.doubleValue()));

        Slider mix = slider(0.0, 1.0, processor.getMix());
        mix.valueProperty().addListener((_, _, v) -> processor.setMix(v.doubleValue()));

        CheckBox drive = new CheckBox("DRIVE");
        drive.setSelected(processor.isDrive());
        drive.setStyle("-fx-text-fill: #ffcc66; -fx-font-weight: bold;");
        drive.selectedProperty().addListener((_, _, v) -> processor.setDrive(v));

        HBox controls = new HBox(12,
                labelled("Threshold (dB)", threshold),
                labelled("Ratio",          ratio),
                labelled("Attack (ms)",    attack),
                labelled("Release (s)",    release),
                autoRelease,
                labelled("Makeup (dB)",    makeup),
                labelled("Mix",            mix),
                drive);
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
        s.setPrefWidth(110);
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
        g.setFill(Color.rgb(230, 140, 50));  // classic VU amber
        g.fillRect(2, 2, w - 4, barHeight);
    }
}
