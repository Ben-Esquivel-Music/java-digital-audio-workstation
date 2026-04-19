package com.benesquivelmusic.daw.core.audio.harness;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig;
import com.benesquivelmusic.daw.sdk.audio.BufferSize;
import com.benesquivelmusic.daw.sdk.audio.LatencyInfo;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeadlessAudioBackendTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 128);

    @Test
    void lifecycleShouldProgressThroughOpenStartStopClose() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        backend.initialize();
        assertThat(backend.isInitialized()).isTrue();

        AudioStreamConfig cfg = new AudioStreamConfig(
                -1, 0, 0, 2, SampleRate.HZ_44100, BufferSize.fromFrames(128));
        backend.openStream(cfg, (in, out, n) -> {});
        assertThat(backend.isStreamOpen()).isTrue();
        backend.startStream();
        assertThat(backend.isStreamActive()).isTrue();

        LatencyInfo latency = backend.getLatencyInfo();
        assertThat(latency.bufferSizeFrames()).isEqualTo(128);

        backend.stopStream();
        assertThat(backend.isStreamActive()).isFalse();
        backend.closeStream();
        assertThat(backend.isStreamOpen()).isFalse();
    }

    @Test
    void driveShouldCaptureOutputWrittenByCallback() {
        HeadlessAudioBackend backend = openAndStart();
        // Callback writes a ramp 0,1,2... across channel 0 per block
        backend.closeStream();
        AudioStreamConfig cfg = new AudioStreamConfig(
                -1, 0, 0, 2, SampleRate.HZ_44100, BufferSize.fromFrames(64));
        backend.openStream(cfg, (in, out, n) -> {
            for (int i = 0; i < n; i++) {
                out[0][i] = i * 0.01f;
                out[1][i] = -i * 0.01f;
            }
        });
        backend.startStream();

        backend.drive(160); // 2 full blocks of 64 + 1 partial block of 32
        double[][] captured = backend.getCapturedOutput();

        assertThat(captured).hasDimensions(2, 160);
        assertThat(captured[0][0]).isEqualTo(0.0);
        assertThat(captured[0][63]).isCloseTo(63 * 0.01, org.assertj.core.data.Offset.offset(1e-6));
        // Second block starts over because the callback resets i each call
        assertThat(captured[0][64]).isEqualTo(0.0);
        assertThat(captured[1][65]).isCloseTo(-0.01, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void driveWithoutActiveStreamShouldThrow() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        assertThatThrownBy(() -> backend.drive(128))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void inputGeneratorShouldBeInvokedBeforeEachCallback() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        AudioStreamConfig cfg = new AudioStreamConfig(
                -1, 0, 1, 1, SampleRate.HZ_44100, BufferSize.fromFrames(32));
        // Passthrough callback: copy channel 0 from input to output
        backend.openStream(cfg, (in, out, n) -> {
            System.arraycopy(in[0], 0, out[0], 0, n);
        });
        backend.setInputGenerator((input, n, offset) -> {
            for (int i = 0; i < n; i++) {
                input[0][i] = (offset + i) * 1.0f;
            }
        });
        backend.startStream();
        backend.drive(32);

        double[][] captured = backend.getCapturedOutput();
        assertThat(captured[0]).hasSize(32);
        for (int i = 0; i < 32; i++) {
            assertThat(captured[0][i]).isEqualTo((double) i);
        }
    }

    @Test
    void getAvailableDevicesShouldReturnHeadlessDevice() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        assertThat(backend.getAvailableDevices()).hasSize(1);
        assertThat(backend.getDefaultOutputDevice().name()).isEqualTo("Headless");
        assertThat(backend.isAvailable()).isTrue();
        assertThat(backend.getBackendName()).isEqualTo("Headless");
    }

    private static HeadlessAudioBackend openAndStart() {
        HeadlessAudioBackend backend = new HeadlessAudioBackend();
        backend.initialize();
        AudioStreamConfig cfg = new AudioStreamConfig(
                -1, 0, 0, FORMAT.channels(), SampleRate.HZ_44100,
                BufferSize.fromFrames(FORMAT.bufferSize()));
        backend.openStream(cfg, (in, out, n) -> {});
        backend.startStream();
        return backend;
    }
}
