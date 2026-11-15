package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.marshal.PulseItemKind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 318 — the {@link FxDispatcher#addPulseParticipant(Runnable)} seam
 * (Audio Engine Wiring Design Book §4.3 "FX PULSE drain"): participants run
 * once per {@link FxDispatcher#pulse()}, before the keyed work and the
 * channel drains; the removal token works and is idempotent;
 * {@link FxDispatcher#dispose()} clears them. No toolkit: the pulse is
 * driven manually, as in {@code FxDispatcherDrainTest}.
 *
 * <p>Story 337 (rulings E32 and E33): the no-arg dispatcher's contract —
 * every item runs, the first failure is rethrown after the frame — holds for
 * an {@link Error} too; a dispatcher built with a
 * {@link com.benesquivelmusic.daw.app.ui.marshal.PulseFailureSink} reports
 * each failure instead and its pulse never throws.</p>
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
    void repeatedParticipantFailureDoesNotStopTheFrameOrHideDistinctFailures() {
        FxDispatcher dispatcher = new FxDispatcher();
        List<String> order = new ArrayList<>();
        var repeatedFailure = new IllegalStateException("repeated failure");
        var distinctFailure = new IllegalArgumentException("distinct failure");
        Runnable throwingParticipant = () -> {
            order.add("throwing participant");
            throw repeatedFailure;
        };
        dispatcher.addPulseParticipant(throwingParticipant);
        dispatcher.addPulseParticipant(throwingParticipant);
        dispatcher.addPulseParticipant(() -> {
            order.add("distinct failure");
            throw distinctFailure;
        });
        dispatcher.addPulseParticipant(() -> order.add("survivor"));
        dispatcher.onFx("key", () -> order.add("keyed"));
        FxDispatcher.ContinuousChannel<String> channel = dispatcher.openContinuous(order::add);
        FxDispatcher.ContinuousDoubleChannel doubleChannel =
                dispatcher.openContinuousDouble(value -> order.add("double channel " + value));
        channel.publish("generic channel");
        doubleChannel.publish(1.0);

        assertThatThrownBy(dispatcher::pulse)
                .isSameAs(repeatedFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(distinctFailure));
        assertThat(order).containsExactly("throwing participant", "throwing participant",
                "distinct failure", "survivor", "keyed", "generic channel", "double channel 1.0");
    }

    @Test
    void anErrorFromAParticipantDoesNotStopTheOthersOrTheRestOfTheFrameAndIsRethrown() {
        FxDispatcher dispatcher = new FxDispatcher();
        AssertionError error = new AssertionError("a participant's invariant broke");
        AtomicInteger survivor = new AtomicInteger();
        List<String> order = new ArrayList<>();
        dispatcher.addPulseParticipant(() -> {
            throw error;
        });
        dispatcher.addPulseParticipant(survivor::incrementAndGet);
        dispatcher.onFx("key", () -> order.add("keyed"));
        FxDispatcher.ContinuousDoubleChannel channel = dispatcher.openContinuousDouble(v -> order.add("channel"));
        channel.publish(1.0);

        assertThatThrownBy(dispatcher::pulse).as("story 337, ruling E33: an Error is held like an exception")
                .isSameAs(error);
        assertThat(survivor.get()).as("the later participant still ran").isEqualTo(1);
        assertThat(order).as("the keyed work and the channel drains still ran").containsExactly("keyed", "channel");
    }

    @Test
    void withASinkAThrowingParticipantIsReportedAndThePulseDoesNotThrow() {
        List<Throwable> reported = new ArrayList<>();
        List<PulseItemKind> kinds = new ArrayList<>();
        FxDispatcher dispatcher = new FxDispatcher((kind, offender, failure) -> {
            kinds.add(kind);
            reported.add(failure);
        });
        AtomicInteger survivor = new AtomicInteger();
        List<String> order = new ArrayList<>();
        IllegalStateException first = new IllegalStateException("boom");
        AssertionError second = new AssertionError("second boom");
        dispatcher.addPulseParticipant(() -> {
            throw first;
        });
        dispatcher.addPulseParticipant(survivor::incrementAndGet);
        dispatcher.addPulseParticipant(() -> {
            throw second;
        });
        dispatcher.onFx("key", () -> order.add("keyed"));
        FxDispatcher.ContinuousDoubleChannel channel = dispatcher.openContinuousDouble(v -> order.add("channel"));
        channel.publish(1.0);

        assertThatCode(dispatcher::pulse).as("story 337, ruling E32: the sink owns the failures")
                .doesNotThrowAnyException();
        assertThat(reported).as("each failure reported as it happened, in order").containsExactly(first, second);
        assertThat(kinds).containsOnly(PulseItemKind.PARTICIPANT);
        assertThat(survivor.get()).isEqualTo(1);
        assertThat(order).containsExactly("keyed", "channel");
    }

    @Test
    void withASinkARepeatedParticipantFailureIsReportedEveryTimeItIsThrown() {
        List<Throwable> reported = new ArrayList<>();
        FxDispatcher dispatcher = new FxDispatcher((kind, offender, failure) -> reported.add(failure));
        IllegalStateException repeatedFailure = new IllegalStateException("repeated failure");
        Runnable throwingParticipant = () -> {
            throw repeatedFailure;
        };
        dispatcher.addPulseParticipant(throwingParticipant);
        dispatcher.addPulseParticipant(throwingParticipant);

        dispatcher.pulse();

        assertThat(reported).as("the funnel, not the dispatcher, counts and dedupes (story 337, ruling E34)")
                .containsExactly(repeatedFailure, repeatedFailure);
    }

    @Test
    void nullParticipantIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new FxDispatcher().addPulseParticipant(null));
    }
}
