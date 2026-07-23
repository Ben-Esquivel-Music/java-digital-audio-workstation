package com.benesquivelmusic.daw.core.plugin.editor;

import java.util.Objects;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.benesquivelmusic.daw.core.dsp.dynamics.TruePeakLimiterProcessor;
import com.benesquivelmusic.daw.core.plugin.TruePeakLimiterPlugin;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

/**
 * Story-302 migration (Plugin View Design Book §8.3) of the retired
 * {@code com.benesquivelmusic.daw.app.ui.TruePeakLimiterPluginView} onto the
 * SDK editor contract, and a reference example of the
 * {@link PluginEditorFactory.Panel} contract: ceiling / release / lookahead /
 * channel-link sliders, an oversampling (ISR) selector and a bypass toggle
 * beside a vertical gain-reduction meter and horizontal true-peak input /
 * output meters, all inside one host-framed {@link Region}.
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
public final class TruePeakLimiterEditor implements PluginEditorFactory.Panel {

    /** Maximum gain-reduction shown on the meter, in dB. */
    static final double GR_METER_MAX_DB = 20.0;

    /** dBFS range shown on the input/output peak meters (top = 0 dBTP). */
    static final double PEAK_METER_FLOOR_DB = -40.0;

    // Parameter ids as declared by TruePeakLimiterPlugin#getParameters().
    private static final int PARAM_CEILING = 0;
    private static final int PARAM_RELEASE = 1;
    private static final int PARAM_LOOKAHEAD = 2;
    private static final int PARAM_ISR = 3;
    private static final int PARAM_CHANNEL_LINK = 4;

    private final TruePeakLimiterPlugin plugin;

    /**
     * Creates the editor factory for the given plugin instance. The plugin
     * returns a fresh {@code TruePeakLimiterEditor} from every
     * {@code editorFactory()} call, so one instance backs exactly one
     * {@link #createPanel(EditorContext)}.
     *
     * @param plugin the plugin whose processor this editor controls; must not
     *               be {@code null}
     */
    public TruePeakLimiterEditor(TruePeakLimiterPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public Region createPanel(EditorContext context) {
        Objects.requireNonNull(context, "context must not be null");
        TruePeakLimiterProcessor processor = Objects.requireNonNull(
                plugin.getProcessor(),
                "plugin must be initialized before opening the editor");

        // ── Ceiling (-3 to 0 dBTP) ─────────────────────────────────────
        Slider ceiling = slider(-3.0, 0.0, processor.getCeilingDb());
        ceiling.valueProperty().addListener((_, _, v) -> {
            processor.setCeilingDb(v.doubleValue());
            context.parameterStore().writeFromUiById(PARAM_CEILING, v.doubleValue());
        });

        // ── Release (1–1000 ms, log-friendly tick spacing) ────────────
        Slider release = slider(1.0, 1000.0, processor.getReleaseMs());
        release.valueProperty().addListener((_, _, v) -> {
            processor.setReleaseMs(v.doubleValue());
            context.parameterStore().writeFromUiById(PARAM_RELEASE, v.doubleValue());
        });

        // ── Lookahead (1–10 ms; reports PDC) ──────────────────────────
        Slider lookahead = slider(1.0, 10.0, processor.getLookaheadMs());
        lookahead.valueProperty().addListener((_, _, v) -> {
            processor.setLookaheadMs(v.doubleValue());
            context.parameterStore().writeFromUiById(PARAM_LOOKAHEAD, v.doubleValue());
        });

        // ── ISR (oversampling factor selector) ────────────────────────
        ComboBox<Integer> isr = new ComboBox<>();
        for (int step : TruePeakLimiterProcessor.OVERSAMPLE_STEPS) {
            isr.getItems().add(step);
        }
        isr.setValue(processor.getIsr());
        isr.valueProperty().addListener((_, _, v) -> {
            if (v != null) {
                processor.setIsr(v);
                context.parameterStore().writeFromUiById(PARAM_ISR, v);
            }
        });

        // ── Channel link (0–100%) ─────────────────────────────────────
        Slider link = slider(0.0, 100.0, processor.getChannelLinkPercent());
        link.valueProperty().addListener((_, _, v) -> {
            processor.setChannelLinkPercent(v.doubleValue());
            context.parameterStore().writeFromUiById(PARAM_CHANNEL_LINK, v.doubleValue());
        });

        // ── Bypass (instant null-comparison; no declared parameter, so
        //    the processor accessor alone is the write path) ────────────
        ToggleButton bypass = new ToggleButton("Bypass");
        bypass.setSelected(processor.isBypass());
        bypass.setStyle("-fx-font-weight: bold;");
        bypass.selectedProperty().addListener((_, _, v) -> processor.setBypass(v));

        HBox controls = new HBox(12,
                labelled("Ceiling (dBTP)", ceiling),
                labelled("Release (ms)",   release),
                labelled("Lookahead (ms)", lookahead),
                labelled("ISR",            isr),
                labelled("Link (%)",       link),
                bypass);
        controls.setAlignment(Pos.CENTER_LEFT);

        // ── Meters: GR (vertical) + IN/OUT true-peak (horizontal) ─────
        // Implementing PluginEditorFactory.Panel inherits the sealed type's
        // nested Canvas member, which shadows an imported
        // javafx.scene.canvas.Canvas inside this class (JLS §6.4.1) — hence
        // the fully-qualified JavaFX Canvas here and in the paint helpers.
        var grCanvas  = new javafx.scene.canvas.Canvas(40, 180);
        var inCanvas  = new javafx.scene.canvas.Canvas(180, 14);
        var outCanvas = new javafx.scene.canvas.Canvas(180, 14);

        VBox grBox = new VBox(4, new Label("GR (dB)"), grCanvas);
        grBox.setAlignment(Pos.CENTER);

        VBox peakBox = new VBox(6,
                new Label("IN (dBTP)"), inCanvas,
                new Label("OUT (dBTP)"), outCanvas);
        peakBox.setAlignment(Pos.CENTER_LEFT);

        HBox main = new HBox(20, controls, grBox, peakBox);
        main.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, main);
        root.setPadding(new Insets(12));
        root.setAlignment(Pos.TOP_CENTER);

        drawMeters(grCanvas, inCanvas, outCanvas, PluginMeterSnapshot.SILENT, context.theme());
        context.themeProperty().addListener((_, _, theme) ->
                drawMeters(grCanvas, inCanvas, outCanvas, processor.getMeterSnapshot(), theme));

        // One showing-window-gated meter timer (see class Javadoc: the Panel
        // contract has no dispose callback, so visibility is the lifecycle).
        AnimationTimer meterTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                PluginMeterSnapshot snapshot = processor.getMeterSnapshot();
                context.parameterStore().publishMeters(snapshot);
                drawMeters(grCanvas, inCanvas, outCanvas, snapshot, context.theme());
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

    private static Slider slider(double min, double max, double initial) {
        Slider s = new Slider(min, max, initial);
        s.setPrefWidth(120);
        s.setShowTickMarks(true);
        return s;
    }

    private static VBox labelled(String text, Node node) {
        VBox box = new VBox(2, new Label(text), node);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ── Meter painting (Theme tokens only — §2.5) ───────────────────────────

    private static void drawMeters(javafx.scene.canvas.Canvas gr,
                                   javafx.scene.canvas.Canvas in,
                                   javafx.scene.canvas.Canvas out,
                                   PluginMeterSnapshot snapshot, Theme theme) {
        drawGr(gr, snapshot.gainReductionDb(), theme);
        drawPeak(in,  snapshot.inputLevelDb(),  theme.accent(),    theme);
        drawPeak(out, snapshot.outputLevelDb(), theme.meterSafe(), theme);
    }

    private static void drawGr(javafx.scene.canvas.Canvas canvas,
                               double gainReductionDb, Theme theme) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.setFill(theme.background());
        g.fillRect(0, 0, w, h);
        g.setStroke(frameStroke(theme));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        double gr = Math.max(-GR_METER_MAX_DB, Math.min(0.0, gainReductionDb));
        double fraction = -gr / GR_METER_MAX_DB;
        double barHeight = fraction * (h - 4);
        g.setFill(theme.meterWarn());
        g.fillRect(2, 2, w - 4, barHeight);
    }

    private static void drawPeak(javafx.scene.canvas.Canvas canvas,
                                 double levelDb, Color tint, Theme theme) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.setFill(theme.background());
        g.fillRect(0, 0, w, h);
        g.setStroke(frameStroke(theme));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        if (levelDb == Double.NEGATIVE_INFINITY) {
            return;
        }
        double clamped = Math.max(PEAK_METER_FLOOR_DB, Math.min(0.0, levelDb));
        double fraction = (clamped - PEAK_METER_FLOOR_DB) / -PEAK_METER_FLOOR_DB;
        double barWidth = fraction * (w - 4);
        // Clip colour above the AES recommended -1 dBTP ceiling.
        g.setFill(levelDb > -1.0 ? theme.meterClip() : tint);
        g.fillRect(2, 2, barWidth, h - 4);
    }

    /** Meter frame stroke — the theme foreground faded, never a hex literal. */
    private static Color frameStroke(Theme theme) {
        return theme.foreground().deriveColor(0, 1.0, 1.0, 0.35);
    }
}
