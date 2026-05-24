package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.fx.GpuCanvas;
import com.benesquivelmusic.daw.fx.GpuRenderer;

import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * Shared {@link Region}-shell base for {@code daw-app} visualizers that host
 * a single {@link GpuCanvas} child.
 *
 * <p>Every {@code *Display} class in {@link com.benesquivelmusic.daw.app.ui.display}
 * needs the same boilerplate to wrap its renderer in a JavaFX layout node:
 * add a {@link GpuCanvas} child, resize it to the full region bounds in
 * {@link #layoutChildren()}, stop animation and release the off-heap surface
 * in {@link #dispose()}, and force a one-shot render when the canvas is
 * detached (so callers that push new data to a hidden display still see
 * the latest frame the moment it is reattached).</p>
 *
 * <p>This base owns that shell. Subclasses only contribute their own DSP /
 * state fields, their {@link GpuRenderer} body (supplied via
 * {@link #setRenderer(GpuRenderer)}, typically a {@code this::renderFrame}
 * method reference so the lambda can close over instance state), and any
 * additional public API or extra overlay children. The renderer body and
 * resulting pixel output are unaffected — this is a pure layout-shell
 * extraction.</p>
 *
 * <h2>Why this lives in {@code daw-app}, not {@code daw-fx}</h2>
 *
 * <p>The {@code daw-fx} module is intentionally minimal: four surface
 * primitives ({@link GpuCanvas}, {@link GpuRenderer},
 * {@link com.benesquivelmusic.daw.fx.GpuRenderContext},
 * {@link com.benesquivelmusic.daw.fx.GpuPipeline}) and nothing else. The
 * scene-aware one-shot handshake (rendering when {@code getScene() == null})
 * is a {@code daw-app}-specific convention for pushing data into displays
 * that may not yet be attached to a {@link javafx.scene.Scene}; pulling it
 * down into {@code daw-fx} would couple the primitive module to a snapshot
 * pattern only the app's visualizers need.</p>
 *
 * <h2>Subclasses that extend the layout</h2>
 *
 * <p>Subclasses that add extra overlay children (e.g. icon labels) must
 * override {@link #layoutChildren()} and call {@code super.layoutChildren()}
 * first so the underlying canvas is still sized to fill the region.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Construction, {@link #setRenderer(GpuRenderer)}, {@link #requestRender()},
 * and {@link #dispose()} must all occur on the JavaFX Application Thread —
 * they delegate to {@link GpuCanvas} methods that enforce the same
 * constraint.</p>
 */
public abstract class GpuCanvasView extends Region {

    private final GpuCanvas gpuCanvas;
    private boolean disposed;

    /**
     * Creates the shell with a clear color and initial animation state.
     *
     * @param clearColor         the background fill applied to the pixel
     *                           surface every frame, or {@code null} to
     *                           leave it transparent (e.g. for circular
     *                           LED indicators that draw on a transparent
     *                           background so the parent shows through).
     * @param animatedByDefault  {@code true} to enable the
     *                           {@link javafx.animation.AnimationTimer} as
     *                           soon as the view attaches to a scene;
     *                           {@code false} for displays driven purely by
     *                           explicit {@link #requestRender()} calls.
     */
    protected GpuCanvasView(Color clearColor, boolean animatedByDefault) {
        gpuCanvas = GpuCanvas.create()
                .clearColor(clearColor)
                .animated(animatedByDefault)
                .build();
        getChildren().add(gpuCanvas);
    }

    /**
     * Installs the renderer that will be invoked on every frame. Typically
     * called once from the subclass constructor with a method reference
     * (e.g. {@code setRenderer(this::renderFrame)}) so the lambda can
     * capture {@code this} state.
     */
    protected final void setRenderer(GpuRenderer renderer) {
        gpuCanvas.setRenderer(renderer);
    }

    /**
     * Returns the underlying {@link GpuCanvas} so subclasses can reach
     * methods that have no first-class wrapper here (e.g.
     * {@link GpuCanvas#setAnimated(boolean)} to start/stop the timer).
     *
     * <p>Intentionally {@code protected} — there is no public passthrough,
     * callers cannot reach the canvas.</p>
     */
    protected final GpuCanvas gpuCanvas() {
        return gpuCanvas;
    }

    /**
     * Scene-aware refresh helper.
     *
     * <p>When the view is attached to a {@link javafx.scene.Scene} the
     * {@link GpuCanvas} {@link javafx.animation.AnimationTimer} is driving
     * frames, so an extra {@code requestRender()} would just duplicate the
     * next pulse — this method is a no-op. When the view has no scene
     * (e.g. a display fed data before being added to the graph, or one
     * temporarily detached) the timer is gated off, so we trigger a
     * single immediate render to keep the off-screen surface current.</p>
     */
    protected final void requestRender() {
        if (getScene() == null) {
            gpuCanvas.requestRender();
        }
    }

    /**
     * Resizes the underlying {@link GpuCanvas} to fill the region. Subclasses
     * that add extra overlay children must override this method and call
     * {@code super.layoutChildren()} first.
     */
    @Override
    protected void layoutChildren() {
        gpuCanvas.resizeRelocate(0, 0, getWidth(), getHeight());
    }

    /**
     * Stops the render loop and releases the off-heap pixel surface. Safe
     * to call multiple times. Must be called from the JavaFX Application
     * Thread.
     */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        gpuCanvas.setAnimated(false);
        gpuCanvas.dispose();
    }

    /** Returns {@code true} once {@link #dispose()} has been invoked. */
    protected final boolean isDisposed() {
        return disposed;
    }
}
