package com.benesquivelmusic.daw.core.mixer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SendModeTest {

    @Test
    void shouldHavePreFaderValue() {
        assertThat(SendMode.PRE_FADER.name()).isEqualTo("PRE_FADER");
    }

    @Test
    void shouldHavePostFaderValue() {
        assertThat(SendMode.POST_FADER.name()).isEqualTo("POST_FADER");
    }

    @Test
    void shouldHaveTwoValues() {
        assertThat(SendMode.values()).hasSize(2);
    }
}
