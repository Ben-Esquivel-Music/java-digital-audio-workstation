package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The driver resync requests an ASIO backend counts and the arrival of each (story 338, rulings E270 and E279):
 * kept on the driver's message thread, taken up by the xrun feed, read by the clock-lock poll.
 */
class ResyncArrivalRingTest {

    private static final int DRIVER_THREADS = 4;
    private static final int REQUESTS_EACH = 20_000;

    @Test
    void eachRequestTakenUpIsDatedAtItsOwnArrivalInRequestOrder() {
        ResyncArrivalRing ring = new ResyncArrivalRing(5L);
        ring.record(100L);
        ring.record(190L);

        assertThat(ring.take()).isEqualTo(new DriverResyncArrivals(0L,
                List.of(new DriverResyncArrivals.Run(100L, 1L), new DriverResyncArrivals.Run(190L, 1L))));
        assertThat(ring.requests()).isEqualTo(2L);
        assertThat(ring.take()).as("nothing since").isEqualTo(new DriverResyncArrivals(2L, List.of()));
    }

    @Test
    void beyondTheFirst256SinceTheLastTakeARequestIsDatedAtThe256th() {
        ResyncArrivalRing ring = new ResyncArrivalRing(5L);
        for (int i = 0; i < 300; i++) {
            ring.record(1_000L + 10L * i);
        }

        DriverResyncArrivals taken = ring.take();

        assertThat(taken.firstRequest()).isZero();
        assertThat(ring.requests()).isEqualTo(300L);
        assertThat(taken.runs()).hasSize(ResyncArrivalRing.KEPT);
        for (int i = 0; i < 255; i++) {
            assertThat(taken.runs().get(i)).as("request %d at its own arrival", i)
                    .isEqualTo(new DriverResyncArrivals.Run(1_000L + 10L * i, 1L));
        }
        assertThat(taken.runs().getLast()).as("the 256th and the 44 after it, at the 256th's arrival")
                .isEqualTo(new DriverResyncArrivals.Run(1_000L + 10L * 255, 45L));
    }

    @Test
    void aTakeFreesTheBoundForTheRequestsAfterIt() {
        ResyncArrivalRing ring = new ResyncArrivalRing(5L);
        for (int i = 0; i < 300; i++) {
            ring.record(1_000L + 10L * i);
        }
        ring.take();
        for (int i = 0; i < 256; i++) {
            ring.record(9_000L + i);
        }

        DriverResyncArrivals taken = ring.take();

        assertThat(taken.firstRequest()).isEqualTo(300L);
        assertThat(taken.runs()).hasSize(256).allSatisfy(run -> assertThat(run.requests()).isEqualTo(1L));
        assertThat(taken.runs().getFirst()).isEqualTo(new DriverResyncArrivals.Run(9_000L, 1L));
        assertThat(taken.runs().getLast()).isEqualTo(new DriverResyncArrivals.Run(9_255L, 1L));
    }

    @Test
    void aRequestsArrivalIsReadUntilItsSlotIsReused() {
        ResyncArrivalRing ring = new ResyncArrivalRing(5L);
        ring.record(100L);
        assertThat(ring.arrivalOf(0L)).hasValue(100L);

        ring.take();
        for (int i = 1; i < 256; i++) {
            ring.record(100L + i);
        }
        assertThat(ring.arrivalOf(0L)).as("taken up, still kept for the poll").hasValue(100L);

        ring.record(999L);
        assertThat(ring.arrivalOf(256L)).hasValue(999L);
        assertThat(ring.arrivalOf(0L)).as("its slot reused").isEmpty();
    }

    @Test
    void aRequestBeyondTheBoundIsNotKept() {
        ResyncArrivalRing ring = new ResyncArrivalRing(5L);
        for (int i = 0; i < 300; i++) {
            ring.record(1_000L + 10L * i);
        }

        assertThat(ring.arrivalOf(255L)).hasValue(1_000L + 10L * 255);
        assertThat(ring.arrivalOf(256L)).isEmpty();
        assertThat(ring.arrivalOf(0L)).as("not overwritten by a request beyond the bound").hasValue(1_000L);
    }

    @Test
    void driverThreadsSendingAtOnceLoseNoRequestAndEachIsTakenUpOnce() throws InterruptedException {
        ResyncArrivalRing ring = new ResyncArrivalRing(System.nanoTime());
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> drivers = new ArrayList<>();
        try {
            for (int i = 0; i < DRIVER_THREADS; i++) {
                drivers.add(Thread.ofPlatform().daemon().name("test-driver-" + i).start(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int request = 0; request < REQUESTS_EACH; request++) {
                        ring.record(System.nanoTime());
                    }
                }));
            }
        } finally {
            go.countDown();
        }
        long takenUp = 0L;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() - deadline < 0L) {
            Thread alive = drivers.stream().filter(Thread::isAlive).findFirst().orElse(null);
            if (alive == null) {
                break;
            }
            takenUp += ring.take().requests();
            alive.join(1);
        }
        assertThat(drivers).as("every driver thread finished").noneMatch(Thread::isAlive);
        takenUp += ring.take().requests();

        assertThat(takenUp).isEqualTo((long) DRIVER_THREADS * REQUESTS_EACH);
        assertThat(ring.requests()).isEqualTo((long) DRIVER_THREADS * REQUESTS_EACH);
    }
}
