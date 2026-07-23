package com.benesquivelmusic.daw.core.plugin.editor;

import java.util.Objects;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.benesquivelmusic.daw.core.dsp.dynamics.TransientShaperProcessor;
import com.benesquivelmusic.daw.core.plugin.TransientShaperPlugin;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

/**
 * Story-302 migration (Plugin View Design Book §8.3) of the retired
 * {@code com.benesquivelmusic.daw.app.ui.TransientShaperPluginView} onto the
 * SDK editor contract, and a reference example of the
 * {@link PluginEditorFactory.Panel} contract: two large bipolar ATTACK /
 * SUSTAIN sliders (0 detent at centre, tick labels), an output trim, a
 * channel-link slider and an input-monitor toggle beside a column of three
 * vertical meters — input level, output level and the transient-detection
 * envelope — all inside one host-framed {@link Region}.
 *
 * <p>Standard controls are themed by the host stylesheet (§2.5 — no
 * hard-coded colours); the meter canvases are painted from the resolved
 * {@link Theme} tokens and re-tinted when
 * {@link EditorContext#themeProperty()} changes. A parameter gesture writes
 * the live processor (behaviour parity with the retired view) <em>and</em> is
 * mirrored into {@link EditorContext#parameterStore()} so host A/B and preset
 * capture see it (§2.6). Each meter tick also republishes the processor's
 * {@link PluginMeterSnapshot} into the store, which lights the host frame's
 * §6.4 chrome meters.</p>
 *
 * <p>Scene detachment is the only teardown hook a {@code Panel} receives —
 * the contract has no dispose callback — so the single meter
 * {@link AnimationTimer} is gated by {@link ShowingWindowGate}: started when
 * the panel sits in a scene whose window is showing, stopped when it leaves
 * the scene or the window hides (a hidden stage keeps its scene attached, so
 * scene presence alone would leave the timer spinning invisibly).</p>
 */
public final class TransientShaperEditor implements PluginEditorFactory.Panel {

    /** Maximum displayed level on the input/output meters, in dBFS (top of bar). */
    static final double METER_MAX_DB = 0.0;

    /** Minimum displayed level on the input/output meters, in dBFS (bottom of bar). */
    static final double METER_MIN_DB = -60.0;

    /** Maximum displayed transient-detection magnitude, in dB. */
    static final double TRANSIENT_METER_MAX = 12.0;

    // Parameter ids as declared by TransientShaperPlugin#getParameters().
    private static final int PARAM_ATTACK = 0;
    private static final int PARAM_SUSTAIN = 1;
    private static final int PARAM_OUTPUT = 2;
    private static final int PARAM_INPUT_MONITOR = 3;
    private static final int PARAM_CHANNEL_LINK = 4;

    private final TransientShaperPlugin plugin;

