package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.vm.command.StopTransportCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TogglePlayPauseCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TransportCommand;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TransportCommandPathTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);

    private DefaultEventBus bus;

    @BeforeEach
    void installBus() {
        bus = new DefaultEventBus();
        EventBusPublisher.setDefault(bus);
    }

    @AfterEach
    void clearBus() {
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
        try (EventBus.Subscription sub = bus.on(TransportEvent.Stopped.class,
                DispatchMode.ON_CALLER_THREAD, stopped::add)) {

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
            assertThat(stopped)
                    .as("exactly one Stopped is announced, carrying the pre-rewind position")
                    .hasSize(1);
            assertThat(stopped.get(0).positionFrames()).isEqualTo(576_000L);

            // A second Stop is the gesture-level rewind to zero — the playhead
            // moves, but the transport did not stop again, so NOTHING is announced.
            dispatch(new StopTransportCommand(), handler);
            assertThat(transport.getPositionInBeats())
                    .as("double-stop rewinds to zero").isEqualTo(0.0);
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
        try (EventBus.Subscription sub = bus.on(TransportEvent.Started.class,
                DispatchMode.ON_CALLER_THREAD, started::add)) {

            computeOnFx(() -> { handler.onPlayWithPreRoll(); return null; });

            assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
            assertThat(transport.getPositionInBeats())
                    .as("2 bars of 4/4 pre-roll rewinds beat 20 to beat 12").isEqualTo(12.0);
            assertThat(started)
                    .as("starting with pre-roll announces Started like every other start path")
                    .hasSize(1);
            // 12 beats @ 120 BPM = 6 s @ 48 kHz = 288000 frames — the POST-rewind
            // position, i.e. where playback actually begins.
            assertThat(started.get(0).positionFrames()).isEqualTo(288_000L);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Mirrors {@code MainController.dispatchTransportCommand} on the FX thread. */
    private static void dispatch(TransportCommand command, TransportController handler)
            throws Exception {
        computeOnFx(() -> { command.execute(handler); return null; });
    }

    private TransportController newController(DawProject project) throws Exception {
        return computeOnFx(() -> new TransportController(
                project, new AudioEngine(project.getFormat()), new UndoManager(),
                new NotificationBar(), new Label(), new Label(), new Label(),
                new Button(), new Button(),
                () -> false,
                () -> GridResolution.QUARTER,
                () -> CountInMode.OFF,
                track -> { },
                () -> true,
                () -> com.benesquivelmusic.daw.sdk.audio.RoundTripLatency.UNKNOWN));
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
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        switch (thrown.get()) {
            case null -> { }
            case RuntimeException re -> throw re;
            case Error e -> throw e;
            case Throwable t -> throw new AssertionError("FX work threw", t);
        }
        return result.get();
    }
}
