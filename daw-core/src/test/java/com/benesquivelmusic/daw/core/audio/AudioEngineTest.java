package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

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
}