    /**
     * Creates the editor factory for the given plugin instance. The plugin
     * returns a fresh {@code TransientShaperEditor} from every
     * {@code editorFactory()} call, so one instance backs exactly one
     * {@link #createPanel(EditorContext)}.
     *
     * @param plugin the plugin whose processor this editor controls; must not
     *               be {@code null}
     */
    public TransientShaperEditor(TransientShaperPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public Region createPanel(EditorContext context) {
        Objects.requireNonNull(context, "context must not be null");
        TransientShaperProcessor processor = Objects.requireNonNull(
                plugin.getProcessor(),
                "plugin must be initialized before opening the editor");

        // ── Big bipolar sliders (ATTACK and SUSTAIN, 0 detent at centre) ──
        Slider attack = bigBipolarSlider(processor.getAttackPercent());
        attack.valueProperty().addListener((_, _, v) -> {
            processor.setAttackPercent(v.doubleValue());
            context.parameterStore().writeFromUiById(PARAM_ATTACK, v.doubleValue());
        });

        Slider sustain = bigBipolarSlider(processor.getSustainPercent());
        sustain.valueProperty().addListener((_, _, v) -> {
            processor.setSustainPercent(v.doubleValue());
            context.parameterStore().writeFromUiById(PARAM_SUSTAIN, v.doubleValue());
        });

        // ── Output trim (-12..+12 dB) ──
        Slider output = new Slider(-12.0, 12.0, processor.getOutputDb());
        output.setPrefWidth(120);
        output.setShowTickMarks(true);
        output.valueProperty().addListener((_, _, v) -> {
            processor.setOutputDb(v.doubleValue());
            context.parameterStore().writeFromUiById(PARAM_OUTPUT, v.doubleValue());
        });

        // ── Channel link (0..1) ──
        Slider link = new Slider(0.0, 1.0, processor.getChannelLink());
        link.setPrefWidth(120);
        link.valueProperty().addListener((_, _, v) -> {
            processor.setChannelLink(v.doubleValue());
            context.parameterStore().writeFromUiById(PARAM_CHANNEL_LINK, v.doubleValue());
        });

        // ── Input-monitor toggle ──
        CheckBox monitor = new CheckBox("MONITOR");
        monitor.setSelected(processor.isInputMonitor());
        monitor.setStyle("-fx-font-weight: bold;");
        monitor.selectedProperty().addListener((_, _, v) -> {
            processor.setInputMonitor(v);
            context.parameterStore().writeFromUiById(PARAM_INPUT_MONITOR, v ? 1.0 : 0.0);
        });

        HBox controls = new HBox(16,
                labelled("ATTACK",  attack),
                labelled("SUSTAIN", sustain),
                labelled("Output (dB)",   output),
                labelled("Channel Link",  link),
                monitor);
        controls.setAlignment(Pos.CENTER_LEFT);

        // ── Meters row ──
        // Implementing PluginEditorFactory.Panel inherits the sealed type's
        // nested Canvas member, which shadows an imported
        // javafx.scene.canvas.Canvas inside this class (JLS §6.4.1) — hence
        // the fully-qualified JavaFX Canvas here and in the paint helpers.
        var inputMeter     = new javafx.scene.canvas.Canvas(20, 180);
        var outputMeter    = new javafx.scene.canvas.Canvas(20, 180);
        var transientMeter = new javafx.scene.canvas.Canvas(20, 180);

        HBox metersHbox = new HBox(8,
                meterColumn("IN",    inputMeter),
                meterColumn("OUT",   outputMeter),
                meterColumn("TRANS", transientMeter));
        metersHbox.setAlignment(Pos.CENTER);

        HBox main = new HBox(20, controls, metersHbox);
        main.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, main);
        root.setPadding(new Insets(12));
        root.setAlignment(Pos.TOP_CENTER);

        drawAll(inputMeter, outputMeter, transientMeter,
                PluginMeterSnapshot.SILENT, context.theme());
        context.themeProperty().addListener((_, _, theme) ->
                drawAll(inputMeter, outputMeter, transientMeter,
                        processor.getMeterSnapshot(), theme));

        // One showing-window-gated meter timer (see class Javadoc: the Panel
        // contract has no dispose callback, so visibility is the lifecycle).
        AnimationTimer meterTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                PluginMeterSnapshot snapshot = processor.getMeterSnapshot();
                context.parameterStore().publishMeters(snapshot);
                drawAll(inputMeter, outputMeter, transientMeter, snapshot, context.theme());
            }
        };
        ShowingWindowGate.install(root, meterTimer::start, meterTimer::stop);
        return root;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sized to match the retired floating view's stage.</p>
     */
    @Override
    public EditorHints hints() {
        return EditorHints.standard().withSize(820, 300);
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

    private static VBox labelled(String text, Node node) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(2, l, node);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static VBox meterColumn(String text, javafx.scene.canvas.Canvas c) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 10px;");
        VBox box = new VBox(2, c, l);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    // ── Meter painting (Theme tokens only — §2.5) ───────────────────────────

    private static void drawAll(javafx.scene.canvas.Canvas in,
                                javafx.scene.canvas.Canvas out,
                                javafx.scene.canvas.Canvas trans,
                                PluginMeterSnapshot snapshot, Theme theme) {
        drawLevelMeter(in,  snapshot.inputLevelDb(),  theme.accent(),    theme);
        drawLevelMeter(out, snapshot.outputLevelDb(), theme.meterSafe(), theme);
        // The transient shaper reports its transient-detection envelope in the
        // snapshot's gainReductionDb slot (see TransientShaperProcessor).
        drawTransientMeter(trans, snapshot.gainReductionDb(), theme);
    }

    private static void drawLevelMeter(javafx.scene.canvas.Canvas canvas, double levelDb,
                                       Color barColor, Theme theme) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.setFill(theme.background());
        g.fillRect(0, 0, w, h);
        g.setStroke(frameStroke(theme));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        double clamped = Math.max(METER_MIN_DB, Math.min(METER_MAX_DB, levelDb));
        double fraction = (clamped - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB);
        double barHeight = fraction * (h - 4);
        g.setFill(barColor);
        g.fillRect(2, h - 2 - barHeight, w - 4, barHeight);
    }

    private static void drawTransientMeter(javafx.scene.canvas.Canvas canvas,
                                           double transientDb, Theme theme) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.setFill(theme.background());
        g.fillRect(0, 0, w, h);
        g.setStroke(frameStroke(theme));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        double clamped = Math.max(0.0, Math.min(TRANSIENT_METER_MAX, transientDb));
        double fraction = clamped / TRANSIENT_METER_MAX;
        double barHeight = fraction * (h - 4);
        g.setFill(theme.meterWarn()); // amber, like a VU peak meter
        g.fillRect(2, h - 2 - barHeight, w - 4, barHeight);
    }

    /** Meter frame stroke — the theme foreground faded, never a hex literal. */
    private static Color frameStroke(Theme theme) {
        return theme.foreground().deriveColor(0, 1.0, 1.0, 0.35);
    }
}
