package com.benesquivelmusic.daw.core.plugin.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.MultibandCompressorProcessor;
import com.benesquivelmusic.daw.core.plugin.MultibandCompressorPlugin;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import com.benesquivelmusic.daw.sdk.visualization.MultibandCompressorData;

/**
 * Story-302 migration (Plugin View Design Book §8.3) of the retired
 * {@code com.benesquivelmusic.daw.app.ui.MultibandCompressorPluginView} onto
 * the SDK editor contract, and a reference example of the
 * {@link PluginEditorFactory.Panel} contract: a band-count selector and
 * linear-phase toggle above a log-frequency spectrum strip with crossover
 * markers, and a per-band cluster of threshold / ratio / attack / release /
 * makeup sliders, bypass / mute / solo toggles and a vertical gain-reduction
 * meter. Changing the band count rebuilds the underlying processor and the
 * band row, exactly as the retired view did.
 *
 * <p>Standard controls are themed by the host stylesheet (§2.5 — no
 * hard-coded colours); the spectrum strip and per-band meters are painted
 * from the resolved {@link Theme} tokens and repainted when
 * {@link EditorContext#themeProperty()} changes. A parameter gesture writes
 * the live processor / plugin (behaviour parity with the retired view)
 * <em>and</em> is mirrored into {@link EditorContext#parameterStore()} so
 * host A/B and preset capture see it (§2.6). The per-band metering type
 * ({@link MultibandCompressorData}) is not a
 * {@code PluginMeterSnapshot}, so this editor does not publish into the
 * store's chrome-meter slot.</p>
 *
 * <p>Scene detachment is the only teardown hook a {@code Panel} receives —
 * the contract has no dispose callback — so the single per-band meter
 * {@link AnimationTimer} is gated on the panel root's {@code sceneProperty()}:
 * started when the panel enters a scene, stopped when it leaves.</p>
 */
public final class MultibandCompressorEditor implements PluginEditorFactory.Panel {

    /** Maximum gain-reduction shown on the per-band meters, in dB. */
    static final double METER_MAX_DB = 24.0;

    /** Width of the spectrum strip canvas. */
    static final double SPECTRUM_WIDTH = 720.0;

    /** Height of the spectrum strip canvas. */
    static final double SPECTRUM_HEIGHT = 80.0;

    /** Lower frequency edge of the spectrum strip, in Hz. */
    static final double MIN_FREQUENCY_HZ = 20.0;

    /** Upper frequency edge of the spectrum strip, in Hz. */
    static final double MAX_FREQUENCY_HZ = 20_000.0;

    // Parameter-id layout as declared by MultibandCompressorPlugin#getParameters():
    // 0 = Band Count, 1 = Linear Phase Toggle, 2..5 = Crossover N (Hz), then
    // per-band blocks of eight starting at id 6.
    private static final int PARAM_BAND_COUNT = 0;
    private static final int PARAM_LINEAR_PHASE = 1;
    private static final int BAND_PARAM_BASE = 6;
    private static final int BAND_PARAM_STRIDE = 8;
    private static final int OFFSET_THRESHOLD = 0;
    private static final int OFFSET_RATIO = 1;
    private static final int OFFSET_ATTACK = 2;
    private static final int OFFSET_RELEASE = 3;
    private static final int OFFSET_MAKEUP = 4;
    private static final int OFFSET_BYPASS = 5;
    private static final int OFFSET_MUTE = 6;
    private static final int OFFSET_SOLO = 7;

    private final MultibandCompressorPlugin plugin;

