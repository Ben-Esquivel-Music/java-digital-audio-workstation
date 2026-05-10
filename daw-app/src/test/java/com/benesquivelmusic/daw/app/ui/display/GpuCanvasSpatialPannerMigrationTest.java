package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.spatial.SpatialTrajectoryOverlay;
import com.benesquivelmusic.daw.core.automation.AutomationData;
import com.benesquivelmusic.daw.core.automation.AutomationLane;
import com.benesquivelmusic.daw.core.automation.ObjectParameterTarget;
import com.benesquivelmusic.daw.core.spatial.panner.VbapPanner;
import com.benesquivelmusic.daw.sdk.spatial.ObjectParameter;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPannerData;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPosition;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Migration tests for {@link SpatialPannerDisplay} after the substrate swap
 * to {@link com.benesquivelmusic.daw.fx.GpuCanvas} (story 250).
 *
 * <p>Mounts the panner without a Stage — the GpuCanvas timer activates on
 * Scene attachment alone — and exercises the public API to assert frame
 * counter, animated-state gating, and that the static coordinate-mapping
 * helpers used by {@link SpatialTrajectoryOverlay} are unaffected by the
 * swap.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class GpuCanvasSpatialPannerMigrationTest {

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

    /** Mounts a node into a headless Scene (no Stage) to engage the GpuCanvas timer. */
    private static Scene mount(javafx.scene.Node node) {
        return new Scene(new Group(node), 400, 300);
    }

    // ── Composition + behaviour-preserving render ──────────────────────────

    @Test
    void shouldComposeGpuCanvasAndExposeIt() throws Exception {
        SpatialPannerDisplay display = onFx(() -> {
            SpatialPannerDisplay d = new SpatialPannerDisplay();
            d.setPrefSize(400, 300);
            d.resize(400, 300);
            d.getGpuCanvas().resize(400, 300);
            mount(d);
            return d;
        });
        try {
            assertThat(display.getGpuCanvas())
                    .as("display should compose a GpuCanvas")
                    .isNotNull();
            // Static dot when transport stopped — animated must default to false
            // so the pulse only runs while object-panner automation is playing.
            assertThat(display.getGpuCanvas().isAnimated()).isFalse();
            assertThat(display.isPlaying()).isFalse();
        } finally {
            onFxRun(display::dispose);
        }
    }

    @Test
    void updateShouldRequestOneShotRenderWhenStopped() throws Exception {
        // Layout used by Atmos automation passes (7.1.4 = 12 speakers).
        VbapPanner panner = defaultPanner(SpeakerLayout.LAYOUT_7_1_4);
        SpatialPannerDisplay display = onFx(() -> {
            SpatialPannerDisplay d = new SpatialPannerDisplay();
            d.setPrefSize(400, 300);
            d.resize(400, 300);
            d.getGpuCanvas().resize(400, 300);
            mount(d);
            return d;
        });
        try {
            long before = display.getGpuCanvas().getFrameCount();
            // Pin to a deterministic position so the dot snaps to a known projection.
            panner.setPosition(SpatialPosition.fromCartesian(0.5, 0.5, 0.0));
            SpatialPannerData data = panner.getPannerData();
            onFxRun(() -> display.update(data));
            assertThat(display.getGpuCanvas().getFrameCount())
                    .as("update() while stopped should trigger a one-shot render")
                    .isGreaterThan(before);
            assertThat(display.getPannerData()).isSameAs(data);
        } finally {
            onFxRun(display::dispose);
        }
    }

    // ── setPlaying() drives setAnimated(true/false) ────────────────────────

    @Test
    void setPlayingShouldEngageGpuCanvasAnimationWhilePlaying() throws Exception {
        SpatialPannerDisplay display = onFx(() -> {
            SpatialPannerDisplay d = new SpatialPannerDisplay();
            d.setPrefSize(400, 300);
            d.resize(400, 300);
            d.getGpuCanvas().resize(400, 300);
            mount(d);
            return d;
        });
        try {
            // Engage playback — flip the AnimationTimer on. The GpuCanvas timer
            // is also gated on Scene attachment which we have via mount().
            onFxRun(() -> display.setPlaying(true));
            assertThat(display.isPlaying()).isTrue();
            assertThat(display.getGpuCanvas().isAnimated()).isTrue();

            // Wait briefly for at least a couple of FX pulses to fire.
            long start = display.getGpuCanvas().getFrameCount();
            long deadline = System.currentTimeMillis() + 1_500L;
            while (display.getGpuCanvas().getFrameCount() <= start
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            long playingFrames = display.getGpuCanvas().getFrameCount();
            assertThat(playingFrames)
                    .as("frame counter should advance while playing")
                    .isGreaterThan(start);

            // Stop transport — the timer should disengage and the counter freeze.
            onFxRun(() -> display.setPlaying(false));
            assertThat(display.getGpuCanvas().isAnimated()).isFalse();
            // Drain one FX pulse so any in-flight timer.handle has completed.
            onFxRun(() -> { });
            long stoppedFrames = display.getGpuCanvas().getFrameCount();
            // Allow at most one frame to slip through the stop transition.
            Thread.sleep(150L);
            assertThat(display.getGpuCanvas().getFrameCount() - stoppedFrames)
                    .as("frame counter should freeze within one frame of transport stop")
                    .isLessThanOrEqualTo(1L);
        } finally {
            onFxRun(display::dispose);
        }
    }

    // ── SpatialTrajectoryOverlay still tracks the panner's coordinate system ──

    @Test
    void trajectoryOverlayShouldStillTrackPannerCoordinateSystem() {
        // Coordinate mapping is a pure static API — the substrate swap to
        // GpuCanvas does not touch it. SpatialTrajectoryOverlay reads from
        // these helpers (via the pannerData snapshot), so a round-trip
        // through the same helpers proves the overlay continues to composite
        // against the same coordinate transform.
        double cx = 200.0;
        double cy = 150.0;
        double r = 100.0;
        for (double v = -1.0; v <= 1.0; v += 0.25) {
            // Normalised [-1, 1] projects to the same pixel coordinates
            // whether the parent owns a raw Canvas or a GpuCanvas.
            double px = SpatialPannerDisplay.spatialXToPixelX(v, cx, r);
            double py = SpatialPannerDisplay.spatialYToPixelY(v, cy, r);
            double pz = SpatialPannerDisplay.spatialZToPixelY(v, cy, r);

            assertThat(SpatialPannerDisplay.pixelXToSpatialX(px, cx, r))
                    .isCloseTo(v, within(1e-9));
            assertThat(SpatialPannerDisplay.pixelYToSpatialY(py, cy, r))
                    .isCloseTo(v, within(1e-9));
            assertThat(SpatialPannerDisplay.pixelYToSpatialZ(pz, cy, r))
                    .isCloseTo(v, within(1e-9));
        }
    }

    @Test
    void trajectoryOverlaySamplesShouldProjectOntoSamePixelsAsTheDot() throws Exception {
        // Build a minimal automation context with X/Y/Z lanes, then prove
        // that SpatialTrajectoryOverlay's sampled (x, y, z) at t=0 projects
        // through SpatialPannerDisplay's coordinate helpers to the same
        // pixel as the panner's source dot — i.e. the overlay continues to
        // composite onto the same coordinate system after the swap.
        AutomationData automationData = new AutomationData();
        String objectId = "panner-1";
        automationData.getOrCreateObjectLane(
                new ObjectParameterTarget(objectId, ObjectParameter.X));
        automationData.getOrCreateObjectLane(
                new ObjectParameterTarget(objectId, ObjectParameter.Y));
        automationData.getOrCreateObjectLane(
                new ObjectParameterTarget(objectId, ObjectParameter.Z));
        AutomationLane xLane = automationData.getObjectLane(
                new ObjectParameterTarget(objectId, ObjectParameter.X));
        AutomationLane yLane = automationData.getObjectLane(
                new ObjectParameterTarget(objectId, ObjectParameter.Y));
        AutomationLane zLane = automationData.getObjectLane(
                new ObjectParameterTarget(objectId, ObjectParameter.Z));
        // Pin a deterministic source position — same value at t=0 and t=4.
        xLane.addPoint(new com.benesquivelmusic.daw.core.automation.AutomationPoint(0.0, 0.5));
        xLane.addPoint(new com.benesquivelmusic.daw.core.automation.AutomationPoint(4.0, 0.5));
        yLane.addPoint(new com.benesquivelmusic.daw.core.automation.AutomationPoint(0.0, 0.25));
        yLane.addPoint(new com.benesquivelmusic.daw.core.automation.AutomationPoint(4.0, 0.25));
        zLane.addPoint(new com.benesquivelmusic.daw.core.automation.AutomationPoint(0.0, 0.0));
        zLane.addPoint(new com.benesquivelmusic.daw.core.automation.AutomationPoint(4.0, 0.0));

        SpatialTrajectoryOverlay overlay =
                new SpatialTrajectoryOverlay(automationData, objectId, 4.0);
        var samples = overlay.sampleFuture(0.0);
        assertThat(samples).isNotEmpty();
        var first = samples.get(0);
        assertThat(first.x()).isCloseTo(0.5, within(1e-9));
        assertThat(first.y()).isCloseTo(0.25, within(1e-9));

        // Project through the same helpers the dot uses — overlay tracks the
        // shared coordinate transform regardless of the canvas substrate.
        double cx = 200.0, cy = 150.0, r = 100.0;
        // The overlay normalises to [-1, 1] units; the display's pixel
        // helpers expect spatial meters, so scale by MAX_DISPLAY_DISTANCE.
        double sx = first.x() * SpatialPannerDisplay.MAX_DISPLAY_DISTANCE;
        double sy = first.y() * SpatialPannerDisplay.MAX_DISPLAY_DISTANCE;
        double pixelX = SpatialPannerDisplay.spatialXToPixelX(sx, cx, r);
        double pixelY = SpatialPannerDisplay.spatialYToPixelY(sy, cy, r);
        // Dot at (sx, sy) projects to the same pixel.
        assertThat(pixelX).isCloseTo(cx + 0.5 * r, within(1e-9));
        assertThat(pixelY).isCloseTo(cy - 0.25 * r, within(1e-9));
    }

    // ── Lifecycle: dispose() is idempotent and stops the timer ─────────────

    @Test
    void disposeShouldStopTimerAndBeIdempotent() throws Exception {
        SpatialPannerDisplay display = onFx(() -> {
            SpatialPannerDisplay d = new SpatialPannerDisplay();
            d.setPrefSize(400, 300);
            d.resize(400, 300);
            d.getGpuCanvas().resize(400, 300);
            mount(d);
            return d;
        });
        onFxRun(() -> display.setPlaying(true));
        assertThat(display.getGpuCanvas().isAnimated()).isTrue();
        onFxRun(display::dispose);
        // dispose() must flip animated off and tolerate repeat calls.
        assertThat(display.getGpuCanvas().isAnimated()).isFalse();
        onFxRun(display::dispose);
        // setPlaying() after dispose() is a no-op (no exception, no animation).
        onFxRun(() -> display.setPlaying(true));
        assertThat(display.getGpuCanvas().isAnimated()).isFalse();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static VbapPanner defaultPanner(SpeakerLayout layout) {
        List<SpatialPosition> positions = new java.util.ArrayList<>();
        for (var label : layout.speakers()) {
            positions.add(label.toSpatialPosition());
        }
        return new VbapPanner(positions);
    }
}
