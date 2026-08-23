package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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

        // Story 316 review: DEVICE_LOST is reserved for the device of an OPEN
        // stream, so this test opens one — being merely WATCHED no longer
        // authorises the transition (see
        // unpluggingAWatchedDeviceWhileNoStreamIsOpenChangesNothing below,
        // the discriminating counterpart of this test).
        SpyStreamingBackend backend = new SpyStreamingBackend("SpyHotplug");
        DeviceId active = new DeviceId("SpyHotplug", "Mock Device");
        engine.setStreamingProvision(new StreamingProvision("SpyHotplug",
                List.of(new BackendStreamRung(backend, active))));
        controller.bindBackendDeviceEvents(backend, active);
        engine.startAudioOutput();
        assertThat(engine.isStreamOpen()).isTrue();

        try {
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
            assertThat(engine.isStreamOpen())
                    .as("the lost device's stream is closed, not left claiming the hardware")
                    .isFalse();
            // Take buffer must have been flushed to disk under .daw/incomplete-takes/
            assertThat(takeStore.bufferedByteCount()).isZero();
            Path takesDir = projectRoot.resolve(".daw").resolve("incomplete-takes");
            assertThat(takesDir).exists();
            try (java.util.stream.Stream<Path> takeEntries = Files.list(takesDir)) {
                assertThat(takeEntries.count()).isPositive();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void shouldReturnToStoppedWhenLostDeviceReturns(@TempDir Path projectRoot)
            throws InterruptedException {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));

        // An OPEN stream is the precondition for DEVICE_LOST (story 316
        // review) — a merely watched device that vanishes is inert, so the
        // DEVICE_LOST -> STOPPED arc this test asserts can only be reached
        // from a real open.
        SpyStreamingBackend backend = new SpyStreamingBackend("SpyReturn");
        DeviceId active = new DeviceId("SpyReturn", "Mock Device");
        engine.setStreamingProvision(new StreamingProvision("SpyReturn",
                List.of(new BackendStreamRung(backend, active))));
        controller.bindBackendDeviceEvents(backend, active);
        engine.startAudioOutput();
        assertThat(engine.isStreamOpen()).isTrue();

        try {
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

            backend.publishDeviceEvent(new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
                    .DeviceArrived(active));
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
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void shouldIgnoreRemovalOfUnrelatedDevice(@TempDir Path projectRoot)
            throws InterruptedException {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        AtomicReference<String> lastNotice = new AtomicReference<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, lastNotice::set, new IncompleteTakeStore(projectRoot));

        // A stream IS open here on purpose (story 316 review): with the
        // open-stream gate in onDeviceRemoved, a stopped engine would ignore
        // every removal, so this test would pass without exercising the
        // endpoint-IDENTITY check it exists for. Opening first makes the
        // device name the only thing that can hold the transition back.
        SpyStreamingBackend backend = new SpyStreamingBackend("SpyUnrelated");
        DeviceId active = new DeviceId("SpyUnrelated", "Active Mock");
        engine.setStreamingProvision(new StreamingProvision("SpyUnrelated",
                List.of(new BackendStreamRung(backend, active))));
        controller.bindBackendDeviceEvents(backend, active);
        engine.startAudioOutput();
        assertThat(engine.isStreamOpen()).isTrue();

        try {
            backend.simulateDeviceRemoved(new DeviceId("SpyUnrelated", "Some Other Device"));

            // Give the publisher a moment, then assert no transition happened.
            Thread.sleep(50);
            assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
            assertThat(lastNotice.get()).isNull();
            assertThat(engine.isStreamOpen())
                    .as("an unrelated device's removal leaves our stream alone")
                    .isTrue();
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void shouldNotThrowWhenDeviceRemovedBeforeAnyTakeCaptured(@TempDir Path projectRoot)
            throws InterruptedException {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));
        // Open a stream first: the flush this test guards is only reached for
        // the device of an OPEN stream (story 316 review).
        SpyStreamingBackend backend = new SpyStreamingBackend("SpyEmptyTake");
        DeviceId active = new DeviceId("SpyEmptyTake", "Mock Device");
        engine.setStreamingProvision(new StreamingProvision("SpyEmptyTake",
                List.of(new BackendStreamRung(backend, active))));
        controller.bindBackendDeviceEvents(backend, active);
        engine.startAudioOutput();

        try {
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
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void unpluggingAWatchedDeviceWhileNoStreamIsOpenChangesNothing(@TempDir Path projectRoot) {
        // Story 316 review — the watched endpoint and the OPEN stream's device
        // are two different facts. applyConfiguration() binds the watch BEFORE
        // any open (and no seam clears it on close), so treating "bound" as
        // "active" made an idle interface's hot-unplug drive a perfectly
        // stopped engine into DEVICE_LOST, stop it, and flush an incomplete
        // take that no stream had ever recorded.
        //
        // A provision IS installed and the take store IS non-empty, so every
        // side effect the old code performed is reachable here — the only
        // reason none of them happens is the open-stream gate.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        List<String> notices = new ArrayList<>();
        IncompleteTakeStore takeStore = new IncompleteTakeStore(projectRoot);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null,
                message -> { synchronized (notices) { notices.add(message); } },
                takeStore);
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyWatchedOnly");
        DeviceId watched = new DeviceId("SpyWatchedOnly", "Idle Interface");
        try {
            engine.setStreamingProvision(new StreamingProvision("SpyWatchedOnly",
                    List.of(new BackendStreamRung(spy, watched))));
            // The pre-open bind applyConfiguration performs while stopped,
            // driven through an injected publisher so both events below are
            // delivered synchronously on this thread — asserting that nothing
            // happened must not depend on a sleep.
            DelayedDeviceEventPublisher events = new DelayedDeviceEventPublisher();
            controller.bindBackendDeviceEvents(spy, watched, events);
            events.connect();
            engine.start();
            controller.captureRecordingFrames(new float[][]{{0.1f, 0.2f}, {0.3f, 0.4f}}, 2);
            assertThat(engine.isStreamOpen())
                    .as("premise: watched, but nothing open")
                    .isFalse();
            int bufferedBefore = takeStore.bufferedByteCount();
            assertThat(bufferedBefore).isPositive();

            events.emit(new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
                    .DeviceRemoved(watched));

            // Delivered synchronously through the injected publisher, so this
            // needs no sleep and no polling.
            assertThat(controller.engineState())
                    .as("a removal with no stream open is a routine unplug, not DEVICE_LOST")
                    .isEqualTo(EngineState.STOPPED);
            assertThat(engine.isRunning())
                    .as("the engine is not stopped out from under the user")
                    .isTrue();
            assertThat(takeStore.bufferedByteCount())
                    .as("no stream recorded anything, so nothing is flushed")
                    .isEqualTo(bufferedBefore);
            synchronized (notices) {
                assertThat(notices)
                        .as("the user is not told playback paused when nothing was playing")
                        .isEmpty();
            }

            // ...and the return of that device must not open a stream either.
            // onDeviceArrived is gated on DEVICE_LOST, so this is the
            // transitive half of the same defect: the unsolicited open.
            events.emit(new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
                    .DeviceArrived(watched));

            assertThat(spy.opens())
                    .as("a replug of a device we merely watch must not open a stream")
                    .isEmpty();
            assertThat(engine.isStreamOpen()).isFalse();
            assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        } finally {
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void anOpenStreamsDeviceStillLosesAndRecoversAcrossTheClose(@TempDir Path projectRoot) {
        // The behaviour the gate above must NOT have broken, plus the reason
        // the review's "clear the active device and subscription on close" was
        // implemented as a derived fact instead: onDeviceRemoved closes the
        // stream on its way into DEVICE_LOST, so a close-cleared identity
        // would be gone exactly when the arrival needs it and the auto-reopen
        // could never fire. The subscription and the watched identity
        // deliberately outlive the close.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        List<String> notices = new ArrayList<>();
        IncompleteTakeStore takeStore = new IncompleteTakeStore(projectRoot);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null,
                message -> { synchronized (notices) { notices.add(message); } },
                takeStore);
        SpyStreamingBackend spy = new SpyStreamingBackend("SpyLossRecovery");
        DeviceId watched = new DeviceId("SpyLossRecovery", "Live Interface");
        try {
            engine.setStreamingProvision(new StreamingProvision("SpyLossRecovery",
                    List.of(new BackendStreamRung(spy, watched))));
            engine.startAudioOutput();
            assertThat(engine.isStreamOpen())
                    .as("premise: a stream really is open on the watched device")
                    .isTrue();
            // Re-bind AFTER the open so the events are delivered synchronously
            // on this thread: the engine's stream-open seam has just rebound
            // the controller to the spy's own asynchronous publisher.
            DelayedDeviceEventPublisher events = new DelayedDeviceEventPublisher();
            controller.bindBackendDeviceEvents(spy, watched, events);
            events.connect();
            controller.captureRecordingFrames(new float[][]{{0.1f, 0.2f}, {0.3f, 0.4f}}, 2);
            assertThat(takeStore.bufferedByteCount()).isPositive();

            events.emit(new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
                    .DeviceRemoved(watched));

            assertThat(controller.engineState())
                    .as("the OPEN stream's device vanished — this IS device loss")
                    .isEqualTo(EngineState.DEVICE_LOST);
            assertThat(engine.isStreamOpen())
                    .as("output is stopped, not left claiming absent hardware")
                    .isFalse();
            assertThat(takeStore.bufferedByteCount())
                    .as("the in-flight take is flushed so the user can recover it")
                    .isZero();
            synchronized (notices) {
                assertThat(notices).anyMatch(m -> m.contains("disconnected"));
            }

            events.emit(new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
                    .DeviceArrived(watched));

            assertThat(spy.opens())
                    .as("the subscription and the watched identity survived the "
                            + "close, so the device's return reopened the stream")
                    .hasSize(2);
            assertThat(spy.opens().get(1).device()).isEqualTo(watched);
            assertThat(engine.isStreamOpen())
                    .as("reopened open-but-parked; the user re-arms transport")
                    .isTrue();
            assertThat(engine.isStreamPaused()).isTrue();
            assertThat(controller.engineState()).isEqualTo(EngineState.STOPPED);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
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
        try {
            engine.setStreamingProvision(new StreamingProvision("SpyASIO",
                    List.of(new BackendStreamRung(spy, active))));
            // Driven through the spy's OWN deviceEvents() stream, the way
            // production does. Story 316 review: the engine's stream-open
            // seam rebinds this controller to the winning rung's own
            // deviceEvents() on every open, so an externally injected
            // publisher handed to the package-private three-argument bind
            // is unsubscribed the moment the stream opens below.
            controller.bindBackendDeviceEvents(spy, active);

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
            spy.publishDeviceEvent(new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent
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
        waitFor(condition, java.time.Duration.ofSeconds(3));
    }

    /**
     * Explicit-budget wait. A guard whose budget is SHORTER than the waits
     * the production path itself performs turns a correct implementation
     * into a flake, so the format-change tests below — which sit behind a
     * 250 ms coalesce plus up to two bounded 1 s render-pump joins — pass
     * their own budget instead of borrowing the 3 s default.
     */
    private static void waitFor(java.util.function.BooleanSupplier condition,
                                java.time.Duration budget) throws InterruptedException {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("Timed out after " + budget
                    + " waiting for condition to become true");
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

        // Story 316 review: no stream is open, so the utilities are driven
        // by the PROVISIONED backend; the honest active query says "None".
        assertThat(controller.getProvisionedBackendName()).isEqualTo(MockAudioBackend.NAME);
        assertThat(controller.getActiveBackendName())
                .isEqualTo(AudioEngineController.BACKEND_NONE);
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
        // notification and the PROVISIONED backend name the ladder's ACTUAL
        // head (PortAudio when available, else Java Sound). The ACTIVE
        // backend is the open stream's, and nothing is streaming here.
        assertThat(provision.requestedBackendName()).isEqualTo("WASAPI (Exclusive)");
        String headRung = provision.firstRung().backend().name();
        assertThat(headRung).isIn("PortAudio", "Java Sound");
        assertThat(controller.getActiveBackendName())
                .as("closed stream: nothing is active (book §3.2 / §5.2)")
                .isEqualTo(AudioEngineController.BACKEND_NONE);
        assertThat(controller.getProvisionedBackendName())
                .as("closed stream: the backend the next open will try is the ladder's head")
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
        // request; the notification and the PROVISIONED backend name the
        // ladder's ACTUAL head instead of a hardcoded "Java Sound". Nothing
        // is streaming, so the ACTIVE backend is honestly "None".
        assertThat(provision.requestedBackendName()).isEqualTo("ASIO");
        String headRung = provision.firstRung().backend().name();
        assertThat(headRung).isIn("PortAudio", "Java Sound");
        assertThat(controller.getActiveBackendName())
                .isEqualTo(AudioEngineController.BACKEND_NONE);
        assertThat(controller.getProvisionedBackendName()).isEqualTo(headRung);
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
    void theEnumeratedDriverTheUserSelectsBecomesTheOpenLaddersDeviceId(
            @TempDir Path projectRoot) {
        // Story 316 review (R4) — the whole selection chain, end to end, on
        // the shape AsioBackend.listDevices() really produces: drivers whose
        // channel counts are unknown because nothing has loaded them.
        // Reporting them as 0-channel devices took them out of the settings
        // menus (supportsOutput() was false), so "device B" could never be
        // selected, never be persisted, and never reach a rung's DeviceId.
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                        java.util.Map.of("ASIO", () -> {
                            SpyStreamingBackend asio = new SpyStreamingBackend("ASIO");
                            asio.setDevices(List.of(
                                    AudioDeviceInfo.unprobed(0, "Driver A", "ASIO"),
                                    AudioDeviceInfo.unprobed(1, "Driver B", "ASIO")));
                            return asio;
                        }));
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        try {
            // 1. What the Settings output menu is built from: the enumeration,
            //    filtered by supportsOutput() exactly as DeviceEnumerationTask
            //    filters it.
            List<String> selectableOutputs = controller.listDevices("ASIO").stream()
                    .filter(AudioDeviceInfo::supportsOutput)
                    .map(AudioDeviceInfo::name)
                    .toList();
            assertThat(selectableOutputs)
                    .as("a specific driver is offered, not just the blank default")
                    .containsExactly("Driver A", "Driver B");
            assertThat(controller.listDevices("ASIO"))
                    .as("and nothing claims a channel count it has not read from a driver")
                    .allSatisfy(device -> assertThat(device.maxOutputChannels())
                            .isEqualTo(AudioDeviceInfo.CHANNEL_COUNT_UNKNOWN)
                            .isNotPositive());

            // 2. The user picks the second driver; the name is what persists.
            String chosen = selectableOutputs.get(1);
            controller.applyConfiguration(new AudioEngineController.Request(
                    "ASIO", "", chosen, SampleRate.HZ_44100, 512, 16, 1));

            // 3. It survives into the rung the next open will actually use.
            StreamingProvision provision = engine.getStreamingProvision();
            assertThat(provision).isNotNull();
            assertThat(provision.firstRung().backend().name())
                    .as("the requested backend passed the gate and heads the ladder")
                    .isEqualTo("ASIO");
            assertThat(provision.firstRung().device())
                    .as("the head rung opens the DRIVER THE USER CHOSE, never the default")
                    .isEqualTo(new DeviceId("ASIO", "Driver B"))
                    .isNotEqualTo(DeviceId.defaultFor("ASIO"));
            assertThat(provision.requestedDevice())
                    .isEqualTo(new DeviceId("ASIO", "Driver B"));
        } finally {
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void stoppingTheStreamMakesActiveNoneWhileProvisionedStillNamesTheRung(
            @TempDir Path projectRoot) {
        // Story 316 review: "active" is the OPEN stream's backend (book §3.2
        // / §5.2). It used to fall back to the provision's head rung after
        // Stop, so the Settings utility panel kept naming a backend as
        // active while nothing was streaming. The "which backend will the
        // next open try" fact is getProvisionedBackendName()'s, and that one
        // must survive the stop.
        SpyStreamingBackend spy = new SpyStreamingBackend("ASIO");
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                        java.util.Map.of("ASIO", () -> spy));
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 64));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        try {
            controller.applyConfiguration(new AudioEngineController.Request(
                    "ASIO", "", "Dev B", SampleRate.HZ_48000, 64, 24, 1));
            engine.startAudioOutput();
            assertThat(spy.isOpen()).isTrue();
            assertThat(controller.getActiveBackendName())
                    .as("while the stream is open, active IS the open stream's backend")
                    .isEqualTo("ASIO");
            assertThat(controller.getProvisionedBackendName()).isEqualTo("ASIO");

            engine.stopAudioOutput();

            assertThat(spy.isOpen()).isFalse();
            assertThat(controller.getActiveBackendName())
                    .as("after Stop nothing is streaming, so nothing is active")
                    .isEqualTo(AudioEngineController.BACKEND_NONE);
            assertThat(controller.getProvisionedBackendName())
                    .as("the provision still names the rung the next open will try")
                    .isEqualTo("ASIO");
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void aPausedStreamStillNamesItsBackendAsActive(@TempDir Path projectRoot) {
        // Story 316 review — the PAUSED edge, decided deliberately rather
        // than left to whatever isStreamOpen() happens to return.
        //
        // A paused stream renders nothing, so "active" could arguably read
        // BACKEND_NONE. It must not. Pause stops the render pump but never
        // closes the backend: the device handle is still held, the driver is
        // still ours, and no other application can take it. Answering "None"
        // there would tell the Settings utility panel that the device is
        // free while the engine is still holding it — a NEW lie of exactly
        // the kind this story removed from the after-Stop path, only
        // pointing the other way. "Nothing is audible right now" is a
        // transport question, not a backend one.
        //
        // Pinned so a future change to isStreamOpen(),
        // openStreamBackendName() or getActiveBackendName() has to be
        // deliberate: narrowing "open" to RUNNING alone fails here.
        SpyStreamingBackend spy = new SpyStreamingBackend("ASIO");
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                        java.util.Map.of("ASIO", () -> spy));
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 64));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        try {
            controller.applyConfiguration(new AudioEngineController.Request(
                    "ASIO", "", "Dev B", SampleRate.HZ_48000, 64, 24, 1));
            engine.startAudioOutput();
            assertThat(controller.getActiveBackendName()).isEqualTo("ASIO");

            engine.pauseAudioOutput();

            assertThat(engine.isStreamPaused())
                    .as("precondition: the engine really is PAUSED, not stopped")
                    .isTrue();
            assertThat(spy.isOpen())
                    .as("pause never closes the backend — the device handle is held")
                    .isTrue();
            assertThat(spy.closeCount())
                    .as("and nothing was released")
                    .isZero();
            assertThat(controller.getActiveBackendName())
                    .as("a PAUSED stream still owns the device, so it is still "
                            + "the active backend — 'None' would claim the "
                            + "device is free while the engine holds it")
                    .isEqualTo("ASIO");
            assertThat(controller.getProvisionedBackendName())
                    .as("provisioned agrees while a stream is open, paused or not")
                    .isEqualTo("ASIO");

            engine.stopAudioOutput();

            assertThat(spy.isOpen())
                    .as("Stop DOES close it — the contrast that makes the "
                            + "paused answer above a decision and not an accident")
                    .isFalse();
            assertThat(controller.getActiveBackendName())
                    .as("only a closed stream frees the device, and only then "
                            + "is nothing active")
                    .isEqualTo(AudioEngineController.BACKEND_NONE);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void theDriverControlPanelStaysReachableAcrossAStop(@TempDir Path projectRoot) {
        // Story 316 review — pins the entire justification for openControlPanel()
        // resolving the PROVISIONED backend (AudioEngine.getBackend()'s
        // else-branch) rather than the ACTIVE one. Driver settings are what a
        // user reaches for while the transport is STOPPED, which is precisely
        // when getActiveBackendName() honestly answers BACKEND_NONE: sourcing
        // the panel from the active fact would make the action disappear after
        // every Stop, and nothing else in the suite would have noticed.
        MockAudioBackend backend = new MockAudioBackend();
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                        java.util.Map.of(MockAudioBackend.NAME, () -> backend));
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 16, 64));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        try {
            controller.applyConfiguration(new AudioEngineController.Request(
                    MockAudioBackend.NAME, "", "Mock Device",
                    SampleRate.HZ_48000, 64, 16, 1));
            engine.startAudioOutput();

            assertThat(controller.getActiveBackendName())
                    .as("precondition: the stream really opened on the mock")
                    .isEqualTo(MockAudioBackend.NAME);
            controller.openControlPanel().orElseThrow().run();
            assertThat(backend.controlPanelInvocationCount())
                    .as("while streaming, the panel action reaches the open backend")
                    .isEqualTo(1);

            engine.stopAudioOutput();

            assertThat(controller.getActiveBackendName())
                    .as("Stop closes the stream, so nothing holds a device and "
                            + "nothing is active")
                    .isEqualTo(AudioEngineController.BACKEND_NONE);
            assertThat(controller.getProvisionedBackendName())
                    .as("the provision survives the Stop and still names the "
                            + "head rung")
                    .isEqualTo(MockAudioBackend.NAME);

            Optional<Runnable> afterStop = controller.openControlPanel();
            assertThat(afterStop)
                    .as("the driver panel is still offered with nothing active — "
                            + "it is sourced from the PROVISIONED backend")
                    .isPresent();
            afterStop.orElseThrow().run();
            assertThat(backend.controlPanelInvocationCount())
                    .as("and the returned action really reaches that provisioned "
                            + "backend rather than being an empty gesture")
                    .isEqualTo(2);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void anOpenDriverControlPanelDoesNotBlockTheRestOfTheController(
            @TempDir Path projectRoot) throws Exception {
        // Story 316 re-review, stall audit A1. The panel action used to run
        // inside `synchronized (DefaultAudioEngineController.this)`. That call
        // blocks for as long as the user leaves the driver's MODAL dialog
        // open — the SDK exempts this one downcall from AsioControlThread's
        // fifteen-second budget for exactly that reason ("a user reading a
        // driver dialog is not a wedged driver") — so every synchronized
        // controller method queued behind the user's attention span. Worst of
        // all, SettingsDialog.updateAudioUtilityDisabledState() calls
        // openControlPanel() ON THE FX THREAD to decide whether to enable the
        // button, so an open driver panel froze the entire UI.
        //
        // Every wait below is bounded, so a regression FAILS on a timeout
        // rather than hanging the suite.
        CountDownLatch panelRunning = new CountDownLatch(1);
        CountDownLatch releasePanel = new CountDownLatch(1);
        SpyStreamingBackend spy = new SpyStreamingBackend("ASIO");
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                        java.util.Map.of("ASIO", () -> spy));
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 64));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        spy.setControlPanelAction(() -> {
            panelRunning.countDown();
            try {
                // Stands in for the modal driver dialog: returns only when
                // this test says so. The bound is a suite safety net only;
                // the finally block below always releases it first.
                releasePanel.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            controller.applyConfiguration(new AudioEngineController.Request(
                    "ASIO", "", "Dev B", SampleRate.HZ_48000, 64, 24, 1));
            workers.execute(controller.openControlPanel().orElseThrow());
            assertThat(panelRunning.await(5, TimeUnit.SECONDS))
                    .as("precondition: the driver panel really is up and will not "
                            + "return until this test releases it")
                    .isTrue();

            Callable<String> provisionedQuery = controller::getProvisionedBackendName;
            assertThat(workers.submit(provisionedQuery).get(5, TimeUnit.SECONDS))
                    .as("an ordinary synchronized controller query must not queue "
                            + "behind the user's attention span")
                    .isEqualTo("ASIO");

            Callable<Optional<Runnable>> panelQuery = controller::openControlPanel;
            assertThat(workers.submit(panelQuery).get(5, TimeUnit.SECONDS))
                    .as("this is the exact call SettingsDialog makes on the FX "
                            + "thread from updateAudioUtilityDisabledState()")
                    .isPresent();
        } finally {
            releasePanel.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void aBackendIsNotClosedWhileItsDriverControlPanelIsOpen(@TempDir Path projectRoot) {
        // Story 316 re-review, stall audit A1, second half. Taking the modal
        // panel action out from under the controller monitor removed a real
        // protection: the monitor made a concurrent close IMPOSSIBLE for the
        // dialog's lifetime. Closing a backend frees exactly the native state
        // the dialog is running on (an AsioBufferSwitchShim upcall arena, a
        // SourceDataLine, a Pa_ handle), so without a replacement the fix
        // would have traded a UI freeze for a native use-after-free. The
        // narrow replacement is a registration every close path consults,
        // mirroring the refusal closeProvisionBackendsOnceQuiesced() already
        // makes for a render pump that will not confirm quiescence.
        //
        // The close is issued FROM INSIDE the panel action deliberately: that
        // makes this test independent of which lock the panel holds, so it
        // pins the close-skip guard alone. The sibling test above pins the
        // monitor removal.
        SpyStreamingBackend spy = new SpyStreamingBackend("ASIO");
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                        java.util.Map.of("ASIO", () -> spy));
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 64));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        AtomicInteger closesSeenWhileThePanelWasOpen = new AtomicInteger(-1);
        spy.setControlPanelAction(() -> {
            controller.shutdown();
            closesSeenWhileThePanelWasOpen.set(spy.closeCount());
        });
        try {
            controller.applyConfiguration(new AudioEngineController.Request(
                    "ASIO", "", "Dev B", SampleRate.HZ_48000, 64, 24, 1));

            controller.openControlPanel().orElseThrow().run();

            assertThat(closesSeenWhileThePanelWasOpen)
                    .as("a shutdown that lands while the driver's modal dialog is up "
                            + "must refuse to free the native state that dialog is "
                            + "running on")
                    .hasValue(0);

            controller.shutdown();
            assertThat(spy.closeCount())
                    .as("and the refusal is scoped to the dialog's lifetime — once "
                            + "the panel returns the instance is closeable again, or "
                            + "the guard would leak every backend a user ever opened "
                            + "a panel on")
                    .isEqualTo(1);
        } finally {
            engine.stop();
        }
    }

    @Test
    void aFailedOpenLeavesActiveNoneWhileProvisionedStillNamesTheHeadRung(
            @TempDir Path projectRoot) throws InterruptedException {
        // Story 316 review, the OTHER half of the same finding as the test
        // above: the Settings utility panel used to keep naming a backend as
        // active not just after Stop but after an open FAILURE, because
        // getActiveBackendName() fell back to the provision's head rung when
        // no stream was open. Here EVERY rung refuses, so startAudioOutput()
        // throws and nothing is streaming at all — yet the pre-fix body
        // would still have answered "ASIO".
        SpyStreamingBackend head = new SpyStreamingBackend("ASIO");
        head.failOpensWith(new AudioBackendException("ASIO driver refused the open"));
        SpyStreamingBackend fallback = new SpyStreamingBackend("SpyFallback");
        fallback.failOpensWith(new AudioBackendException("fallback refused it too"));

        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 24, 64));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));
        // Hand-built all-failing ladder: no real hardware, and the failure
        // is the ladder's own, not a provisioning-gate rejection.
        engine.setStreamingProvision(new StreamingProvision("ASIO", List.of(
                new BackendStreamRung(head, new DeviceId("ASIO", "Dev B")),
                new BackendStreamRung(fallback, DeviceId.defaultFor("SpyFallback")))));

        // Both rungs publish a fallback event on the way down, and the
        // spies carry DISTINCT messages so "the first failure" is an
        // assertable fact rather than a claim about an exception type.
        com.benesquivelmusic.daw.sdk.event.EventBus previousBus =
                com.benesquivelmusic.daw.core.event.EventBusPublisher.getDefault();
        com.benesquivelmusic.daw.core.event.DefaultEventBus bus =
                new com.benesquivelmusic.daw.core.event.DefaultEventBus();
        List<com.benesquivelmusic.daw.sdk.audio.BackendFallbackEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch published = new CountDownLatch(2);
        bus.on(com.benesquivelmusic.daw.sdk.audio.BackendFallbackEvent.class, event -> {
            events.add(event);
            published.countDown();
        });
        com.benesquivelmusic.daw.core.event.EventBusPublisher.setDefault(bus);
        try {
            assertThatThrownBy(engine::startAudioOutput)
                    .as("every rung refused, so the open must surface the FIRST "
                            + "rung's failure rather than pretend a stream exists "
                            + "or report the last rung's")
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessage("ASIO driver refused the open");

            assertThat(engine.openStreamBackendName()).isEmpty();
            assertThat(head.isOpen()).isFalse();
            assertThat(fallback.isOpen()).isFalse();

            assertThat(controller.getActiveBackendName())
                    .as("no rung opened, so nothing is active — the utility "
                            + "panel must not name a backend after a failed open")
                    .isEqualTo(AudioEngineController.BACKEND_NONE);
            assertThat(controller.getProvisionedBackendName())
                    .as("the provision still names the head rung the next open "
                            + "will try")
                    .isEqualTo("ASIO");

            // The EventBus half of the same honesty contract: with no rung
            // streaming, every published hop must say so — "none" for both
            // active components — while still naming the user's request.
            assertThat(published.await(5, TimeUnit.SECONDS))
                    .as("both failed hops must publish a BackendFallbackEvent")
                    .isTrue();
            assertThat(events).hasSize(2);
            assertThat(events).allSatisfy(event -> {
                assertThat(event.requestedBackend()).isEqualTo("ASIO");
                assertThat(event.requestedDevice()).isEqualTo("Dev B");
                assertThat(event.activeBackend())
                        .as("nothing opened, so no event may name an active backend")
                        .isEqualTo("none");
                assertThat(event.activeDevice())
                        .as("nothing opened, so no event may name an active device")
                        .isEqualTo("none");
            });
            assertThat(events.get(0).cause())
                    .as("the hops publish in ladder order")
                    .isEqualTo("ASIO driver refused the open");
            assertThat(events.get(1).cause()).isEqualTo("fallback refused it too");
        } finally {
            com.benesquivelmusic.daw.core.event.EventBusPublisher.setDefault(previousBus);
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
            bus.close();
        }
    }

    @Test
    void provisionedBackendIsNoneWhenNothingIsProvisionedAtAll(
            @TempDir Path projectRoot) {
        // The third branch of getProvisionedBackendName(): no open stream AND
        // no installed provision — a controller nobody has configured yet.
        // Every other test of this query either installs a provision or
        // overrides the method, so the BACKEND_NONE floor was untested.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));
        try {
            assertThat(engine.getStreamingProvision())
                    .as("construction must not install a provision — that is what "
                            + "makes this the untested branch")
                    .isNull();
            assertThat(controller.getProvisionedBackendName())
                    .isEqualTo(AudioEngineController.BACKEND_NONE);
        } finally {
            controller.shutdown();
            engine.stop();
        }
    }

    @Test
    void unknownBackendNameIsRejectedLikeAnUnavailableOneAndPreservesTheRequest(
            @TempDir Path projectRoot) {
        // Story 316 review: a name this build has no backend for (a stale
        // persisted setting, say) used to be silently swapped for the
        // default provision — the requested backend/device vanished and no
        // fallback event or warning was ever produced, so requested !=
        // active was invisible on every surface. It now takes the same path
        // as an unavailable backend: request preserved, user notified,
        // rejection carried as a pending failed hop.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        List<String> notices = new ArrayList<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null,
                message -> { synchronized (notices) { notices.add(message); } },
                new IncompleteTakeStore(projectRoot));

        controller.applyConfiguration(new AudioEngineController.Request(
                "NoSuchBackend", "", "Studio Interface Out", SampleRate.HZ_44100, 512, 16, 1));

        StreamingProvision provision = engine.getStreamingProvision();
        assertThat(provision).isNotNull();
        assertThat(provision.requestedBackendName())
                .as("the provision still names the USER'S request")
                .isEqualTo("NoSuchBackend");
        assertThat(provision.requestedDevice())
                .as("paired with the OUTPUT device the user asked for")
                .isEqualTo(new DeviceId("NoSuchBackend", "Studio Interface Out"));
        String headRung = provision.firstRung().backend().name();
        assertThat(headRung).isIn("PortAudio", "Java Sound");
        synchronized (notices) {
            assertThat(notices)
                    .as("exactly one fallback notification, naming the ladder's actual head")
                    .hasSize(1)
                    .first()
                    .asString()
                    .startsWith("NoSuchBackend")
                    .contains("not available — falling back to " + headRung);
        }
        assertThat(provision.pendingFailedHopCauses())
                .as("the rejection rides into the ladder for the engine to publish")
                .hasSize(1)
                .first().asString()
                .contains("NoSuchBackend")
                .contains("not a backend this build knows");
        engine.stop();
        controller.shutdown();
    }

    @Test
    void blankAndNoneBackendNamesStillYieldTheDefaultLadderSilently(
            @TempDir Path projectRoot) {
        // Story 316 review: the unknown-name rejection above must not leak
        // into the "no preference" names — a blank or BACKEND_NONE request
        // is the default ladder, with nothing to notify and no failed hop.
        for (String noPreference : List.of("", AudioEngineController.BACKEND_NONE)) {
            AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
            List<String> notices = new ArrayList<>();
            DefaultAudioEngineController controller = new DefaultAudioEngineController(
                    engine, null,
                    message -> { synchronized (notices) { notices.add(message); } },
                    new IncompleteTakeStore(projectRoot));

            controller.applyConfiguration(new AudioEngineController.Request(
                    noPreference, "", "", SampleRate.HZ_44100, 512, 16, 1));

            StreamingProvision provision = engine.getStreamingProvision();
            assertThat(provision).as(noPreference).isNotNull();
            assertThat(provision.requestedBackendName())
                    .as("[%s] the default ladder names its own head as the request", noPreference)
                    .isIn("PortAudio", "Java Sound");
            synchronized (notices) {
                assertThat(notices)
                        .as("[%s] no preference means nothing to fall back from", noPreference)
                        .isEmpty();
            }
            assertThat(provision.pendingFailedHopCauses())
                    .as("[%s] no rejection, no pending failed hop", noPreference)
                    .isEmpty();
            engine.stop();
            controller.shutdown();
        }
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
            // OPEN stream's device lands in DEVICE_LOST synchronously on the
            // emitting thread. The stream really has to be open — story 316
            // review gated DEVICE_LOST on AudioEngine.isStreamOpen(), so a
            // removal against a merely-watched endpoint would leave this test
            // in STOPPED and make its own premise vacuous.
            SpyStreamingBackend spy = new SpyStreamingBackend("Mock");
            DeviceId lost = new DeviceId("Mock", "Mock Out");
            engine.setStreamingProvision(new StreamingProvision("Mock",
                    List.of(new BackendStreamRung(spy, lost))));
            engine.startAudioOutput();
            assertThat(engine.isStreamOpen()).isTrue();
            DelayedDeviceEventPublisher events = new DelayedDeviceEventPublisher();
            controller.bindBackendDeviceEvents(spy, lost, events);
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
    void gateRejectedRequestPublishesAFallbackEventOnTheEventBusToo(
            @TempDir Path projectRoot) throws Exception {
        // Story 316 review: the availability/streaming gate removes an
        // unavailable / non-streaming requested backend from the ladder
        // BEFORE the engine sees it, so the ladder walk records no failed
        // hop for it — and when the fallback head then opens on the first
        // try, requested != active was published NOWHERE. The story's
        // contract is that every fallback is a visible fact on the EventBus
        // seam; the NotificationManager message is a different surface, not
        // a substitute. The rejection is therefore carried forward as a
        // pending failed hop the engine publishes once the winner is known.
        SpyStreamingBackend gated = new SpyStreamingBackend("ASIO");
        // Present on the host, but with no implemented streaming path —
        // the WASAPI / CoreAudio / JACK shape, which is a different
        // user-facing fact from "not installed" and must read differently.
        gated.setSupportsStreaming(false);
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                        java.util.Map.of("ASIO", () -> gated));
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

            StreamingProvision provision = engine.getStreamingProvision();
            assertThat(provision.requestedBackendName())
                    .as("the provision still names the USER'S request")
                    .isEqualTo("ASIO");
            assertThat(provision.firstRung().backend().name())
                    .as("the gate really did drop the request from the ladder")
                    .isNotEqualTo("ASIO");
            assertThat(provision.pendingFailedHopCauses())
                    .as("the gate rejection is carried forward for the engine to publish")
                    .hasSize(1)
                    .first().asString()
                    .contains("ASIO")
                    .contains("streaming path is not available in this build");

            try {
                engine.startAudioOutput();
            } catch (RuntimeException everyRungFailed) {
                // Acceptable on hosts where no fallback rung can open — the
                // gate hop still published its event, asserted below.
            }

            assertThat(published.await(5, TimeUnit.SECONDS))
                    .as("the GATE rejection publishes a BackendFallbackEvent too")
                    .isTrue();
            String winner = engine.openStreamBackendName().orElse("none");
            com.benesquivelmusic.daw.sdk.audio.BackendFallbackEvent gateHop = events.get(0);
            assertThat(gateHop.requestedBackend())
                    .as("the event names the ORIGINAL user request")
                    .isEqualTo("ASIO");
            assertThat(gateHop.requestedDevice())
                    .as("paired with the device the user asked for, never a fallback's")
                    .isEqualTo(DeviceId.defaultFor("ASIO").name());
            assertThat(gateHop.activeBackend())
                    .as("and with the rung that ACTUALLY opened — known only after the walk")
                    .isEqualTo(winner)
                    .isNotEqualTo("ASIO");
            assertThat(gateHop.cause())
                    .as("the cause says WHY the gate rejected it")
                    .contains("ASIO")
                    .contains("streaming path is not available in this build");
        } finally {
            com.benesquivelmusic.daw.core.event.EventBusPublisher.setDefault(previousBus);
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
            bus.close();
        }
    }

    @Test
    void aDirectEngineOpenRebindsDeviceEventsToTheWinningRung(@TempDir Path projectRoot)
            throws Exception {
        // Story 316 review: a normal Play calls AudioEngine.startAudioOutput()
        // straight from TransportController — this controller is not in that
        // loop at all. When the requested rung failed and a fallback opened,
        // the controller stayed subscribed to the LOSER and kept naming the
        // requested device, so hot-unplug handling, channel queries and
        // latency overrides every one targeted a backend/device that was not
        // the open stream. The engine's stream-open seam re-points them.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));
        SpyStreamingBackend refusing = new SpyStreamingBackend("Requested");
        refusing.failOpensWith(
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendException(
                        "requested rung refused the open"));
        SpyStreamingBackend winner = new SpyStreamingBackend("Fallback");
        DeviceId requestedDevice = new DeviceId("Requested", "Requested Out");
        DeviceId winnerDevice = new DeviceId("Fallback", "Fallback Out");
        try {
            engine.setStreamingProvision(new StreamingProvision("Requested", List.of(
                    new BackendStreamRung(refusing, requestedDevice),
                    new BackendStreamRung(winner, winnerDevice))));
            // The pre-open bind applyConfiguration performs while the engine
            // is stopped: with no open stream the requested rung is the
            // honest thing to watch.
            controller.bindBackendDeviceEvents(refusing, requestedDevice);
            assertThat(controller.getWatchedDevice()).contains(requestedDevice);

            // The Play path: straight to the engine, never through this
            // controller.
            engine.startAudioOutput();

            assertThat(engine.openStreamBackendName())
                    .as("non-vacuity guard: the ladder really did fall back")
                    .contains("Fallback");
            assertThat(controller.getWatchedDevice())
                    .as("the controller now names the device that is actually open")
                    .contains(winnerDevice);

            // ...and the SUBSCRIPTION moved with it, not merely the field:
            // an unplug announced by the WINNER reaches this controller.
            winner.simulateDeviceRemoved(winnerDevice);
            waitForLong(() -> controller.engineState() == EngineState.DEVICE_LOST);
            assertThat(controller.engineState())
                    .as("the device-event subscription follows the open stream")
                    .isEqualTo(EngineState.DEVICE_LOST);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    @Test
    void shutdownDoesNotCloseProvisionBackendsWhileThePumpMayStillRender(
            @TempDir Path projectRoot) throws Exception {
        // Story 316 review: a timed-out stopAudioOutput() deliberately leaves
        // the backend open because the pump may still be inside sink /
        // awaitSinkCapacity. Closing every provision backend anyway would
        // release native state — an ASIO bufferSwitch upcall arena, a
        // SourceDataLine, a PortAudio stream handle — under the live render
        // thread. Deferring to process exit is strictly safer.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));
        SpyStreamingBackend wedging = new SpyStreamingBackend("Wedging");
        // Never opened: isolates the CONTROLLER'S provision cleanup from the
        // engine's own hand-back of the open handle.
        SpyStreamingBackend spare = new SpyStreamingBackend("Spare");
        try {
            engine.setStreamingProvision(new StreamingProvision("Wedging", List.of(
                    new BackendStreamRung(wedging, new DeviceId("Wedging", "Out")),
                    new BackendStreamRung(spare, new DeviceId("Spare", "Out")))));
            wedging.blockAwait = true;
            engine.startAudioOutput();
            waitForLong(() -> wedging.blockedAwaitEntries.get() >= 1);

            controller.shutdown();

            assertThat(wedging.closeCount())
                    .as("the open backend is NOT closed under a possibly-live pump")
                    .isZero();
            assertThat(spare.closeCount())
                    .as("and neither is any other provision instance")
                    .isZero();
        } finally {
            wedging.blockAwait = false;
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    @Test
    void shutdownClosesProvisionBackendsOnceQuiescenceIsConfirmed(
            @TempDir Path projectRoot) {
        // Companion to the wedged case: the quiescence guard must not
        // silently disable shutdown cleanup on the normal path.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot));
        SpyStreamingBackend streaming = new SpyStreamingBackend("Streaming");
        SpyStreamingBackend spare = new SpyStreamingBackend("Spare");
        try {
            engine.setStreamingProvision(new StreamingProvision("Streaming", List.of(
                    new BackendStreamRung(streaming, new DeviceId("Streaming", "Out")),
                    new BackendStreamRung(spare, new DeviceId("Spare", "Out")))));
            engine.startAudioOutput();
            assertThat(engine.isStreamOpen()).isTrue();

            controller.shutdown();

            assertThat(spare.closeCount())
                    .as("the never-opened rung proves the controller's provision "
                            + "cleanup ran — the engine only ever hands back the "
                            + "OPEN handle")
                    .isEqualTo(1);
            assertThat(streaming.closeCount())
                    .as("and the open backend was handed back and closed")
                    .isGreaterThanOrEqualTo(1);
            assertThat(streaming.isOpen()).isFalse();
        } finally {
            engine.stopAudioOutput();
            engine.stop();
        }
    }

    @Test
    void aReconfigureIsRefusedWholeWhileTheRenderPumpMayStillBeRendering(
            @TempDir Path projectRoot) throws Exception {
        // Story 316 re-review: applyConfiguration DISCARDED stopAudioOutput()'s
        // quiescence verdict and then mutated the engine FORMAT. A
        // format-only change reaches neither engine-side guard —
        // setStreamingProvision refuses a SWAP, requireQuiescedPump refuses a
        // REOPEN — and setFormat itself refuses only while isRunning(), which
        // the stop() just above it has already cleared. A pump whose bounded
        // join timed out could therefore still be inside processBlock,
        // rendering through the RenderPipeline shaped by the OLD format,
        // while the new format was stored under it.
        //
        // This replaces the story-316 'refusedProvisionSwapClosesTheIncoming
        // BackendInstances' test, whose premise this fix preempts: the
        // reconfigure is now refused BEFORE it builds the incoming provision,
        // so there is no orphaned incoming instance left to close.
        // installProvision's close-the-orphans catch stays as the defence for
        // its other caller (installDefaultProvision) and any future one.
        AtomicInteger incomingBuilds = new AtomicInteger();
        SpyStreamingBackend incoming = new SpyStreamingBackend("SpyIncoming");
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                        java.util.Map.of("SpyIncoming", () -> {
                            incomingBuilds.incrementAndGet();
                            return incoming;
                        }));
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        AtomicInteger postReconfigureRuns = new AtomicInteger();
        List<String> notified = java.util.Collections.synchronizedList(new ArrayList<>());
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, postReconfigureRuns::incrementAndGet, notified::add,
                new IncompleteTakeStore(projectRoot), selector);
        SpyStreamingBackend outgoing = new SpyStreamingBackend("Outgoing");
        StreamingProvision outgoingProvision = new StreamingProvision("Outgoing", List.of(
                new BackendStreamRung(outgoing, new DeviceId("Outgoing", "Out"))));
        try {
            engine.setStreamingProvision(outgoingProvision);
            outgoing.blockAwait = true;
            engine.startAudioOutput();
            waitForLong(() -> outgoing.blockedAwaitEntries.get() >= 1);
            AudioFormat before = engine.getFormat();

            Throwable failure = catchThrowable(() -> controller.applyConfiguration(
                    new AudioEngineController.Request(
                            "SpyIncoming", "", "", SampleRate.HZ_48000, 256, 24, 1)));

            assertThat(engine.getFormat())
                    .as("the engine format is NOT mutated while a render pump may still"
                            + " be inside processBlock")
                    .isEqualTo(before);
            assertThat(failure)
                    .as("and the refusal reaches the caller")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("render pump");
            assertThat(incomingBuilds.get())
                    .as("the refusal precedes the provision rebuild, so no incoming"
                            + " backend instance is constructed and then orphaned")
                    .isZero();
            assertThat(engine.getStreamingProvision())
                    .as("the engine still points at the provision it was streaming")
                    .isSameAs(outgoingProvision);
            assertThat(outgoing.closeCount())
                    .as("and the wedged backend's handle is untouched")
                    .isZero();
            assertThat(postReconfigureRuns.get())
                    .as("the post-reconfigure callback still runs on the refusal path —"
                            + " the settings dialog must re-enable on EVERY path")
                    .isEqualTo(1);
            assertThat(controller.engineState())
                    .as("the controller lands in STOPPED so the user can retry")
                    .isEqualTo(EngineState.STOPPED);
            assertThat(notified)
                    .as("and the user is told why")
                    .anyMatch(message -> message.contains("render pump"));
        } finally {
            outgoing.blockAwait = false;
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
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

    // -- Story 316 re-review: the R5 sibling path (A1) ---------------------

    @Test
    void aDriverFormatChangeDoesNotTouchTheEngineFormatWhileTheRenderPumpMayStillBeRendering(
            @TempDir Path projectRoot) throws InterruptedException {
        // The SIBLING of aReconfigureIsRefusedWholeWhileTheRenderPumpMayStill
        // BeRendering. applyConfiguration was made to honour
        // stopAudioOutput()'s quiescence verdict; performFormatChangeReopen
        // discarded the very same verdict and then mutated the very same
        // engine format field, from the device-event worker instead of the
        // settings thread. Its only extra protection was a fixed 200 ms
        // sleep, which proves nothing about whether the render pump left
        // processBlock. Closing one caller of a shared predicate and leaving
        // the other open leaves the hazard fully reachable.
        WedgedRig rig = wedgedFormatChangeRig(projectRoot);
        try {
            AudioFormat before = rig.engine().getFormat();

            publishBufferSizeChange(rig, 24);

            // Wait on the callback, not on a delay: it is the one side effect
            // BOTH outcomes produce (step 8 of a completed reopen, and the
            // refusal path), so this wait cannot mask either result.
            waitFor(() -> rig.postReconfigureRuns().get() >= 1,
                    java.time.Duration.ofSeconds(20));

            assertThat(rig.engine().getFormat())
                    .as("the engine format must NOT be rewritten while a render pump may"
                            + " still be inside processBlock, rendering through the"
                            + " RenderPipeline, buffer pool and pump planes this format"
                            + " sizes")
                    .isEqualTo(before);
            assertThat(rig.backend().closeCount())
                    .as("and nothing the wedged pump is still calling into was torn down")
                    .isZero();
            assertThat(rig.backend().isOpen())
                    .as("the stream the engine could not confirm quiesced is left alone")
                    .isTrue();
        } finally {
            releaseWedgedRig(rig);
        }
    }

    @Test
    void aRefusedDriverFormatChangeTellsTheUserAndComesToRestInStopped(
            @TempDir Path projectRoot) throws InterruptedException {
        // How the refusal SURFACES on this path is a decision, not a
        // leftover: performFormatChangeReopen runs on the format-change
        // worker with no caller to throw at, and its existing failure
        // handling logs. A refusal that only logged would be invisible to
        // the user whose driver just renegotiated - indistinguishable from a
        // reconfigure that silently did nothing.
        WedgedRig rig = wedgedFormatChangeRig(projectRoot);
        try {
            publishBufferSizeChange(rig, 24);

            waitFor(() -> rig.postReconfigureRuns().get() >= 1
                            && rig.controller().engineState() == EngineState.STOPPED,
                    java.time.Duration.ofSeconds(20));

            List<String> messages;
            synchronized (rig.notified()) {
                messages = List.copyOf(rig.notified());
            }
            assertThat(messages)
                    .as("the user is told the reconfigure was refused, and why")
                    .anyMatch(message -> message.contains("render pump"));
            assertThat(messages)
                    .as("and is never also told it succeeded - nothing was reconfigured")
                    .noneMatch(message -> message.equals("Audio engine reconfigured"));
            assertThat(rig.postReconfigureRuns().get())
                    .as("the post-reconfigure callback runs on EVERY terminal path of this"
                            + " method, refusals included, so a surface that disabled itself"
                            + " for the reconfigure re-enables")
                    .isEqualTo(1);
            assertThat(rig.stateWhenCallbackRan().get())
                    .as("and it runs BEFORE the terminal state, per this codebase's"
                            + " side-effects-precede-terminal-state rule")
                    .isEqualTo(EngineState.RECONFIGURING);
            assertThat(rig.controller().engineState())
                    .as("STOPPED is the honest resting state - stop() cleared the engine's"
                            + " running flag - and it leaves the user able to re-arm"
                            + " transport and retry once the pump unblocks; RECONFIGURING"
                            + " would strand every surface in a transition that has ended")
                    .isEqualTo(EngineState.STOPPED);
        } finally {
            releaseWedgedRig(rig);
        }
    }

    // -- Story 316 re-review: restored orphan-cleanup coverage (A2) --------

    @Test
    void aPlayRacingTheReconfigureRefusesTheSwapAndClosesTheIncomingBackendInstances(
            @TempDir Path projectRoot) {
        // Restores the coverage the R5 fix removed. installProvision's
        // orphan-cleanup catch closes the INCOMING backend instances when the
        // engine refuses a provision swap; its only test was deleted on the
        // premise that applyConfiguration's new quiescence gate preempts every
        // such refusal. It does not - it only moves the window.
        //
        // The gate runs BEFORE buildStreamingProvision; the swap runs AFTER
        // it. A plain Play occupies exactly that window: TransportController
        // calls AudioEngine.startAudioOutput() DIRECTLY (three call sites) and
        // never takes this controller's monitor. That open runs startLocked(),
        // so by the time installProvision reaches setStreamingProvision the
        // engine is RUNNING and the swap is refused outright - a different
        // refusal from the wedged-pump one, orphaning exactly the same
        // freshly-constructed incoming instances.
        //
        // The interleaving is driven deterministically through the backend
        // factory, which the production code invokes inside that very window,
        // so no production test-seam is added for it.
        SpyStreamingBackend outgoing = new SpyStreamingBackend("Outgoing");
        SpyStreamingBackend incoming = new SpyStreamingBackend("SpyIncoming");
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        AtomicBoolean raced = new AtomicBoolean();
        com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector selector =
                new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                        java.util.Map.of("SpyIncoming", () -> {
                            if (raced.compareAndSet(false, true)) {
                                engine.startAudioOutput();
                            }
                            return incoming;
                        }));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine, null, NotificationManager.noop(),
                new IncompleteTakeStore(projectRoot), selector);
        StreamingProvision outgoingProvision = new StreamingProvision("Outgoing", List.of(
                new BackendStreamRung(outgoing, DeviceId.defaultFor("Outgoing"))));
        engine.setStreamingProvision(outgoingProvision);
        try {
            Throwable failure = catchThrowable(() -> controller.applyConfiguration(
                    new AudioEngineController.Request(
                            "SpyIncoming", "", "", SampleRate.HZ_44100, 512, 16, 1)));

            assertThat(raced)
                    .as("precondition: the racing open really did land inside the window")
                    .isTrue();
            assertThat(engine.isRunning())
                    .as("precondition: that open left the engine RUNNING, which is what "
                            + "makes the swap refusable at all")
                    .isTrue();
            assertThat(failure)
                    .as("the engine refuses the swap whole rather than re-pointing itself "
                            + "at a new ladder while a stream renders through the old one")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("while engine is running");
            assertThat(incoming.closeCount())
                    .as("buildStreamingProvision had already CONSTRUCTED the incoming"
                            + " instances by then, so an exception that simply propagated"
                            + " left them neither installed nor closed - a leak of exactly"
                            + " the native handles this ordering exists to protect")
                    .isEqualTo(1);
            assertThat(outgoing.closeCount())
                    .as("the OUTGOING instance is still live on the engine - untouched")
                    .isZero();
            assertThat(engine.getStreamingProvision())
                    .as("the swap aborted whole; the engine still points at the old"
                            + " provision")
                    .isSameAs(outgoingProvision);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
    }

    /**
     * A controller whose engine is streaming through a render pump that
     * cannot be joined: {@code stopAudioOutput()} answers {@code false}, the
     * one condition under which no caller may change the engine format.
     */
    private record WedgedRig(AudioEngine engine,
                             DefaultAudioEngineController controller,
                             SpyStreamingBackend backend,
                             DeviceId device,
                             List<String> notified,
                             AtomicInteger postReconfigureRuns,
                             AtomicReference<EngineState> stateWhenCallbackRan) {
    }

    private static WedgedRig wedgedFormatChangeRig(Path projectRoot)
            throws InterruptedException {
        SpyStreamingBackend wedged = new SpyStreamingBackend("SpyWedged");
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000.0, 2, 16, 64));
        List<String> notified = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger postReconfigureRuns = new AtomicInteger();
        AtomicReference<EngineState> stateWhenCallbackRan = new AtomicReference<>();
        AtomicReference<DefaultAudioEngineController> controllerRef = new AtomicReference<>();
        DefaultAudioEngineController controller = new DefaultAudioEngineController(
                engine,
                () -> {
                    stateWhenCallbackRan.compareAndSet(
                            null, controllerRef.get().engineState());
                    postReconfigureRuns.incrementAndGet();
                },
                notified::add,
                new IncompleteTakeStore(projectRoot));
        controllerRef.set(controller);
        DeviceId device = new DeviceId("SpyWedged", "Wedged Device");
        engine.setStreamingProvision(new StreamingProvision("SpyWedged",
                List.of(new BackendStreamRung(wedged, device))));
        wedged.blockAwait = true;
        // The engine's stream-open seam binds this rung's device events to the
        // controller, which is what makes the published FormatChangeRequested
        // below reach performFormatChangeReopen at all.
        engine.startAudioOutput();
        awaitWedgedPump(wedged);
        return new WedgedRig(engine, controller, wedged, device, notified,
                postReconfigureRuns, stateWhenCallbackRan);
    }

    private static void releaseWedgedRig(WedgedRig rig) {
        rig.backend().blockAwait = false;
        rig.engine().stopAudioOutput();
        rig.engine().stop();
        rig.controller().shutdown();
    }

    private static void publishBufferSizeChange(WedgedRig rig, int bitDepth) {
        rig.backend().publishDeviceEvent(
                new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent.FormatChangeRequested(
                        rig.device(),
                        Optional.of(new com.benesquivelmusic.daw.sdk.audio.AudioFormat(
                                48_000.0, 2, bitDepth)),
                        new FormatChangeReason.BufferSizeChange()));
    }

    private static void awaitWedgedPump(SpyStreamingBackend backend) {
        try {
            waitForLong(() -> backend.blockedAwaitEntries.get() >= 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the render pump to wedge", e);
        }
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
