package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
    /** The REC indicator handed to the controller, for recording-lifecycle assertions. */
    private Label recIndicator;
    /** The status-bar label handed to the controller, for "UI tail untouched" assertions. */
    private Label statusBarLabel;
    /** Engine handed to the latest controller, for honest stream-state assertions and cleanup. */
    private AudioEngine audioEngine;
    /** Actionable notification surface handed to the latest controller. */
    private NotificationBar notificationBar;
    /** Counts invocations of the injected Open Audio Settings route. */
    private AtomicInteger audioSettingsOpens;

    @AfterEach
    void closeEngine() {
        if (audioEngine != null) {
            audioEngine.stopAudioOutput();
            audioEngine.stop();
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
            NotificationBar nb = new NotificationBar();
            nb.setAnimated(false);
            notificationBar = nb;
            audioSettingsOpens = new AtomicInteger();
            Label statusLabel = new Label();
            statusBarLabel = new Label();
            recIndicator = new Label();
            recIndicator.setVisible(false);
            recIndicator.setManaged(false);
            playButton = new Button();
            Button record = new Button();
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
                    audioSettingsOpens::incrementAndGet,
                    null));
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        return ref.get();
    }

    /** Streaming backend whose selected endpoint always refuses to open. */
    private static final class FailingAudioBackend implements AudioBackend {
        private final String name;
        private final String failureMessage;

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
            throw new AudioBackendException(failureMessage);
        }
        @Override public Flow.Publisher<AudioBlock> inputBlocks() { return _ -> { }; }
        @Override public void sink(AudioBlock block) { }
        @Override public boolean isOpen() { return false; }
        @Override public void close() { }
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
        // Story 315 review — RecordingPipeline.start() sets active = true at its
        // top and only calls transport.record() at the very end, so a throw in
        // between (session creation, temp-file I/O, engine start) leaves the
        // pipeline ACTIVE while the transport is still STOPPED, and onRecord()
        // does not catch it. This test reproduces exactly that end state — an
        // active pipeline over a stopped transport — and asserts Stop finalizes
        // rather than taking the double-stop rewind and leaking the recording
        // sessions, the temp files, the per-track recording flags and the lit
        // REC indicator forever.
        //
        // Story 316 review — the engine now needs a REAL capture-capable
        // provision to reach that state at all. onRecord() opens the device
        // BEFORE starting the pipeline and aborts the whole take when the open
        // fails, so on the bare provision-less engine the other tests use no
        // pipeline is created and there is nothing for Stop to finalize (that
        // abort is pinned in TransportCommandPathTest). MockAudioBackend
        // overrides openedInputChannels() honestly, so it survives the
        // CaptureRequirement.REQUIRED walk.
        DawProject project = new DawProject("test",
                new AudioFormat(48000, 2, 16, 256));
        Transport transport = project.getTransport();
        Track armed = new Track("Armed", TrackType.AUDIO);
        armed.setArmed(true);
        project.addTrack(armed);
        transport.setPositionInBeats(5.0);

        TransportController controller = newController(project, new MockAudioBackend());
        runHandler(controller::toggleRecord);   // pipeline active; transport RECORDING
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

    /** Runs a handler method on the FX thread, tolerating headless audio-engine failures. */
    private static void runHandler(Runnable handler) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                handler.run();
            } catch (RuntimeException e) {
                // Audio engine may fail to open in a headless environment; the
                // transport-state assertions are what matter.
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
    }
}
