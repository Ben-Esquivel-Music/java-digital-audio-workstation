package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.dsp.PreparedParameterProcessor;
import com.benesquivelmusic.daw.core.metering.InsertTapPair;
import com.benesquivelmusic.daw.core.metering.LevelTapSlot;
import com.benesquivelmusic.daw.core.metering.SampleBlockRing;
import com.benesquivelmusic.daw.core.metering.TapSnapshot;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.DynamicLatencyProcessor;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;

import java.util.*;

/**
 * An ordered chain of {@link AudioProcessor} instances applied in series.
 *
 * <p>Audio passes through each processor in insertion order. The output
 * of one processor feeds the input of the next. The chain can be
 * bypassed, in which case input is copied directly to output.</p>
 *
 * <p>Intermediate buffers are pre-allocated via {@link #allocateIntermediateBuffers(int, int)}
 * so that {@link #process(float[][], float[][], int)} performs zero heap allocations —
 * making it safe to call on the real-time audio thread.</p>
 *
 * <h2>{@code INSERT_IO} metering (story 318)</h2>
 * <p>Each processor may carry an optional <em>tag</em>
 * ({@link #addProcessor(AudioProcessor, Object)}); the mixer's
 * {@code MixerChannel.rebuildEffectsChain} tags every processor with its
 * {@link InsertSlot}, so the tap hook lives here — where the supervisor
 * wrapping is preserved — rather than on the raw slot list. The tapped
 * overloads {@link #process(float[][], float[][], int, TapSnapshot)} and
 * {@link #processDouble(double[][], double[][], int, TapSnapshot)} resolve
 * each tag through {@link TapSnapshot#insertTapFor(InsertSlot)} (an identity
 * scan) and, only for a tapped processor, accumulate the input frame before
 * and the output frame after the call. A processor absent from the chain
 * (bypassed slot) publishes nothing.</p>
 *
 * <p>Sidechain processing uses the same captured links, supervisor wrappers
 * and insert taps. A block-scoped resolver supplies each slot's detection
 * input without traversing the mutable control-thread slot list.</p>
 *
 * <p>Processor and tag are published to the render thread as ONE immutable
 * array ({@code Link[]}, read once per {@code process} call), so a single
 * insert add / remove / reorder — and a whole-chain
 * {@link #replaceAll(List, List)} — can never be observed half-applied by
 * the audio thread. A <em>sequence</em> of individual mutators is a sequence
 * of publications: the audio thread may run any prefix state between them,
 * which is why {@code MixerChannel.rebuildEffectsChain()} rebuilds through
 * {@link #replaceAll(List, List)} rather than a drain-then-refill loop
 * (which would hand the render thread an empty chain — one fully dry
 * block — in the middle of a bypass toggle or a reorder).</p>
 */
public final class EffectsChain {

    private static final int DEFAULT_OFFLINE_BLOCK_FRAMES = 256;

    @FunctionalInterface
    public interface SidechainInputResolver {
        float[][] resolve(InsertSlot slot);
    }

    /** One chain position: the processor that runs and the tag it was added with. */
    private record Link(AudioProcessor processor, Object tag) {
    }

    private static final Link[] NO_LINKS = new Link[0];

    private final List<AudioProcessor> processors = new ArrayList<>();
    /** Parallel to {@link #processors}: the optional tag of each processor ({@code null} when untagged). */
    private final List<Object> tags = new ArrayList<>();

    /**
     * The render thread's view of the chain: processor and tag in ONE
     * immutable array, rebuilt off the render thread after every mutation and
     * swapped with a single volatile store (the house
     * {@code volatile T[] snapshot} idiom of {@code ChangeNotifier}).
     *
     * <p>The whole render PATH reads this field — the loops, and the
     * {@link #isEmpty()} / {@link #size()} guards the mixer and the
     * {@code AudioGraphScheduler} test before entering them — and never
     * touches {@link #processors} / {@link #tags}, so a concurrent
     * {@code MixerChannel.rebuildEffectsChain()} on the UI thread can never
     * show them a half-built chain — in particular never a processor whose
     * parallel tag entry has not been appended yet, which two independently
     * mutated {@link ArrayList}s would (an {@code IndexOutOfBoundsException}
     * on the audio thread), and never a guard that says "non-empty" while
     * the loop it guards sees an empty chain. A block that starts before the
     * swap runs the old chain to completion; the next block runs the new
     * one.</p>
     */
    private record Snapshot(Link[] links, float[][][] floats, double[][][] doubles) { }
    private volatile Snapshot snapshot = new Snapshot(NO_LINKS, null, null);
    private volatile long renderStarted;
    private volatile long renderFinished;
    private int renderDepth;
    private static final IllegalStateException UNPREPARED = new IllegalStateException("Prepare effects-chain buffers before rendering");

