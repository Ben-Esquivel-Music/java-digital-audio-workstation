package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioBackendFactoryTest {

    @Test
    void shouldCreateDefaultBackend() {
        NativeAudioBackend backend = AudioBackendFactory.createDefault();
        assertThat(backend).isNotNull();
        assertThat(backend.getBackendName()).isIn("PortAudio", "Java Sound");
    }

    @Test
    void shouldCreateJavaSoundBackend() {
        NativeAudioBackend backend = AudioBackendFactory.createJavaSound();
        assertThat(backend).isNotNull();
        assertThat(backend.getBackendName()).isEqualTo("Java Sound");
        assertThat(backend.isAvailable()).isTrue();
    }

    @Test
    void shouldDetectBackendName() {
        String name = AudioBackendFactory.detectBackendName();
        assertThat(name).isIn("PortAudio", "Java Sound");
    }

    @Test
    void shouldReturnAvailableDefaultBackend() {
        NativeAudioBackend backend = AudioBackendFactory.createDefault();
        assertThat(backend.isAvailable()).isTrue();
    }
}
