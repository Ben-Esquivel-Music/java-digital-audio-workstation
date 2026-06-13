package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Story 289 — verifies the {@link FxDispatcher} continuous lock-free path
 * (Control Synchronization Design Book §4.1, §4.5, §4.6): a high-frequency
 * producer publishes into a single-reader buffer that is drained once per frame,
 * coalescing a burst into the latest value, and the producer's
 * {@code publish(...)} <strong>never blocks</strong> — it is wait-free, safe for
 * the {@code @RealTimeSafe} audio thread.
 *
 * <p>No JavaFX toolkit is needed: the drain is driven <em>manually</em> via
 * {@link FxDispatcher#pulse()} (the story forbids relying on a live
 * {@code AnimationTimer}), and the consumer is a plain test callback. The
 * consumer naturally runs on whatever thread calls {@code pulse()} here; in
 * production {@code pulse()} is the FX thread's {@code AnimationTimer} body.</p>
 */
class FxDispatcherDrainTest {

    @Test
    void manyWritesBetweenTwoDrainsYieldTheLatestCoalescedValue() {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger drains = new AtomicInteger(0);
        AtomicReference<Integer> lastDelivered = new AtomicReference<>();

        FxDispatcher.ContinuousChannel<Integer> channel =
                dispatcher.openContinuous(v -> {
                    drains.incrementAndGet();
                    lastDelivered.set(v);
                });

        // Publish a burst, then drain once: the consumer must see only the
        // latest value, invoked exactly once (the coalescing contract).
        for (int i = 0; i < 500; i++) {
            channel.publish(i);
        }
        dispatcher.pulse();

        assertThat(drains.get())
                .as("a burst drained once must deliver exactly one coalesced value")
                .isEqualTo(1);
        assertThat(lastDelivered.get())
                .as("the delivered value must be the latest published")
                .isEqualTo(499);

        // A second pulse with nothing newly published must not re-deliver.
        dispatcher.pulse();
        assertThat(drains.get())
                .as("an empty drain must not invoke the consumer")
                .isEqualTo(1);

        // After more writes, the next drain delivers the new latest.
        channel.publish(1000);
        channel.publish(1001);
        dispatcher.pulse();
        assertThat(drains.get()).isEqualTo(2);
        assertThat(lastDelivered.get()).isEqualTo(1001);
    }

    @Test
    void publishNeverBlocksUnderAConcurrentWriterHammeringTheChannel() throws InterruptedException {
        FxDispatcher dispatcher = new FxDispatcher();
        FxDispatcher.ContinuousChannel<Integer> channel =
                dispatcher.openContinuous(v -> { /* drained on the test thread */ });

        int writeCount = 1_000_000;
        AtomicReference<Throwable> writerError = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Long> elapsedNanos = new AtomicReference<>();

        // A writer thread hammers publish() WITHOUT any concurrent drain: the
        // depth-1 latest-wins mailbox is wait-free and overwrites the pending
        // value rather than blocking, so even a million writes against an
        // undrained buffer must return promptly.
        Thread writer = new Thread(() -> {
            try {
                long start = System.nanoTime();
                for (int i = 0; i < writeCount; i++) {
                    channel.publish(i);
                }
                elapsedNanos.set(System.nanoTime() - start);
            } catch (Throwable t) {
                writerError.set(t);
            } finally {
                done.countDown();
            }
        }, "fx-dispatcher-drain-test-writer");
        writer.setDaemon(true);
        writer.start();

        // If publish() blocked (e.g. waited on a full buffer) this would time
        // out — proving the producer never blocks on the consumer.
        boolean finished = done.await(10, TimeUnit.SECONDS);
        assertThat(finished)
                .as("a writer hammering publish() must never block — %d wait-free "
                        + "writes must complete well within the timeout", writeCount)
                .isTrue();
        assertThat(writerError.get())
                .as("the writer must not throw")
                .isNull();
        assertThat(elapsedNanos.get())
                .as("the write burst must complete (non-null elapsed time)")
                .isNotNull();
    }

