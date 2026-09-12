package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

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

    /**
     * Story 318 — the tap hook resolves a processor's {@code InsertSlot}
     * through its tag, so a tags/processors misalignment would meter the
     * WRONG plugin slot instead of failing loudly. Every chain in the
     * end-to-end tests holds one link, where index 0 is always right; this
     * pins the pairing across mid-chain removals.
     */
    @Test
    void shouldKeepEachTagPairedWithItsProcessorAcrossRemovals() {
        EffectsChain chain = new EffectsChain();
        PassthroughProcessor a = new PassthroughProcessor();
        GainProcessor b = new GainProcessor(0.5f);
        LatencyProcessor c = new LatencyProcessor(10);
        chain.addProcessor(a, "tag-a");
        chain.addProcessor(b, "tag-b");
        chain.addProcessor(c, "tag-c");

        assertTagsPairedWithProcessors(chain, "tag-a", "tag-b", "tag-c");

        assertThat(chain.removeProcessor(b)).isTrue();
        assertThat(chain.getProcessors()).containsExactly(a, c);
        assertTagsPairedWithProcessors(chain, "tag-a", "tag-c");

        chain.removeProcessor(0);
        assertThat(chain.getProcessors()).containsExactly(c);
        assertTagsPairedWithProcessors(chain, "tag-c");
    }

    /** Asserts {@code getTag(i)} pairs with {@code getProcessors().get(i)} for every i. */
    private static void assertTagsPairedWithProcessors(EffectsChain chain, String... expectedTags) {
        assertThat(chain.size()).isEqualTo(expectedTags.length);
        assertThat(chain.getProcessors()).hasSize(expectedTags.length);
        for (int i = 0; i < expectedTags.length; i++) {
            assertThat(chain.getTag(i))
                    .as("tag at index %d, beside processor %s", i, chain.getProcessors().get(i))
                    .contains(expectedTags[i]);
        }
    }

    @Test
    void replaceAllSwapsTheWholeChainAndItsTagsInOneCall() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new PassthroughProcessor(), "old");

        GainProcessor first = new GainProcessor(0.5f);
        GainProcessor second = new GainProcessor(0.25f);
        chain.replaceAll(List.of(first, second), Arrays.asList("one", null));

        assertThat(chain.getProcessors()).containsExactly(first, second);
        assertThat(chain.getTag(0)).contains("one");
        assertThat(chain.getTag(1)).isEmpty();

        chain.replaceAll(List.of(), List.of());
        assertThat(chain.isEmpty()).isTrue();
        assertThat(chain.size()).isZero();
    }

    @Test
    void replaceAllRejectsMismatchedProcessorAndTagCounts() {
        EffectsChain chain = new EffectsChain();

        assertThatThrownBy(() -> chain.replaceAll(
                List.of(new PassthroughProcessor()), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(chain.isEmpty()).as("a rejected batch publishes nothing").isTrue();
    }

    /**
     * Story 318 (second review round) — growing a chain must publish the
     * scratch it needs together with the chain itself. Before the fix the
     * buffers were re-allocated only after the mutator returned, so a block
     * landing in between saw the longer chain with the shorter scratch and
     * allocated a temp buffer on the audio thread.
     */
    @Test
    void growingTheChainGrowsThePreAllocatedScratchInTheSamePublication() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new GainProcessor(0.5f));
        chain.allocateIntermediateBuffers(1, 4);
        assertThat(chain.intermediateBufferCount())
                .as("a one-processor chain needs no scratch").isZero();

        chain.addProcessor(new GainProcessor(0.5f));

        assertThat(chain.intermediateBufferCount())
                .as("the second processor's scratch exists as soon as it is published")
                .isGreaterThanOrEqualTo(1);

        chain.replaceAll(List.of(new GainProcessor(0.5f), new GainProcessor(0.5f),
                new GainProcessor(0.5f)), Arrays.asList(null, null, null));
        assertThat(chain.intermediateBufferCount())
                .as("a batch replace grows the scratch too")
                .isGreaterThanOrEqualTo(2);

        float[][] input = {{1.0f, -1.0f, 0.5f, 0.25f}};
        float[][] output = {{0.0f, 0.0f, 0.0f, 0.0f}};
        chain.process(input, output, 4);
        assertThat(output[0][0]).isEqualTo(0.125f);
    }

    /**
     * An empty chain that was never pre-allocated keeps the documented
     * {@code createTempBuffer} fallback — the growth rule must not invent
     * dimensions it was never given.
     */
    @Test
    void aChainThatWasNeverPreAllocatedGrowsNoScratch() {
        EffectsChain chain = new EffectsChain();
        chain.addProcessor(new GainProcessor(0.5f));
        chain.addProcessor(new GainProcessor(0.5f));

        assertThat(chain.intermediateBufferCount()).isZero();
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
