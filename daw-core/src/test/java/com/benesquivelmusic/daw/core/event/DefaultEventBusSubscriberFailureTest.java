package com.benesquivelmusic.daw.core.event;

import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 337, rulings E37 and E38 — every consumer failure of
 * {@link DefaultEventBus} is reported to its required
 * {@link SubscriberFailureSink}, on every delivery path, and never ends the
 * failing consumer's subscription or starves a sibling.
 *
 * <p>Handlers and Flow subscribers are delivered on the bus's own workers, so
 * each asynchronous test ends its burst with a marker event and awaits the
 * marker's delivery: a subscription delivers in FIFO order and reports a
 * failure before taking its next event, so once a consumer has seen the
 * marker every report for the events before it has been made.</p>
 */
class DefaultEventBusSubscriberFailureTest {

    private static final long GUARD_SECONDS = 10;
    private static final int EVENTS = 5;
    private static final long MARKER_POSITION = Long.MAX_VALUE;
    private static final Instant AT = Instant.parse("2026-11-14T12:00:00Z");

    private final List<ReportedFailure> reports = new CopyOnWriteArrayList<>();
    private final SubscriberFailureSink recordingSink =
            (subscriber, failure) -> reports.add(new ReportedFailure(subscriber, failure));
    private final List<LogRecord> busWarnings = new CopyOnWriteArrayList<>();
    private final Handler busLogCapture = new Handler() {
        @Override public void publish(LogRecord logRecord) {
            if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) busWarnings.add(logRecord);
        }
        @Override public void flush() {}
        @Override public void close() {}
    };
    private final Logger busLogger = Logger.getLogger(DefaultEventBus.class.getName());
    private DefaultEventBus bus;

    @AfterEach
    void closeBusAndReleaseTheLogger() {
        busLogger.removeHandler(busLogCapture);
        if (bus != null) {
            bus.close();
        }
    }

    private record ReportedFailure(SubscriberDescription subscriber, Throwable failure) {
    }

    private static TransportEvent.Started started(long position) {
        return new TransportEvent.Started(position, AT);
    }

    private static boolean isMarker(TransportEvent.Started event) {
        return event.positionFrames() == MARKER_POSITION;
    }

    private void publishBurstThenMarker() {
        for (int i = 0; i < EVENTS; i++) {
            bus.publish(started(i));
        }
        bus.publish(started(MARKER_POSITION));
    }

    @Test
    void aPersistentlyThrowingSubscriberIsReportedForEveryEventWhileItsSiblingKeepsReceiving() throws Exception {
        bus = new DefaultEventBus(recordingSink);
        busLogger.addHandler(busLogCapture);
        CountDownLatch failingSawMarker = new CountDownLatch(1);
        CountDownLatch siblingSawMarker = new CountDownLatch(1);
        AtomicInteger siblingReceived = new AtomicInteger();
        bus.on(TransportEvent.Started.class, event -> {
            if (isMarker(event)) {
                failingSawMarker.countDown();
                return;
            }
            throw new IllegalStateException("subscriber failure " + event.positionFrames());
        });
        bus.on(TransportEvent.Started.class, event -> {
            if (isMarker(event)) {
                siblingSawMarker.countDown();
            } else {
                siblingReceived.incrementAndGet();
            }
        });

        publishBurstThenMarker();

        assertThat(failingSawMarker.await(GUARD_SECONDS, TimeUnit.SECONDS))
                .as("the failing subscription keeps receiving after every failure").isTrue();
        assertThat(siblingSawMarker.await(GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(siblingReceived).as("the sibling received every event").hasValue(EVENTS);
        assertThat(reports).hasSize(EVENTS);
        assertThat(reports).allSatisfy(report -> {
            assertThat(report.subscriber().path()).isEqualTo(DeliveryPath.HANDLER);
            assertThat(report.subscriber().eventTypeName()).isEqualTo("Started");
            assertThat(report.subscriber().subscriberId()).startsWith("Started@");
            assertThat(report.failure()).isInstanceOf(IllegalStateException.class);
        });
        assertThat(reports.stream().map(report -> report.failure().getMessage()).toList())
                .containsExactly("subscriber failure 0", "subscriber failure 1", "subscriber failure 2",
                        "subscriber failure 3", "subscriber failure 4");
        assertThat(busWarnings).as("with a sink the bus logs no subscriber failure itself").isEmpty();
    }

    @Test
    void aThrowingFlowSubscriberIsReportedToTheSameSink() throws Exception {
        bus = new DefaultEventBus(recordingSink);
        CountDownLatch sawMarker = new CountDownLatch(1);
        Flow.Subscriber<TransportEvent.Started> subscriber = new ThrowingFlowSubscriber(sawMarker);
        bus.subscribe(TransportEvent.Started.class).subscribe(subscriber);

        publishBurstThenMarker();

        assertThat(sawMarker.await(GUARD_SECONDS, TimeUnit.SECONDS))
                .as("the Flow subscription keeps receiving after every failure").isTrue();
        assertThat(reports).hasSize(EVENTS).allSatisfy(report -> {
            assertThat(report.subscriber().path()).isEqualTo(DeliveryPath.FLOW_SUBSCRIBER);
            assertThat(report.subscriber().eventTypeName()).isEqualTo("Started");
            assertThat(report.subscriber().subscriberId()).isEqualTo(ThrowingFlowSubscriber.class.getName());
            assertThat(report.failure()).isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void aPublicationObserverErrorIsIsolatedAndReported() {
        bus = new DefaultEventBus(recordingSink);
        List<String> ran = new CopyOnWriteArrayList<>();
        bus.onPublication(TransportEvent.Started.class, event -> {
            ran.add("first");
            throw new AssertionError("observer error");
        });
        bus.onPublication(TransportEvent.Started.class, event -> ran.add("later"));

        assertThatCode(() -> bus.publish(started(1))).doesNotThrowAnyException();

        assertThat(ran).containsExactly("first", "later");
        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.subscriber().path()).isEqualTo(DeliveryPath.PUBLICATION_OBSERVER);
            assertThat(report.subscriber().eventTypeName()).isEqualTo("Started");
            assertThat(report.failure()).isInstanceOf(AssertionError.class).hasMessage("observer error");
        });
    }

    @Test
    void aThrowingSinkDoesNotEndTheSubscription() throws Exception {
        bus = new DefaultEventBus((subscriber, failure) -> {
            throw new IllegalStateException("sink failure");
        });
        busLogger.addHandler(busLogCapture);
        CountDownLatch sawMarker = new CountDownLatch(1);
        bus.on(TransportEvent.Started.class, event -> {
            if (isMarker(event)) {
                sawMarker.countDown();
                return;
            }
            throw new IllegalArgumentException("subscriber failure " + event.positionFrames());
        });

        publishBurstThenMarker();

        assertThat(sawMarker.await(GUARD_SECONDS, TimeUnit.SECONDS))
                .as("a throwing sink must not end the subscription").isTrue();
        assertThat(busWarnings).as("each contained sink failure is logged once, with the consumer's failure")
                .hasSize(EVENTS)
                .allSatisfy(warning -> {
                    assertThat(warning.getMessage()).startsWith("The subscriber failure sink failed for Started@")
                            .endsWith("(HANDLER)");
                    assertThat(warning.getThrown()).isInstanceOf(IllegalStateException.class)
                            .hasMessage("sink failure");
                    assertThat(warning.getThrown().getSuppressed())
                            .singleElement()
                            .isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void theFailureSinkIsRequired() {
        assertThatThrownBy(() -> new DefaultEventBus(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DefaultEventBus.builder(null)).isInstanceOf(NullPointerException.class);
    }

    /** Throws from {@code onNext} for every event but the marker, which it counts down on. */
    private static final class ThrowingFlowSubscriber implements Flow.Subscriber<TransportEvent.Started> {

        private final CountDownLatch sawMarker;

        ThrowingFlowSubscriber(CountDownLatch sawMarker) {
            this.sawMarker = sawMarker;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(TransportEvent.Started item) {
            if (isMarker(item)) {
                sawMarker.countDown();
                return;
            }
            throw new UnsupportedOperationException("flow subscriber failure " + item.positionFrames());
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }
    }
}
