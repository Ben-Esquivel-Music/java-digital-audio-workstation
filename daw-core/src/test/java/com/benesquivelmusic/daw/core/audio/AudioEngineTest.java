package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioEngineTest {

    @Test
    void shouldStartAndStop() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);

        assertThat(engine.isRunning()).isFalse();

        assertThat(engine.start()).isTrue();
        assertThat(engine.isRunning()).isTrue();

        // starting again should be a no-op
        assertThat(engine.start()).isFalse();

        assertThat(engine.stop()).isTrue();
        assertThat(engine.isRunning()).isFalse();

        // stopping again should be a no-op
        assertThat(engine.stop()).isFalse();
    }

    @Test
    void shouldReturnConfiguredFormat() {
        AudioEngine engine = new AudioEngine(AudioFormat.STUDIO_QUALITY);
        assertThat(engine.getFormat()).isEqualTo(AudioFormat.STUDIO_QUALITY);
    }

    @Test
    void shouldExposeMasterChain() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        assertThat(engine.getMasterChain()).isNotNull();
        assertThat(engine.getMasterChain().isEmpty()).isTrue();
    }

    @Test
    void shouldAllocateBufferPoolOnStart() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        assertThat(engine.getBufferPool()).isNull();

        engine.start();
        assertThat(engine.getBufferPool()).isNotNull();
        assertThat(engine.getBufferPool().available()).isGreaterThan(0);
    }

    @Test
    void shouldProcessBlockPassthroughWhenChainEmpty() {
        AudioFormat format = new AudioFormat(44_100.0, 1, 16, 4);
        AudioEngine engine = new AudioEngine(format);
        engine.start();

        float[][] input = {{0.5f, -0.3f, 0.8f, -1.0f}};
        float[][] output = {{0.0f, 0.0f, 0.0f, 0.0f}};
        engine.processBlock(input, output, 4);

        assertThat(output[0]).containsExactly(0.5f, -0.3f, 0.8f, -1.0f);
    }

    @Test
    void shouldProcessBlockThroughMasterChain() {
        AudioFormat format = new AudioFormat(44_100.0, 1, 16, 4);
        AudioEngine engine = new AudioEngine(format);
        engine.getMasterChain().addProcessor(new HalfGainProcessor());
        engine.start();

        float[][] input = {{1.0f, -1.0f, 0.5f, -0.5f}};
        float[][] output = {{0.0f, 0.0f, 0.0f, 0.0f}};
        engine.processBlock(input, output, 4);

        assertThat(output[0]).containsExactly(0.5f, -0.5f, 0.25f, -0.25f);
    }

    @Test
    void shouldThrowWhenProcessingWhileNotRunning() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);

        float[][] input = {{0.0f}};
        float[][] output = {{0.0f}};

        assertThatThrownBy(() -> engine.processBlock(input, output, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldInvokeRecordingCallbackDuringProcessBlock() {
        AudioFormat format = new AudioFormat(44_100.0, 1, 16, 4);
        AudioEngine engine = new AudioEngine(format);
        engine.start();

        int[] callbackCount = {0};
        int[] capturedFrames = {0};
        engine.setRecordingCallback((inputBuffer, numFrames) -> {
            callbackCount[0]++;
            capturedFrames[0] = numFrames;
        });

        float[][] input = {{0.5f, -0.3f, 0.8f, -1.0f}};
        float[][] output = {{0.0f, 0.0f, 0.0f, 0.0f}};
        engine.processBlock(input, output, 4);

        assertThat(callbackCount[0]).isEqualTo(1);
        assertThat(capturedFrames[0]).isEqualTo(4);
    }

    @Test
    void shouldNotInvokeCallbackWhenNull() {
        AudioFormat format = new AudioFormat(44_100.0, 1, 16, 4);
        AudioEngine engine = new AudioEngine(format);
        engine.start();

        assertThat(engine.getRecordingCallback()).isNull();

        // Should not throw when no callback is set
        float[][] input = {{0.5f, -0.3f, 0.8f, -1.0f}};
        float[][] output = {{0.0f, 0.0f, 0.0f, 0.0f}};
        engine.processBlock(input, output, 4);

        assertThat(output[0]).containsExactly(0.5f, -0.3f, 0.8f, -1.0f);
    }

    @Test
    void shouldSetAndGetRecordingCallback() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);

        assertThat(engine.getRecordingCallback()).isNull();

        AudioEngine.RecordingCallback cb = (inputBuffer, numFrames) -> {};
        engine.setRecordingCallback(cb);
        assertThat(engine.getRecordingCallback()).isSameAs(cb);

        engine.setRecordingCallback(null);
        assertThat(engine.getRecordingCallback()).isNull();
    }

    @Test
    void shouldRefuseStartAudioInputOutputWithoutBackend() {
        // Story 316 review: this used to assert the engine merely started.
        // With no provision there is no capture device AT ALL, so a record
        // that returned normally handed the recording pipeline a publisher
        // that could never emit a block — the silent take. Story 317 applies
        // the same honest refusal to playback because it has no callback clock.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);

        assertThatThrownBy(engine::startAudioInputOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("no audio backend is configured");
    }

    @Test
    void shouldRefuseAudioOutputWithoutBackendBeforeStarting() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);

        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("no audio backend is configured");

        assertThat(engine.isRunning()).isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
    }

    @Test
    void refusedOutputPreservesAnEngineThatWasAlreadyRunningForAnotherReason() {
        // The failed output attempt owns only starts it performed itself. An
        // engine already running for offline/in-process work stays running.
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);

        engine.start();
        assertThat(engine.isRunning()).isTrue();

        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(AudioBackendException.class)
                .hasMessageContaining("no audio backend is configured");
        assertThat(engine.isRunning()).isTrue();
        assertThat(engine.isStreamOpen()).isFalse();
        engine.stop();
    }

    @Test
    void failedStreamOwnedEnginePreparationRollsBackBeforeOpeningTheBackend() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        var preparationFailure = installArmablePreparationFailure(engine);
        var backend = new MockAudioBackend();
        engine.setStreamingProvision(mockProvision(backend));
        preparationFailure.set(true);

        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mixer preparation failed");

        assertThat(engine.isRunning()).isFalse();
        assertThat(engine.isStreamOpen()).isFalse();
        assertThat(backend.isOpen())
                .as("the backend is never reached after preparation fails")
                .isFalse();
    }

    @Test
    void failedEnginePreparationDuringResumeKeepsTheOpenStreamPaused() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        var backend = new MockAudioBackend();
        engine.setStreamingProvision(mockProvision(backend));
        engine.startAudioOutput();
        engine.pauseAudioOutput();
        engine.stop();
        assertThat(engine.isRunning()).isFalse();
        assertThat(engine.isStreamPaused()).isTrue();

        var preparationFailure = installArmablePreparationFailure(engine);
        preparationFailure.set(true);

        assertThatThrownBy(engine::startAudioOutput)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mixer preparation failed");

        assertThat(engine.isRunning()).isFalse();
        assertThat(engine.isStreamOpen()).isTrue();
        assertThat(engine.isStreamPaused()).isTrue();
        assertThat(backend.isOpen()).isTrue();
        engine.stopAudioOutput();
    }

    private static StreamingProvision mockProvision(MockAudioBackend backend) {
        DeviceId device = DeviceId.defaultFor(backend.name());
        return new StreamingProvision(
                backend.name(), List.of(new BackendStreamRung(backend, device)));
    }

    private static AtomicBoolean installArmablePreparationFailure(AudioEngine engine) {
        AtomicBoolean fail = new AtomicBoolean();
        Mixer mixer = new Mixer();
        MixerChannel channel = new MixerChannel("Preparation failure");
        channel.addInsert(new InsertSlot(
                "Preparation failure", new ArmablePreparationFailureProcessor(fail)));
        mixer.addChannel(channel);
        engine.setGraph(new Transport(), mixer, List.of());
        return fail;
    }

    @Test
    void shouldPrepareMixerEffectsChainsWhenSetAfterStart() {
        AudioFormat format = new AudioFormat(44_100.0, 1, 16, 4);
        AudioEngine engine = new AudioEngine(format);
        engine.start();

        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.addInsert(new InsertSlot("Gain", new HalfGainProcessor()));
        ch.addInsert(new InsertSlot("Gain", new HalfGainProcessor()));
        mixer.addChannel(ch);

        // Publishing a mixer after start() should prepare effects chains
        engine.setGraph(null, mixer, null);

        // Verify the chain works with pre-allocated buffers by checking
        // the effects chain's intermediate buffers are not null
        assertThat(ch.getEffectsChain().getProcessors()).hasSize(2);

        // Process through the chain — if buffers were pre-allocated,
        // this won't allocate on the audio thread
        float[][] input = {{1.0f, 0.8f, 0.6f, 0.4f}};
        float[][] output = {{0.0f, 0.0f, 0.0f, 0.0f}};
        ch.getEffectsChain().process(input, output, 4);

        // 1.0 * 0.5 * 0.5 = 0.25
        assertThat(output[0][0]).isEqualTo(0.25f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    private static class HalfGainProcessor implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                for (int i = 0; i < numFrames; i++) {
                    outputBuffer[ch][i] = inputBuffer[ch][i] * 0.5f;
                }
            }
        }

        @Override
        public void reset() {}

        @Override
        public int getInputChannelCount() { return 1; }

        @Override
        public int getOutputChannelCount() { return 1; }
    }

    private record ArmablePreparationFailureProcessor(AtomicBoolean fail)
            implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int channel = 0; channel < inputBuffer.length; channel++) {
                System.arraycopy(
                        inputBuffer[channel], 0, outputBuffer[channel], 0, numFrames);
            }
        }

        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }

        @Override
        public int getLatencySamples() {
            if (fail.get()) {
                throw new IllegalStateException("mixer preparation failed");
            }
            return 0;
        }
    }

    /**
     * A processor that reports latency but passes audio through unmodified.
     */
    private record LatencyProcessor(int latency) implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }
        @Override public void reset() {}
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
        @Override public int getLatencySamples() { return latency; }
    }

    @Test
    void shouldOffsetRenderPositionBySystemLatency() {
        // Set up a mono, 8-sample-buffer engine at 120 BPM, 44100 Hz
        // At 120 BPM, 44100 Hz: samplesPerBeat = 44100 * 60 / 120 = 22050
        // A 4-sample latency → renderOffsetBeats = 4 / 22050 ≈ 0.000181 beats
        AudioFormat format = new AudioFormat(44_100.0, 1, 16, 8);
        AudioEngine engine = new AudioEngine(format);
        engine.start();

        Transport transport = new Transport();
        transport.setPositionInBeats(0.0);
        transport.play();

        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        // Add a 4-sample latency insert — this sets systemLatency = 4
        ch1.addInsert(new InsertSlot("Latent", new LatencyProcessor(4)));
        mixer.addChannel(ch1);

        // Create a track with a ramp clip starting at beat 0 so we can
        // verify which sample index the engine reads from.
        Track track = new Track("Track1", TrackType.AUDIO);
        float[][] clipAudio = new float[1][22050];
        for (int i = 0; i < clipAudio[0].length; i++) {
            clipAudio[0][i] = (float) (i + 1);   // ramp: 1, 2, 3, 4, 5, 6, ...
        }
        AudioClip clip = new AudioClip("Clip1", 0.0, 1.0, "test.wav");
        clip.setAudioData(clipAudio);
        track.addClip(clip);

        engine.setGraph(transport, mixer, List.of(track));

        // Verify getSystemLatencySamples() reports the expected latency
        assertThat(engine.getSystemLatencySamples()).isEqualTo(4);

        // Process one block. With a 4-sample render offset the engine reads
        // clip data starting at sample index 4 (ramp values 5, 6, 7, 8, ...)
        // instead of index 0 (ramp values 1, 2, 3, 4, ...).
        float[][] input = new float[1][8];
        float[][] output = new float[1][8];
        engine.processBlock(input, output, 8);

        // The output should start at the offset position in the ramp.
        // With only one channel at max latency, PDC compensation = 0 for this
        // channel, so the render offset determines which samples appear.
        // Ramp uses (i+1) to distinguish valid audio from silence (0.0f).
        // Ramp index 4 has value 5.0f, index 5 → 6.0f, etc.
        assertThat(output[0][0]).isEqualTo(5.0f);
        assertThat(output[0][1]).isEqualTo(6.0f);
        assertThat(output[0][2]).isEqualTo(7.0f);
        assertThat(output[0][3]).isEqualTo(8.0f);
        assertThat(output[0][4]).isEqualTo(9.0f);
        assertThat(output[0][5]).isEqualTo(10.0f);
        assertThat(output[0][6]).isEqualTo(11.0f);
        assertThat(output[0][7]).isEqualTo(12.0f);
    }
}
