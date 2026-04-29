package com.benesquivelmusic.daw.sdk.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportEventTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void startedRejectsNegativePosition() {
        assertThatThrownBy(() -> new TransportEvent.Started(-1L, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startedRejectsNullTimestamp() {
        assertThatThrownBy(() -> new TransportEvent.Started(0L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void seekedCarriesFromAndTo() {
        TransportEvent.Seeked s = new TransportEvent.Seeked(10L, 100L, T0);
        assertThat(s.fromFrames()).isEqualTo(10L);
        assertThat(s.toFrames()).isEqualTo(100L);
        assertThat(s.timestamp()).isEqualTo(T0);
    }

    @Test
    void tempoChangedRejectsNonPositive() {
        assertThatThrownBy(() -> new TransportEvent.TempoChanged(0.0, 120.0, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tempoChangedRejectsNonFinite() {
        assertThatThrownBy(() -> new TransportEvent.TempoChanged(120.0, Double.NaN, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransportEvent.TempoChanged(120.0, Double.POSITIVE_INFINITY, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loopChangedRejectsEndBeforeStart() {
        assertThatThrownBy(() -> new TransportEvent.LoopChanged(true, 100L, 50L, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loopChangedAllowsEqualEndpoints() {
        TransportEvent.LoopChanged e = new TransportEvent.LoopChanged(false, 50L, 50L, T0);
        assertThat(e.startFrames()).isEqualTo(e.endFrames());
    }
}
