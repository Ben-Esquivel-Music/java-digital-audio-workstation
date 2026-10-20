package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 328 (book §5.6) — the punch creation rules of the command cascade:
 * the three commands route to their handler methods, a set point wins over
 * the other point (which yields to keep at least {@code 2 × 5 ms} of region,
 * never a swap), a set with no region creates an ENABLED one, the toggle
 * flips only an existing region, frames are PROJECT-rate, and every mutation
 * announces {@link TransportEvent.PunchChanged} on the bus (the sibling of
 * the {@code LoopChanged} announce).
 */
class CoreTransportIntentHandlerPunchTest {

    private static final double PROJECT_RATE = 96_000.0;
    /** 2 × 5 ms at 96 kHz. */
    private static final long MIN_FRAMES = 960L;
    /** The PROJECT phase, unobserved here: {@code LoopAndPunchIntentsMarkTheProjectDirtyTest} asserts it. */
    private static final Runnable NO_DIRTY_OBSERVER = () -> { };

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
    void minimumPunchLengthIsTwoCrossfadesAtTheProjectRate() {
        assertThat(CoreTransportIntentHandler.minPunchFrames(PROJECT_RATE)).isEqualTo(MIN_FRAMES);
        assertThat(CoreTransportIntentHandler.minPunchFrames(48_000.0)).isEqualTo(480L);
        assertThat(new CoreTransportIntentHandler(new Transport(), PROJECT_RATE, NO_DIRTY_OBSERVER).minPunchFrames())
                .isEqualTo(MIN_FRAMES);
    }

    @Test
    void punchCommandsRouteToTheirHandlerMethods() {
        RecordingHandler handler = new RecordingHandler();

        new SetPunchInCommand().execute(handler);
        new SetPunchOutCommand().execute(handler);
        new TogglePunchCommand().execute(handler);

        assertThat(handler.calls).containsExactly("setPunchIn", "setPunchOut", "togglePunch");
    }

    @Test
    void setPunchInWithNoRegionCreatesAnEnabledRegionOfTheMinimumLengthAtProjectRateFrames() {
        Transport transport = transportAt120Bpm(4.0); // beat 4 @ 120 BPM = 2 s = 192 000 frames @ 96 kHz
        CoreTransportIntentHandler handler = new CoreTransportIntentHandler(transport, PROJECT_RATE, NO_DIRTY_OBSERVER);

        new SetPunchInCommand().execute(handler);

        assertThat(transport.getPunchRegion())
                .isEqualTo(new PunchRegion(192_000L, 192_000L + MIN_FRAMES, true));
    }

    @Test
    void setPunchOutWithNoRegionCreatesAnEnabledRegionEndingAtThePlayhead() {
        Transport transport = transportAt120Bpm(4.0);
        CoreTransportIntentHandler handler = new CoreTransportIntentHandler(transport, PROJECT_RATE, NO_DIRTY_OBSERVER);

        new SetPunchOutCommand().execute(handler);

        assertThat(transport.getPunchRegion())
                .isEqualTo(new PunchRegion(192_000L - MIN_FRAMES, 192_000L, true));
    }

    @Test
    void setPunchInBeforeExistingOutKeepsTheOutPointAndEnabledFlag() {
        Transport transport = transportAt120Bpm(4.0);
        transport.setPunchRegion(new PunchRegion(10_000L, 400_000L, false));
        CoreTransportIntentHandler handler = new CoreTransportIntentHandler(transport, PROJECT_RATE, NO_DIRTY_OBSERVER);

        new SetPunchInCommand().execute(handler);

        assertThat(transport.getPunchRegion())
                .as("the in-point moves to the playhead; out and the enabled flag are untouched")
                .isEqualTo(new PunchRegion(192_000L, 400_000L, false));
    }

    @Test
    void setPunchInAfterExistingOutMovesTheOutPointToInPlusMinimumNeverSwaps() {
        Transport transport = transportAt120Bpm(4.0);
        transport.setPunchRegion(PunchRegion.enabled(10_000L, 100_000L)); // out is before the playhead
        CoreTransportIntentHandler handler = new CoreTransportIntentHandler(transport, PROJECT_RATE, NO_DIRTY_OBSERVER);

        new SetPunchInCommand().execute(handler);

        assertThat(transport.getPunchRegion())
                .as("the point just set wins; the out-point yields to keep the minimum region")
                .isEqualTo(new PunchRegion(192_000L, 192_000L + MIN_FRAMES, true));
    }

    @Test
    void setPunchOutBeforeExistingInMovesTheInPointToOutMinusMinimumNeverSwaps() {
        Transport transport = transportAt120Bpm(4.0);
        transport.setPunchRegion(PunchRegion.enabled(300_000L, 400_000L)); // in is after the playhead
        CoreTransportIntentHandler handler = new CoreTransportIntentHandler(transport, PROJECT_RATE, NO_DIRTY_OBSERVER);

        new SetPunchOutCommand().execute(handler);

        assertThat(transport.getPunchRegion())
                .isEqualTo(new PunchRegion(192_000L - MIN_FRAMES, 192_000L, true));
    }

