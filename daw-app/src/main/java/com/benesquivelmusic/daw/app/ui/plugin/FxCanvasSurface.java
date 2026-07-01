package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.sdk.editor.CanvasSurface;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;

import java.util.Objects;

/**
 * Host-side {@link CanvasSurface} implementation backing a
 * {@link PluginEditorFactory.Canvas} editor with a JavaFX {@link Canvas}
 * (story 301, Plugin View Design Book §4.1, §6.3).
 *
 * <p>The host owns the surface (§2.2): {@link PluginEditorSession} creates the
 * backing {@code Canvas} inside its {@code editor-canvas-host} wrapper, sizes
 * it to follow the wrapper's layout bounds, and hands the plugin only this
 * facade — the plugin draws <em>into</em> the surface but never constructs,
 * sizes or reparents it. Clipping is enforced one level up: the
 * {@code EditorFrame} skin clips the whole body host to its bounds (§6.3), so
 * a misbehaving plugin cannot paint over the breadcrumb or footer and this
 * facade needs no clip of its own.</p>
 *
 * <p>{@link #requestRender()} routes to the session's coalesced render
 * scheduler ({@code FxDispatcher} keyed latest-wins, §6.3 "the host coalesces
 * repeated requests within a single frame") — the same path
 * {@code EditorContext#requestRender()} takes, so an event-handler repaint and
 * a context repaint collapse into one {@code render(RenderTick)} per pulse.</p>
 *
 * <p>Every method here is called on the JavaFX Application Thread, from inside
 * the session's fault harness (§4.7).</p>
 *
 * @see PluginEditorSession
 */
final class FxCanvasSurface implements CanvasSurface {

    private final Canvas canvas;
    private final Runnable renderRequester;

    /**
     * @param canvas          the host-owned backing canvas; must not be
     *                        {@code null}
     * @param renderRequester the session's coalesced render scheduler; must
     *                        not be {@code null}
     */
    FxCanvasSurface(Canvas canvas, Runnable renderRequester) {
        this.canvas = Objects.requireNonNull(canvas, "canvas must not be null");
        this.renderRequester = Objects.requireNonNull(
                renderRequester, "renderRequester must not be null");
    }

    @Override
    public double width() {
        return canvas.getWidth();
    }

    @Override
    public double height() {
        return canvas.getHeight();
    }

    @Override
    public GraphicsContext graphicsContext() {
        return canvas.getGraphicsContext2D();
    }

    @Override
    public void requestRender() {
        renderRequester.run();
    }
}
