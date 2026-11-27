package com.benesquivelmusic.daw.core.audio.health;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The publication order of one lane's descriptor ring under a live drain
 * (story 337, rulings E19, E21 and E22): the owner writes a slot's fields and
 * only then publishes the slot with a release store of {@code tail}, and the
 * drain copies a slot before it releases it through {@code head}. A writer and
 * a drain racing on two threads must therefore never yield a sample whose
 * throwable or subject belongs to a different record than its detail.
 *
 * <p>A memory-ordering fault cannot be forced deterministically, so the two
 * sides race for a bounded time instead. With the order the ring documents
 * this test cannot fail; with {@code tail} published before the slot's field
 * stores it failed within a few hundred milliseconds on an x86-64 Windows
 * host, so it is a probabilistic guard, not a proof.</p>
 */
class AudioHealthLanePublicationOrderTest {

    private static final long RACE_MILLIS = 1_000L;
    private static final Duration JOIN_BUDGET = Duration.ofSeconds(10);
    private static final int DISTINCT = 64;
    private static final long MIN_DRAINED = 10_000L;

    @Test
    void aDrainRacingTheWriterNeverSeesAHalfWrittenSlot() throws Exception {
        Throwable[] failures = new Throwable[DISTINCT];
        String[] subjects = new String[DISTINCT];
        for (int i = 0; i < DISTINCT; i++) {
            failures[i] = new IllegalStateException("failure " + i);
            subjects[i] = "subject " + i;
        }
        AudioHealthLane lane = new AudioHealthRecord().claimLane(LaneRole.RENDER_DRIVE);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong drained = new AtomicLong();
        CountDownLatch enoughDrained = new CountDownLatch(1);
        AtomicLong torn = new AtomicLong();
        AtomicReference<AudioFailureSample> firstTorn = new AtomicReference<>();

        Thread writer = Thread.ofPlatform().name("lane-writer").daemon(true).start(() -> {
            int detail = 0;
            while (!stop.get()) {
                int index = detail % DISTINCT;
                long lost = lane.lostRecords();
                lane.record(AudioFailureCodes.RENDER, failures[index], subjects[index], detail);
                detail = (detail + 1) & Integer.MAX_VALUE;
                // On a shared CPU a full ring would otherwise hold the drain off for the writer's whole time slice.
                if (lane.lostRecords() != lost) {
                    Thread.yield();
                }
            }
        });
        Thread drain = Thread.ofPlatform().name("lane-drain").daemon(true).start(() -> {
            while (!stop.get()) {
                boolean took = lane.poll(sample -> {
                    if (drained.incrementAndGet() == MIN_DRAINED) {
                        enoughDrained.countDown();
                    }
                    int index = Math.floorMod(sample.detail(), DISTINCT);
                    boolean whole = sample.failure().filter(failure -> failure == failures[index]).isPresent()
                            && sample.subject().filter(subject -> subject == subjects[index]).isPresent();
                    if (!whole && torn.getAndIncrement() == 0L) {
                        firstTorn.set(sample);
                    }
                });
                if (!took) {
                    Thread.yield();
                }
            }
        });

        try {
            Thread.sleep(RACE_MILLIS);
            assertThat(enoughDrained.await(JOIN_BUDGET.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the drain took %d samples from the writer", MIN_DRAINED)
                    .isTrue();
        } finally {
            stop.set(true);
        }

        assertThat(writer.join(JOIN_BUDGET)).as("the writer stopped").isTrue();
        assertThat(drain.join(JOIN_BUDGET)).as("the drain stopped").isTrue();
        assertThat(torn.get())
                .as("samples whose throwable or subject is not their record's; the first: %s", firstTorn.get())
                .isZero();
    }
}
