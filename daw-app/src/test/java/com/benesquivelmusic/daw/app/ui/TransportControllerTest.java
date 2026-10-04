package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.persistence.ProjectManager;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CaptureFlushService;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.DiskHeadroomWatch;
import com.benesquivelmusic.daw.core.recording.EarlySeal;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.StopSealFailure;
import com.benesquivelmusic.daw.core.recording.TakeDirectories;
import com.benesquivelmusic.daw.core.recording.TakeManifest;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.app.ui.recording.RecordState;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoHistoryListener;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.BackendFallbackEvent;
import com.benesquivelmusic.daw.sdk.audio.CaptureRequirement;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.event.BusEvent;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.EventBusMetrics;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Tests for the {@link TransportController} helper logic that can be exercised
 * without a live JavaFX scene or toolkit.
 *
 * <p>Story 315 — the controller now implements the story-290
 * {@code TransportIntentHandler} ({@code start}/{@code pause}/{@code stop}
 * replace the retired {@code onPlay}/{@code onStop} gesture entries), no
 * longer receives time-ticker runnables or the time-display label (the display
 * is bound by {@code TransportControlBinder.bindTimeDisplay}), and no longer
 * holds the Stop/Loop buttons (their state is binder-driven).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TransportControllerTest {

    @Test
    void shouldCreateTransportControllerClass() {
        // Story 293 retired the Host interface; verify the controller class is
        // still loadable and is the package-private final extraction it should be.
        Class<?> controllerClass = TransportController.class;
        assertThat(controllerClass).isNotNull();
        assertThat(java.lang.reflect.Modifier.isFinal(controllerClass.getModifiers())).isTrue();
    }

    @Test
    void controllerNoLongerHoldsTheBinderOwnedButtonsOrTheTimeDisplay() {
        // Story 315 — the Stop/Loop buttons and the time display are entirely
        // binder-driven; the controller must hold no reference through which a
        // stray disable/setText/pseudo-class poke could return.
        assertThat(TransportController.class.getDeclaredFields())
                .noneMatch(f -> f.getName().equals("stopButton")
                        || f.getName().equals("loopButton")
                        || f.getName().equals("timeDisplay"));
    }

    // ── Harness ──────────────────────────────────────────────────────────────

    /** The play button handed to the controller, for enablement assertions. */
    private Button playButton;
    private Button recordButton;
    private Label statusLabel;
    /** The REC indicator handed to the controller, for recording-lifecycle assertions. */
    private Label recIndicator;
    /** The status-bar label handed to the controller, for "UI tail untouched" assertions. */
    private Label statusBarLabel;
    /** Engine handed to the latest controller, for honest stream-state assertions and cleanup. */
    private AudioEngine audioEngine;
    /** Actionable notification surface handed to the latest controller. */
    private NotificationBar notificationBar;
    /** Undo history handed to the latest controller (story 323 review: "Record Audio" registration). */
    private UndoManager undoManager;
    /** Counts invocations of the injected Open Audio Settings route. */
    private AtomicInteger audioSettingsOpens;
    /** Story 322 — the session input handed to the latest controller (blank = backend default). */
    private StubSessionInputSelection sessionInputSelection = new StubSessionInputSelection();
    /**
     * The still-writing warning's delay handed to the latest controller: fired
     * only by the tests that fire it, so no assertion here depends on a take
     * finishing within {@code TAKE_STILL_WRITING_DELAY} of its Stop (the
     * production delay is exercised in {@code StopPublishesTheTakeOnALaterFxTurnTest}).
     */
    private ManualFxDelay stillWritingDelay;
    /** Holds the latest take's capture thread, for the tests that stop a take still being written. */
    private final CaptureThreadHold hold = new CaptureThreadHold();

    /** The hang guard of a wait for a real take's capture thread: its files created, or the take published. */
    private static final long TAKE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);

    /**
     * Story 323 — an audio take streams into the project's own
     * {@code audio/takes}, so every test that starts (or tries to start) an
     * audio take gives its project this directory through
     * {@link #giveTheProjectADirectory(DawProject)}; a project without one is
     * refused before the engine is touched
     * ({@link #recordIsRefusedWithAVisibleErrorWhenTheProjectHasNoDirectory()}).
     * Cleaned up by JUnit after the test's {@code @AfterEach}, by which time
     * every take started here has been stopped (segments sealed and closed)
     * — by the test, or, when it failed first, by
     * {@link #stopEveryTakeAndCloseEngine()}.
     */
    @TempDir
    Path projectDirectory;

    /** Every controller {@link #newControllerWithProvision} made for the running test. */
    private final List<TransportController> controllers = new CopyOnWriteArrayList<>();

    /** The capture-flush threads alive when the running test began. */
    private CaptureFlushThreadWatch flushThreads;

    @BeforeEach
    void rememberTheLiveCaptureFlushThreads() {
        flushThreads = CaptureFlushThreadWatch.snapshot();
    }

    private void giveTheProjectADirectory(DawProject project) {
        project.setMetadata(project.getMetadata().withPath(projectDirectory));
    }

    /**
     * On every path: releases a held capture thread, then stops each take a
     * controller of the test left recording or preparing and waits (bounded)
     * until nothing is in flight or being written — so no capture thread and
     * no open segment outlives the test or the temporary directory — and only
     * then closes the engine. Every controller is tried; the first failure
     * fails the test with the later ones suppressed.
     */
    @AfterEach
    void stopEveryTakeAndCloseEngine() throws Exception {
        AssertionError failure = null;
        try {
            hold.release();
            for (TransportController controller : controllers) {
                try {
                    runHandler(() -> {
                        if (controller.isRecordingInFlight()) {
                            controller.stop();
                        }
                    });
                    awaitOnFx(() -> !controller.isRecordingInFlight() && !controller.isTakeBeingWritten(),
                            "a take the test left running is stopped and published");
                } catch (AssertionError | Exception e) {
                    if (failure == null) {
                        failure = new AssertionError("a take the test left running could not be ended", e);
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            AssertionError leak = flushThreads.joinNewThreads();
            if (leak != null) {
                if (failure == null) {
                    failure = leak;
                } else {
                    failure.addSuppressed(leak);
                }
            }
        } catch (AssertionError | Exception e) {
            if (failure == null) {
                failure = new AssertionError("the takes the test left running could not be ended", e);
            } else {
                failure.addSuppressed(e);
            }
        } finally {
            try {
                for (TransportController controller : controllers) {
                    runHandler(controller::retire);
                }
            } catch (AssertionError | Exception e) {
                if (failure == null) {
                    failure = new AssertionError("retiring a controller failed", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
            controllers.clear();
            if (audioEngine != null) {
                audioEngine.stopAudioOutput();
                audioEngine.stop();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private TransportController newController(DawProject project) throws Exception {
        return newController(project, new MockAudioBackend());
    }

    /**
     * Builds the controller over an engine that can actually open a stream
     * (story 316 review). {@code streamingBackend} becomes the engine's whole
     * fallback ladder; passing {@code null} leaves the engine with no
     * streaming provision so refusal behavior can be tested explicitly.
     */
    private TransportController newController(DawProject project,
                                              AudioBackend streamingBackend) throws Exception {
        StreamingProvision provision = streamingBackend == null
                ? null
                : new StreamingProvision(
                        streamingBackend.name(),
                        List.of(new BackendStreamRung(streamingBackend,
                                DeviceId.defaultFor(streamingBackend.name()))));
        return newControllerWithProvision(project, provision);
    }

    private TransportController newControllerWithProvision(
            DawProject project, StreamingProvision provision) throws Exception {
        AtomicReference<TransportController> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            AudioEngine engine = new AudioEngine(project.getFormat());
            audioEngine = engine;
            if (provision != null) {
                engine.setStreamingProvision(provision);
            }
            UndoManager undo = new UndoManager();
            undoManager = undo;
            NotificationBar nb = new NotificationBar();
            nb.setAnimated(false);
            notificationBar = nb;
            audioSettingsOpens = new AtomicInteger();
            statusLabel = new Label();
            statusBarLabel = new Label();
            recIndicator = new Label();
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
            playButton = new Button();
            Button record = new Button();
            recordButton = record;
            // Story 293 — the Host is retired; pass direct functional deps.
            // Values mirror the former stub: snap off, quarter grid, no
            // count-in, no-op MIDI flash, and the former Host defaults for
            // latency compensation (true) and reported latency (UNKNOWN).
            ref.set(new TransportController(project, engine, undo, nb,
                    statusLabel, statusBarLabel, recIndicator,
                    playButton, record,
                    () -> false,
                    () -> GridResolution.QUARTER,
                    () -> CountInMode.OFF,
                    track -> { },
                    () -> true,
                    () -> com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN,
                    sessionInputSelection,
                    audioSettingsOpens::incrementAndGet,
                    null));
            stillWritingDelay = new ManualFxDelay();
            ref.get().setStillWritingDelayForTest(stillWritingDelay);
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        controllers.add(ref.get());
        return ref.get();
    }

    /**
     * Presses Record and waits, bounded, until the start has settled: an
     * audio take's Record only enters PREPARING (PR #978 review 5391920205),
     * and the take records — or its start fails — on a later FX turn, once its
     * capture thread has created its files. A start that was refused, failed
     * at once or is MIDI-only has settled when Record returns.
     */
    private static void record(TransportController controller) throws Exception {
        runHandler(controller::toggleRecord);
        awaitOnFx(() -> !controller.isPreparingTake(), "the take's start settled");
    }

    /**
     * Presses Stop and waits, bounded, until nothing is being written: a
     * stopped take is published on a later FX turn, once its capture thread
     * has terminated, and the files of a cancelled start are removed once
     * that thread has deleted them. A stopped take's audio is read back
     * from its segments off the FX thread before it is published, and the
     * take is being written until then, so this waits for that read too.
     * Every test that records a real take ends with it, so the take's
     * files are closed before the temporary directory is removed.
     */
    private static void stopAndAwaitTheTake(TransportController controller) throws Exception {
        runHandler(controller::stop);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the stopped take was published");
    }

    /** Waits, at most {@link #TAKE_BUDGET_NANOS}, polling on the FX thread, until {@code condition} holds there. */
    private static void awaitOnFx(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + TAKE_BUDGET_NANOS;
        while (true) {
            AtomicReference<Boolean> holds = new AtomicReference<>(false);
            runHandler(() -> holds.set(condition.getAsBoolean()));
            if (holds.get()) {
                return;
            }
            assertThat(System.nanoTime() - deadline < 0).as("%s, within 30 s", what).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    /** Streaming backend whose selected endpoint always refuses to open. */
    private static final class FailingAudioBackend implements AudioBackend {
        private final String name;
        private final String failureMessage;
        private Runnable beforeFailure = () -> { };

        private FailingAudioBackend() {
            this("Broken Backend", "device is disconnected");
        }

        private FailingAudioBackend(String name, String failureMessage) {
            this.name = name;
            this.failureMessage = failureMessage;
        }

        @Override public String name() { return name; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public List<AudioDeviceInfo> listDevices() { return List.of(); }
        @Override
        public void open(DeviceId device,
                         com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                         int bufferFrames) {
            beforeFailure.run();
            throw new AudioBackendException(failureMessage);
        }
        @Override public Flow.Publisher<AudioBlock> inputBlocks() { return _ -> { }; }
        @Override public void sink(AudioBlock block) { }
        @Override public boolean isOpen() { return false; }
        @Override public void close() { }
    }

    private static final class CaptureTrackingBackend implements AudioBackend {
        private final MockAudioBackend delegate = new MockAudioBackend();
        private final boolean captureCapable;
        private CaptureRequirement requestedCapture;

        private CaptureTrackingBackend(boolean captureCapable) {
            this.captureCapable = captureCapable;
        }

        @Override public String name() { return delegate.name(); }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public List<AudioDeviceInfo> listDevices() { return delegate.listDevices(); }
        @Override public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                                   int bufferFrames) {
            delegate.open(device, format, bufferFrames);
        }
        @Override public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                                   int bufferFrames, CaptureRequirement capture) {
            requestedCapture = capture;
            delegate.open(device, format, bufferFrames);
        }
        @Override public int openedInputChannels() { return captureCapable ? delegate.openedInputChannels() : 0; }
        @Override public Flow.Publisher<AudioBlock> inputBlocks() { return delegate.inputBlocks(); }
        @Override public void sink(AudioBlock block) { delegate.sink(block); }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() { delegate.close(); }
    }

    /** Opens normally once, then refuses the subscription used by a resume. */
    private static final class ResumeFailingAudioBackend implements AudioBackend {
        private final MockAudioBackend delegate = new MockAudioBackend();
        private final AtomicInteger subscriptions = new AtomicInteger();

        @Override public String name() { return "Fallback Backend"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public List<AudioDeviceInfo> listDevices() { return List.of(); }
        @Override
        public void open(DeviceId device,
                         com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                         int bufferFrames) {
            delegate.open(device, format, bufferFrames);
        }
        @Override
        public Flow.Publisher<AudioBlock> inputBlocks() {
            return subscriber -> {
                if (subscriptions.incrementAndGet() > 1) {
                    throw new AudioBackendException("fallback callback refused to resume");
                }
                delegate.inputBlocks().subscribe(subscriber);
            };
        }
        @Override public void sink(AudioBlock block) { delegate.sink(block); }
        @Override public int openedInputChannels() { return delegate.openedInputChannels(); }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() { delegate.close(); }
    }

    /** Opens its device, then refuses the capture subscription that starts the pump. */
    private static final class PumpStartFailingAudioBackend implements AudioBackend {
        private final MockAudioBackend delegate = new MockAudioBackend();
        private Runnable beforeFailure = () -> { };

        @Override public String name() { return "Fallback Pump Backend"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public List<AudioDeviceInfo> listDevices() { return List.of(); }
        @Override
        public void open(DeviceId device,
                         com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                         int bufferFrames) {
            delegate.open(device, format, bufferFrames);
        }
        @Override
        public Flow.Publisher<AudioBlock> inputBlocks() {
            return _ -> {
                beforeFailure.run();
                throw new AudioBackendException("fallback callback refused to start");
            };
        }
        @Override public void sink(AudioBlock block) { delegate.sink(block); }
        @Override public int openedInputChannels() { return delegate.openedInputChannels(); }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() { delegate.close(); }
    }

    /** Minimal synchronous bus seam for ordering a lifecycle announcement in a test. */
    private static final class PublishHookEventBus implements EventBus {
        private final Consumer<BusEvent> publishHook;

        private PublishHookEventBus(Consumer<BusEvent> publishHook) {
            this.publishHook = publishHook;
        }

        @Override
        public void publish(BusEvent event) {
            publishHook.accept(event);
        }

        @Override
        public <E extends BusEvent> Flow.Publisher<E> subscribe(Class<E> type) {
            throw new UnsupportedOperationException("subscriptions are not used by this test bus");
        }

        @Override
        public <E extends BusEvent> Subscription on(
                Class<E> type, DispatchMode mode, Consumer<? super E> handler) {
            throw new UnsupportedOperationException("subscriptions are not used by this test bus");
        }

        @Override
        public EventBusMetrics metrics() {
            throw new UnsupportedOperationException("metrics are not used by this test bus");
        }

        @Override
        public void close() {
            // No resources: publish runs synchronously on the lifecycle caller.
        }
    }

    @Test
    void onTogglePreRollShouldEnableWithDefaultBars() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        TransportController controller = newController(project);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.onTogglePreRoll();
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        PreRollPostRoll prpr = project.getTransport().getPreRollPostRoll();
        assertThat(prpr.enabled()).isTrue();
        assertThat(prpr.preBars()).isEqualTo(TransportController.DEFAULT_BARS);
        assertThat(prpr.postBars()).isEqualTo(0);
    }

    @Test
    void onTogglePreRollTwiceShouldDisablePreservingBarCounts() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        // Start with pre=3, post=1 enabled.
        project.getTransport().setPreRollPostRoll(PreRollPostRoll.enabled(3, 1));
        TransportController controller = newController(project);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.onTogglePreRoll(); // toggles pre off → preBars becomes 0
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        PreRollPostRoll prpr = project.getTransport().getPreRollPostRoll();
        // Pre is now off; post is untouched.
        assertThat(prpr.preBars()).isEqualTo(0);
        assertThat(prpr.postBars()).isEqualTo(1);
        // enabled is derived: still true because postBars > 0.
        assertThat(prpr.enabled()).isTrue();
    }

    @Test
    void onTogglePostRollShouldBeIndependentOfPreRoll() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        // Pre-roll is active with 2 bars, post-roll is off.
        project.getTransport().setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));
        TransportController controller = newController(project);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.onTogglePostRoll(); // enables post independently
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        PreRollPostRoll prpr = project.getTransport().getPreRollPostRoll();
        assertThat(prpr.preBars()).isEqualTo(2);  // unchanged
        assertThat(prpr.postBars()).isEqualTo(TransportController.DEFAULT_BARS);
        assertThat(prpr.enabled()).isTrue();
    }

    @Test
    void playWithPreRollShouldSeekBackByConfiguredBars() throws Exception {
        // Issue test: enable pre-roll with preBars=2, set playhead at bar 25,
        // press Shift+Space, assert transport seeks to bar 23 and plays.
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        // 4/4 time signature; bar 25 = beat 96 (zero-indexed: 24 bars × 4 beats).
        int beatsPerBar = transport.getTimeSignatureNumerator(); // 4
        transport.setPositionInBeats(24.0 * beatsPerBar);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));

        TransportController controller = newController(project);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                controller.playWithPreRoll();
            } catch (RuntimeException e) {
                // Audio engine may fail to open in a headless environment;
                // that does not affect the transport position assertion.
            }
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Seeked back by 2 × 4 = 8 beats: bar 23 = beat 88.
        assertThat(transport.getPositionInBeats())
                .isEqualTo((24.0 - 2.0) * beatsPerBar);
        // The transport must report it is in pre-roll so the recording
        // pipeline suppresses input capture during bars 23–24.
        assertThat(transport.isInPreRoll()).isTrue();
        assertThat(transport.isInputCaptureGated()).isTrue();
    }

    @Test
    void createPreRollPostRollControlsShouldWireSpinnersToTransport() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        TransportController controller = newController(project);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.createPreRollPostRollControls();
            // Simulate the user typing "5" into the pre-roll spinner.
            controller.preRollSpinnerForTest().getValueFactory().setValue(5);
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Spinner edits propagate to the transport configuration; the
        // enabled flag is derived (true because preBars = 5 > 0).
        PreRollPostRoll prpr = project.getTransport().getPreRollPostRoll();
        assertThat(prpr.preBars()).isEqualTo(5);
        assertThat(prpr.enabled()).isTrue();
    }

    @Test
    void spinnerSetToZeroShouldUpdateToggleState() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        // Start with pre-roll active.
        project.getTransport().setPreRollPostRoll(PreRollPostRoll.enabled(3, 0));
        TransportController controller = newController(project);

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.createPreRollPostRollControls();
            // Pre-roll toggle should initially be selected.
            assertThat(controller.preRollToggleForTest().isSelected()).isTrue();
            // Set pre-roll spinner to 0 — should deselect the toggle.
            controller.preRollSpinnerForTest().getValueFactory().setValue(0);
            assertThat(controller.preRollToggleForTest().isSelected()).isFalse();
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        PreRollPostRoll prpr = project.getTransport().getPreRollPostRoll();
        assertThat(prpr.preBars()).isEqualTo(0);
        assertThat(prpr.enabled()).isFalse();
    }

    @Test
    void postRollStopShouldUseRequestStop() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(0, 2));
        transport.play(); // Transport must be playing for requestStop to enter post-roll.

        TransportController controller = newController(project);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            controller.stop();
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Transport should be in post-roll (still playing), not stopped.
        assertThat(transport.isInPostRoll()).isTrue();
        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.PLAYING);
    }

    // ── Intent handlers (story 315: start/pause/stop replace onPlay/onStop) ──

    @Test
    void startWhileStoppedShouldStartPlayback() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);

        TransportController controller = newController(project);
        runHandler(controller::start);

        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.PLAYING);
    }

    @Test
    void failedPlayOpenStaysStoppedAndOffersAudioSettings() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        TransportController controller = newController(project, new FailingAudioBackend());

        runHandler(controller::start);

        assertThat(transport.getState())
                .as("a refused stream cannot authorize PLAYING")
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(transport.isRealTimeClockActive())
                .as("no callback/ticker owns time after refusal").isFalse();
        assertThat(audioEngine.isStreamOpen()).isFalse();
        assertThat(audioEngine.isRunning())
                .as("an engine started solely for the failed open is rolled back")
                .isFalse();
        assertThat(statusBarLabel.getText()).doesNotContain("Playing");
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage())
                .contains("Broken Backend", "<default>", "device is disconnected");
        assertThat(notificationBar.getPill().getActionButton().getText())
                .isEqualTo("Open Audio Settings");

        runHandler(() -> notificationBar.getPill().getActionButton().fire());
        assertThat(audioSettingsOpens).hasValue(1);
    }

    @Test
    void failedFreshStartReportsTheInvocationProvisionAfterConcurrentReprovision()
            throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        var originalBackend = new FailingAudioBackend(
                "Original Requested", "original device is disconnected");
        DeviceId originalDevice = new DeviceId(
                "Original Requested", "Original Device");
        StreamingProvision originalProvision = new StreamingProvision(
                originalBackend.name(), originalDevice,
                List.of(new BackendStreamRung(originalBackend, originalDevice)));
        TransportController controller = newControllerWithProvision(project, originalProvision);

        var replacementBackend = new MockAudioBackend();
        DeviceId replacementDevice = new DeviceId(
                replacementBackend.name(), "Replacement Device");
        StreamingProvision replacementProvision = new StreamingProvision(
                replacementBackend.name(), replacementDevice,
                List.of(new BackendStreamRung(replacementBackend, replacementDevice)));
        CountDownLatch insideOriginalOpen = new CountDownLatch(1);
        CountDownLatch reprovisionStarted = new CountDownLatch(1);
        CountDownLatch reprovisionCompleted = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        originalBackend.beforeFailure = () -> {
            insideOriginalOpen.countDown();
            try {
                assertThat(reprovisionStarted.await(5, TimeUnit.SECONDS))
                        .as("the replacement caller reached setStreamingProvision")
                        .isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted inside original open", interrupted);
            }
        };
        Thread reprovisioner = Thread.ofPlatform()
                .name("transport-failure-reprovision")
                .daemon(true)
                .unstarted(() -> {
                    try {
                        assertThat(insideOriginalOpen.await(5, TimeUnit.SECONDS)).isTrue();
                        reprovisionStarted.countDown();
                        audioEngine.setStreamingProvision(replacementProvision);
                    } catch (Throwable failure) {
                        threadFailure.set(failure);
                    } finally {
                        reprovisionCompleted.countDown();
                    }
                });

        var previousBus = EventBusPublisher.getDefault();
        var eventBus = new PublishHookEventBus(event -> {
            if (event instanceof BackendFallbackEvent) {
                try {
                    assertThat(reprovisionCompleted.await(5, TimeUnit.SECONDS))
                            .as("reprovision completed before the refusal message was built")
                            .isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                            "interrupted awaiting the replacement provision", interrupted);
                }
            }
        });
        try {
            EventBusPublisher.setDefault(eventBus);
            reprovisioner.start();

            runHandler(controller::start);

            reprovisioner.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(reprovisioner.isAlive()).isFalse();
            assertThat(threadFailure.get()).isNull();
            assertThat(audioEngine.getStreamingProvision()).isSameAs(replacementProvision);
            assertThat(notificationBar.getMessage())
                    .contains("Original Requested", "Original Device",
                            "original device is disconnected", "could not start")
                    .doesNotContain(replacementBackend.name(), "Replacement Device");
        } finally {
            EventBusPublisher.setDefault(previousBus);
        }
    }

    @Test
    void failedFallbackPumpStartNamesTheAttemptedFallbackEndpoint() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        var requestedBackend = new FailingAudioBackend(
                "Requested Backend", "requested device is disconnected");
        var fallbackBackend = new PumpStartFailingAudioBackend();
        DeviceId requestedDevice = new DeviceId("Requested Backend", "Requested Device");
        DeviceId fallbackDevice =
                new DeviceId("Fallback Pump Backend", "Fallback Pump Device");
        StreamingProvision provision = new StreamingProvision(
                "Requested Backend",
                requestedDevice,
                List.of(
                        new BackendStreamRung(requestedBackend, requestedDevice),
                        new BackendStreamRung(fallbackBackend, fallbackDevice)));
        TransportController controller = newControllerWithProvision(project, provision);

        runHandler(controller::start);

        assertThat(transport.getState())
                .as("a failed fallback pump cannot authorize PLAYING")
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(audioEngine.isStreamOpen()).isFalse();
        assertThat(audioEngine.isRunning()).isFalse();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage())
                .contains("Fallback Pump Backend", "Fallback Pump Device",
                        "fallback callback refused to start")
                .doesNotContain("Requested Backend", "Requested Device");
    }

    @Test
    void exactFailureEndpointWinsOverAStreamOpenedByAReentrantAnnouncement() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        var requestedBackend = new FailingAudioBackend(
                "Requested Backend", "requested device is disconnected");
        var failedWinner = new PumpStartFailingAudioBackend();
        DeviceId requestedDevice = new DeviceId("Requested Backend", "Requested Device");
        DeviceId failedDevice =
                new DeviceId("Fallback Pump Backend", "Failed Pump Device");
        StreamingProvision failedProvision = new StreamingProvision(
                "Requested Backend",
                requestedDevice,
                List.of(
                        new BackendStreamRung(requestedBackend, requestedDevice),
                        new BackendStreamRung(failedWinner, failedDevice)));
        TransportController controller = newControllerWithProvision(project, failedProvision);
        audioEngine.setGraph(transport, null, null);

        var laterWinner = new MockAudioBackend();
        DeviceId laterDevice = new DeviceId(laterWinner.name(), "Later Live Device");
        StreamingProvision laterProvision = new StreamingProvision(
                laterWinner.name(), laterDevice,
                List.of(new BackendStreamRung(laterWinner, laterDevice)));
        var observerCalls = new AtomicInteger();
        var reentrantFailure = new AtomicReference<Throwable>();
        failedWinner.beforeFailure = () -> {
            transport.play();
            transport.setPositionInBeats(12.0);
        };
        transport.addChangeListener(kind -> {
            if (kind != Transport.ChangeKind.POSITION
                    || !observerCalls.compareAndSet(0, 1)) {
                return;
            }
            try {
                transport.stop();
                audioEngine.setStreamingProvision(laterProvision);
                audioEngine.startAudioOutput();
            } catch (Throwable failure) {
                reentrantFailure.set(failure);
            }
        });

        runHandler(controller::start);

        assertThat(observerCalls)
                .as("the outer failure delivered the same-thread clock-release announcement")
                .hasValue(1);
        assertThat(reentrantFailure.get()).isNull();
        assertThat(audioEngine.openStreamDevice())
                .as("a later stream really is live before the refusal message is built")
                .contains(laterDevice);
        assertThat(notificationBar.getMessage())
                .contains("Fallback Pump Backend", "Failed Pump Device",
                        "fallback callback refused to start")
                .doesNotContain(laterWinner.name(), "Later Live Device",
                        "Requested Backend", "Requested Device");
        assertThat(transport.getState())
                .as("the failed controller request still cannot authorize PLAYING")
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
    }

    @Test
    void failedPausedFallbackResumeNamesTheOpenEndpointAndAnnouncesNoStart() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        var requestedBackend = new FailingAudioBackend(
                "Requested Backend", "requested device is disconnected");
        var fallbackBackend = new ResumeFailingAudioBackend();
        DeviceId requestedDevice = new DeviceId("Requested Backend", "Requested Device");
        DeviceId fallbackDevice = new DeviceId("Fallback Backend", "Fallback Device");
        StreamingProvision provision = new StreamingProvision(
                "Requested Backend",
                requestedDevice,
                List.of(
                        new BackendStreamRung(requestedBackend, requestedDevice),
                        new BackendStreamRung(fallbackBackend, fallbackDevice)));
        TransportController controller = newControllerWithProvision(project, provision);

        runHandler(controller::start);
        assertThat(audioEngine.openStreamBackendName()).contains("Fallback Backend");
        assertThat(audioEngine.openStreamDevice()).contains(fallbackDevice);
        runHandler(controller::pause);
        assertThat(transport.getState())
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.PAUSED);
        assertThat(audioEngine.isStreamPaused()).isTrue();

        var previousBus = EventBusPublisher.getDefault();
        var eventBus = new DefaultEventBus();
        try {
            EventBusPublisher.setDefault(eventBus);
            runHandler(controller::start);

            assertThat(transport.getState())
                    .as("a refused resume publishes no Started transition")
                    .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.PAUSED);
            assertThat(eventBus.metrics().publishedByType())
                    .doesNotContainKey("TransportEvent.Started");
            assertThat(audioEngine.isStreamOpen()).isTrue();
            assertThat(audioEngine.isStreamPaused()).isTrue();
            assertThat(transport.isRealTimeClockActive()).isFalse();
            assertThat(statusBarLabel.getText()).doesNotContain("Playing");
            assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
            assertThat(notificationBar.getMessage())
                    .contains("Fallback Backend", "Fallback Device", "could not resume",
                            "fallback callback refused to resume")
                    .doesNotContain("Requested Backend", "Requested Device");
        } finally {
            EventBusPublisher.setDefault(previousBus);
            eventBus.close();
        }
    }

    @Test
    void failedPreRollOpenDoesNotRewindOrPlay() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.setPositionInBeats(24.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));
        TransportController controller = newController(project, new FailingAudioBackend());

        runHandler(controller::playWithPreRoll);

        assertThat(transport.getState())
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isEqualTo(24.0);
        assertThat(transport.isInPreRoll()).isFalse();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getPill().getActionButton().getText())
                .isEqualTo("Open Audio Settings");
    }

    @Test
    void failedMidiOnlyRecordOpenStartsNothingAndStaysStopped() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Track midiTrack = new Track("Armed MIDI", TrackType.MIDI);
        midiTrack.setArmed(true);
        project.addTrack(midiTrack);
        TransportController controller = newController(project, new FailingAudioBackend());

        runHandler(controller::toggleRecord);

        assertThat(project.getTransport().getState())
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(project.getTransport().isRealTimeClockActive()).isFalse();
        assertThat(midiTrack.isRecording())
                .as("the output opens before any MidiRecorder or track flag")
                .isFalse();
        assertThat(recIndicator.isVisible()).isFalse();
        assertThat(statusBarLabel.getText()).doesNotContain("Recording");
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage())
                .contains("refused for recording", "Broken Backend", "<default>");
        assertThat(notificationBar.getPill().getActionButton().getText())
                .isEqualTo("Open Audio Settings");
    }

    @Test
    void recordStartWarnsWhenAnArmedTrackChoseAnInputOtherThanTheSessionDevice() throws Exception {
        // Story 322 — the per-track index does not route audio (recording opens
        // the SESSION device); a disagreement is surfaced as one WARNING naming
        // the track and both devices, never silently ignored.
        DawProject project = new DawProject("test", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);   // story 323: the take lives in the project
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        Track agreeing = project.createAudioTrack("Agreeing");
        agreeing.setArmed(true);
        MockAudioBackend backend = new MockAudioBackend();
        AudioDeviceInfo mockDevice = backend.listDevices().get(0);
        vox.setInputDeviceIndex(mockDevice.index());   // the enumerated device — not the session one
        sessionInputSelection = new StubSessionInputSelection("Session In [ASIO]");
        TransportController controller = newController(project, backend);
        audioEngine.setGraph(project.getTransport(), project.getMixer(), project.getTracks());
        try {
            record(controller);
            awaitSessionInputCheck(controller);

            assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.WARNING);
            assertThat(notificationBar.getMessage())
                    .contains("Recording uses the session input 'Session In [ASIO]'")
                    .contains("track(s) Vox chose '" + mockDevice.qualifiedName() + "'")
                    .doesNotContain("Agreeing")
                    .contains("story 326");
        } finally {
            stopAndAwaitTheTake(controller);
        }
    }

    @Test
    void recordStartEnumeratesDevicesOffTheFxThreadAndStillWarns() throws Exception {
        // Story 322 fix round (S7): AudioBackend.listDevices() is a driver walk
        // (on ASIO it blocks on the control thread), so the record-start check
        // enumerates on a worker and only its WARNING lands on the FX thread.
        DawProject project = new DawProject("test", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);   // story 323: the take lives in the project
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        EnumerationTrackingBackend backend = new EnumerationTrackingBackend();
        AudioDeviceInfo mockDevice = new MockAudioBackend().listDevices().get(0);
        vox.setInputDeviceIndex(mockDevice.index());   // the enumerated device — not the session one
        sessionInputSelection = new StubSessionInputSelection("Session In [ASIO]");
        TransportController controller = newController(project, backend);
        audioEngine.setGraph(project.getTransport(), project.getMixer(), project.getTracks());
        try {
            record(controller);
            assertThat(backend.enumerationsOnFxThread.get())
                    .as("the record handler and its readiness turn enumerated nothing on the FX thread").isZero();
            awaitSessionInputCheck(controller);

            assertThat(backend.enumerations.get()).as("the check did enumerate (off-thread)").isPositive();
            assertThat(backend.enumerationsOnFxThread.get()).as("never on the FX thread").isZero();
            assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.WARNING);
            assertThat(notificationBar.getMessage())
                    .contains("track(s) Vox chose '" + mockDevice.qualifiedName() + "'");
        } finally {
            stopAndAwaitTheTake(controller);
        }
    }

    /**
     * Story 322 fix round (S7): the session-input mismatch check enumerates
     * devices on a worker; wait for it, then for the FX turn that shows (or,
     * when every armed track agrees, does not show) its toast.
     */
    private static void awaitSessionInputCheck(TransportController controller) throws Exception {
        Optional<Thread> check = controller.pendingSessionInputCheck();
        assertThat(check).as("an audio take starts the session-input check").isPresent();
        check.get().join(TimeUnit.SECONDS.toMillis(5));
        assertThat(check.get().isAlive()).as("the input check completed").isFalse();
        runHandler(() -> { });   // FX barrier: everything the check posted has run
    }

    @Test
    void recordStartStaysOnTheInfoToastWhenEveryArmedTrackAgreesWithTheSessionDevice() throws Exception {
        DawProject project = new DawProject("test", new AudioFormat(48000, 2, 16, 256));
        Track vox = project.createAudioTrack("Vox");
        vox.setArmed(true);
        MockAudioBackend backend = new MockAudioBackend();
        AudioDeviceInfo mockDevice = backend.listDevices().get(0);
        vox.setInputDeviceIndex(mockDevice.index());
        sessionInputSelection = new StubSessionInputSelection(mockDevice.qualifiedName());
        giveTheProjectADirectory(project);   // story 323: the take lives in the project
        TransportController controller = newController(project, backend);
        audioEngine.setGraph(project.getTransport(), project.getMixer(), project.getTracks());
        try {
            record(controller);
            awaitSessionInputCheck(controller);

            assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.INFO);
            assertThat(notificationBar.getMessage()).contains("Recording started");
        } finally {
            stopAndAwaitTheTake(controller);
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "true, true"})
    void instrumentRecordingRequiresCaptureOnlyWhenPhysicalInputIsAssigned(boolean physicalInput,
                                                                           boolean captureCapable) throws Exception {
        var project = new DawProject("Keyboard recording", new AudioFormat(48_000, 2, 16, 256));
        giveTheProjectADirectory(project);   // story 323: the take lives in the project
        var keyboard = project.createAudioTrack("Keyboard");
        keyboard.setArmed(true);
        keyboard.setInputRouting(physicalInput ? new InputRouting(1, 1) : InputRouting.NONE);
        project.getMixerChannelForTrack(keyboard).addInsert(PluginSignalPathActivationTest.builtInSlot(
                com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin.class));
        var backend = new CaptureTrackingBackend(captureCapable);
        var controller = newController(project, backend);
        audioEngine.setGraph(project.getTransport(), project.getMixer(), project.getTracks());
        try {
            record(controller);
            assertThat(backend.requestedCapture).isEqualTo(physicalInput
                    ? CaptureRequirement.REQUIRED : CaptureRequirement.OPTIONAL);
            boolean started = !physicalInput || captureCapable;
            assertThat(keyboard.isRecording()).isEqualTo(started);
            assertThat(recIndicator.isVisible()).isEqualTo(started);
            assertThat(project.getTransport().getState()).isEqualTo(started
                    ? com.benesquivelmusic.daw.core.transport.TransportState.RECORDING
                    : com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        } finally {
            stopAndAwaitTheTake(controller);
            audioEngine.stopAudioOutput();
            project.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedInstrumentAndPhysicalInputTracksRequireCaptureForTheWholeTake(boolean inputTrackHasInstrument)
            throws Exception {
        var project = new DawProject("Mixed recording", new AudioFormat(48_000, 2, 16, 256));
        giveTheProjectADirectory(project);   // story 323: the take lives in the project
        var keyboard = project.createAudioTrack("Keyboard");
        keyboard.setArmed(true);
        keyboard.setInputRouting(InputRouting.NONE);
        project.getMixerChannelForTrack(keyboard).addInsert(PluginSignalPathActivationTest.builtInSlot(
                com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin.class));
        var microphone = project.createAudioTrack("Microphone");
        microphone.setArmed(true);
        microphone.setInputRouting(new InputRouting(1, 1));
        if (inputTrackHasInstrument) {
            project.getMixerChannelForTrack(microphone).addInsert(PluginSignalPathActivationTest.builtInSlot(
                    com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin.class));
        }
        var backend = new CaptureTrackingBackend(false);
        var controller = newController(project, backend);
        audioEngine.setGraph(project.getTransport(), project.getMixer(), project.getTracks());
        try {
            record(controller);
            assertThat(backend.requestedCapture).isEqualTo(CaptureRequirement.REQUIRED);
            assertThat(keyboard.isRecording()).isFalse();
            assertThat(microphone.isRecording()).isFalse();
            assertThat(recIndicator.isVisible()).isFalse();
            assertThat(project.getTransport().getState())
                    .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        } finally {
            stopAndAwaitTheTake(controller);
            audioEngine.stopAudioOutput();
            project.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void graphKeyboardKeepsAuditionStreamThroughPauseStopAndBypassUntilRemoved() throws Exception {
        var project = new DawProject("Keyboard", new AudioFormat(48_000, 2, 16, 256));
        var track = project.createAudioTrack("Keyboard");
        var channel = project.getMixerChannelForTrack(track);
        var slot = PluginSignalPathActivationTest.builtInSlot(
                com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin.class);
        channel.addInsert(slot);
        var controller = newController(project);
        audioEngine.setGraph(project.getTransport(), project.getMixer(), project.getTracks());
        try {
            runHandler(controller::start);
            runHandler(controller::pause);
            assertThat(project.getTransport().getState()).isEqualTo(
                    com.benesquivelmusic.daw.core.transport.TransportState.PAUSED);
            assertThat(audioEngine.isStreamOpen()).isTrue();
            assertThat(audioEngine.isStreamPaused()).isFalse();
            runHandler(controller::stop);
            assertThat(project.getTransport().getState()).isEqualTo(
                    com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
            assertThat(audioEngine.isStreamOpen()).isTrue();
            assertThat(audioEngine.isStreamPaused()).isFalse();
            channel.setInsertBypassed(0, true);
            runHandler(controller::stop);
            assertThat(audioEngine.isStreamOpen()).as("unbypass can audition without transport restart").isTrue();
            channel.removeInsert(slot);
            runHandler(controller::stop);
            assertThat(audioEngine.isStreamOpen()).isFalse();
        } finally {
            audioEngine.stopAudioOutput();
            project.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void pauseWhilePlayingShouldPause() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.play();

        TransportController controller = newController(project);
        runHandler(controller::pause);

        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.PAUSED);
    }

    @Test
    void startWhilePausedShouldResumePlayback() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.play();
        transport.pause();

        TransportController controller = newController(project);
        runHandler(controller::start);

        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.PLAYING);
    }

    @Test
    void startWhileRecordingIsANoOp() throws Exception {
        // Stop is the only way out of record — the retired onPlay guard
        // survives as the handler's VALIDATE phase.
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.record();

        TransportController controller = newController(project);
        runHandler(controller::start);

        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.RECORDING);
    }

    @Test
    void playWithPreRollWhileRecordingIsANoOp() throws Exception {
        // Story 315 review — Shift+Space is reachable while RECORDING, and
        // Transport.playWithPreRoll() is permissive (always sets PLAYING and
        // rewinds). The production handler's VALIDATE rejects it before the
        // engine or the status bar is touched: Stop is the only way out of
        // record.
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.setPositionInBeats(20.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));
        transport.record();

        TransportController controller = newController(project);
        runHandler(controller::playWithPreRoll);

        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.RECORDING);
        assertThat(transport.getPositionInBeats())
                .as("no pre-roll rewind is applied when the intent is rejected")
                .isEqualTo(20.0);
        assertThat(transport.isInPreRoll())
                .as("the pre-roll window is never entered when the intent is rejected")
                .isFalse();
        assertThat(statusBarLabel.getText())
                .as("the status-bar tail runs only after VALIDATE passes")
                .isNullOrEmpty();
    }

    @Test
    void pauseWhileStoppedIsANoOp() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.setPositionInBeats(3.0);

        TransportController controller = newController(project);
        runHandler(controller::pause);

        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isEqualTo(3.0);
    }

    @Test
    void togglePlayPauseFlipsPlayingAndPausedThroughTheProductionHandler() throws Exception {
        // Story 315 review — the production handler resolves the Play gesture
        // from the AUTHORITATIVE transport state and runs the engine +
        // status-bar tails of its own start()/pause().
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();

        TransportController controller = newController(project);

        runHandler(controller::togglePlayPause);
        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.PLAYING);

        runHandler(controller::togglePlayPause);
        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.PAUSED);

        runHandler(controller::togglePlayPause);
        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.PLAYING);
    }

    @Test
    void togglePlayPauseWhileRecordingIsANoOp() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.record();

        TransportController controller = newController(project);
        runHandler(controller::togglePlayPause);

        assertThat(transport.getState())
                .as("Stop is the only way out of record")
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.RECORDING);
    }

    // ── Stop semantics (story 315) ───────────────────────────────────────────

    @Test
    void stopReturnsToTheAnchorAndASecondStopRewindsToZero() throws Exception {
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.setPositionInBeats(5.0);

        TransportController controller = newController(project);
        runHandler(controller::start);          // anchors at beat 5
        transport.advancePosition(3.0);         // the engine clock moves on
        assertThat(transport.getPositionInBeats()).isEqualTo(8.0);

        runHandler(controller::stop);           // first Stop → back to the anchor
        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(transport.getPositionInBeats())
                .as("Stop returns the playhead to the play-start anchor").isEqualTo(5.0);

        runHandler(controller::stop);           // second Stop → the gesture-level rewind
        assertThat(transport.getPositionInBeats())
                .as("a second Stop while already stopped rewinds to zero").isEqualTo(0.0);
    }

    @Test
    void secondStopLeavesThePlayheadWhenReturnToStartOnStopIsOff() throws Exception {
        // Story 315 review — the shipped transport.returnToStartOnStop
        // description promises "when off, the playhead stays where it stopped".
        // The double-stop gesture must honour it instead of always rewinding.
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.setReturnToStartOnStop(false);
        transport.setPositionInBeats(5.0);

        TransportController controller = newController(project);
        runHandler(controller::start);          // anchors at beat 5
        transport.advancePosition(3.0);
        runHandler(controller::stop);           // first Stop — no rewind
        assertThat(transport.getPositionInBeats())
                .as("with the preference off, Stop leaves the playhead").isEqualTo(8.0);

        runHandler(controller::stop);           // second Stop — still no rewind
        assertThat(transport.getPositionInBeats())
                .as("with the preference off, a second Stop does not rewind to zero")
                .isEqualTo(8.0);
    }

    @Test
    void stopFinalizesAnActiveRecordingEvenWhenTheTransportIsAlreadyStopped() throws Exception {
        // Story 315 review — the pipeline can be ACTIVE while the transport is
        // STOPPED: a count-in take before its deferred transport.record(), or
        // an internal caller stopping the transport under a running pipeline.
        // (Before story 323 a throw inside RecordingPipeline.start() was a
        // third way in — its start is now all-or-nothing, prepare() then
        // beginCapture(), and a failed start is abandoned on its allocation
        // turn or its readiness turn.) This test reproduces exactly that end state — an
        // active pipeline over a stopped transport — and asserts Stop finalizes
        // rather than taking the double-stop rewind and leaking the recording
        // sessions, the segment files, the per-track recording flags and the
        // lit REC indicator forever.
        //
        // Story 316 review — the engine now needs a REAL capture-capable
        // provision to reach that state at all. onRecord() opens the device
        // BEFORE starting the pipeline and aborts the whole take when the open
        // fails, so on the bare provision-less engine the other tests use no
        // pipeline is created and there is nothing for Stop to finalize (that
        // abort is pinned in TransportCommandPathTest). MockAudioBackend
        // overrides openedInputChannels() honestly, so it survives the
        // CaptureRequirement.REQUIRED walk.
        //
        // Story 323 — the take streams under the project's audio/takes, so the
        // project gets a directory; without one the record is refused before
        // any pipeline exists.
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Transport transport = project.getTransport();
        Track armed = new Track("Armed", TrackType.AUDIO);
        armed.setArmed(true);
        project.addTrack(armed);
        transport.setPositionInBeats(5.0);

        TransportController controller = newController(project, new MockAudioBackend());
        record(controller);   // pipeline active; transport RECORDING
        assertThat(armed.isRecording())
                .as("the pipeline armed the track").isTrue();
        assertThat(recIndicator.isVisible())
                .as("the REC indicator lit when recording started").isTrue();

        // The aborted-start state: the transport never made it to RECORDING (or
        // an internal caller already stopped it) while the pipeline runs on.
        transport.stop();
        assertThat(transport.getState()).isEqualTo(
                com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(armed.isRecording())
                .as("the pipeline is still active — nothing has finalized it").isTrue();

        runHandler(controller::stop);

        assertThat(armed.isRecording())
                .as("Stop finalized the recording instead of rewinding past it").isFalse();
        assertThat(recIndicator.isVisible())
                .as("the REC indicator is cleared by the finalize").isFalse();
        assertThat(transport.getPositionInBeats())
                .as("the finalize path does not take the double-stop rewind to zero")
                .isEqualTo(5.0);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the stopped take was published");
    }

    @Test
    void twoSkipForwardsWhileTheRealTimeClockIsClaimedLandTwoJumpsAhead() throws Exception {
        // Story 315 review — a relative seek must compose against the PENDING
        // seek target. While the RT clock owns the transport the committed
        // position does not move until the next block boundary, so composing
        // against getPositionInBeats() made two presses inside one block both
        // add the jump to the same base; the single-slot, last-writer-wins queue
        // then kept only the second and the playhead landed one jump ahead.
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        transport.play();
        transport.setRealTimeClockActive(true);   // an audio callback owns the clock
        double jump = 4.0 * transport.getTimeSignatureNumerator(); // 4 bars of 4/4 = 16 beats

        TransportController controller = newController(project);
        runHandler(controller::skipForward);
        runHandler(controller::skipForward);

        assertThat(transport.getSeekTargetInBeats())
                .as("two Skip Forwards inside one audio block queue two jumps")
                .isEqualTo(2.0 * jump);

        // The block boundary applies the queued target.
        transport.advancePosition(0.0);
        assertThat(transport.getPositionInBeats())
                .as("the drained seek lands two jumps ahead").isEqualTo(2.0 * jump);
    }

    @Test
    void stopButtonIsNeverDisabledByUpdateStatus() throws Exception {
        // Story 315 — Stop must stay clickable while STOPPED (the double-stop
        // rewind gesture). The controller no longer even receives the Stop
        // button (structural test above); updateStatus only gates Play.
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        TransportController controller = newController(project);

        runHandler(controller::updateStatus);   // state == STOPPED
        assertThat(playButton.isDisable())
                .as("Play stays enabled while stopped").isFalse();

        project.getTransport().record();
        runHandler(controller::updateStatus);
        assertThat(playButton.isDisable())
                .as("Play is disabled during RECORDING (Stop is the only way out)")
                .isTrue();
    }

    // ── Story 323: the take lives in the project ─────────────────────────────

    @Test
    void recordIsRefusedWithAVisibleErrorWhenTheProjectHasNoDirectory() throws Exception {
        // Story 323 (D6) — an audio take streams under <project>/audio/takes
        // and never into the OS temp directory, so a never-saved project
        // (metadata without a path) cannot record audio. The refusal is
        // visible (ERROR toast + status text) and happens BEFORE the engine,
        // the pipeline or any MidiRecorder is touched.
        DawProject project = new DawProject("unsaved", new AudioFormat(48000, 2, 16, 256));
        assertThat(project.getMetadata().projectPath())
                .as("fixture: a project that has never been saved").isNull();
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        OpenCountingBackend backend = new OpenCountingBackend();
        TransportController controller = newController(project, backend);

        runHandler(controller::toggleRecord);

        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage())
                .isEqualTo(TransportController.NO_PROJECT_FOLDER_MESSAGE);
        assertThat(statusBarLabel.getText())
                .isEqualTo(TransportController.NO_PROJECT_FOLDER_MESSAGE);
        assertThat(project.getTransport().getState())
                .as("nothing started — the transport never left STOPPED")
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(armed.isRecording()).as("no pipeline flagged the track").isFalse();
        assertThat(recIndicator.isVisible()).as("the REC indicator stays hidden").isFalse();
        assertThat(backend.opens.get()).as("the device was never opened").isZero();
        assertThat(audioEngine.isStreamOpen()).as("no stream is open").isFalse();
        assertThat(controller.activeTakeDirectory()).as("no take directory exists").isEmpty();
        assertThat(controller.isPreparingTake()).as("and no take is being prepared").isFalse();
    }

    @Test
    void aStartedTakeLivesUnderTheProjectsAudioTakesDirectory() throws Exception {
        // Story 323 (D6, D12) — the take directory is allocated by
        // TakeDirectories under the project's audio/takes, the pipeline
        // streams into it from the start (manifest + lane-0 .part, created by
        // the take's capture thread before capture begins), and the status
        // line names it.
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        OpenCountingBackend backend = new OpenCountingBackend();
        TransportController controller = newController(project, backend);
        try {
            record(controller);

            assertThat(backend.opens.get())
                    .as("non-vacuity of the refusal test's zero: a real take opens the device")
                    .isPositive();
            Optional<Path> takeDirectory = controller.activeTakeDirectory();
            assertThat(takeDirectory).as("an audio take is in flight").isPresent();
            Path takes = TakeDirectories.takesDirectory(
                    ProjectManager.audioDirectory(projectDirectory));
            assertThat(takeDirectory.get().getParent())
                    .as("the take is a direct child of <project>/audio/takes")
                    .isEqualTo(takes);
            String takeName = takeDirectory.get().getFileName().toString();
            assertThat(TakeDirectories.isTakeDirectoryName(takeName))
                    .as("take directory name has the <stamp>_take-NNNN shape: " + takeName)
                    .isTrue();
            assertThat(TakeManifest.manifestPath(takeDirectory.get()))
                    .as("the manifest is written at take start").isRegularFile();
            assertThat(takeDirectory.get().resolve(armed.getId()).resolve("segment-000.wav.part"))
                    .as("lane 0 of the armed track is streaming to disk").isRegularFile();
            assertThat(statusBarLabel.getText())
                    .isEqualTo("Recording — 1 track armed — streaming to audio/takes/" + takeName);
            assertThat(recIndicator.isVisible()).isTrue();
        } finally {
            stopAndAwaitTheTake(controller);
        }
    }

    @Test
    void midiOnlyRecordingDoesNotNeedAProjectDirectory() throws Exception {
        // Story 323 — MIDI recording writes no audio files, so a never-saved
        // project may still record MIDI; the status line names no take
        // directory and claims no auto-save.
        DawProject project = new DawProject("unsaved", new AudioFormat(48000, 2, 16, 256));
        assertThat(project.getMetadata().projectPath()).isNull();
        Track midiTrack = new Track("Keys", TrackType.MIDI);
        midiTrack.setArmed(true);
        project.addTrack(midiTrack);
        TransportController controller = newController(project, new MockAudioBackend());
        runHandler(() -> controller.setMidiInputDeviceResolverForTest(_ -> new RecordingInFlightFixture.StubMidiInput()));
        try {
            runHandler(controller::toggleRecord);

            assertThat(project.getTransport().getState())
                    .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.RECORDING);
            assertThat(recIndicator.isVisible()).isTrue();
            assertThat(notificationBar.getCurrentLevel())
                    .as("no refusal — the INFO toast is the last one shown")
                    .isEqualTo(NotificationLevel.INFO);
            assertThat(statusBarLabel.getText()).isEqualTo("Recording — 1 track armed");
            assertThat(controller.activeTakeDirectory())
                    .as("no audio pipeline, so no take directory").isEmpty();
        } finally {
            runHandler(controller::stop);
        }
    }

    @Test
    void story325DoubleRecordFinalizesExactlyOneTakeAndOpensInputOnce() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        OpenCountingBackend backend = new OpenCountingBackend();
        TransportController controller = newController(project, backend);
        CountingCompletion completion = new CountingCompletion();
        runStrictHandler(() -> controller.setTakeCompletionForTest(completion));
        record(controller);
        awaitABlockOnDisk(controller, armed);
        runStrictHandler(controller::toggleRecord);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "toggle-stop publishes its one take");
        assertThat(backend.opens.get()).isEqualTo(1);
        assertThat(completion.calls).hasSize(1);
        assertThat(armed.getClips()).hasSize(1);
        assertThat(recIndicator.isVisible()).isFalse();
        assertThat(controller.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.IDLE);
    }

    @Test
    void story325DeclinedSettingsLeaveTheSameTakeRecording() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        TransportController controller = newController(project);
        record(controller);
        Path take = controller.activeTakeDirectory().orElseThrow();
        runStrictHandler(() -> controller.recordCoordinator().setStopAndApplyConfirmationForTest(() -> false));
        CompletableFuture<Runnable> permit = controller.recordCoordinator().requestConfigurationChange().toCompletableFuture();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> permit.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(AudioConfigurationDeclinedException.class);
        assertThat(controller.activeTakeDirectory()).contains(take);
        assertThat(audioEngine.isStreamOpen()).isTrue();
        assertThat(recIndicator.isVisible()).isTrue();
        assertThat(controller.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.RECORDING);
    }

    @Test
    void story325ConfirmedSettingsWaitForPublicationBeforeGrantingTheLease() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completion = new CountingCompletion();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completion);
        holdTheCaptureThread();
        runStrictHandler(() -> controller.recordCoordinator().setStopAndApplyConfirmationForTest(() -> true));
        CompletableFuture<Runnable> permit = controller.recordCoordinator().requestConfigurationChange().toCompletableFuture();
        awaitOnFx(() -> !controller.isRecordingInFlight(), "confirmed apply stops capture");
        assertThat(permit.isDone()).isFalse();
        assertThat(completion.calls).isEmpty();
        assertThat(controller.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.FINALIZING);
        releaseAndAwaitThePublication(controller);
        Runnable release = permit.get(5, TimeUnit.SECONDS);
        assertThat(completion.calls).hasSize(1);
        assertThat(armed.getClips()).hasSize(1);
        try {
            runStrictHandler(controller::toggleRecord);
            assertThat(controller.isRecordingInFlight()).isFalse();
        } finally {
            release.run();
            flushFx();
        }
    }

    @Test
    void story325MidiToggleClosesEveryDeviceAndNeverReplacesLiveRecorders() throws Exception {
        DawProject project = new DawProject("keys", new AudioFormat(48000, 2, 16, 256));
        Track first = new Track("First", TrackType.MIDI);
        Track second = new Track("Second", TrackType.MIDI);
        first.setArmed(true); second.setArmed(true);
        first.setMidiInputDeviceName("first"); second.setMidiInputDeviceName("second");
        project.addTrack(first); project.addTrack(second);
        var input1 = new RecordingInFlightFixture.StubMidiInput();
        var input2 = new RecordingInFlightFixture.StubMidiInput();
        TransportController controller = newController(project);
        runStrictHandler(() -> controller.setMidiInputDeviceResolverForTest(name -> name.equals("first") ? input1 : input2));
        record(controller);
        assertThat(input1.isConnected()).isTrue(); assertThat(input2.isConnected()).isTrue();
        runStrictHandler(controller::toggleRecord);
        flushFx();
        assertThat(input1.isOpen()).isFalse(); assertThat(input2.isOpen()).isFalse();
        assertThat(input1.isConnected()).isFalse(); assertThat(input2.isConnected()).isFalse();
        assertThat(first.isRecording()).isFalse(); assertThat(second.isRecording()).isFalse();
        assertThat(controller.isRecordingInFlight()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allMidiInputsArePreparedBeforeAnyCaptureClockStarts(boolean mixed) throws Exception {
        DawProject project = new DawProject("keys", new AudioFormat(48000, 2, 16, 256));
        if (mixed) {
            giveTheProjectADirectory(project);
            project.createAudioTrack("Vox").setArmed(true);
        }
        Track first = new Track("First", TrackType.MIDI);
        Track second = new Track("Second", TrackType.MIDI);
        first.setArmed(true); second.setArmed(true);
        first.setMidiInputDeviceName("first"); second.setMidiInputDeviceName("second");
        project.addTrack(first); project.addTrack(second);
        var clock = new java.util.concurrent.atomic.AtomicLong(-1_000_000_000L);
        CountingMidiInput input1 = new CountingMidiInput(false);
        CountingMidiInput input2 = new CountingMidiInput(false);
        input2.onOpen = () -> {
            assertThat(input1.isConnected()).isTrue();
            assertThat(first.isRecording()).as("earlier input is only prepared").isFalse();
            clock.addAndGet(2_000_000_000L); // A slow later input, without a sleep.
            sendMidi(input1, javax.sound.midi.ShortMessage.NOTE_ON, 59, 100);
            sendMidi(input1, javax.sound.midi.ShortMessage.NOTE_OFF, 59, 0);
            assertThat(first.getMidiClip().size()).isZero();
        };
        TransportController controller = newController(project);
        runStrictHandler(() -> {
            controller.setMidiInputDeviceResolverForTest(name -> name.equals("first") ? input1 : input2);
            controller.recordCoordinator().setMidiMonotonicClockForTest(clock::get);
        });
        Runnable removeListener = project.getTransport().addChangeListener(kind -> {
            if (kind != Transport.ChangeKind.STATE || project.getTransport().getState() != TransportState.RECORDING) return;
            assertThat(input1.isConnected()).isTrue();
            assertThat(input2.isConnected()).isTrue();
            clock.addAndGet(3_000_000_000L); // Capture activation follows successful startup notification.
            for (CountingMidiInput input : List.of(input1, input2)) {
                sendMidi(input, javax.sound.midi.ShortMessage.NOTE_ON, 58, 100);
                sendMidi(input, javax.sound.midi.ShortMessage.NOTE_OFF, 58, 0);
            }
        });
        try {
            record(controller);
        } finally {
            removeListener.run();
        }
        assertThat(controller.recordCoordinator().getState()).isEqualTo(RecordState.RECORDING);
        assertThat(first.isRecording()).isTrue();
        assertThat(second.isRecording()).isTrue();
        assertThat(first.getMidiClip().size()).isZero();
        assertThat(second.getMidiClip().size()).isZero();
        clock.addAndGet(250_000_000L);
        sendMidi(input1, javax.sound.midi.ShortMessage.NOTE_ON, 60, 100);
        sendMidi(input2, javax.sound.midi.ShortMessage.NOTE_ON, 60, 100);
        clock.addAndGet(250_000_000L);
        sendMidi(input1, javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0);
        sendMidi(input2, javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0);
        var expected = new com.benesquivelmusic.daw.core.midi.MidiNoteData(60, 2, 2, 100, 0);
        assertThat(first.getMidiClip().getNotes()).containsExactly(expected);
        assertThat(second.getMidiClip().getNotes()).containsExactly(expected);
        stopAndAwaitTheTake(controller);
        for (CountingMidiInput input : List.of(input1, input2)) {
            assertThat(input.closes).isEqualTo(1);
            assertThat(input.transmitterCloses).isEqualTo(1);
            assertThat(input.isConnected()).isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void midiActivationFailureRollsBackPreparedInputsAndTheEntireTake(boolean mixed, boolean error) throws Exception {
        DawProject project = new DawProject("keys", new AudioFormat(48000, 2, 16, 256));
        if (mixed) {
            giveTheProjectADirectory(project);
            project.createAudioTrack("Vox").setArmed(true);
        }
        Track first = new Track("First", TrackType.MIDI);
        Track second = new Track("Second", TrackType.MIDI);
        first.setArmed(true); second.setArmed(true);
        first.setMidiInputDeviceName("first"); second.setMidiInputDeviceName("second");
        project.addTrack(first); project.addTrack(second);
        CountingMidiInput input1 = new CountingMidiInput(false);
        CountingMidiInput input2 = new CountingMidiInput(false);
        TransportController controller = newController(project);
        runStrictHandler(() -> {
            controller.setMidiInputDeviceResolverForTest(name -> name.equals("first") ? input1 : input2);
            controller.recordCoordinator().setMidiMonotonicClockForTest(() -> {
                if (error) throw new AssertionError("capture clock unavailable");
                throw new IllegalStateException("capture clock unavailable");
            });
        });
        record(controller);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the failed activation's rollback settles");
        assertThat(controller.recordCoordinator().getState()).isEqualTo(RecordState.IDLE);
        assertThat(project.getTransport().getState()).isEqualTo(TransportState.STOPPED);
        assertThat(audioEngine.isStreamOpen()).isFalse();
        assertThat(audioEngine.isRunning()).isFalse();
        assertThat(audioEngine.getRecordingCallback()).isNull();
        assertThat(recIndicator.isVisible()).isFalse();
        assertThat(undoManager.undoSize()).isZero();
        assertThat(project.isDirty()).isFalse();
        for (Track track : project.getTracks()) {
            assertThat(track.isRecording()).isFalse();
            assertThat(track.getClips()).isEmpty();
        }
        for (CountingMidiInput input : List.of(input1, input2)) {
            assertThat(input.opens).isEqualTo(1);
            assertThat(input.closes).isEqualTo(1);
            assertThat(input.transmitterCloses).isEqualTo(1);
            assertThat(input.isConnected()).isFalse();
        }
        assertThat(notificationBar.getMessage()).contains("capture clock unavailable");
        if (mixed) assertThat(takeDirectories()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void errorOpeningALaterMidiInputDrainsEarlierPreparationAndTheEntireTake(boolean mixed) throws Exception {
        DawProject project = new DawProject("keys", new AudioFormat(48000, 2, 16, 256));
        if (mixed) {
            giveTheProjectADirectory(project);
            project.createAudioTrack("Vox").setArmed(true);
        }
        Track first = new Track("First", TrackType.MIDI);
        Track second = new Track("Second", TrackType.MIDI);
        first.setArmed(true); second.setArmed(true);
        first.setMidiInputDeviceName("first"); second.setMidiInputDeviceName("second");
        project.addTrack(first); project.addTrack(second);
        CountingMidiInput input1 = new CountingMidiInput(false);
        CountingMidiInput input2 = new CountingMidiInput(false);
        input2.onOpen = () -> { throw new AssertionError("later input open failed"); };
        TransportController controller = newController(project);
        runStrictHandler(() -> controller.setMidiInputDeviceResolverForTest(name -> name.equals("first") ? input1 : input2));
        record(controller);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the failed input's rollback settles");
        assertThat(controller.recordCoordinator().getState()).isEqualTo(RecordState.IDLE);
        assertThat(input1.closes).isEqualTo(1);
        assertThat(input1.transmitterCloses).isEqualTo(1);
        assertThat(input2.closes).isEqualTo(1);
        assertThat(input1.isConnected()).isFalse();
        assertThat(input2.isOpen()).isFalse();
        assertThat(audioEngine.isStreamOpen()).isFalse();
        assertThat(audioEngine.isRunning()).isFalse();
        assertThat(audioEngine.getRecordingCallback()).isNull();
        assertThat(undoManager.undoSize()).isZero();
        assertThat(first.getMidiClip().size()).isZero();
        assertThat(second.getMidiClip().size()).isZero();
        assertThat(notificationBar.getMessage()).contains("later input open failed");
        if (mixed) assertThat(takeDirectories()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transportStoppedByAStartListenerCannotActivateMidiOrAnnounceRecording(boolean mixed) throws Exception {
        DawProject project = new DawProject("keys", new AudioFormat(48000, 2, 16, 256));
        if (mixed) {
            giveTheProjectADirectory(project);
            project.createAudioTrack("Vox").setArmed(true);
        }
        Track midi = new Track("Keys", TrackType.MIDI);
        midi.setArmed(true); project.addTrack(midi);
        CountingMidiInput input = new CountingMidiInput(false);
        TransportController controller = newController(project);
        runStrictHandler(() -> controller.setMidiInputDeviceResolverForTest(_ -> input));
        Transport transport = project.getTransport();
        Runnable removeListener = transport.addChangeListener(kind -> {
            if (kind == Transport.ChangeKind.STATE && transport.getState() == TransportState.RECORDING) transport.stop();
        });
        try {
            record(controller);
        } finally {
            removeListener.run();
        }
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the interrupted start settles");
        assertThat(controller.recordCoordinator().getState()).isEqualTo(RecordState.IDLE);
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(midi.isRecording()).isFalse();
        assertThat(midi.getMidiClip().size()).isZero();
        assertThat(input.closes).isEqualTo(1);
        assertThat(input.transmitterCloses).isEqualTo(1);
        assertThat(audioEngine.getRecordingCallback()).isNull();
        assertThat(audioEngine.isStreamOpen()).isFalse();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage()).contains("transport stopped during start");
        if (mixed) assertThat(takeDirectories()).isEmpty();
    }

    private static void sendMidi(RecordingInFlightFixture.StubMidiInput input, int command, int note, int velocity) {
        try {
            input.getTransmitters().getFirst().getReceiver().send(
                    new javax.sound.midi.ShortMessage(command, 0, note, velocity), -1);
        } catch (javax.sound.midi.InvalidMidiDataException invalid) {
            throw new AssertionError(invalid);
        }
    }

    private List<Path> takeDirectories() throws IOException {
        return listing(TakeDirectories.takesDirectory(ProjectManager.audioDirectory(projectDirectory)));
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "true,true,false", "true,false,true"})
    void midiCaptureStartListenerFailureAbortsAndKeepsTheOriginalCauseDespiteCleanupFailures(
            boolean failCleanup, boolean closeWithError, boolean failNotification) throws Exception {
        DawProject project = new DawProject("keys", new AudioFormat(48000, 2, 16, 256));
        Track first = new Track("First", TrackType.MIDI);
        Track second = new Track("Second", TrackType.MIDI);
        first.setArmed(true); second.setArmed(true);
        first.setMidiInputDeviceName("first"); second.setMidiInputDeviceName("second");
        project.addTrack(first); project.addTrack(second);
        CountingMidiInput input1 = new CountingMidiInput(false);
        CountingMidiInput input2 = new CountingMidiInput(failCleanup, closeWithError);
        TransportController controller = newController(project);
        Transport transport = project.getTransport();
        runStrictHandler(() -> {
            controller.setMidiInputDeviceResolverForTest(name -> name.equals("first") ? input1 : input2);
            controller.start();
        });
        NotificationHistoryService shown = notificationsFromNowOn();
        IllegalStateException original = new IllegalStateException("record listener failed");
        IllegalStateException stopFailure = new IllegalStateException("stop listener failed");
        IllegalStateException notificationFailure = new IllegalStateException("notification listener failed");
        Consumer<NotificationEntry> notificationListener = _ -> { if (failNotification) throw notificationFailure; };
        shown.addListener(notificationListener);
        List<RecordState> states = new CopyOnWriteArrayList<>();
        runStrictHandler(() -> controller.recordCoordinator().stateProperty()
                .addListener((_, _, next) -> states.add(next)));
        Runnable removeListener = transport.addChangeListener(kind -> {
            if (kind != Transport.ChangeKind.STATE) return;
            if (transport.getState() == TransportState.RECORDING) throw original;
            if (failCleanup && transport.getState() == TransportState.STOPPED) throw stopFailure;
        });
        List<BusEvent> published = new CopyOnWriteArrayList<>();
        EventBus previousBus = EventBusPublisher.getDefault();
        try {
            EventBusPublisher.setDefault(new PublishHookEventBus(published::add));
            runStrictHandler(controller::toggleRecord);
            flushFx();
        } finally {
            removeListener.run();
            shown.removeListener(notificationListener);
            EventBusPublisher.setDefault(previousBus);
        }

        assertThat(states).containsExactly(RecordState.COUNT_IN, RecordState.ABORTED, RecordState.IDLE);
        assertThat(controller.recordCoordinator().recordAvailableProperty().get()).isTrue();
        assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
        assertThat(controller.isRecordingInFlight()).isFalse();
        assertThat(controller.isTakeBeingWritten()).isFalse();
        assertThat(audioEngine.isStreamOpen()).isFalse();
        assertThat(audioEngine.isRunning()).isFalse();
        assertThat(audioEngine.getRecordingCallback()).isNull();
        assertThat(recIndicator.isVisible()).isFalse();
        for (CountingMidiInput input : List.of(input1, input2)) {
            assertThat(input.opens).isEqualTo(1);
            assertThat(input.closes).isEqualTo(1);
            assertThat(input.transmitterCloses).isEqualTo(1);
            assertThat(input.isConnected()).isFalse();
            assertThat(input.isOpen()).isFalse();
        }
        assertThat(first.isRecording()).isFalse();
        assertThat(second.isRecording()).isFalse();
        String message = "Recording aborted — no take was started: record listener failed";
        assertThat(statusBarLabel.getText()).isEqualTo(message);
        assertThat(shown.getEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo(message);
        });
        assertThat(published).noneMatch(TransportEvent.Started.class::isInstance);
        if (failCleanup) {
            assertThat(original.getSuppressed()).contains(stopFailure)
                    .extracting(Throwable::getMessage).contains("MIDI close failed");
        } else {
            assertThat(original.getSuppressed()).isEmpty();
        }
        if (failNotification) {
            assertThat(original.getSuppressed()).contains(notificationFailure);
        } else {
            assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
            assertThat(notificationBar.getMessage()).isEqualTo(message);
        }

        runStrictHandler(controller::toggleRecord);
        assertThat(controller.recordCoordinator().getState()).isEqualTo(RecordState.RECORDING);
        assertThat(input1.opens).isEqualTo(2);
        assertThat(input2.opens).isEqualTo(2);
        assertThat(first.isRecording()).isTrue();
        assertThat(second.isRecording()).isTrue();
        if (closeWithError) {
            runStrictHandler(() -> input2.closeWithError = false);
        }
        runStrictHandler(controller::stop);
        awaitOnFx(() -> controller.recordCoordinator().getState() == RecordState.IDLE, "the retry settles");
    }

    @Test
    void story325SharedMidiStartupFailureLeavesTheOtherTrackRecordingAndPublishable() throws Exception {
        DawProject project = new DawProject("keys", new AudioFormat(48000, 2, 16, 256));
        Track first = new Track("First", TrackType.MIDI), second = new Track("Second", TrackType.MIDI);
        first.setArmed(true); second.setArmed(true);
        first.setMidiInputDeviceName("shared"); second.setMidiInputDeviceName("shared");
        project.addTrack(first); project.addTrack(second);
        AtomicInteger closes = new AtomicInteger();
        var input = new RecordingInFlightFixture.StubMidiInput() {
            @Override public javax.sound.midi.Transmitter getTransmitter() {
                if (isConnected()) throw new IllegalStateException("only one transmitter available");
                return super.getTransmitter();
            }
            @Override public void close() { closes.incrementAndGet(); super.close(); }
        };
        TransportController controller = newController(project);
        runStrictHandler(() -> controller.setMidiInputDeviceResolverForTest(_ -> input));
        record(controller);
        assertThat(controller.recordCoordinator().getState())
                .isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.RECORDING);
        assertThat(first.isRecording()).isTrue();
        assertThat(second.isRecording()).isFalse();
        assertThat(input.isOpen()).isTrue();
        assertThat(input.isConnected()).isTrue();
        assertThat(notificationBar.getMessage()).contains("Second", "skipped");
        var receiver = input.getTransmitters().getFirst().getReceiver();
        receiver.send(new javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_ON, 0, 60, 100), 1_000_000);
        receiver.send(new javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_OFF, 0, 60, 0), 1_250_000);
        runStrictHandler(controller::toggleRecord);
        flushFx();
        assertThat(first.getMidiClip().size()).isEqualTo(1);
        assertThat(second.getMidiClip().size()).isZero();
        assertThat(project.isDirty()).isTrue();
        assertThat(undoManager.undoSize()).isEqualTo(1);
        assertThat(closes).hasValue(1);
        assertThat(input.isConnected()).isFalse();
        assertThat(input.isOpen()).isFalse();
        assertThat(controller.isRecordingInFlight()).isFalse();
        runStrictHandler(undoManager::undo);
        assertThat(first.getMidiClip().size()).isZero();
    }

    @Test
    void story325NoUsableMidiDeviceFailsTheGuardAndShowsAnError() throws Exception {
        DawProject project = new DawProject("keys", new AudioFormat(48000, 2, 16, 256));
        Track midi = new Track("Keys", TrackType.MIDI);
        midi.setArmed(true); project.addTrack(midi);
        TransportController controller = newController(project);
        runStrictHandler(() -> controller.setMidiInputDeviceResolverForTest(_ -> null));
        record(controller);
        assertThat(project.getTransport().getState()).isEqualTo(TransportState.STOPPED);
        assertThat(controller.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.IDLE);
        assertThat(recIndicator.isVisible()).isFalse();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage()).contains("MIDI input precondition", "no armed MIDI track");
        runStrictHandler(() -> controller.setMidiInputDeviceResolverForTest(_ -> new CountingMidiInput(false)));
        record(controller);
        assertThat(controller.isRecordingInFlight()).isTrue();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.INFO);
        assertThat(notificationBar.getMessage()).doesNotContain("skipped");
    }

    @Test
    void story325RecordSurfacesFollowCoordinatorDuringPreparationAndFinalization() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox"); armed.setArmed(true);
        TransportController controller = newController(project);
        AtomicReference<Runnable> allocation = new AtomicReference<>();
        runStrictHandler(() -> {
            controller.setStorageExecutorForTest(allocation::set);
            controller.toggleRecord();
            assertThat(controller.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.PREPARING);
            assertThat(recIndicator.isVisible()).isFalse();
            assertThat(controller.recordCoordinator().statusProperty().get()).isEqualTo(TransportController.TAKE_PREPARING_MESSAGE);
            controller.toggleRecord();
            assertThat(controller.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.FINALIZING);
            assertThat(controller.recordCoordinator().recordAvailableProperty().get()).isFalse();
            controller.setStorageExecutorForTest(task -> Thread.ofVirtual().start(task));
        });
        allocation.get().run();
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "cancelled start removes its directory");
        assertThat(controller.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.IDLE);
    }

    @Test
    void story325PauseCannotSilentlySuspendAnActiveTake() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track track = project.createAudioTrack("Vox"); track.setArmed(true);
        TransportController controller = newController(project);
        record(controller);
        runStrictHandler(controller::pause);
        assertThat(project.getTransport().getState()).isEqualTo(TransportState.RECORDING);
        assertThat(audioEngine.isStreamPaused()).isFalse();
        assertThat(recIndicator.isVisible()).isTrue();
    }

    @Test
    void story325OffFxRecordIsRefusedBeforeAnyResourceOpens() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track track = project.createAudioTrack("Vox"); track.setArmed(true);
        OpenCountingBackend backend = new OpenCountingBackend();
        TransportController controller = newController(project, backend);
        org.assertj.core.api.Assertions.assertThatThrownBy(controller::toggleRecord)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FX thread");
        assertThat(backend.opens.get()).isZero();
        assertThat(controller.isRecordingInFlight()).isFalse();
    }

    @Test
    void story325ThrowingConfirmationCompletesRequestAndLeavesTakeRecording() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track track = project.createAudioTrack("Vox"); track.setArmed(true);
        TransportController controller = newController(project);
        record(controller);
        runStrictHandler(() -> controller.recordCoordinator().setStopAndApplyConfirmationForTest(
                () -> { throw new IllegalStateException("prompt unavailable"); }));
        var result = controller.recordCoordinator().requestConfigurationChange().toCompletableFuture();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(controller.recordCoordinator().recordAvailableProperty().get()).isTrue();
        assertThat(recIndicator.isVisible()).isTrue();
    }

    private DefaultAudioEngineController story325SettingsController(TransportController recording) {
        return story325SettingsController(recording, new AtomicInteger());
    }

    private DefaultAudioEngineController story325SettingsController(TransportController recording, AtomicInteger sampleRateCalls) {
        var selector = new com.benesquivelmusic.daw.sdk.audio.AudioBackendSelector(
                Map.of(new MockAudioBackend().name(), () -> new SampleRateCountingBackend(sampleRateCalls)));
        DefaultAudioEngineController controller = new DefaultAudioEngineController(audioEngine, null,
                NotificationManager.noop(), new IncompleteTakeStore(projectDirectory), selector);
        controller.setRecordConfigurationGuard(recording.recordCoordinator()::requestConfigurationChange);
        return controller;
    }

    private SettingsModel story325SettingsModel() {
        var model = new SettingsModel(java.util.prefs.Preferences.userRoot().node("story325-" + UUID.randomUUID()));
        model.setAudioBackend(new MockAudioBackend().name());
        model.setSampleRate(48000); model.setBufferSize(256); model.setBitDepth(16);
        model.setWorkerPoolSize(audioEngine.getWorkerPoolSize());
        return model;
    }

    @Test
    void story325SettingsDialogDeclinePreservesRuntimePersistenceAndDirtyEdit() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track track = project.createAudioTrack("Vox"); track.setArmed(true);
        TransportController recording = newController(project);
        record(recording);
        AtomicInteger sampleRateCalls = new AtomicInteger();
        DefaultAudioEngineController controller = story325SettingsController(recording, sampleRateCalls);
        SettingsModel model = story325SettingsModel();
        AtomicReference<SettingsDialog> dialog = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> outcome = new AtomicReference<>();
        try {
            runStrictHandler(() -> {
                recording.recordCoordinator().setStopAndApplyConfirmationForTest(() -> false);
                SettingsDialog settings = new SettingsDialog(model);
                dialog.set(settings);
                settings.setAudioEngineController(controller);
                settings.setDirtyClosePrompt(() -> SettingsDialog.DirtyChoice.DISCARD);
                settings.getShell().settingRow("audio.bufferSize").orElseThrow().setValue(512);
                settings.getShell().settingRow("audio.sampleRate").orElseThrow().setValue(96000);
                outcome.set(settings.applySettingsAsync().toCompletableFuture());
            });
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> outcome.get().get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AudioConfigurationDeclinedException.class);
            runStrictHandler(() -> {
                assertThat(dialog.get().getShell().dirtyProperty().get()).isTrue();
                assertThat(dialog.get().getShell().settingRow("audio.bufferSize").orElseThrow().getValue()).isEqualTo(512);
            });
            assertThat(model.getBufferSize()).isEqualTo(256);
            assertThat(model.getSampleRate()).isEqualTo(48000);
            assertThat(sampleRateCalls.get()).isZero();
            assertThat(audioEngine.getFormat().sampleRate()).isEqualTo(48000);
            assertThat(audioEngine.getFormat().bufferSize()).isEqualTo(256);
            assertThat(recIndicator.isVisible()).isTrue();
            assertThat(audioEngine.isStreamOpen()).isTrue();
        } finally {
            runStrictHandler(() -> { if (dialog.get() != null) dialog.get().close(); });
            controller.shutdown();
        }
    }

    @Test
    void story325SettingsDialogConfirmedApplyCommitsOnlyAfterTheTakeIsPublished() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track track = project.createAudioTrack("Vox"); track.setArmed(true);
        CountingCompletion completion = new CountingCompletion();
        TransportController recording = recordingWithABlockOnDisk(project, track, completion);
        holdTheCaptureThread();
        AtomicInteger sampleRateCalls = new AtomicInteger();
        DefaultAudioEngineController controller = story325SettingsController(recording, sampleRateCalls);
        SettingsModel model = story325SettingsModel();
        AtomicReference<SettingsDialog> dialog = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> outcome = new AtomicReference<>();
        try {
            runStrictHandler(() -> {
                recording.recordCoordinator().setStopAndApplyConfirmationForTest(() -> true);
                SettingsDialog settings = new SettingsDialog(model);
                dialog.set(settings);
                settings.setAudioEngineController(controller);
                settings.setDirtyClosePrompt(() -> SettingsDialog.DirtyChoice.DISCARD);
                settings.getShell().settingRow("audio.bufferSize").orElseThrow().setValue(512);
                settings.getShell().settingRow("audio.sampleRate").orElseThrow().setValue(96000);
                outcome.set(settings.applySettingsAsync().toCompletableFuture());
            });
            awaitOnFx(() -> !recording.isRecordingInFlight(), "confirmed settings stop the take");
            assertThat(outcome.get().isDone()).isFalse();
            assertThat(model.getBufferSize()).isEqualTo(256);
            assertThat(model.getSampleRate()).isEqualTo(48000);
            assertThat(sampleRateCalls.get()).isZero();
            assertThat(audioEngine.getFormat().sampleRate()).isEqualTo(48000);
            assertThat(audioEngine.getFormat().bufferSize()).isEqualTo(256);
            releaseAndAwaitThePublication(recording);
            outcome.get().get(10, TimeUnit.SECONDS);
            assertThat(completion.calls).hasSize(1);
            assertThat(track.getClips()).hasSize(1);
            assertThat(model.getBufferSize()).isEqualTo(512);
            assertThat(model.getSampleRate()).isEqualTo(96000);
            assertThat(sampleRateCalls.get()).isPositive();
            assertThat(audioEngine.getFormat().sampleRate()).isEqualTo(96000);
            assertThat(audioEngine.getFormat().bufferSize()).isEqualTo(512);
        } finally {
            hold.release();
            runStrictHandler(() -> { if (dialog.get() != null) dialog.get().close(); });
            controller.shutdown();
        }
    }

    @Test
    void story325SessionInputDeclinePreservesTheLiveTakeAndDoesNotPersistTheRejectedInput() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track track = project.createAudioTrack("Vox"); track.setArmed(true);
        TransportController recording = newController(project);
        record(recording);
        Path take = recording.activeTakeDirectory().orElseThrow();
        DefaultAudioEngineController controller = story325SettingsController(recording);
        SettingsModel model = story325SettingsModel();
        model.setAudioInputDevice("Old input");
        var device = AudioDeviceInfo.unprobed(1, "New input", "Mock");
        AtomicInteger confirmations = new AtomicInteger();
        var selection = new com.benesquivelmusic.daw.app.ui.recording.SettingsBackedSessionInputSelection(
                model, controller, (_, _, _, _) -> { }, () -> { });
        AtomicReference<Thread> worker = new AtomicReference<>();
        try {
            runStrictHandler(() -> {
                recording.recordCoordinator().setStopAndApplyConfirmationForTest(() -> {
                    confirmations.incrementAndGet();
                    return false;
                });
                worker.set(selection.selectAndApply(device).orElseThrow());
            });
            assertThat(worker.get().join(Duration.ofSeconds(10))).isTrue();
            assertThat(model.getAudioInputDevice()).isEqualTo("Old input");
            assertThat(selection.currentDeviceName()).isEqualTo("Old input");
            runStrictHandler(() -> worker.set(selection.selectAndApply(device).orElseThrow()));
            assertThat(worker.get().join(Duration.ofSeconds(10))).isTrue();
            assertThat(confirmations).hasValue(2);
            assertThat(recording.activeTakeDirectory()).contains(take);
            assertThat(audioEngine.isStreamOpen()).isTrue();
            assertThat(recIndicator.isVisible()).isTrue();
            assertThat(recording.recordCoordinator().getState())
                    .isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.RECORDING);
        } finally {
            controller.shutdown();
        }
    }

    @Test
    void story325SessionInputConfirmationWaitsForPublicationBeforePersistingAndApplying() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track track = project.createAudioTrack("Vox"); track.setArmed(true);
        CountingCompletion completion = new CountingCompletion();
        TransportController recording = recordingWithABlockOnDisk(project, track, completion);
        holdTheCaptureThread();
        DefaultAudioEngineController controller = story325SettingsController(recording);
        SettingsModel model = story325SettingsModel();
        model.setAudioInputDevice("Old input");
        var device = AudioDeviceInfo.unprobed(1, "New input", "Mock");
        List<String> errors = new CopyOnWriteArrayList<>();
        var selection = new com.benesquivelmusic.daw.app.ui.recording.SettingsBackedSessionInputSelection(
                model, controller, (_, message, _, _) -> errors.add(message), () -> { });
        AtomicReference<Thread> worker = new AtomicReference<>();
        try {
            runStrictHandler(() -> {
                recording.recordCoordinator().setStopAndApplyConfirmationForTest(() -> true);
                worker.set(selection.selectAndApply(device).orElseThrow());
            });
            awaitOnFx(() -> !recording.isRecordingInFlight(), "confirmed input change stops capture");
            assertThat(worker.get().isAlive()).isTrue();
            assertThat(model.getAudioInputDevice()).isEqualTo("Old input");
            assertThat(completion.calls).isEmpty();
            releaseAndAwaitThePublication(recording);
            assertThat(worker.get().join(Duration.ofSeconds(10))).isTrue();
            assertThat(completion.calls).hasSize(1);
            assertThat(track.getClips()).hasSize(1);
            assertThat(model.getAudioInputDevice()).isEqualTo(device.qualifiedName());
            assertThat(errors).isEmpty();
            assertThat(controller.isConfigurationChangeInProgress()).isFalse();
        } finally {
            hold.release();
            controller.shutdown();
        }
    }

    @Test
    void story325LateReadinessFailureRollsBackFlushFilesAndStreamInReverseOrder() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 8, 256));
        giveTheProjectADirectory(project);
        Track track = project.createAudioTrack("Vox"); track.setArmed(true);
        List<String> order = new CopyOnWriteArrayList<>();
        OpenCountingBackend backend = new OpenCountingBackend();
        backend.lifecycle = order;
        TransportController recording = newController(project, backend);
        AtomicInteger storageTasks = new AtomicInteger();
        AtomicReference<RecordingPipeline> prepared = new AtomicReference<>();
        AtomicReference<Boolean> terminatedBeforeCleanup = new AtomicReference<>(false);
        AtomicReference<Boolean> removedBeforeStreamClose = new AtomicReference<>(false);
        backend.beforeClose = () -> {
            removedBeforeStreamClose.set(!Files.exists(prepared.get().getTakeDirectory()));
            order.add("directory-cleaned");
        };
        runStrictHandler(() -> {
            recording.setStorageExecutorForTest(task -> Thread.ofVirtual().start(() -> {
                if (storageTasks.getAndIncrement() > 0) {
                    terminatedBeforeCleanup.set(prepared.get().termination().toCompletableFuture().isDone());
                    order.add("flush-terminated");
                }
                task.run();
            }));
            recording.setPipelineSetupForTest(pipeline -> {
                prepared.set(pipeline);
                assertThat(Files.isDirectory(pipeline.getTakeDirectory())).isTrue();
                order.add("directory-created");
                order.add("flush-preparing");
            });
            recording.toggleRecord();
        });
        awaitOnFx(() -> !recording.isPreparingTake() && !recording.isTakeBeingWritten(), "failed readiness fully rolls back");
        assertThat(terminatedBeforeCleanup.get()).as("real flush termination precedes directory cleanup").isTrue();
        assertThat(removedBeforeStreamClose.get()).as("directory cleanup precedes closing the opened stream").isTrue();
        assertThat(order).containsSubsequence("stream-opened", "directory-created", "flush-preparing",
                "flush-terminated", "directory-cleaned", "stream-closed");
        assertThat(track.isRecording()).isFalse();
        assertThat(project.getTransport().getState()).isEqualTo(TransportState.STOPPED);
        assertThat(recording.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.IDLE);
        assertThat(audioEngine.getRecordingCallback()).isNull();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage()).contains("bitDepth");
    }

    @Test
    void story325BoundRecordButtonRecAndStatusAllRenderTheCoordinator() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track track = project.createAudioTrack("Vox"); track.setArmed(true);
        TransportController recording = newController(project);
        AtomicReference<com.benesquivelmusic.daw.app.ui.vm.TransportVM> vm = new AtomicReference<>();
        AtomicReference<com.benesquivelmusic.daw.app.ui.vm.TransportControlBinder> binder = new AtomicReference<>();
        try {
            runStrictHandler(() -> {
                var feed = new com.benesquivelmusic.daw.app.ui.vm.TransportVM(project.getTransport(), new FxDispatcher());
                vm.set(feed);
                feed.bindRecordState(recording.recordCoordinator().stateProperty(), recording.recordCoordinator().recordAvailableProperty());
                var binding = new com.benesquivelmusic.daw.app.ui.vm.TransportControlBinder(feed, command -> command.execute(recording));
                binder.set(binding); binding.bindRecord(recordButton);
            });
            record(recording);
            runStrictHandler(() -> {
                assertThat(recIndicator.isVisible()).isTrue();
                assertThat(recordButton.getPseudoClassStates()).contains(javafx.css.PseudoClass.getPseudoClass("active"));
                assertThat(recordButton.isDisable()).isFalse();
                assertThat(statusLabel.getText()).isEqualTo("RECORDING");
                assertThat(statusBarLabel.getText()).isEqualTo(recording.recordCoordinator().statusProperty().get());
                recordButton.fire();
                assertThat(recIndicator.isVisible()).isFalse();
                assertThat(recordButton.getPseudoClassStates()).doesNotContain(javafx.css.PseudoClass.getPseudoClass("active"));
                assertThat(recordButton.isDisable()).isTrue();
                assertThat(statusLabel.getText()).isEqualTo("FINALIZING");
            });
            awaitOnFx(() -> !recording.isTakeBeingWritten(), "bound toggle publishes the take");
            runStrictHandler(() -> assertThat(recordButton.isDisable()).isFalse());
        } finally {
            runStrictHandler(() -> { if (binder.get() != null) binder.get().dispose(); if (vm.get() != null) vm.get().dispose(); });
        }
    }

    @Test
    void story325NoBackendRefusesAudioRecordingWithoutFilesOrFlags() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track track = project.createAudioTrack("Vox"); track.setArmed(true);
        TransportController recording = newController(project, null);
        record(recording);
        assertThat(project.getTransport().getState()).isEqualTo(TransportState.STOPPED);
        assertThat(recording.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.IDLE);
        assertThat(recording.isRecordingInFlight()).isFalse();
        assertThat(recording.isTakeBeingWritten()).isFalse();
        assertThat(recording.activeTakeDirectory()).isEmpty();
        assertThat(audioEngine.getRecordingCallback()).isNull();
        assertThat(track.isRecording()).isFalse();
        assertThat(recIndicator.isVisible()).isFalse();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage()).containsIgnoringCase("backend");
        try (Stream<Path> files = Files.walk(projectDirectory)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "true,true,false", "false,false,true"})
    void story325MidiCloseFailureKeepsNotesUndoAndDirtyStateWhileDrainingEveryDevice(
            boolean closeWithError, boolean failTransmitterClose, boolean failUndoListener) throws Exception {
        DawProject project = new DawProject("keys", new AudioFormat(48000, 2, 16, 256));
        Track first = new Track("First", TrackType.MIDI), second = new Track("Second", TrackType.MIDI);
        first.setArmed(true); second.setArmed(true);
        first.setMidiInputDeviceName("first"); second.setMidiInputDeviceName("second");
        project.addTrack(first); project.addTrack(second);
        CountingMidiInput input1 = new CountingMidiInput(false);
        CountingMidiInput input2 = new CountingMidiInput(!failTransmitterClose, closeWithError && !failTransmitterClose);
        input2.transmitterCloseWithError = failTransmitterClose;
        List<String> closedDevices = new CopyOnWriteArrayList<>();
        input1.onClose = () -> closedDevices.add("first");
        input2.onClose = () -> closedDevices.add("second");
        TransportController recording = newController(project);
        runStrictHandler(() -> recording.setMidiInputDeviceResolverForTest(name -> name.equals("first") ? input1 : input2));
        record(recording);
        var note = new javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_ON, 0, 60, 100);
        input1.getTransmitters().getFirst().getReceiver().send(note, -1);
        input2.getTransmitters().getFirst().getReceiver().send(note, -1);
        UndoHistoryListener listener = history -> {
            if (failUndoListener && history.undoSize() == 1) throw new AssertionError("MIDI undo listener failed");
        };
        runStrictHandler(() -> undoManager.addHistoryListener(listener));
        try {
            runStrictHandler(recording::toggleRecord);
        } finally {
            runStrictHandler(() -> undoManager.removeHistoryListener(listener));
        }
        flushFx();
        assertThat(first.getMidiClip().size()).isEqualTo(1);
        assertThat(second.getMidiClip().size()).isEqualTo(1);
        assertThat(project.isDirty()).isTrue();
        assertThat(undoManager.undoSize()).isEqualTo(2);
        assertThat(closedDevices).containsExactly("second", "first");
        assertThat(project.getTransport().getState()).isEqualTo(TransportState.STOPPED);
        assertThat(recording.recordCoordinator().getState()).isEqualTo(RecordState.IDLE);
        assertThat(recording.recordCoordinator().recordAvailableProperty().get()).isTrue();
        assertThat(recIndicator.isVisible()).isFalse();
        assertThat(audioEngine.isStreamOpen()).isFalse();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage()).contains("Second", "MIDI", "close failed",
                closeWithError ? "AssertionError" : "IllegalStateException");
        if (failUndoListener) assertThat(notificationBar.getMessage()).contains("AssertionError: MIDI undo listener failed");
        assertThat(statusBarLabel.getText()).isEqualTo(notificationBar.getMessage());
        runStrictHandler(() -> {
            recording.stop();
            recording.recordCoordinator().stopMidiRecording();
        });
        flushFx();
        for (CountingMidiInput input : List.of(input1, input2)) {
            assertThat(input.opens).isEqualTo(1);
            assertThat(input.transmitters).isEqualTo(1);
            assertThat(input.closes).isEqualTo(1);
            assertThat(input.transmitterCloses).isEqualTo(1);
            assertThat(input.isOpen()).isFalse();
            assertThat(input.isConnected()).isFalse();
        }
        assertThat(first.isRecording()).isFalse(); assertThat(second.isRecording()).isFalse();
        assertThat(recording.isRecordingInFlight()).isFalse();
        assertThat(recording.isTakeBeingWritten()).isFalse();
        assertThat(closedDevices).containsExactly("second", "first");
        assertThat(undoManager.undoSize()).isEqualTo(2);
        runStrictHandler(() -> { undoManager.undo(); undoManager.undo(); });
        assertThat(first.getMidiClip().size()).isZero();
        assertThat(second.getMidiClip().size()).isZero();
        runStrictHandler(() -> { undoManager.redo(); undoManager.redo(); });
        assertThat(first.getMidiClip().size()).isEqualTo(1);
        assertThat(second.getMidiClip().size()).isEqualTo(1);
    }

    @Test
    void story325AcceptedRecordCancelsPostRollAndItsStaleTimerCannotStopCapture() throws Exception {
        DawProject project = new DawProject("keys", new AudioFormat(48000, 2, 16, 256));
        Track track = new Track("Keys", TrackType.MIDI); track.setArmed(true); track.setMidiInputDeviceName("keys");
        project.addTrack(track);
        TransportController recording = newController(project);
        var input = new CountingMidiInput(false);
        AtomicReference<javafx.animation.PauseTransition> timer = new AtomicReference<>();
        var field = TransportController.class.getDeclaredField("postRollTimer"); field.setAccessible(true);
        runStrictHandler(() -> {
            recording.setMidiInputDeviceResolverForTest(_ -> input);
            project.getTransport().setPreRollPostRoll(PreRollPostRoll.enabled(0, 8));
            recording.start(); recording.stop();
            assertThat(project.getTransport().isInPostRoll()).isTrue();
            try { timer.set((javafx.animation.PauseTransition) field.get(recording)); }
            catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            recording.toggleRecord();
            assertThat(timer.get().getStatus()).isEqualTo(javafx.animation.Animation.Status.STOPPED);
            timer.get().getOnFinished().handle(new javafx.event.ActionEvent());
            assertThat(project.getTransport().getState()).isEqualTo(TransportState.RECORDING);
            assertThat(recording.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.RECORDING);
            assertThat(input.isConnected()).isTrue();
            assertThat(audioEngine.isStreamOpen()).isTrue();
            assertThat(recIndicator.isVisible()).isTrue();
        });
    }

    @Test
    void story325GrantedConfigurationLeaseBlocksRecordAfterProjectReplacement() throws Exception {
        DawProject oldProject = new DawProject("old", new AudioFormat(48000, 2, 16, 256));
        TransportController old = newController(oldProject);
        DefaultAudioEngineController controller = story325SettingsController(old);
        AtomicReference<TransportController> current = new AtomicReference<>(old);
        controller.setConfigurationActivityCallback(() -> current.get().recordCoordinator().refreshRecordAvailability());
        OpenCountingBackend backend = new OpenCountingBackend();
        try (AudioEngineController.ConfigurationLease ignored = controller.beginConfigurationChange()) {
            runStrictHandler(old::retire);
            DawProject replacement = new DawProject("new", new AudioFormat(48000, 2, 16, 256));
            giveTheProjectADirectory(replacement);
            Track track = replacement.createAudioTrack("Vox"); track.setArmed(true);
            TransportController incoming = newController(replacement, backend);
            current.set(incoming);
            runStrictHandler(() -> {
                incoming.recordCoordinator().setConfigurationInProgressCheck(controller::isConfigurationChangeInProgress);
                incoming.toggleRecord();
                assertThat(incoming.recordCoordinator().recordAvailableProperty().get()).isFalse();
                assertThat(incoming.isRecordingInFlight()).isFalse();
                assertThat(incoming.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.IDLE);
                assertThat(backend.opens.get()).isZero();
            });
        } finally {
            controller.shutdown();
        }
        flushFx();
        assertThat(current.get().recordCoordinator().recordAvailableProperty().get()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void story325MixedTakeKeepsMidiCloseErrorVisibleAfterAudioPublication(
            boolean closeWithError, boolean failTransmitterClose) throws Exception {
        DawProject project = new DawProject("mixed", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track audio = project.createAudioTrack("Vox"); audio.setArmed(true);
        Track first = new Track("First", TrackType.MIDI), midi = new Track("Keys", TrackType.MIDI);
        first.setArmed(true); midi.setArmed(true);
        first.setMidiInputDeviceName("first"); midi.setMidiInputDeviceName("keys");
        project.addTrack(first); project.addTrack(midi);
        var input1 = new CountingMidiInput(false);
        var input = new CountingMidiInput(!failTransmitterClose, closeWithError && !failTransmitterClose);
        input.transmitterCloseWithError = failTransmitterClose;
        List<String> closedDevices = new CopyOnWriteArrayList<>();
        input1.onClose = () -> closedDevices.add("first");
        input.onClose = () -> closedDevices.add("keys");
        TransportController recording = newController(project);
        runStrictHandler(() -> {
            recording.setMidiInputDeviceResolverForTest(name -> name.equals("first") ? input1 : input);
            audioEngine.setGraph(project.getTransport(), project.getMixer(), project.getTracks());
        });
        record(recording);
        var note = new javax.sound.midi.ShortMessage(javax.sound.midi.ShortMessage.NOTE_ON, 0, 60, 100);
        input1.getTransmitters().getFirst().getReceiver().send(note, -1);
        input.getTransmitters().getFirst().getReceiver().send(note, -1);
        awaitABlockOnDisk(recording, audio);
        stopAndAwaitTheTake(recording);
        assertThat(audio.getClips()).hasSize(1);
        assertThat(first.getMidiClip().size()).isEqualTo(1);
        assertThat(midi.getMidiClip().size()).isEqualTo(1);
        assertThat(project.isDirty()).isTrue();
        assertThat(undoManager.undoSize()).isEqualTo(3);
        assertThat(project.getTransport().getState()).isEqualTo(TransportState.STOPPED);
        assertThat(recording.recordCoordinator().getState()).isEqualTo(RecordState.IDLE);
        assertThat(recording.recordCoordinator().recordAvailableProperty().get()).isTrue();
        assertThat(recIndicator.isVisible()).isFalse();
        assertThat(audioEngine.isStreamOpen()).isFalse();
        assertThat(audioEngine.getRecordingCallback()).isNull();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage()).contains("Keys", "MIDI", "close failed",
                closeWithError ? "AssertionError" : "IllegalStateException");
        assertThat(statusBarLabel.getText()).isEqualTo(notificationBar.getMessage());
        runStrictHandler(() -> {
            recording.stop();
            recording.recordCoordinator().stopMidiRecording();
        });
        flushFx();
        assertThat(closedDevices).containsExactly("keys", "first");
        for (CountingMidiInput midiInput : List.of(input1, input)) {
            assertThat(midiInput.opens).isEqualTo(1);
            assertThat(midiInput.transmitters).isEqualTo(1);
            assertThat(midiInput.closes).isEqualTo(1);
            assertThat(midiInput.transmitterCloses).isEqualTo(1);
            assertThat(midiInput.isConnected()).isFalse();
            assertThat(midiInput.isOpen()).isFalse();
        }
        assertThat(first.isRecording()).isFalse(); assertThat(midi.isRecording()).isFalse();
        assertThat(recording.isRecordingInFlight()).isFalse();
        assertThat(recording.isTakeBeingWritten()).isFalse();
        assertThat(undoManager.undoSize()).isEqualTo(3);
        runStrictHandler(() -> { undoManager.undo(); undoManager.undo(); undoManager.undo(); });
        assertThat(audio.getClips()).isEmpty();
        assertThat(first.getMidiClip().size()).isZero();
        assertThat(midi.getMidiClip().size()).isZero();
        runStrictHandler(() -> { undoManager.redo(); undoManager.redo(); undoManager.redo(); });
        assertThat(audio.getClips()).hasSize(1);
        assertThat(first.getMidiClip().size()).isEqualTo(1);
        assertThat(midi.getMidiClip().size()).isEqualTo(1);
    }

    private static final class CountingMidiInput extends RecordingInFlightFixture.StubMidiInput {
        final boolean failClose;
        boolean closeWithError;
        boolean transmitterCloseWithError;
        Runnable onClose = () -> { };
        Runnable onOpen = () -> { };
        int opens, transmitters, closes, transmitterCloses;
        CountingMidiInput(boolean failClose) { this(failClose, false); }
        CountingMidiInput(boolean failClose, boolean closeWithError) {
            this.failClose = failClose;
            this.closeWithError = closeWithError;
        }
        @Override public void open() { opens++; super.open(); onOpen.run(); }
        @Override public javax.sound.midi.Transmitter getTransmitter() {
            transmitters++;
            var delegate = super.getTransmitter();
            return new javax.sound.midi.Transmitter() {
                @Override public void setReceiver(javax.sound.midi.Receiver receiver) { delegate.setReceiver(receiver); }
                @Override public javax.sound.midi.Receiver getReceiver() { return delegate.getReceiver(); }
                @Override public void close() {
                    transmitterCloses++;
                    delegate.close();
                    if (transmitterCloseWithError) throw new AssertionError("MIDI transmitter close failed");
                }
            };
        }
        @Override public void close() {
            closes++;
            onClose.run();
            super.close();
            if (closeWithError) throw new AssertionError("MIDI close failed");
            if (failClose) throw new IllegalStateException("MIDI close failed");
        }
    }

    private static final class SampleRateCountingBackend implements AudioBackend {
        final MockAudioBackend delegate = new MockAudioBackend();
        final AtomicInteger rates;
        SampleRateCountingBackend(AtomicInteger rates) { this.rates = rates; }
        @Override public String name() { return delegate.name(); }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public List<AudioDeviceInfo> listDevices() { return delegate.listDevices(); }
        @Override public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format, int frames) { delegate.open(device, format, frames); }
        @Override public void setSampleRate(DeviceId device, double rate) { rates.incrementAndGet(); }
        @Override public Flow.Publisher<AudioBlock> inputBlocks() { return delegate.inputBlocks(); }
        @Override public void sink(AudioBlock block) { delegate.sink(block); }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() { delegate.close(); }
    }

    // ── Story 323 review: a take still being written after its Stop ─────────

    /**
     * Counts the controller's completions of a stopped take
     * ({@code setTakeCompletionForTest}) — each the real
     * {@code RecordingPipeline.completeStop(Function)} unless the test says
     * otherwise — recording the pipeline and whether it ran on the FX thread.
     * The first completion may be made to throw ({@link #firstFailure}) or to
     * hand back no clip ({@link #firstReturnsNothing}: the clips the real
     * completion added are taken off their tracks again); any later one is
     * the real completion.
     */
    private static final class CountingCompletion implements TransportController.TakeCompletion {
        final List<RecordingPipeline> calls = new CopyOnWriteArrayList<>();
        final List<Boolean> onFxThread = new CopyOnWriteArrayList<>();
        /** Thrown by the first completion instead of completing the take. */
        volatile RuntimeException firstFailure;
        /** The first completion completes the take, takes its clips back off the tracks and hands back none. */
        volatile boolean firstReturnsNothing;

        @Override
        public List<AudioClip> complete(RecordingPipeline pipeline,
                                        java.util.function.Function<List<String>, float[][]> loadedAudio) {
            calls.add(pipeline);
            onFxThread.add(Platform.isFxApplicationThread());
            if (calls.size() > 1) {
                return pipeline.completeStop(loadedAudio);
            }
            RuntimeException failure = firstFailure;
            if (failure != null) {
                throw failure;
            }
            List<AudioClip> clips = pipeline.completeStop(loadedAudio);
            if (!firstReturnsNothing) {
                return clips;
            }
            pipeline.getRecordedClips().forEach(Track::removeClip);
            return List.of();
        }
    }

    /**
     * Records an audio take that has at least one block on disk, with the
     * {@link #hold} on its capture thread installed (unarmed), {@code completion}
     * as the way the controller completes it and the production early-seal
     * signal.
     */
    private TransportController recordingWithABlockOnDisk(DawProject project, Track armed,
                                                          TransportController.TakeCompletion completion)
            throws Exception {
        return recordingWithABlockOnDisk(project, armed, completion, RecordingPipeline::earlySeal);
    }

    /**
     * Records an audio take that has at least one block on disk, with the
     * {@link #hold} on its capture thread installed (unarmed),
     * {@code completion} as the way the controller completes it and
     * {@code earlySeal} as the way the controller reads its early-seal signal.
     */
    private TransportController recordingWithABlockOnDisk(DawProject project, Track armed,
                                                          TransportController.TakeCompletion completion,
                                                          TransportController.EarlySealSignal earlySeal)
            throws Exception {
        TransportController controller = newController(project, new MockAudioBackend());
        runHandler(() -> {
            hold.installOn(controller);
            controller.setTakeCompletionForTest(completion);
            controller.setEarlySealSignalForTest(earlySeal);
        });
        record(controller);
        awaitABlockOnDisk(controller, armed);
        return controller;
    }

    /** Holds the recording take's capture thread mid-pass, so its Stop leaves the take still being written. */
    private void holdTheCaptureThread() throws InterruptedException {
        hold.arm();
        hold.awaitHolding(Duration.ofSeconds(10));
    }

    /** Releases the take's capture thread and waits, bounded, until the take has been published. */
    private void releaseAndAwaitThePublication(TransportController controller) throws Exception {
        hold.release();
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published");
    }

    /** Waits, for at most 10 s, until the active take of {@code controller} has a block of {@code armed} on disk. */
    private static void awaitABlockOnDisk(TransportController controller, Track armed) throws IOException {
        Path part = controller.activeTakeDirectory().orElseThrow()
                .resolve(armed.getId()).resolve("segment-000.wav.part");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        // The engine's render pump delivers blocks at the device pace; the
        // take has a clip to publish once one is past the 44-byte header.
        while (!Files.exists(part) || Files.size(part) <= 44) {
            assertThat(System.nanoTime() - deadline < 0)
                    .as("fixture: a recorded block reached %s within 10 s", part).isTrue();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /** One FX turn after everything posted so far: the posts run in order. */
    private static void flushFx() throws Exception {
        runHandler(() -> { });
    }

    @Test
    void aTakeStillBeingWrittenIsPublishedOnceOnTheFxThreadWhenItsThreadHasTerminated() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions);
        Path takes = TakeDirectories.takesDirectory(ProjectManager.audioDirectory(projectDirectory));
        List<Path> takeDirectories = listing(takes);
        RecordingPipeline stopped = hold.pipeline();
        holdTheCaptureThread();

        assertThat(runHandlerCatching(controller::stop))
                .as("the take still being written never escapes the Stop handler").isNull();

        assertThat(completions.calls).as("nothing is completed while the take is being written").isEmpty();
        assertThat(statusBarLabel.getText()).isEqualTo(TransportController.TAKE_FINISHING_MESSAGE);
        assertThat(stillWritingDelay.delays()).as("the Stop scheduled the still-writing warning")
                .containsExactly(TransportController.TAKE_STILL_WRITING_DELAY);
        runHandler(stillWritingDelay::fireAll); // the delay has passed and the take is still being written
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.WARNING);
        assertThat(notificationBar.getMessage()).isEqualTo(TransportController.TAKE_STILL_WRITING_MESSAGE);
        assertThat(statusBarLabel.getText()).isEqualTo(TransportController.TAKE_STILL_WRITING_MESSAGE);
        assertThat(armed.getClips()).as("no clip before the take is written").isEmpty();
        assertThat(undoManager.canUndo()).as("no undo entry before the take is written").isFalse();
        assertThat(recIndicator.isVisible()).as("the rest of Stop ran: REC indicator hidden").isFalse();
        assertThat(project.getTransport().getState())
                .as("the transport is stopped (by the pipeline's one-shot stop)")
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(controller.isTakeBeingWritten()).as("the take is FINALIZING").isTrue();

        // Record while the take is still being written is refused, visibly.
        assertThat(runHandlerCatching(controller::toggleRecord)).isNull();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.WARNING);
        assertThat(notificationBar.getMessage()).isEqualTo(TransportController.RECORD_WHILE_WRITING_MESSAGE);
        assertThat(statusBarLabel.getText()).isEqualTo(TransportController.RECORD_WHILE_WRITING_MESSAGE);
        assertThat(project.getTransport().getState())
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(controller.isPreparingTake()).as("no take is being prepared").isFalse();
        assertThat(controller.activeTakeDirectory()).as("no pipeline was built").isEmpty();
        assertThat(listing(takes)).as("no take directory was allocated").isEqualTo(takeDirectories);
        assertThat(recIndicator.isVisible()).isFalse();

        // A second Stop is an ordinary Stop: it neither stops nor completes the take again.
        assertThat(runHandlerCatching(controller::stop)).isNull();
        assertThat(completions.calls).as("the second Stop completes nothing").isEmpty();

        // Playback started while the take is being written belongs to the
        // user: the turn that publishes the take leaves it alone.
        runHandler(controller::start);
        assertThat(project.getTransport().getState())
                .as("fixture: playback started while the take is being written")
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.PLAYING);

        releaseAndAwaitThePublication(controller);

        assertThat(completions.calls).as("the stopped take is completed once").containsExactly(stopped);
        assertThat(completions.onFxThread).as("on the FX thread").containsExactly(true);
        assertThat(armed.getClips()).hasSize(1);
        assertThat(undoManager.undoSize()).isEqualTo(1);
        assertThat(undoManager.undoDescription()).isEqualTo("Record Audio");
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.SUCCESS);
        assertThat(notificationBar.getMessage()).isEqualTo("Recording stopped — 1 clip created");
        assertThat(statusBarLabel.getText()).isEqualTo("Recording stopped — 1 clip created");
        assertThat(project.getTransport().getState())
                .as("publishing the take does not stop the playback started while the take was being written")
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.PLAYING);
        assertThat(controller.isTakeBeingWritten()).as("the take has been published").isFalse();

        // Record is available again (from a stopped transport).
        runHandler(controller::stop);
        record(controller);
        try {
            assertThat(controller.activeTakeDirectory()).as("a new take started").isPresent();
        } finally {
            stopAndAwaitTheTake(controller);
        }
    }

    @Test
    void aTakeWhoseCompletionFailsIsLoggedAndShownAsAnErrorAndRecordIsAvailableAgain() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        completions.firstFailure = new IllegalStateException("injected completion failure");
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions);
        List<LogRecord> severe = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.SEVERE) {
                    severe.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(RecordCoordinator.class.getName());
        logger.addHandler(capture);
        try {
            assertThat(runHandlerCatching(controller::stop)).isNull();
            awaitOnFx(() -> !controller.isTakeBeingWritten(), "the turn that publishes the take ran");

            assertThat(completions.calls).as("fixture: the completion was attempted").hasSize(1);
            assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
            assertThat(notificationBar.getMessage())
                    .startsWith("Recording could not be finished — injected completion failure");
            assertThat(statusBarLabel.getText()).isEqualTo(notificationBar.getMessage());
            assertThat(severe).as("the failure is logged, never swallowed")
                    .anySatisfy(record -> assertThat(record.getThrown()).isSameAs(completions.firstFailure));
            assertThat(undoManager.canUndo()).as("nothing was published").isFalse();
        } finally {
            logger.removeHandler(capture);
        }

        record(controller);
        try {
            assertThat(controller.activeTakeDirectory()).as("Record is available again").isPresent();
        } finally {
            stopAndAwaitTheTake(controller);
        }
    }

    @Test
    void aTakeWrittenWithoutAClipTakesBackTheStillWritingStatus() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        completions.firstReturnsNothing = true;
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions);
        holdTheCaptureThread();

        assertThat(runHandlerCatching(controller::stop)).isNull();
        runHandler(stillWritingDelay::fireAll);
        assertThat(statusBarLabel.getText()).isEqualTo(TransportController.TAKE_STILL_WRITING_MESSAGE);
        releaseAndAwaitThePublication(controller);

        assertThat(completions.calls).as("fixture: the take was completed").hasSize(1);
        assertThat(statusBarLabel.getText()).isEqualTo(TransportController.TAKE_WRITTEN_WITHOUT_CLIPS_MESSAGE);
        assertThat(undoManager.canUndo()).isFalse();
    }

    // ── Story 323 review: the take's controller retired by a project change ──

    /**
     * MainController retires a controller when it builds the next project's
     * ({@code createTransportController}); a take that controller was still
     * writing finishes afterwards. Nothing is published into the project it
     * was recorded in — no clip, no undo entry, no SUCCESS — and the take is
     * not completed; one WARNING, logged and shown, names that project
     * and the take's directory, and the status bar's promise that the clips
     * will appear is taken back.
     */
    @Test
    void aRetiredControllerPublishesNothingWhenItsTakeFinishesAndSaysWhereItsFilesAre() throws Exception {
        DawProject project = new DawProject("Song A", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions);
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        holdTheCaptureThread();
        assertThat(runHandlerCatching(controller::stop)).isNull();
        assertThat(controller.isTakeBeingWritten()).as("fixture: the take is still being written").isTrue();
        assertThat(statusBarLabel.getText()).as("fixture").isEqualTo(TransportController.TAKE_FINISHING_MESSAGE);

        runHandler(controller::retire);
        NotificationHistoryService shown = new NotificationHistoryService();
        runHandler(() -> notificationBar.setHistoryService(shown));
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = warningCapture(warnings);
        Logger logger = Logger.getLogger(RecordCoordinator.class.getName());
        logger.addHandler(capture);
        try {
            releaseAndAwaitThePublication(controller); // the capture thread terminates
            runHandler(stillWritingDelay::fireAll); // and the delayed warning is retired too
        } finally {
            logger.removeHandler(capture);
        }

        String expected = TransportController.takeOfAReplacedProjectMessage("Song A", takeDirectory);
        assertThat(expected).as("the warning names the project and the take's directory")
                .contains("'Song A'", takeDirectory.toString());
        assertThat(completions.calls).as("a retired controller does not complete the take").isEmpty();
        assertThat(armed.getClips()).as("no clip on the replaced project's track").isEmpty();
        assertThat(undoManager.canUndo()).as("no undo entry in the replaced project's history").isFalse();
        assertThat(shown.getEntries()).as("one WARNING, and no SUCCESS, once the take has finished")
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message()).isEqualTo(expected);
                });
        assertThat(warnings).as("the warning is logged").anySatisfy(
                record -> assertThat(record.getMessage()).isEqualTo(expected));
        assertThat(statusBarLabel.getText())
                .as("nothing else takes back the status bar's promise that the clips will appear, so the"
                        + " retired controller does, in the words of its warning")
                .isEqualTo(TransportController.TAKE_OF_A_REPLACED_PROJECT_STATUS);
        assertThat(controller.isTakeBeingWritten()).as("the take is no longer being written").isFalse();
        assertThat(TakeManifest.manifestPath(takeDirectory))
                .as("the take's files are where the warning says").isRegularFile();
    }

    /**
     * The retirement is read when the turn that publishes the take runs, not
     * when it is posted: a project change that lands between the take's
     * termination and the FX turn it posted publishes nothing either.
     */
    @Test
    void aControllerRetiredAfterItsTakeFinishedButBeforeThePublishingTurnRanPublishesNothing() throws Exception {
        DawProject project = new DawProject("Song B", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions);
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        RecordingPipeline stopped = hold.pipeline();
        holdTheCaptureThread();
        assertThat(runHandlerCatching(controller::stop)).isNull();

        // One FX turn: the capture thread is released and terminates — its
        // termination posts the publishing turn — and the project change
        // retires the controller before that post runs. The wait for the
        // termination inside this turn is the test's, bounded, and the
        // capture thread needs nothing from the FX thread to terminate.
        assertThat(runHandlerCatching(() -> {
            hold.release();
            try {
                stopped.termination().toCompletableFuture().get(4, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("fixture: the released capture thread terminated within 4 s", e);
            }
            controller.retire();
        })).isNull();
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the publishing turn ran");

        assertThat(completions.calls).as("the take is not completed").isEmpty();
        assertThat(armed.getClips()).isEmpty();
        assertThat(undoManager.canUndo()).isFalse();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.WARNING);
        assertThat(notificationBar.getMessage())
                .isEqualTo(TransportController.takeOfAReplacedProjectMessage("Song B", takeDirectory));
    }

    /**
     * The retired controller takes back only its own promise: a status text
     * something else wrote after the Stop is left as it is.
     */
    @Test
    void aRetiredControllerLeavesAStatusTextItDidNotWrite() throws Exception {
        DawProject project = new DawProject("Song C", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        TransportController controller = recordingWithABlockOnDisk(project, armed, new CountingCompletion());
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        holdTheCaptureThread();
        assertThat(runHandlerCatching(controller::stop)).isNull();
        assertThat(statusBarLabel.getText()).as("fixture").isEqualTo(TransportController.TAKE_FINISHING_MESSAGE);

        runHandler(() -> {
            statusBarLabel.setText("Metronome: ON");
            controller.retire();
        });
        releaseAndAwaitThePublication(controller);

        assertThat(notificationBar.getMessage()).as("fixture: the publishing turn ran, retired")
                .isEqualTo(TransportController.takeOfAReplacedProjectMessage("Song C", takeDirectory));
        assertThat(statusBarLabel.getText()).isEqualTo("Metronome: ON");
    }

    // ── Story 323 review: a take the capture thread sealed early ────────────

    /** Free space below the default 64 MiB floor, every segment sealed. */
    private static final EarlySeal DISK_EXHAUSTED =
            new EarlySeal.DiskExhausted(DiskHeadroomWatch.DEFAULT_FLOOR_BYTES, false, true);

    /**
     * Stands in for the core's early-seal signal, whose triggers — an
     * injected disk-headroom watch, a writer set to fail — are
     * package-private to daw-core: one signal per pipeline, completed by the
     * test as the capture thread completes it. The pipelines are real and
     * nothing here makes their capture threads seal early, so each take is
     * sealed by its Stop; what the controller does with the signal is what
     * is tested.
     */
    private static final class ControlledEarlySeal implements TransportController.EarlySealSignal {
        private final Map<RecordingPipeline, CompletableFuture<EarlySeal>> signals = new ConcurrentHashMap<>();
        /** When set, a pipeline's signal has already completed with it when the controller first reads it. */
        volatile EarlySeal completedBeforeTheFirstRead;

        @Override
        public CompletionStage<EarlySeal> of(RecordingPipeline pipeline) {
            return signals.computeIfAbsent(pipeline, _ -> {
                CompletableFuture<EarlySeal> signal = new CompletableFuture<>();
                EarlySeal already = completedBeforeTheFirstRead;
                if (already != null) {
                    signal.complete(already);
                }
                return signal;
            });
        }

        /** The signal of the one take started so far. */
        CompletableFuture<EarlySeal> signal() {
            assertThat(signals).as("fixture: one take read its signal").hasSize(1);
            return signals.values().iterator().next();
        }

        /** Completes {@link #signal()} with {@code seal} on a thread named as the capture thread is, and waits for it. */
        void sealEarlyOnTheCaptureThread(EarlySeal seal) throws InterruptedException {
            CompletableFuture<EarlySeal> signal = signal();
            Thread capture = Thread.ofPlatform().name(CaptureFlushService.THREAD_NAME)
                    .start(() -> signal.complete(seal));
            capture.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(capture.isAlive()).as("fixture: the signal was completed").isFalse();
        }
    }

    /** Records every notification shown from now on. */
    private NotificationHistoryService notificationsFromNowOn() throws Exception {
        NotificationHistoryService shown = new NotificationHistoryService();
        runHandler(() -> notificationBar.setHistoryService(shown));
        return shown;
    }

    /**
     * The capture thread seals the take on its own: the take is stopped on a
     * later FX turn exactly as the user's Stop stops it — callback removed,
     * transport stopped with Stopped announced, REC indicator hidden — and,
     * once its capture thread has terminated, the clip and the "Record Audio"
     * undo entry are published and the seal is reported once, as an ERROR
     * naming the cause and what was kept, never as the SUCCESS of a normal
     * Stop.
     */
    @Test
    void aTakeSealedEarlyIsStoppedOnAnFxTurnAndReportedAsAnError() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        ControlledEarlySeal earlySeal = new ControlledEarlySeal();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions, earlySeal);
        awaitSessionInputCheck(controller);
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        NotificationHistoryService shown = notificationsFromNowOn();
        List<BusEvent> published = new CopyOnWriteArrayList<>();
        var previousBus = EventBusPublisher.getDefault();
        try {
            EventBusPublisher.setDefault(new PublishHookEventBus(published::add));
            earlySeal.sealEarlyOnTheCaptureThread(DISK_EXHAUSTED);
            flushFx(); // the turn the capture thread's dependent posted ran before this one
        } finally {
            EventBusPublisher.setDefault(previousBus);
        }

        assertThat(audioEngine.getRecordingCallback()).as("the recording callback is removed").isNull();
        assertThat(project.getTransport().getState())
                .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.STOPPED);
        assertThat(published).as("the transport's stop is announced")
                .filteredOn(TransportEvent.Stopped.class::isInstance).hasSize(1);
        assertThat(recIndicator.isVisible()).as("the REC indicator is hidden").isFalse();
        assertThat(armed.isRecording()).isFalse();
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published");
        assertThat(completions.calls).as("the take was completed once").hasSize(1);
        assertThat(completions.onFxThread).as("on the FX thread").containsExactly(true);
        assertThat(armed.getClips()).as("the take's clip is published").hasSize(1);
        assertThat(undoManager.undoSize()).isEqualTo(1);
        assertThat(undoManager.undoDescription()).isEqualTo("Record Audio");
        String expected = TransportController.takeSealedEarlyMessage(DISK_EXHAUSTED, 1, takeDirectory);
        assertThat(expected).isEqualTo("Recording stopped — free disk space fell below 64 MiB;"
                + " the audio recorded before that is kept (1 clip created)");
        assertThat(shown.getEntries()).as("one ERROR, and no SUCCESS").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo(expected);
        });
        assertThat(statusBarLabel.getText()).isEqualTo(expected);
        assertThat(controller.activeTakeDirectory()).as("no take is in flight").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSealKnownAtReadinessNeverStartsCaptureAndKeepsThePublicationFence(boolean throwingStopListener) throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox"); armed.setArmed(true);
        Track midi = new Track("Keys", TrackType.MIDI); midi.setArmed(true); midi.setMidiInputDeviceName("keys");
        project.addTrack(midi);
        var input = new CountingMidiInput(false);
        ControlledEarlySeal earlySeal = new ControlledEarlySeal();
        earlySeal.completedBeforeTheFirstRead = DISK_EXHAUSTED;
        TransportController controller = newController(project, new MockAudioBackend());
        AtomicInteger transportRecording = new AtomicInteger(), stateRecording = new AtomicInteger(), recLit = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger stopListenerCalls = new AtomicInteger();
        AtomicReference<Runnable> removeStopListener = new AtomicReference<>();
        CountDownLatch publicationEntered = new CountDownLatch(1);
        Object publicationGate = new Object();
        var shown = new NotificationHistoryService();
        runStrictHandler(() -> {
            if (throwingStopListener) controller.start();
            controller.setMidiInputDeviceResolverForTest(_ -> input);
            controller.setEarlySealSignalForTest(earlySeal);
            notificationBar.setHistoryService(shown);
            removeStopListener.set(project.getTransport().addChangeListener(kind -> {
                if (kind == Transport.ChangeKind.STATE && project.getTransport().getState() == TransportState.RECORDING) transportRecording.incrementAndGet();
                if (throwingStopListener && kind == Transport.ChangeKind.STATE
                        && project.getTransport().getState() == TransportState.STOPPED) {
                    stopListenerCalls.incrementAndGet();
                    throw new IllegalStateException("injected STOPPED listener failure");
                }
            }));
            controller.recordCoordinator().stateProperty().addListener((_, _, value) -> {
                if (value == com.benesquivelmusic.daw.app.ui.recording.RecordState.RECORDING) stateRecording.incrementAndGet();
            });
            recIndicator.visibleProperty().addListener((_, _, value) -> { if (value) recLit.incrementAndGet(); });
            controller.setTakeCompletionForTest((pipeline, loaded) -> {
                completions.incrementAndGet();
                publicationEntered.countDown();
                Platform.enterNestedEventLoop(publicationGate);
                return pipeline.completeStop(loaded);
            });
        });
        List<BusEvent> published = new CopyOnWriteArrayList<>();
        var previousBus = EventBusPublisher.getDefault();
        CompletableFuture<Runnable> configuration = null;
        try {
            EventBusPublisher.setDefault(new PublishHookEventBus(published::add));
            runStrictHandler(controller::toggleRecord);
            assertThat(publicationEntered.await(10, TimeUnit.SECONDS)).isTrue();
            runStrictHandler(() -> {
                assertThat(controller.isTakeBeingWritten()).isTrue();
                assertThat(controller.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.ABORTED);
                assertThat(controller.recordCoordinator().recordAvailableProperty().get()).isFalse();
                assertThat(audioEngine.getRecordingCallback()).isNull();
                controller.toggleRecord();
            });
            configuration = controller.recordCoordinator().requestConfigurationChange().toCompletableFuture();
            flushFx();
            assertThat(configuration.isDone()).isFalse();
            assertThat(transportRecording.get()).isZero(); assertThat(stateRecording.get()).isZero(); assertThat(recLit.get()).isZero();
            assertThat(input.opens).isZero();
            assertThat(published).noneMatch(TransportEvent.Started.class::isInstance);
        } finally {
            EventBusPublisher.setDefault(previousBus);
            if (publicationEntered.getCount() == 0) runStrictHandler(() -> Platform.exitNestedEventLoop(publicationGate, null));
        }
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "sealed preparation finishes publication");
        if (configuration != null) configuration.get(5, TimeUnit.SECONDS).run();
        flushFx();
        assertThat(completions.get()).isEqualTo(1);
        assertThat(stopListenerCalls.get()).isEqualTo(throwingStopListener ? 1 : 0);
        assertThat(controller.activeTakeDirectory()).isEmpty();
        assertThat(controller.recordCoordinator().getState()).isEqualTo(com.benesquivelmusic.daw.app.ui.recording.RecordState.IDLE);
        assertThat(project.getTransport().getState()).isEqualTo(TransportState.STOPPED);
        assertThat(audioEngine.getRecordingCallback()).isNull();
        assertThat(audioEngine.isRunning()).isFalse();
        assertThat(audioEngine.isStreamOpen()).isFalse();
        assertThat(recIndicator.isVisible()).isFalse();
        assertThat(armed.isRecording()).isFalse(); assertThat(midi.isRecording()).isFalse();
        assertThat(shown.getEntries()).filteredOn(entry -> entry.level() == NotificationLevel.ERROR).hasSize(1);
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage()).startsWith("Recording stopped — free disk space fell below 64 MiB; ");
        if (throwingStopListener) assertThat(notificationBar.getMessage()).contains("take cleanup failed", "injected STOPPED listener failure");
        assertThat(statusBarLabel.getText()).isEqualTo(notificationBar.getMessage());
        try (Stream<Path> files = Files.walk(projectDirectory)) {
            assertThat(files.filter(path -> path.getFileName().toString().equals("take.manifest")).toList()).hasSize(1);
        }
        if (throwingStopListener) {
            runStrictHandler(() -> {
                removeStopListener.get().run();
                controller.setEarlySealSignalForTest(RecordingPipeline::earlySeal);
                controller.setTakeCompletionForTest(RecordingPipeline::completeStop);
            });
            record(controller);
            stopAndAwaitTheTake(controller);
            assertThat(notificationBar.getCurrentLevel()).isNotEqualTo(NotificationLevel.ERROR);
            assertThat(notificationBar.getMessage()).doesNotContain("injected STOPPED listener failure", "take cleanup failed");
        }
    }

    @Test
    void anEarlySealDuringMidiOpenIsRecheckedBeforeCaptureBegins() throws Exception {
        DawProject project = new DawProject("mixed", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox"); armed.setArmed(true);
        Track midi = new Track("Keys", TrackType.MIDI); midi.setArmed(true); midi.setMidiInputDeviceName("keys"); project.addTrack(midi);
        var input = new CountingMidiInput(false);
        ControlledEarlySeal earlySeal = new ControlledEarlySeal();
        TransportController controller = newController(project);
        AtomicInteger recorded = new AtomicInteger();
        runStrictHandler(() -> {
            controller.setEarlySealSignalForTest(earlySeal);
            controller.setMidiInputDeviceResolverForTest(_ -> {
                earlySeal.signal().complete(DISK_EXHAUSTED);
                return input;
            });
            project.getTransport().addChangeListener(kind -> {
                if (project.getTransport().getState() == TransportState.RECORDING) recorded.incrementAndGet();
            });
        });
        record(controller);
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "unannounced mixed take finalizes");
        assertThat(recorded.get()).isZero();
        assertThat(input.opens).isEqualTo(1); assertThat(input.closes).isEqualTo(1);
        assertThat(input.isConnected()).isFalse();
        assertThat(audioEngine.getRecordingCallback()).isNull();
        assertThat(recIndicator.isVisible()).isFalse();
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
    }

    /**
     * The user's Stop runs before the early seal's FX turn: that Stop's take
     * is published and the seal reported, once, and the turn then does
     * nothing — no second stop, no second report.
     */
    @Test
    void aUserStopThatWinsTheRaceReportsTheEarlySealOnce() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        ControlledEarlySeal earlySeal = new ControlledEarlySeal();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions, earlySeal);
        awaitSessionInputCheck(controller);
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        NotificationHistoryService shown = notificationsFromNowOn();
        EarlySeal seal = new EarlySeal.WriteFailed(
                new UncheckedIOException("write failed on segment-000.wav.part", new IOException("injected")), true);

        // One FX turn: the signal completes — its dependent posts the
        // auto-Stop to a later turn — and the user's Stop runs first.
        assertThat(runHandlerCatching(() -> {
            earlySeal.signal().complete(seal);
            controller.stop();
        })).isNull();
        flushFx(); // the posted turn
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published");

        assertThat(completions.calls).as("stopped once, by the user's Stop, and completed once").hasSize(1);
        String expected = TransportController.takeSealedEarlyMessage(seal, 1, takeDirectory);
        assertThat(expected).isEqualTo("Recording stopped — writing the take to disk failed (injected);"
                + " the audio recorded before that is kept (1 clip created)");
        assertThat(shown.getEntries()).as("one ERROR, and no SUCCESS").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo(expected);
        });
        assertThat(statusBarLabel.getText()).isEqualTo(expected);
        assertThat(armed.getClips()).hasSize(1);
        assertThat(undoManager.undoSize()).isEqualTo(1);
    }

    /**
     * The FX turn does nothing once the take it was posted for is no longer
     * active. (The core never completes a take's signal after that take's
     * stop has returned; the test seam does, so that the turn's own guard is
     * what is seen.)
     */
    @Test
    void anEarlySealTurnAfterTheTakeWasStoppedDoesNothing() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        ControlledEarlySeal earlySeal = new ControlledEarlySeal();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions, earlySeal);
        awaitSessionInputCheck(controller);
        NotificationHistoryService shown = notificationsFromNowOn();
        stopAndAwaitTheTake(controller);
        assertThat(completions.calls).as("fixture: the user's Stop, and its take published").hasSize(1);

        earlySeal.sealEarlyOnTheCaptureThread(DISK_EXHAUSTED);
        flushFx();

        assertThat(completions.calls).as("no second stop").hasSize(1);
        assertThat(shown.getEntries()).as("the user's Stop published its SUCCESS, and nothing followed")
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.SUCCESS, "Recording stopped — 1 clip created"));
        assertThat(statusBarLabel.getText()).isEqualTo("Recording stopped — 1 clip created");
        assertThat(undoManager.undoSize()).isEqualTo(1);
    }

    /**
     * A retired controller's FX turn does nothing: the take it was posted
     * for belongs to the replaced project, and nothing of it is stopped,
     * published or shown.
     */
    @Test
    void aRetiredControllerLeavesATakeSealedEarlyAsItIs() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        ControlledEarlySeal earlySeal = new ControlledEarlySeal();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions, earlySeal);
        awaitSessionInputCheck(controller);
        runHandler(controller::retire);
        NotificationHistoryService shown = notificationsFromNowOn();
        try {
            earlySeal.sealEarlyOnTheCaptureThread(DISK_EXHAUSTED);
            flushFx();

            assertThat(controller.isRecordingInFlight()).as("a retired controller does not stop the take").isTrue();
            assertThat(controller.isTakeBeingWritten()).isFalse();
            assertThat(audioEngine.getRecordingCallback()).isNotNull();
            assertThat(project.getTransport().getState())
                    .isEqualTo(com.benesquivelmusic.daw.core.transport.TransportState.RECORDING);
            assertThat(armed.getClips()).isEmpty();
            assertThat(undoManager.canUndo()).isFalse();
            assertThat(shown.getEntries()).as("nothing is shown").isEmpty();
        } finally {
            stopAndAwaitTheTake(controller); // releases the take's files
        }
        assertThat(completions.calls).as("nor, retired, does it complete the take once it is stopped").isEmpty();
    }

    /**
     * An early seal while a stopped take is still being written — the capture
     * thread sealed the take in its final sweep — causes no second stop: its
     * FX turn does nothing, and the turn that publishes the take reports the
     * seal once, instead of the SUCCESS.
     */
    @Test
    void anEarlySealWhileTheTakeIsStillBeingWrittenIsReportedOnceWhenItIsPublished() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        ControlledEarlySeal earlySeal = new ControlledEarlySeal();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions, earlySeal);
        awaitSessionInputCheck(controller);
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        NotificationHistoryService shown = notificationsFromNowOn();
        holdTheCaptureThread();
        assertThat(runHandlerCatching(controller::stop)).isNull();
        assertThat(controller.isTakeBeingWritten()).as("fixture: the take is still being written").isTrue();
        runHandler(stillWritingDelay::fireAll); // the delay has passed and the take is still being written

        earlySeal.sealEarlyOnTheCaptureThread(DISK_EXHAUSTED);
        flushFx();
        assertThat(controller.isTakeBeingWritten()).as("the early seal's turn does not stop the take again").isTrue();
        assertThat(completions.calls).isEmpty();
        assertThat(statusBarLabel.getText())
                .as("the turn ran no Stop at all: the status bar still says the take is being written")
                .isEqualTo(TransportController.TAKE_STILL_WRITING_MESSAGE);

        releaseAndAwaitThePublication(controller);

        assertThat(completions.calls).as("only the publishing turn completed the take").hasSize(1);
        String expected = TransportController.takeSealedEarlyMessage(DISK_EXHAUSTED, 1, takeDirectory);
        assertThat(shown.getEntries()).as("the still-writing WARNING, then one ERROR, and no SUCCESS")
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.WARNING, TransportController.TAKE_STILL_WRITING_MESSAGE),
                        tuple(NotificationLevel.ERROR, expected));
        assertThat(statusBarLabel.getText()).isEqualTo(expected);
        assertThat(armed.getClips()).hasSize(1);
        assertThat(undoManager.undoDescription()).isEqualTo("Record Audio");
        assertThat(controller.isTakeBeingWritten()).isFalse();
    }

    @Test
    void theEarlySealReportNamesTheCauseAndWhatWasKept() {
        Path take = Path.of("project", "audio", "takes", "2026-09-30T10-00-00_take-0001");

        assertThat(TransportController.takeSealedEarlyMessage(DISK_EXHAUSTED, 2, take))
                .isEqualTo("Recording stopped — free disk space fell below 64 MiB;"
                        + " the audio recorded before that is kept (2 clips created)");
        assertThat(TransportController.takeSealedEarlyMessage(
                new EarlySeal.DiskExhausted(DiskHeadroomWatch.DEFAULT_FLOOR_BYTES, true, true), 1, take))
                .isEqualTo("Recording stopped — the free disk space could not be read;"
                        + " the audio recorded before that is kept (1 clip created)");
        assertThat(TransportController.takeSealedEarlyMessage(new EarlySeal.DiskExhausted(1000, false, true), 0, take))
                .isEqualTo("Recording stopped — free disk space fell below 1000 bytes;"
                        + " no audio had been recorded before that");
        assertThat(TransportController.takeSealedEarlyMessage(
                new EarlySeal.WriteFailed(new IllegalStateException(), false), 1, take))
                .as("files left unfinished are never said to hold audio, nor called kept, saved or sealed")
                .isEqualTo("Recording stopped — capturing the take failed (IllegalStateException);"
                        + " one or more segment files that could not be finished are left under"
                        + " audio/takes/2026-09-30T10-00-00_take-0001 (1 clip created)")
                .doesNotContain("kept", "saved", "sealed");
        assertThat(TransportController.takeSealedEarlyMessage(
                new EarlySeal.DiskExhausted(DiskHeadroomWatch.DEFAULT_FLOOR_BYTES, false, false), 0, take))
                .as("with no clip, the files left unfinished follow the words that no audio had been recorded")
                .isEqualTo("Recording stopped — free disk space fell below 64 MiB;"
                        + " no audio had been recorded before that, and one or more segment files that could"
                        + " not be finished are left under audio/takes/2026-09-30T10-00-00_take-0001");
    }

    /**
     * A write failure is reported by the reason its innermost
     * {@link IOException} gives — its message, a {@link FileSystemException}'s
     * reason, or its type when it gives neither — never by the message of an
     * exception that wraps it.
     */
    @Test
    void aWriteFailureIsReportedByTheReasonOfItsInnermostIOException() {
        Path take = Path.of("project", "audio", "takes", "2026-09-30T10-00-00_take-0001");
        String part = "C:\\project\\audio\\takes\\2026-09-30T10-00-00_take-0001\\t1\\segment-000.wav.part";

        assertThat(TransportController.takeSealedEarlyMessage(new EarlySeal.WriteFailed(
                new UncheckedIOException("write failed on " + part,
                        new IOException("There is not enough space on the disk")), true), 1, take))
                .isEqualTo("Recording stopped — writing the take to disk failed"
                        + " (There is not enough space on the disk); the audio recorded before that is kept"
                        + " (1 clip created)")
                .doesNotContain("write failed on", "UncheckedIOException");
        assertThat(TransportController.takeSealedEarlyMessage(new EarlySeal.WriteFailed(
                new UncheckedIOException("cannot seal segment " + part,
                        new IOException("rename failed",
                                new FileSystemException(part, null, "The device is not ready"))), false), 1, take))
                .as("the innermost IOException, a FileSystemException with a reason")
                .isEqualTo("Recording stopped — writing the take to disk failed (The device is not ready);"
                        + " one or more segment files that could not be finished are left under"
                        + " audio/takes/2026-09-30T10-00-00_take-0001 (1 clip created)")
                .doesNotContain("cannot seal segment", "rename failed");
        assertThat(TransportController.takeSealedEarlyMessage(new EarlySeal.WriteFailed(
                new UncheckedIOException("force failed on " + part, new FileSystemException(part)), true), 2, take))
                .as("a FileSystemException with no reason: its message is the path alone")
                .isEqualTo("Recording stopped — writing the take to disk failed (FileSystemException);"
                        + " the audio recorded before that is kept (2 clips created)")
                .doesNotContain("segment-000.wav.part");
        assertThat(TransportController.takeSealedEarlyMessage(new EarlySeal.WriteFailed(
                new IllegalStateException("not streaming", new IOException("The handle is invalid")), true), 1, take))
                .as("any throwable with an IOException in its cause chain is a write failure")
                .isEqualTo("Recording stopped — writing the take to disk failed (The handle is invalid);"
                        + " the audio recorded before that is kept (1 clip created)");
    }

    /**
     * A throwable with no {@link IOException} in its cause chain — an
     * {@link OutOfMemoryError} on the capture-flush thread, say — is
     * reported as a failed capture, by its type and message, and the disk is
     * not blamed for it.
     */
    @Test
    void aFailureThatIsNotAnIOFailureIsNotBlamedOnTheDisk() {
        Path take = Path.of("project", "audio", "takes", "2026-09-30T10-00-00_take-0001");

        assertThat(TransportController.takeSealedEarlyMessage(
                new EarlySeal.WriteFailed(new OutOfMemoryError("Java heap space"), true), 1, take))
                .isEqualTo("Recording stopped — capturing the take failed (OutOfMemoryError: Java heap space);"
                        + " the audio recorded before that is kept (1 clip created)")
                .doesNotContain("disk");
        assertThat(TransportController.takeSealedEarlyMessage(
                new EarlySeal.WriteFailed(new OutOfMemoryError(), true), 0, take))
                .isEqualTo("Recording stopped — capturing the take failed (OutOfMemoryError);"
                        + " no audio had been recorded before that");
    }

    // ── Story 323 review: a published take marks the project dirty ──────────

    /**
     * A take that published no clip — the core builds none for a take that
     * recorded no frame, and a real take's first block arrives at the
     * device's pace, so its completion is made to hand back none
     * ({@link CountingCompletion#firstReturnsNothing}) — leaves the project as
     * it was, and the status bar's finishing text is taken back; the next
     * take, which publishes one, marks it dirty, as an undoable edit does
     * (story 294: {@code DawProject} holds the one dirty bit).
     */
    @Test
    void anAudioTakeMarksTheProjectDirtyOnlyWhenItPublishedAClip() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        completions.firstReturnsNothing = true;
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions);
        project.markClean();

        stopAndAwaitTheTake(controller);

        assertThat(completions.calls).as("fixture: the take was completed").hasSize(1);
        assertThat(armed.getClips()).as("fixture: the take published no clip").isEmpty();
        assertThat(undoManager.canUndo()).isFalse();
        assertThat(project.isDirty()).as("a take that published no clip leaves the project clean").isFalse();
        assertThat(statusBarLabel.getText()).as("the finishing text is taken back")
                .isEqualTo(TransportController.TAKE_WRITTEN_WITHOUT_CLIPS_MESSAGE);

        record(controller);
        awaitABlockOnDisk(controller, armed);
        stopAndAwaitTheTake(controller);

        assertThat(completions.calls).as("fixture: the second take was completed").hasSize(2);
        assertThat(armed.getClips()).hasSize(1);
        assertThat(undoManager.undoDescription()).isEqualTo("Record Audio");
        assertThat(project.isDirty()).as("publishing the take's clip marks the project dirty").isTrue();
    }

    /**
     * A take still being written is not in the project and leaves it clean;
     * the turn that publishes it marks the project dirty.
     */
    @Test
    void aTakeStillBeingWrittenMarksTheProjectDirtyOnlyWhenItIsPublished() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        TransportController controller = recordingWithABlockOnDisk(project, armed, new CountingCompletion());
        project.markClean();
        holdTheCaptureThread();

        assertThat(runHandlerCatching(controller::stop)).isNull();
        assertThat(controller.isTakeBeingWritten()).as("fixture: the take is being written").isTrue();
        assertThat(project.isDirty()).as("a take still being written is not in the project yet").isFalse();

        releaseAndAwaitThePublication(controller);

        assertThat(armed.getClips()).as("fixture: the take was published").hasSize(1);
        assertThat(project.isDirty()).as("publishing the take's clip marks the project dirty").isTrue();
    }

    /** The auto-Stop of a take the capture thread sealed early publishes its clip and marks the project dirty. */
    @Test
    void aTakeSealedEarlyAndStoppedOnItsOwnMarksTheProjectDirty() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        ControlledEarlySeal earlySeal = new ControlledEarlySeal();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions, earlySeal);
        project.markClean();

        earlySeal.sealEarlyOnTheCaptureThread(DISK_EXHAUSTED);
        flushFx(); // the auto-Stop's turn
        assertThat(controller.isRecordingInFlight()).as("fixture: the auto-Stop stopped the take").isFalse();
        awaitOnFx(() -> !controller.isTakeBeingWritten(), "the take was published");

        assertThat(completions.calls).as("fixture: the stopped take was completed").hasSize(1);
        assertThat(controller.activeTakeDirectory()).isEmpty();
        assertThat(armed.getClips()).hasSize(1);
        assertThat(notificationBar.getCurrentLevel()).as("fixture: the early seal was reported")
                .isEqualTo(NotificationLevel.ERROR);
        assertThat(project.isDirty()).as("publishing the take's clip marks the project dirty").isTrue();
    }

    /**
     * A MIDI take that recorded no note leaves the project as it was; one
     * that recorded a note — the recorder put it into the track's clip, and
     * the Stop registered it for undo — marks the project dirty.
     */
    @Test
    void aMidiTakeMarksTheProjectDirtyOnlyWhenItRecordedANote() throws Exception {
        DawProject project = new DawProject("unsaved", new AudioFormat(48000, 2, 16, 256));
        try (RecordingInFlightFixture recording = RecordingInFlightFixture.midiOnly(project)) {
            Track keys = project.getTracks().getFirst();
            project.markClean();

            recording.start();
            recording.stop();

            assertThat(keys.getMidiClip().isEmpty()).as("fixture: no note was recorded").isTrue();
            assertThat(project.isDirty()).as("a MIDI take with no note leaves the project clean").isFalse();

            recording.start();
            recording.holdANote();
            recording.stop();

            assertThat(keys.getMidiClip().size()).as("fixture: the held note was recorded").isEqualTo(1);
            assertThat(project.isDirty()).as("registering the recorded note marks the project dirty").isTrue();
        }
    }

    /**
     * The fallback of a project change that bypassed the guard: a retired
     * controller's publishing turn publishes nothing into the replaced
     * project, so it marks nothing dirty, and it reports nothing but where
     * the take's files are — not even a failure of the Stop's own seal.
     */
    @Test
    void aRetiredControllersPublishingTurnLeavesTheProjectCleanAndReportsNoSealFailure() throws Exception {
        DawProject project = new DawProject("Song D", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions);
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        runHandler(() -> controller.setStopSealOutcomeForTest(stopSealFailedWith(RENAME_FAILED)));
        holdTheCaptureThread();
        assertThat(runHandlerCatching(controller::stop)).isNull();
        project.markClean();
        runHandler(controller::retire);
        NotificationHistoryService shown = notificationsFromNowOn();

        releaseAndAwaitThePublication(controller); // the capture thread terminates

        assertThat(completions.calls).as("fixture: a retired controller does not complete the take").isEmpty();
        assertThat(armed.getClips()).isEmpty();
        assertThat(project.isDirty()).as("nothing was published into the replaced project").isFalse();
        assertThat(shown.getEntries()).as("one WARNING, and no report of the seal").singleElement()
                .satisfies(entry -> {
                    assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
                    assertThat(entry.message())
                            .isEqualTo(TransportController.takeOfAReplacedProjectMessage("Song D", takeDirectory));
                });
    }

    // ── Story 323 review: a Stop whose own seal failed ──────────────────────

    /** A lane's rename failed in the seal the Stop requested, and its segment is left as its {@code .part}. */
    private static final StopSealFailure RENAME_FAILED = new StopSealFailure(
            new UncheckedIOException("cannot seal segment segment-000.wav", new IOException("injected rename failure")),
            false);

    /** Stands in for the core's failed Stop seal, whose triggers are package-private to daw-core. */
    private static TransportController.StopSealOutcome stopSealFailedWith(StopSealFailure failure) {
        return _ -> Optional.of(failure);
    }

    /**
     * A clean Stop still ends in one SUCCESS: the core reports no failure of
     * its seal, and the controller shows the clips created.
     */
    @Test
    void aStopWhoseSealSucceededStillShowsOneSuccess() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions);
        awaitSessionInputCheck(controller);
        NotificationHistoryService shown = notificationsFromNowOn();

        stopAndAwaitTheTake(controller);

        assertThat(completions.calls.getFirst().stopSealFailure()).as("the core reports no failure of the Stop's seal")
                .isEmpty();
        assertThat(shown.getEntries()).extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.SUCCESS, "Recording stopped — 1 clip created"));
        assertThat(statusBarLabel.getText()).isEqualTo("Recording stopped — 1 clip created");
    }

    /**
     * A lane's seal failed in the seal the user's Stop requested: the clip
     * and the undo entry are published, and the failure is reported once,
     * as the ERROR toast and the status text, never as the SUCCESS.
     */
    @Test
    void aStopWhoseSealFailedIsReportedOnceAsAnErrorWithItsClips() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        TransportController controller = recordingWithABlockOnDisk(project, armed, new CountingCompletion());
        awaitSessionInputCheck(controller);
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        runHandler(() -> controller.setStopSealOutcomeForTest(stopSealFailedWith(RENAME_FAILED)));
        project.markClean();
        NotificationHistoryService shown = notificationsFromNowOn();

        stopAndAwaitTheTake(controller);

        String expected = TransportController.stopSealFailedMessage(RENAME_FAILED, 1, takeDirectory);
        assertThat(expected).isEqualTo("Recording stopped — finishing the take on disk failed (injected rename"
                + " failure); one or more segment files that could not be finished are left under audio/takes/"
                + takeDirectory.getFileName() + " (1 clip created)");
        assertThat(shown.getEntries()).as("one ERROR, and no SUCCESS").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo(expected);
        });
        assertThat(notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.ERROR);
        assertThat(notificationBar.getMessage()).isEqualTo(expected);
        assertThat(statusBarLabel.getText()).isEqualTo(expected);
        assertThat(armed.getClips()).hasSize(1);
        assertThat(undoManager.undoDescription()).isEqualTo("Record Audio");
        assertThat(project.isDirty())
                .as("a take whose Stop seal failed still published its clips: an unsaved change").isTrue();
    }

    /** The same for a take whose Stop created no clip: the failure is still reported, once. */
    @Test
    void aStopWhoseSealFailedWithoutAClipIsReportedOnceAsAnError() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        completions.firstReturnsNothing = true;
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions);
        awaitSessionInputCheck(controller);
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        runHandler(() -> controller.setStopSealOutcomeForTest(stopSealFailedWith(RENAME_FAILED)));
        NotificationHistoryService shown = notificationsFromNowOn();

        stopAndAwaitTheTake(controller);

        String expected = TransportController.stopSealFailedMessage(RENAME_FAILED, 0, takeDirectory);
        assertThat(expected).isEqualTo("Recording stopped — finishing the take on disk failed (injected rename"
                + " failure); no audio had been recorded, and one or more segment files that could not be finished"
                + " are left under audio/takes/" + takeDirectory.getFileName());
        assertThat(shown.getEntries()).as("one ERROR, and no SUCCESS").singleElement().satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).isEqualTo(expected);
        });
        assertThat(notificationBar.getMessage()).isEqualTo(expected);
        assertThat(statusBarLabel.getText()).isEqualTo(expected);
        assertThat(armed.getClips()).isEmpty();
    }

    /**
     * A take still being written when the still-writing warning is shown
     * reports a failure of its Stop's seal the same way when it is
     * published: after the WARNING, one ERROR, and no SUCCESS.
     */
    @Test
    void aTakeStillBeingWrittenWhoseSealFailedIsReportedOnceAsAnError() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        TransportController controller = recordingWithABlockOnDisk(project, armed, new CountingCompletion());
        awaitSessionInputCheck(controller);
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        runHandler(() -> controller.setStopSealOutcomeForTest(stopSealFailedWith(RENAME_FAILED)));
        project.markClean();
        NotificationHistoryService shown = notificationsFromNowOn();
        holdTheCaptureThread();
        assertThat(runHandlerCatching(controller::stop)).isNull();
        runHandler(stillWritingDelay::fireAll); // the delay has passed and the take is still being written

        releaseAndAwaitThePublication(controller);

        String expected = TransportController.stopSealFailedMessage(RENAME_FAILED, 1, takeDirectory);
        assertThat(shown.getEntries()).as("the still-writing WARNING, then one ERROR, and no SUCCESS")
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.WARNING, TransportController.TAKE_STILL_WRITING_MESSAGE),
                        tuple(NotificationLevel.ERROR, expected));
        assertThat(notificationBar.getMessage()).isEqualTo(expected);
        assertThat(statusBarLabel.getText()).isEqualTo(expected);
        assertThat(armed.getClips()).hasSize(1);
        assertThat(controller.isTakeBeingWritten()).isFalse();
        assertThat(project.isDirty())
                .as("a take whose Stop seal failed still published its clips: an unsaved change").isTrue();
    }

    /** The same for a take still being written that produced no clip: one ERROR, which the status text keeps. */
    @Test
    void aTakeStillBeingWrittenWhoseSealFailedWithoutAClipIsReportedOnceAsAnError() throws Exception {
        DawProject project = new DawProject("saved", new AudioFormat(48000, 2, 16, 256));
        giveTheProjectADirectory(project);
        Track armed = project.createAudioTrack("Vox");
        armed.setArmed(true);
        CountingCompletion completions = new CountingCompletion();
        completions.firstReturnsNothing = true;
        TransportController controller = recordingWithABlockOnDisk(project, armed, completions);
        awaitSessionInputCheck(controller);
        Path takeDirectory = controller.activeTakeDirectory().orElseThrow();
        runHandler(() -> controller.setStopSealOutcomeForTest(stopSealFailedWith(RENAME_FAILED)));
        NotificationHistoryService shown = notificationsFromNowOn();
        holdTheCaptureThread();
        assertThat(runHandlerCatching(controller::stop)).isNull();
        runHandler(stillWritingDelay::fireAll); // the delay has passed and the take is still being written

        releaseAndAwaitThePublication(controller);

        String expected = TransportController.stopSealFailedMessage(RENAME_FAILED, 0, takeDirectory);
        assertThat(shown.getEntries()).as("the still-writing WARNING, then one ERROR, and no SUCCESS")
                .extracting(NotificationEntry::level, NotificationEntry::message)
                .containsExactly(tuple(NotificationLevel.WARNING, TransportController.TAKE_STILL_WRITING_MESSAGE),
                        tuple(NotificationLevel.ERROR, expected));
        assertThat(statusBarLabel.getText()).as("the report, not that the take holds no audio")
                .isEqualTo(expected);
    }

    @Test
    void theStopSealFailureReportSaysWhatIsLeftWithAndWithoutClips() {
        Path take = Path.of("project", "audio", "takes", "2026-10-01T10-00-00_take-0001");
        StopSealFailure unfinished = new StopSealFailure(
                new UncheckedIOException("cannot seal segment segment-000.wav",
                        new IOException("There is not enough space on the disk")), false);
        StopSealFailure finishedAllTheSame = new StopSealFailure(new IllegalStateException("listener failed"), true);

        assertThat(TransportController.stopSealFailedMessage(unfinished, 2, take))
                .as("files left unfinished are never said to hold audio, nor called kept, saved or sealed")
                .isEqualTo("Recording stopped — finishing the take on disk failed (There is not enough space on the"
                        + " disk); one or more segment files that could not be finished are left under"
                        + " audio/takes/2026-10-01T10-00-00_take-0001 (2 clips created)")
                .doesNotContain("kept", "saved", "sealed", "capturing");
        assertThat(TransportController.stopSealFailedMessage(unfinished, 0, take))
                .as("with no clip, the files left unfinished follow the words that no audio had been recorded")
                .isEqualTo("Recording stopped — finishing the take on disk failed (There is not enough space on the"
                        + " disk); no audio had been recorded, and one or more segment files that could not be"
                        + " finished are left under audio/takes/2026-10-01T10-00-00_take-0001")
                .doesNotContain("kept", "saved", "sealed", "capturing");
        assertThat(TransportController.stopSealFailedMessage(finishedAllTheSame, 1, take))
                .as("every segment finished all the same: nothing is said to be left")
                .isEqualTo("Recording stopped — finishing the take failed (IllegalStateException: listener failed);"
                        + " the audio recorded is kept (1 clip created)")
                .doesNotContain("left under");
        assertThat(TransportController.stopSealFailedMessage(finishedAllTheSame, 0, take))
                .isEqualTo("Recording stopped — finishing the take failed (IllegalStateException: listener failed);"
                        + " no audio had been recorded");
    }

    /**
     * A failed Stop seal is blamed on the disk only when an {@link IOException}
     * is in its cause chain, and then by the reason the innermost one gives;
     * any other throwable — an {@link OutOfMemoryError}, say — by its type
     * and message, with no word of the disk.
     */
    @Test
    void aStopSealFailureIsBlamedOnTheDiskOnlyForAnIOFailure() {
        Path take = Path.of("project", "audio", "takes", "2026-10-01T10-00-00_take-0001");
        String part = "C:\\project\\audio\\takes\\2026-10-01T10-00-00_take-0001\\t1\\segment-000.wav.part";

        assertThat(TransportController.stopSealFailedMessage(new StopSealFailure(
                new UncheckedIOException("cannot seal segment " + part,
                        new IOException("rename failed",
                                new FileSystemException(part, null, "The device is not ready"))), false), 1, take))
                .as("the innermost IOException, a FileSystemException with a reason")
                .isEqualTo("Recording stopped — finishing the take on disk failed (The device is not ready);"
                        + " one or more segment files that could not be finished are left under"
                        + " audio/takes/2026-10-01T10-00-00_take-0001 (1 clip created)")
                .doesNotContain("cannot seal segment", "rename failed");
        assertThat(TransportController.stopSealFailedMessage(
                new StopSealFailure(new OutOfMemoryError("Java heap space"), false), 1, take))
                .isEqualTo("Recording stopped — finishing the take failed (OutOfMemoryError: Java heap space);"
                        + " one or more segment files that could not be finished are left under"
                        + " audio/takes/2026-10-01T10-00-00_take-0001 (1 clip created)")
                .doesNotContain("disk", "capturing");
        assertThat(TransportController.stopSealFailedMessage(
                new StopSealFailure(new OutOfMemoryError(), true), 2, take))
                .isEqualTo("Recording stopped — finishing the take failed (OutOfMemoryError);"
                        + " the audio recorded is kept (2 clips created)")
                .doesNotContain("disk");
    }

    /** Collects the WARNING records a logger publishes. */
    private static Handler warningCapture(List<LogRecord> warnings) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static List<Path> listing(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.sorted().toList();
        }
    }

    /** Runs a handler on the FX thread and returns the {@link RuntimeException} it threw, if any. */
    private static RuntimeException runHandlerCatching(Runnable handler) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                handler.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        if (thrown.get() instanceof Error e) {
            throw e;
        }
        return (RuntimeException) thrown.get();
    }

    /** Counts every device open (both overloads) — proof of whether the engine was touched. */
    private static final class OpenCountingBackend implements AudioBackend {
        private final MockAudioBackend delegate = new MockAudioBackend();
        private final AtomicInteger opens = new AtomicInteger();
        private List<String> lifecycle;
        private Runnable beforeClose = () -> { };

        @Override public String name() { return delegate.name(); }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public List<AudioDeviceInfo> listDevices() { return delegate.listDevices(); }
        @Override public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                                   int bufferFrames) {
            opens.incrementAndGet();
            if (lifecycle != null) lifecycle.add("stream-opened");
            delegate.open(device, format, bufferFrames);
        }
        @Override public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                                   int bufferFrames, CaptureRequirement capture) {
            opens.incrementAndGet();
            if (lifecycle != null) lifecycle.add("stream-opened");
            delegate.open(device, format, bufferFrames);
        }
        @Override public int openedInputChannels() { return delegate.openedInputChannels(); }
        @Override public Flow.Publisher<AudioBlock> inputBlocks() { return delegate.inputBlocks(); }
        @Override public void sink(AudioBlock block) { delegate.sink(block); }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() {
            if (delegate.isOpen()) {
                beforeClose.run();
                if (lifecycle != null) lifecycle.add("stream-closed");
            }
            delegate.close();
        }
    }

    private static void runStrictHandler(Runnable handler) throws Exception {
        RuntimeException failure = runHandlerCatching(handler);
        if (failure != null) throw failure;
    }

    /** Runs a handler method on the FX thread, propagating failures to the test caller. */
    private static void runHandler(Runnable handler) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                handler.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        if (thrown.get() instanceof Error e) throw e;
        if (thrown.get() instanceof RuntimeException failure) throw failure;
    }
}
