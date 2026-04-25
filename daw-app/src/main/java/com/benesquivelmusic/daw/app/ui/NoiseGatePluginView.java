package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.dynamics.NoiseGateProcessor;
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
import javafx.scene.shape.Circle;

import java.util.Objects;

/**
 * JavaFX view for the built-in {@link NoiseGateProcessor}.
 *
 * <p>Lays out the canonical noise-gate controls — threshold, hysteresis,
 * attack, hold, release, range, lookahead — plus the sidechain section
 * (enable toggle, bandpass frequency &amp; Q) and a vertical level meter
 * with a horizontal threshold line. A small status LED to the right of
 * the meter lights green while the gate is open, amber while attacking
 * or holding, and dark while closed.</p>
 *
 * <p>Parameter changes are written directly to the processor on the JavaFX
 * application thread; the processor picks them up on its next audio-thread
 * buffer (scalar writes are safe for the simple primitives used by
 * {@link NoiseGateProcessor}).</p>
 */
public final class NoiseGatePluginView extends VBox {

    /** Maximum displayed level on the input meter, in dBFS (top of bar). */
    static final double METER_MAX_DB = 0.0;
    /** Minimum displayed level on the input meter, in dBFS (bottom of bar). */
    static final double METER_MIN_DB = -60.0;

    private final NoiseGateProcessor processor;
    private final Canvas levelMeter;
    private final Circle gateLed;
    private final Label gateStateLabel;
    private final Slider thresholdSlider;
    private final AnimationTimer meterTimer;

    /**
     * Creates a new noise-gate view bound to the given processor.
     *
     * @param processor the processor to control; must not be {@code null}
     */
    public NoiseGatePluginView(NoiseGateProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor must not be null");

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_CENTER);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("Noise Gate");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── Core gate controls ────────────────────────────────────────
        thresholdSlider = slider(-80.0, 0.0, processor.getThresholdDb());
        thresholdSlider.valueProperty().addListener(
                (_, _, v) -> processor.setThresholdDb(v.doubleValue()));

        Slider hysteresis = slider(0.0, 24.0, processor.getHysteresisDb());
        hysteresis.valueProperty().addListener(
                (_, _, v) -> processor.setHysteresisDb(v.doubleValue()));

        Slider attack = slider(0.01, 50.0, processor.getAttackMs());
        attack.valueProperty().addListener(
                (_, _, v) -> processor.setAttackMs(v.doubleValue()));

        Slider hold = slider(0.0, 500.0, processor.getHoldMs());
        hold.valueProperty().addListener(
                (_, _, v) -> processor.setHoldMs(v.doubleValue()));

        Slider release = slider(1.0, 500.0, processor.getReleaseMs());
        release.valueProperty().addListener(
                (_, _, v) -> processor.setReleaseMs(v.doubleValue()));

        Slider range = slider(-80.0, 0.0, processor.getRangeDb());
        range.valueProperty().addListener(
                (_, _, v) -> processor.setRangeDb(v.doubleValue()));

        Slider lookahead = slider(0.0, 10.0, processor.getLookaheadMs());
        lookahead.valueProperty().addListener(
                (_, _, v) -> processor.setLookaheadMs(v.doubleValue()));

        HBox controls = new HBox(12,
                labelled("Threshold (dB)",  thresholdSlider),
                labelled("Hysteresis (dB)", hysteresis),
                labelled("Attack (ms)",     attack),
                labelled("Hold (ms)",       hold),
                labelled("Release (ms)",    release),
                labelled("Range (dB)",      range),
                labelled("Lookahead (ms)",  lookahead));
        controls.setAlignment(Pos.CENTER_LEFT);

        // ── Sidechain section ─────────────────────────────────────────
        CheckBox sidechainEnabled = new CheckBox("Sidechain");
        sidechainEnabled.setStyle("-fx-text-fill: #ccc;");
        sidechainEnabled.setSelected(processor.isSidechainEnabled());
        sidechainEnabled.selectedProperty().addListener(
                (_, _, v) -> processor.setSidechainEnabled(v));

