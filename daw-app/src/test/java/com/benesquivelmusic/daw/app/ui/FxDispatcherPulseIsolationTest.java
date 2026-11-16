package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.marshal.PulseFailureSink;
import com.benesquivelmusic.daw.app.ui.marshal.PulseItemKind;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Story 337, Goals — Tests "Pulse isolation test" (rulings E32 and E33;
 * Failure Surfacing book §2.9 "Isolate every fan-out"): every participant,
 * keyed runnable and continuous-channel drain of one {@link FxDispatcher#pulse()}
 * runs inside its own guard, so a keyed runnable and a channel consumer that
 * throw on every frame never starve their siblings of the same frame. With a
 * {@link PulseFailureSink} each failure is reported as it happens — an
 * {@link Error} like an exception — and the pulse never throws, a throwing
 * sink included; without one (the no-arg contract) every item still runs and
 * the frame's first failure, the later ones suppressed onto it, is rethrown
 * after the whole frame. No toolkit: the pulse is driven directly, as in
 * {@code FxDispatcherDrainTest}.
 */
class FxDispatcherPulseIsolationTest {

    private static final int FRAMES = 5;

    private final List<Reported> reported = new CopyOnWriteArrayList<>();
    private final PulseFailureSink recordingSink =
            (kind, offender, failure) -> reported.add(new Reported(kind, offender, failure));
    private final Logger dispatcherLogger = Logger.getLogger(FxDispatcher.class.getName());
    private final List<LogRecord> dispatcherWarnings = new CopyOnWriteArrayList<>();
    private final Handler dispatcherLogCapture = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
                dispatcherWarnings.add(logRecord);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @AfterEach
    void releaseTheLogger() {
        dispatcherLogger.removeHandler(dispatcherLogCapture);
    }

    @Test
    void aThrowingKeyedRunnableAndChannelDrainDoNotStarveTheirSiblingsInTheSameFrame() {
        FxDispatcher dispatcher = new FxDispatcher(recordingSink);
        AtomicInteger participantRuns = new AtomicInteger();
        AtomicInteger keyedSiblingRuns = new AtomicInteger();
        AtomicInteger channelSiblingRuns = new AtomicInteger();
        dispatcher.addPulseParticipant(participantRuns::incrementAndGet);
        Runnable failingKeyed = new Runnable() {
            @Override
            public void run() {
                dispatcher.onFx("failing keyed work", this);
                throw new IllegalStateException("the keyed refresh broke");
            }
        };
        dispatcher.onFx("failing keyed work", failingKeyed);
        FxDispatcher.ContinuousChannel<String> failingChannel = dispatcher.openContinuous(value -> {
            throw new IllegalStateException("the meter consumer broke");
        });
        FxDispatcher.ContinuousDoubleChannel siblingChannel =
                dispatcher.openContinuousDouble(value -> channelSiblingRuns.incrementAndGet());

        for (int frame = 0; frame < FRAMES; frame++) {
            dispatcher.onFx("sibling keyed work", keyedSiblingRuns::incrementAndGet);
            failingChannel.publish("level");
            siblingChannel.publish(0.5);
            assertThatCode(dispatcher::pulse).as("with a sink the pulse never throws").doesNotThrowAnyException();
        }

        assertThat(participantRuns).as("the participant ran every frame").hasValue(FRAMES);
        assertThat(keyedSiblingRuns).as("the sibling keyed work ran every frame").hasValue(FRAMES);
        assertThat(channelSiblingRuns).as("the sibling channel drained every frame").hasValue(FRAMES);
        assertThat(reported).as("two failures reported per frame").hasSize(2 * FRAMES);
        assertThat(reported).filteredOn(report -> report.kind() == PulseItemKind.KEYED_WORK)
                .hasSize(FRAMES)
                .allSatisfy(report -> {
                    assertThat(report.offender()).startsWith("key failing keyed work, ")
                            .contains(FxDispatcherPulseIsolationTest.class.getName());
                    assertThat(report.failure()).hasMessage("the keyed refresh broke");
                });
        assertThat(reported).filteredOn(report -> report.kind() == PulseItemKind.CHANNEL_DRAIN)
                .hasSize(FRAMES)
                .allSatisfy(report -> {
                    assertThat(report.offender()).startsWith(FxDispatcherPulseIsolationTest.class.getName());
                    assertThat(report.failure()).hasMessage("the meter consumer broke");
                });
    }

    @Test
    void anErrorIsIsolatedLikeAnException() {
        FxDispatcher dispatcher = new FxDispatcher(recordingSink);
        AssertionError participantError = new AssertionError("a participant's invariant broke");
        StackOverflowError keyedError = new StackOverflowError("keyed work recursed");
        List<String> order = new ArrayList<>();
        dispatcher.addPulseParticipant(() -> {
            throw participantError;
        });
        dispatcher.addPulseParticipant(() -> order.add("participant"));
        dispatcher.onFx("key", () -> {
            throw keyedError;
        });
        FxDispatcher.ContinuousDoubleChannel channel = dispatcher.openContinuousDouble(value -> order.add("channel"));
        channel.publish(1.0);

        assertThatCode(dispatcher::pulse).doesNotThrowAnyException();

        assertThat(order).as("the later participant and the channel still ran").containsExactly("participant",
                "channel");
        assertThat(reported).extracting(Reported::kind, Reported::failure)
                .containsExactly(tuple(PulseItemKind.PARTICIPANT, participantError),
                        tuple(PulseItemKind.KEYED_WORK, keyedError));
    }

    @Test
    void aThrowingSinkIsContained() {
        dispatcherLogger.addHandler(dispatcherLogCapture);
        AtomicInteger sinkCalls = new AtomicInteger();
        FxDispatcher dispatcher = new FxDispatcher((kind, offender, failure) -> {
            sinkCalls.incrementAndGet();
            throw new IllegalStateException("the sink broke");
        });
        AtomicInteger survivor = new AtomicInteger();
        dispatcher.addPulseParticipant(() -> {
            throw new IllegalArgumentException("a participant broke");
        });
        dispatcher.addPulseParticipant(survivor::incrementAndGet);
        FxDispatcher.ContinuousChannel<String> channel = dispatcher.openContinuous(value -> {
            throw new IllegalArgumentException("a consumer broke");
        });
        channel.publish("value");

        assertThatCode(dispatcher::pulse).as("a throwing sink never reaches the pulse's caller")
                .doesNotThrowAnyException();

        assertThat(survivor).as("the frame carried on").hasValue(1);
        assertThat(sinkCalls).as("the sink was asked for both failures").hasValue(2);
        assertThat(dispatcherWarnings).as("one WARNING per sink failure, with the sink's throwable")
                .hasSize(2)
                .allSatisfy(warning -> {
                    assertThat(warning.getMessage()).isEqualTo("The pulse failure sink failed");
                    assertThat(warning.getThrown()).hasMessage("the sink broke");
                });
    }

    @Test
    void aKeyWhoseHashCodeThrowsIsReportedAndTheFrameContinues() {
        FxDispatcher dispatcher = new FxDispatcher(recordingSink);
        AtomicInteger keyedRuns = new AtomicInteger();
        AtomicInteger channelRuns = new AtomicInteger();
        dispatcher.onFx(new HashCodeThrowsFromTheSecondCall(), keyedRuns::incrementAndGet);
        FxDispatcher.ContinuousDoubleChannel sibling =
                dispatcher.openContinuousDouble(value -> channelRuns.incrementAndGet());
        sibling.publish(4.0);

        assertThatCode(dispatcher::pulse).as("the removal's failure is the item's, not the pulse's (ruling E33)")
                .doesNotThrowAnyException();

        assertThat(reported).as("one KEYED_WORK report").singleElement().satisfies(report -> {
            assertThat(report.kind()).isEqualTo(PulseItemKind.KEYED_WORK);
            assertThat(report.offender()).startsWith("key a key whose hashCode throws, ");
            assertThat(report.failure()).hasMessage("hashCode refused");
        });
        assertThat(channelRuns).as("the sibling channel drained in the same frame").hasValue(1);
        assertThat(keyedRuns).as("the entry could not be removed, so it did not run").hasValue(0);
    }

    @Test
    void aThrowingKeyedEntryNoLongerLeavesTheRestOfTheSnapshotUnrun() {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger healthyRuns = new AtomicInteger();
        IllegalStateException first = new IllegalStateException("keyed failure one");
        IllegalStateException second = new IllegalStateException("keyed failure two");
        dispatcher.onFx("one", () -> {
            throw first;
        });
        dispatcher.onFx("healthy a", healthyRuns::incrementAndGet);
        dispatcher.onFx("two", () -> {
            throw second;
        });
        dispatcher.onFx("healthy b", healthyRuns::incrementAndGet);
        AtomicInteger channelRuns = new AtomicInteger();
        FxDispatcher.ContinuousDoubleChannel channel =
                dispatcher.openContinuousDouble(value -> channelRuns.incrementAndGet());
        channel.publish(2.0);

        assertThatThrownBy(dispatcher::pulse)
                .as("the frame's first failure is rethrown after the frame, the other suppressed onto it")
                .satisfies(thrown -> {
                    assertThat(thrown).isIn(first, second);
                    assertThat(thrown.getSuppressed()).containsExactly(thrown == first ? second : first);
                });
        assertThat(healthyRuns).as("every keyed entry of the snapshot ran").hasValue(2);
        assertThat(channelRuns).as("and the channel drained").hasValue(1);

        assertThatCode(dispatcher::pulse).as("nothing was left queued for the next frame").doesNotThrowAnyException();
        assertThat(healthyRuns).hasValue(2);
    }

    @Test
    void anErrorFromAParticipantNoLongerDiscardsAHeldFailure() {
        FxDispatcher dispatcher = new FxDispatcher();
        IllegalStateException held = new IllegalStateException("the first participant broke");
        AssertionError error = new AssertionError("the second participant's invariant broke");
        List<String> order = new ArrayList<>();
        dispatcher.addPulseParticipant(() -> {
            throw held;
        });
        dispatcher.addPulseParticipant(() -> {
            throw error;
        });
        dispatcher.onFx("key", () -> order.add("keyed"));
        FxDispatcher.ContinuousChannel<String> channel = dispatcher.openContinuous(order::add);
        channel.publish("channel");

        assertThatThrownBy(dispatcher::pulse).isSameAs(held)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(error));
        assertThat(order).as("the Error stopped neither the keyed work nor the channel drain")
                .containsExactly("keyed", "channel");
    }

    @Test
    void aThrowingChannelConsumerNoLongerStopsTheLaterChannelsWithoutASink() {
        FxDispatcher dispatcher = new FxDispatcher();
        IllegalStateException failure = new IllegalStateException("the first consumer broke");
        AtomicInteger laterChannelRuns = new AtomicInteger();
        FxDispatcher.ContinuousChannel<String> failing = dispatcher.openContinuous(value -> {
            throw failure;
        });
        FxDispatcher.ContinuousDoubleChannel later =
                dispatcher.openContinuousDouble(value -> laterChannelRuns.incrementAndGet());
        failing.publish("value");
        later.publish(3.0);

        assertThatThrownBy(dispatcher::pulse).isSameAs(failure);
        assertThat(laterChannelRuns).as("the later channel drained in the same frame").hasValue(1);
    }

    /** One report the recording sink received. */
    private record Reported(PulseItemKind kind, String offender, Throwable failure) {
    }

    /**
     * A coalescing key whose {@code hashCode} answers once — the keyed post's
     * insert — and throws from its second call on, which is the pulse's
     * removal by key and value.
     */
    private static final class HashCodeThrowsFromTheSecondCall {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public int hashCode() {
            if (calls.incrementAndGet() > 1) {
                throw new IllegalStateException("hashCode refused");
            }
            return 42;
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public String toString() {
            return "a key whose hashCode throws";
        }
    }
}
