package com.benesquivelmusic.daw.core.audio.portaudio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortAudioBackendTest {

    @Test
    void shouldReportPortAudioBackendName() {
        PortAudioBackend backend = new PortAudioBackend();
        assertThat(backend.getBackendName()).isEqualTo("PortAudio");
    }

    @Test
    void shouldReportAvailability() {
        PortAudioBackend backend = new PortAudioBackend();
        // Just check that it runs without error
        assertThat(backend.isAvailable()).isIn(true, false);
    }

    @Test
    void shouldReportStreamInactiveBeforeStart() {
        PortAudioBackend backend = new PortAudioBackend();
        assertThat(backend.isStreamActive()).isFalse();
    }

    @Test
    void shouldThrowOnInitializeWhenUnavailable() {
        PortAudioBackend backend = new PortAudioBackend();
        if (!backend.isAvailable()) {
            assertThatThrownBy(backend::initialize)
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessageContaining("not available");
        }
    }

    @Test
    void shouldThrowWhenNotInitialized() {
        PortAudioBackend backend = new PortAudioBackend();
        assertThatThrownBy(backend::getAvailableDevices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldThrowWhenNoStreamOpenForLatency() {
        PortAudioBackend backend = new PortAudioBackend();
        // Even without initialization, streamHandle is null
        assertThatThrownBy(backend::getLatencyInfo)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldThrowWhenNoStreamOpenForStart() {
        PortAudioBackend backend = new PortAudioBackend();
        assertThatThrownBy(backend::startStream)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldAllowCloseWithoutInitialization() {
        PortAudioBackend backend = new PortAudioBackend();
        backend.close(); // should not throw
    }

    @Test
    void shouldAllowCloseStreamWithoutOpenStream() {
        PortAudioBackend backend = new PortAudioBackend();
        backend.closeStream(); // should not throw
    }

    @Test
    void shouldAllowStopStreamWithoutStart() {
        PortAudioBackend backend = new PortAudioBackend();
        backend.stopStream(); // should not throw
    }
}
