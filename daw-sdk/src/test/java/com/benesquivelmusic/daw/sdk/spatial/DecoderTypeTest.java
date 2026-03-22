package com.benesquivelmusic.daw.sdk.spatial;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecoderTypeTest {

    @Test
    void shouldHaveThreeTypes() {
        assertThat(DecoderType.values()).hasSize(3);
    }

    @Test
    void shouldContainExpectedTypes() {
        assertThat(DecoderType.values()).containsExactly(
                DecoderType.BASIC, DecoderType.MAX_RE, DecoderType.IN_PHASE);
    }
}
