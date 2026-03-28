package com.benesquivelmusic.daw.core.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeSignatureChangeEventTest {

    // ── construction and accessors ──────────────────────────────────────────

    @Test
    void shouldCreateEvent() {
        TimeSignatureChangeEvent event = new TimeSignatureChangeEvent(8.0, 3, 4);
        assertThat(event.positionInBeats()).isEqualTo(8.0);
        assertThat(event.numerator()).isEqualTo(3);
        assertThat(event.denominator()).isEqualTo(4);
    }

    @Test
    void shouldCreateEventAtZero() {
        TimeSignatureChangeEvent event = new TimeSignatureChangeEvent(0.0, 4, 4);
        assertThat(event.positionInBeats()).isEqualTo(0.0);
    }

    // ── validation ──────────────────────────────────────────────────────────

    @Test
    void shouldRejectNegativePosition() {
        assertThatThrownBy(() -> new TimeSignatureChangeEvent(-1.0, 4, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroNumerator() {
        assertThatThrownBy(() -> new TimeSignatureChangeEvent(0.0, 0, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeNumerator() {
        assertThatThrownBy(() -> new TimeSignatureChangeEvent(0.0, -1, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroDenominator() {
        assertThatThrownBy(() -> new TimeSignatureChangeEvent(0.0, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeDenominator() {
        assertThatThrownBy(() -> new TimeSignatureChangeEvent(0.0, 4, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── compareTo ───────────────────────────────────────────────────────────

    @Test
    void shouldOrderByPosition() {
        TimeSignatureChangeEvent earlier = new TimeSignatureChangeEvent(4.0, 3, 4);
        TimeSignatureChangeEvent later = new TimeSignatureChangeEvent(8.0, 6, 8);
        assertThat(earlier.compareTo(later)).isNegative();
        assertThat(later.compareTo(earlier)).isPositive();
    }

    @Test
    void shouldReturnZeroForSamePosition() {
        TimeSignatureChangeEvent a = new TimeSignatureChangeEvent(4.0, 3, 4);
        TimeSignatureChangeEvent b = new TimeSignatureChangeEvent(4.0, 6, 8);
        assertThat(a.compareTo(b)).isZero();
    }

    // ── record equality ─────────────────────────────────────────────────────

    @Test
    void shouldBeEqualForSameValues() {
        TimeSignatureChangeEvent a = new TimeSignatureChangeEvent(4.0, 3, 4);
        TimeSignatureChangeEvent b = new TimeSignatureChangeEvent(4.0, 3, 4);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void shouldNotBeEqualForDifferentNumerator() {
        TimeSignatureChangeEvent a = new TimeSignatureChangeEvent(4.0, 3, 4);
        TimeSignatureChangeEvent b = new TimeSignatureChangeEvent(4.0, 4, 4);
        assertThat(a).isNotEqualTo(b);
    }
}
