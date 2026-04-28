package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link BufferSizeRange} — verifies the four-tuple invariants,
 * the ASIO-style expansion of granular ranges into discrete dropdown
 * menus, and the {@link BufferSizeRange#singleton(int)} convenience
 * factory used by JACK and WASAPI shared mode.
 */
class BufferSizeRangeTest {

    @Test
    void shouldExpandGranularRangeAsAsioDriverWould() {
        // Story 213's canonical example: a fake backend exposing
        // BufferSizeRange(96, 384, 192, 96) produces a dropdown of
        // {96, 192, 288, 384}.
        BufferSizeRange range = new BufferSizeRange(96, 384, 192, 96);
        assertThat(range.expandedSizes()).containsExactly(96, 192, 288, 384);
        assertThat(range.accepts(96)).isTrue();
        assertThat(range.accepts(192)).isTrue();
        assertThat(range.accepts(288)).isTrue();
        assertThat(range.accepts(384)).isTrue();
        assertThat(range.accepts(128)).isFalse();
        assertThat(range.accepts(95)).isFalse();
        assertThat(range.accepts(385)).isFalse();
    }

    @Test
    void shouldExpandGranularRangeWithStepIncrements() {
        BufferSizeRange range = new BufferSizeRange(64, 512, 128, 64);
        assertThat(range.expandedSizes()).containsExactly(64, 128, 192, 256, 320, 384, 448, 512);
    }

    @Test
    void singletonShouldReturnSingleEntryForJackAndWasapiShared() {
        BufferSizeRange range = BufferSizeRange.singleton(1024);
        assertThat(range.min()).isEqualTo(1024);
        assertThat(range.max()).isEqualTo(1024);
        assertThat(range.preferred()).isEqualTo(1024);
        assertThat(range.granularity()).isZero();
        assertThat(range.expandedSizes()).containsExactly(1024);
        assertThat(range.accepts(1024)).isTrue();
        assertThat(range.accepts(512)).isFalse();
    }

    @Test
    void shouldIncludeMaxEvenWhenNotMultipleOfGranularity() {
        // Some drivers report ranges where max is not exactly min + N*granularity.
        // The expanded dropdown should still let the user pick max so they can
        // hit the highest buffer the driver accepts.
        BufferSizeRange range = new BufferSizeRange(100, 250, 200, 100);
        assertThat(range.expandedSizes()).containsExactly(100, 200, 250);
        // accepts() must agree with expandedSizes() — max is accepted
        // even when it is not on the regular granularity ladder.
        assertThat(range.accepts(250)).isTrue();
    }

    @Test
    void defaultRangeShouldExpandToHistoricalPowerOfTwoLadder() {
        // The DEFAULT_RANGE must return exactly the historical power-of-two
        // menu {32, 64, 128, 256, 512, 1024, 2048} so that:
        //  (a) persisted settings keep working
        //  (b) BufferSize.fromFrames() does not throw for dropdown values
        assertThat(BufferSizeRange.DEFAULT_RANGE.expandedSizes())
                .containsExactly(32, 64, 128, 256, 512, 1024, 2048);
        // accepts() must agree with the expanded list.
        assertThat(BufferSizeRange.DEFAULT_RANGE.accepts(128)).isTrue();
        assertThat(BufferSizeRange.DEFAULT_RANGE.accepts(96)).isFalse();
    }

    @Test
    void shouldRejectInvalidConstructorArguments() {
        assertThatThrownBy(() -> new BufferSizeRange(0, 100, 50, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min must be positive");
        assertThatThrownBy(() -> new BufferSizeRange(100, 50, 50, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max");
        assertThatThrownBy(() -> new BufferSizeRange(100, 200, 50, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preferred");
        assertThatThrownBy(() -> new BufferSizeRange(100, 200, 150, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("granularity");
    }
}
