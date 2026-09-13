package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.plugin.SoundWaveTelemetryPlugin;
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
 * {@link SoundWaveTelemetryPlugin} (story 302, Plugin View Design Book §8.3
 * item 5).
 *
 * <p>Renders the host tap's live waveform alongside the plugin's provider
 * status. With no analysis frames it draws a stationary centre line. Room
 * geometry remains in the docked Telemetry panel.</p>
 *
 * <p>All colours derive from the per-frame {@link Theme} tokens (§2.5); the
 * status rows select between constant strings, so {@link #render(RenderTick)}
 * stays allocation-free.</p>
 */
public final class SoundWaveTelemetryEditor implements PluginEditorFactory.Canvas {

    private final SoundWaveTelemetryPlugin plugin;
    private CanvasSurface surface;

    private final Font titleFont = Font.font("System", FontWeight.BOLD, 13);
    private final Font statusFont = Font.font("System", 12);
    private final Font hintFont = Font.font("System", 11);

    // Palette derived once per theme change so render() allocates nothing.
    private Theme cachedTokens;
    private Color dimText;
    private Color ribbon;

    /**
     * @param plugin the plugin whose status this editor renders; must not be
     *               {@code null}
     */
    public SoundWaveTelemetryEditor(SoundWaveTelemetryPlugin plugin) {
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
     * re-requests a render only while the backing canvas is in a scene whose
     * window is showing ({@link ShowingWindowGate} — a hidden stage keeps its
     * scene attached, so scene presence alone is not enough), so a hidden or
     * torn-down editor never spins the loop — the host's scene-entry / re-show
     * repaint restarts it.</p>
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
        gc.fillText("Sound Wave Telemetry", 8, 6);

        drawStatusRows(gc, w, h, tokens);
        drawWaveform(gc, w, h);

        gc.setFont(hintFont);
        gc.setFill(dimText);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.fillText("Full room telemetry lives in the docked Telemetry panel", w / 2, h - 8);

        if (ShowingWindowGate.isShowing(surface.graphicsContext().getCanvas())) {
            surface.requestRender();
        }
    }

    @Override
    public void detach() {
        surface = null;
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    private void drawStatusRows(GraphicsContext gc, double w, double h, Theme tokens) {
        boolean active = plugin.isActive();
        boolean providerWired = plugin.getArmedTrackSourceProvider() != null;
        boolean subscribed = plugin.isSubscribedToArmedTrackSourceProvider();

        gc.setFont(statusFont);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        double cx = w / 2;
        double y = h * 0.28;
        double step = 20;

        gc.setFill(active ? tokens.accent() : dimText);
        gc.fillText(active ? "Plugin active" : "Plugin inactive", cx, y);

        gc.setFill(providerWired ? tokens.foreground() : dimText);
        gc.fillText(providerWired
                ? "Armed-track provider wired"
                : "No armed-track provider", cx, y + step);

        gc.setFill(subscribed ? tokens.foreground() : dimText);
        gc.fillText(subscribed
                ? "Subscribed to armed-track updates"
                : "Not subscribed to armed-track updates", cx, y + 2 * step);
    }

    private void drawWaveform(GraphicsContext gc, double w, double h) {
        double midY = h * 0.68;
        double amplitude = h * 0.18;
        gc.setStroke(ribbon);
        gc.setLineWidth(2.0);
        var data = plugin.getWaveform();
        if (data == null) {
            gc.strokeLine(0, midY, w, midY);
            gc.setFill(dimText);
            gc.fillText("No signal", w / 2, midY - 12);
            return;
        }
        gc.beginPath();
        float[] values = data.maxValues();
        for (int i = 0; i < values.length; i++) {
            double x = i * w / Math.max(1, values.length - 1);
            double y = midY - amplitude * values[i];
            if (i == 0) {
                gc.moveTo(x, y);
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private void refreshPalette(Theme tokens) {
        if (Objects.equals(tokens, cachedTokens)) {
            return;
        }
        cachedTokens = tokens;
        dimText = tokens.foreground().deriveColor(0, 1.0, 1.0, 0.55);
        ribbon = tokens.accent().deriveColor(0, 1.0, 1.0, 0.30);
    }
}
