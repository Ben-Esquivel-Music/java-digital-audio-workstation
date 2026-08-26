package com.benesquivelmusic.daw.sdk.audio;

import com.benesquivelmusic.daw.sdk.event.DawEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 316 — unit tests for {@link BackendFallbackEvent}, the event the
 * engine publishes once per failed rung of the ASIO &rarr; PortAudio &rarr;
 * Java Sound open ladder so requested &ne; active is always a visible fact.
 */
class BackendFallbackEventTest {

    private static BackendFallbackEvent sample() {
        return new BackendFallbackEvent(
                "ASIO", "Scarlett 18i20",
                "Java Sound", "default",
                "Could not load and initialize ASIO driver: Scarlett 18i20");
    }

    @Test
    void accessorsRoundTripTheComponents() {
        BackendFallbackEvent event = sample();
        assertEquals("ASIO", event.requestedBackend());
        assertEquals("Scarlett 18i20", event.requestedDevice());
        assertEquals("Java Sound", event.activeBackend());
        assertEquals("default", event.activeDevice());
        assertEquals("Could not load and initialize ASIO driver: Scarlett 18i20",
                event.cause());
    }

    @Test
    void rejectsNullComponents() {
        assertThrows(NullPointerException.class, () -> new BackendFallbackEvent(
                null, "dev", "Java Sound", "default", "cause"));
        assertThrows(NullPointerException.class, () -> new BackendFallbackEvent(
                "ASIO", null, "Java Sound", "default", "cause"));
        assertThrows(NullPointerException.class, () -> new BackendFallbackEvent(
                "ASIO", "dev", null, "default", "cause"));
        assertThrows(NullPointerException.class, () -> new BackendFallbackEvent(
                "ASIO", "dev", "Java Sound", null, "cause"));
        assertThrows(NullPointerException.class, () -> new BackendFallbackEvent(
                "ASIO", "dev", "Java Sound", "default", null));
    }

    @Test
    void isADawEvent() {
        assertInstanceOf(DawEvent.class, sample());
        assertTrue(DawEvent.class.isAssignableFrom(BackendFallbackEvent.class));
    }

    @Test
    void dawEventSealedFamilyPermitsBackendFallbackEvent() {
        List<Class<?>> permitted = Arrays.asList(DawEvent.class.getPermittedSubclasses());
        assertTrue(permitted.contains(BackendFallbackEvent.class),
                "DawEvent permits must include BackendFallbackEvent, was: " + permitted);
    }

    @Test
    void timestampInheritsTheEpochDefaultLikeXrunEvent() {
        // Not tied to a wall-clock instant at construction; consumers treat
        // arrival as "now" — the same convention XrunEvent established.
        assertEquals(Instant.EPOCH, sample().timestamp());
    }
}
