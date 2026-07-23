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
 * <p><strong>This is a status surface, not a duplicate renderer.</strong> The
 * plugin instance holds no telemetry data — the rich room-telemetry display is
 * the story-287 docked {@code TelemetryView} (daw-app), fed by the static
 * {@code SoundWaveTelemetryEngine} from the project's
 * {@code RoomConfiguration}, and it stays untouched and reachable through the
 * dock manifest. The SDK currently offers no channel that could carry
 * {@code RoomTelemetryData} into a plugin editor, so this canvas renders what
 * the plugin itself can answer — active state, armed-track provider wiring and
 * subscription — plus a gentle animated ribbon so the surface reads as alive.
 * Surfacing the room telemetry through the SDK contract is future-story
 * plumbing.</p>
 *
 * <p>All colours derive from the per-frame {@link Theme} tokens (§2.5); the
 * status rows select between constant strings, so {@link #render(RenderTick)}
 * stays allocation-free.</p>
 */
public final class SoundWaveTelemetryEditor implements PluginEditorFactory.Canvas {

    /** Ribbon oscillation rate in radians per second. */
    private static final double PHASE_RATE = 1.2;

    /** Ribbon spatial frequency in radians per pixel. */
    private static final double RIBBON_WAVELENGTH = 0.025;

    /** Horizontal sampling step of the ribbon polyline, in pixels. */
    private static final double RIBBON_STEP = 4.0;

    private static final double TWO_PI = Math.PI * 2.0;

    private final SoundWaveTelemetryPlugin plugin;
    private CanvasSurface surface;

    private final Font titleFont = Font.font("System", FontWeight.BOLD, 13);
    private final Font statusFont = Font.font("System", 12);
    private final Font hintFont = Font.font("System", 11);

    // Palette derived once per theme change so render() allocates nothing.
    private Theme cachedTokens;
    private Color dimText;
    private Color ribbon;

    /** Accumulated ribbon phase, advanced by {@link RenderTick#deltaSeconds()}. */
    private double phase;

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
        phase = (phase + tick.deltaSeconds() * PHASE_RATE) % TWO_PI;

        gc.setFill(tokens.background());
        gc.fillRect(0, 0, w, h);

        gc.setFont(titleFont);
        gc.setFill(tokens.foreground());
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.fillText("Sound Wave Telemetry", 8, 6);

        drawStatusRows(gc, w, h, tokens);
        drawRibbon(gc, w, h);

        gc.setFont(hintFont);
        gc.setFill(dimText);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.fillText("Full room telemetry lives in the docked Telemetry panel", w / 2, h - 8);

        if (surface.graphicsContext().getCanvas().getScene() != null) {
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

    private void drawRibbon(GraphicsContext gc, double w, double h) {
        double midY = h * 0.68;
        double amplitude = h * 0.10;
        gc.setStroke(ribbon);
        gc.setLineWidth(2.0);
        gc.beginPath();
        boolean first = true;
        for (double x = 0; x <= w; x += RIBBON_STEP) {
            double y = midY + amplitude * Math.sin(phase + x * RIBBON_WAVELENGTH);
            if (first) {
                gc.moveTo(x, y);
                first = false;
            } else {
                gc.lineTo(x, y);
            }
        }
        gc.stroke();
    }

    private void refreshPalette(Theme tokens) {
        if (tokens == cachedTokens) {
            return;
        }
        cachedTokens = tokens;
        dimText = tokens.foreground().deriveColor(0, 1.0, 1.0, 0.55);
        ribbon = tokens.accent().deriveColor(0, 1.0, 1.0, 0.30);
    }
}
