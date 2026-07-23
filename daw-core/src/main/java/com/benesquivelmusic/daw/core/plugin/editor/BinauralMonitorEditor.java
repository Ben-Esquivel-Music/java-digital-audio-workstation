package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.plugin.BinauralMonitorPlugin;
import com.benesquivelmusic.daw.core.spatial.binaural.BinauralMonitoringProcessor;
import com.benesquivelmusic.daw.sdk.editor.CanvasSurface;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.RenderTick;
import com.benesquivelmusic.daw.sdk.editor.Theme;

import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Objects;

/**
 * Immersive {@link PluginEditorFactory.Canvas} editor for the built-in
 * {@link BinauralMonitorPlugin} (story 302, Plugin View Design Book §8.3
 * item 5 — the design book prescribes the immersive canvas for this
 * built-in) — a monitoring status surface: headphone glyph, live wet-level
 * bar and active/bypassed state, drawn straight into the host-owned surface.
 *
 * <p><strong>Scope notes.</strong> The retired {@code BinauralMonitorPluginView}
 * (daw-app) was pure controls — an HRTF-profile combo, a wet slider and
 * Import/Manage buttons — nothing rendered. HRTF profile selection and its
 * per-project persistence deliberately live in the host's Settings →
 * "Manage HRTF Profiles…" dialog path now; the wet-level parameter (id 0)
 * remains automatable through {@link BinauralMonitorPlugin#getParameters()}.
 * This surface renders the {@link BinauralMonitoringProcessor}'s current wet
 * level and self-schedules so automation-driven changes show live.</p>
 *
 * <p>All colours derive from the per-frame {@link Theme} tokens (§2.5); the
 * wet-percent label is re-formatted only when the rounded percentage changes,
 * so {@link #render(RenderTick)} stays allocation-free.</p>
 */
public final class BinauralMonitorEditor implements PluginEditorFactory.Canvas {

    private final BinauralMonitorPlugin plugin;
    private CanvasSurface surface;

    private final Font titleFont = Font.font("System", FontWeight.BOLD, 13);
    private final Font stateFont = Font.font("System", 12);
    private final Font labelFont = Font.font("System", 11);
    private final Font cupFont = Font.font("System", FontWeight.BOLD, 11);

    // Palette derived once per theme change so render() allocates nothing.
    private Theme cachedTokens;
    private Color glyph;
    private Color barTrack;
    private Color dimText;

    // Wet-percent label rebuilt only when the rounded value changes.
    private int cachedWetPct = -1;
    private String wetLabel = "";

    /**
     * @param plugin the plugin whose processor this editor renders; must not
     *               be {@code null}
     */
    public BinauralMonitorEditor(BinauralMonitorPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
    }

    @Override
    public void attach(CanvasSurface surface) {
        this.surface = Objects.requireNonNull(surface, "surface must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Self-scheduled continuous repaint (so wet-level changes coming from
     * automation show live): at the end of each frame the editor re-requests a
     * render only while the backing canvas is still in a scene, so a hidden or
     * torn-down editor never spins the loop — the host's scene-entry repaint
     * restarts it.</p>
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

        gc.setFont(titleFont);
        gc.setFill(tokens.foreground());
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.fillText("Binaural Monitor", 8, 6);

        drawHeadphoneGlyph(gc, w, h, tokens);
        drawStateAndWetBar(gc, w, h, tokens);

        if (surface.graphicsContext().getCanvas().getScene() != null) {
            surface.requestRender();
        }
    }

    @Override
    public void detach() {
        surface = null;
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    /** Simple geometric headphone hint: a top arc band plus two L/R ear cups. */
    private void drawHeadphoneGlyph(GraphicsContext gc, double w, double h, Theme tokens) {
        double cx = w / 2;
        double cy = h * 0.40;
        double r = Math.min(w, h) * 0.16;
        if (r <= 4) {
            return;
        }

        gc.setStroke(glyph);
        gc.setLineWidth(Math.max(2.0, r * 0.12));
        gc.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 0, 180, ArcType.OPEN);

        double cupW = r * 0.42;
        double cupH = r * 0.66;
        double cupArc = cupW * 0.8;
        gc.setFill(glyph);
        gc.fillRoundRect(cx - r - cupW / 2, cy - cupH * 0.25, cupW, cupH, cupArc, cupArc);
        gc.fillRoundRect(cx + r - cupW / 2, cy - cupH * 0.25, cupW, cupH, cupArc, cupArc);

        gc.setFont(cupFont);
        gc.setFill(tokens.background());
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("L", cx - r, cy + cupH * 0.08);
        gc.fillText("R", cx + r, cy + cupH * 0.08);
    }

    private void drawStateAndWetBar(GraphicsContext gc, double w, double h, Theme tokens) {
        BinauralMonitoringProcessor processor = plugin.getProcessor();
        boolean active = plugin.isActive();
        double cx = w / 2;

        gc.setFont(stateFont);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        double stateY = h * 0.40 + Math.min(w, h) * 0.16 + 22;
        if (processor == null) {
            gc.setFill(dimText);
            gc.fillText("Not initialised", cx, stateY);
            return;
        }
        gc.setFill(active ? tokens.accent() : dimText);
        gc.fillText(active ? "Monitoring active" : "Bypassed", cx, stateY);

        // Wet-level bar: accent fill over a foreground-dimmed track.
        double wet = Math.max(0.0, Math.min(1.0, processor.getWetLevel()));
        int wetPct = (int) Math.round(wet * 100);
        if (wetPct != cachedWetPct) {
            cachedWetPct = wetPct;
            wetLabel = wetPct + "%";
        }

        double barW = w * 0.64;
        double barH = 10;
        double barX = (w - barW) / 2;
        double barY = h * 0.74;

        gc.setFont(labelFont);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(dimText);
        gc.fillText("Wet Level", barX, barY - 4);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFill(tokens.foreground());
        gc.fillText(wetLabel, barX + barW, barY - 4);

        gc.setFill(barTrack);
        gc.fillRoundRect(barX, barY, barW, barH, barH, barH);
        gc.setFill(tokens.accent());
        gc.fillRoundRect(barX, barY, barW * wet, barH, barH, barH);
    }

    private void refreshPalette(Theme tokens) {
        if (tokens == cachedTokens) {
            return;
        }
        cachedTokens = tokens;
        Color fg = tokens.foreground();
        glyph = fg.deriveColor(0, 1.0, 1.0, 0.55);
        barTrack = fg.deriveColor(0, 1.0, 1.0, 0.18);
        dimText = fg.deriveColor(0, 1.0, 1.0, 0.60);
    }
}
