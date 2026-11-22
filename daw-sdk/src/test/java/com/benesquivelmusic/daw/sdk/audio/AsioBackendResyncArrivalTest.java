package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/** When the driver's newest resync message arrived (story 338, rulings E40 and E62). */
class AsioBackendResyncArrivalTest {

    @Test
    void theNewestResyncsArrivalIsKnownOnceOneArrived() throws InterruptedException {
        AsioBackend backend = new AsioBackend();
        assertThat(backend.newestDriverResyncNanos()).as("no resync yet").isEmpty();

        long before = System.nanoTime();
        backend.noteDriverResync();
        OptionalLong first = backend.newestDriverResyncNanos();
        assertThat(first).as("the arrival of the first").isPresent();
        assertThat(first.getAsLong()).isBetween(before, System.nanoTime());

        Thread.sleep(2);
        backend.noteDriverResync();
        assertThat(backend.newestDriverResyncNanos().getAsLong()).as("the newest one's")
                .isGreaterThan(first.getAsLong());
        assertThat(backend.dropoutCounters().driverResyncs()).isEqualTo(2L);
    }
}
