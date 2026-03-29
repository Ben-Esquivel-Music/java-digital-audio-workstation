package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.telemetry.Position3D;

import javafx.application.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
}