    @Test
    void setPunchOutAtTheProjectStartClampsToAMinimumRegionFromFrameZero() {
        Transport transport = transportAt120Bpm(0.0);
        CoreTransportIntentHandler handler = new CoreTransportIntentHandler(transport, PROJECT_RATE, NO_DIRTY_OBSERVER);

        new SetPunchOutCommand().execute(handler);

        assertThat(transport.getPunchRegion())
                .as("a region cannot start before the project does: the only case the set point moves")
                .isEqualTo(new PunchRegion(0L, MIN_FRAMES, true));
    }

    @Test
    void togglePunchFlipsTheEnabledFlagOfAnExistingRegionAndKeepsItsBounds() {
        Transport transport = transportAt120Bpm(4.0);
        transport.setPunchRegion(PunchRegion.enabled(1_000L, 50_000L));
        CoreTransportIntentHandler handler = new CoreTransportIntentHandler(transport, PROJECT_RATE, NO_DIRTY_OBSERVER);

        new TogglePunchCommand().execute(handler);
        assertThat(transport.getPunchRegion()).isEqualTo(new PunchRegion(1_000L, 50_000L, false));

        new TogglePunchCommand().execute(handler);
        assertThat(transport.getPunchRegion()).isEqualTo(new PunchRegion(1_000L, 50_000L, true));
    }

    @Test
    void togglePunchWithNoRegionNeitherMutatesNorAnnounces() {
        Transport transport = transportAt120Bpm(4.0);
        CoreTransportIntentHandler handler = new CoreTransportIntentHandler(transport, PROJECT_RATE, NO_DIRTY_OBSERVER);

        AtomicReference<TransportEvent> any = new AtomicReference<>();
        try (EventBus.Subscription sub = bus.on(TransportEvent.class,
                DispatchMode.ON_CALLER_THREAD, any::set)) {

            new TogglePunchCommand().execute(handler);

            assertThat(transport.getPunchRegion()).as("VALIDATE: nothing to toggle").isNull();
            assertThat(any.get()).as("no PunchChanged for a rejected toggle").isNull();
        }
    }

    @Test
    void setPunchInAnnouncesPunchChangedWithTheInstalledRegion() throws InterruptedException {
        Transport transport = transportAt120Bpm(4.0);
        CoreTransportIntentHandler handler = new CoreTransportIntentHandler(transport, PROJECT_RATE, NO_DIRTY_OBSERVER);

        // ON_CALLER_THREAD still hands delivery to the bus's drain worker, so
        // the two announces are awaited through a latch (as the LoopChanged
        // sibling tests in TransportCommandEventTest do), never asserted inline.
        List<TransportEvent.PunchChanged> announced = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(2);
        try (EventBus.Subscription sub = bus.on(TransportEvent.PunchChanged.class,
                DispatchMode.ON_CALLER_THREAD, event -> {
                    announced.add(event);
                    delivered.countDown();
                })) {

            new SetPunchInCommand().execute(handler);
            new TogglePunchCommand().execute(handler);

            assertThat(delivered.await(5, TimeUnit.SECONDS))
                    .as("ANNOUNCE: both PunchChanged events reach the subscriber").isTrue();
            assertThat(announced).hasSize(2);
            assertThat(announced.get(0).enabled()).isTrue();
            assertThat(announced.get(0).startFrames()).isEqualTo(192_000L);
            assertThat(announced.get(0).endFrames()).isEqualTo(192_000L + MIN_FRAMES);
            assertThat(announced.get(1).enabled()).as("the toggle announces the flipped flag").isFalse();
            assertThat(announced.get(1).startFrames()).isEqualTo(192_000L);
        }
    }

    @Test
    void transportFiresPunchChangeKindForSetAndClear() {
        Transport transport = new Transport();
        List<Transport.ChangeKind> kinds = new ArrayList<>();
        Runnable unregister = transport.addChangeListener(kinds::add);
        try {
            transport.setPunchRegion(PunchRegion.enabled(0L, 10L));
            transport.clearPunchRegion();
        } finally {
            unregister.run();
        }
        assertThat(kinds).containsExactly(Transport.ChangeKind.PUNCH, Transport.ChangeKind.PUNCH);
    }

    private static Transport transportAt120Bpm(double positionBeats) {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        transport.setPositionInBeats(positionBeats);
        return transport;
    }

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
        @Override public void toggleLoopRecord() { calls.add("toggleLoopRecord"); }
        @Override public void setPunchIn() { calls.add("setPunchIn"); }
        @Override public void setPunchOut() { calls.add("setPunchOut"); }
        @Override public void togglePunch() { calls.add("togglePunch"); }
    }
}
