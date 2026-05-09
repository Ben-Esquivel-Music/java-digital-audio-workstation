package com.benesquivelmusic.daw.fx;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
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
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GPU-accelerated drawing surface backed by a Java FFM pixel pathway.
 *
 * <p>{@code GpuCanvas} is a resizable {@link Region} that exposes an off-heap
 * {@link MemorySegment} of <strong>BGRA pre-multiplied</strong> pixels to a
 * pluggable {@link GpuRenderer}. After each render the segment is committed
 * to a JavaFX {@link WritableImage} (via {@link PixelBuffer}) which is
 * displayed by an internal {@link ImageView}. Prism — JavaFX's hardware
 * pipeline (Direct3D 11 on Windows, Metal on macOS, OpenGL ES2 on Linux) —
 * uploads that image to the GPU and rasterises it. The active backend can
 * be inspected with {@link GpuPipeline#detect()}.
 *
 * <h2>Pixel pathway</h2>
 * <ol>
 *   <li>A confined {@link Arena} owned by the FX thread allocates a
 *       {@link MemorySegment} of {@code height * stride} bytes (packed BGRA,
 *       4 bytes/pixel, {@code stride == width * 4}).</li>
 *   <li>Each frame: the host clears the segment to
 *       {@link #clearColorProperty()} (or transparent zero if null) and
 *       invokes the renderer.</li>
 *   <li>The host calls {@code PixelBuffer.updateBuffer(...)} to mark the
 *       full image dirty; Prism re-uploads it to the GPU on the next pulse.</li>
 * </ol>
 * BGRA pre is the native upload format on D3D and avoids a swizzle pass on
 * other backends.
 *
 * <h2>Lifecycle</h2>
 * The internal {@link AnimationTimer} starts when {@link #animatedProperty()}
 * is {@code true} <em>and</em> the canvas is attached to a {@link Scene}, and
 * stops when either condition no longer holds. {@link #dispose()} unregisters
 * all listeners, stops the timer, and closes the owning {@link Arena} —
 * after which the {@link MemorySegment} (and the {@link ByteBuffer} view
 * inside the {@link PixelBuffer}) become invalid and any access throws
 * {@link IllegalStateException}. Call {@code dispose()} when the canvas is
 * permanently removed (e.g. the parent view is being destroyed).
 *
 * <p>When the canvas shrinks to zero width or height the surface is released
 * eagerly; the next non-zero size reallocates from scratch.
 *
 * <h2>HiDPI</h2>
 * The surface is allocated at <strong>logical-pixel</strong> resolution
 * ({@code (int) Math.floor(getWidth())} × {@code (int) Math.floor(getHeight())}),
 * clamped to {@link #MAX_SURFACE_DIM} (16 384) pixels per axis to prevent
 * unbounded native allocations and {@code int} overflow in the stride
 * calculation. On HiDPI displays Prism scales the {@link ImageView} to
 * device pixels, so the canvas effectively renders at half (or quarter) the
 * device resolution. This is a known limitation — callers that need
 * device-pixel fidelity should query
 * {@code getScene().getWindow().getOutputScaleX()/Y()} and pre-scale their
 * renderer output accordingly.
 *
 * <h2>Threading</h2>
 * All public mutators, {@link #requestRender()}, {@link #dispose()}, and
 * surface (re)allocation must occur on the JavaFX Application Thread.
 * Calling {@code requestRender()} or {@code dispose()} from any other thread
 * throws {@link IllegalStateException}. {@link PixelBuffer#updateBuffer}
 * requires the FX thread, and the FFM {@link Arena#ofConfined()} pins
 * ownership to it as well.
 *
 * @see GpuRenderer
 * @see GpuRenderContext
 * @see GpuPipeline
 */
public final class GpuCanvas extends Region {

    private static final Logger LOG = Logger.getLogger(GpuCanvas.class.getName());

    private static final double DEFAULT_PREF_SIZE = 250.0;
    private static final double DEFAULT_MIN_SIZE = 50.0;

    private static final int BPP = 4;

    /**
     * Maximum surface dimension in pixels. Matches the common GPU texture
     * limit (16384) and prevents runaway allocations from a misconfigured
     * parent layout. Also keeps {@code stride = width * BPP} safely below
     * {@link Integer#MAX_VALUE}.
     */
    private static final int MAX_SURFACE_DIM = 16384;

    private final ImageView imageView = new ImageView();
    private final Canvas overlayCanvas = new Canvas();
    private final GraphicsContext overlayGc = overlayCanvas.getGraphicsContext2D();

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

    // Off-heap pixel surface. Reallocated on resize, closed on dispose or
    // when the canvas shrinks to zero width or height.
    private Arena arena;
    private MemorySegment pixels;
    private PixelBuffer<ByteBuffer> pixelBuffer;
    private int surfaceWidth;
    private int surfaceHeight;
    private int surfaceStride;

    // Coalesces width+height changes from a single Region#resize call into
    // one deferred render. Without this, each Region#resize allocates the
    // surface twice (once when width changes, once when height changes).
    private boolean coalescedSizeRenderPending = false;

    private long lastNanos = 0L;
    private boolean disposed = false;

    public GpuCanvas() {
        this(GpuRenderer.NOOP);
    }

    public GpuCanvas(GpuRenderer initialRenderer) {
        renderer.set(Objects.requireNonNull(initialRenderer, "initialRenderer"));

        getChildren().add(imageView);
        // Overlay JavaFX Canvas sits on top of the pixel ImageView so renderers
        // that prefer GraphicsContext-based drawing (lines, arcs, gradients,
        // text) can use ctx.gc() while pixel-pathway renderers remain unchanged.
        // The canvas starts each frame fully transparent (clearRect), so it
        // never occludes the pixel surface unless the renderer explicitly draws.
        getChildren().add(overlayCanvas);
        overlayCanvas.widthProperty().bind(widthProperty());
        overlayCanvas.heightProperty().bind(heightProperty());
        overlayCanvas.setMouseTransparent(true);
        getStyleClass().add("gpu-canvas");

        // Size the displayed image to the Region. Using fit dimensions (rather
        // than mutating from layoutChildren) keeps the ImageView correct even
        // when there is no live Scene to drive the layout pulse — important
        // for headless tests that drive the canvas via Region#resize.
        imageView.fitWidthProperty().bind(widthProperty());
        imageView.fitHeightProperty().bind(heightProperty());
        imageView.setPreserveRatio(false);
        imageView.setSmooth(false);

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double delta = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1_000_000_000.0;
                lastNanos = now;
                renderOneFrame(now, delta);
            }
        };

        sizeListener = obs -> scheduleSizeChangeRender();
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
        requireFxThread("setRenderer");
        renderer.set(Objects.requireNonNull(r, "renderer"));
    }

    public final BooleanProperty animatedProperty() { return animated; }
    public final boolean isAnimated() { return animated.get(); }
    public final void setAnimated(boolean value) {
        requireFxThread("setAnimated");
        animated.set(value);
    }

    public final ObjectProperty<Color> clearColorProperty() { return clearColor; }
    public final Color getClearColor() { return clearColor.get(); }
    public final void setClearColor(Color color) {
        requireFxThread("setClearColor");
        clearColor.set(color);
    }

    public final ReadOnlyLongProperty frameCountProperty() { return frameCount.getReadOnlyProperty(); }
    public final long getFrameCount() { return frameCount.get(); }

    /** Reports the active Prism backend; equivalent to {@link GpuPipeline#detect()}. */
    public final GpuPipeline getActivePipeline() {
        return GpuPipeline.detect();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Renders one frame immediately on the current thread. Must be called on
     * the JavaFX Application Thread; calling from any other thread throws
     * {@link IllegalStateException}. No-op if the canvas has been
     * {@linkplain #dispose() disposed} or has zero width or height.
     *
     * <p>One-off renders always deliver {@link GpuRenderContext#deltaSeconds()}
     * of {@code 0.0}; non-zero deltas only originate from the internal
     * {@link AnimationTimer}.
     */
    public void requestRender() {
        requireFxThread("requestRender");
        if (disposed) return;
        renderOneFrame(System.nanoTime(), 0.0);
    }

    /**
     * Defers a render to the next FX pulse, coalescing repeated size-change
     * notifications. {@code Region#resize(w, h)} fires the width and height
     * listeners back-to-back; without coalescing each firing would allocate a
     * fresh surface and immediately discard the previous one.
     */
    private void scheduleSizeChangeRender() {
        if (disposed || coalescedSizeRenderPending) return;
        coalescedSizeRenderPending = true;
        Platform.runLater(() -> {
            coalescedSizeRenderPending = false;
            if (disposed) return;
            renderOneFrame(System.nanoTime(), 0.0);
        });
    }

    private void renderOneFrame(long nowNanos, double deltaSeconds) {
        if (disposed) return;
        // Defensive: renderer is never null (setRenderer rejects null and the
        // field is initialised to GpuRenderer.NOOP), but guard against a
        // hypothetical future code path that clears the property directly.
        GpuRenderer current = renderer.get();
        if (current == null) {
            return;
        }
        if (!ensureSurface()) {
            return;
        }

        clearSurface();
        // The overlay JavaFX Canvas always starts each frame fully transparent
        // so anything drawn through ctx.gc() is layered cleanly on top of the
        // pixel surface (which itself reflects clearColorProperty()).
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            overlayGc.clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
        }

        long frame = frameCount.get();
        GpuRenderContext ctx = new GpuRenderContext(
                pixels, surfaceWidth, surfaceHeight, surfaceStride,
                nowNanos, deltaSeconds, frame, overlayGc);
        try {
            current.render(ctx);
        } catch (RuntimeException e) {
            // A throwing renderer would, if uncaught, propagate out of
            // AnimationTimer#handle and detach the timer permanently — one bad
            // frame would stop the entire render loop. Log, drop the frame,
            // and stay alive. frameCount is not incremented for failed frames.
            LOG.log(Level.WARNING, "GpuRenderer threw; frame discarded", e);
            return;
        }
        // Mark the entire image dirty. PixelBuffer signals a full update when
        // the callback returns null (vs. a partial Rectangle2D).
        pixelBuffer.updateBuffer(pb -> null);
        frameCount.set(frame + 1);
    }

    /**
     * Allocates or reallocates the off-heap pixel surface to match the current
     * Region size. Releases the surface when either dimension is non-positive.
     * Returns {@code true} if a usable surface (positive size) exists on
     * return.
     */
    private boolean ensureSurface() {
        if (disposed) return false;
        int w = Math.min((int) Math.floor(getWidth()), MAX_SURFACE_DIM);
        int h = Math.min((int) Math.floor(getHeight()), MAX_SURFACE_DIM);
        if (w <= 0 || h <= 0) {
            releaseSurface();
            return false;
        }
        if (pixels != null && w == surfaceWidth && h == surfaceHeight) {
            return true;
        }
        // (Re)allocate atomically: build the new surface, then close the old
        // arena. Anything that referenced the old segment is invalidated, but
        // the renderer never retains it across calls (per GpuRenderContext
        // contract) and the displayed image is replaced before the close.
        Arena oldArena = arena;
        int stride = w * BPP;
        long byteSize = (long) h * stride;

        Arena newArena = Arena.ofConfined();
        MemorySegment newSegment = newArena.allocate(byteSize, BPP);
        // Native-backed segments give a direct ByteBuffer that aliases the
        // off-heap memory; Prism uploads from that address without copying.
        // A non-native segment would force per-commit copies into a staging
        // buffer, defeating the whole point of this pathway.
        if (!newSegment.isNative()) {
            newArena.close();
            throw new IllegalStateException(
                    "GpuCanvas surface must be backed by a native MemorySegment");
        }
        ByteBuffer newBuffer = newSegment.asByteBuffer();
        PixelBuffer<ByteBuffer> newPixelBuffer = new PixelBuffer<>(
                w, h, newBuffer, PixelFormat.getByteBgraPreInstance());
        WritableImage newImage = new WritableImage(newPixelBuffer);

        this.arena = newArena;
        this.pixels = newSegment;
        this.pixelBuffer = newPixelBuffer;
        this.surfaceWidth = w;
        this.surfaceHeight = h;
        this.surfaceStride = stride;

        imageView.setImage(newImage);

        // Defer closing the old arena to the next FX pulse so that any
        // in-flight Prism upload that still references the previous
        // PixelBuffer's ByteBuffer (which aliases the old MemorySegment)
        // can complete before the memory is unmapped.
        if (oldArena != null) {
            Platform.runLater(oldArena::close);
        }
        return true;
    }

    private void releaseSurface() {
        if (arena == null) return;
        // Defer closing to the next FX pulse so that any in-flight Prism
        // upload referencing the PixelBuffer's aliased ByteBuffer can
        // complete before the backing MemorySegment is unmapped.
        Arena old = arena;
        arena = null;
        pixels = null;
        pixelBuffer = null;
        surfaceWidth = 0;
        surfaceHeight = 0;
        surfaceStride = 0;
        imageView.setImage(null);
        Platform.runLater(old::close);
    }

    private void clearSurface() {
        Color background = clearColor.get();
        if (background == null) {
            // Fully transparent: every byte is zero.
            pixels.fill((byte) 0);
            return;
        }
        // BGRA pre-multiplied: premultiply RGB by alpha, then write [B, G, R, A].
        double a = background.getOpacity();
        byte aByte = (byte) Math.round(a * 255.0);
        byte rByte = (byte) Math.round(background.getRed()   * a * 255.0);
        byte gByte = (byte) Math.round(background.getGreen() * a * 255.0);
        byte bByte = (byte) Math.round(background.getBlue()  * a * 255.0);

        // Fully-transparent clear color (e.g. Color.color(r, g, b, 0)):
        // all premultiplied bytes are zero — use the fast bulk fill path.
        if (aByte == 0 && rByte == 0 && gByte == 0 && bByte == 0) {
            pixels.fill((byte) 0);
            return;
        }

        // Write the first pixel, then tile by doubling — O(log n) MemorySegment
        // bulk copies. The source region [0, copy) and destination region
        // [filled, filled + copy) never overlap because copy ≤ filled.
        long total = pixels.byteSize();
        if (total < BPP) return;
        pixels.set(ValueLayout.JAVA_BYTE, 0L, bByte);
        pixels.set(ValueLayout.JAVA_BYTE, 1L, gByte);
        pixels.set(ValueLayout.JAVA_BYTE, 2L, rByte);
        pixels.set(ValueLayout.JAVA_BYTE, 3L, aByte);
        long filled = BPP;
        while (filled < total) {
            long copy = Math.min(filled, total - filled);
            MemorySegment.copy(pixels, 0L, pixels, filled, copy);
            filled += copy;
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

    private static void requireFxThread(String op) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException(
                    op + " must be called on the JavaFX Application Thread");
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void layoutChildren() {
        imageView.setLayoutX(0);
        imageView.setLayoutY(0);
        overlayCanvas.setLayoutX(0);
        overlayCanvas.setLayoutY(0);
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
     * Stops the render loop, unregisters all listeners, and releases the
     * off-heap pixel surface. Must be called on the JavaFX Application Thread;
     * calling from any other thread throws {@link IllegalStateException}. Safe
     * to call multiple times; further property mutations and
     * {@link #requestRender()} calls have no effect after disposal.
     *
     * <p>After disposal, any retained reference to the prior
     * {@link GpuRenderContext#pixels()} segment becomes invalid — accessing
     * it throws {@link IllegalStateException}.
     */
    public void dispose() {
        requireFxThread("dispose");
        if (disposed) return;
        disposed = true;
        timer.stop();
        lastNanos = 0L;
        imageView.fitWidthProperty().unbind();
        imageView.fitHeightProperty().unbind();
        imageView.setImage(null);
        overlayCanvas.widthProperty().unbind();
        overlayCanvas.heightProperty().unbind();
        // Final clear so the overlay does not retain stale pixels if the
        // canvas is removed from but later re-added to the scene graph.
        if (overlayCanvas.getWidth() > 0 && overlayCanvas.getHeight() > 0) {
            overlayGc.clearRect(0, 0, overlayCanvas.getWidth(), overlayCanvas.getHeight());
        }
        widthProperty().removeListener(sizeListener);
        heightProperty().removeListener(sizeListener);
        renderer.removeListener(repaintOnChange);
        clearColor.removeListener(repaintOnChange);
        animated.removeListener(animatedListener);
        sceneProperty().removeListener(sceneListener);
        if (arena != null) {
            // Defer closing to the next FX pulse so that any in-flight Prism
            // upload referencing the PixelBuffer's aliased ByteBuffer can
            // complete before the backing MemorySegment is unmapped.
            Arena old = arena;
            arena = null;
            pixels = null;
            pixelBuffer = null;
            surfaceWidth = 0;
            surfaceHeight = 0;
            surfaceStride = 0;
            Platform.runLater(old::close);
        }
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
        private double[] prefSize = null;

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
            this.prefSize = new double[]{ width, height };
            return this;
        }

        /**
         * Builds and returns the configured {@code GpuCanvas}. Must be called
         * on the JavaFX Application Thread (the underlying property setters
         * enforce this constraint).
         */
        public GpuCanvas build() {
            GpuCanvas c = new GpuCanvas(renderer);
            c.setClearColor(clearColor);
            c.setAnimated(animated);
            if (prefSize != null) {
                c.setPrefSize(prefSize[0], prefSize[1]);
            }
            return c;
        }
    }
}
