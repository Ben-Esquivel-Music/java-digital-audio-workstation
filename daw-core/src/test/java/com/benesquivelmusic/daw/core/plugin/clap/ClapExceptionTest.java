package com.benesquivelmusic.daw.core.plugin.clap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClapExceptionTest {

    @Test
    void shouldCreateWithMessage() {
        ClapException ex = new ClapException("test error");
        assertThat(ex.getMessage()).isEqualTo("test error");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void shouldCreateWithMessageAndCause() {
        RuntimeException cause = new RuntimeException("root cause");
        ClapException ex = new ClapException("test error", cause);
        assertThat(ex.getMessage()).isEqualTo("test error");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void shouldBeRuntimeException() {
        assertThat(new ClapException("test")).isInstanceOf(RuntimeException.class);
    }
}
