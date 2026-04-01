package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomTelemetryData;
import com.benesquivelmusic.daw.sdk.telemetry.SoundWavePath;

import javafx.application.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RoomTelemetryDisplayTest {

    private static boolean toolkitAvailable;
    private static final double AUDIENCE_RADIUS = 7.0;
    private static final double LABEL_OFFSET = 18.0;
    private static final double AUDIENCE_LABEL_STAGGER = 12.0;

    @BeforeAll
    static void initToolkit() throws Exception {
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized
        } catch (UnsupportedOperationException ignored) {
            // No display available (headless CI environment)
            return;
        }
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) {
            }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

    private RoomTelemetryDisplay createOnFxThread() throws Exception {
        AtomicReference<RoomTelemetryDisplay> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new RoomTelemetryDisplay());
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX thread task should complete within timeout")
                .isTrue();
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX thread task should complete within timeout")
                .isTrue();
    }

    @Test
    void evenIndexShouldPlaceLabelBelowSilhouette() {
        double cy = 200.0;
        double labelY = RoomTelemetryDisplay.computeAudienceLabelY(cy, 0);

        assertThat(labelY).isEqualTo(cy + AUDIENCE_RADIUS + LABEL_OFFSET);
    }

    @Test
    void oddIndexShouldPlaceLabelAboveSilhouette() {
        double cy = 200.0;
        double labelY = RoomTelemetryDisplay.computeAudienceLabelY(cy, 1);

        assertThat(labelY).isEqualTo(cy - AUDIENCE_RADIUS - AUDIENCE_LABEL_STAGGER);
    }

    @Test
    void shouldAlternateLabelPositionsForConsecutiveMembers() {
        double cy = 150.0;

        double label0 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 0);
        double label1 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 1);
        double label2 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 2);
        double label3 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 3);

        // Even indices go below, odd indices go above
        assertThat(label0).isGreaterThan(cy);
        assertThat(label1).isLessThan(cy);
        assertThat(label2).isGreaterThan(cy);
        assertThat(label3).isLessThan(cy);

        // Same-parity labels should be at the same offset from center
        assertThat(label0).isEqualTo(label2);
        assertThat(label1).isEqualTo(label3);
    }

    @Test
    void labelsShouldBeDistinctForAdjacentIndices() {
        double cy = 100.0;

        double labelBelow = RoomTelemetryDisplay.computeAudienceLabelY(cy, 0);
        double labelAbove = RoomTelemetryDisplay.computeAudienceLabelY(cy, 1);

        // The stagger should create meaningful vertical separation
        assertThat(Math.abs(labelBelow - labelAbove))
                .isGreaterThan(AUDIENCE_RADIUS * 2);
    }

    @Test
    void shouldHandleLargeIndex() {
        double cy = 300.0;

        double label100 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 100);
        double label101 = RoomTelemetryDisplay.computeAudienceLabelY(cy, 101);

        // Even index → below
        assertThat(label100).isGreaterThan(cy);
        // Odd index → above
        assertThat(label101).isLessThan(cy);
    }

    // ── Drag callback setters ──────────────────────────────────────

    @Test
    void shouldAcceptSourceDragCallback() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        RoomTelemetryDisplay display = createOnFxThread();

        AtomicReference<String> capturedName = new AtomicReference<>();
        AtomicReference<Position3D> capturedPos = new AtomicReference<>();

        runOnFxThread(() -> display.setOnSourceDragged((name, pos) -> {
            capturedName.set(name);
            capturedPos.set(pos);
        }));

        assertThat(display.getOnSourceDragged()).isNotNull();
    }

    @Test
    void shouldAcceptMicDragCallback() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        RoomTelemetryDisplay display = createOnFxThread();

        runOnFxThread(() -> display.setOnMicDragged((name, pos) -> {}));

        assertThat(display.getOnMicDragged()).isNotNull();
    }

    @Test
    void dragCallbacksShouldBeNullByDefault() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        RoomTelemetryDisplay display = createOnFxThread();

        // When no callback is set, the getter returns null
        // (constructor doesn't set callbacks)
        // Note: When used inside TelemetryView, callbacks ARE set
        // This test verifies standalone construction
        assertThat(display.getOnSourceDragged()).isNull();
        assertThat(display.getOnMicDragged()).isNull();
    }

    @Test
    void telemetryDataShouldBeNullInitially() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        RoomTelemetryDisplay display = createOnFxThread();

        assertThat(display.getTelemetryData()).isNull();
    }

    // ── 3D Isometric Projection Tests ──────────────────────────────

    @Test
    void projectAndUnprojectShouldRoundTripRoomCenter() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        RoomTelemetryDisplay display = createOnFxThread();

        // Simulate a render with known telemetry data to populate cached transform
        RoomDimensions dims = new RoomDimensions(10.0, 10.0, 3.0);
        SoundWavePath path = new SoundWavePath(
                "S", "M",
                List.of(new Position3D(1, 1, 1), new Position3D(5, 5, 1)),
                5.0, 0.01, -3.0, false);
        RoomTelemetryData data = RoomTelemetryData.withoutAudience(
                dims, List.of(path), 0.5, List.of());

        runOnFxThread(() -> {
            display.setPrefSize(800, 600);
            display.resize(800, 600);
            display.setTelemetryData(data);
        });

        // Round-trip the room center (5,5,0): projectToScreen followed by
        // unprojectFromScreen should give back the original point.
        double[] screen = display.projectToScreen(5.0, 5.0, 0.0);
        Position3D roundTrip = display.unprojectFromScreen(screen[0], screen[1], 0.0);

        assertThat(roundTrip.x()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(roundTrip.y()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(roundTrip.z()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void unprojectShouldInverseProject() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        RoomTelemetryDisplay display = createOnFxThread();

        RoomDimensions dims = new RoomDimensions(12.0, 8.0, 4.0);
        SoundWavePath path = new SoundWavePath(
                "S", "M",
                List.of(new Position3D(2, 3, 1.5), new Position3D(8, 6, 1.5)),
                7.0, 0.02, -5.0, false);
        RoomTelemetryData data = RoomTelemetryData.withoutAudience(
                dims, List.of(path), 0.4, List.of());

        runOnFxThread(() -> {
            display.setPrefSize(1024, 768);
            display.resize(1024, 768);
            display.setTelemetryData(data);
        });

        // Test multiple points at different heights
        double[][] testPoints = {
                {0, 0, 0}, {12, 0, 0}, {0, 8, 0}, {12, 8, 0},
                {6, 4, 2}, {3, 7, 1}, {10, 2, 3.5}
        };

        for (double[] pt : testPoints) {
            double[] screen = display.projectToScreen(pt[0], pt[1], pt[2]);
            Position3D roundTrip = display.unprojectFromScreen(screen[0], screen[1], pt[2]);

            assertThat(roundTrip.x())
                    .as("x for point (%.1f, %.1f, %.1f)", pt[0], pt[1], pt[2])
                    .isCloseTo(pt[0], org.assertj.core.data.Offset.offset(0.001));
            assertThat(roundTrip.y())
                    .as("y for point (%.1f, %.1f, %.1f)", pt[0], pt[1], pt[2])
                    .isCloseTo(pt[1], org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Test
    void projectShouldPreserveIsometricProperties() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        RoomTelemetryDisplay display = createOnFxThread();

        RoomDimensions dims = new RoomDimensions(10.0, 10.0, 3.0);
        SoundWavePath path = new SoundWavePath(
                "S", "M",
                List.of(new Position3D(1, 1, 1), new Position3D(5, 5, 1)),
                5.0, 0.01, -3.0, false);
        RoomTelemetryData data = RoomTelemetryData.withoutAudience(
                dims, List.of(path), 0.5, List.of());

        runOnFxThread(() -> {
            display.setPrefSize(800, 600);
            display.resize(800, 600);
            display.setTelemetryData(data);
        });

        // In isometric, increasing Z should move the point upward on screen (lower screenY)
        double[] atFloor = display.projectToScreen(5, 5, 0);
        double[] atHeight = display.projectToScreen(5, 5, 2);

        assertThat(atHeight[1]).isLessThan(atFloor[1])
                .as("Higher Z should produce smaller screen Y (higher on screen)");

        // X should increase screenX to the right
        double[] left = display.projectToScreen(2, 5, 0);
        double[] right = display.projectToScreen(8, 5, 0);

        assertThat(right[0]).isGreaterThan(left[0])
                .as("Larger X should produce larger screen X (further right)");

        // Y should decrease screenX (goes to the left in isometric)
        double[] front = display.projectToScreen(5, 2, 0);
        double[] back = display.projectToScreen(5, 8, 0);

        assertThat(back[0]).isLessThan(front[0])
                .as("Larger Y should produce smaller screen X (further left in isometric)");
    }
}