    /**
     * Creates the editor factory for the given plugin instance. The plugin
     * returns a fresh {@code MultibandCompressorEditor} from every
     * {@code editorFactory()} call, so one instance backs exactly one
     * {@link #createPanel(EditorContext)}.
     *
     * @param plugin the plugin this editor controls; must not be {@code null}
     */
    public MultibandCompressorEditor(MultibandCompressorPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public Region createPanel(EditorContext context) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(plugin.getProcessor(),
                "plugin must be initialized before opening the editor");
        return new Content(plugin, context);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sized to match the retired floating view's stage.</p>
     */
    @Override
    public EditorHints hints() {
        return EditorHints.standard().withSize(960, 560);
    }

    private static int bandParamId(int bandIndex, int offset) {
        return BAND_PARAM_BASE + bandIndex * BAND_PARAM_STRIDE + offset;
    }

    /**
     * The panel body — one instance per
     * {@link MultibandCompressorEditor#createPanel(EditorContext)} call.
     */
    private static final class Content extends VBox {

        private final MultibandCompressorPlugin plugin;
        private final EditorContext context;
        // The enclosing editor implements PluginEditorFactory.Panel, which
        // inherits the sealed type's nested Canvas member; that member is in
        // scope here too and shadows an imported javafx.scene.canvas.Canvas
        // (JLS §6.4.1) — hence the fully-qualified JavaFX Canvas throughout.
        private final javafx.scene.canvas.Canvas spectrumCanvas;
        private final VBox bandRow;
        private final List<javafx.scene.canvas.Canvas> bandMeters = new ArrayList<>();

        Content(MultibandCompressorPlugin plugin, EditorContext context) {
            this.plugin = plugin;
            this.context = context;

            setSpacing(10);
            setPadding(new Insets(12));
            setAlignment(Pos.TOP_CENTER);

            // ── Top-level controls (band count + linear phase) ─────────────
            ComboBox<Integer> bandCount = new ComboBox<>();
            for (int n = MultibandCompressorPlugin.MIN_BAND_COUNT;
                 n <= MultibandCompressorPlugin.MAX_BAND_COUNT; n++) {
                bandCount.getItems().add(n);
            }
            bandCount.setValue(plugin.getBandCount());
            bandCount.valueProperty().addListener((_, _, v) -> {
                if (v != null && v != plugin.getBandCount()) {
                    plugin.setBandCount(v);
                    context.parameterStore().writeFromUiById(PARAM_BAND_COUNT, v);
                    rebuildBandRow();
                    drawSpectrum();
                }
            });

            CheckBox linearPhase = new CheckBox("Linear Phase");
            linearPhase.setSelected(plugin.isLinearPhase());
            linearPhase.selectedProperty().addListener((_, _, v) -> {
                plugin.setLinearPhase(v);
                context.parameterStore().writeFromUiById(PARAM_LINEAR_PHASE, v ? 1.0 : 0.0);
            });

            HBox topControls = new HBox(12, labelled("Bands", bandCount), linearPhase);
            topControls.setAlignment(Pos.CENTER_LEFT);

            // ── Spectrum strip with crossover markers ──────────────────────
            spectrumCanvas = new javafx.scene.canvas.Canvas(SPECTRUM_WIDTH, SPECTRUM_HEIGHT);

            // ── Per-band knob/meter cluster ───────────────────────────────
            bandRow = new VBox(8);
            bandRow.setAlignment(Pos.CENTER_LEFT);
            rebuildBandRow();

            getChildren().addAll(topControls, spectrumCanvas, bandRow);
            drawSpectrum();

            context.themeProperty().addListener((_, _, _) -> {
                drawSpectrum();
                repaintBandMeters();
            });

            // One scene-gated per-band GR meter timer (see class Javadoc:
            // scene detachment is the only teardown hook the Panel contract
            // provides).
            AnimationTimer meterTimer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    repaintBandMeters();
                }
            };
            sceneProperty().addListener((_, _, scene) -> {
                if (scene != null) {
                    meterTimer.start();
                } else {
                    meterTimer.stop();
                }
            });
            if (getScene() != null) {
                meterTimer.start();
            }
        }

        private void repaintBandMeters() {
            MultibandCompressorProcessor proc = plugin.getProcessor();
            if (proc == null) {
                return;
            }
            MultibandCompressorData data = proc.getMeteringData();
            double[] gr = data.bandGainReductionDb();
            int n = Math.min(gr.length, bandMeters.size());
            for (int i = 0; i < n; i++) {
                drawMeter(bandMeters.get(i), gr[i], context.theme());
            }
        }

        private void rebuildBandRow() {
            bandRow.getChildren().clear();
            bandMeters.clear();

            MultibandCompressorProcessor proc = plugin.getProcessor();
            HBox bands = new HBox(10);
            bands.setAlignment(Pos.TOP_LEFT);
            for (int i = 0; i < proc.getBandCount(); i++) {
                bands.getChildren().add(buildBandPanel(i));
            }
            bandRow.getChildren().add(bands);
        }

