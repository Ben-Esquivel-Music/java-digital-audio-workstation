package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.fx.GpuCanvas;
import com.benesquivelmusic.daw.fx.GpuRenderContext;
import com.benesquivelmusic.daw.fx.GpuRenderer;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless tests for {@link GpuCanvasView} — the shared {@link javafx.scene.layout.Region}
 * shell extracted from the per-display boilerplate. Exercises:
 *
 * <ul>
 *   <li>Construction and child wiring — the {@link GpuCanvas} is the sole
 *       managed child of the view.</li>
 *   <li>Layout — resizing the view also resizes the embedded canvas to fill
 *       the full bounds.</li>
 *   <li>Disposal — animation is stopped and the underlying canvas is
 *       disposed; idempotent.</li>
 *   <li>Scene-aware {@link GpuCanvasView#requestRender()} — a one-shot
 *       render when detached from a scene, a no-op when attached (the
 *       {@link javafx.animation.AnimationTimer} drives frames in that case).</li>
 *   <li>Render context smoke test — a subclass of {@code GpuCanvasView}
 *       receives {@link GpuRenderContext#gc()} and
 *       {@link GpuRenderContext#pixels()} identically to a hand-rolled
 *       host built directly on {@link GpuCanvas}.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class GpuCanvasViewTest {

    /**
     * Minimal concrete subclass used for testing — installs a renderer that
     * records every invocation and exposes the protected hooks for
     * assertions.
     */
    private static final class TestView extends GpuCanvasView {
        final AtomicInteger renderCount = new AtomicInteger();
        final AtomicReference<GpuRenderContext> lastCtx = new AtomicReference<>();

        TestView(Color clearColor, boolean animated) {
            super(clearColor, animated);
            setRenderer(ctx -> {
                renderCount.incrementAndGet();
                lastCtx.set(ctx);
            });
        }

        // Re-expose the protected helpers for the tests.
        GpuCanvas canvas() { return gpuCanvas(); }
        void pingRender() { requestRender(); }
        void replaceRenderer(GpuRenderer r) { setRenderer(r); }
    }

    // ── FX harness helpers ────────────────────────────────────────────────

    private <T> T onFx(Supplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) {
            throw new AssertionError("FX action threw", err.get());
        }
        return ref.get();
    }

    private void onFxRun(Runnable r) throws Exception {
        onFx(() -> { r.run(); return null; });
    }

    /** Drain pending {@code Platform.runLater} tasks (e.g. coalesced
     *  size-change renders) so subsequent assertions measure a quiescent
     *  baseline. Empirically two round-trips are sufficient. */
    private void drainFx() throws Exception {
        onFxRun(() -> {});
        onFxRun(() -> {});
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    void constructorAddsSingleCanvasAsManagedChild() throws Exception {
        TestView view = onFx(() -> new TestView(Color.BLACK, false));
        try {
            assertThat(view.getChildrenUnmodifiable())
                    .hasSize(1)
                    .first()
                    .isSameAs(view.canvas());
            assertThat(view.canvas()).isNotNull();
            assertThat(view.canvas().getClearColor()).isEqualTo(Color.BLACK);
            assertThat(view.canvas().isAnimated()).isFalse();
        } finally {
            onFxRun(view::dispose);
        }
    }

    @Test
    void animatedByDefaultConstructorEnablesTimer() throws Exception {
        TestView view = onFx(() -> new TestView(Color.BLACK, true));
        try {
            assertThat(view.canvas().isAnimated()).isTrue();
        } finally {
            onFxRun(view::dispose);
        }
    }

    @Test
    void layoutChildrenResizesCanvasToFillBounds() throws Exception {
        TestView view = onFx(() -> {
            TestView v = new TestView(Color.BLACK, false);
            v.resize(320, 180);
            v.layout();
            return v;
        });
        try {
            assertThat(view.canvas().getWidth()).isEqualTo(320.0);
            assertThat(view.canvas().getHeight()).isEqualTo(180.0);
            assertThat(view.canvas().getLayoutX()).isEqualTo(0.0);
            assertThat(view.canvas().getLayoutY()).isEqualTo(0.0);

            onFxRun(() -> { view.resize(640, 360); view.layout(); });
            assertThat(view.canvas().getWidth()).isEqualTo(640.0);
            assertThat(view.canvas().getHeight()).isEqualTo(360.0);
        } finally {
            onFxRun(view::dispose);
        }
    }

    @Test
    void disposeStopsAnimationAndDisposesCanvasIdempotently() throws Exception {
        TestView view = onFx(() -> new TestView(Color.BLACK, true));
        GpuCanvas canvas = view.canvas();

        assertThat(canvas.isAnimated()).isTrue();
        assertThat(view.isDisposed()).isFalse();

        onFxRun(view::dispose);

        assertThat(view.isDisposed()).isTrue();
        assertThat(canvas.isAnimated()).isFalse();

        // requestRender on a disposed canvas must be a no-op (per the
        // GpuCanvas contract) and the second dispose() must not throw.
        onFxRun(view::dispose);
        onFxRun(() -> canvas.requestRender());
        assertThat(view.isDisposed()).isTrue();
    }

    @Test
    void requestRenderTriggersOneShotWhenDetached() throws Exception {
        TestView view = onFx(() -> {
            TestView v = new TestView(Color.BLACK, false);
            v.resize(64, 64);
            v.layout();
            return v;
        });
        try {
            // Drain any renders triggered by the resize/layout pulse.
            drainFx();
            int before = view.renderCount.get();
            onFxRun(view::pingRender);
            assertThat(view.renderCount.get())
                    .as("scene-null requestRender must trigger exactly one render")
                    .isEqualTo(before + 1);

            // Repeated calls still render — the helper is not coalescing.
            onFxRun(view::pingRender);
            assertThat(view.renderCount.get()).isEqualTo(before + 2);
        } finally {
            onFxRun(view::dispose);
        }
    }

    @Test
    void requestRenderIsNoOpWhenAttachedToScene() throws Exception {
        TestView view = onFx(() -> {
            TestView v = new TestView(Color.BLACK, false);
            v.resize(64, 64);
            new Scene(new Group(v), 64, 64);
            v.layout();
            return v;
        });
        try {
            // Now drain — once attached, requestRender() must NOT call the
            // canvas (the AnimationTimer is responsible when animated, or
            // the caller is expected to refresh via gpuCanvas() directly).
            drainFx();
            int before = view.renderCount.get();
            onFxRun(view::pingRender);
            onFxRun(view::pingRender);
            onFxRun(view::pingRender);
            drainFx();
            assertThat(view.renderCount.get())
                    .as("scene-attached requestRender must be a no-op")
                    .isEqualTo(before);
        } finally {
            onFxRun(view::dispose);
        }
    }

    @Test
    void subclassReceivesSameRenderContextAsBareGpuCanvasHost() throws Exception {
        // Smoke: a GpuCanvasView subclass routes the per-frame
        // GpuRenderContext through to its renderer with the same .gc(),
        // .pixels(), .width(), .height() semantics as a bare GpuCanvas host.
        AtomicReference<GpuRenderContext> viewCtx = new AtomicReference<>();
        AtomicReference<GpuRenderContext> bareCtx = new AtomicReference<>();

        TestView view = onFx(() -> {
            TestView v = new TestView(Color.BLACK, false);
            v.resize(80, 60);
            v.layout();
            return v;
        });
        GpuCanvas bare = onFx(() -> {
            GpuRenderer r = bareCtx::set;
            GpuCanvas c = GpuCanvas.create()
                    .renderer(r)
                    .clearColor(Color.BLACK)
                    .animated(false)
                    .build();
            c.resize(80, 60);
            return c;
        });

        try {
            // Capture one ctx from each via an explicit requestRender (both
            // are scene-detached so requestRender drives a single frame).
            onFxRun(() -> { view.replaceRenderer(viewCtx::set); view.canvas().requestRender(); });
            onFxRun(bare::requestRender);

            GpuRenderContext vc = viewCtx.get();
            GpuRenderContext bc = bareCtx.get();
            assertThat(vc).isNotNull();
            assertThat(bc).isNotNull();
            assertThat(vc.width()).isEqualTo(bc.width());
            assertThat(vc.height()).isEqualTo(bc.height());
            assertThat(vc.gc()).isInstanceOf(GraphicsContext.class);
            assertThat(bc.gc()).isInstanceOf(GraphicsContext.class);
            MemorySegment vp = vc.pixels();
            MemorySegment bp = bc.pixels();
            assertThat(vp).isNotNull();
            assertThat(bp).isNotNull();
            // Surfaces are independent allocations, but the byte layout
            // (BGRA, stride == width * 4) must match.
            assertThat(vp.byteSize()).isEqualTo(bp.byteSize());
        } finally {
            onFxRun(view::dispose);
            onFxRun(bare::dispose);
        }
    }
}
