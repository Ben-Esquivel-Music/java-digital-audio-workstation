package com.benesquivelmusic.daw.core.audio.javasound;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import java.util.List;
import com.benesquivelmusic.daw.sdk.audio.BufferSize;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaSoundBackendTest {

    private JavaSoundBackend backend;

    @BeforeEach
    void setUp() {
        backend = new JavaSoundBackend();
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    void shouldReportJavaSoundBackendName() {
        assertThat(backend.getBackendName()).isEqualTo("Java Sound");
    }

    @Test
    void shouldAlwaysBeAvailable() {
        assertThat(backend.isAvailable()).isTrue();
    }

    @Test
    void shouldInitializeSuccessfully() {
        backend.initialize();
        // Should not throw
    }

    @Test
    void shouldEnumerateDevicesAfterInitialization() {
        backend.initialize();
        List<AudioDeviceInfo> devices = backend.getAvailableDevices();
        assertThat(devices).isNotNull();
        // Devices may be empty in CI, but the list itself should be valid
    }

    @Test
    void shouldReturnSameDeviceListOnRepeatedCalls() {
        backend.initialize();
        List<AudioDeviceInfo> first = backend.getAvailableDevices();
        List<AudioDeviceInfo> second = backend.getAvailableDevices();
        assertThat(first).isSameAs(second);
    }

    @Test
    void shouldThrowWhenNotInitialized() {
        assertThatThrownBy(() -> backend.getAvailableDevices())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldReportStreamInactiveBeforeStart() {
        assertThat(backend.isStreamActive()).isFalse();
    }

    @Test
    void shouldAllowCloseWithoutInitialization() {
        backend.close(); // should not throw
    }

    @Test
    void shouldAllowStopStreamWithoutStart() {
        backend.stopStream(); // should not throw
    }

    @Test
    void shouldAllowCloseStreamWithoutOpen() {
        backend.closeStream(); // should not throw
    }

    @Test
    void shouldThrowWhenOpenStreamNotInitialized() {
        com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig config = new com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig(
                -1, 0, 0, 2, SampleRate.HZ_44100, BufferSize.SAMPLES_256);
        assertThatThrownBy(() -> backend.openStream(config, (in, out, n) -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldThrowWhenLatencyInfoWithoutStream() {
        assertThatThrownBy(() -> backend.getLatencyInfo())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldClearStateOnClose() {
        backend.initialize();
        backend.close();
        assertThat(backend.isStreamActive()).isFalse();
        // After close, should require re-initialization
        assertThatThrownBy(() -> backend.getAvailableDevices())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldReportDeviceHostApiAsJavaSound() {
        backend.initialize();
        List<AudioDeviceInfo> devices = backend.getAvailableDevices();
        for (AudioDeviceInfo device : devices) {
            assertThat(device.hostApi()).isEqualTo("Java Sound");
        }
    }
}
