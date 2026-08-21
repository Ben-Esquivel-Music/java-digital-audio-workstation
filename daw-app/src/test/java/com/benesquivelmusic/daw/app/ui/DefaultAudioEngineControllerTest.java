package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // -- Story 314 review follow-up: always-active monitor-event drain -------

    @Test
    void monitorEventsAreDrainedWithoutAnyCpuLoadPoll() throws InterruptedException {
        AudioFormat format = new AudioFormat(48_000.0, 2, 24, 256);
        AudioEngine engine = new AudioEngine(format);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null);
        try {
            PerformanceMonitor monitor = new PerformanceMonitor(format);
            CountDownLatch warned = new CountDownLatch(1);
            monitor.addWarningListener(_ -> warned.countDown());
            engine.setPerformanceMonitor(monitor);
            // One recorded block far above the ~5.3 ms budget pushes the
            // smoothed load orders of magnitude past the default 80%
            // threshold, leaving a pending warning transition on the RT side.
            monitor.recordProcessingTime(TimeUnit.SECONDS.toNanos(10));
            // Deliberately no getCpuLoadPercent() call: delivery must come
            // from the controller's always-active 250 ms drain alone —
            // underrun logs and warning listeners must work with every
            // dialog closed.
            assertThat(warned.await(2, TimeUnit.SECONDS))
                    .as("the always-active monitor-event drain must deliver "
                            + "deferred warning-listener notifications without "
                            + "any UI-side poll")
                    .isTrue();
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void getCpuLoadPercentIsSideEffectFree() {
        AudioFormat format = new AudioFormat(48_000.0, 2, 24, 256);
        AudioEngine engine = new AudioEngine(format);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null);
        // Kill the drain before its first 250 ms tick so the getter is the
        // only candidate publisher left.
        controller.shutdown();
        PerformanceMonitor monitor = new PerformanceMonitor(format);
        AtomicInteger warnings = new AtomicInteger();
        monitor.addWarningListener(_ -> warnings.incrementAndGet());
        engine.setPerformanceMonitor(monitor);
        monitor.recordProcessingTime(TimeUnit.SECONDS.toNanos(10));

        double load = controller.getCpuLoadPercent();

        assertThat(load).isEqualTo(monitor.getCpuLoadPercent());
        assertThat(warnings.get())
                .as("getCpuLoadPercent() must not publish the monitor's "
                        + "pending events — the always-active drain owns that")
                .isZero();
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
    void startupAndInteractiveConfigurationsCannotOverlap() throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        CountDownLatch startupInCallback = new CountDownLatch(1);
        CountDownLatch releaseStartup = new CountDownLatch(1);
        CountDownLatch interactiveCompleted = new CountDownLatch(1);
        AtomicInteger callbackCount = new AtomicInteger();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, () -> {
                    if (callbackCount.incrementAndGet() == 1) {
                        startupInCallback.countDown();
                        try {
                            releaseStartup.await();
                        } catch (InterruptedException cancelled) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(cancelled);
                        }
                    } else {
                        interactiveCompleted.countDown();
                    }
                });
        var startupRequest = new AudioEngineController.Request(
                AudioEngineController.BACKEND_NONE, "", "",
                SampleRate.HZ_48000, 256, 24);
        var interactiveRequest = new AudioEngineController.Request(
                AudioEngineController.BACKEND_NONE, "", "",
                SampleRate.HZ_44100, 512, 16);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread startup = Thread.ofVirtual().name("test-startup-audio").start(() -> {
            try {
                controller.applyConfiguration(startupRequest);
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            }
        });
        assertThat(startupInCallback.await(5, TimeUnit.SECONDS)).isTrue();
        Thread interactive = Thread.ofVirtual().name("test-settings-audio").start(() -> {
            try {
                controller.applyConfiguration(interactiveRequest);
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            }
        });

        assertThat(interactiveCompleted.await(250, TimeUnit.MILLISECONDS))
                .as("interactive configuration waits for startup configuration")
                .isFalse();
        assertThat(engine.getFormat().bufferSize()).isEqualTo(256);
        releaseStartup.countDown();
        assertThat(interactiveCompleted.await(5, TimeUnit.SECONDS)).isTrue();
        startup.join();
        interactive.join();

        assertThat(failure.get()).isNull();
        assertThat(callbackCount).hasValue(2);
        assertThat(engine.getFormat().bufferSize()).isEqualTo(512);
        controller.shutdown();
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

        // The state-flip and the notification are emitted from the same
        // device-event thread but in two separate steps; polling on state
        // alone races with the notify call. Wait for both.
        waitFor(() -> {
            if (controller.engineState() != EngineState.DEVICE_LOST) {
                return false;
            }
            synchronized (notifications) {
                return notifications.stream().anyMatch(m -> m.contains("disconnected"));
            }
        });
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
        // The SubmissionPublisher delivers events asynchronously, so the
        // subscriber may observe the STOPPED transition slightly after the
        // controller's state field flips — wait for delivery before asserting.
        waitFor(() -> {
            synchronized (seen) {
                return seen.contains(EngineState.STOPPED);
            }
        });
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
        // Story 314 review follow-up: the driver-originated reopen must fire
        // the post-reconfigure callback (MainController wires it to reinstall
        // the CPU budget enforcer AND EngineBinder.refreshPerformanceMonitor(),
        // whose per-block budget is fixed at construction) — and it must fire
        // BEFORE the terminal STOPPED transition, per the step-7/8/9 ordering
        // convention. Capture the engine state observed inside the callback.
        AtomicReference<DefaultAudioEngineController> controllerRef = new AtomicReference<>();
        AtomicInteger postReconfigureHits = new AtomicInteger();
        AtomicReference<EngineState> stateWhenCallbackRan = new AtomicReference<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, () -> {
                    stateWhenCallbackRan.compareAndSet(null, controllerRef.get().engineState());
                    postReconfigureHits.incrementAndGet();
                },
                message -> { synchronized (notifications) { notifications.add(message); } },
                new IncompleteTakeStore(projectRoot));
        controllerRef.set(controller);

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
        // The post-reconfigure callback ran exactly once for this reopen,
        // and it ran while the controller was still RECONFIGURING — i.e.
        // strictly before the terminal STOPPED transition observers gate on.
        assertThat(postReconfigureHits.get()).isEqualTo(1);
        assertThat(stateWhenCallbackRan.get())
                .as("the post-reconfigure callback must run before the "
                        + "STOPPED transition (side effects precede the "
                        + "terminal state)")
                .isEqualTo(EngineState.RECONFIGURING);
    }

    @Test
    void formatChangeReopenWithOpenStreamClosesThenReopensThroughEngineSeam(
            @TempDir Path projectRoot) throws InterruptedException {
        // Story 316 — the wasOpen == true arc of the driver-initiated
        // format-change reopen (the test above runs with NO open stream and
        // ends STOPPED): a stream that was OPEN before the event must be
        // closed and then reopened THROUGH the engine's one open/close seam
        // — never by calling backend.open(...) directly — ending RUNNING
        // with the engine format carrying the proposed change.
        AudioFormat starting = new AudioFormat(48_000.0, 2, 24, 256);
        AudioEngine engine = new AudioEngine(starting);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));
        List<String> lifecycleLog =
                java.util.Collections.synchronizedList(new ArrayList<>());
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyASIO", lifecycleLog);
        DeviceId active = new DeviceId("SpyASIO", "Dev B");
        SubmissionPublisher<com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent>
                deviceEvents = new SubmissionPublisher<>();
        try {
            engine.setStreamingProvision(new StreamingProvision("SpyASIO",
                    List.of(new BackendStreamRung(spy, active))));
            controller.bindBackendDeviceEvents(spy, active, deviceEvents);

            // Open the stream BEFORE the driver event — the discriminator
            // against the no-open-stream variant above.
            engine.startAudioOutput();
            assertThat(engine.isStreamOpen()).isTrue();
            assertThat(spy.isOpen()).isTrue();

            List<EngineState> transitions = new ArrayList<>();
            controller.engineStateEvents().subscribe(new Flow.Subscriber<EngineState>() {
                @Override public void onSubscribe(Flow.Subscription s) {
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
            deviceEvents.submit(new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
                    .FormatChangeRequested(active, Optional.of(proposed),
                            new FormatChangeReason.BufferSizeChange(512)));

            // Wait for the RECONFIGURING -> RUNNING arc: a restored stream
            // reports RUNNING as its terminal state (never a stale STOPPED).
            waitForLong(() -> {
                synchronized (transitions) {
                    return transitions.contains(EngineState.RECONFIGURING)
                            && transitions.lastIndexOf(EngineState.RUNNING)
                                    > transitions.indexOf(EngineState.RECONFIGURING);
                }
            });

            // The reopen routed through the engine seam: the spy recorded
            // the OLD stream's close strictly before the second open.
            assertThat(List.copyOf(lifecycleLog))
                    .as("format-change reopen must close the old stream and "
                            + "reopen through the engine's one seam, in order")
                    .containsExactly("open:SpyASIO", "close:SpyASIO", "open:SpyASIO");

            // The stream is open again afterwards, honestly reported.
            assertThat(controller.engineState()).isEqualTo(EngineState.RUNNING);
            assertThat(engine.isStreamOpen()).isTrue();
            assertThat(spy.isOpen()).isTrue();
            assertThat(engine.openStreamBackendName()).contains("SpyASIO");

            // The engine format carries the proposed change (new buffer
            // size; rate and depth retained), and the SECOND open was made
            // at exactly that format on the configured device.
            assertThat(engine.getFormat().bufferSize()).isEqualTo(512);
            assertThat(engine.getFormat().sampleRate()).isEqualTo(48_000.0);
            assertThat(engine.getFormat().bitDepth()).isEqualTo(24);
            List<SpyStreamingBackend.OpenRecord> opens = spy.opens();
            assertThat(opens).hasSize(2);
            assertThat(opens.get(1).bufferFrames()).isEqualTo(512);
            assertThat(opens.get(1).device()).isEqualTo(active);
        } finally {
            deviceEvents.close();
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void sampleRateChangeRequestedFallsBackToSrc(@TempDir Path projectRoot)
            throws InterruptedException {
        // Project session at 48 kHz.
        AudioFormat starting = new AudioFormat(48_000.0, 2, 24, 256);
        AudioEngine engine = new AudioEngine(starting);
        List<String> notifications = new ArrayList<>();
        // Story 314 review follow-up: the SRC-fallback branch also calls
        // audioEngine.setFormat(...), so the post-reconfigure callback
        // (budget-enforcer reinstall + monitor refresh) must fire on this
        // path too.
        AtomicInteger postReconfigureHits = new AtomicInteger();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, postReconfigureHits::incrementAndGet,
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
        // The post-reconfigure callback fired for this driver-originated
        // reopen too — the STOPPED arc completing implies it already ran
        // (step 8 precedes step 9's STOPPED transition).
        assertThat(postReconfigureHits.get()).isEqualTo(1);
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

    // -- Stories 130/316: backend selection & provision wiring ---------------

    @Test
    void applyConfigurationWiresSdkBackendAsProvisionFirstRung(@TempDir Path projectRoot) {
        // Inject a selector whose factory map maps "Mock" to MockAudioBackend
        // (the default selector already does, but threading it explicitly
        // keeps the test deterministic and demonstrates how the selector
        // is wired into the controller for headless integration tests).
        java.util.Map<String, java.util.function.Supplier<
                com.benesquivelmusic.daw.sdk.audio.AudioBackend>> factories =
                new java.util.LinkedHashMap<>();
        factories.put(MockAudioBackend.NAME, MockAudioBackend::new);
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(factories);

        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        try {
            controller.applyConfiguration(new AudioEngineController.Request(
                    MockAudioBackend.NAME, "", "", SampleRate.HZ_44100, 512, 16, 1));

            // Story 316: the engine's ONE streaming slot carries the selected
            // SDK backend as the provision's requested (first) rung — the
            // wiring story's headline goal, now on the honest seam.
            StreamingProvision provision = engine.getStreamingProvision();
            assertThat(provision).isNotNull();
            assertThat(provision.requestedBackendName()).isEqualTo(MockAudioBackend.NAME);
            assertThat(provision.firstRung().backend())
                    .isInstanceOf(MockAudioBackend.class);
            assertThat(engine.getBackend()).isInstanceOf(MockAudioBackend.class);
        } finally {
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void activeSdkBackendDrivesUtilitiesCapabilitiesAndForwardedEvents(
            @TempDir Path projectRoot) throws Exception {
        MockAudioBackend backend = new MockAudioBackend();
        var range = new com.benesquivelmusic.daw.sdk.audio.BufferSizeRange(64, 512, 128, 64);
        backend.setBufferSizeRange(range);
        backend.setSupportedSampleRates(Set.of(48_000, 96_000));
        var internal = new com.benesquivelmusic.daw.sdk.audio.ClockSource(
                1, "Internal", true,
                new com.benesquivelmusic.daw.sdk.audio.ClockKind.Internal());
        var wordClock = new com.benesquivelmusic.daw.sdk.audio.ClockSource(
                2, "Word Clock", false,
                new com.benesquivelmusic.daw.sdk.audio.ClockKind.WordClock());
        backend.setClockSources(List.of(internal, wordClock));
        var selector = new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                java.util.Map.of(MockAudioBackend.NAME, () -> backend));
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        controller.applyConfiguration(new AudioEngineController.Request(
                MockAudioBackend.NAME, "", "Mock Device", SampleRate.HZ_44100, 512, 16, 1));
        engine.stop();
        DeviceId device = new DeviceId(MockAudioBackend.NAME, "Mock Device");
        controller.bindBackendDeviceEvents(backend, device);

        assertThat(controller.getActiveBackendName()).isEqualTo(MockAudioBackend.NAME);
        assertThat(controller.listDevices()).extracting("name").containsExactly("Mock Device");
        assertThat(controller.bufferSizeRange(MockAudioBackend.NAME, "Mock Device"))
                .isEqualTo(range);
        assertThat(controller.supportedSampleRates(MockAudioBackend.NAME, "Mock Device"))
                .containsExactlyInAnyOrder(48_000, 96_000);
        assertThat(controller.clockSources(MockAudioBackend.NAME, "Mock Device"))
                .containsExactly(internal, wordClock);
        controller.selectClockSource(MockAudioBackend.NAME, "Mock Device", 2);
        assertThat(backend.recordedClockSourceSelections()).containsExactly(2);
        controller.openControlPanel().orElseThrow().run();
        assertThat(backend.controlPanelInvocationCount()).isEqualTo(1);

        CountDownLatch eventForwarded = new CountDownLatch(1);
        AtomicReference<com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent> forwarded =
                new AtomicReference<>();
        controller.deviceEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(
                    com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent event) {
                forwarded.set(event);
                eventForwarded.countDown();
            }
            @Override public void onError(Throwable failure) { }
            @Override public void onComplete() { }
        });
        backend.simulateDeviceFormatChanged(
                device, com.benesquivelmusic.daw.sdk.audio.AudioFormat.STUDIO_QUALITY_48K);

        assertThat(eventForwarded.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(forwarded.get())
                .isInstanceOf(com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
                        .DeviceFormatChanged.class);
        controller.shutdown();
    }

    @Test
    void lateBackendSubscriptionsCannotReplaceCurrentGenerationOrLeakAfterShutdown(
            @TempDir Path projectRoot) throws Exception {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));
        MockAudioBackend backendA = new MockAudioBackend();
        MockAudioBackend backendB = new MockAudioBackend();
        DeviceId deviceA = new DeviceId(MockAudioBackend.NAME, "Device A");
        DeviceId deviceB = new DeviceId(MockAudioBackend.NAME, "Device B");
        DelayedDeviceEventPublisher eventsA = new DelayedDeviceEventPublisher();
        DelayedDeviceEventPublisher eventsB = new DelayedDeviceEventPublisher();
        CountDownLatch forwarded = new CountDownLatch(1);
        AtomicReference<com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent> seen =
                new AtomicReference<>();
        controller.deviceEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(
                    com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent event) {
                seen.set(event);
                forwarded.countDown();
            }
            @Override public void onError(Throwable failure) { }
            @Override public void onComplete() { }
        });

        controller.bindBackendDeviceEvents(backendA, deviceA, eventsA);
        controller.bindBackendDeviceEvents(backendB, deviceB, eventsB);
        eventsB.connect();
        eventsA.connect();

        assertThat(eventsA.cancelled).isTrue();
        assertThat(eventsB.cancelled).isFalse();
        eventsA.emit(new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
                .DeviceRemoved(deviceA));
        assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        assertThat(seen.get()).isNull();
        var currentEvent = new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
                .DeviceFormatChanged(
                        deviceB,
                        com.benesquivelmusic.daw.sdk.audio.AudioFormat.STUDIO_QUALITY_48K);
        eventsB.emit(currentEvent);
        assertThat(forwarded.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo(currentEvent);

        DelayedDeviceEventPublisher afterShutdown = new DelayedDeviceEventPublisher();
        controller.bindBackendDeviceEvents(backendB, deviceB, afterShutdown);
        controller.shutdown();
        afterShutdown.connect();
        assertThat(afterShutdown.cancelled).isTrue();
    }

    @Test
    void wasapiExclusiveNameFailsStreamingGateAndExplicitlyFallsBack(
            @TempDir Path projectRoot) {
        // Story 316: WASAPI's streaming path is not implemented
        // (supportsStreaming() == false), so requesting "WASAPI
        // (Exclusive)" must never provision a WASAPI rung — an
        // honest-looking silent stream — regardless of isAvailable().
        // The gate produces the same explicit fallback notification the
        // unavailable-backend path uses.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        List<String> notices = new ArrayList<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null,
                message -> { synchronized (notices) { notices.add(message); } },
                new IncompleteTakeStore(projectRoot));

        controller.applyConfiguration(new AudioEngineController.Request(
                "WASAPI (Exclusive)", "", "", SampleRate.HZ_44100, 512, 16, 1));

        StreamingProvision provision = engine.getStreamingProvision();
        assertThat(provision).isNotNull();
        for (BackendStreamRung rung : provision.ladder()) {
            assertThat(rung.backend())
                    .isNotInstanceOf(com.benesquivelmusic.daw.sdk.audio.WasapiBackend.class);
        }
        // Story 316 review (F8): the provision keeps the USER'S request —
        // the engine stamps it into every BackendFallbackEvent — while the
        // notification and the reported backend name the ladder's ACTUAL
        // head (PortAudio when available, else Java Sound).
        assertThat(provision.requestedBackendName()).isEqualTo("WASAPI (Exclusive)");
        String headRung = provision.firstRung().backend().name();
        assertThat(headRung).isIn("PortAudio", "Java Sound");
        assertThat(controller.getActiveBackendName())
                .as("closed stream: the reported backend is the ladder's first rung")
                .isEqualTo(headRung);
        synchronized (notices) {
            assertThat(notices)
                    .filteredOn(m -> m.contains(
                            "not available — falling back to " + headRung))
                    .hasSize(1)
                    .first()
                    .asString()
                    .startsWith("WASAPI (Exclusive)");
        }
        engine.stop();
        controller.shutdown();
    }

    @Test
    void unavailablePlatformBackendRequestFallsBackAndNotifies(
            @TempDir Path projectRoot) {
        // Register a deterministic test-only unavailable backend under the
        // name "ASIO" so this test does not depend on host OS or
        // native-library state. MockAudioBackend with setAvailable(false)
        // simulates a platform backend whose native driver is absent.
        java.util.Map<String, java.util.function.Supplier<
                com.benesquivelmusic.daw.sdk.audio.AudioBackend>> factories =
                new java.util.LinkedHashMap<>();
        factories.put("ASIO", () -> {
            MockAudioBackend unavailable = new MockAudioBackend();
            unavailable.setAvailable(false);
            return unavailable;
        });
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(factories);

        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        List<String> notices = new ArrayList<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null,
                message -> { synchronized (notices) { notices.add(message); } },
                new IncompleteTakeStore(projectRoot), selector);

        controller.applyConfiguration(new AudioEngineController.Request(
                "ASIO", "", "", SampleRate.HZ_44100, 512, 16, 1));

        // Story 316: the provision does NOT carry the unavailable backend —
        // its ladder starts at the emergency rungs, so the user ends up on
        // a backend that actually streams.
        StreamingProvision provision = engine.getStreamingProvision();
        assertThat(provision).isNotNull();
        for (BackendStreamRung rung : provision.ladder()) {
            assertThat(rung.backend()).isNotInstanceOf(MockAudioBackend.class);
        }
        // Story 316 review (F8): requestedBackendName stays the USER'S
        // request; the notification and the reported backend name the
        // ladder's ACTUAL head instead of a hardcoded "Java Sound".
        assertThat(provision.requestedBackendName()).isEqualTo("ASIO");
        String headRung = provision.firstRung().backend().name();
        assertThat(headRung).isIn("PortAudio", "Java Sound");
        assertThat(controller.getActiveBackendName()).isEqualTo(headRung);
        // Exactly one fallback notification, naming the actual head rung.
        synchronized (notices) {
            assertThat(notices)
                    .filteredOn(m -> m.contains(
                            "not available — falling back to " + headRung))
                    .hasSize(1)
                    .first()
                    .asString()
                    .startsWith("ASIO");
        }
        engine.stop();
        controller.shutdown();
    }

    @Test
    void gateRejectedProvisionCarriesTheUsersOwnRequestedDevice(
            @TempDir Path projectRoot) {
        // Story 316 review: BackendFallbackEvent.requestedDevice is defined
        // as the device the USER'S configuration asked for. A gate-rejected
        // request starts the ladder on a FALLBACK rung, so defaulting the
        // requested device from firstRung() would stamp every published
        // event with a device the user never chose — the fallback backend's
        // own default. Same deterministic unavailable-"ASIO" setup as the
        // test above, but naming an explicit OUTPUT device.
        java.util.Map<String, java.util.function.Supplier<
                com.benesquivelmusic.daw.sdk.audio.AudioBackend>> factories =
                new java.util.LinkedHashMap<>();
        factories.put("ASIO", () -> {
            MockAudioBackend unavailable = new MockAudioBackend();
            unavailable.setAvailable(false);
            return unavailable;
        });
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(factories);

        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);

        controller.applyConfiguration(new AudioEngineController.Request(
                "ASIO", "", "Studio Interface Out", SampleRate.HZ_44100, 512, 16, 1));

        StreamingProvision provision = engine.getStreamingProvision();
        assertThat(provision).isNotNull();
        assertThat(provision.requestedBackendName()).isEqualTo("ASIO");
        assertThat(provision.requestedDevice())
                .as("the provision names the OUTPUT device the user asked for")
                .isEqualTo(new DeviceId("ASIO", "Studio Interface Out"));

        BackendStreamRung head = provision.firstRung();
        assertThat(head.device())
                .as("falling back is an emergency: the rung opens its OWN default device")
                .isEqualTo(DeviceId.defaultFor(head.backend().name()));
        assertThat(head.device())
                .as("the fallback's device never masquerades as the user's request")
                .isNotEqualTo(provision.requestedDevice());

        engine.stop();
        controller.shutdown();
    }

    @Test
    void applyConfigurationAlwaysFinishesEvenWhenTheProvisionBuildThrows(
            @TempDir Path projectRoot) {
        // Story 316 review — the finally-block guarantee. Anything escaping
        // applyConfiguration used to strand the whole flow: neither the
        // terminal engine state nor the post-reconfigure callback ran, so the
        // settings dialog stayed disabled forever behind a failure nobody
        // surfaced. AudioBackendSelector.selectByName calls factory.get() and
        // propagates whatever the supplier throws, which is the most honest
        // way to make the provision build blow up from a unit test.
        //
        // What this test deliberately does NOT cover: the inner wasOpen
        // branch's guard around startAudioOutput(). That branch cannot be
        // driven deterministically from a unit test — the fallback ladder
        // always ends in a real JavaxSoundBackend rung that opens
        // successfully on a healthy host, and AudioEngine is final so it
        // cannot be stubbed. Faking that, or asserting on whether this
        // particular host has a working output device, would both be worse
        // than the honest gap.
        java.util.Map<String, java.util.function.Supplier<
                com.benesquivelmusic.daw.sdk.audio.AudioBackend>> factories =
                new java.util.LinkedHashMap<>();
        factories.put("ASIO", () -> {
            throw new IllegalStateException("ASIO driver blew up while loading");
        });
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(factories);

        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        AtomicBoolean callbackRan = new AtomicBoolean();
        List<String> notices = new ArrayList<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, () -> callbackRan.set(true),
                message -> { synchronized (notices) { notices.add(message); } },
                new IncompleteTakeStore(projectRoot), selector);
        try {
            // Leave the initial STOPPED state first so the STOPPED assertion
            // below is discriminating rather than vacuous: a removal of the
            // bound device lands in DEVICE_LOST synchronously on the
            // emitting thread.
            DeviceId lost = new DeviceId("Mock", "Mock Out");
            DelayedDeviceEventPublisher events = new DelayedDeviceEventPublisher();
            controller.bindBackendDeviceEvents(new MockAudioBackend(), lost, events);
            events.connect();
            events.emit(new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
                    .DeviceRemoved(lost));
            assertThat(controller.engineState()).isEqualTo(EngineState.DEVICE_LOST);

            assertThatThrownBy(() -> controller.applyConfiguration(
                    new AudioEngineController.Request(
                            "ASIO", "", "", SampleRate.HZ_44100, 512, 16, 1)))
                    .as("the failure still reaches the caller")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ASIO driver blew up while loading");

            assertThat(callbackRan)
                    .as("the post-reconfigure callback runs on EVERY path — the "
                            + "settings dialog re-enables even when the apply failed")
                    .isTrue();
            assertThat(controller.engineState())
                    .as("the flow lands in a terminal state, never stranded")
                    .isEqualTo(EngineState.STOPPED);
            synchronized (notices) {
                assertThat(notices)
                        .as("the user is told why the configuration did not apply")
                        .anyMatch(m -> m.startsWith(
                                "Audio configuration could not be applied: "));
            }
        } finally {
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void gateFailureNotificationNamesPortAudioWhenPortAudioHeadsTheLadder(
            @TempDir Path projectRoot) {
        // Story 316 review (F8): on a PortAudio-capable host the fallback
        // ladder's head is PortAudio, and the notification must say so —
        // the pre-fix message dishonestly hardcoded "Java Sound". On a host
        // without PortAudio this degrades to asserting the actual head.
        java.util.Map<String, java.util.function.Supplier<
                com.benesquivelmusic.daw.sdk.audio.AudioBackend>> factories =
                new java.util.LinkedHashMap<>();
        factories.put("ASIO", () -> {
            MockAudioBackend unavailable = new MockAudioBackend();
            unavailable.setAvailable(false);
            return unavailable;
        });
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(factories);

        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        List<String> notices = new ArrayList<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null,
                message -> { synchronized (notices) { notices.add(message); } },
                new IncompleteTakeStore(projectRoot), selector);

        boolean portAudioCapable =
                controller.getAvailableBackendNames().contains("PortAudio");

        controller.applyConfiguration(new AudioEngineController.Request(
                "ASIO", "", "", SampleRate.HZ_44100, 512, 16, 1));

        StreamingProvision provision = engine.getStreamingProvision();
        assertThat(provision).isNotNull();
        String headRung = provision.firstRung().backend().name();
        if (portAudioCapable) {
            assertThat(headRung)
                    .as("a PortAudio-capable host heads the fallback ladder with PortAudio")
                    .isEqualTo("PortAudio");
        } else {
            assertThat(headRung).isEqualTo("Java Sound");
        }
        synchronized (notices) {
            assertThat(notices)
                    .filteredOn(m -> m.contains(
                            "not available — falling back to " + headRung))
                    .as("the notification names the ladder's actual head: " + headRung)
                    .hasSize(1)
                    .first()
                    .asString()
                    .startsWith("ASIO");
        }
        engine.stop();
        controller.shutdown();
    }

    @Test
    void postGateLadderFailurePublishesEventsNamingTheOriginalRequest(
            @TempDir Path projectRoot) throws Exception {
        // Story 316 review (F8): the provision the controller builds stamps
        // the USER'S requested name into every BackendFallbackEvent the
        // engine publishes. A spy "ASIO" passes the availability/streaming
        // gate, then refuses the open — a post-gate ladder-hop failure.
        SpyStreamingBackend refusing = new SpyStreamingBackend("ASIO");
        refusing.failOpensWith(
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendException(
                        "ASIO driver refused the open"));
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                        java.util.Map.of("ASIO", () -> refusing));
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);

        com.benesquivelmusic.daw.sdk.event.EventBus previousBus =
                com.benesquivelmusic.daw.core.event.EventBusPublisher.getDefault();
        com.benesquivelmusic.daw.core.event.DefaultEventBus bus =
                new com.benesquivelmusic.daw.core.event.DefaultEventBus();
        List<com.benesquivelmusic.daw.sdk.audio.BackendFallbackEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch published = new CountDownLatch(1);
        bus.on(com.benesquivelmusic.daw.sdk.audio.BackendFallbackEvent.class, event -> {
            events.add(event);
            published.countDown();
        });
        com.benesquivelmusic.daw.core.event.EventBusPublisher.setDefault(bus);
        try {
            controller.applyConfiguration(new AudioEngineController.Request(
                    "ASIO", "", "", SampleRate.HZ_44100, 512, 16, 1));
            assertThat(engine.getStreamingProvision().requestedBackendName())
                    .as("the gate passed; the provision names the user's request")
                    .isEqualTo("ASIO");

            try {
                engine.startAudioOutput(); // hop 1 fails post-gate; the ladder walks on
            } catch (RuntimeException everyRungFailed) {
                // Acceptable on hosts where no fallback rung can open —
                // the failed hops still published their events, asserted below.
            }

            assertThat(published.await(5, TimeUnit.SECONDS))
                    .as("the failed post-gate hop publishes a BackendFallbackEvent")
                    .isTrue();
            assertThat(events)
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event.requestedBackend())
                            .as("fallback events name the ORIGINAL user request")
                            .isEqualTo("ASIO"));
        } finally {
            com.benesquivelmusic.daw.core.event.EventBusPublisher.setDefault(previousBus);
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
            bus.close();
        }
    }

    @Test
    void getAvailableBackendNamesIncludesSdkSelectorBackends() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null);
        List<String> names = controller.getAvailableBackendNames();
        // Java Sound is always present (the ladder's unconditional floor).
        // The full set is the union with whatever the selector reports
        // available on this host: on Linux that's at least JACK,
        // on Windows ASIO/WASAPI, on macOS CoreAudio. The exact extra
        // entry depends on the OS, but the union must always be a
        // superset of the selector's available list.
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector();
        assertThat(names)
                .contains("Java Sound")
                .containsAll(selector.availableBackendNames());
    }

    @Test
    void setSampleRateRoutesToSdkBackendForKnownName(@TempDir Path projectRoot) {
        // Story 220: the dialog calls controller.setSampleRate(...) and
        // expects the call to reach the SDK backend identified by name.
        // Inject a permitted backend (MockAudioBackend) under the name
        // "ASIO" via the selector — this verifies routing without
        // requiring the real native shim or a Windows host. The
        // MockAudioBackend keeps the AudioBackend default
        // setSampleRate(...) which throws UnsupportedOperationException;
        // the controller swallows that and returns silently, which is
        // the documented contract for backends without a live setter.
        java.util.Map<String, java.util.function.Supplier<
                com.benesquivelmusic.daw.sdk.audio.AudioBackend>> factories =
                new java.util.LinkedHashMap<>();
        factories.put("ASIO", MockAudioBackend::new);
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(factories);
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);

        // The call resolves a backend through the selector and invokes
        // its setSampleRate(DeviceId, double). MockAudioBackend uses
        // the AudioBackend default which throws
        // UnsupportedOperationException — the controller swallows it.
        controller.setSampleRate("ASIO", "Mock Out", 96_000.0);
    }

    @Test
    void setSampleRateIsNoopForLegacyNativeBackends(@TempDir Path projectRoot) {
        // Legacy NativeAudioBackend names ("PortAudio", "Java Sound")
        // negotiate the rate at stream open and have no separate
        // setter — the controller must skip them so the dialog flow
        // falls through to the model-only update.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot),
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector());
        // Both null/blank/legacy names must be silent no-ops.
        controller.setSampleRate(null, "", 48_000.0);
        controller.setSampleRate("", "", 48_000.0);
        controller.setSampleRate("Java Sound", "", 48_000.0);
        controller.setSampleRate("PortAudio", "", 48_000.0);
    }

    private static final class DelayedDeviceEventPublisher
            implements Flow.Publisher<com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent> {
        private final AtomicReference<Flow.Subscriber<? super
                com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent>> subscriber =
                new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void subscribe(Flow.Subscriber<? super
                com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent> newSubscriber) {
            subscriber.set(newSubscriber);
        }

        private void connect() {
            subscriber.get().onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { }
                @Override public void cancel() { cancelled.set(true); }
            });
        }

        private void emit(com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent event) {
            subscriber.get().onNext(event);
        }
    }
}
