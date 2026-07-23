package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.dsp.eq.MatchEqProcessor;
import com.benesquivelmusic.daw.core.plugin.MatchEqPlugin;
import com.benesquivelmusic.daw.sdk.editor.CanvasSurface;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.RenderTick;
import com.benesquivelmusic.daw.sdk.editor.Theme;

import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Objects;

/**
 * Immersive {@link PluginEditorFactory.Canvas} editor for the built-in
 * {@link MatchEqPlugin} (story 302, Plugin View Design Book §8.3 item 5) —
 * the source spectrum, reference spectrum and target match curve overlaid on
 * a log-frequency / ±24 dB plot, drawn straight into the host-owned surface.
 *
 * <p><strong>Scope notes.</strong> This canvas replaces the plugin's previous
 * implicit {@code Declarative} default as its <em>visual</em> surface; the
 * four {@link MatchEqPlugin#getParameters()} entries remain the declarative
 * data automation binds to. The retired {@code MatchEqPluginView} (daw-app)
 * was production-orphaned — never instantiated outside its own tests — so its
 * {@code FileChooser} / live-capture / apply buttons do not migrate; reference
 * loading ({@link MatchEqPlugin#loadReferenceFile}) and the capture/apply
 * processor API remain host- and automation-driven. The view's plot math is
 * ported here as package-private statics ({@link #freqToX(double, double)},
 * {@link #dbToY(double, double)}, {@code drawPlot}/{@code strokeCurve}/
 * {@code strokeTargetCurve}) so it stays headlessly testable
 * ({@code MatchEqEditorPlotMathTest}).</p>
 *
 * <p>All colours derive from the per-frame {@link Theme} tokens (§2.5):
 * source spectrum in dimmed foreground, reference spectrum in the meter-safe
 * token, target curve in the accent (thicker). The spectra accessors on
 * {@link MatchEqProcessor} clone their arrays, so the editor refreshes its
 * snapshot at a {@value #SPECTRA_REFRESH_SECONDS}-second cadence instead of
 * allocating every frame.</p>
 */
public final class MatchEqEditor implements PluginEditorFactory.Canvas {

    /** Plot Y axis range in dB (centered at 0). The curve is clipped to ±DB_RANGE. */
    static final double DB_RANGE = 24.0;

    /** Lower frequency edge of the plot, in Hz. */
    static final double MIN_FREQUENCY_HZ = 20.0;

    /** Upper frequency edge of the plot, in Hz. */
    static final double MAX_FREQUENCY_HZ = 20_000.0;

    /** How often the (cloning) processor spectra accessors are re-read, in seconds. */
    static final double SPECTRA_REFRESH_SECONDS = 0.25;

    private static final double[] DECADE_LINES = {100.0, 1_000.0, 10_000.0};

    /**
     * The token-derived colours {@link #drawPlot} paints with — rebuilt only
     * when the theme changes so the render loop stays allocation-free.
     */
    record PlotPalette(Color background, Color frame, Color grid, Color zeroLine,
                       Color source, Color reference, Color target) {}

    private final MatchEqPlugin plugin;
    private CanvasSurface surface;

    private final Font titleFont = Font.font("System", FontWeight.BOLD, 13);
    private final Font legendFont = Font.font("System", 11);
    private final Font statusFont = Font.font("System", 11);

    // Palette derived once per theme change.
    private Theme cachedTokens;
    private PlotPalette palette;
    private Color titleText;
    private Color statusText;

    // Spectra snapshot, refreshed on a countdown (accessors clone).
    private double refreshCountdown;
    private double[] sourceSpectrum;
    private double[] referenceSpectrum;
    private double[] targetCurve;

    // Status line rebuilt only when its facts change.
    private boolean cachedNoProcessor;
    private boolean cachedMatchActive;
    private int cachedAmountPct = -1;
    private String statusLine;

