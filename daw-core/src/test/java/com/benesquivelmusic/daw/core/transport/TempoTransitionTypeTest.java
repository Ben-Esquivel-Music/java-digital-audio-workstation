package com.benesquivelmusic.daw.core.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TempoTransitionTypeTest {

    @Test
    void shouldHaveInstant() {
        assertThat(TempoTransitionType.valueOf("INSTANT"))
                .isEqualTo(TempoTransitionType.INSTANT);
    }

    @Test
    void shouldHaveLinear() {
        assertThat(TempoTransitionType.valueOf("LINEAR"))
                .isEqualTo(TempoTransitionType.LINEAR);
    }

    @Test
    void shouldHaveCurved() {
        assertThat(TempoTransitionType.valueOf("CURVED"))
                .isEqualTo(TempoTransitionType.CURVED);
    }

    @Test
    void shouldHaveExactlyThreeValues() {
        assertThat(TempoTransitionType.values()).hasSize(3);
    }
}
