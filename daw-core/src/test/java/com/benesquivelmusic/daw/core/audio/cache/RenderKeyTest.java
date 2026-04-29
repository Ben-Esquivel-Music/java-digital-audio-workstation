package com.benesquivelmusic.daw.core.audio.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderKeyTest {

    private static final String VALID_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void shouldAcceptValidValues() {
        RenderKey key = new RenderKey(VALID_HASH, 48_000, 32);

        assertThat(key.trackDspHash()).isEqualTo(VALID_HASH);
        assertThat(key.sessionSampleRate()).isEqualTo(48_000);
        assertThat(key.bitDepth()).isEqualTo(32);
        assertThat(key.hashPrefix()).isEqualTo("01");
        assertThat(key.toFileName())
                .isEqualTo(VALID_HASH + "_48000_32.pcm");
    }

    @Test
    void shouldRejectShortHash() {
        assertThatThrownBy(() -> new RenderKey("abc", 48_000, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64-character");
    }

    @Test
    void shouldRejectUppercaseHash() {
        assertThatThrownBy(() -> new RenderKey(VALID_HASH.toUpperCase(), 48_000, 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveSampleRate() {
        assertThatThrownBy(() -> new RenderKey(VALID_HASH, 0, 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveBitDepth() {
        assertThatThrownBy(() -> new RenderKey(VALID_HASH, 48_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void differentRatesAndDepthsProduceDifferentFileNames() {
        RenderKey a = new RenderKey(VALID_HASH, 44_100, 16);
        RenderKey b = new RenderKey(VALID_HASH, 48_000, 16);
        RenderKey c = new RenderKey(VALID_HASH, 44_100, 24);

        assertThat(a.toFileName()).isNotEqualTo(b.toFileName());
        assertThat(a.toFileName()).isNotEqualTo(c.toFileName());
    }
}
