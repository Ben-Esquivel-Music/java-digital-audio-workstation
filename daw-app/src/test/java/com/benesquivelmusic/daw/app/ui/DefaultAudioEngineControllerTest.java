package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

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
        try {
            assertThat(Files.list(takesDir).count()).isPositive();
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

    private static void waitFor(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
    }
}