    /**
     * @param plugin the plugin whose processor this editor renders; must not
     *               be {@code null}
     */
    public MatchEqEditor(MatchEqPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public void attach(CanvasSurface surface) {
        this.surface = Objects.requireNonNull(surface, "surface must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Self-scheduled continuous repaint (the spectra update from audio-side
     * analysis with no change notification): at the end of each frame the
     * editor re-requests a render only while the backing canvas is still in a
     * scene, so a hidden or torn-down editor never spins the loop — the host's
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
        refreshPalette(tick.tokens());

        MatchEqProcessor processor = plugin.getProcessor();
        refreshCountdown -= tick.deltaSeconds();
        if (refreshCountdown <= 0) {
            refreshCountdown = SPECTRA_REFRESH_SECONDS;
            sourceSpectrum = processor == null ? null : processor.getSourceSpectrum();
            referenceSpectrum = processor == null ? null : processor.getReferenceSpectrum();
            targetCurve = processor == null ? null : processor.getTargetCurve();
        }

        int fftSize = processor == null ? 0 : processor.getFftSize().value();
        double sampleRate = processor == null ? 0.0 : processor.getSampleRate();
        drawPlot(gc, w, h, sourceSpectrum, referenceSpectrum, targetCurve,
                fftSize, sampleRate, palette);

        drawTitleAndLegend(gc, w);
        drawStatus(gc, h, processor);

        if (surface.graphicsContext().getCanvas().getScene() != null) {
            surface.requestRender();
        }
    }

    @Override
    public void detach() {
        surface = null;
    }

    // ── Chrome-free text overlays ──────────────────────────────────────────

    private void drawTitleAndLegend(GraphicsContext gc, double w) {
        gc.setFont(titleFont);
        gc.setFill(titleText);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.fillText("Match EQ", 8, 6);

        gc.setFont(legendFont);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFill(palette.source());
        gc.fillText("Source", w - 8, 6);
        gc.setFill(palette.reference());
        gc.fillText("Reference", w - 8, 20);
        gc.setFill(palette.target());
        gc.fillText("Target", w - 8, 34);
    }

    private void drawStatus(GraphicsContext gc, double h, MatchEqProcessor processor) {
        boolean noProcessor = processor == null;
        boolean matchActive = !noProcessor && processor.isMatchActive();
        int amountPct = noProcessor ? 0 : (int) Math.round(processor.getAmount() * 100);
        if (statusLine == null || noProcessor != cachedNoProcessor
                || matchActive != cachedMatchActive || amountPct != cachedAmountPct) {
            cachedNoProcessor = noProcessor;
            cachedMatchActive = matchActive;
            cachedAmountPct = amountPct;
            if (noProcessor) {
                statusLine = "Not initialised";
            } else if (matchActive) {
                statusLine = "Match active · Amount " + amountPct + "%";
            } else {
                statusLine = "No match — capture source and reference spectra, then apply";
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
        palette = new PlotPalette(
                tokens.background(),
                fg.deriveColor(0, 1.0, 1.0, 0.28),
                fg.deriveColor(0, 1.0, 1.0, 0.12),
                fg.deriveColor(0, 1.0, 1.0, 0.30),
                fg.deriveColor(0, 1.0, 1.0, 0.55),
                tokens.meterSafe(),
                tokens.accent());
        titleText = fg;
        statusText = fg.deriveColor(0, 1.0, 1.0, 0.70);
    }

    // ── Plot drawing (ported from the retired MatchEqPluginView) ──────────

    static void drawPlot(GraphicsContext g, double w, double h,
                         double[] source, double[] reference, double[] target,
                         int fftSize, double sampleRate, PlotPalette palette) {
        // Background
        g.setFill(palette.background());
        g.fillRect(0, 0, w, h);
        g.setLineWidth(1.0);
        g.setStroke(palette.frame());
        g.strokeRect(0.5, 0.5, w - 1, h - 1);

        // Horizontal grid: 0 dB centre + ±6 / ±12 / ±18 dB.
        g.setStroke(palette.grid());
        for (int db = -18; db <= 18; db += 6) {
            double y = dbToY(db, h);
            g.strokeLine(0, y, w, y);
        }
        g.setStroke(palette.zeroLine());
        double zeroY = dbToY(0, h);
        g.strokeLine(0, zeroY, w, zeroY);

        // Vertical grid: decade lines (100 Hz, 1 kHz, 10 kHz).
        for (double freq : DECADE_LINES) {
            double x = freqToX(freq, w);
            g.strokeLine(x, 0, x, h);
        }

        // Curves.
        double nyquist = sampleRate * 0.5;
        int half = fftSize / 2 + 1;
        if (source != null && source.length == half) {
            strokeCurve(g, w, h, source, sampleRate, nyquist, palette.source());
        }
        if (reference != null && reference.length == half) {
            strokeCurve(g, w, h, reference, sampleRate, nyquist, palette.reference());
        }
        if (target != null && target.length == half) {
            strokeTargetCurve(g, w, h, target, sampleRate, nyquist, palette.target());
        }
    }

    static void strokeCurve(GraphicsContext g, double w, double h,
                            double[] mag, double sampleRate, double nyquist,
                            Color color) {
        g.setStroke(color);
        g.setLineWidth(1.2);
        int half = mag.length;
        double maxMag = 0.0;
        for (double v : mag) {
            if (v > maxMag) {
                maxMag = v;
            }
        }
        if (maxMag <= 0.0) {
            return;
        }
        g.beginPath();
        boolean first = true;
        for (int k = 1; k < half; k++) {
            double freq = (double) k * sampleRate / ((half - 1) * 2.0);
            if (freq < MIN_FREQUENCY_HZ || freq > nyquist) {
                continue;
            }
            double db = 20.0 * Math.log10(Math.max(1e-9, mag[k] / maxMag));
            double x = freqToX(freq, w);
            double y = dbToY(db, h);
            if (first) {
                g.moveTo(x, y);
                first = false;
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();
    }

    static void strokeTargetCurve(GraphicsContext g, double w, double h,
                                  double[] target, double sampleRate,
                                  double nyquist, Color color) {
        g.setStroke(color);
        g.setLineWidth(1.8);
        int half = target.length;
        g.beginPath();
        boolean first = true;
        for (int k = 1; k < half; k++) {
            double freq = (double) k * sampleRate / ((half - 1) * 2.0);
            if (freq < MIN_FREQUENCY_HZ || freq > nyquist) {
                continue;
            }
            double db = 20.0 * Math.log10(Math.max(1e-9, target[k]));
            double x = freqToX(freq, w);
            double y = dbToY(db, h);
            if (first) {
                g.moveTo(x, y);
                first = false;
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();
    }

    // ── Plot mapping (pure math — exercised by MatchEqEditorPlotMathTest) ──

    /** Maps a frequency in Hz to the plot's X pixel (log scale). */
    static double freqToX(double freqHz, double width) {
        double logMin = Math.log10(MIN_FREQUENCY_HZ);
        double logMax = Math.log10(MAX_FREQUENCY_HZ);
        double f = Math.max(MIN_FREQUENCY_HZ, Math.min(MAX_FREQUENCY_HZ, freqHz));
        double t = (Math.log10(f) - logMin) / (logMax - logMin);
        return t * width;
    }

    /** Maps a dB value to the plot's Y pixel (0 dB at vertical centre). */
    static double dbToY(double db, double height) {
        double clamped = Math.max(-DB_RANGE, Math.min(DB_RANGE, db));
        double t = (clamped + DB_RANGE) / (2.0 * DB_RANGE);    // 0..1 bottom..top
        return height - t * height;
    }
}
