package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Story 290 — verifies the transport command cascade end-to-end: a
 * {@link SetTempoCommand} runs the mutation through {@link CoreTransportIntentHandler}
 * (the MUTATE phase) and the existing {@link TransportEvent.TempoChanged} reaches a
 * subscriber via the bus (the ANNOUNCE phase) — Control Synchronization Design
 * Book §5.1, §5.2.
 *
 * <p>The typed event is asserted via its <em>payload</em> captured by a bus
 * subscriber (the design-book analogue of "a parent {@code addEventFilter} on the
 * payload"), never via source identity and never via rendered output — so this
 * test needs no JavaFX toolkit.</p>
 */
class TransportCommandEventTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final double SAMPLE_RATE = 48_000.0;

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
    void setTempoCommandMutatesTransportAndAnnouncesTempoChangedOnTheBus() throws InterruptedException {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<TransportEvent.TempoChanged> captured = new AtomicReference<>();
        try (EventBus.Subscription sub = bus.on(TransportEvent.TempoChanged.class,
                DispatchMode.ON_CALLER_THREAD, event -> {
                    captured.set(event);
                    delivered.countDown();
                })) {

            // A control raises the intent; the handler runs MUTATE then ANNOUNCE.
            new SetTempoCommand(140.0).execute(handler);

            assertThat(transport.getTempo())
                    .as("MUTATE: the command ran the tempo mutation").isEqualTo(140.0);
            assertThat(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("ANNOUNCE: TempoChanged reaches the subscriber via the bus").isTrue();
            assertThat(captured.get().previousBpm()).isEqualTo(120.0);
            assertThat(captured.get().newBpm()).isEqualTo(140.0);
        }
    }

    @Test
    void startCommandMutatesAndAnnouncesStarted() throws InterruptedException {
        Transport transport = new Transport();
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        CountDownLatch delivered = new CountDownLatch(1);
        try (EventBus.Subscription sub = bus.on(TransportEvent.Started.class,
                DispatchMode.ON_CALLER_THREAD, event -> delivered.countDown())) {

            new StartTransportCommand().execute(handler);

            assertThat(transport.getState()).isEqualTo(TransportState.PLAYING);
            assertThat(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void startCommandIsANoOpWhileRecordingAndNeverDropsOutOfRecordOrAnnounces() {
        Transport transport = new Transport();
        transport.record();
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        AtomicReference<TransportEvent.Started> announced = new AtomicReference<>();
        try (EventBus.Subscription sub = bus.on(TransportEvent.Started.class,
                DispatchMode.ON_CALLER_THREAD, announced::set)) {

            new StartTransportCommand().execute(handler);

            assertThat(transport.getState())
                    .as("Start is a no-op while RECORDING — Stop is the only way out of record")
                    .isEqualTo(TransportState.RECORDING);
            assertThat(announced.get())
                    .as("no Started is announced when Start is a no-op").isNull();
        }
    }

    @Test
    void pauseCommandPausesAPlayingTransportAndAnnouncesNothing() {
        Transport transport = new Transport();
        transport.play();
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        AtomicReference<TransportEvent> any = new AtomicReference<>();
        try (EventBus.Subscription sub = bus.on(TransportEvent.class,
                DispatchMode.ON_CALLER_THREAD, any::set)) {

            new PauseTransportCommand().execute(handler);

            assertThat(transport.getState())
                    .as("MUTATE: the command paused the transport")
                    .isEqualTo(TransportState.PAUSED);
            assertThat(any.get())
                    .as("ANNOUNCE is deliberately skipped — the story-283 TransportEvent "
                            + "vocabulary has no Paused event and §5.1 permits skipping a phase")
                    .isNull();
        }
    }

    @Test
    void pauseCommandIsANoOpWhileStopped() {
        Transport transport = new Transport();
        transport.setPositionInBeats(7.0);
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        new PauseTransportCommand().execute(handler);

        assertThat(transport.getState())
                .as("VALIDATE: a stale Pause intent (only PLAYING/RECORDING may pause) is absorbed")
                .isEqualTo(TransportState.STOPPED);
        assertThat(transport.getPositionInBeats()).isEqualTo(7.0);
    }

    @Test
    void togglePlayPauseCommandFlipsPlayingAndPausedFromTheAuthoritativeState() {
        // Story 315 review — the handler owns the decision, so the SAME command
        // starts an idle transport and pauses a rolling one. Nothing about the
        // command carries the choice.
        Transport transport = new Transport();
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        new TogglePlayPauseCommand().execute(handler);
        assertThat(transport.getState())
                .as("a toggle against a STOPPED transport starts it")
                .isEqualTo(TransportState.PLAYING);

        new TogglePlayPauseCommand().execute(handler);
        assertThat(transport.getState())
                .as("a toggle against a PLAYING transport pauses it")
                .isEqualTo(TransportState.PAUSED);

        new TogglePlayPauseCommand().execute(handler);
        assertThat(transport.getState())
                .as("a toggle against a PAUSED transport resumes it")
                .isEqualTo(TransportState.PLAYING);
    }

    @Test
    void togglePlayPauseCommandIsANoOpWhileRecording() {
        // Stop is the only way out of record: the toggle maps RECORDING to
        // start, whose VALIDATE rejects it — the retired playOrPauseCommand()
        // mapping preserved exactly.
        Transport transport = new Transport();
        transport.record();
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        AtomicReference<TransportEvent> any = new AtomicReference<>();
        try (EventBus.Subscription sub = bus.on(TransportEvent.class,
                DispatchMode.ON_CALLER_THREAD, any::set)) {

            new TogglePlayPauseCommand().execute(handler);

            assertThat(transport.getState()).isEqualTo(TransportState.RECORDING);
            assertThat(any.get())
                    .as("a dropped toggle announces nothing").isNull();
        }
    }

    @Test
    void skipAndPreRollCommandsRouteToTheirHandlerMethods() {
        // Story 315 review follow-up — the last transport gestures adopted the
        // command path. A command is a thin router (§2.8): each new record must
        // reach exactly its own handler method, nothing else.
        RecordingHandler handler = new RecordingHandler();

        new SkipBackCommand().execute(handler);
        new SkipForwardCommand().execute(handler);
        new PlayWithPreRollCommand().execute(handler);

        assertThat(handler.calls)
                .containsExactly("skipBack", "skipForward", "playWithPreRoll");
    }

    @Test
    void skipBackCommandSeeksToZeroAndAnnouncesNothing() {
        Transport transport = new Transport();
        transport.setPositionInBeats(9.0);
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        AtomicReference<TransportEvent> any = new AtomicReference<>();
        try (EventBus.Subscription sub = bus.on(TransportEvent.class,
                DispatchMode.ON_CALLER_THREAD, any::set)) {

            new SkipBackCommand().execute(handler);

            assertThat(transport.getPositionInBeats())
                    .as("MUTATE: an absolute seek to the beginning").isEqualTo(0.0);
            assertThat(any.get())
                    .as("ANNOUNCE is deliberately skipped — TransportEvent.Seeked has no "
                            + "production publisher and §5.1 permits skipping a phase")
                    .isNull();
        }
    }

    @Test
    void twoSkipForwardCommandsWhileTheRealTimeClockIsClaimedAccumulate() {
        // Story 315 review — a relative seek composes against the PENDING seek
        // target, never the committed position. While the transport is rolling
        // AND the RT clock is claimed, seeks sit in a single-slot,
        // last-writer-wins queue until the next block boundary; only reading
        // getSeekTargetInBeats() lets successive skips accumulate.
        Transport transport = new Transport();
        transport.play();
        transport.setRealTimeClockActive(true); // an audio callback owns the clock
        double jump = 4.0 * transport.getTimeSignatureNumerator(); // 4 bars of 4/4 = 16 beats
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        // A seek already sits queued (rolling + claimed → deferred)…
        transport.setPositionInBeats(8.0);
        assertThat(transport.getPositionInBeats())
                .as("the seek is queued, not applied — the committed position holds")
                .isEqualTo(0.0);

        // …and two Skip Forwards inside the same block stack on top of it.
        new SkipForwardCommand().execute(handler);
        new SkipForwardCommand().execute(handler);

        assertThat(transport.getSeekTargetInBeats())
                .as("each skip composes against the pending target — two jumps accumulate")
                .isEqualTo(8.0 + 2.0 * jump);

        // The block boundary applies the queued target.
        transport.advancePosition(0.0);
        assertThat(transport.getPositionInBeats())
                .as("the drained seek lands the queued base plus two jumps")
                .isEqualTo(8.0 + 2.0 * jump);
    }

    @Test
    void playWithPreRollCommandAnnouncesStartedAtThePostRewindPosition() throws InterruptedException {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        transport.setPositionInBeats(20.0);
        transport.setPreRollPostRoll(PreRollPostRoll.enabled(2, 0));
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<TransportEvent.Started> captured = new AtomicReference<>();
        try (EventBus.Subscription sub = bus.on(TransportEvent.Started.class,
                DispatchMode.ON_CALLER_THREAD, event -> {
                    captured.set(event);
                    delivered.countDown();
                })) {

            new PlayWithPreRollCommand().execute(handler);

            assertThat(transport.getState())
                    .as("MUTATE: playWithPreRoll always transitions to PLAYING")
                    .isEqualTo(TransportState.PLAYING);
            assertThat(transport.getPositionInBeats())
                    .as("2 bars of 4/4 pre-roll rewinds beat 20 to beat 12").isEqualTo(12.0);
            assertThat(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("ANNOUNCE: Started reaches the subscriber via the bus").isTrue();
            // 12 beats @ 120 BPM = 6 s @ 48 kHz = 288000 frames — the POST-rewind
            // position, i.e. where playback actually begins (parity with the
            // production TransportController path).
            assertThat(captured.get().positionFrames()).isEqualTo(288_000L);
        }
    }

    @Test
    void outOfRangeTempoIsRejectedInValidateAndNeverMutatesOrAnnounces() {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        TransportIntentHandler handler = new CoreTransportIntentHandler(transport, SAMPLE_RATE);

        AtomicReference<TransportEvent> any = new AtomicReference<>();
        try (EventBus.Subscription sub = bus.on(TransportEvent.class,
                DispatchMode.ON_CALLER_THREAD, any::set)) {

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new SetTempoCommand(5.0).execute(handler));

            assertThat(transport.getTempo())
                    .as("VALIDATE rejected the intent before MUTATE").isEqualTo(120.0);
            assertThat(any.get())
                    .as("no event is announced when validation fails").isNull();
        }
    }

    /**
     * Records which handler method each command routes to — the recording fake
     * the interface Javadoc promises ("Tests substitute a recording fake to
     * assert that a control gesture issues the right command without touching
     * the engine").
     */
    private static final class RecordingHandler implements TransportIntentHandler {
        final List<String> calls = new ArrayList<>();

        @Override public void start() { calls.add("start"); }
        @Override public void pause() { calls.add("pause"); }
        @Override public void togglePlayPause() { calls.add("togglePlayPause"); }
        @Override public void playWithPreRoll() { calls.add("playWithPreRoll"); }
        @Override public void stop() { calls.add("stop"); }
        @Override public void skipBack() { calls.add("skipBack"); }
        @Override public void skipForward() { calls.add("skipForward"); }
        @Override public void toggleRecord() { calls.add("toggleRecord"); }
        @Override public void setTempo(double bpm) { calls.add("setTempo(" + bpm + ")"); }
        @Override public void toggleLoop() { calls.add("toggleLoop"); }
    }
}
