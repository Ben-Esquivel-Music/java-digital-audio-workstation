package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StretchQualityTest {

    @Test
    void shouldHaveThreeValues() {
        assertThat(StretchQuality.values()).hasSize(3);
    }

    @Test
    void shouldContainExpectedValues() {
        assertThat(StretchQuality.valueOf("LOW")).isEqualTo(StretchQuality.LOW);
        assertThat(StretchQuality.valueOf("MEDIUM")).isEqualTo(StretchQuality.MEDIUM);
        assertThat(StretchQuality.valueOf("HIGH")).isEqualTo(StretchQuality.HIGH);
    }
}