        CheckBox sidechainFilter = new CheckBox("Filter");
        sidechainFilter.setStyle("-fx-text-fill: #ccc;");
        sidechainFilter.setSelected(processor.isSidechainFilterEnabled());
        sidechainFilter.selectedProperty().addListener(
                (_, _, v) -> processor.setSidechainFilterEnabled(v));

        Slider scFreq = slider(20.0, 2000.0, processor.getSidechainFilterFreqHz());
        scFreq.valueProperty().addListener(
                (_, _, v) -> processor.setSidechainFilterFreqHz(v.doubleValue()));

        Slider scQ = slider(0.1, 10.0, processor.getSidechainFilterQ());
        scQ.valueProperty().addListener(
                (_, _, v) -> processor.setSidechainFilterQ(v.doubleValue()));

        HBox sidechain = new HBox(12,
                sidechainEnabled,
                sidechainFilter,
                labelled("SC Freq (Hz)", scFreq),
                labelled("SC Q",         scQ));
        sidechain.setAlignment(Pos.CENTER_LEFT);

        // ── Level meter + gate-state LED ──────────────────────────────
        levelMeter = new Canvas(40, 180);
        Label meterLabel = new Label("In (dBFS)");
        meterLabel.setStyle("-fx-text-fill: #ccc;");
        VBox meterBox = new VBox(4, meterLabel, levelMeter);
        meterBox.setAlignment(Pos.CENTER);

        gateLed = new Circle(8, Color.rgb(50, 50, 50));
        gateLed.setStroke(Color.rgb(110, 110, 110));
        gateStateLabel = new Label("CLOSED");
        gateStateLabel.setStyle("-fx-text-fill: #ccc; -fx-font-family: monospace;");
        VBox ledBox = new VBox(6, new Label("Gate") {{
            setStyle("-fx-text-fill: #ccc;");
        }}, gateLed, gateStateLabel);
        ledBox.setAlignment(Pos.CENTER);

        HBox main = new HBox(20, controls, meterBox, ledBox);
        main.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, main, sidechain);

        drawMeter(NoiseGateProcessor.MeterSnapshot.SILENT);
        meterTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                NoiseGateProcessor.MeterSnapshot snap = processor.getMeterSnapshot();
                drawMeter(snap);
                updateGateLed(snap);
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

    private void drawMeter(NoiseGateProcessor.MeterSnapshot snapshot) {
        GraphicsContext g = levelMeter.getGraphicsContext2D();
        double w = levelMeter.getWidth();
        double h = levelMeter.getHeight();
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        // Level bar (top = 0 dBFS, bottom = METER_MIN_DB).
        double level = Math.max(METER_MIN_DB,
                Math.min(METER_MAX_DB, snapshot.inputLevelDb()));
        double levelFraction = (level - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB);
        double barHeight = levelFraction * (h - 4);
        g.setFill(snapshot.isOpen()
                ? Color.rgb(80, 200, 110)     // green when open
                : Color.rgb(180, 180, 70));   // amber when closed/transitioning
        g.fillRect(2, h - 2 - barHeight, w - 4, barHeight);

        // Threshold line (horizontal red marker).
        double thresholdDb = Math.max(METER_MIN_DB,
                Math.min(METER_MAX_DB, processor.getThresholdDb()));
        double thresholdFraction =
                (thresholdDb - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB);
        double y = h - 2 - thresholdFraction * (h - 4);
        g.setStroke(Color.rgb(230, 80, 80));
        g.setLineWidth(1.5);
        g.strokeLine(1, y, w - 1, y);
    }

    private void updateGateLed(NoiseGateProcessor.MeterSnapshot snapshot) {
        Color color = switch (snapshot.state()) {
            case OPEN              -> Color.rgb(80, 220, 110);  // green
            case ATTACK, HOLD      -> Color.rgb(240, 200, 60);  // amber
            case RELEASE, CLOSED   -> Color.rgb(60, 60, 60);    // dim
        };
        gateLed.setFill(color);
        gateStateLabel.setText(snapshot.state().name());
    }
}
