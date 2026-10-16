package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 327 fix round (PR #982 review): the {@link EngineStateBridge}'s
 * subscription cell against the publisher's asynchronous
 * {@code onSubscribe}. {@code SubmissionPublisher} delivers the
 * subscription on its executor, so a window teardown can
 * {@link EngineStateBridge#cancel() cancel} before it has arrived; the
 * bridge then cancels the late subscription on arrival instead of
 * requesting from it, and drops a state delivered after the cancellation.
 * The subscription is a recording double where the bridge's own calls are
 * the fact; the last test is the finding's exact interleaving against the
 * real JDK publisher, with its consumer task held until the bridge has
 * been cancelled. The transport supplier counts the FX turns the bridge
 * posts and hands back no transport, so no {@link TransportController} is
 * built: a counted call is a posted turn that ran.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class EngineStateBridgeTest {

    private static final long FX_TURN_BUDGET_SECONDS = 5;

    @Test
    void aSubscriptionArrivingAfterTheCancellationIsCancelledAndNeverRequested() {
        AtomicInteger deliveries = new AtomicInteger();
        EngineStateBridge bridge = bridgeOver(deliveries);
        RecordingSubscription late = new RecordingSubscription();

        bridge.cancel();
        bridge.onSubscribe(late);

        assertThat(late.cancellations.get()).as("cancelled on arrival").isEqualTo(1);
        assertThat(late.requests).as("nothing requested").isEmpty();
        bridge.cancel();
        assertThat(late.cancellations.get()).as("a second cancel() is a no-op").isEqualTo(1);
    }

    @Test
    void aSubscriptionHeldWhenCancelledIsCancelledOnceAfterItWasRequested() {
        EngineStateBridge bridge = bridgeOver(new AtomicInteger());
        RecordingSubscription held = new RecordingSubscription();

        bridge.onSubscribe(held);
        assertThat(held.requests).as("everything requested, once").containsExactly(Long.MAX_VALUE);
        assertThat(held.cancellations.get()).isZero();

        bridge.cancel();
        assertThat(held.cancellations.get()).isEqualTo(1);
        bridge.cancel();
        assertThat(held.cancellations.get()).as("a second cancel() is a no-op").isEqualTo(1);
        assertThat(held.requests).containsExactly(Long.MAX_VALUE);
    }

    @Test
    void aSecondSubscriptionWhileOneIsHeldIsCancelledAndTheFirstIsUntouched() {
        EngineStateBridge bridge = bridgeOver(new AtomicInteger());
        RecordingSubscription first = new RecordingSubscription();
        RecordingSubscription second = new RecordingSubscription();

        bridge.onSubscribe(first);
        bridge.onSubscribe(second);

        assertThat(second.cancellations.get()).as("Reactive Streams rule 2.5: the second is cancelled").isEqualTo(1);
        assertThat(second.requests).isEmpty();
        assertThat(first.requests).containsExactly(Long.MAX_VALUE);
        assertThat(first.cancellations.get()).isZero();
    }

    @Test
    void aStateDeliveredAfterTheCancellationIsNotPosted() throws Exception {
        AtomicInteger deliveries = new AtomicInteger();
        EngineStateBridge bridge = bridgeOver(deliveries);
        bridge.onSubscribe(new RecordingSubscription());
        EngineStateChange change = new EngineStateChange(EngineState.STOPPED, Optional.empty());

        bridge.onNext(change);
        anFxTurn();
        assertThat(deliveries.get()).as("a state delivered while subscribed is posted and its turn ran").isEqualTo(1);

        bridge.cancel();
        bridge.onNext(change);
        anFxTurn();
        assertThat(deliveries.get()).as("a state delivered after cancel() is dropped").isEqualTo(1);
    }

    /**
     * The finding's interleaving against the real publisher: its consumer
     * task — the one that delivers {@code onSubscribe} — is held on the
     * executor while the bridge is cancelled, then released; the publisher
     * then has no subscriber left, where a forgotten cancellation left the
     * bridge subscribed and requesting.
     */
    @Test
    void cancellingBeforeTheRealPublisherDeliversTheSubscriptionLeavesItWithNoSubscriber() throws Exception {
        SteppedTakeFixture.ManualStorage executor = new SteppedTakeFixture.ManualStorage();
        SubmissionPublisher<EngineStateChange> publisher = new SubmissionPublisher<>(executor, Flow.defaultBufferSize());
        try {
            EngineStateBridge bridge = bridgeOver(new AtomicInteger());
            publisher.subscribe(bridge);
            executor.awaitQueued(1);
            assertThat(publisher.getNumberOfSubscribers())
                    .as("premise: subscribed, its onSubscribe still held on the executor").isEqualTo(1);

            bridge.cancel();
            executor.release();

            await(() -> publisher.getNumberOfSubscribers() == 0, "the late subscription was cancelled on arrival");
            assertThat(publisher.hasSubscribers()).isFalse();
        } finally {
            publisher.close();
            executor.release();
            executor.joinEveryTask();
        }
    }

    private static EngineStateBridge bridgeOver(AtomicInteger deliveries) {
        return new EngineStateBridge(() -> {
            deliveries.incrementAndGet();
            return null;
        }, new FxDispatcher());
    }

    /** Posts a marker turn and waits for it: every turn posted before it has run. */
    private static void anFxTurn() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        Platform.runLater(ran::countDown);
        assertThat(ran.await(FX_TURN_BUDGET_SECONDS, TimeUnit.SECONDS))
                .as("the FX turn ran within %d s", FX_TURN_BUDGET_SECONDS).isTrue();
    }

    /** Records what the bridge asks of it; requests and cancels nothing of its own. */
    private static final class RecordingSubscription implements Flow.Subscription {
        final List<Long> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger cancellations = new AtomicInteger();

        @Override
        public void request(long n) {
            requests.add(n);
        }

        @Override
        public void cancel() {
            cancellations.incrementAndGet();
        }
    }
}
