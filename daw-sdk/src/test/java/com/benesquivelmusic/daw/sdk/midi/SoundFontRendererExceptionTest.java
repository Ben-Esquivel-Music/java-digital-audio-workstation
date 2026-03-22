package com.benesquivelmusic.daw.sdk.midi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SoundFontRendererExceptionTest {

    @Test
    void shouldCreateWithMessage() {
        SoundFontRendererException ex = new SoundFontRendererException("test error");
        assertThat(ex.getMessage()).isEqualTo("test error");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void shouldCreateWithMessageAndCause() {
        RuntimeException cause = new RuntimeException("root cause");
        SoundFontRendererException ex = new SoundFontRendererException("test error", cause);
        assertThat(ex.getMessage()).isEqualTo("test error");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void shouldBeRuntimeException() {
        assertThat(new SoundFontRendererException("test"))
                .isInstanceOf(RuntimeException.class);
    }
}
