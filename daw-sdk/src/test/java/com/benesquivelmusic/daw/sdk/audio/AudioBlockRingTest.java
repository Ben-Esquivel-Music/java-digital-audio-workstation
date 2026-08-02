package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AudioBlockRing} — the lock-free SPSC hand-off used in
 * both directions by the ASIO streaming bridge (story 311): render thread
 * &rarr; buffer-switch callback for playback, and buffer-switch callback
 * &rarr; {@code asio-input-drain} for capture.
 */
class AudioBlockRingTest {

    /** Bounds every concurrent wait so a regression fails instead of hanging. */
    private static final long DEADLINE_SECONDS = 30;

    @Test
    void capacityIsRoundedUpToThePowerOfTwo() {
        assertThat(new AudioBlockRing(1, 4).capacity()).isEqualTo(1);
        assertThat(new AudioBlockRing(3, 4).capacity()).isEqualTo(4);
        assertThat(new AudioBlockRing(5, 4).capacity()).isEqualTo(8);
        assertThat(new AudioBlockRing(8, 4).capacity()).isEqualTo(8);
    }

    @Test
    void rejectsNonPositiveGeometry() {
        assertThatThrownBy(() -> new AudioBlockRing(0, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
        assertThatThrownBy(() -> new AudioBlockRing(4, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplesPerBlock");
    }

    @Test
    void drainOnAnEmptyRingReturnsFalseAndLeavesDestinationUntouched() {
        AudioBlockRing ring = new AudioBlockRing(4, 4);
        float[] destination = {9f, 9f, 9f, 9f};

        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.drainLatestInto(destination)).isFalse();
        assertThat(ring.readInto(destination)).isFalse();
        assertThat(destination).containsExactly(9f, 9f, 9f, 9f);
    }

    @Test
    void writeThenDrainRoundTripsTheSamples() {
        AudioBlockRing ring = new AudioBlockRing(4, 4);

        assertThat(ring.write(new float[] {0.1f, 0.2f, 0.3f, 0.4f}, 4)).isTrue();
        assertThat(ring.isEmpty()).isFalse();

        float[] destination = new float[4];
        assertThat(ring.drainLatestInto(destination)).isTrue();
        assertThat(destination).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(ring.isEmpty()).isTrue();
    }

    @Test
    void drainConsumesEveryPendingSlotAndYieldsTheMostRecentBlock() {
        AudioBlockRing ring = new AudioBlockRing(4, 2);
        ring.write(new float[] {1f, 1f}, 2);
        ring.write(new float[] {2f, 2f}, 2);
        ring.write(new float[] {3f, 3f}, 2);

        assertThat(ring.size()).isEqualTo(3);
        float[] destination = new float[2];
        assertThat(ring.drainLatestInto(destination)).isTrue();
        assertThat(destination).containsExactly(3f, 3f);
        // All three slots were consumed, not just the newest one.
        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.drainLatestInto(destination)).isFalse();
    }

    /**
     * The capture direction must never skip a block: {@code readInto} is the
     * in-order consumer the {@code asio-input-drain} thread uses.
     */
    @Test
    void readIntoConsumesExactlyOneSlotPerCallOldestFirst() {
        AudioBlockRing ring = new AudioBlockRing(4, 2);
        ring.write(new float[] {1f, 1f}, 2);
        ring.write(new float[] {2f, 2f}, 2);
        ring.write(new float[] {3f, 3f}, 2);

        float[] destination = new float[2];
        assertThat(ring.readInto(destination)).isTrue();
        assertThat(destination).containsExactly(1f, 1f);
        assertThat(ring.size()).isEqualTo(2);

        assertThat(ring.readInto(destination)).isTrue();
        assertThat(destination).containsExactly(2f, 2f);

        assertThat(ring.readInto(destination)).isTrue();
        assertThat(destination).containsExactly(3f, 3f);

        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.readInto(destination)).isFalse();
    }

    @Test
    void readIntoZeroPadsAndTruncatesLikeDrainLatest() {
        AudioBlockRing ring = new AudioBlockRing(2, 2);
        ring.write(new float[] {1f, 2f}, 2);

        float[] longer = new float[4];
        assertThat(ring.readInto(longer)).isTrue();
        assertThat(longer).containsExactly(1f, 2f, 0f, 0f);

        ring.write(new float[] {3f, 4f}, 2);
        float[] shorter = new float[1];
        assertThat(ring.readInto(shorter)).isTrue();
        assertThat(shorter).containsExactly(3f);
    }

    @Test
    void writeDropsRatherThanBlocksWhenFull() {
        AudioBlockRing ring = new AudioBlockRing(2, 1);

        assertThat(ring.write(new float[] {1f}, 1)).isTrue();
        assertThat(ring.write(new float[] {2f}, 1)).isTrue();
        assertThat(ring.write(new float[] {3f}, 1))
                .as("third write into a 2-slot ring must be dropped, not blocked")
                .isFalse();

        float[] destination = new float[1];
        assertThat(ring.drainLatestInto(destination)).isTrue();
        assertThat(destination)
                .as("the newest block is dropped on overflow; the queued ones survive")
                .containsExactly(2f);
    }

    @Test
    void shorterSourceIsZeroPaddedAndLongerSourceIsTruncated() {
        AudioBlockRing ring = new AudioBlockRing(2, 4);

        ring.write(new float[] {1f, 2f}, 2);
        float[] destination = new float[4];
        assertThat(ring.drainLatestInto(destination)).isTrue();
        assertThat(destination).containsExactly(1f, 2f, 0f, 0f);

        ring.write(new float[] {5f, 6f, 7f, 8f, 9f}, 5);
        assertThat(ring.drainLatestInto(destination)).isTrue();
        assertThat(destination).containsExactly(5f, 6f, 7f, 8f);
    }

    @Test
    void slotsAreReusedAfterDrainSoTheRingNeverPermanentlyFills() {
        AudioBlockRing ring = new AudioBlockRing(2, 1);
        float[] destination = new float[1];

        for (int i = 1; i <= 10; i++) {
            assertThat(ring.write(new float[] {i}, 1)).isTrue();
            assertThat(ring.drainLatestInto(destination)).isTrue();
            assertThat(destination[0]).isEqualTo(i);
        }
    }

    @Test
    void nullArgumentsDegradeInsteadOfThrowingOnTheRealTimeThread() {
        AudioBlockRing ring = new AudioBlockRing(2, 2);
        assertThat(ring.write(null, 2)).isFalse();
        assertThat(ring.drainLatestInto(null)).isFalse();
        assertThat(ring.readInto(null)).isFalse();
    }

    @Test
    void toStringDescribesTheGeometry() {
        assertThat(new AudioBlockRing(3, 8))
                .hasToString("AudioBlockRing[capacity=4, samplesPerBlock=8]");
    }

    // ------------------------------------------------------------------
    // Concurrency — the single property the class exists for
    // ------------------------------------------------------------------

    /**
     * The capture path's real contract: one producer thread and one consumer
     * thread, no lock, no torn slot, no reordering, no lost block.
     *
     * <p>The producer retries on full so nothing is legitimately dropped, which
     * makes the expected consumer output exactly {@code 1..N} in order. Each
     * block is filled with a single distinct value, so a torn slot (the
     * consumer reading a slot the producer is still filling) shows up as a
     * block whose elements are not all equal.</p>
     */
    @Test
    void inOrderConsumerSeesEveryBlockExactlyOnceUnderConcurrentProduction()
            throws Exception {
        int blocks = 20_000;
        int samplesPerBlock = 64;
        AudioBlockRing ring = new AudioBlockRing(8, samplesPerBlock);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);

        CountDownLatch started = new CountDownLatch(2);
        AtomicReference<String> producerFailure = new AtomicReference<>();
        List<Float> consumed = new ArrayList<>(blocks);
        AtomicReference<String> tornSlot = new AtomicReference<>();

        Thread producer = new Thread(() -> {
            started.countDown();
            float[] source = new float[samplesPerBlock];
            for (int i = 1; i <= blocks; i++) {
                java.util.Arrays.fill(source, i);
                while (!ring.write(source, samplesPerBlock)) {
                    if (System.nanoTime() > deadline) {
                        producerFailure.set("producer stalled at block " + i);
                        return;
                    }
                    Thread.onSpinWait();
                }
            }
        }, "ring-producer");

        Thread consumer = new Thread(() -> {
            started.countDown();
            float[] destination = new float[samplesPerBlock];
            while (consumed.size() < blocks && System.nanoTime() < deadline) {
                if (!ring.readInto(destination)) {
                    Thread.onSpinWait();
                    continue;
                }
                float first = destination[0];
                for (int s = 1; s < samplesPerBlock; s++) {
                    if (destination[s] != first) {
                        tornSlot.compareAndSet(null,
                                "torn slot at block " + consumed.size()
                                        + ": [0]=" + first + " [" + s + "]="
                                        + destination[s]);
                    }
                }
                consumed.add(first);
            }
        }, "ring-consumer");

        producer.start();
        consumer.start();
        assertThat(started.await(DEADLINE_SECONDS, TimeUnit.SECONDS)).isTrue();
        producer.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS));
        consumer.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS));

        assertThat(producer.isAlive()).as("producer must have finished").isFalse();
        assertThat(consumer.isAlive()).as("consumer must have finished").isFalse();
        assertThat(producerFailure.get()).isNull();
        assertThat(tornSlot.get()).isNull();
        assertThat(consumed).hasSize(blocks);
        for (int i = 0; i < blocks; i++) {
            assertThat(consumed.get(i))
                    .as("block %d must arrive in order and intact", i)
                    .isEqualTo((float) (i + 1));
        }
    }

    /**
     * The playback path's contract: the latest-wins consumer may skip blocks
     * (that is the point) but must never observe a torn slot or a value that
     * moves backwards.
     */
    @Test
    void latestWinsConsumerNeverSeesATornOrOutOfOrderSlot() throws Exception {
        int blocks = 20_000;
        int samplesPerBlock = 64;
        AudioBlockRing ring = new AudioBlockRing(4, samplesPerBlock);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);

        AtomicReference<String> violation = new AtomicReference<>();
        CountDownLatch producerDone = new CountDownLatch(1);

        Thread producer = new Thread(() -> {
            float[] source = new float[samplesPerBlock];
            for (int i = 1; i <= blocks; i++) {
                java.util.Arrays.fill(source, i);
                // Latest-wins tolerates drops, so no retry loop here.
                ring.write(source, samplesPerBlock);
            }
            producerDone.countDown();
        }, "ring-producer-latest");

        Thread consumer = new Thread(() -> {
            float[] destination = new float[samplesPerBlock];
            float previous = 0f;
            while (System.nanoTime() < deadline) {
                if (producerDone.getCount() == 0 && ring.isEmpty()) {
                    return;
                }
                if (!ring.drainLatestInto(destination)) {
                    Thread.onSpinWait();
                    continue;
                }
                float first = destination[0];
                for (int s = 1; s < samplesPerBlock; s++) {
                    if (destination[s] != first) {
                        violation.compareAndSet(null, "torn slot: [0]=" + first
                                + " [" + s + "]=" + destination[s]);
                    }
                }
                if (first < previous) {
                    violation.compareAndSet(null,
                            "out-of-order: " + first + " after " + previous);
                }
                previous = first;
            }
        }, "ring-consumer-latest");

        producer.start();
        consumer.start();
        producer.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS));
        consumer.join(TimeUnit.SECONDS.toMillis(DEADLINE_SECONDS));

        assertThat(producer.isAlive()).as("producer must have finished").isFalse();
        assertThat(consumer.isAlive()).as("consumer must have finished").isFalse();
        assertThat(violation.get()).isNull();
    }
}
