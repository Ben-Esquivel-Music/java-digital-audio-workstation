package com.benesquivelmusic.daw.sdk.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WindowTypeTest {

    @Test
    void shouldHaveThreeWindowTypes() {
        assertThat(WindowType.values()).hasSize(3);
    }

    @Test
    void shouldReturnDisplayNames() {
        assertThat(WindowType.HANN.displayName()).isEqualTo("Hann");
        assertThat(WindowType.HAMMING.displayName()).isEqualTo("Hamming");
        assertThat(WindowType.BLACKMAN_HARRIS.displayName()).isEqualTo("Blackman-Harris");
    }

    @Test
    void shouldResolveFromName() {
        assertThat(WindowType.valueOf("HANN")).isEqualTo(WindowType.HANN);
        assertThat(WindowType.valueOf("HAMMING")).isEqualTo(WindowType.HAMMING);
        assertThat(WindowType.valueOf("BLACKMAN_HARRIS")).isEqualTo(WindowType.BLACKMAN_HARRIS);
    }
}
