package com.benesquivelmusic.daw.core.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimeDisplayModeTest {

    @Test
    void shouldHaveBarsBeatsTicks() {
        assertThat(TimeDisplayMode.valueOf("BARS_BEATS_TICKS"))
                .isEqualTo(TimeDisplayMode.BARS_BEATS_TICKS);
    }

    @Test
    void shouldHaveTime() {
        assertThat(TimeDisplayMode.valueOf("TIME"))
                .isEqualTo(TimeDisplayMode.TIME);
    }

    @Test
    void shouldHaveExactlyTwoValues() {
        assertThat(TimeDisplayMode.values()).hasSize(2);
    }
}
