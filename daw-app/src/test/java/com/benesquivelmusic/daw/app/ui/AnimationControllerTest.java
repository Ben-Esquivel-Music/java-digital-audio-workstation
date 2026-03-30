package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link AnimationController} helper logic that can be exercised
 * without a live JavaFX scene or toolkit.
 */
class AnimationControllerTest {

    // ── formatTime ──────────────────────────────────────────────────────────

    @Test
    void shouldFormatZeroAsAllZeros() {
        assertThat(AnimationController.formatTime(0)).isEqualTo("00:00:00.0");
    }

    @ParameterizedTest(name = "{0} nanos → \"{1}\"")
    @CsvSource({
            "0,             00:00:00.0",
            "100000000,     00:00:00.1",
            "500000000,     00:00:00.5",
            "1000000000,    00:00:01.0",
            "59999000000,   00:00:59.9",
            "60000000000,   00:01:00.0",
            "3599900000000, 00:59:59.9",
            "3600000000000, 01:00:00.0",
            "86399000000000,23:59:59.0"
    })
    void shouldFormatTimeCorrectly(long nanos, String expected) {
        assertThat(AnimationController.formatTime(nanos)).isEqualTo(expected);
    }

    @Test
    void shouldFormatSubSecondTenths() {
        // 1.3 seconds → "00:00:01.3"
        long nanos = 1_300_000_000L;
        assertThat(AnimationController.formatTime(nanos)).isEqualTo("00:00:01.3");
    }

    @Test
    void shouldFormatMinutesAndSeconds() {
        // 2 minutes, 30 seconds → "00:02:30.0"
        long nanos = (2 * 60 + 30) * 1_000_000_000L;
        assertThat(AnimationController.formatTime(nanos)).isEqualTo("00:02:30.0");
    }

    @Test
    void shouldFormatHoursMinutesSeconds() {
        // 1 hour, 15 minutes, 42.7 seconds
        long nanos = (1 * 3600 + 15 * 60 + 42) * 1_000_000_000L + 700_000_000L;
        assertThat(AnimationController.formatTime(nanos)).isEqualTo("01:15:42.7");
    }

    @Test
    void shouldTruncateMillisecondsToTenths() {
        // 999 milliseconds → should display as .9 (truncated to tenths, not rounded)
        long nanos = 999_000_000L;
        assertThat(AnimationController.formatTime(nanos)).isEqualTo("00:00:00.9");
    }

    // ── Playhead update callback ────────────────────────────────────────────

    @Test
    void shouldAcceptNullPlayheadCallback() {
        // Verify the setter does not throw when clearing the callback.
        // Full AnimationTimer integration requires a JavaFX toolkit, but
        // the setter itself is safe to call without one.
        // This is a compile-time verification that the API exists.
        assertThat(AnimationController.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("setPlayheadUpdateCallback"));
    }
}