    /**
     * Read on the render path beside {@link #snapshot} and written off it, so it
     * carries the same visibility guarantee: a bypass toggle with no
     * accompanying chain mutation still reaches the audio thread.
     */
    private volatile boolean bypassed;

    /**
     * Control-thread scratch ownership. Every publication captures these arrays
     * in the same immutable snapshot as its processor links. The render thread
     * reads only the captured arrays, including when a format change replaces them.
     */
    private volatile float[][][] intermediateBuffers;
    /** The 64-bit twin of {@link #intermediateBuffers}, on the same publication rule. */
    private volatile double[][][] intermediateDoubleBuffers;

    /** Dimensions of the last {@link #allocateIntermediateBuffers(int, int)}; 0 until it is called. */
    private int bufferChannels;
    private int bufferFrames;
    /** Dimensions of the last {@link #allocateIntermediateDoubleBuffers(int, int)}. */
    private int doubleBufferChannels;
    private int doubleBufferFrames;

    /**
     * Republishes {@link #snapshot} from the mutable lists, growing the
     * intermediate buffers to match FIRST so that one volatile store
     * publishes the chain and the scratch it needs together. Off the render
     * thread only.
     */
    private void republishLinks() {
        Link[] rebuilt = new Link[processors.size()];
        for (int i = 0; i < rebuilt.length; i++) {
            rebuilt[i] = new Link(processors.get(i), tags.get(i));
        }
        growIntermediateBuffers(Math.max(rebuilt.length - 1, 0));
        snapshot = new Snapshot(rebuilt, intermediateBuffers, intermediateDoubleBuffers);
    }

    /**
     * Grows the pre-allocated scratch to {@code needed} positions, reusing
     * the dimensions of the last explicit {@code allocateIntermediate*}
     * call. A multi-processor chain must be prepared before rendering; a chain that shrinks
     * keeps its larger arrays — the render loops index by position, so extra
     * entries are inert and re-allocating would only add garbage.
     */
    private void growIntermediateBuffers(int needed) {
        if (needed > 0 && bufferChannels > 0 && bufferFrames > 0
                && (intermediateBuffers == null || intermediateBuffers.length < needed)) {
            intermediateBuffers = new float[needed][bufferChannels][bufferFrames];
        }
        if (needed > 0 && doubleBufferChannels > 0 && doubleBufferFrames > 0
                && (intermediateDoubleBuffers == null || intermediateDoubleBuffers.length < needed)) {
            intermediateDoubleBuffers = new double[needed][doubleBufferChannels][doubleBufferFrames];
        }
    }

    /**
     * Replaces the whole chain in ONE publication: the render thread sees
     * either the entire old chain or the entire new one, never a prefix of
     * either.
     *
     * <p>This is what {@code MixerChannel.rebuildEffectsChain()} calls. The
     * per-mutator {@code republishLinks()} of {@link #addProcessor} and
     * {@link #removeProcessor} is atomic per mutation, but a rebuild
     * expressed as "remove every processor, then add the surviving ones"
     * publishes {@code N + M} intermediate chains — including the EMPTY one,
     * which a block landing in that window would run as a fully dry
     * pass-through (an audible drop on a compressed or reverb-fed channel).
     * One store, one allocation of the scratch, no such window.</p>
     *
     * @param newProcessors the processors in chain order
     * @param newTags       the tag of each processor, same size as
     *                      {@code newProcessors}; entries may be {@code null}
     * @throws IllegalArgumentException if the two lists differ in size
     */
    public void replaceAll(List<AudioProcessor> newProcessors, List<Object> newTags) {
        Objects.requireNonNull(newProcessors, "newProcessors must not be null");
        Objects.requireNonNull(newTags, "newTags must not be null");
        if (newProcessors.size() != newTags.size()) {
            throw new IllegalArgumentException("processors and tags must have the same size: "
                    + newProcessors.size() + " != " + newTags.size());
        }
        for (AudioProcessor processor : newProcessors) {
            Objects.requireNonNull(processor, "processor must not be null");
        }
        processors.clear();
        processors.addAll(newProcessors);
        tags.clear();
        tags.addAll(newTags);
        republishLinks();
    }

