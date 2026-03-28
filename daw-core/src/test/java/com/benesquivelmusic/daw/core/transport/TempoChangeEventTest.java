package com.benesquivelmusic.daw.core.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TempoChangeEventTest {

    // ── construction and accessors ──────────────────────────────────────────

    @Test
    void shouldCreateInstantEvent() {
        TempoChangeEvent event = TempoChangeEvent.instant(8.0, 140.0);
        assertThat(event.positionInBeats()).isEqualTo(8.0);
        assertThat(event.bpm()).isEqualTo(140.0);
        assertThat(event.transitionType()).isEqualTo(TempoTransitionType.INSTANT);
    }

    @Test
    void shouldCreateLinearEvent() {
        TempoChangeEvent event = TempoChangeEvent.linear(16.0, 160.0);
        assertThat(event.positionInBeats()).isEqualTo(16.0);
        assertThat(event.bpm()).isEqualTo(160.0);
        assertThat(event.transitionType()).isEqualTo(TempoTransitionType.LINEAR);
    }

    @Test
    void shouldCreateCurvedEvent() {
        TempoChangeEvent event = TempoChangeEvent.curved(32.0, 90.0);
        assertThat(event.positionInBeats()).isEqualTo(32.0);
        assertThat(event.bpm()).isEqualTo(90.0);
        assertThat(event.transitionType()).isEqualTo(TempoTransitionType.CURVED);
    }

    @Test
    void shouldCreateEventWithConstructor() {
        TempoChangeEvent event = new TempoChangeEvent(4.0, 100.0, TempoTransitionType.LINEAR);
        assertThat(event.positionInBeats()).isEqualTo(4.0);
        assertThat(event.bpm()).isEqualTo(100.0);
        assertThat(event.transitionType()).isEqualTo(TempoTransitionType.LINEAR);
    }

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    void shouldRejectNegativePosition() {
        assertThatThrownBy(() -> TempoChangeEvent.instant(-1.0, 120.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBpmBelowMinimum() {
        assertThatThrownBy(() -> TempoChangeEvent.instant(0.0, 19.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBpmAboveMaximum() {
        assertThatThrownBy(() -> TempoChangeEvent.instant(0.0, 1000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullTransitionType() {
        assertThatThrownBy(() -> new TempoChangeEvent(0.0, 120.0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldAcceptMinimumBpm() {
        TempoChangeEvent event = TempoChangeEvent.instant(0.0, 20.0);
        assertThat(event.bpm()).isEqualTo(20.0);
    }

    @Test
    void shouldAcceptMaximumBpm() {
        TempoChangeEvent event = TempoChangeEvent.instant(0.0, 999.0);
        assertThat(event.bpm()).isEqualTo(999.0);
    }

    @Test
    void shouldAcceptZeroPosition() {
        TempoChangeEvent event = TempoChangeEvent.instant(0.0, 120.0);
        assertThat(event.positionInBeats()).isEqualTo(0.0);
    }

    // ── compareTo ───────────────────────────────────────────────────────────

    @Test
    void shouldOrderByPosition() {
        TempoChangeEvent earlier = TempoChangeEvent.instant(4.0, 120.0);
        TempoChangeEvent later = TempoChangeEvent.instant(8.0, 140.0);
        assertThat(earlier.compareTo(later)).isNegative();
        assertThat(later.compareTo(earlier)).isPositive();
    }

    @Test
    void shouldReturnZeroForSamePosition() {
        TempoChangeEvent a = TempoChangeEvent.instant(4.0, 120.0);
        TempoChangeEvent b = TempoChangeEvent.linear(4.0, 140.0);
        assertThat(a.compareTo(b)).isZero();
    }

    // ── record equality ─────────────────────────────────────────────────────

    @Test
    void shouldBeEqualForSameValues() {
        TempoChangeEvent a = TempoChangeEvent.instant(4.0, 120.0);
        TempoChangeEvent b = TempoChangeEvent.instant(4.0, 120.0);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void shouldNotBeEqualForDifferentBpm() {
        TempoChangeEvent a = TempoChangeEvent.instant(4.0, 120.0);
        TempoChangeEvent b = TempoChangeEvent.instant(4.0, 140.0);
        assertThat(a).isNotEqualTo(b);
    }
}
