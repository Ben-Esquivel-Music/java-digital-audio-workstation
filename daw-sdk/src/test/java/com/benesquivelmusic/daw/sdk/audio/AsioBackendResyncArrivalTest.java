package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** When each of the driver's resync messages arrived (story 338, rulings E40, E62, E270 and E279). */
class AsioBackendResyncArrivalTest {

    @Test
    void eachRequestsArrivalIsCountedAndTakenUpOnceInRequestOrder() {
        AsioBackend backend = new AsioBackend();
        assertThat(backend.takeDriverResyncArrivals()).as("no resync yet")
                .isEqualTo(new DriverResyncArrivals(0L, List.of()));

        long before = System.nanoTime();
        backend.noteDriverResync();
        long between = System.nanoTime();
        backend.noteDriverResync();
        long after = System.nanoTime();
        DriverResyncArrivals taken = backend.takeDriverResyncArrivals();

        assertThat(backend.dropoutCounters().driverResyncs()).isEqualTo(2L);
        assertThat(taken.firstRequest()).isZero();
        assertThat(taken.runs()).extracting(DriverResyncArrivals.Run::requests).containsExactly(1L, 1L);
        assertThat(taken.runs().get(0).arrivedNanos()).as("the first's own arrival").isBetween(before, between);
        assertThat(taken.runs().get(1).arrivedNanos()).as("the second's own arrival").isBetween(between, after);
        assertThat(backend.takeDriverResyncArrivals()).as("each is taken up once")
                .isEqualTo(new DriverResyncArrivals(2L, List.of()));
    }
}