    /**
     * Appends a processor to the end of the chain.
     *
     * @param processor the processor to add
     */
    public void addProcessor(AudioProcessor processor) {
        addProcessor(processor, null);
    }

    /**
     * Appends a processor carrying an optional tag. The mixer tags each
     * processor with the {@link InsertSlot} it was built from so the
     * tapped {@code process} overloads can resolve its {@code INSERT_IO}
     * tap pair by identity.
     *
     * @param processor the processor to add
     * @param tag       the tag, or {@code null} when untagged
     */
    public void addProcessor(AudioProcessor processor, Object tag) {
        Objects.requireNonNull(processor, "processor must not be null");
        processors.add(processor);
        tags.add(tag);
        republishLinks();
    }

    /**
     * Inserts a processor at the specified index.
     *
     * @param index     the insertion index
     * @param processor the processor to insert
     */
    public void insertProcessor(int index, AudioProcessor processor) {
        Objects.requireNonNull(processor, "processor must not be null");
        processors.add(index, processor);
        tags.add(index, null);
        republishLinks();
    }

    /**
     * Removes a processor from the chain.
     *
     * @param processor the processor to remove
     * @return {@code true} if the processor was removed
     */
    public boolean removeProcessor(AudioProcessor processor) {
        int index = processors.indexOf(processor);
        if (index < 0) {
            return false;
        }
        processors.remove(index);
        tags.remove(index);
        republishLinks();
        return true;
    }

    /**
     * Removes the processor at the specified index.
     *
     * @param index the index of the processor to remove
     * @return the removed processor
     */
    public AudioProcessor removeProcessor(int index) {
        AudioProcessor removed = processors.remove(index);
        tags.remove(index);
        republishLinks();
        return removed;
    }

    /**
     * Visible for testing: the number of pre-allocated ping-pong scratch
     * slots currently published. A multi-processor chain with zero scratch
     * must be prepared before entering the render loop.
     */
    int intermediateBufferCount() {
        float[][][] scratch = intermediateBuffers;
        return scratch == null ? 0 : scratch.length;
    }

    /**
     * Returns the tag of the processor at {@code index}, or empty when it
     * was added untagged.
     *
     * @param index the processor index
     * @return the tag, if any
     */
    public Optional<Object> getTag(int index) {
        return Optional.ofNullable(tags.get(index));
    }

    /**
     * Returns an unmodifiable view of the processors in the chain.
     *
     * @return the list of processors
     */
    public List<AudioProcessor> getProcessors() {
        return Collections.unmodifiableList(processors);
    }

    /**
     * Returns the number of processors in the chain.
     *
     * <p>Reads the published {@link #snapshot} snapshot, not the mutable list:
     * this is a render-path query (the mixer and the {@code AudioGraphScheduler}
     * gate on {@link #isEmpty()} before entering {@code process}), so it must
     * answer for the same chain the loop will run.</p>
     */
    @RealTimeSafe
    public int size() {
        return snapshot.links().length;
    }

    /**
     * Returns whether the chain is empty, from the published
     * {@link #snapshot} snapshot — see {@link #size()}.
     */
    @RealTimeSafe
    public boolean isEmpty() {
        return snapshot.links().length == 0;
    }

    /** Returns whether the chain is bypassed. */
    public boolean isBypassed() {
        return bypassed;
    }

    /** Sets the bypassed state. */
    public void setBypassed(boolean bypassed) {
        this.bypassed = bypassed;
    }

