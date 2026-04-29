package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.FormatChangeReason;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DefaultAudioEngineController}. Uses a plain
 * {@link AudioEngine} without a native backend; only verifies the format
 * mutation path and the post-reconfigure callback.
 */
class DefaultAudioEngineControllerTest {

    @Test
    void shouldReportNoneWhenNoBackendAttached() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null);
        assertThat(controller.getActiveBackendName()).isEqualTo(AudioEngineController.BACKEND_NONE);
    }

    @Test
    void shouldIncludeJavaSoundInAvailableBackends() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null);
        assertThat(controller.getAvailableBackendNames()).contains("Java Sound");
    }

    @Test
    void shouldReturnEmptyDeviceListWhenNoBackend() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null);
        assertThat(controller.listDevices()).isEmpty();
    }

    @Test
    void shouldReturnEmptyDevicesForUnknownBackend() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null);
        assertThat(controller.listDevices("Made Up Backend")).isEmpty();
    }

    @Test
    void shouldReturnNegativeCpuLoadWhenNoMonitor() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null);
        assertThat(controller.getCpuLoadPercent()).isEqualTo(-1.0);
    }

    @Test
    void shouldApplyConfigurationUpdatingFormatAndCallback() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        AtomicInteger callbackHits = new AtomicInteger();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, callbackHits::incrementAndGet);

        AudioEngineController.Request request = new AudioEngineController.Request(
                AudioEngineController.BACKEND_NONE,
                "",
                "",
                SampleRate.HZ_48000,
                128,
                16);
        controller.applyConfiguration(request);

        AudioFormat updated = engine.getFormat();
        assertThat(updated.sampleRate()).isEqualTo(48_000.0);
        assertThat(updated.bufferSize()).isEqualTo(128);
        assertThat(updated.bitDepth()).isEqualTo(16);
        assertThat(updated.channels()).isEqualTo(AudioFormat.CD_QUALITY.channels());
        assertThat(callbackHits.get()).isEqualTo(1);
    }

    @Test
    void shouldRejectNullRequest() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null);
        assertThatThrownByApply(controller, null);
    }

    private static void assertThatThrownByApply(DefaultAudioEngineController controller,
                                                 AudioEngineController.Request req) {
        try {
            controller.applyConfiguration(req);
            assertThat(false).as("expected NullPointerException").isTrue();
        } catch (NullPointerException expected) {
            // ok
        }
    }

    // -- Hot-plug detection --------------------------------------------------

    @Test
    void shouldStartInStoppedState() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null);
        assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
    }

    @Test
    void shouldTransitionToDeviceLostWhenActiveDeviceRemoved(@TempDir Path projectRoot)
            throws InterruptedException {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        List<String> notifications = new ArrayList<>();
        IncompleteTakeStore takeStore = new IncompleteTakeStore(projectRoot);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, message -> { synchronized (notifications) { notifications.add(message); } }, takeStore);

        MockAudioBackend backend = new MockAudioBackend();
        DeviceId active = new DeviceId(MockAudioBackend.NAME, "Mock Device");
        controller.bindBackendDeviceEvents(backend, active);

        // Simulate that some audio was already captured into the take buffer.
        controller.captureRecordingFrames(new float[][]{{0.1f, 0.2f}, {0.3f, 0.4f}}, 2);
        assertThat(takeStore.bufferedByteCount()).isGreaterThan(0);

        // Yank the device.
        backend.simulateDeviceRemoved(active);

        waitFor(() -> controller.engineState() == EngineState.DEVICE_LOST);
        assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);
        synchronized (notifications) {
            assertThat(notifications).anyMatch(m -> m.contains("disconnected"));
        }
        // Take buffer must have been flushed to disk under .daw/incomplete-takes/
        assertThat(takeStore.bufferedByteCount()).isZero();
        Path takesDir = projectRoot.resolve(".daw").resolve("incomplete-takes");
        assertThat(takesDir).exists();
        try (java.util.stream.Stream<Path> takeEntries = Files.list(takesDir)) {
            assertThat(takeEntries.count()).isPositive();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void shouldReturnToStoppedWhenLostDeviceReturns(@TempDir Path projectRoot)
            throws InterruptedException {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));

        MockAudioBackend backend = new MockAudioBackend();
        DeviceId active = new DeviceId(MockAudioBackend.NAME, "Mock Device");
        controller.bindBackendDeviceEvents(backend, active);

        // Subscribe to engine-state events to verify the published transitions.
        List<EngineState> seen = new ArrayList<>();
        controller.engineStateEvents().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(EngineState state) {
                synchronized (seen) { seen.add(state); }
            }
            @Override public void onError(Throwable t) { /* ignore */ }
            @Override public void onComplete() { /* ignore */ }
        });

        backend.simulateDeviceRemoved(active);
        waitFor(() -> controller.engineState() == EngineState.DEVICE_LOST);

        backend.simulateDeviceArrived(active);
        waitFor(() -> controller.engineState() == EngineState.STOPPED);

        assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        synchronized (seen) {
            assertThat(seen).contains(EngineState.DEVICE_LOST, EngineState.STOPPED);
        }
    }

    @Test
    void shouldIgnoreRemovalOfUnrelatedDevice(@TempDir Path projectRoot)
            throws InterruptedException {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        AtomicReference<String> lastNotice = new AtomicReference<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, lastNotice::set, new IncompleteTakeStore(projectRoot));

        MockAudioBackend backend = new MockAudioBackend();
        controller.bindBackendDeviceEvents(backend,
                new DeviceId(MockAudioBackend.NAME, "Active Mock"));

        backend.simulateDeviceRemoved(new DeviceId(MockAudioBackend.NAME, "Some Other Device"));

        // Give the publisher a moment, then assert no transition happened.
        Thread.sleep(50);
        assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        assertThat(lastNotice.get()).isNull();
    }

    @Test
    void shouldNotThrowWhenDeviceRemovedBeforeAnyTakeCaptured(@TempDir Path projectRoot)
            throws InterruptedException {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));
        MockAudioBackend backend = new MockAudioBackend();
        DeviceId active = new DeviceId(MockAudioBackend.NAME, "Mock Device");
        controller.bindBackendDeviceEvents(backend, active);

        backend.simulateDeviceRemoved(active);
        waitFor(() -> controller.engineState() == EngineState.DEVICE_LOST);
        // Nothing buffered → no file should have been written, but no exception either.
        Path takesDir = projectRoot.resolve(".daw").resolve("incomplete-takes");
        if (java.nio.file.Files.exists(takesDir)) {
            try (var stream = java.nio.file.Files.list(takesDir)) {
                assertThat(stream.count()).isZero();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    // -- Driver-initiated format-change handling (story 218) -----------------

    @Test
    void formatChangeRequestedTriggersReopenWithProposedFormat(@TempDir Path projectRoot)
            throws InterruptedException {
        // Engine starts at 256-frame buffer.
        AudioFormat starting = new AudioFormat(48_000.0, 2, 24, 256);
        AudioEngine engine = new AudioEngine(starting);
        List<String> notifications = new ArrayList<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null,
                message -> { synchronized (notifications) { notifications.add(message); } },
                new IncompleteTakeStore(projectRoot));

        MockAudioBackend backend = new MockAudioBackend();
        backend.setBufferSizeRange(
                new com.benesquivelmusic.daw.sdk.audio.BufferSizeRange(64, 2048, 512, 64));
        DeviceId active = new DeviceId(MockAudioBackend.NAME, "Mock Device");
        controller.bindBackendDeviceEvents(backend, active);

        // Track engine-state transitions so we can wait for the
        // RECONFIGURING -> STOPPED arc rather than a stale STOPPED match
        // (the engine starts in STOPPED).
        List<EngineState> transitions = new ArrayList<>();
        controller.engineStateEvents().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(EngineState s) {
                synchronized (transitions) { transitions.add(s); }
            }
            @Override public void onError(Throwable t) { /* ignore */ }
            @Override public void onComplete() { /* ignore */ }
        });

        // The driver renegotiated to a 512-frame buffer at the same rate.
        com.benesquivelmusic.daw.sdk.audio.AudioFormat proposed =
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(48_000.0, 2, 24);
        backend.simulateFormatChangeRequested(active, Optional.of(proposed),
                new FormatChangeReason.BufferSizeChange());

        // Wait for the worker to publish RECONFIGURING followed by STOPPED.
        waitForLong(() -> {
            synchronized (transitions) {
                return transitions.contains(EngineState.RECONFIGURING)
                        && transitions.lastIndexOf(EngineState.STOPPED)
                                > transitions.indexOf(EngineState.RECONFIGURING);
            }
        });

        assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        // Engine retained the proposed sample rate / bit depth.
        assertThat(engine.getFormat().sampleRate()).isEqualTo(48_000.0);
        assertThat(engine.getFormat().bitDepth()).isEqualTo(24);
        // No exception escaped through the publisher; the engine survived.
        assertThat(engine.isRunning()).isFalse();
        // User-facing notifications were emitted around the reopen.
        synchronized (notifications) {
            assertThat(notifications)
                    .anyMatch(m -> m.toLowerCase().contains("reconfigur"));
        }
    }

    @Test
    void sampleRateChangeRequestedFallsBackToSrc(@TempDir Path projectRoot)
            throws InterruptedException {
        // Project session at 48 kHz.
        AudioFormat starting = new AudioFormat(48_000.0, 2, 24, 256);
        AudioEngine engine = new AudioEngine(starting);
        List<String> notifications = new ArrayList<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null,
                message -> { synchronized (notifications) { notifications.add(message); } },
                new IncompleteTakeStore(projectRoot));

        MockAudioBackend backend = new MockAudioBackend();
        DeviceId active = new DeviceId(MockAudioBackend.NAME, "Mock Device");
        controller.bindBackendDeviceEvents(backend, active);

        List<EngineState> transitions = new ArrayList<>();
        controller.engineStateEvents().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(EngineState s) {
                synchronized (transitions) { transitions.add(s); }
            }
            @Override public void onError(Throwable t) { /* ignore */ }
            @Override public void onComplete() { /* ignore */ }
        });

        // Driver moved to 44.1 kHz. The session rate must NOT change.
        com.benesquivelmusic.daw.sdk.audio.AudioFormat proposed =
                new com.benesquivelmusic.daw.sdk.audio.AudioFormat(44_100.0, 2, 24);
        backend.simulateFormatChangeRequested(active, Optional.of(proposed),
                new FormatChangeReason.SampleRateChange());

        waitForLong(() -> {
            synchronized (transitions) {
                return transitions.contains(EngineState.RECONFIGURING)
                        && transitions.lastIndexOf(EngineState.STOPPED)
                                > transitions.indexOf(EngineState.RECONFIGURING);
            }
        });

        // Project session rate unchanged: SRC fallback at the device boundary.
        assertThat(engine.getFormat().sampleRate()).isEqualTo(48_000.0);
        // SRC notification must mention the rate move so the user knows
        // they can pick a matching project rate from the driver panel.
        synchronized (notifications) {
            assertThat(notifications)
                    .anyMatch(m -> m.toLowerCase().contains("src"));
            assertThat(notifications)
                    .anyMatch(m -> m.contains("44") /* 44 kHz */);
        }
        assertThat(engine.isRunning()).isFalse();
    }

    private static void waitFor(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError(
                    "Timed out after 2 s waiting for condition to become true");
        }
    }

    /** Same as {@link #waitFor(java.util.function.BooleanSupplier)} but with a
     * longer ceiling — the format-change worker debounces 250 ms before the
     * 200 ms drain, so the total wait is at least ~450 ms before STOPPED. */
    private static void waitForLong(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError(
                    "Timed out after 3 s waiting for condition to become true");
        }
    }
}