        private VBox buildBandPanel(int bandIndex) {
            MultibandCompressorProcessor proc = plugin.getProcessor();
            CompressorProcessor band = proc.getBandCompressor(bandIndex);

            Label title = new Label("Band " + (bandIndex + 1));
            title.setStyle("-fx-font-weight: bold;");

            Slider threshold = slider(-60.0, 0.0, band.getThresholdDb());
            threshold.valueProperty().addListener((_, _, v) -> {
                band.setThresholdDb(v.doubleValue());
                context.parameterStore().writeFromUiById(
                        bandParamId(bandIndex, OFFSET_THRESHOLD), v.doubleValue());
            });

            Slider ratio = slider(1.0, 20.0, band.getRatio());
            ratio.valueProperty().addListener((_, _, v) -> {
                band.setRatio(v.doubleValue());
                context.parameterStore().writeFromUiById(
                        bandParamId(bandIndex, OFFSET_RATIO), v.doubleValue());
            });

            Slider attack = slider(0.01, 100.0, band.getAttackMs());
            attack.valueProperty().addListener((_, _, v) -> {
                band.setAttackMs(v.doubleValue());
                context.parameterStore().writeFromUiById(
                        bandParamId(bandIndex, OFFSET_ATTACK), v.doubleValue());
            });

            Slider release = slider(10.0, 1000.0, band.getReleaseMs());
            release.valueProperty().addListener((_, _, v) -> {
                band.setReleaseMs(v.doubleValue());
                context.parameterStore().writeFromUiById(
                        bandParamId(bandIndex, OFFSET_RELEASE), v.doubleValue());
            });

            Slider makeup = slider(0.0, 30.0, proc.getBandMakeupGainDb(bandIndex));
            makeup.valueProperty().addListener((_, _, v) -> {
                proc.setBandMakeupGainDb(bandIndex, v.doubleValue());
                context.parameterStore().writeFromUiById(
                        bandParamId(bandIndex, OFFSET_MAKEUP), v.doubleValue());
            });

            CheckBox bypass = toggle("Bypass", proc.isBandBypassed(bandIndex));
            bypass.selectedProperty().addListener((_, _, v) -> {
                proc.setBandBypassed(bandIndex, v);
                context.parameterStore().writeFromUiById(
                        bandParamId(bandIndex, OFFSET_BYPASS), v ? 1.0 : 0.0);
            });

            CheckBox solo = toggle("Solo", proc.isBandSoloed(bandIndex));
            solo.selectedProperty().addListener((_, _, v) -> {
                proc.setBandSoloed(bandIndex, v);
                context.parameterStore().writeFromUiById(
                        bandParamId(bandIndex, OFFSET_SOLO), v ? 1.0 : 0.0);
            });

            // Mute is implemented as a forced-zero makeup (behaviour preserved
            // from the retired view): when muting, the current makeup value is
            // saved and a -120 dB makeup is applied so the band contributes
            // nothing to the sum, and the makeup slider is disabled; unmuting
            // restores the saved value and re-enables the slider. This keeps
            // mute local to the editor without requiring a new processor API.
            // The forced -120 dB is deliberately NOT mirrored into the store's
            // Makeup parameter (declared range 0..30 dB would clamp it); the
            // store carries the Mute Toggle fact instead.
            CheckBox mute = toggle("Mute", false);
            final double[] savedMakeup = { proc.getBandMakeupGainDb(bandIndex) };
            mute.selectedProperty().addListener((_, _, v) -> {
                if (v) {
                    savedMakeup[0] = makeup.getValue();
                    proc.setBandMakeupGainDb(bandIndex, -120.0);
                    makeup.setDisable(true);
                } else {
                    proc.setBandMakeupGainDb(bandIndex, savedMakeup[0]);
                    makeup.setDisable(false);
                }
                context.parameterStore().writeFromUiById(
                        bandParamId(bandIndex, OFFSET_MUTE), v ? 1.0 : 0.0);
            });

            var meter = new javafx.scene.canvas.Canvas(20, 120);
            bandMeters.add(meter);
            drawMeter(meter, 0.0, context.theme());
            VBox meterBox = new VBox(2, new Label("GR (dB)"), meter);
            meterBox.setAlignment(Pos.CENTER);

            VBox knobs = new VBox(4,
                    labelled("Threshold (dB)", threshold),
                    labelled("Ratio",          ratio),
                    labelled("Attack (ms)",    attack),
                    labelled("Release (ms)",   release),
                    labelled("Makeup (dB)",    makeup),
                    bypass, mute, solo);

            HBox content = new HBox(6, knobs, meterBox);
            content.setAlignment(Pos.TOP_LEFT);

            VBox panel = new VBox(4, title, content);
            panel.setPadding(new Insets(6));
            panel.setAlignment(Pos.TOP_LEFT);
            return panel;
        }

