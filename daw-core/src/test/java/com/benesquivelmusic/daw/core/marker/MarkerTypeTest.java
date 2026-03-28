package com.benesquivelmusic.daw.core.marker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkerTypeTest {

    @Test
    void shouldHaveThreeValues() {
        assertThat(MarkerType.values()).hasSize(3);
    }

    @Test
    void shouldReturnDefaultColorForSection() {
        assertThat(MarkerType.SECTION.getDefaultColor()).isEqualTo("#3498DB");
    }

    @Test
    void shouldReturnDefaultColorForRehearsal() {
        assertThat(MarkerType.REHEARSAL.getDefaultColor()).isEqualTo("#E67E22");
    }

    @Test
    void shouldReturnDefaultColorForArrangement() {
        assertThat(MarkerType.ARRANGEMENT.getDefaultColor()).isEqualTo("#2ECC71");
    }

    @Test
    void shouldResolveFromName() {
        assertThat(MarkerType.valueOf("SECTION")).isEqualTo(MarkerType.SECTION);
        assertThat(MarkerType.valueOf("REHEARSAL")).isEqualTo(MarkerType.REHEARSAL);
        assertThat(MarkerType.valueOf("ARRANGEMENT")).isEqualTo(MarkerType.ARRANGEMENT);
    }
}
