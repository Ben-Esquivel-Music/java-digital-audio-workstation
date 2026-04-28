package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin.Pattern;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin.Rate;
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
 * Compact JavaFX view for the built-in {@link ArpeggiatorPlugin}.
 *
 * <p>Lays out the six parameters of the arpeggiator — rate dropdown,
 * pattern dropdown, octave-range slider, gate slider, swing slider, and a
 * latch toggle — in a single horizontal row, plus a step-sequence
 * indicator that lights up the currently-firing step. The indicator is
 * driven by an {@link AnimationTimer} that polls
 * {@link ArpeggiatorPlugin#getStepIndex()} on the JavaFX thread.</p>
 *
 * <p>All parameter changes are written directly to the plugin on the
 * JavaFX thread; the plugin reads them on its next audio-block call
 * (scalar primitive writes are safe for the simple fields used here).</p>
 */
public final class ArpeggiatorPluginView extends VBox {

    /** Number of step lights drawn in the indicator row. */
    static final int INDICATOR_STEPS = 16;

    private final ArpeggiatorPlugin plugin;
    private final Canvas indicator;
    private final AnimationTimer timer;

    /**
     * Creates a new arpeggiator view bound to the given plugin instance.
     *
     * @param plugin the plugin to control; must not be {@code null}
     */
    public ArpeggiatorPluginView(ArpeggiatorPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");

        setSpacing(8);
        setPadding(new Insets(10));
        setAlignment(Pos.TOP_CENTER);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("Arpeggiator");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── Rate dropdown ────────────────────────────────────────────────
        ComboBox<Rate> rate = new ComboBox<>();
        rate.getItems().addAll(Rate.values());
        rate.setValue(plugin.getRate());
        rate.setCellFactory(_ -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(Rate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        });
        rate.setButtonCell(rate.getCellFactory().call(null));
        rate.valueProperty().addListener((_, _, v) -> { if (v != null) plugin.setRate(v); });

        // ── Pattern dropdown ─────────────────────────────────────────────
        ComboBox<Pattern> pattern = new ComboBox<>();
        pattern.getItems().addAll(Pattern.values());
        pattern.setValue(plugin.getPattern());
        pattern.valueProperty().addListener((_, _, v) -> { if (v != null) plugin.setPattern(v); });

        // ── Octave range slider (1–4) ────────────────────────────────────
        Slider octave = slider(ArpeggiatorPlugin.MIN_OCTAVE, ArpeggiatorPlugin.MAX_OCTAVE,
                plugin.getOctaveRange());
        octave.setMajorTickUnit(1);
        octave.setMinorTickCount(0);
        octave.setSnapToTicks(true);
        octave.setShowTickLabels(true);
        octave.valueProperty().addListener((_, _, v) -> plugin.setOctaveRange(v.intValue()));

        // ── Gate slider (10–200%) ────────────────────────────────────────
        Slider gate = slider(ArpeggiatorPlugin.MIN_GATE, ArpeggiatorPlugin.MAX_GATE, plugin.getGate());
        gate.valueProperty().addListener((_, _, v) -> plugin.setGate(v.doubleValue()));

        // ── Swing slider (0–75%) ─────────────────────────────────────────
        Slider swing = slider(ArpeggiatorPlugin.MIN_SWING, ArpeggiatorPlugin.MAX_SWING, plugin.getSwing());
        swing.valueProperty().addListener((_, _, v) -> plugin.setSwing(v.doubleValue()));

        // ── Latch toggle ─────────────────────────────────────────────────
        ToggleButton latch = new ToggleButton("LATCH");
        latch.setSelected(plugin.isLatch());
        latch.setStyle("-fx-text-fill: #ffcc66; -fx-font-weight: bold;");
        latch.selectedProperty().addListener((_, _, v) -> plugin.setLatch(v));

        HBox controls = new HBox(10,
                labelled("Rate",      rate),
                labelled("Pattern",   pattern),
                labelled("Octave",    octave),
                labelled("Gate (%)",  gate),
                labelled("Swing (%)", swing),
                latch);
        controls.setAlignment(Pos.CENTER_LEFT);

        // ── Step-sequence indicator ──────────────────────────────────────
        indicator = new Canvas(INDICATOR_STEPS * 14 + 4, 14);
        Label stepLabel = new Label("Step");
        stepLabel.setStyle("-fx-text-fill: #ccc;");
        VBox stepBox = new VBox(2, stepLabel, indicator);
        stepBox.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, controls, stepBox);

        drawIndicator(-1);
        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                int s = plugin.getStepIndex();
                drawIndicator(s);
            }
        };
        timer.start();
    }

    /** Stops the indicator's animation timer. Call when the view is closed. */
    public void dispose() {
        timer.stop();
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

    private void drawIndicator(int currentStep) {
        GraphicsContext g = indicator.getGraphicsContext2D();
        double w = indicator.getWidth();
        double h = indicator.getHeight();
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        int active = currentStep < 0 ? -1 : Math.floorMod(currentStep, INDICATOR_STEPS);
        for (int i = 0; i < INDICATOR_STEPS; i++) {
            double x = 2 + i * 14;
            g.setFill(i == active ? Color.rgb(230, 140, 50) : Color.rgb(60, 60, 60));
            g.fillRect(x, 2, 10, h - 4);
        }
    }
}
