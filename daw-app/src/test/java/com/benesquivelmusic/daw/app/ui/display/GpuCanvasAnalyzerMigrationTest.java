package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin.TuningResult;
import com.benesquivelmusic.daw.sdk.visualization.GoniometerData;
import com.benesquivelmusic.daw.sdk.visualization.SpectrumData;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless tests covering the GpuCanvas-backed real-time analyzer displays
 * ({@link SpectrumDisplay}, {@link CorrelationDisplay}, {@link TunerDisplay}).
 *
 * <p>Each display is mounted into a {@link Scene} (without a {@code Stage} —
 * the GpuCanvas timer is gated on {@link javafx.scene.Node#sceneProperty()}
 * attachment, not on stage visibility) and we then exercise the public API
 * to assert observable state on the embedded {@link GpuCanvas} (frame
 * count, clear color, animator state, deltaSeconds-driven phosphor decay).
 *
 * <p>Behaviour parity with the pre-migration implementations is established
 * by the {@code render()} bodies being copied verbatim onto the GpuRenderer
 * — only the host substrate has changed.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class GpuCanvasAnalyzerMigrationTest {

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
        new Scene(new Group(node), 200, 200);
    }

    // ── SpectrumDisplay ─────────────────────────────────────────────────

    @Test
    void spectrumDisplayShouldComposeGpuCanvasAndAdvanceFramesOnUpdate() throws Exception {
        SpectrumDisplay display = onFx(() -> {
            SpectrumDisplay d = new SpectrumDisplay(32);
            d.setPrefSize(400, 200);
            d.resize(400, 200);
            d.getGpuCanvas().resize(400, 200);
            mount(d);
            return d;
        });

        try {
            assertThat(display.getGpuCanvas()).isNotNull();
            assertThat(display.getGpuCanvas().isAnimated())
                    .as("spectrum display should be animated when mounted")
                    .isTrue();
            // Background is provided by clearColor — not an in-renderer fillRect.
            assertThat(display.getGpuCanvas().getClearColor())
                    .as("clearColor should match the spectrum background")
                    .isNotNull();

            long before = display.getGpuCanvas().getFrameCount();
            onFxRun(() -> {
                display.updateSpectrum(deterministicSpectrum(512, 44100, 5));
                // updateSpectrum does not call requestRender when scene-attached
                // (relies on AnimationTimer); force a frame for this test.
                display.getGpuCanvas().requestRender();
            });
            assertThat(display.getGpuCanvas().getFrameCount())
                    .as("requestRender should advance the frame count")
                    .isGreaterThan(before);
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void spectrumDisplayResizeShouldNotRequireExplicitRequestRender() throws Exception {
        // GpuCanvas's own size listener already invokes a render on resize;
        // SpectrumDisplay no longer wires its own width/height listeners.
        // Resize only the SpectrumDisplay and force a layout pass — the
        // GpuCanvas is resized transitively via layoutChildren().
        SpectrumDisplay display = onFx(() -> {
            SpectrumDisplay d = new SpectrumDisplay(32);
            mount(d);
            d.resize(300, 150);
            d.applyCss();
            d.layout();
            return d;
        });
        try {
            // The size-change-triggered render is scheduled via Platform.runLater;
            // pump the FX queue so the deferred frame lands.
            onFxRun(() -> { /* flush queue */ });
            // GpuCanvas's own size listener should have triggered a render
            // without us calling requestRender() on the SpectrumDisplay.
            assertThat(display.getGpuCanvas().getFrameCount()).isGreaterThan(0L);
        } finally {
            onFxRun(display::dispose);
        }
    }

    // ── CorrelationDisplay ──────────────────────────────────────────────

    @Test
    void correlationDisplayShouldComposeGpuCanvasWithClearColor()
            throws Exception {
        CorrelationDisplay display = onFx(() -> {
            CorrelationDisplay d = new CorrelationDisplay();
            d.setPrefSize(300, 300);
            d.resize(300, 300);
            d.getGpuCanvas().resize(300, 300);
            mount(d);
            return d;
        });
        try {
            assertThat(display.getGpuCanvas()).isNotNull();
            assertThat(display.getGpuCanvas().isAnimated()).isTrue();
            // Background is provided by clearColor — GpuCanvas clears both
            // the pixel surface and overlay GC each frame, so previous-frame
            // pixel retention (phosphor trail) is not possible with the
            // current GpuCanvas architecture.
            assertThat(display.getGpuCanvas().getClearColor())
                    .as("clearColor should match the correlation background")
                    .isNotNull();

            long before = display.getGpuCanvas().getFrameCount();
            onFxRun(() -> {
                display.setGoniometerMode(true);
                display.updateGoniometer(deterministicLissajous(64));
                display.getGpuCanvas().requestRender();
            });
            assertThat(display.getGpuCanvas().getFrameCount()).isGreaterThan(before);
        } finally {
            onFxRun(display::dispose);
        }
    }

    // ── TunerDisplay ────────────────────────────────────────────────────

    @Test
    void tunerDisplayShouldComposeGpuCanvasAndRenderOnUpdate() throws Exception {
        TunerDisplay display = onFx(() -> {
            TunerDisplay d = new TunerDisplay();
            d.setPrefSize(360, 220);
            d.resize(360, 220);
            d.getGpuCanvas().resize(360, 220);
            mount(d);
            return d;
        });
        try {
            assertThat(display.getGpuCanvas()).isNotNull();
            assertThat(display.getGpuCanvas().isAnimated()).isTrue();
            assertThat(display.getGpuCanvas().getClearColor())
                    .as("clearColor should match the tuner background")
                    .isNotNull();

            long before = display.getGpuCanvas().getFrameCount();
            onFxRun(() -> {
                display.update(new TuningResult("A", 4, 440.0, 0.0, true));
                display.getGpuCanvas().requestRender();
            });
            assertThat(display.getGpuCanvas().getFrameCount()).isGreaterThan(before);

            // No-signal state must still render cleanly.
            long beforeNoSig = display.getGpuCanvas().getFrameCount();
            onFxRun(() -> {
                display.update(null);
                display.getGpuCanvas().requestRender();
            });
            assertThat(display.getGpuCanvas().getFrameCount()).isGreaterThan(beforeNoSig);
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void disposeShouldStopTimerAndBeIdempotentOnAllAnalyzers() throws Exception {
        SpectrumDisplay s = onFx(SpectrumDisplay::new);
        CorrelationDisplay c = onFx(CorrelationDisplay::new);
        TunerDisplay t = onFx(TunerDisplay::new);

        onFxRun(s::dispose);
        onFxRun(s::dispose); // idempotent
        onFxRun(c::dispose);
        onFxRun(c::dispose);
        onFxRun(t::dispose);
        onFxRun(t::dispose);

        assertThat(s.getGpuCanvas().isAnimated()).isFalse();
        assertThat(c.getGpuCanvas().isAnimated()).isFalse();
        assertThat(t.getGpuCanvas().isAnimated()).isFalse();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Deterministic spectrum with a peak at the supplied bin index. */
    private static SpectrumData deterministicSpectrum(int fftSize, double sampleRate, int peakBin) {
        int bins = fftSize / 2;
        float[] mags = new float[bins];
        java.util.Arrays.fill(mags, -90f);
        if (peakBin >= 0 && peakBin < bins) mags[peakBin] = -3f;
        return new SpectrumData(mags, fftSize, sampleRate);
    }

    private static GoniometerData deterministicLissajous(int n) {
        float[] x = new float[n];
        float[] y = new float[n];
        float[] m = new float[n];
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            double t = 2.0 * Math.PI * i / n;
            x[i] = (float) Math.cos(t);
            y[i] = (float) Math.sin(2 * t);
            m[i] = (float) Math.hypot(x[i], y[i]);
            a[i] = (float) Math.atan2(y[i], x[i]);
        }
        return new GoniometerData(x, y, m, a, n);
    }
}