    /**
     * Pre-allocates intermediate buffers for real-time-safe processing.
     *
     * <p>Call this method before entering the audio processing loop. Once
     * allocated, {@link #process(float[][], float[][], int)} will reuse
     * these buffers instead of allocating on each call.</p>
     *
     * <p>The dimensions are remembered: a later chain mutation re-sizes the
     * scratch itself, inside the same publication as the new chain, so
     * growing a live chain always publishes sufficient scratch.</p>
     *
     * @param channels the number of audio channels
     * @param frames   the number of sample frames per buffer
     */
    public void allocateIntermediateBuffers(int channels, int frames) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        this.bufferChannels = channels;
        this.bufferFrames = frames;
        int maxIntermediateNeeded = Math.max(snapshot.links().length - 1, 0);
        intermediateBuffers = new float[maxIntermediateNeeded][channels][frames];
        republishLinks();
    }

    /**
     * Processes audio through the entire chain.
     *
     * <p>If the chain is bypassed or empty, input is copied directly
     * to output.</p>
     *
     * <p>When intermediate buffers have been pre-allocated via
     * {@link #allocateIntermediateBuffers(int, int)}, this method performs
     * zero heap allocations — safe for the real-time audio thread.</p>
     *
     * @param inputBuffer  input audio data {@code [channel][frame]}
     * @param outputBuffer output audio data {@code [channel][frame]}
     * @param numFrames    the number of frames to process
     */
    @RealTimeSafe
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        process(inputBuffer, outputBuffer, numFrames, null);
    }

    /**
     * Processes audio through the entire chain, publishing an
     * {@code INSERT_IO} frame pair for every processor whose tag resolves
     * through {@code taps} (story 318). Identical to
     * {@link #process(float[][], float[][], int)} when {@code taps} is
     * {@code null} or resolves no tag: the only extra work is one identity
     * scan per processor, and a pass over the buffers happens only for a
     * tapped processor. Single-writer per block per slot even when the chain
     * runs on an {@code AudioGraphScheduler} worker, because one channel's
     * chain runs on exactly one thread per block.
     *
     * @param inputBuffer  input audio data {@code [channel][frame]}
     * @param outputBuffer output audio data {@code [channel][frame]}
     * @param numFrames    the number of frames to process
     * @param taps         the block's tap snapshot, or {@code null} when untapped
     */
    @RealTimeSafe
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames,
                        TapSnapshot taps) {
        enterRender();
        try {
            Snapshot captured = snapshot;
            drainParameters(captured.links());
            processSnapshot(captured, inputBuffer, outputBuffer, numFrames, taps, null);
        } finally {
            leaveRender();
        }
    }

    /**
     * Processes a complete offline render in bounded blocks using private scratch.
     * Works before playback preparation and for renders larger than the live block
     * size, without replacing the buffers published to the real-time renderer.
     * Pending editor values are applied and their DSP preparation is awaited once
     * before the first block; later editor writes remain queued for the next render.
     * The caller must own the processors exclusively for the duration of this call,
     * just as with {@link #process(float[][], float[][], int)}.
     *
     * @param inputBuffer input audio data {@code [channel][frame]}
     * @param outputBuffer output audio data {@code [channel][frame]}
     * @param numFrames number of frames in the offline render
     */
    public void processOffline(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        Objects.requireNonNull(inputBuffer, "inputBuffer must not be null");
        Objects.requireNonNull(outputBuffer, "outputBuffer must not be null");
        if (numFrames < 0) {
            throw new IllegalArgumentException("numFrames must not be negative");
        }
        if (numFrames == 0) {
            return;
        }
        enterRender();
        try {
            Snapshot captured = snapshot;
            prepareOfflineParameters(captured.links());
            int blockFrames = Math.min(numFrames,
                    bufferFrames > 0 ? bufferFrames : DEFAULT_OFFLINE_BLOCK_FRAMES);
            float[][] blockInput = new float[inputBuffer.length][blockFrames];
            float[][] blockOutput = new float[outputBuffer.length][blockFrames];
            float[][][] scratch = new float[Math.max(captured.links().length - 1, 0)]
                    [outputBuffer.length][blockFrames];
            Snapshot offline = new Snapshot(captured.links(), scratch, null);
            for (int offset = 0; offset < numFrames;) {
                int frames = Math.min(blockFrames, numFrames - offset);
                for (int channel = 0; channel < inputBuffer.length; channel++) {
                    System.arraycopy(inputBuffer[channel], offset, blockInput[channel], 0, frames);
                }
                clearBuffer(blockOutput, frames);
                processSnapshot(offline, blockInput, blockOutput, frames, null, null);
                for (int channel = 0; channel < outputBuffer.length; channel++) {
                    System.arraycopy(blockOutput[channel], 0, outputBuffer[channel], offset, frames);
                }
                offset += frames;
            }
        } finally {
            leaveRender();
        }
    }

    @RealTimeSafe
    private void processSnapshot(Snapshot captured, float[][] inputBuffer, float[][] outputBuffer, int numFrames,
                                 TapSnapshot taps, SidechainInputResolver sidechainResolver) {
        Link[] chain = captured.links();
        // Processor links and their scratch come from the same publication.
        float[][][] scratch = captured.floats();
        if (bypassed || chain.length == 0) {
            copyBuffer(inputBuffer, outputBuffer, numFrames);
            return;
        }

        long tapEpoch = taps != null ? taps.epoch() : 0L;
        long tapBlock = taps != null ? taps.blockIndex() : 0L;
        float[][] currentInput = inputBuffer;
        for (int i = 0; i < chain.length; i++) {
            float[][] currentOutput;
            if (i == chain.length - 1) {
                currentOutput = outputBuffer;
            } else if (scratch != null && i < scratch.length) {
                currentOutput = scratch[i];
                clearBuffer(currentOutput, numFrames);
            } else {
                throw UNPREPARED;
            }
            InsertTapPair pair = tapPairFor(taps, chain[i].tag());
            if (pair != null) {
                accumulateFrame(pair.input(), currentInput, numFrames, tapEpoch, tapBlock);
            }
            if (chain[i].tag() instanceof InsertSlot slot && slot.isBypassed()) {
                copyBuffer(currentInput, currentOutput, numFrames);
            } else if (sidechainResolver != null && chain[i].tag() instanceof InsertSlot slot
                    && chain[i].processor() instanceof SidechainAwareProcessor sidechainProcessor) {
                float[][] sidechain = sidechainResolver.resolve(slot);
                if (sidechain != null) {
                    sidechainProcessor.processSidechain(currentInput, sidechain, currentOutput, numFrames);
                } else {
                    chain[i].processor().process(currentInput, currentOutput, numFrames);
                }
            } else {
                chain[i].processor().process(currentInput, currentOutput, numFrames);
            }
            if (pair != null) {
                accumulateFrame(pair.output(), currentOutput, numFrames, tapEpoch, tapBlock);
                SampleBlockRing[] rings = pair.output().rings();
                for (int r = 0; r < rings.length; r++) {
                    rings[r].write(currentOutput, currentOutput.length, numFrames);
                }
            }
            currentInput = currentOutput;
        }
    }

    /** Uses the same immutable supervised links and meter hooks for sidechain routing. */
    @RealTimeSafe
    public void processWithSidechain(float[][] input, float[][] output, int frames,
                                     TapSnapshot taps, SidechainInputResolver resolver) {
        enterRender();
        try {
            Snapshot captured = snapshot;
            drainParameters(captured.links());
            processSnapshot(captured, input, output, frames, taps, resolver);
        } finally {
            leaveRender();
        }
    }

    @RealTimeSafe
    private static void drainParameters(Link[] chain) {
        for (int i = 0; i < chain.length; i++) {
            if (chain[i].tag() instanceof InsertSlot slot) {
                slot.drainParametersToAudio();
            }
        }
    }

    private static void prepareOfflineParameters(Link[] chain) {
        for (Link link : chain) {
            if (link.tag() instanceof InsertSlot slot) {
                slot.prepareParametersForOfflineRendering();
            } else if (link.processor() instanceof PreparedParameterProcessor prepared) {
                prepared.awaitParameterPreparation();
            }
        }
    }

    /** The {@code INSERT_IO} pair for a chain position's tag, or {@code null} when untapped. */
    @RealTimeSafe
    private static InsertTapPair tapPairFor(TapSnapshot taps, Object tag) {
        if (taps == null) {
            return null;
        }
        return tag instanceof InsertSlot slot ? taps.insertTapFor(slot) : null;
    }

    /** One tap frame over {@code buffer}: begin, fold every lane, publish. */
    @RealTimeSafe
    private static void accumulateFrame(LevelTapSlot slot, float[][] buffer, int numFrames,
                                        long epoch, long blockIndex) {
        slot.beginBlock(epoch, blockIndex, buffer.length);
        for (int ch = 0; ch < buffer.length; ch++) {
            slot.accumulate(ch, buffer[ch], numFrames);
        }
        slot.publish(numFrames);
    }

    /** {@link #accumulateFrame(LevelTapSlot, float[][], int, long, long)} for the 64-bit path. */
    @RealTimeSafe
    private static void accumulateFrame(LevelTapSlot slot, double[][] buffer, int numFrames,
                                        long epoch, long blockIndex) {
        slot.beginBlock(epoch, blockIndex, buffer.length);
        for (int ch = 0; ch < buffer.length; ch++) {
            slot.accumulate(ch, buffer[ch], numFrames);
        }
        slot.publish(numFrames);
    }

    /**
     * Returns the total processing latency of this chain, in samples.
     *
     * <p>The total latency is the sum of {@link AudioProcessor#getLatencySamples()}
     * across all processors currently in the chain. If the chain is
     * {@linkplain #isBypassed() bypassed} or empty, the latency is zero.</p>
     *
     * @return total latency in sample frames, always &ge; 0
     */
    public int getTotalLatencySamples() {
        if (bypassed) {
            return 0;
        }
        int total = 0;
        for (Link link : snapshot.links()) {
            total += link.processor().getLatencySamples();
        }
        return total;
    }

    /** Captures native/static latency on the control thread, never in the monitor. */
    LatencySnapshot captureLatency() {
        int fixed = 0;
        var dynamic = new ArrayList<DynamicLatencyProcessor>();
        for (Link link : snapshot.links()) {
            AudioProcessor source = link.tag() instanceof InsertSlot slot ? slot.getProcessor() : link.processor();
            if (source instanceof DynamicLatencyProcessor live) {
                dynamic.add(live);
            } else {
                fixed += link.processor().getLatencySamples();
            }
        }
        return new LatencySnapshot(this, fixed, List.copyOf(dynamic));
    }

    record LatencySnapshot(EffectsChain chain, int fixed, List<DynamicLatencyProcessor> dynamic) {
        int samples() {
            if (chain.isBypassed()) return 0;
            int total = fixed;
            for (DynamicLatencyProcessor processor : dynamic) total += processor.getLatencySamples();
            return total;
        }
    }

    /**
     * Processes audio through the entire chain in 64-bit double precision.
     *
     * <p>For processors that report {@link AudioProcessor#supportsDouble()},
     * the native {@code processDouble} path is used. For processors that
     * do not, the default narrowing adapter on {@code AudioProcessor} is
     * invoked — note that the default adapter allocates scratch buffers,
     * so calling this method on the real-time audio thread is only safe
     * when all processors in the chain support double or intermediate
     * double buffers have been pre-allocated via
     * {@link #allocateIntermediateDoubleBuffers(int, int)}.</p>
     *
     * @param inputBuffer  input audio data {@code [channel][frame]}
     * @param outputBuffer output audio data {@code [channel][frame]}
     * @param numFrames    the number of frames to process
     */
    public void processDouble(double[][] inputBuffer, double[][] outputBuffer, int numFrames) {
        processDouble(inputBuffer, outputBuffer, numFrames, null);
    }

    /**
     * {@link #processDouble(double[][], double[][], int)} with the
     * {@code INSERT_IO} tap hook of
     * {@link #process(float[][], float[][], int, TapSnapshot)} — the mixer's
     * return-bus chains run here under {@code MixPrecision.DOUBLE_64}, so
     * their insert meters would otherwise stay dark in the default precision.
     * Carries the same allocation caveat as the untapped overload.
     *
     * <p>A processor without native double support may still use the SDK's
     * allocating narrowing adapter, so this method is not marked real-time
     * safe. The chain itself uses only prepared snapshot scratch and never
     * allocates a fallback buffer.</p>
     *
     * @param inputBuffer  input audio data {@code [channel][frame]}
     * @param outputBuffer output audio data {@code [channel][frame]}
     * @param numFrames    the number of frames to process
     * @param taps         the block's tap snapshot, or {@code null} when untapped
     */
    public void processDouble(double[][] inputBuffer, double[][] outputBuffer, int numFrames,
                              TapSnapshot taps) {
        enterRender();
        try {
            processDoubleSnapshot(inputBuffer, outputBuffer, numFrames, taps);
        } finally {
            leaveRender();
        }
    }

    private void processDoubleSnapshot(double[][] inputBuffer, double[][] outputBuffer, int numFrames,
                                       TapSnapshot taps) {
        Snapshot captured = snapshot;
        Link[] chain = captured.links();
        // See process(...): the scratch is published before the chain it belongs to.
        double[][][] scratch = captured.doubles();
        drainParameters(chain);
        if (bypassed || chain.length == 0) {
            copyBufferDouble(inputBuffer, outputBuffer, numFrames);
            return;
        }

        long tapEpoch = taps != null ? taps.epoch() : 0L;
        long tapBlock = taps != null ? taps.blockIndex() : 0L;
        double[][] currentInput = inputBuffer;
        for (int i = 0; i < chain.length; i++) {
            double[][] currentOutput;
            if (i == chain.length - 1) {
                currentOutput = outputBuffer;
            } else if (scratch != null && i < scratch.length) {
                currentOutput = scratch[i];
                clearBufferDouble(currentOutput, numFrames);
            } else {
                throw UNPREPARED;
            }
            InsertTapPair pair = tapPairFor(taps, chain[i].tag());
            if (pair != null) {
                accumulateFrame(pair.input(), currentInput, numFrames, tapEpoch, tapBlock);
            }
            if (chain[i].tag() instanceof InsertSlot slot && slot.isBypassed()) {
                copyBufferDouble(currentInput, currentOutput, numFrames);
            } else {
                chain[i].processor().processDouble(currentInput, currentOutput, numFrames);
            }
            if (pair != null) {
                accumulateFrame(pair.output(), currentOutput, numFrames, tapEpoch, tapBlock);
                SampleBlockRing[] rings = pair.output().rings();
                for (int r = 0; r < rings.length; r++) {
                    rings[r].write(currentOutput, currentOutput.length, numFrames);
                }
            }
            currentInput = currentOutput;
        }
    }

    /**
     * Pre-allocates double-precision intermediate buffers for the
     * {@link #processDouble} path. Call alongside
     * {@link #allocateIntermediateBuffers(int, int)} when the mixer is
     * configured for {@link com.benesquivelmusic.daw.sdk.audio.MixPrecision#DOUBLE_64}.
     *
     * @param channels the number of audio channels
     * @param frames   the number of sample frames per buffer
     */
    public void allocateIntermediateDoubleBuffers(int channels, int frames) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        this.doubleBufferChannels = channels;
        this.doubleBufferFrames = frames;
        int maxIntermediateNeeded = Math.max(snapshot.links().length - 1, 0);
        intermediateDoubleBuffers = new double[maxIntermediateNeeded][channels][frames];
        republishLinks();
    }

    /**
     * Resets all processors in the chain.
     */
    public void reset() {
        for (AudioProcessor processor : processors) {
            processor.reset();
        }
    }

    /** Removes the graph first, then closes resources after every old render has left. */
    public java.util.concurrent.CompletableFuture<Void> retireAll(Runnable disposal) {
        replaceAll(List.of(), List.of());
        long retirementGeneration = renderStarted;
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        // Virtual threads (JEP 444, final): retirement waits never occupy the audio or FX thread.
        Thread.ofVirtual().name("insert-retirement").start(() -> {
            while (renderFinished < retirementGeneration) {
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            try {
                disposal.run();
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
        return completion;
    }

    /**
     * Covers sidechain and parameter draining in the same retirement generation.
     * A chain has one render consumer, as required by its shared DSP/scratch state;
     * the scheduler transfers that ownership between non-overlapping tasks.
     * Nested calls publish completion only when the outermost consumer exits.
     */
    @RealTimeSafe public void enterRender() { if (renderDepth++ == 0) { renderStarted++; } }
    @RealTimeSafe public void leaveRender() { if (--renderDepth == 0) { renderFinished = renderStarted; } }

    private static void copyBuffer(float[][] src, float[][] dst, int numFrames) {
        int channels = Math.min(src.length, dst.length);
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(src[ch], 0, dst[ch], 0, numFrames);
        }
    }

    private static void clearBuffer(float[][] buffer, int numFrames) {
        for (float[] channel : buffer) {
            Arrays.fill(channel, 0, numFrames, 0.0f);
        }
    }


    private static void copyBufferDouble(double[][] src, double[][] dst, int numFrames) {
        int channels = Math.min(src.length, dst.length);
        for (int ch = 0; ch < channels; ch++) {
            System.arraycopy(src[ch], 0, dst[ch], 0, numFrames);
        }
    }

    private static void clearBufferDouble(double[][] buffer, int numFrames) {
        for (double[] channel : buffer) {
            Arrays.fill(channel, 0, numFrames, 0.0);
        }
    }

}
