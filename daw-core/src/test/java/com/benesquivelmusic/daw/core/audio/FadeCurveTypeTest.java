package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FadeCurveTypeTest {

    @Test
    void shouldContainAllExpectedValues() {
        assertThat(FadeCurveType.values()).containsExactly(
                FadeCurveType.LINEAR,
                FadeCurveType.EQUAL_POWER,
                FadeCurveType.S_CURVE
        );
    }

    @Test
    void shouldResolveFromName() {
        assertThat(FadeCurveType.valueOf("LINEAR")).isEqualTo(FadeCurveType.LINEAR);
        assertThat(FadeCurveType.valueOf("EQUAL_POWER")).isEqualTo(FadeCurveType.EQUAL_POWER);
        assertThat(FadeCurveType.valueOf("S_CURVE")).isEqualTo(FadeCurveType.S_CURVE);
    }
}
