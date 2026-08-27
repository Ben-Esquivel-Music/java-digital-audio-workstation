package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.vm.command.StopTransportCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TogglePlayPauseCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleRecordCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportCommand;
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
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 — the adopted command path end-to-end against the PRODUCTION
 * handler: story-290 {@link TransportCommand}s executed against a real
 * {@link TransportController} (the way {@code MainController.dispatchTransportCommand}
 * routes every toolbar / keyboard / Performance-Stage gesture), including the
 * play/pause mapping and the two-stage Stop semantics with truthful bus
 * announcements.
 *
 * <p>{@code MainController} is never FXML-loaded in tests, so this substitutes
 * its wiring in-process: every Play surface raises the same
 * {@link TogglePlayPauseCommand} into {@code dispatchTransportCommand} and the
 * handler resolves it (story 315 review retired
 * {@code MainController.playOrPauseCommand()}, which duplicated the decision) —
 * the literal FXML-loaded path remains a known coverage gap.</p>
 *
 * <p>Bus announcements are asserted only after {@link #awaitBusDrained()}: the
 * bus delivers on its own worker thread, so without it both "exactly one" and
 * "none" would be racing the drain (story 316 review build failure; see the
 * helper).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TransportCommandPathTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final long TIMEOUT_SECONDS = 5;

    private DefaultEventBus bus;
    private AudioEngine audioEngine;

    /** The marker {@link #awaitBusDrained()} is currently waiting on, if any. */
    private final AtomicReference<DrainMarker> drainMarker = new AtomicReference<>();

    private record DrainMarker(TransportEvent.Seeked event, CountDownLatch delivered) { }

    @BeforeEach
    void installBus() {
        bus = new DefaultEventBus();
        EventBusPublisher.setDefault(bus);
    }

    @AfterEach
    void clearBus() {
        if (audioEngine != null) {
            audioEngine.stopAudioOutput();
            audioEngine.stop();
        }
        EventBusPublisher.setDefault(null);
        bus.close();
    }

    @Test
    void playPauseMappingTogglesAndStopStopsAtTheAnchorThenZero() throws Exception {
        DawProject project = new DawProject("command-path", FORMAT);
        Transport transport = project.getTransport();
        transport.setTempo(120.0);
        transport.setPositionInBeats(20.0);
        TransportController handler = newController(project);

        List<TransportEvent.Stopped> stopped = new CopyOnWriteArrayList<>();
        try (EventBus.Subscription sub = collectAnnouncements(TransportEvent.Stopped.class, stopped)) {

            // Play → Start (anchors at beat 20). The SAME command each time —
            // the production handler reads the authoritative state.
            dispatch(new TogglePlayPauseCommand(), handler);
            assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);

            // Play again → Pause.
            dispatch(new TogglePlayPauseCommand(), handler);
            assertThat(transport.getState()).isEqualTo(TransportState.PAUSED);

            // Play once more → Start (resume re-anchors at the paused position).
            dispatch(new TogglePlayPauseCommand(), handler);
            assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);

            // The engine clock rolls on to beat 24…
            transport.advancePosition(4.0);
            assertThat(transport.getPositionInBeats()).isEqualTo(24.0);

            // …Stop returns to the play-start anchor and announces the
            // position the transport ACTUALLY stopped at (beat 24, captured
            // before the rewind — 24 beats @ 120 BPM @ 48 kHz = 576000 frames).
            dispatch(new StopTransportCommand(), handler);
            assertThat(transport.getState()).isEqualTo(TransportState.STOPPED);
            assertThat(transport.getPositionInBeats())
                    .as("Stop returns the playhead to the play-start anchor").isEqualTo(20.0);
            awaitBusDrained();
            assertThat(stopped)
                    .as("exactly one Stopped is announced, carrying the pre-rewind position")
                    .hasSize(1);
            assertThat(stopped.get(0).positionFrames()).isEqualTo(576_000L);

            // A second Stop is the gesture-level rewind to zero — the playhead
            // moves, but the transport did not stop again, so NOTHING is announced.
            dispatch(new StopTransportCommand(), handler);
            assertThat(transport.getPositionInBeats())
                    .as("double-stop rewinds to zero").isEqualTo(0.0);
            awaitBusDrained();
            assertThat(stopped)
                    .as("the rewind gesture announces no second Stopped").hasSize(1);
        }
    }

    @Test
    void playWithPreRollAnnouncesStartedAtThePostRewindPosition() throws Exception {
        // Story 315 review — this was the one start path that announced
        // nothing, so a consumer pairing Started with Stopped saw an unmatched
        // Stopped whenever the user started with pre-roll (Shift+Space).
        DawProject project = new DawProject("pre-roll-announce", FORMAT);
        Transport transport = project.getTransport();
        transport.setTempo(120.0);
        transport.setPositionInBeats(20.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));
        TransportController handler = newController(project);

        List<TransportEvent.Started> started = new CopyOnWriteArrayList<>();
        try (EventBus.Subscription sub = collectAnnouncements(TransportEvent.Started.class, started)) {

            computeOnFx(() -> { handler.playWithPreRoll(); return null; });

            assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
            assertThat(transport.getPositionInBeats())
                    .as("2 bars of 4/4 pre-roll rewinds beat 20 to beat 12").isEqualTo(12.0);
            awaitBusDrained();
            assertThat(started)
                    .as("starting with pre-roll announces Started like every other start path")
                    .hasSize(1);
            // 12 beats @ 120 BPM = 6 s @ 48 kHz = 288000 frames — the POST-rewind
            // position, i.e. where playback actually begins.
            assertThat(started.get(0).positionFrames()).isEqualTo(288_000L);
        }
    }

    @Test
    void aFailedCaptureOpenAbortsTheWholeTakeInsteadOfClaimingToRecord() throws Exception {
        // Story 316 review, the app half of the High finding. onRecord() used
        // to call RecordingPipeline.start() FIRST — which installs the engine
        // recording callback, starts the engine and moves the transport to
        // RECORDING — and only then open the capture device, inside a catch
        // that logged, toasted and FELL THROUGH. After a device failure the
        // transport therefore read RECORDING, Started went on the bus, the
        // status bar read "Recording — N tracks armed — auto-save active" and
        // the REC indicator was lit, while nothing whatsoever could be
        // captured.
        //
        // The engine here has no streaming provision, which is one of the
        // cases the same review made startAudioInputOutput() throw on: it
        // walks the ladder with CaptureRequirement.REQUIRED rather than
        // treating "no capture device at all" as a silent success the way the
        // playback open legitimately may.
        //
        // A MIDI track is armed alongside the audio one on purpose: the abort
        // is deliberately WHOLE. Continuing with the MIDI half would hand back
        // a take silently missing the audio tracks the user armed, which is
        // the same dishonesty in a form that is harder to notice.
        DawProject project = new DawProject("record-abort", FORMAT);
        Transport transport = project.getTransport();
        transport.setPositionInBeats(5.0);
        Track armedAudio = new Track("Gtr", TrackType.AUDIO);
        armedAudio.setArmed(true);
        project.addTrack(armedAudio);
        Track armedMidi = new Track("Keys", TrackType.MIDI);
        armedMidi.setArmed(true);
        project.addTrack(armedMidi);
        TransportController handler = newUnprovisionedController(project);

        List<TransportEvent.Started> started = new CopyOnWriteArrayList<>();
        try (EventBus.Subscription sub = collectAnnouncements(TransportEvent.Started.class, started)) {

            dispatch(new ToggleRecordCommand(), handler);

            assertThat(transport.getState())
                    .as("a take that cannot capture never moves the transport")
                    .isEqualTo(TransportState.STOPPED);
            awaitBusDrained();
            assertThat(started)
                    .as("and never announces Started — the bus must not carry a "
                            + "take that did not begin")
                    .isEmpty();
            // RecordingPipeline.start() is the only thing that sets active =
            // true, and it flags every armed track as recording on its way
            // through; an unflagged track is therefore proof the pipeline was
            // not started and is not left active for stop() to finalize.
            assertThat(armedAudio.isRecording())
                    .as("no pipeline was started, so nothing is left active")
                    .isFalse();
            assertThat(armedMidi.isRecording())
                    .as("the MIDI half of the take is abandoned with the audio half")
                    .isFalse();
            assertThat(recIndicator.isVisible())
                    .as("the REC indicator is not lit over a take that never started")
                    .isFalse();
            assertThat(statusBarLabel.getText())
                    .as("the user is told the take was ABORTED, and why — not that "
                            + "recording started")
                    .contains("aborted")
                    .doesNotContain("Recording — ")
                    .contains("no audio backend is configured");
        }

        // The pipeline reference was cleared too, not merely never started: a
        // Stop now takes the double-stop rewind gesture, which stop() gates on
        // isRecordingInFlight() being false.
        dispatch(new StopTransportCommand(), handler);
        assertThat(transport.getPositionInBeats())
                .as("nothing is in flight, so Stop is the plain rewind gesture")
                .isEqualTo(0.0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Mirrors {@code MainController.dispatchTransportCommand} on the FX thread. */
    private static void dispatch(TransportCommand command, TransportController handler)
            throws Exception {
        computeOnFx(() -> { command.execute(handler); return null; });
    }

    /**
     * ONE subscription per test, on the common super-type, that collects the
     * announcements of {@code type} into {@code into} and recognises the
     * {@link #awaitBusDrained()} marker by identity. Subscribing to the
     * super-type is what puts the marker and the announcements under test on
     * the SAME per-subscription FIFO &mdash; the ordering guarantee the helper
     * relies on.
     */
    private <E extends TransportEvent> EventBus.Subscription collectAnnouncements(
            Class<E> type, List<E> into) {
        return bus.on(TransportEvent.class, DispatchMode.ON_CALLER_THREAD, event -> {
            DrainMarker marker = drainMarker.get();
            if (marker != null && event == marker.event()) {
                marker.delivered().countDown();
            } else if (type.isInstance(event)) {
                into.add(type.cast(event));
            }
        });
    }

    /**
     * Blocks until every event enqueued on this test's subscription before
     * now has been delivered to its collector.
     *
     * <p>Why this exists &mdash; do not "simplify" it away.
     * {@link DefaultEventBus#publish} only ENQUEUES onto each subscription's
     * own bounded FIFO ({@code Sub.tryEnqueue}); a per-subscription serial
     * worker drains it ({@code runLoop}), and
     * {@link DispatchMode#ON_CALLER_THREAD} means "run the handler inline on
     * that drain worker", not on the publishing thread. So the
     * {@code Stopped} that {@code TransportController.stop()} publishes
     * synchronously is NOT yet in the collecting list when the FX-thread
     * dispatch returns. Under full-reactor load (the story 316 review build)
     * the worker had not run and "exactly one Stopped" observed zero. The
     * negative assertions were worse off: "no second Stopped" and "never
     * announces Started" could only ever pass, because an event not yet
     * delivered is indistinguishable from one never published.</p>
     *
     * <p>The fix is FIFO ordering on a single subscription. This publishes a
     * marker {@link TransportEvent.Seeked} (nothing in production publishes
     * {@code Seeked}, and the collector matches it by identity regardless) to
     * the SAME bus and waits for it. One deque, drained in order by one
     * worker, means the marker's delivery proves that everything enqueued on
     * this subscription before it &mdash; including any Stopped/Started
     * published inside the gesture under test &mdash; has already been
     * delivered. Both the positive and the negative assertions become
     * deterministic; no sleep, no polling. A fresh marker and latch per call,
     * since the first test drains twice.</p>
     */
    private void awaitBusDrained() throws InterruptedException {
        DrainMarker marker = new DrainMarker(
                new TransportEvent.Seeked(0L, 0L, Instant.now()), new CountDownLatch(1));
        drainMarker.set(marker);
        bus.publish(marker.event());
        assertThat(marker.delivered().await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the bus delivered the drain marker, and with it everything "
                        + "enqueued before it")
                .isTrue();
    }

    /** The status-bar label handed to the controller, for message assertions. */
    private Label statusBarLabel;
    /** The REC indicator handed to the controller, for recording-lifecycle assertions. */
    private Label recIndicator;

    private TransportController newController(DawProject project) throws Exception {
        return computeOnFx(() -> {
            MockAudioBackend backend = new MockAudioBackend();
            AudioEngine engine = new AudioEngine(project.getFormat());
            engine.setStreamingProvision(new StreamingProvision(
                    backend.name(), List.of(new BackendStreamRung(
                            backend, DeviceId.defaultFor(backend.name())))));
            return createController(project, engine);
        });
    }

    private TransportController newUnprovisionedController(DawProject project) throws Exception {
        return computeOnFx(() -> createController(
                project, new AudioEngine(project.getFormat())));
    }

    private TransportController createController(DawProject project, AudioEngine engine) {
        audioEngine = engine;
        statusBarLabel = new Label();
        recIndicator = new Label();
        recIndicator.setVisible(false);
        recIndicator.setManaged(false);
        return new TransportController(
                project, engine, new UndoManager(),
                new NotificationBar(), new Label(), statusBarLabel, recIndicator,
                new Button(), new Button(),
                () -> false,
                () -> GridResolution.QUARTER,
                () -> CountInMode.OFF,
                track -> { },
                () -> true,
                () -> com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN);
    }

    /** Runs {@code work} on the FX thread with capture-and-rethrow of assertion errors. */
    private static <T> T computeOnFx(Callable<T> work) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        switch (thrown.get()) {
            case null -> { }
            case RuntimeException re -> throw re;
            case Error e -> throw e;
            case Throwable t -> throw new AssertionError("FX work threw", t);
        }
        return result.get();
    }
}
