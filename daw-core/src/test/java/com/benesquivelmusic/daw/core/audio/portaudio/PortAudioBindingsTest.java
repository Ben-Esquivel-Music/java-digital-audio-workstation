package com.benesquivelmusic.daw.core.audio.portaudio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PortAudioBindingsTest {

    @Test
    void shouldReportAvailabilityBasedOnNativeLibrary() {
        // PortAudio is unlikely to be installed in the CI/test environment
        PortAudioBindings bindings = new PortAudioBindings();
        // We don't assert true/false — just that it doesn't crash
        assertThat(bindings.isAvailable()).isIn(true, false);
    }

    @Test
    void shouldDefineConstants() {
        assertThat(PortAudioBindings.PA_FLOAT32).isEqualTo(0x00000001L);
        assertThat(PortAudioBindings.PA_NO_ERROR).isEqualTo(0);
        assertThat(PortAudioBindings.PA_NO_DEVICE).isEqualTo(-1);
        assertThat(PortAudioBindings.PA_CONTINUE).isEqualTo(0);
        assertThat(PortAudioBindings.PA_COMPLETE).isEqualTo(1);
        assertThat(PortAudioBindings.PA_ABORT).isEqualTo(2);
    }

    @Test
    void shouldDefineDeviceInfoLayout() {
        assertThat(PortAudioBindings.PA_DEVICE_INFO_LAYOUT).isNotNull();
        assertThat(PortAudioBindings.PA_DEVICE_INFO_LAYOUT.byteSize()).isGreaterThan(0);
    }

    @Test
    void shouldDefineStreamInfoLayout() {
        assertThat(PortAudioBindings.PA_STREAM_INFO_LAYOUT).isNotNull();
        assertThat(PortAudioBindings.PA_STREAM_INFO_LAYOUT.byteSize()).isGreaterThan(0);
    }
}
