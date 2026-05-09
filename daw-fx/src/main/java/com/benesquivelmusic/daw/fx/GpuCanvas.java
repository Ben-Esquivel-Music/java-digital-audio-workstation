package com.benesquivelmusic.daw.fx;

import javafx.animation.AnimationTimer;
import javafx.beans.InvalidationListener;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.Objects;

/**
 * GPU-accelerated drawing surface.
 *
 * <p>{@code GpuCanvas} is a resizable {@link Region} that wraps a JavaFX
 * {@link Canvas} and dispatches frames to a pluggable {@link GpuRenderer}.
 * The Canvas is rasterised by Prism, JavaFX's hardware pipeline (Direct3D 11
 * on Windows, Metal on macOS, OpenGL ES2 on Linux), so fills, strokes,
 * gradients, image draws, and clips are pushed to the GPU. The active
 * backend can be inspected with {@link GpuPipeline#detect()}.
 *
 * <h2>Lifecycle</h2>
 * The internal {@link AnimationTimer} starts when {@link #animatedProperty()}
 * is {@code true} <em>and</em> the canvas is attached to a {@link Scene}, and
 * stops when either condition no longer holds. {@link #dispose()} unregisters
 * all listeners and stops the timer for good — call it when the canvas is
 * permanently removed (e.g. the parent view is being destroyed).
 *
 * <h2>Rendering model</h2>
 * Each frame, the canvas is cleared (using {@link #clearColorProperty()} if
 * non-null, otherwise to fully transparent) and the current renderer is
 * invoked with a {@link GpuRenderContext}. When {@link #animatedProperty()}
 * is {@code false}, frames are only produced on demand via
 * {@link #requestRender()} or when the canvas is resized.
 *
 * @see GpuRenderer
 * @see GpuPipeline
 */
public final class GpuCanvas extends Region {

    private static final double DEFAULT_PREF_SIZE = 250.0;
    private static final double DEFAULT_MIN_SIZE = 50.0;

    private final Canvas canvas = new Canvas();

    private final ObjectProperty<GpuRenderer> renderer =
            new SimpleObjectProperty<>(this, "renderer", GpuRenderer.NOOP);
    private final BooleanProperty animated =
            new SimpleBooleanProperty(this, "animated", false);
    private final ObjectProperty<Color> clearColor =
            new SimpleObjectProperty<>(this, "clearColor", null);
    private final ReadOnlyLongWrapper frameCount =
            new ReadOnlyLongWrapper(this, "frameCount", 0L);

    private final AnimationTimer timer;
    private final InvalidationListener sizeListener;
    private final InvalidationListener repaintOnChange;
    private final ChangeListener<Boolean> animatedListener;
    private final ChangeListener<Scene> sceneListener;

    private long lastNanos = 0L;
    private boolean disposed = false;

    public GpuCanvas() {
        this(GpuRenderer.NOOP);
    }

