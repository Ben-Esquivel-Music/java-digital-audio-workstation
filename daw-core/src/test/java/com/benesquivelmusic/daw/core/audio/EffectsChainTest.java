package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectsChainTest {

    @Test
    void shouldStartEmpty() {
        EffectsChain chain = new EffectsChain();

        assertThat(chain.isEmpty()).isTrue();
        assertThat(chain.size()).isZero();
        assertThat(chain.isBypassed()).isFalse();
    }

    @Test
    void shouldAddProcessor() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new PassthroughProcessor());

        assertThat(chain.size()).isEqualTo(1);
        assertThat(chain.isEmpty()).isFalse();
    }

    @Test
    void shouldInsertProcessorAtIndex() {
        EffectsChain chain = new EffectsChain();
        PassthroughProcessor p1 = new PassthroughProcessor();
        GainProcessor p2 = new GainProcessor(0.5f);
        chain.addProcessor(p1);
        chain.insertProcessor(0, p2);

        assertThat(chain.getProcessors().getFirst()).isSameAs(p2);
    }

    @Test
    void shouldRemoveProcessor() {
        EffectsChain chain = new EffectsChain();
        PassthroughProcessor p = new PassthroughProcessor();
        chain.addProcessor(p);

        assertThat(chain.removeProcessor(p)).isTrue();
        assertThat(chain.isEmpty()).isTrue();
    }

    @Test
    void shouldRemoveProcessorByIndex() {
        EffectsChain chain = new EffectsChain();
        PassthroughProcessor p = new PassthroughProcessor();
        chain.addProcessor(p);

        AudioProcessor removed = chain.removeProcessor(0);
        assertThat(removed).isSameAs(p);
        assertThat(chain.isEmpty()).isTrue();
    }

    @Test
    void shouldCopyInputToOutputWhenBypassed() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new GainProcessor(0.0f));
        chain.setBypassed(true);

        float[][] input = {{1.0f, 0.5f}};
        float[][] output = {{0.0f, 0.0f}};
        chain.process(input, output, 2);

        assertThat(output[0]).containsExactly(1.0f, 0.5f);
    }

    @Test
    void shouldCopyInputToOutputWhenEmpty() {
        EffectsChain chain = new EffectsChain();

        float[][] input = {{0.7f, -0.3f}};
        float[][] output = {{0.0f, 0.0f}};
        chain.process(input, output, 2);

        assertThat(output[0]).containsExactly(0.7f, -0.3f);
    }

    @Test
    void shouldProcessThroughSingleProcessor() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new GainProcessor(0.5f));

        float[][] input = {{1.0f, -1.0f}};
        float[][] output = {{0.0f, 0.0f}};
        chain.process(input, output, 2);

        assertThat(output[0]).containsExactly(0.5f, -0.5f);
    }

    @Test
    void shouldProcessThroughChainedProcessors() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new GainProcessor(0.5f));
        chain.addProcessor(new GainProcessor(0.5f));

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};
        chain.process(input, output, 1);

        assertThat(output[0][0]).isEqualTo(0.25f);
    }

    @Test
    void shouldResetAllProcessors() {
        EffectsChain chain = new EffectsChain();
        PassthroughProcessor p1 = new PassthroughProcessor();
        PassthroughProcessor p2 = new PassthroughProcessor();
        chain.addProcessor(p1);
        chain.addProcessor(p2);

        chain.reset();

        assertThat(p1.resetCount).isEqualTo(1);
        assertThat(p2.resetCount).isEqualTo(1);
    }

    @Test
    void shouldReturnUnmodifiableProcessorList() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new PassthroughProcessor());

        assertThatThrownBy(() -> chain.getProcessors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullProcessor() {
        EffectsChain chain = new EffectsChain();

        assertThatThrownBy(() -> chain.addProcessor(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldProcessWithPreAllocatedIntermediateBuffers() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new GainProcessor(0.5f));
        chain.addProcessor(new GainProcessor(0.5f));
        chain.allocateIntermediateBuffers(1, 4);

        float[][] input = {{1.0f, 0.8f, 0.6f, 0.4f}};
        float[][] output = {{0.0f, 0.0f, 0.0f, 0.0f}};
        chain.process(input, output, 4);

        assertThat(output[0][0]).isEqualTo(0.25f);
        assertThat(output[0][1]).isEqualTo(0.2f);
    }

    @Test
    void shouldWorkWithThreeProcessorsAndPreAllocatedBuffers() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new GainProcessor(0.5f));
        chain.addProcessor(new GainProcessor(0.5f));
        chain.addProcessor(new GainProcessor(0.5f));
        chain.allocateIntermediateBuffers(1, 2);

        float[][] input = {{1.0f, -1.0f}};
        float[][] output = {{0.0f, 0.0f}};
        chain.process(input, output, 2);

        assertThat(output[0][0]).isEqualTo(0.125f);
        assertThat(output[0][1]).isEqualTo(-0.125f);
    }

    @Test
    void shouldReturnZeroLatencyWhenEmpty() {
        EffectsChain chain = new EffectsChain();
        assertThat(chain.getTotalLatencySamples()).isZero();
    }

    @Test
    void shouldReturnZeroLatencyWhenBypassed() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new LatencyProcessor(100));
        chain.setBypassed(true);

        assertThat(chain.getTotalLatencySamples()).isZero();
    }

    @Test
    void shouldSumLatencyAcrossProcessors() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new LatencyProcessor(50));
        chain.addProcessor(new LatencyProcessor(100));
        chain.addProcessor(new PassthroughProcessor()); // 0 latency

        assertThat(chain.getTotalLatencySamples()).isEqualTo(150);
    }

    @Test
    void shouldReturnZeroLatencyForProcessorsWithoutLatency() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new PassthroughProcessor());
        chain.addProcessor(new GainProcessor(0.5f));

        assertThat(chain.getTotalLatencySamples()).isZero();
    }

    // --- Test processors ---

    private static class PassthroughProcessor implements AudioProcessor {
        int resetCount = 0;

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {
            resetCount++;
        }

        @Override
        public int getInputChannelCount() {
            return 1;
        }

        @Override
        public int getOutputChannelCount() {
            return 1;
        }
    }

    private record GainProcessor(float gain) implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                for (int i = 0; i < numFrames; i++) {
                    outputBuffer[ch][i] = inputBuffer[ch][i] * gain;
                }
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 1;
        }

        @Override
        public int getOutputChannelCount() {
            return 1;
        }
    }

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

        @Override
        public int getLatencySamples() {
            return latency;
        }
    }
}