    @Test
    void concurrentlyDrainingWhileWritingNeverBlocksTheWriter() throws InterruptedException {
        FxDispatcher dispatcher = new FxDispatcher();
        List<Integer> delivered = new ArrayList<>();
        FxDispatcher.ContinuousChannel<Integer> channel =
                dispatcher.openContinuous(delivered::add);

        int writeCount = 200_000;
        AtomicReference<Throwable> writerError = new AtomicReference<>();
        CountDownLatch writerDone = new CountDownLatch(1);

        // SPSC contract: ONE producer thread, ONE consumer (the drain). Here the
        // writer thread is the single producer and the main thread is the single
        // consumer draining concurrently — the writer must still never block.
        Thread writer = new Thread(() -> {
            try {
                for (int i = 1; i <= writeCount; i++) {
                    channel.publish(i);
                }
            } catch (Throwable t) {
                writerError.set(t);
            } finally {
                writerDone.countDown();
            }
        }, "fx-dispatcher-drain-test-spsc-writer");
        writer.setDaemon(true);
        writer.start();

        // Drain repeatedly from this (single consumer) thread while the writer runs.
        while (writerDone.getCount() > 0) {
            dispatcher.pulse();
        }

        assertThat(writerDone.await(10, TimeUnit.SECONDS))
                .as("the concurrent SPSC writer must finish without blocking")
                .isTrue();
        assertThat(writerError.get()).as("the writer must not throw").isNull();

        // The writer is fully done, so the buffer is no longer being written.
        // A sentinel published now cannot be dropped by a concurrent overflow,
        // so a final drain must deliver it deterministically — proving the drain
        // path keeps working after concurrent writes (the coalescing + drop of
        // intermediate burst values during the run is by design, so we do not
        // assert which mid-run values were delivered).
        int sentinel = writeCount + 1;
        channel.publish(sentinel);
        dispatcher.pulse();
        assertThat(delivered)
                .as("a post-run sentinel must be delivered by the final drain")
                .contains(sentinel);
    }

    @Test
    void closingAGenericChannelRemovesItFromTheDrainSoItCannotLeak() {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger drains = new AtomicInteger(0);
        FxDispatcher.ContinuousChannel<Integer> channel =
                dispatcher.openContinuous(v -> drains.incrementAndGet());
        assertThat(dispatcher.openChannelCount())
                .as("openContinuous registers one channel").isEqualTo(1);

        channel.publish(7);
        dispatcher.pulse();
        assertThat(drains.get()).as("an open channel is drained").isEqualTo(1);

        channel.close();
        assertThat(dispatcher.openChannelCount())
                .as("close() removes the channel from the dispatcher (no leak)").isZero();

        // A value published after close is never delivered — the channel is gone.
        channel.publish(8);
        dispatcher.pulse();
        assertThat(drains.get())
                .as("a closed channel is no longer drained").isEqualTo(1);

        channel.close(); // idempotent
        assertThat(dispatcher.openChannelCount()).isZero();
    }

    @Test
    void doubleChannelCoalescesABurstToTheLatestValue() {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger drains = new AtomicInteger(0);
        double[] lastDelivered = {Double.NaN};
        FxDispatcher.ContinuousDoubleChannel channel =
                dispatcher.openContinuousDouble(v -> {
                    drains.incrementAndGet();
                    lastDelivered[0] = v; // DoubleConsumer.accept(double) — no box
                });

        // A primitive burst coalesces to the latest, drained exactly once.
        for (int i = 1; i <= 500; i++) {
            channel.publish(i * 0.25);
        }
        dispatcher.pulse();
        assertThat(drains.get())
                .as("a burst drained once delivers exactly one coalesced value").isEqualTo(1);
        assertThat(lastDelivered[0])
                .as("the delivered value is the latest published").isEqualTo(125.0);

        // An empty drain must not re-deliver.
        dispatcher.pulse();
        assertThat(drains.get()).as("an empty drain does not invoke the consumer").isEqualTo(1);

        // 0.0 is a real value distinct from the NaN empty-sentinel — it must deliver.
        channel.publish(0.0);
        dispatcher.pulse();
        assertThat(drains.get()).isEqualTo(2);
        assertThat(lastDelivered[0])
                .as("zero is a real value, not the empty sentinel").isEqualTo(0.0);
    }

    @Test
    void doubleChannelRejectsNaNAsTheReservedEmptySentinel() {
        FxDispatcher dispatcher = new FxDispatcher();
        FxDispatcher.ContinuousDoubleChannel channel =
                dispatcher.openContinuousDouble(v -> { });
        assertThatIllegalArgumentException()
                .as("NaN is the empty-mailbox sentinel and must be rejected")
                .isThrownBy(() -> channel.publish(Double.NaN));
    }

    @Test
    void closingADoubleChannelRemovesItFromTheDrainSoItCannotLeak() {
        FxDispatcher dispatcher = new FxDispatcher();
        AtomicInteger drains = new AtomicInteger(0);
        FxDispatcher.ContinuousDoubleChannel channel =
                dispatcher.openContinuousDouble(v -> drains.incrementAndGet());
        assertThat(dispatcher.openChannelCount()).isEqualTo(1);

        channel.publish(3.0);
        dispatcher.pulse();
        assertThat(drains.get()).isEqualTo(1);

        channel.close();
        assertThat(dispatcher.openChannelCount())
                .as("close() removes the double channel (no leak)").isZero();
        channel.publish(4.0);
        dispatcher.pulse();
        assertThat(drains.get())
                .as("a closed double channel is no longer drained").isEqualTo(1);
    }
}