    public GpuCanvas(GpuRenderer initialRenderer) {
        renderer.set(Objects.requireNonNull(initialRenderer, "initialRenderer"));

        getChildren().add(canvas);
        getStyleClass().add("gpu-canvas");

        // Track the Region's size so renders work whether the canvas is in a
        // live scene (where layoutChildren runs) or driven directly via
        // Region#resize from tests/embedding code.
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double delta = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;
                renderOneFrame(now, delta);
            }
        };

        sizeListener = obs -> requestRender();
        widthProperty().addListener(sizeListener);
        heightProperty().addListener(sizeListener);

        repaintOnChange = obs -> requestRender();
        renderer.addListener(repaintOnChange);
        clearColor.addListener(repaintOnChange);

        animatedListener = (obs, was, isNow) -> updateTimerState();
        animated.addListener(animatedListener);

        sceneListener = (obs, oldScene, newScene) -> updateTimerState();
        sceneProperty().addListener(sceneListener);
    }

    // ------------------------------------------------------------------
    // Properties
    // ------------------------------------------------------------------

    public final ObjectProperty<GpuRenderer> rendererProperty() { return renderer; }
    public final GpuRenderer getRenderer() { return renderer.get(); }
    public final void setRenderer(GpuRenderer r) {
        renderer.set(Objects.requireNonNull(r, "renderer"));
    }

    public final BooleanProperty animatedProperty() { return animated; }
    public final boolean isAnimated() { return animated.get(); }
    public final void setAnimated(boolean value) { animated.set(value); }

    public final ObjectProperty<Color> clearColorProperty() { return clearColor; }
    public final Color getClearColor() { return clearColor.get(); }
    public final void setClearColor(Color color) { clearColor.set(color); }

    public final ReadOnlyLongProperty frameCountProperty() { return frameCount.getReadOnlyProperty(); }
    public final long getFrameCount() { return frameCount.get(); }

    /** Reports the active Prism backend; equivalent to {@link GpuPipeline#detect()}. */
    public GpuPipeline getActivePipeline() {
        return GpuPipeline.detect();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Renders one frame immediately on the current thread. Must be called on
     * the JavaFX Application Thread. No-op if the canvas has been
     * {@linkplain #dispose() disposed} or has zero width or height.
     *
     * <p>One-off renders always deliver {@link GpuRenderContext#deltaSeconds()}
     * of {@code 0.0}; non-zero deltas only originate from the internal
     * {@link AnimationTimer}.
     */
    public void requestRender() {
        if (disposed) return;
        renderOneFrame(System.nanoTime(), 0.0);
    }

    private void renderOneFrame(long nowNanos, double deltaSeconds) {
        GpuRenderer current = renderer.get();
        if (current == null) {
            return;
        }
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0.0 || h <= 0.0) {
            return;
        }

        GraphicsContext gc = canvas.getGraphicsContext2D();
        Color background = clearColor.get();
        if (background == null) {
            gc.clearRect(0, 0, w, h);
        } else {
            gc.setFill(background);
            gc.fillRect(0, 0, w, h);
        }

        long frame = frameCount.get();
        GpuRenderContext ctx = new GpuRenderContext(gc, w, h, nowNanos, deltaSeconds, frame);
        try {
            current.render(ctx);
        } finally {
            frameCount.set(frame + 1);
        }
    }

    private void updateTimerState() {
        if (disposed) return;
        boolean shouldRun = animated.get() && getScene() != null;
        if (shouldRun) {
            lastNanos = 0L;
            timer.start();
        } else {
            timer.stop();
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void layoutChildren() {
        canvas.setLayoutX(0);
        canvas.setLayoutY(0);
    }

    @Override protected double computeMinWidth(double height)  { return DEFAULT_MIN_SIZE; }
    @Override protected double computeMinHeight(double width)  { return DEFAULT_MIN_SIZE; }
    @Override protected double computePrefWidth(double height) { return DEFAULT_PREF_SIZE; }
    @Override protected double computePrefHeight(double width) { return DEFAULT_PREF_SIZE; }
    @Override protected double computeMaxWidth(double height)  { return Double.MAX_VALUE; }
    @Override protected double computeMaxHeight(double width)  { return Double.MAX_VALUE; }

    @Override public boolean isResizable() { return true; }

    // ------------------------------------------------------------------
    // Disposal
    // ------------------------------------------------------------------

    /**
     * Stops the render loop and unregisters all listeners. Must be called on
     * the JavaFX Application Thread. Safe to call multiple times; further
     * property mutations have no effect after disposal.
     */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        timer.stop();
        canvas.widthProperty().unbind();
        canvas.heightProperty().unbind();
        widthProperty().removeListener(sizeListener);
        heightProperty().removeListener(sizeListener);
        renderer.removeListener(repaintOnChange);
        clearColor.removeListener(repaintOnChange);
        animated.removeListener(animatedListener);
        sceneProperty().removeListener(sceneListener);
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    /** Returns a new fluent builder. */
    public static Builder create() {
        return new Builder();
    }

    /** Fluent builder for {@code GpuCanvas}. */
    public static final class Builder {
        private GpuRenderer renderer = GpuRenderer.NOOP;
        private boolean animated = false;
        private Color clearColor = null;
        private boolean prefSizeSet = false;
        private double prefWidth;
        private double prefHeight;

        private Builder() { }

        public Builder renderer(GpuRenderer r) {
            this.renderer = Objects.requireNonNull(r, "renderer");
            return this;
        }

        public Builder animated(boolean value) {
            this.animated = value;
            return this;
        }

        public Builder clearColor(Color color) {
            this.clearColor = color;
            return this;
        }

        public Builder prefSize(double width, double height) {
            this.prefWidth = width;
            this.prefHeight = height;
            this.prefSizeSet = true;
            return this;
        }

        public GpuCanvas build() {
            GpuCanvas c = new GpuCanvas(renderer);
            c.setClearColor(clearColor);
            c.setAnimated(animated);
            if (prefSizeSet) {
                c.setPrefSize(prefWidth, prefHeight);
            }
            return c;
        }
    }
}
