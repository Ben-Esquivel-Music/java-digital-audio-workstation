package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.analysis.SpectrumAnalyzer;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.sdk.editor.CanvasSurface;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.RenderTick;
import com.benesquivelmusic.daw.sdk.editor.Theme;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;

import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Objects;

/**
 * Immersive {@link PluginEditorFactory.Canvas} editor for the built-in
 * {@link SpectrumAnalyzerPlugin} (story 302, Plugin View Design Book §8.3
 * item 5) — a log-frequency spectrum plot drawn straight into the host-owned
 * surface, replacing the legacy {@code SpectrumDisplayWindow} route.
 *
 * <p><strong>Scope notes.</strong> The old window's FFT-size / window-type
 * toolbar is intentionally not part of the immersive surface (§5.D — the
 * canvas owns every pixel); {@link SpectrumAnalyzerPlugin#reconfigure}
 * remains available to automation and future stories. The docked spectrum
 * panel ({@code PANEL_SPECTRUM}, the app-side {@code SpectrumDisplay} fed by
 * the app's metering pipeline) is a separate surface of the app — this editor
 * renders <em>this plugin's own</em> {@link SpectrumAnalyzer}, so no renderer
 * is duplicated across the module boundary. No production code currently
 * feeds that analyzer audio: until a future story wires live audio the
 * surface honestly shows the grid with the trace at the floor.</p>

 * <p>All colours derive from the per-frame {@link Theme} tokens (§2.5) — the
 * derived grid/trace tints are re-computed only when the theme instance
 * changes, so {@link #render(RenderTick)} stays allocation-free.</p>
 */
public final class SpectrumAnalyzerEditor implements PluginEditorFactory.Canvas {

    /** Lower frequency edge of the plot, in Hz. */
    static final double MIN_FREQUENCY_HZ = 20.0;

    /** Upper frequency edge of the plot, in Hz. */
    static final double MAX_FREQUENCY_HZ = 20_000.0;

    /** Plot floor in dBFS (bins at or below this hug the bottom edge). */
    static final double MIN_DB = -90.0;

    /** Plot ceiling in dBFS (top edge). */
    static final double MAX_DB = 0.0;

    private static final double[] MINOR_FREQ_LINES = {50, 200, 500, 2_000, 5_000};
    private static final double[] MAJOR_FREQ_LINES = {100, 1_000, 10_000};

    private final SpectrumAnalyzerPlugin plugin;
    private CanvasSurface surface;

    private final Font titleFont = Font.font("System", FontWeight.BOLD, 13);
    private final Font statusFont = Font.font("System", 11);

    // Palette derived once per theme change so render() allocates nothing.
    private Theme cachedTokens;
    private Color gridMinor;
    private Color gridMajor;
    private Color peakTrace;
    private Color statusText;

    // Status line rebuilt only when the analyzer instance or active flag flips.
    private SpectrumAnalyzer cachedAnalyzer;
    private boolean cachedActive;
    private String statusLine;

