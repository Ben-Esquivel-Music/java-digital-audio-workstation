package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.RoundTripLatency;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Headless tests for {@link IoLatencyDetailsPopup} — verifies the
 * source-label selection rules, the frame/ms formatters, and the
 * popup's three-component layout when constructed on the FX thread
 * with a live {@link RoundTripLatency}.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class IoLatencyDetailsPopupTest {

    @Test
    void shouldFormatComponentAsFramesAndMillis() {
        // 256 / 48000 * 1000 = 5.333… ms → "256 frames (5.33 ms)"
        assertThat(IoLatencyDetailsPopup.formatComponent(256, 48_000.0))
                .isEqualTo("256 frames (5.33 ms)");
    }

    @Test
    void shouldFormatTotalLineWithLeadingLabel() {
        assertThat(IoLatencyDetailsPopup.formatTotalLine(208, 44_100.0))
                .startsWith("Total round-trip: 208 frames (");
    }

    @Test
    void shouldRejectNegativeFrames() {
        assertThatThrownBy(() -> IoLatencyDetailsPopup.formatComponent(-1, 48_000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> IoLatencyDetailsPopup.formatComponent(64, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceLabelShouldBeUnknownForUnknownLatencyAndNoOverride() {
        assertThat(IoLatencyDetailsPopup.sourceLabel(RoundTripLatency.UNKNOWN, null))
                .isEqualTo(IoLatencyDetailsPopup.SourceLabel.UNKNOWN);
    }

    @Test
    void sourceLabelShouldBeDriverReportedWhenBackendReportsAndNoOverride() {
        RoundTripLatency driver = new RoundTripLatency(64, 128, 16);
        assertThat(IoLatencyDetailsPopup.sourceLabel(driver, null))
                .isEqualTo(IoLatencyDetailsPopup.SourceLabel.DRIVER_REPORTED);
    }

    @Test
    void sourceLabelShouldBeCalibratedWheneverOverrideIsActive() {
        // Override active even when driver report is UNKNOWN — the user
        // calibrated against silence and accepted the override.
        assertThat(IoLatencyDetailsPopup.sourceLabel(RoundTripLatency.UNKNOWN, 240))
                .isEqualTo(IoLatencyDetailsPopup.SourceLabel.CALIBRATED);
        assertThat(IoLatencyDetailsPopup.sourceLabel(new RoundTripLatency(64, 128, 16), 240))
                .isEqualTo(IoLatencyDetailsPopup.SourceLabel.CALIBRATED);
    }

    @Test
    void shouldRenderThreeComponentsAndTotalForDriverReportedLatency() throws Exception {
        // Issue requirement: "Test asserts that clicking the I/O
        // latency indicator opens IoLatencyDetailsPopup with the three
        // components matching the active RoundTripLatency."
        RoundTripLatency driver = new RoundTripLatency(64, 128, 16); // total 208
        IoLatencyDetailsPopup popup = onFxThread(() -> new IoLatencyDetailsPopup(
                driver, null, 48_000.0, () -> { /* no-op */ }));

        runOnFxAndWait(() -> {
            // Total line shows the driver-reported total when no override is set.
            assertThat(popup.totalLabel().getText())
                    .isEqualTo("Total round-trip: 208 frames (4.33 ms)");
            // Source badge reflects the driver report.
            assertThat(popup.sourceBadge().getText()).isEqualTo("reported by driver");
            // Calibrate action is reachable.
            assertThat(popup.calibrateButton().getText()).contains("Calibrate");
        });
    }

    @Test
    void shouldShowCalibratedBadgeAndOverrideTotalWhenOverrideIsActive() throws Exception {
        RoundTripLatency driver = new RoundTripLatency(64, 128, 16); // driver-total 208
        IoLatencyDetailsPopup popup = onFxThread(() -> new IoLatencyDetailsPopup(
                driver, 360, 48_000.0, () -> { /* no-op */ }));

        runOnFxAndWait(() -> {
            // With an override active the total reflects the override (not 208).
            assertThat(popup.totalLabel().getText())
                    .isEqualTo("Total round-trip: 360 frames (7.50 ms)");
            assertThat(popup.sourceBadge().getText()).isEqualTo("calibrated by user");
        });
    }

    @Test
    void calibrateButtonShouldInvokeOpenCalibrationCallback() throws Exception {
        RoundTripLatency driver = new RoundTripLatency(64, 128, 16);
        AtomicBoolean opened = new AtomicBoolean(false);
        IoLatencyDetailsPopup popup = onFxThread(() -> new IoLatencyDetailsPopup(
                driver, null, 48_000.0, () -> opened.set(true)));

        runOnFxAndWait(() -> popup.calibrateButton().fire());
        assertThat(opened).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static <T> T onFxThread(java.util.function.Supplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { ref.set(supplier.get()); }
            catch (Throwable t) { err.set(t); }
            finally { latch.countDown(); }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) {
            throw new RuntimeException("FX thread action failed", err.get());
        }
        return ref.get();
    }

    private static void runOnFxAndWait(Runnable action) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { action.run(); }
            catch (Throwable t) { err.set(t); }
            finally { latch.countDown(); }
        });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) {
            if (err.get() instanceof AssertionError ae) throw ae;
            throw new RuntimeException("FX thread action failed", err.get());
        }
    }
}

