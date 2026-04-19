package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.sdk.audio.XrunEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link XrunDetector}. The clock is injected as a
 * {@link java.util.function.LongSupplier} so that late buffers can be
 * simulated deterministically without sleeping the test thread.
 */
class XrunDetectorTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int BUFFER_SIZE = 256;
    /** Deadline = 256/48000 s ≈ 5,333,333 ns. */
    private static final long DEADLINE_NANOS = (long) ((BUFFER_SIZE / SAMPLE_RATE) * 1_000_000_000.0);

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
        assertThatThrownBy(() -> new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, 1.0, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onTimeBufferEmitsNoEvent() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, 2.0, clock::get)) {
            List<XrunEvent> received = subscribeAndCollect(d);
            try (var tick = d.beginTick()) {
                clock.addAndGet(DEADLINE_NANOS / 2); // well within budget
            }
            // Give the SubmissionPublisher executor a moment; nothing should arrive.
            Thread.sleep(50);
            assertThat(received).isEmpty();
            assertThat(d.getLateCount()).isZero();
            assertThat(d.getDroppedCount()).isZero();
        }
    }

    @Test
    void lateBufferEmitsBufferLateEvent() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        CountDownLatch latch = new CountDownLatch(1);
        List<XrunEvent> received = new CopyOnWriteArrayList<>();
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, 2.0, clock::get)) {
            subscribe(d, e -> { received.add(e); latch.countDown(); });
            try (var tick = d.beginTick()) {
                // 1.5x budget — past deadline, below dropout threshold
                clock.addAndGet((long) (DEADLINE_NANOS * 1.5));
            }
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).hasSize(1);
            assertThat(received.get(0)).isInstanceOf(XrunEvent.BufferLate.class);
            XrunEvent.BufferLate late = (XrunEvent.BufferLate) received.get(0);
            assertThat(late.frameIndex()).isZero();
            assertThat(late.deadlineMiss().toNanos()).isGreaterThan(0);
            assertThat(d.getLateCount()).isEqualTo(1);
            assertThat(d.getDroppedCount()).isZero();
        }
    }

    @Test
    void veryLateBufferEmitsBufferDroppedEvent() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        CountDownLatch latch = new CountDownLatch(1);
        List<XrunEvent> received = new CopyOnWriteArrayList<>();
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, 2.0, clock::get)) {
            subscribe(d, e -> { received.add(e); latch.countDown(); });
            try (var tick = d.beginTick()) {
                // 3x budget — past dropout threshold (2x)
                clock.addAndGet(DEADLINE_NANOS * 3);
            }
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).hasSize(1);
            assertThat(received.get(0)).isInstanceOf(XrunEvent.BufferDropped.class);
            assertThat(d.getDroppedCount()).isEqualTo(1);
            assertThat(d.getLateCount()).isZero();
        }
    }

    @Test
    void recordTickAssignsIncrementingFrameIndices() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        CountDownLatch latch = new CountDownLatch(3);
        List<XrunEvent> received = new CopyOnWriteArrayList<>();
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, 2.0, clock::get)) {
            subscribe(d, e -> { received.add(e); latch.countDown(); });
            d.recordTick((long) (DEADLINE_NANOS * 1.2)); // late -> frame 0
            d.recordTick((long) (DEADLINE_NANOS * 1.3)); // late -> frame 1
            d.recordTick(DEADLINE_NANOS * 5);            // dropped -> frame 2
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.stream().map(XrunEvent::frameIndex))
                    .containsExactly(0L, 1L, 2L);
        }
    }

    @Test
    void reportGraphOverloadPublishesGraphOverloadEvent() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<XrunEvent> received = new CopyOnWriteArrayList<>();
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE)) {
            subscribe(d, e -> { received.add(e); latch.countDown(); });
            d.reportGraphOverload("reverb-1", 1.4);
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).hasSize(1);
            assertThat(received.get(0)).isInstanceOf(XrunEvent.GraphOverload.class);
            XrunEvent.GraphOverload go = (XrunEvent.GraphOverload) received.get(0);
            assertThat(go.offendingNodeId()).isEqualTo("reverb-1");
            assertThat(go.cpuFraction()).isEqualTo(1.4);
            assertThat(d.getOverloadCount()).isEqualTo(1);
        }
    }

    @Test
    void resetClearsAllCountersAndFrameIndex() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        try (XrunDetector d = new XrunDetector(SAMPLE_RATE, BUFFER_SIZE, 2.0, clock::get)) {
            d.recordTick((long) (DEADLINE_NANOS * 1.2));
            d.recordTick(DEADLINE_NANOS * 5);
            d.reportGraphOverload("n", 1.0);
            assertThat(d.getLateCount()).isEqualTo(1);
            assertThat(d.getDroppedCount()).isEqualTo(1);
            assertThat(d.getOverloadCount()).isEqualTo(1);

            d.reset();
            assertThat(d.getLateCount()).isZero();
            assertThat(d.getDroppedCount()).isZero();
            assertThat(d.getOverloadCount()).isZero();

            // Next tick should be frame 0 again
            CountDownLatch latch = new CountDownLatch(1);
            List<XrunEvent> received = new CopyOnWriteArrayList<>();
            subscribe(d, e -> { received.add(e); latch.countDown(); });
            d.recordTick((long) (DEADLINE_NANOS * 1.2));
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get(0).frameIndex()).isZero();
        }
    }

    // --- helpers ---

    private static List<XrunEvent> subscribeAndCollect(XrunDetector d) {
        List<XrunEvent> received = new CopyOnWriteArrayList<>();
        subscribe(d, received::add);
        return received;
    }

    private static void subscribe(XrunDetector d, java.util.function.Consumer<XrunEvent> onNext) {
        d.xrunEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(XrunEvent item) { onNext.accept(item); }
            @Override public void onError(Throwable throwable) { /* ignored in tests */ }
            @Override public void onComplete() { /* no-op */ }
        });
    }
}