        // ── UI helpers ──────────────────────────────────────────────────────

        private static Slider slider(double min, double max, double initial) {
            double clamped = Math.max(min, Math.min(max, initial));
            Slider s = new Slider(min, max, clamped);
            s.setPrefWidth(140);
            s.setShowTickMarks(true);
            return s;
        }

        private static CheckBox toggle(String label, boolean initial) {
            CheckBox c = new CheckBox(label);
            c.setSelected(initial);
            return c;
        }

        private static VBox labelled(String text, Node node) {
            VBox box = new VBox(2, new Label(text), node);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        }

        // ── Painting (Theme tokens only — §2.5) ─────────────────────────────

        private void drawSpectrum() {
            MultibandCompressorProcessor proc = plugin.getProcessor();
            if (proc == null) {
                return;
            }
            Theme theme = context.theme();
            GraphicsContext g = spectrumCanvas.getGraphicsContext2D();
            double w = spectrumCanvas.getWidth();
            double h = spectrumCanvas.getHeight();
            g.setFill(theme.background());
            g.fillRect(0, 0, w, h);
            g.setStroke(frameStroke(theme));
            g.strokeRect(0.5, 0.5, w - 1, h - 1);

            // Decade gridlines (100 Hz, 1 kHz, 10 kHz)
            g.setStroke(gridStroke(theme));
            for (double f = 100; f <= MAX_FREQUENCY_HZ; f *= 10) {
                double x = freqToX(f, w);
                g.strokeLine(x, 1, x, h - 1);
            }

            // Crossover markers
            double[] xovers = proc.getCrossoverFrequencies();
            g.setStroke(theme.meterWarn());
            g.setLineWidth(1.5);
            for (double f : xovers) {
                double x = freqToX(f, w);
                g.strokeLine(x, 1, x, h - 1);
                g.setFill(theme.meterWarn());
                g.fillText(formatFreq(f), Math.min(x + 4, w - 50), 14);
            }
            g.setLineWidth(1.0);
        }

        private static double freqToX(double freq, double width) {
            double clamped = Math.max(MIN_FREQUENCY_HZ, Math.min(MAX_FREQUENCY_HZ, freq));
            double logMin = Math.log10(MIN_FREQUENCY_HZ);
            double logMax = Math.log10(MAX_FREQUENCY_HZ);
            return (Math.log10(clamped) - logMin) / (logMax - logMin) * width;
        }

        private static String formatFreq(double f) {
            if (f >= 1000) {
                return String.format("%.1f kHz", f / 1000.0);
            }
            return String.format("%.0f Hz", f);
        }

        private static void drawMeter(javafx.scene.canvas.Canvas canvas,
                                      double gainReductionDb, Theme theme) {
            GraphicsContext g = canvas.getGraphicsContext2D();
            double w = canvas.getWidth();
            double h = canvas.getHeight();
            g.setFill(theme.background());
            g.fillRect(0, 0, w, h);
            g.setStroke(frameStroke(theme));
            g.strokeRect(0.5, 0.5, w - 1, h - 1);

            double gr = Math.max(-METER_MAX_DB, Math.min(0.0, gainReductionDb));
            double fraction = -gr / METER_MAX_DB;
            double barHeight = fraction * (h - 4);
            g.setFill(theme.meterWarn());
            g.fillRect(2, 2, w - 4, barHeight);
        }

        /** Frame stroke — the theme foreground faded, never a hex literal. */
        private static Color frameStroke(Theme theme) {
            return theme.foreground().deriveColor(0, 1.0, 1.0, 0.35);
        }

        /** Decade gridline stroke — fainter than the frame, from the same token. */
        private static Color gridStroke(Theme theme) {
            return theme.foreground().deriveColor(0, 1.0, 1.0, 0.15);
        }
    }
}
