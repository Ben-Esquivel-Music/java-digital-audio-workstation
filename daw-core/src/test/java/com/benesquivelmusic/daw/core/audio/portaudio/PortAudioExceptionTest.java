package com.benesquivelmusic.daw.core.audio.portaudio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortAudioExceptionTest {

    @Test
    void shouldIncludeErrorCodeInMessage() {
        PortAudioException ex = new PortAudioException("Test error", -9999);
        assertThat(ex.getMessage()).contains("Test error");
        assertThat(ex.getMessage()).contains("-9999");
        assertThat(ex.getErrorCode()).isEqualTo(-9999);
    }

    @Test
    void shouldIncludeCause() {
        RuntimeException cause = new RuntimeException("root cause");
        PortAudioException ex = new PortAudioException("Wrapper", -1, cause);
        assertThat(ex.getCause()).isEqualTo(cause);
        assertThat(ex.getErrorCode()).isEqualTo(-1);
    }

    @Test
    void shouldThrowOnNegativeErrorCode() {
        assertThatThrownBy(() -> PortAudioException.checkError(-1, "Pa_Test"))
                .isInstanceOf(PortAudioException.class)
                .hasMessageContaining("Pa_Test")
                .hasMessageContaining("-1");
    }

    @Test
    void shouldNotThrowOnZeroErrorCode() {
        PortAudioException.checkError(0, "Pa_Test"); // should not throw
    }

    @Test
    void shouldNotThrowOnPositiveErrorCode() {
        PortAudioException.checkError(1, "Pa_Test"); // should not throw (positive = success/info)
    }
}
