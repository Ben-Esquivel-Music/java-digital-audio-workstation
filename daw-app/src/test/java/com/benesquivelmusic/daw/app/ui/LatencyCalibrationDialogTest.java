package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo;
import com.benesquivelmusic.daw.sdk.audio.AudioSettingsStore;
import com.benesquivelmusic.daw.sdk.audio.LatencyCalibration;
import com.benesquivelmusic.daw.sdk.audio.LatencyCalibration.CalibrationResult;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless tests for {@link LatencyCalibrationDialog}.
 *
 * <p>The issue requires:
 * <ul>
 *   <li>Driving the dialog via the existing test harness with a
 *       synthetic 208-frame round-trip (driver-reported 64 frames →
 *       delta 144 frames &gt; 64 → override offered).</li>
 *   <li>Asserting an override survives an
 *       {@link AudioSettingsStore} save/load cycle.</li>
 *   <li>Asserting calibration cannot be re-entered while one is in
 *       flight.</li>
 * </ul>
 * </p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LatencyCalibrationDialogTest {

    private static final List<AudioChannelInfo> SINGLE_INPUT = List.of(
            new AudioChannelInfo(0, "Loopback / measurement input"));

    @Test
    void shouldOfferOverrideWhenSyntheticImpulseExceedsThreshold() throws Exception {
        // Synthetic capture: a 2048-sample buffer with the impulse at
        // sample 208 — the issue's specified round-trip. Driver-reported
        // is 64 frames so delta = 144 > 64 → override should be offered.
        float[] capture = new float[2048];
        capture[208] = 1.0f;

        LatencyCalibrationDialog dialog = onFxThread(() -> new LatencyCalibrationDialog(
                SINGLE_INPUT,
                48_000.0,
                input -> LatencyCalibration.measure(capture, 64)));

        runOnFxAndWait(() -> {
            CalibrationResult r = LatencyCalibration.measure(capture, 64);
            // Skip the worker-thread path entirely so the assertions
            // run synchronously on the FX thread.
            dialog.applyResultForTest(r);
        });

        runOnFxAndWait(() -> {
            assertThat(dialog.warningLabel().isVisible()).isTrue();
            assertThat(dialog.warningLabel().getText())
                    .contains("may be off by 144 samples");
            assertThat(dialog.acceptOverrideButton().isDisabled()).isFalse();
            // User accepts override.
            dialog.clickAcceptOverrideForTest();
        });

        assertThat(dialog.acceptedOverride()).contains(208);
    }

    @Test
    void shouldNotOfferOverrideWhenDeltaIsWithinTolerance() throws Exception {
        // 80 - 64 = 16 frames < 64 → no warning, override disabled.
        float[] capture = new float[2048];
        capture[80] = 1.0f;

        LatencyCalibrationDialog dialog = onFxThread(() -> new LatencyCalibrationDialog(
                SINGLE_INPUT, 48_000.0,
                input -> LatencyCalibration.measure(capture, 64)));

        runOnFxAndWait(() -> dialog.applyResultForTest(
                LatencyCalibration.measure(capture, 64)));

        runOnFxAndWait(() -> {
            assertThat(dialog.warningLabel().isVisible()).isFalse();
            assertThat(dialog.acceptOverrideButton().isDisabled()).isTrue();
        });
    }

    @Test
    void shouldRefuseReentryWhileCalibrationIsRunning() throws Exception {
        // The runner blocks until released so we can observe the
        // re-entrant click being rejected.
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Integer> runCount = new AtomicReference<>(0);
        LatencyCalibrationDialog.CalibrationRunner blockingRunner = input -> {
            runCount.updateAndGet(n -> n + 1);
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LatencyCalibration.measure(new float[]{0f}, 0);
        };

        LatencyCalibrationDialog dialog = onFxThread(() -> new LatencyCalibrationDialog(
                SINGLE_INPUT, 48_000.0, blockingRunner));

        runOnFxAndWait(dialog::runCalibrationForTest);
        // Second click while running should be a no-op.
        runOnFxAndWait(dialog::runCalibrationForTest);

        // Release the worker so it completes; cleanup.
        release.countDown();
        Thread.sleep(50);

        assertThat(runCount.get()).isEqualTo(1);
    }

    @Test
    void overrideShouldSurviveAudioSettingsStoreSaveLoadCycle(@TempDir Path dir) throws IOException {
        AudioSettingsStore store = new AudioSettingsStore(dir.resolve("audio-settings.json"));
        String deviceKey = "ASIO|Focusrite Scarlett 4i4";
        AudioSettingsStore.Settings settings = new AudioSettingsStore.Settings(
                "ASIO", "Focusrite Scarlett 4i4", "Focusrite Scarlett 4i4",
                48_000.0, 128,
                Map.of(),
                true,
                Map.of(deviceKey, 208));
        store.save(settings);

        AudioSettingsStore.Settings loaded = store.load().orElseThrow();
        // The override the user accepted via the calibration dialog
        // round-trips intact through the persisted JSON.
        assertThat(loaded.latencyOverrideFramesByDeviceKey().get(deviceKey)).isEqualTo(208);
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
