package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.core.dsp.PreparedParameterProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EffectsChainTest {

    @Test
    void offlineRenderPreparesPendingParametersOnceAndDefersLaterEditorWrites() {
        var processor = new OfflinePreparedGain();
        var slot = new InsertSlot("Prepared gain", processor);
        var chain = new EffectsChain();
        chain.addProcessor(processor, slot);
        chain.allocateIntermediateBuffers(1, 32);
        slot.getParameterStore().writeFromUiById(1, 0.25);
        processor.onFirstBlock = () -> slot.getParameterStore().writeFromUiById(1, 0.75);
        var input = new float[1][1031];
        var output = new float[1][1031];
        Arrays.fill(input[0], 1f);

        chain.processOffline(input, output, 1031);

        assertThat(output[0]).containsOnly(0.25f);
        assertThat(processor.preparations).isEqualTo(1);
        assertThat(processor.getGain()).isEqualTo(0.25);
        assertThat(slot.getParameterStore().valueById(1)).isEqualTo(0.75);
        chain.process(new float[1][32], new float[1][32], 32);
        assertThat(processor.getGain()).isEqualTo(0.75);
        assertThat(processor.preparations).isEqualTo(1);
    }

    @Test
    void offlineRenderingUsesBoundedPrivateScratchAndPreservesTheLivePreparation() {
        var chain = new EffectsChain();
        chain.addProcessor(new BoundedGainProcessor(0.5f, 32));
        chain.addProcessor(new GainProcessor(0.5f));
        chain.allocateIntermediateBuffers(1, 32);
        var input = new float[1][1031];
        var output = new float[1][1031];
        Arrays.fill(input[0], 1f);

        chain.processOffline(input, output, 1031);

        assertThat(output[0]).containsOnly(0.25f);
        assertThat(chain.intermediateBufferCount()).isEqualTo(1);
        var liveInput = new float[1][32];
        var liveOutput = new float[1][32];
        Arrays.fill(liveInput[0], 0.5f);
        chain.process(liveInput, liveOutput, 32);
        assertThat(liveOutput[0]).containsOnly(0.125f);
    }

    @Test
    void offlineSnapshotStaysAliveUntilTheLastBlockCompletes() throws Exception {
        var chain = new EffectsChain();
        var disposed = new java.util.concurrent.atomic.AtomicBoolean();
        var retirement = new AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
        var processedFrames = new java.util.concurrent.atomic.AtomicInteger();
        chain.addProcessor(new AudioProcessor() {
            @Override public void process(float[][] input, float[][] output, int frames) {
                if (retirement.get() == null) {
                    retirement.set(chain.retireAll(() -> disposed.set(true)));
                }
                assertThat(disposed.get()).isFalse();
                processedFrames.addAndGet(frames);
                for (int channel = 0; channel < input.length; channel++) {
                    System.arraycopy(input[channel], 0, output[channel], 0, frames);
                }
            }
            @Override public void reset() { }
            @Override public int getInputChannelCount() { return 1; }
            @Override public int getOutputChannelCount() { return 1; }
        });
        chain.addProcessor(new GainProcessor(0.5f));
        var input = new float[1][1031];
        var output = new float[1][1031];
        Arrays.fill(input[0], 1f);

        chain.processOffline(input, output, 1031);

        retirement.get().get(5, TimeUnit.SECONDS);
        assertThat(disposed.get()).isTrue();
        assertThat(processedFrames.get()).isEqualTo(1031);
        assertThat(output[0]).containsOnly(0.5f);
        assertThat(chain.isEmpty()).isTrue();
    }

    @Test
    void preparedRenderAllocatesNothingDuringAddRemoveReorderAndBypass() throws Exception {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        var channel = new MixerChannel("Concurrent inserts");
        channel.prepareEffectsChain(1, 32);
        var first = new InsertSlot("First", new GainProcessor(0.5f));
        var second = new InsertSlot("Second", new GainProcessor(0.5f));
        var third = new InsertSlot("Third", new GainProcessor(0.5f));
        channel.addInsert(first);
        channel.addInsert(second);
        var ready = new CountDownLatch(1);
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        long[] allocation = {-1};
        var input = new float[1][32];
        var output = new float[1][32];
        Arrays.fill(input[0], 1f);
        var renderer = Thread.ofPlatform().start(() -> {
            try {
                renderAndVerifyBlocks(channel.getEffectsChain(), input, output, 20_000);
                // Warm each branch that concurrent edits can expose, including dry-stage copies.
                for (int edit = 0; edit < 1000; edit++) {
                    channel.addInsert(third);
                    channel.moveInsert(2, 0);
                    renderAndVerifyBlocks(channel.getEffectsChain(), input, output, 10);
                    channel.setInsertBypassed(0, true);
                    renderAndVerifyBlocks(channel.getEffectsChain(), input, output, 10);
                    channel.setInsertBypassed(0, false);
                    channel.removeInsert(third);
                    renderAndVerifyBlocks(channel.getEffectsChain(), input, output, 10);
                }
                long thread = Thread.currentThread().threadId();
                bean.getThreadAllocatedBytes(thread);
                ready.countDown();
                start.await();
                long before = bean.getThreadAllocatedBytes(thread);
                renderAndVerifyBlocks(channel.getEffectsChain(), input, output, 50_000);
                allocation[0] = bean.getThreadAllocatedBytes(thread) - before;
            } catch (Throwable throwable) {
                failure.set(throwable);
                ready.countDown();
            }
        });
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (int edit = 0; edit < 1000; edit++) {
            channel.addInsert(third);
            channel.moveInsert(2, 0);
            channel.setInsertBypassed(0, true);
            channel.setInsertBypassed(0, false);
            channel.removeInsert(third);
        }
        renderer.join(10_000);
        assertThat(renderer.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(allocation[0]).isZero();
    }

    private static void renderAndVerifyBlocks(EffectsChain chain, float[][] input, float[][] output, int blocks) {
        for (int block = 0; block < blocks; block++) {
            chain.process(input, output, 32);
            float value = output[0][0];
            if (value != 0.125f && value != 0.25f && value != 0.5f) {
                throw new AssertionError("Incomplete insert publication");
            }
            for (float sample : output[0]) {
                if (sample != value) throw new AssertionError("Partial block processing");
            }
        }
    }

    @Test
    void concurrentWholeChainEditsPublishOnlyCompletePreparedSnapshots() throws Exception {
        var chain = new EffectsChain();
        chain.allocateIntermediateBuffers(1, 32);
        var pair = List.<AudioProcessor>of(new GainProcessor(0.5f), new GainProcessor(0.5f));
        var triple = List.<AudioProcessor>of(new GainProcessor(0.5f), new GainProcessor(0.5f), new GainProcessor(0.5f));
        chain.replaceAll(pair, Arrays.asList(null, null));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var ready = new java.util.concurrent.CountDownLatch(1);
        var renderer = Thread.ofPlatform().start(() -> {
            var input = new float[1][32];
            var output = new float[1][32];
            Arrays.fill(input[0], 1f);
            ready.countDown();
            try {
                for (int block = 0; block < 10_000; block++) {
                    chain.process(input, output, 32);
                    assertThat(output[0][0]).isIn(0.25f, 0.125f);
                    for (float sample : output[0]) { assertThat(sample).isEqualTo(output[0][0]); }
                }
            } catch (Throwable e) { failure.set(e); }
        });
        assertThat(ready.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        for (int edit = 0; edit < 1000; edit++) {
            chain.replaceAll(triple, Arrays.asList(null, null, null));
            chain.replaceAll(pair, Arrays.asList(null, null));
        }
        renderer.join(5000);
        assertThat(renderer.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

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
        chain.allocateIntermediateBuffers(1, 1);
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

    public static final class OfflinePreparedGain implements AudioProcessor, PreparedParameterProcessor {
        private double requestedGain = 1.0;
        private double preparedGain = 1.0;
        private int preparations;
        private Runnable onFirstBlock;

        @ProcessorParam(id = 1, name = "Gain", min = 0, max = 1, defaultValue = 1)
        public double getGain() { return requestedGain; }
        public void setGain(double gain) { requestedGain = gain; }
        @Override public void awaitParameterPreparation() {
            preparedGain = requestedGain;
            preparations++;
        }
        @Override public void enableRealtimeParameterPreparation() { }
        @Override public void applyPreparedParameters() { }
        @Override public void closeParameterPreparation() { }
        @Override public void process(float[][] input, float[][] output, int frames) {
            if (onFirstBlock != null) {
                onFirstBlock.run();
                onFirstBlock = null;
            }
            for (int frame = 0; frame < frames; frame++) {
                output[0][frame] = (float) (input[0][frame] * preparedGain);
            }
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }

    private record BoundedGainProcessor(float gain, int maxFrames) implements AudioProcessor {
        @Override public void process(float[][] input, float[][] output, int frames) {
            assertThat(frames).isBetween(1, maxFrames);
            for (int channel = 0; channel < input.length; channel++) {
                for (int frame = 0; frame < frames; frame++) {
                    output[channel][frame] = input[channel][frame] * gain;
                }
            }
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
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
