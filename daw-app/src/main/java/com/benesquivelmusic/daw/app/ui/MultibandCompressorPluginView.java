package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.dsp.MultibandCompressorProcessor;
import com.benesquivelmusic.daw.core.plugin.MultibandCompressorPlugin;
import com.benesquivelmusic.daw.sdk.visualization.MultibandCompressorData;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JavaFX view for the built-in {@link MultibandCompressorPlugin}.
 *
 * <p>Renders a frequency spectrum strip with vertical markers showing each
 * crossover boundary, a horizontal cluster of per-band knobs (threshold,
 * ratio, attack, release, makeup) with bypass/mute/solo toggles, and a
 * vertical gain-reduction meter for every band updated in near-real time
 * via a low-priority {@link AnimationTimer}.</p>
 *
 * <p>Top-level controls let the engineer change the active band count
 * (3 / 4 / 5) and toggle the linear-phase crossover preference described in
 * {@link MultibandCompressorPlugin#isLinearPhase()}.  Changing the band
 * count rebuilds the underlying processor and the view.</p>
 *
 * <p>Parameter writes happen on the JavaFX thread; the processor reads them
 * on its next audio buffer.  The animation timer only reads the volatile
 * gain-reduction snapshot exposed by {@link MultibandCompressorProcessor},
 * so no locking is required.</p>
 */
public final class MultibandCompressorPluginView extends VBox {

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

    private final MultibandCompressorPlugin plugin;
    private final Canvas spectrumCanvas;
    private final VBox bandRow;
    private final List<Canvas> bandMeters = new ArrayList<>();
    private final AnimationTimer meterTimer;

    /**
     * Creates a new multiband compressor view bound to the given plugin.
     *
     * @param plugin the plugin to control; must not be {@code null} and must
     *               already be initialized (so {@code getProcessor() != null})
     */
    public MultibandCompressorPluginView(MultibandCompressorPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        Objects.requireNonNull(plugin.getProcessor(),
                "plugin must be initialized before opening the view");

        setSpacing(10);
        setPadding(new Insets(12));
        setAlignment(Pos.TOP_CENTER);
        setStyle("-fx-background-color: #2b2b2b;");

        Label title = new Label("Multiband Compressor");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        // ── Top-level controls (band count + linear phase) ─────────────────
        ComboBox<Integer> bandCount = new ComboBox<>();
        for (int n = MultibandCompressorPlugin.MIN_BAND_COUNT;
             n <= MultibandCompressorPlugin.MAX_BAND_COUNT; n++) {
            bandCount.getItems().add(n);
        }
        bandCount.setValue(plugin.getBandCount());
        bandCount.valueProperty().addListener((_, _, v) -> {
            if (v != null && v != plugin.getBandCount()) {
                plugin.setBandCount(v);
                rebuildBandRow();
                drawSpectrum();
            }
        });

        CheckBox linearPhase = new CheckBox("Linear Phase");
        linearPhase.setStyle("-fx-text-fill: #ccc;");
        linearPhase.setSelected(plugin.isLinearPhase());
        linearPhase.selectedProperty().addListener((_, _, v) -> plugin.setLinearPhase(v));

        HBox topControls = new HBox(12,
                labelled("Bands", bandCount),
                linearPhase);
        topControls.setAlignment(Pos.CENTER_LEFT);

        // ── Spectrum strip with crossover markers ──────────────────────────
        spectrumCanvas = new Canvas(SPECTRUM_WIDTH, SPECTRUM_HEIGHT);

        // ── Per-band knob/meter cluster ───────────────────────────────────
        bandRow = new VBox(8);
        bandRow.setAlignment(Pos.CENTER_LEFT);
        rebuildBandRow();

        getChildren().addAll(title, topControls, spectrumCanvas, bandRow);
        drawSpectrum();

        meterTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                MultibandCompressorProcessor proc = plugin.getProcessor();
                if (proc == null) {
                    return;
                }
                MultibandCompressorData data = proc.getMeteringData();
                double[] gr = data.bandGainReductionDb();
                int n = Math.min(gr.length, bandMeters.size());
                for (int i = 0; i < n; i++) {
                    drawMeter(bandMeters.get(i), gr[i]);
                }
            }
        };
        meterTimer.start();
    }

    /** Stops the meter's animation timer. Call when the view is closed. */
    public void dispose() {
        meterTimer.stop();
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
        title.setStyle("-fx-text-fill: #ffcc66; -fx-font-weight: bold;");

        Slider threshold = slider(-60.0, 0.0, band.getThresholdDb());
        threshold.valueProperty().addListener((_, _, v) -> band.setThresholdDb(v.doubleValue()));

        Slider ratio = slider(1.0, 20.0, band.getRatio());
        ratio.valueProperty().addListener((_, _, v) -> band.setRatio(v.doubleValue()));

        Slider attack = slider(0.01, 100.0, band.getAttackMs());
        attack.valueProperty().addListener((_, _, v) -> band.setAttackMs(v.doubleValue()));

        Slider release = slider(10.0, 1000.0, band.getReleaseMs());
        release.valueProperty().addListener((_, _, v) -> band.setReleaseMs(v.doubleValue()));

        Slider makeup = slider(0.0, 30.0, proc.getBandMakeupGainDb(bandIndex));
        makeup.valueProperty().addListener((_, _, v) ->
                proc.setBandMakeupGainDb(bandIndex, v.doubleValue()));

        CheckBox bypass = toggle("Bypass", proc.isBandBypassed(bandIndex));
        bypass.selectedProperty().addListener((_, _, v) -> proc.setBandBypassed(bandIndex, v));

        CheckBox solo = toggle("Solo", proc.isBandSoloed(bandIndex));
        solo.selectedProperty().addListener((_, _, v) -> proc.setBandSoloed(bandIndex, v));

        // Mute is implemented as a forced-zero makeup; we drive it via a flag
        // that, when on, applies a -120 dB makeup so the band contributes
        // nothing to the sum.  This keeps the mute behavior local to the view
        // without requiring a new processor API.
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
        });

        Canvas meter = new Canvas(20, 120);
        bandMeters.add(meter);
        drawMeter(meter, 0.0);
        Label meterLabel = new Label("GR (dB)");
        meterLabel.setStyle("-fx-text-fill: #ccc;");
        VBox meterBox = new VBox(2, meterLabel, meter);
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
        panel.setStyle("-fx-border-color: #444; -fx-border-radius: 4; -fx-padding: 6;");
        panel.setAlignment(Pos.TOP_LEFT);
        return panel;
    }

    private static Slider slider(double min, double max, double initial) {
        double clamped = Math.max(min, Math.min(max, initial));
        Slider s = new Slider(min, max, clamped);
        s.setPrefWidth(140);
        s.setShowTickMarks(true);
        return s;
    }

    private static CheckBox toggle(String label, boolean initial) {
        CheckBox c = new CheckBox(label);
        c.setStyle("-fx-text-fill: #ccc;");
        c.setSelected(initial);
        return c;
    }

    private static VBox labelled(String text, javafx.scene.Node node) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #ccc;");
        VBox box = new VBox(2, l, node);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void drawSpectrum() {
        GraphicsContext g = spectrumCanvas.getGraphicsContext2D();
        double w = spectrumCanvas.getWidth();
        double h = spectrumCanvas.getHeight();
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        // Octave gridlines
        g.setStroke(Color.rgb(45, 45, 45));
        for (double f = 100; f <= MAX_FREQUENCY_HZ; f *= 10) {
            double x = freqToX(f, w);
            g.strokeLine(x, 1, x, h - 1);
        }

        // Crossover markers
        double[] xovers = plugin.getProcessor().getCrossoverFrequencies();
        g.setStroke(Color.rgb(230, 140, 50));
        g.setLineWidth(1.5);
        for (double f : xovers) {
            double x = freqToX(f, w);
            g.strokeLine(x, 1, x, h - 1);
            g.setFill(Color.rgb(230, 140, 50));
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

    private static void drawMeter(Canvas canvas, double gainReductionDb) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.setFill(Color.rgb(20, 20, 20));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.rgb(70, 70, 70));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        double gr = Math.max(-METER_MAX_DB, Math.min(0.0, gainReductionDb));
        double fraction = -gr / METER_MAX_DB;
        double barHeight = fraction * (h - 4);
        g.setFill(Color.rgb(230, 140, 50));
        g.fillRect(2, 2, w - 4, barHeight);
    }
}
