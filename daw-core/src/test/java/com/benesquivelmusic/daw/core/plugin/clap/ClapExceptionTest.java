package com.benesquivelmusic.daw.core.plugin.clap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClapExceptionTest {

    @Test
    void shouldCreateWithMessage() {
        var ex = new ClapException("test error");
        assertThat(ex.getMessage()).isEqualTo("test error");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void shouldCreateWithMessageAndCause() {
        var cause = new RuntimeException("root cause");
        var ex = new ClapException("test error", cause);
        assertThat(ex.getMessage()).isEqualTo("test error");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void shouldBeRuntimeException() {
        assertThat(new ClapException("test")).isInstanceOf(RuntimeException.class);
    }
}