    /**
     * @param plugin the plugin whose analyzer this editor renders; must not be
     *               {@code null}
     */
    public SpectrumAnalyzerEditor(SpectrumAnalyzerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public void attach(CanvasSurface surface) {
        this.surface = Objects.requireNonNull(surface, "surface must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Self-scheduled continuous repaint: at the end of each frame the editor
     * re-requests a render only while the backing canvas is still in a scene,
     * so a hidden or torn-down editor never spins the loop — the host's
     * scene-entry repaint restarts it.</p>
     */
    @Override
    public void render(RenderTick tick) {
        if (surface == null) {
            return;
        }
        double w = surface.width();
        double h = surface.height();
        GraphicsContext gc = surface.graphicsContext();
        Theme tokens = tick.tokens();
        refreshPalette(tokens);

        gc.setFill(tokens.background());
        gc.fillRect(0, 0, w, h);

        drawGrid(gc, w, h);
        drawSpectrum(gc, w, h, tokens);
        drawTitle(gc, tokens);
        drawStatus(gc, h);

        if (surface.graphicsContext().getCanvas().getScene() != null) {
            surface.requestRender();
        }
    }

    @Override
    public void detach() {
        surface = null;
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    private void drawGrid(GraphicsContext gc, double w, double h) {
        gc.setLineWidth(1.0);

        gc.setStroke(gridMinor);
        for (double f : MINOR_FREQ_LINES) {
            double x = freqToX(f, w);
            gc.strokeLine(x, 0, x, h);
        }
        for (int db = -80; db <= -10; db += 10) {
            double y = dbToY(db, h);
            gc.strokeLine(0, y, w, y);
        }

        gc.setStroke(gridMajor);
        for (double f : MAJOR_FREQ_LINES) {
            double x = freqToX(f, w);
            gc.strokeLine(x, 0, x, h);
        }
    }

    private void drawSpectrum(GraphicsContext gc, double w, double h, Theme tokens) {
        SpectrumAnalyzer analyzer = plugin.getAnalyzer();
        SpectrumData data = analyzer == null ? null : analyzer.getLatestData();
        if (data == null) {
            return;
        }
        strokeTrace(gc, w, h, data, data.magnitudesDb(), tokens.accent(), 1.5);
        if (data.hasPeakHold()) {
            strokeTrace(gc, w, h, data, data.peakHoldDb(), peakTrace, 1.0);
        }
    }

    private static void strokeTrace(GraphicsContext gc, double w, double h,
                                    SpectrumData data, float[] magnitudesDb,
                                    Color color, double lineWidth) {
        gc.setStroke(color);
        gc.setLineWidth(lineWidth);
        gc.beginPath();
        boolean first = true;
        int bins = data.binCount();
        for (int k = 1; k < bins; k++) {
            double freq = data.frequencyOfBin(k);
            if (freq < MIN_FREQUENCY_HZ || freq > MAX_FREQUENCY_HZ) {
                continue;
            }
            double x = freqToX(freq, w);
            double y = dbToY(magnitudesDb[k], h);
            if (first) {
                gc.moveTo(x, y);
                first = false;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private void drawTitle(GraphicsContext gc, Theme tokens) {
        gc.setFont(titleFont);
        gc.setFill(tokens.foreground());
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.fillText("Spectrum Analyzer", 8, 6);
    }

    private void drawStatus(GraphicsContext gc, double h) {
        SpectrumAnalyzer analyzer = plugin.getAnalyzer();
        boolean active = plugin.isActive();
        if (statusLine == null || analyzer != cachedAnalyzer || active != cachedActive) {
            cachedAnalyzer = analyzer;
            cachedActive = active;
            if (analyzer == null) {
                statusLine = "Analyzer not initialised";
            } else {
                statusLine = analyzer.getFftSize() + "-pt " + analyzer.getWindowType()
                        + " · " + Math.round(analyzer.getSampleRate()) + " Hz"
                        + (active ? "" : " · inactive");
            }
        }
        gc.setFont(statusFont);
        gc.setFill(statusText);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.fillText(statusLine, 8, h - 6);
    }

    private void refreshPalette(Theme tokens) {
        if (Objects.equals(tokens, cachedTokens)) {
            return;
        }
        cachedTokens = tokens;
        Color fg = tokens.foreground();
        gridMinor = fg.deriveColor(0, 1.0, 1.0, 0.10);
        gridMajor = fg.deriveColor(0, 1.0, 1.0, 0.22);
        peakTrace = fg.deriveColor(0, 1.0, 1.0, 0.60);
        statusText = fg.deriveColor(0, 1.0, 1.0, 0.70);
    }

    // ── Plot mapping ───────────────────────────────────────────────────────

    /** Maps a frequency in Hz to the plot's X pixel (log scale, 20 Hz–20 kHz). */
    static double freqToX(double freqHz, double width) {
        double logMin = Math.log10(MIN_FREQUENCY_HZ);
        double logMax = Math.log10(MAX_FREQUENCY_HZ);
        double f = Math.max(MIN_FREQUENCY_HZ, Math.min(MAX_FREQUENCY_HZ, freqHz));
        double t = (Math.log10(f) - logMin) / (logMax - logMin);
        return t * width;
    }

    /** Maps a dBFS value to the plot's Y pixel (0 dBFS at the top edge). */
    static double dbToY(double db, double height) {
        double clamped = Math.max(MIN_DB, Math.min(MAX_DB, db));
        return (MAX_DB - clamped) / (MAX_DB - MIN_DB) * height;
    }
}
