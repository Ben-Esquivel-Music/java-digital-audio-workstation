package com.benesquivelmusic.daw.sdk.editor;

import javafx.scene.canvas.GraphicsContext;

/**
 * A minimal facade over the drawing surface the host provides to a
 * {@link PluginEditorFactory.Canvas} plugin (Plugin View Design Book §4.1).
 * The host owns the surface — it is backed by a JavaFX {@code Canvas} today and
 * may be backed by a GPU canvas in future — so the plugin draws <em>into</em>
 * it but never constructs, sizes, or reparents it (§2.2, §6.3).
 *
 * <p><strong>Threading (§4.7):</strong> every method here is called on the
 * JavaFX Application Thread, from inside the host's fault harness. Drawing
 * should not allocate and must not block.</p>
 */
public interface CanvasSurface {

    /**
     * Returns the current drawable width in pixels. May change between frames
     * when the host resizes the editor; a plugin should read it each frame
     * rather than cache it.
     *
     * @return the surface width in pixels
     */
    double width();

    /**
     * Returns the current drawable height in pixels.
     *
     * @return the surface height in pixels
     */
    double height();

    /**
     * Returns the {@link GraphicsContext} the plugin draws with. The host
     * clips drawing to the surface bounds, so a misbehaving plugin cannot paint
     * over the host chrome (§6.3). Valid only between
     * {@link PluginEditorFactory.Canvas#attach(CanvasSurface)} and
     * {@link PluginEditorFactory.Canvas#detach()}.
     *
     * @return the graphics context for this surface, never {@code null}
     */
    GraphicsContext graphicsContext();

    /**
     * Asks the host to schedule another {@link PluginEditorFactory.Canvas#render(RenderTick)}
     * on the next pulse. Use this from an event handler (a knob turn, a data
     * update) to repaint on demand; the host coalesces repeated requests within
     * a single frame.
     */
    void requestRender();
}
