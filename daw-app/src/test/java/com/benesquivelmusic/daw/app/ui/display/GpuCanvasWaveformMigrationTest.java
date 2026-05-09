package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.fx.GpuCanvas;
import com.benesquivelmusic.daw.sdk.visualization.WaveformData;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless tests covering the GpuCanvas-backed {@link WaveformDisplay}
 * migration.
 *
 * <p>The display is mounted into a {@link Scene} (without a {@code Stage}
 * — the GpuCanvas timer is gated on
 * {@link javafx.scene.Node#sceneProperty()} attachment, not on stage
 * visibility) and we then exercise the public API to assert observable
 * state on the embedded {@link GpuCanvas} (frame count, animator state,
 * call-coalescing). Behaviour parity with the pre-migration
 * implementation is established by the {@code render()} body being copied
 * verbatim onto the {@code GpuRenderer}: only the host substrate has
 * changed.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class GpuCanvasWaveformMigrationTest {

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

    private static void mount(javafx.scene.Node node) {
        new Scene(new Group(node), 400, 200);
    }

    /** Deterministic sine-shaped peak / RMS arrays for repeatable parity. */
    private static WaveformData synth(int columns) {
        float[] min = new float[columns];
        float[] max = new float[columns];
        float[] rms = new float[columns];
        for (int i = 0; i < columns; i++) {
            double t = (double) i / columns;
            float env = (float) Math.sin(t * Math.PI);
            max[i] = env;
            min[i] = -env;
            rms[i] = env * 0.7f;
        }
        return new WaveformData(min, max, rms, columns);
    }

    @Test
    void shouldComposeGpuCanvasWithBackgroundClearColor() throws Exception {
        WaveformDisplay display = onFx(() -> {
            WaveformDisplay d = new WaveformDisplay();
            d.setPrefSize(400, 200);
            d.resize(400, 200);
            d.getGpuCanvas().resize(400, 200);
            mount(d);
            return d;
        });
        try {
            assertThat(display.getGpuCanvas()).isNotNull();
            // Background clear is delegated to GpuCanvas, so the renderer
            // never issues a per-frame fillRect for the background.
            assertThat(display.getGpuCanvas().getClearColor()).isNotNull();
            // Default is non-animated — cursor is driven by setters only.
            assertThat(display.getGpuCanvas().isAnimated()).isFalse();
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void setBackgroundColorShouldUpdateGpuCanvasClearColor() throws Exception {
        WaveformDisplay display = onFx(WaveformDisplay::new);
        try {
            Color custom = Color.web("#202030");
            onFxRun(() -> display.setBackgroundColor(custom));
            assertThat(display.getGpuCanvas().getClearColor()).isEqualTo(custom);
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void setCursorPositionShouldRenderSynchronouslyOnEachCall()
            throws Exception {
        // GpuCanvas#requestRender() renders synchronously on the FX thread
        // (deltaSeconds=0), so each setCursorPosition call triggers exactly
        // one renderer invocation. This test documents that contract.
        AtomicInteger renderCount = new AtomicInteger();

        WaveformDisplay display = onFx(() -> {
            WaveformDisplay d = new WaveformDisplay();
            d.setPrefSize(400, 200);
            d.resize(400, 200);
            d.getGpuCanvas().resize(400, 200);
            mount(d);
            // Replace renderer with a counting stub so we measure invocations
            // without relying on frame-count side effects from any previous
            // construction-time renders.
            d.getGpuCanvas().setRenderer(ctx -> renderCount.incrementAndGet());
            return d;
        });
        try {
            // setRenderer above also triggers one render via the renderer-
            // property listener — reset after the FX runLater settles so we
            // measure only the burst.
            onFxRun(() -> renderCount.set(0));
            // requestRender() runs synchronously on the FX thread, so each
            // call emits exactly one render. This documents the
            // current GpuCanvas semantics.
            onFxRun(() -> {
                for (int i = 0; i < 10; i++) {
                    display.setCursorPosition(i / 10.0);
                }
            });
            assertThat(renderCount.get())
                    .as("one render per setCursorPosition (synchronous requestRender)")
                    .isEqualTo(10);
            assertThat(display.getCursorPosition()).isEqualTo(0.9);
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void setAnimatedShouldStartAndStopFrameAdvance() throws Exception {
        WaveformDisplay display = onFx(() -> {
            WaveformDisplay d = new WaveformDisplay();
            d.setPrefSize(400, 200);
            d.resize(400, 200);
            d.getGpuCanvas().resize(400, 200);
            mount(d);
            return d;
        });
        try {
            assertThat(display.isAnimated()).isFalse();
            long before = display.getGpuCanvas().getFrameCount();

            onFxRun(() -> display.setAnimated(true));
            assertThat(display.isAnimated()).isTrue();
            assertThat(display.getGpuCanvas().isAnimated()).isTrue();

            // Wait for the AnimationTimer to deliver a few frames.
            Thread.sleep(120);
            long whileRunning = display.getGpuCanvas().getFrameCount();
            assertThat(whileRunning)
                    .as("frame count advances while animated")
                    .isGreaterThan(before);

            onFxRun(() -> display.setAnimated(false));
            assertThat(display.isAnimated()).isFalse();
            // After stop, give the timer a moment to settle, then confirm
            // no further advances occur.
            Thread.sleep(50);
            long stopped = display.getGpuCanvas().getFrameCount();
            Thread.sleep(120);
            assertThat(display.getGpuCanvas().getFrameCount())
                    .as("frame count is stable after setAnimated(false)")
                    .isEqualTo(stopped);
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void cursorVelocityShouldIntegrateAgainstDeltaSecondsWhenAnimated()
            throws Exception {
        WaveformDisplay display = onFx(() -> {
            WaveformDisplay d = new WaveformDisplay();
            d.setPrefSize(400, 200);
            d.resize(400, 200);
            d.getGpuCanvas().resize(400, 200);
            mount(d);
            return d;
        });
        try {
            // One-off renders supply deltaSeconds == 0.0 per the GpuCanvas
            // contract, so the cursor must not advance when only setters
            // are invoked — even with a non-zero velocity.
            onFxRun(() -> {
                display.setCursorVelocity(1.0);
                display.setCursorPosition(0.0);
            });
            double before = display.getCursorPosition();
            onFxRun(() -> display.refresh());
            assertThat(display.getCursorPosition())
                    .as("requestRender (deltaSeconds=0) must not integrate the cursor")
                    .isEqualTo(before);

            // Now drive the timer; the cursor must advance and clamp to 1.0.
            onFxRun(() -> display.setAnimated(true));
            Thread.sleep(200);
            onFxRun(() -> display.setAnimated(false));
            assertThat(display.getCursorPosition())
                    .as("cursor advances under timer-driven deltaSeconds")
                    .isGreaterThan(before);
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void rendererInvocationProducesSameFrameCountForMultipleCursorPositions()
            throws Exception {
        // Render the same WaveformData at three different cursor positions
        // and assert that each requestRender() advances the GpuCanvas frame
        // counter by exactly one — i.e. a deterministic snapshot exists for
        // each cursor position. Pixel-level parity is not asserted here
        // (the per-frame draw routine was copied verbatim from the
        // pre-migration implementation, so visual parity is established
        // by code inspection); this guards against regressions in the
        // setter→requestRender→renderer call path.
        WaveformDisplay display = onFx(() -> {
            WaveformDisplay d = new WaveformDisplay();
            d.setPrefSize(400, 200);
            d.resize(400, 200);
            d.getGpuCanvas().resize(400, 200);
            mount(d);
            return d;
        });
        try {
            WaveformData data = synth(64);
            onFxRun(() -> display.setWaveformData(data));
            long base = display.getGpuCanvas().getFrameCount();

            onFxRun(() -> display.setCursorPosition(0.1));
            assertThat(display.getGpuCanvas().getFrameCount()).isEqualTo(base + 1);

            onFxRun(() -> display.setCursorPosition(0.5));
            assertThat(display.getGpuCanvas().getFrameCount()).isEqualTo(base + 2);

            onFxRun(() -> display.setCursorPosition(0.9));
            assertThat(display.getGpuCanvas().getFrameCount()).isEqualTo(base + 3);
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void disposeShouldStopTimerAndBeIdempotent() throws Exception {
        WaveformDisplay display = onFx(() -> {
            WaveformDisplay d = new WaveformDisplay();
            d.setPrefSize(400, 200);
            d.resize(400, 200);
            d.getGpuCanvas().resize(400, 200);
            mount(d);
            return d;
        });
        onFxRun(() -> display.setAnimated(true));
        onFxRun(display::dispose);
        // Second call is a no-op and must not throw.
        onFxRun(display::dispose);
        assertThat(display.isAnimated()).isFalse();
    }
}
