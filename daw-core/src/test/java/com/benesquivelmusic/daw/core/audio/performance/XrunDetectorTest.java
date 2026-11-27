package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.sdk.audio.XrunEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Unit tests for {@link XrunDetector}: the render drive's tick only counts,
 * and the events reach subscribers through {@link XrunDetector#publishPending()}
 * or the two report methods, on a non-real-time thread.
 */
class XrunDetectorTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int BUFFER_SIZE = 256;
    /** Deadline = 256/48000 s ≈ 5,333,333 ns. */
    private static final long DEADLINE_NANOS = (long) ((BUFFER_SIZE / SAMPLE_RATE) * 1_000_000_000.0);
    private static final long LATE = (long) (DEADLINE_NANOS * 1.5);
    private static final long DROPPED = DEADLINE_NANOS * 3;
    private static final long GUARD_BUDGET_MILLIS = 5_000L;

    @Test
    void constructorComputesDeadlineFromBufferSizeAndSampleRate() {
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE)) {
            assertThat(d.getDeadlineNanos()).isEqualTo(DEADLINE_NANOS);
        }
    }

    @Test
    void constructorRejectsInvalidArguments() {
        assertThatThrownBy(() -> new XrunDetector(0.0, BUFFER_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new XrunDetector(SAMPLE_RATE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, (SubmissionPublisher<XrunEvent>) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ticksAreClassifiedAndCountedWithoutTouchingThePublisher() {
        CountingPublisher publisher = new CountingPublisher();
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, publisher)) {
            d.recordTick(DEADLINE_NANOS / 2);
            d.recordTick(DEADLINE_NANOS);
            d.recordTick(LATE);
            d.recordTick(DROPPED);
            d.recordTick((long) (DEADLINE_NANOS * 2.0));

            assertThat(d.getTickCount()).isEqualTo(5);
            assertThat(d.getLateCount())
                    .as("only the block past the deadline and below twice it is late")
                    .isEqualTo(1);
            assertThat(d.getDroppedCount())
                    .as("a block at or beyond the dropout threshold is dropped")
                    .isEqualTo(2);
            assertThat(publisher.offers)
                    .as("the render drive's tick offers nothing: events wait for publishPending")
                    .isZero();
        }
    }

    @Test
    void publishPendingOffersExactlyTheNewTicksOnce() throws Exception {
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE)) {
            List<XrunEvent> received = subscribeAndCollect(d);
            d.recordTick(LATE);
            d.recordTick(DEADLINE_NANOS / 2);
            d.recordTick(DROPPED);

            assertThat(d.publishPending()).isEqualTo(2);
            awaitCondition(() -> received.size() == 2, "both events are delivered");
            assertThat(received).extracting(event -> event.getClass().getSimpleName())
                    .containsExactly("BufferDropped", "BufferLate");
            XrunEvent.BufferLate late = (XrunEvent.BufferLate) received.get(1);
            assertThat(late.frameIndex()).as("the newest tick's ordinal").isEqualTo(2);
            assertThat(late.deadlineMiss().toNanos())
                    .as("the newest overshoot")
                    .isEqualTo(DROPPED - DEADLINE_NANOS);

            assertThat(d.publishPending())
                    .as("a second call with no new ticks offers nothing")
                    .isZero();
            d.recordTick(LATE);
            assertThat(d.publishPending()).as("only the tick counted since").isEqualTo(1);
            awaitCondition(() -> received.size() == 3, "the third event is delivered");
            Thread.sleep(50);
            assertThat(received).hasSize(3);
        }
    }

    @Test
    void publishPendingOffersAtMostTheCapAndKeepsTheCountsExact() {
        CountingPublisher publisher = new CountingPublisher();
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, publisher)) {
            for (int i = 0; i < 40; i++) {
                d.recordTick(i % 2 == 0 ? LATE : DROPPED);
            }

            assertThat(d.publishPending()).isEqualTo(XrunDetector.MAX_EVENTS_PER_PUBLISH);
            assertThat(publisher.offers).isEqualTo(XrunDetector.MAX_EVENTS_PER_PUBLISH);
            assertThat(d.publishPending())
                    .as("ticks beyond the cap are accounted for, not carried into the next call")
                    .isZero();
            assertThat(d.getLateCount()).isEqualTo(20);
            assertThat(d.getDroppedCount()).isEqualTo(20);
        }
    }

    @Test
    void reportedAndTickedDropsBothReachTheDroppedCount() {
        CountingPublisher publisher = new CountingPublisher();
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, publisher)) {
            d.recordTick(DROPPED);
            d.reportDropped(3);

            assertThat(d.getDroppedCount()).isEqualTo(4);
            assertThat(publisher.offers)
                    .as("one offer for the whole report, not one per block")
                    .isEqualTo(1);
            assertThatThrownBy(() -> d.reportDropped(0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(d.getDroppedCount()).isEqualTo(4);
        }
    }

    @Test
    void reportGraphOverloadPublishesGraphOverloadEvent() {
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE)) {
            List<XrunEvent> received = subscribeAndCollect(d);
            d.reportGraphOverload("reverb-1", 1.4);
            awaitCondition(() -> received.size() == 1, "the overload is delivered");
            assertThat(received.get(0)).isInstanceOf(XrunEvent.GraphOverload.class);
            XrunEvent.GraphOverload go = (XrunEvent.GraphOverload) received.get(0);
            assertThat(go.offendingNodeId()).isEqualTo("reverb-1");
            assertThat(go.cpuFraction()).isEqualTo(1.4);
            assertThat(d.getOverloadCount()).isEqualTo(1);
        }
    }

    @Test
    void aBorrowedPublisherOutlivesTheDetectorsClose() {
        SubmissionPublisher<XrunEvent> shared = new SubmissionPublisher<>();
        try {
            XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, shared);
            assertThat(d.xrunEvents()).isSameAs(shared);
            d.close();
            assertThat(shared.isClosed()).as("a borrowed publisher stays open").isFalse();
        } finally {
            shared.close();
        }
    }

    @Test
    void anOwnedPublisherIsCompletedByClose() {
        XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE);
        List<String> signals = new CopyOnWriteArrayList<>();
        d.xrunEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(XrunEvent item) { signals.add("next"); }
            @Override public void onError(Throwable throwable) { signals.add("error"); }
            @Override public void onComplete() { signals.add("complete"); }
        });
        d.close();
        awaitCondition(() -> signals.contains("complete"), "the owned publisher completes");
    }

    @Test
    void aClosedPublisherNeverMakesTheDetectorThrow() {
        SubmissionPublisher<XrunEvent> shared = new SubmissionPublisher<>();
        shared.close();
        XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, shared);
        d.recordTick(LATE);
        d.recordTick(DROPPED);

        assertThatCode(() -> {
            assertThat(d.publishPending()).isZero();
            d.reportDropped(1);
            d.reportGraphOverload("node", 1.0);
            d.close();
        }).doesNotThrowAnyException();
        assertThat(d.getLateCount()).isEqualTo(1);
        assertThat(d.getDroppedCount()).isEqualTo(2);
        assertThat(d.getOverloadCount()).isEqualTo(1);
    }

    // --- helpers ---

    /** Counts offers on the caller's thread, so a test can prove the tick made none. */
    private static final class CountingPublisher extends SubmissionPublisher<XrunEvent> {
        int offers;

        @Override
        public int offer(XrunEvent item, BiPredicate<Flow.Subscriber<? super XrunEvent>, ? super XrunEvent> onDrop) {
            offers++;
            return super.offer(item, onDrop);
        }
    }

    private static List<XrunEvent> subscribeAndCollect(XrunDetector d) {
        List<XrunEvent> received = new CopyOnWriteArrayList<>();
        d.xrunEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(XrunEvent item) { received.add(item); }
            @Override public void onError(Throwable throwable) { /* ignored in tests */ }
            @Override public void onComplete() { /* no-op */ }
        });
        return received;
    }

    private static void awaitCondition(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GUARD_BUDGET_MILLIS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out after " + GUARD_BUDGET_MILLIS + " ms awaiting: " + description);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }
}
