package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the audio output stream lifecycle methods on {@link AudioEngine}:
 * {@link AudioEngine#startAudioOutput()}, {@link AudioEngine#stopAudioOutput()},
 * and {@link AudioEngine#pauseAudioOutput()}.
 */
class AudioEngineOutputTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 512);

    private AudioEngine engine;
    private StubAudioBackend backend;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(FORMAT);
        backend = new StubAudioBackend();
        engine.setAudioBackend(backend);
    }

    @Test
    void startAudioOutputShouldInitializeAndOpenAndStartStream() {
        engine.startAudioOutput();

        assertThat(backend.initialized).isTrue();
        assertThat(backend.streamOpened).isTrue();
        assertThat(backend.streamStarted).isTrue();
        assertThat(backend.streamStopped).isFalse();
        assertThat(backend.streamClosed).isFalse();
        assertThat(engine.isRunning()).isTrue();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isStreamPaused()).isFalse();
    }

    @Test
    void stopAudioOutputShouldStopAndCloseStream() {
        engine.startAudioOutput();
        engine.stopAudioOutput();

        assertThat(backend.streamStopped).isTrue();
        assertThat(backend.streamClosed).isTrue();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(engine.isStreamPaused()).isFalse();
    }

    @Test
    void pauseAudioOutputShouldStopStreamButKeepItOpen() {
        engine.startAudioOutput();
        engine.pauseAudioOutput();

        assertThat(backend.streamStopped).isTrue();
        assertThat(backend.streamClosed).isFalse();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isStreamPaused()).isTrue();
    }

    @Test
    void startAudioOutputAfterPauseShouldResumeStream() {
        engine.startAudioOutput();
        engine.pauseAudioOutput();

        // Reset tracking to verify resume calls
        backend.streamStarted = false;
        backend.streamStopped = false;

        engine.startAudioOutput();

        assertThat(backend.streamStarted).isTrue();
        assertThat(engine.isStreamPaused()).isFalse();
        assertThat(engine.isStreamOpen()).isTrue();
    }

    @Test
    void stopAudioOutputWhenNoStreamIsOpenShouldBeNoOp() {
        engine.stopAudioOutput();

        assertThat(backend.streamStopped).isFalse();
        assertThat(backend.streamClosed).isFalse();
    }

    @Test
    void pauseAudioOutputWhenNoStreamIsOpenShouldBeNoOp() {
        engine.pauseAudioOutput();

        assertThat(backend.streamStopped).isFalse();
    }

    @Test
    void startAudioOutputWithNoBackendShouldStartEngineOnly() {
        AudioEngine engineNoBackend = new AudioEngine(FORMAT);
        engineNoBackend.startAudioOutput();

        assertThat(engineNoBackend.isRunning()).isTrue();
        assertThat(engineNoBackend.isStreamOpen()).isFalse();
    }

    @Test
    void startAudioOutputShouldRegisterProcessBlockAsCallback() {
        engine.startAudioOutput();

        assertThat(backend.registeredCallback).isNotNull();
        // The callback should be engine::processBlock — verify it works
        float[][] input = new float[2][512];
        float[][] output = new float[2][512];
        backend.registeredCallback.process(input, output, 512);
        // Should not throw (engine is running, passthrough mode)
    }

    @Test
    void startAudioOutputShouldPassCorrectStreamConfig() {
        engine.startAudioOutput();

        AudioStreamConfig config = backend.lastConfig;
        assertThat(config).isNotNull();
        assertThat(config.inputDeviceIndex()).isEqualTo(-1);
        assertThat(config.outputDeviceIndex()).isEqualTo(0);
        assertThat(config.inputChannels()).isEqualTo(0);
        assertThat(config.outputChannels()).isEqualTo(2);
        assertThat(config.sampleRate()).isEqualTo(SampleRate.HZ_44100);
        assertThat(config.bufferSize().getFrames()).isEqualTo(512);
    }

    @Test
    void startAudioOutputShouldPropagateBackendException() {
        backend.failOnOpenStream = true;

        assertThatThrownBy(() -> engine.startAudioOutput())
                .isInstanceOf(AudioBackendException.class);
    }

    @Test
    void startAudioOutputShouldCleanUpOnStartStreamFailure() {
        backend.failOnStartStream = true;

        assertThatThrownBy(() -> engine.startAudioOutput())
                .isInstanceOf(AudioBackendException.class);

        // Stream should have been closed after startStream failure
        assertThat(backend.streamClosed).isTrue();
        assertThat(engine.isStreamOpen()).isFalse();

        // A subsequent start should work (no stale streamOpen flag)
        backend.failOnStartStream = false;
        backend.streamClosed = false;
        engine.startAudioOutput();

        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(backend.streamStarted).isTrue();
    }

    @Test
    void startAudioOutputWhenAlreadyRunningShouldBeNoOp() {
        engine.startAudioOutput();

        // Reset tracking to detect redundant calls
        backend.initialized = false;
        backend.streamOpened = false;
        backend.streamStarted = false;

        engine.startAudioOutput();

        // No backend methods should have been called again
        assertThat(backend.initialized).isFalse();
        assertThat(backend.streamOpened).isFalse();
        assertThat(backend.streamStarted).isFalse();
        assertThat(engine.isStreamOpen()).isTrue();
    }

    @Test
    void initializeShouldBeCalledOnlyOnceAcrossMultipleStartStopCycles() {
        engine.startAudioOutput();
        assertThat(backend.initializeCount).isEqualTo(1);

        engine.stopAudioOutput();
        engine.startAudioOutput();

        // initialize() should not be called again for the same backend
        assertThat(backend.initializeCount).isEqualTo(1);
    }

    // ── Stub backend ─────────────────────────────────────────────────────────

    private static final class StubAudioBackend implements NativeAudioBackend {

        boolean initialized;
        int initializeCount;
        boolean streamOpened;
        boolean streamStarted;
        boolean streamStopped;
        boolean streamClosed;
        boolean failOnOpenStream;
        boolean failOnStartStream;
        AudioStreamCallback registeredCallback;
        AudioStreamConfig lastConfig;

        @Override
        public void initialize() {
            initialized = true;
            initializeCount++;
        }

        @Override
        public List<AudioDeviceInfo> getAvailableDevices() { return List.of(); }

        @Override
        public AudioDeviceInfo getDefaultInputDevice() { return null; }

        @Override
        public AudioDeviceInfo getDefaultOutputDevice() { return null; }

        @Override
        public void openStream(AudioStreamConfig config, AudioStreamCallback callback) {
            if (failOnOpenStream) {
                throw new AudioBackendException("Simulated open failure");
            }
            lastConfig = config;
            registeredCallback = callback;
            streamOpened = true;
        }

        @Override
        public void startStream() {
            if (failOnStartStream) {
                throw new AudioBackendException("Simulated start failure");
            }
            streamStarted = true;
        }

        @Override
        public void stopStream() { streamStopped = true; }

        @Override
        public void closeStream() { streamClosed = true; }

        @Override
        public LatencyInfo getLatencyInfo() {
            return LatencyInfo.of(0, 0, 512, 44100);
        }

        @Override
        public boolean isStreamActive() { return streamStarted && !streamStopped; }

        @Override
        public String getBackendName() { return "Stub"; }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public void close() {}
    }
}
