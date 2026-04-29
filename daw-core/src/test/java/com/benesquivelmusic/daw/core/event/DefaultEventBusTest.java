package com.benesquivelmusic.daw.core.event;

import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.OverflowStrategy;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DefaultEventBus}.
 *
 * <p>Covers the requirements from the issue:</p>
 * <ul>
 *   <li>100k-event burst is delivered deterministically to subscribers.</li>
 *   <li>{@link OverflowStrategy#DROP_OLDEST} drops the oldest events.</li>
 *   <li>{@link DispatchMode#ON_UI_THREAD} routes onto the configured UI
 *       executor and not the publishing thread.</li>
 *   <li>{@link DispatchMode#ON_VIRTUAL_THREAD} routes onto a virtual thread.</li>
 *   <li>Typed convenience subscriptions filter to the requested subtype.</li>
 *   <li>Per-event-type throughput counters are exposed via metrics.</li>
 *   <li>Slow subscribers are flagged when avg dispatch &gt; 1&nbsp;ms.</li>
 * </ul>
 */
class DefaultEventBusTest {

    private DefaultEventBus bus;

    @AfterEach
    void closeBus() {
        if (bus != null) bus.close();
    }

    private static TransportEvent.Started newStarted(long offset) {
        return new TransportEvent.Started(
                offset,
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(offset));
    }

    @Test
    void deliversAllEventsForSlowConsumerWithDefaultBuffer() throws Exception {
        bus = DefaultEventBus.builder()
                .bufferCapacity(2048)
                .defaultOverflowStrategy(OverflowStrategy.BLOCK)
                .build();
        int n = 100_000;
        CountDownLatch latch = new CountDownLatch(n);
        AtomicInteger received = new AtomicInteger();
        bus.on(TransportEvent.Started.class, ev -> {
            received.incrementAndGet();
            latch.countDown();
        });
        for (int i = 0; i < n; i++) {
            bus.publish(newStarted(i));
        }
        assertThat(latch.await(30, TimeUnit.SECONDS))
                .as("all 100k events should be delivered with BLOCK strategy")
                .isTrue();
        assertThat(received.get()).isEqualTo(n);
        assertThat(bus.metrics().publishedByType()
                .get("TransportEvent.Started")).isEqualTo(n);
    }

    @Test
    void dropOldestDiscardsHeadOfQueueWhenSubscriberIsSlow() throws Exception {
        bus = DefaultEventBus.builder()
                .bufferCapacity(8)
                .defaultOverflowStrategy(OverflowStrategy.DROP_OLDEST)
                .build();
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Long> seen = new ConcurrentLinkedQueue<>();
        bus.on(TransportEvent.Started.class, ev -> {
            try {
                start.await();
            } catch (InterruptedException ignored) { /* ignore */ }
            seen.add(ev.positionFrames());
        });
        // Flood the bus far beyond the buffer capacity while the subscriber is held.
        int n = 1000;
        for (int i = 0; i < n; i++) {
            bus.publish(newStarted(i));
        }
        // Release the subscriber and let it drain.
        start.countDown();

        // Wait until metrics report some drops to avoid flaky timing.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long drops;
        do {
            drops = bus.metrics().droppedByType()
                    .getOrDefault("TransportEvent.Started", 0L);
            if (drops > 0) break;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);

        assertThat(drops).as("DROP_OLDEST must record dropped events").isGreaterThan(0);
        // Total publishes equal n; received + dropped <= n (some events
        // may still be in flight). The contract: oldest are dropped, so
        // when finally received the high frame offsets must be present.
        Thread.sleep(200);
        assertThat(seen).isNotEmpty();
        // The very last published event must have been retained because
        // DROP_OLDEST always drops the head, never the new arrival.
        assertThat(seen).contains((long) (n - 1));
    }

    @Test
    void onUiThreadRoutesThroughConfiguredExecutorAndNotCallerThread() throws Exception {
        AtomicReference<Thread> uiThread = new AtomicReference<>();
        // UI executor that pretends to be JavaFX's Platform.runLater:
        // it dispatches to a single dedicated thread.
        java.util.concurrent.ExecutorService fxExec =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "fake-fx-thread");
                    uiThread.set(t);
                    return t;
                });
        Executor uiExecutor = fxExec::execute;

        bus = DefaultEventBus.builder()
                .bufferCapacity(32)
                .uiExecutor(uiExecutor)
                .build();
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Thread> subscriberThread = new AtomicReference<>();
            bus.on(TransportEvent.Started.class, DispatchMode.ON_UI_THREAD, ev -> {
                subscriberThread.set(Thread.currentThread());
                latch.countDown();
            });

            Thread caller = Thread.currentThread();
            bus.publish(newStarted(1));

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(subscriberThread.get()).isNotSameAs(caller);
            assertThat(subscriberThread.get().getName()).isEqualTo("fake-fx-thread");
        } finally {
            fxExec.shutdownNow();
        }
    }

    @Test
    void onUiThreadWithoutUiExecutorThrows() {
        bus = DefaultEventBus.builder().build();
        assertThatThrownBy(() -> bus.on(TransportEvent.Started.class,
                DispatchMode.ON_UI_THREAD, ev -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onVirtualThreadRunsOnVirtualThread() throws Exception {
        bus = DefaultEventBus.builder().bufferCapacity(8).build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> isVirtual = new AtomicReference<>();
        bus.on(TransportEvent.Started.class, DispatchMode.ON_VIRTUAL_THREAD, ev -> {
            isVirtual.set(Thread.currentThread().isVirtual());
            latch.countDown();
        });
        bus.publish(newStarted(1));
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(isVirtual.get()).isTrue();
    }

    @Test
    void typedSubscriptionFiltersToRequestedSubtype() throws Exception {
        bus = DefaultEventBus.builder().bufferCapacity(64).build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger startedSeen = new AtomicInteger();
        AtomicInteger stoppedSeen = new AtomicInteger();
        bus.on(TransportEvent.Started.class, ev -> {
            startedSeen.incrementAndGet();
            latch.countDown();
        });
        bus.on(TransportEvent.Stopped.class, ev -> stoppedSeen.incrementAndGet());

        bus.publish(newStarted(1));
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        assertThat(startedSeen.get()).isEqualTo(1);
        assertThat(stoppedSeen.get()).isZero();
    }

    @Test
    void supertypeSubscriptionReceivesAllPermittedSubtypes() throws Exception {
        bus = DefaultEventBus.builder().bufferCapacity(64).build();
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger n = new AtomicInteger();
        bus.on(TransportEvent.class, ev -> {
            n.incrementAndGet();
            latch.countDown();
        });
        bus.publish(newStarted(1));
        bus.publish(new TransportEvent.Stopped(100,
                Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(n.get()).isEqualTo(2);
    }

    @Test
    void slowSubscriberFlaggedInMetrics() throws Exception {
        bus = DefaultEventBus.builder().bufferCapacity(8).build();
        CountDownLatch latch = new CountDownLatch(2);
        bus.on(TransportEvent.Started.class, ev -> {
            try { Thread.sleep(3); } catch (InterruptedException ignored) {}
            latch.countDown();
        });
        bus.publish(newStarted(1));
        bus.publish(newStarted(2));
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // Give the metrics a moment to settle.
        Thread.sleep(20);
        assertThat(bus.metrics().slowSubscribers()).isNotEmpty();
    }

    @Test
    void closedBusSilentlyDropsPublishes() {
        bus = DefaultEventBus.builder().build();
        AtomicInteger count = new AtomicInteger();
        bus.on(TransportEvent.Started.class, ev -> count.incrementAndGet());
        bus.close();
        bus.publish(newStarted(1));
        assertThat(count.get()).isZero();
    }

    @Test
    void closedBusRejectsNewSubscriptions() {
        bus = DefaultEventBus.builder().build();
        bus.close();
        assertThatThrownBy(() -> bus.on(TransportEvent.Started.class, ev -> {}))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bus.subscribe(TransportEvent.Started.class))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellingSubscriptionStopsDelivery() throws Exception {
        bus = DefaultEventBus.builder().bufferCapacity(8).build();
        CountDownLatch first = new CountDownLatch(1);
        AtomicInteger received = new AtomicInteger();
        EventBus.Subscription handle = bus.on(TransportEvent.Started.class, ev -> {
            received.incrementAndGet();
            first.countDown();
        });
        bus.publish(newStarted(1));
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();

        handle.close();
        bus.publish(newStarted(2));
        Thread.sleep(50);
        assertThat(received.get()).isEqualTo(1);
    }

    @Test
    void publishOfNullThrows() {
        bus = DefaultEventBus.builder().build();
        assertThatThrownBy(() -> bus.publish(null))
                .isInstanceOf(NullPointerException.class);
    }
}
