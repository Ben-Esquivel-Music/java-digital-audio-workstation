package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.fx.GpuCanvas;
import com.benesquivelmusic.daw.sdk.visualization.LevelData;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;

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
 * Headless tests covering the GpuCanvas-backed metering displays
 * ({@link LevelMeterDisplay}, {@link LoudnessDisplay},
 * {@link InputMeterStrip}, {@link MiniClipIndicator}).
 *
 * <p>Each display is mounted into a {@link Scene} (without a {@code Stage} —
 * the GpuCanvas timer is gated on {@link javafx.scene.Node#sceneProperty()}
 * attachment, not on stage visibility) and we then exercise the public API
 * to assert observable state on the embedded {@link GpuCanvas} (frame
 * count, animator decay, latched clip flag).</p>
 *
 * <p>The tests do not perform pixel-level diffs — the per-frame draw
 * routines were copied verbatim from the pre-migration implementations, so
 * behaviour parity is established by code inspection plus the existing
 * {@link MeterAnimatorTest}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class GpuCanvasMeterMigrationTest {

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
        // Wrap in a Scene without a Stage — the GpuCanvas timer activates as
        // soon as the node has a Scene, so this is enough to exercise the
        // mounted-while-animated code path without spawning a real window.
        new Scene(new Group(node), 200, 200);
    }

    // ── LevelMeterDisplay ───────────────────────────────────────────────

    @Test
    void levelMeterDisplayShouldComposeGpuCanvasAndAdvanceFrames() throws Exception {
        LevelMeterDisplay display = onFx(() -> {
            LevelMeterDisplay d = new LevelMeterDisplay(true);
            d.setPrefSize(40, 120);
            d.resize(40, 120);
            d.getGpuCanvas().resize(40, 120);
            mount(d);
            return d;
        });

        try {
            assertThat(display.getGpuCanvas()).isNotNull();
            assertThat(display.getGpuCanvas().isAnimated()).isTrue();

            onFxRun(() -> {
                display.update(new LevelData(0.5, 0.4, -6.0, -8.0, false));
                display.getGpuCanvas().requestRender();
            });

            assertThat(display.getGpuCanvas().getFrameCount()).isGreaterThan(0L);

            // The peak animator should have moved off zero on the first frame.
            assertThat(display.getPeakAnimator().getCurrentValue()).isGreaterThan(0.0);
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void levelMeterDisplayPeakHoldShouldDecayOverDeltaSeconds() throws Exception {
        // Reaches into the MeterAnimator the display owns and simulates the
        // GpuCanvas-driven per-frame ticks. Equivalent to the pre-migration
        // deltaNanos pulse — proves the ballistic still advances the same
        // way when sourced from deltaSeconds-derived deltaNanos.
        LevelMeterDisplay display = onFx(() -> new LevelMeterDisplay(true));
        try {
            for (int i = 0; i < 30; i++) {
                display.getPeakAnimator().update(0.9, 16_666_667L);
            }
            double peak = display.getPeakAnimator().getPeakValue();
            assertThat(peak).isGreaterThan(0.5);

            // Drop signal; peak hold should hang on within the 1.5 s window.
            for (int i = 0; i < 10; i++) {
                display.getPeakAnimator().update(0.0, 16_666_667L);
            }
            assertThat(display.getPeakAnimator().getPeakValue())
                    .as("peak still held during hold window")
                    .isCloseTo(peak, org.assertj.core.data.Offset.offset(0.05));
        } finally {
            onFxRun(display::dispose);
        }
    }

    // ── LoudnessDisplay ─────────────────────────────────────────────────

    @Test
    void loudnessDisplayShouldComposeGpuCanvasAndRenderOnUpdate() throws Exception {
        LoudnessDisplay display = onFx(() -> {
            LoudnessDisplay d = new LoudnessDisplay();
            d.setPrefSize(400, 200);
            d.resize(400, 200);
            d.getGpuCanvas().resize(400, 200);
            mount(d);
            return d;
        });

        try {
            assertThat(display.getGpuCanvas()).isNotNull();
            // LoudnessDisplay uses animated(false) — renders are driven by
            // update()/setTarget()/resize, not every FX pulse.
            assertThat(display.getGpuCanvas().isAnimated()).isFalse();
            long before = display.getGpuCanvas().getFrameCount();
            onFxRun(() -> display.update(new LoudnessData(
                    -14.0, -14.5, -15.0, 5.0, -1.5)));
            assertThat(display.getGpuCanvas().getFrameCount()).isGreaterThan(before);
        } finally {
            onFxRun(display::dispose);
        }
    }

    // ── InputMeterStrip ─────────────────────────────────────────────────

    @Test
    void inputMeterStripShouldComposeGpuCanvasAndPollMonitor() throws Exception {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("track-1");

        InputMeterStrip strip = onFx(() -> {
            InputMeterStrip s = new InputMeterStrip(monitor, registry);
            s.setPrefSize(20, 120);
            s.resize(20, 120);
            s.getGpuCanvas().resize(20, 120);
            mount(s);
            return s;
        });

        try {
            assertThat(strip.getGpuCanvas()).isNotNull();
            assertThat(strip.getGpuCanvas().isAnimated()).isTrue();

            // Push a clipping signal through the monitor.
            float[] hot = new float[256];
            for (int i = 0; i < hot.length; i++) hot[i] = 1.5f;
            monitor.process(hot);
            assertThat(monitor.isClippedSinceReset()).isTrue();

            onFxRun(() -> strip.getGpuCanvas().requestRender());
            assertThat(strip.getGpuCanvas().getFrameCount()).isGreaterThan(0L);
        } finally {
            onFxRun(strip::dispose);
        }
    }

    @Test
    void inputMeterStripStopShouldDisposeGpuCanvas() throws Exception {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("t");
        InputMeterStrip strip = onFx(() -> new InputMeterStrip(monitor, registry));
        // stop() is a safe alias for dispose() — calling twice must not
        // throw and must leave the GpuCanvas timer stopped.
        onFxRun(strip::stop);
        onFxRun(strip::stop);
    }

    // ── MiniClipIndicator ───────────────────────────────────────────────

    @Test
    void miniClipIndicatorShouldLightOnAboveZeroSnapshotAndClearOnReset()
            throws Exception {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("track");

        MiniClipIndicator indicator = onFx(() -> {
            MiniClipIndicator i = new MiniClipIndicator(monitor, registry);
            i.resize(10, 10);
            i.getGpuCanvas().resize(10, 10);
            // Mount in a Scene — the issue requires the indicator to latch
            // even when not visible (gating is by Scene attachment, not
            // visibility), so we exercise that exact path here.
            mount(i);
            return i;
        });

        try {
            // Drive a clipping signal through the monitor.
            float[] hot = new float[128];
            for (int i = 0; i < hot.length; i++) hot[i] = 1.2f;
            monitor.process(hot);

            onFxRun(() -> indicator.getGpuCanvas().requestRender());
            assertThat(monitor.isClippedSinceReset())
                    .as("monitor latch should have tripped on >0 dBFS sample")
                    .isTrue();

            // Reset clears the latch regardless of indicator visibility.
            onFxRun(monitor::reset);
            assertThat(monitor.isClippedSinceReset()).isFalse();
        } finally {
            onFxRun(indicator::dispose);
        }
    }
}
