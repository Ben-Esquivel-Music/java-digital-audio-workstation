package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure-logic parts of {@link TimeTickerAnimator}.
 * Methods that interact with a JavaFX {@link javafx.scene.control.Label}
 * remain covered by the existing toolkit-bound integration tests; the
 * format helper is tested here in isolation.
 */
class TimeTickerAnimatorTest {

    @Test
    void zeroNanosFormatsAsAllZeros() {
        assertThat(TimeTickerAnimator.formatTime(0)).isEqualTo("00:00:00.0");
    }

    @Test
    void oneSecondFormatsCorrectly() {
        assertThat(TimeTickerAnimator.formatTime(1_000_000_000L)).isEqualTo("00:00:01.0");
    }

    @Test
    void hourBoundaryFormatsCorrectly() {
        assertThat(TimeTickerAnimator.formatTime(3_600_000_000_000L)).isEqualTo("01:00:00.0");
    }

    @Test
    void tenthsAreTruncatedNotRounded() {
        // 999 ms → ".9" (truncated)
        assertThat(TimeTickerAnimator.formatTime(999_000_000L)).isEqualTo("00:00:00.9");
    }
}
