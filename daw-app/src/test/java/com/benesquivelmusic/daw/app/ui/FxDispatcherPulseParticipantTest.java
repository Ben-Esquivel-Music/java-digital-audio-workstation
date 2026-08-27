package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 318 — the {@link FxDispatcher#addPulseParticipant(Runnable)} seam
 * (Audio Engine Wiring Design Book §4.3 "FX PULSE drain"): participants run
 * once per {@link FxDispatcher#pulse()}, before the keyed work and the
 * channel drains; the removal token works and is idempotent;
 * {@link FxDispatcher#dispose()} clears them. No toolkit: the pulse is
 * driven manually, as in {@code FxDispatcherDrainTest}.
 */
class FxDispatcherPulseParticipantTest {

    @Test
    void participantRunsExactlyOncePerPulse() {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger runs = new AtomicInteger();

        dispatcher.addPulseParticipant(runs::incrementAndGet);
        assertThat(dispatcher.pulseParticipantCount()).isEqualTo(1);

        dispatcher.pulse();
        assertThat(runs.get()).isEqualTo(1);
        dispatcher.pulse();
        dispatcher.pulse();
        assertThat(runs.get()).isEqualTo(3);
    }

    @Test
    void participantsRunBeforeKeyedWorkAndChannelDrains() {
        FxDispatcher dispatcher = new FxDispatcher();
        List<String> order = new ArrayList<>();

        FxDispatcher.ContinuousDoubleChannel channel =
                dispatcher.openContinuousDouble(v -> order.add("channel"));
        dispatcher.onFx("key", () -> order.add("keyed"));
        dispatcher.addPulseParticipant(() -> order.add("participant"));
        channel.publish(1.0);

        dispatcher.pulse();

        assertThat(order).containsExactly("participant", "keyed", "channel");
    }

    @Test
    void removalTokenRemovesTheParticipantAndIsIdempotent() {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        Runnable removeFirst = dispatcher.addPulseParticipant(first::incrementAndGet);
        dispatcher.addPulseParticipant(second::incrementAndGet);
        dispatcher.pulse();

        removeFirst.run();
        removeFirst.run();
        dispatcher.pulse();

        assertThat(first.get()).as("removed after the first pulse").isEqualTo(1);
        assertThat(second.get()).as("the other participant keeps running").isEqualTo(2);
        assertThat(dispatcher.pulseParticipantCount()).isEqualTo(1);
    }

    @Test
    void theSameRunnableRegisteredTwiceRunsTwiceAndEachTokenRemovesOne() {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger runs = new AtomicInteger();
        Runnable participant = runs::incrementAndGet;

        Runnable removeA = dispatcher.addPulseParticipant(participant);
        dispatcher.addPulseParticipant(participant);
        dispatcher.pulse();
        assertThat(runs.get()).isEqualTo(2);

        removeA.run();
        dispatcher.pulse();
        assertThat(runs.get()).isEqualTo(3);
        assertThat(dispatcher.pulseParticipantCount()).isEqualTo(1);
    }

    @Test
    void disposeClearsEveryParticipant() {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger runs = new AtomicInteger();
        dispatcher.addPulseParticipant(runs::incrementAndGet);
        dispatcher.addPulseParticipant(runs::incrementAndGet);

        dispatcher.dispose();
        dispatcher.pulse();

        assertThat(dispatcher.pulseParticipantCount()).isZero();
        assertThat(runs.get()).isZero();
    }

    @Test
    void aThrowingParticipantDoesNotStopTheOthersOrTheRestOfTheFrameAndIsRethrown() {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger survivor = new AtomicInteger();
        List<String> order = new ArrayList<>();
        FxDispatcher.ContinuousDoubleChannel channel =
                dispatcher.openContinuousDouble(v -> order.add("channel"));
        dispatcher.onFx("key", () -> order.add("keyed"));
        channel.publish(1.0);
        dispatcher.addPulseParticipant(() -> {
            throw new IllegalStateException("boom");
        });
        dispatcher.addPulseParticipant(survivor::incrementAndGet);
        dispatcher.addPulseParticipant(() -> {
            throw new IllegalArgumentException("second boom");
        });

        assertThatThrownBy(dispatcher::pulse)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom")
                .satisfies(t -> assertThat(t.getSuppressed())
                        .as("later failures are suppressed onto the first")
                        .hasSize(1));
        assertThat(survivor.get()).as("the later participant still ran").isEqualTo(1);
        assertThat(order).as("the keyed work and the channel drains still ran")
                .containsExactly("keyed", "channel");
    }

    @Test
    void nullParticipantIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new FxDispatcher().addPulseParticipant(null));
    }
}
