package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

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
 */
public final class EffectsChain {

    private final List<AudioProcessor> processors = new ArrayList<>();
    private boolean bypassed;
    private float[][][] intermediateBuffers;

    /**
     * Appends a processor to the end of the chain.
     *
     * @param processor the processor to add
     */
    public void addProcessor(AudioProcessor processor) {
        Objects.requireNonNull(processor, "processor must not be null");
        processors.add(processor);
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
    }

    /**
     * Removes a processor from the chain.
     *
     * @param processor the processor to remove
     * @return {@code true} if the processor was removed
     */
    public boolean removeProcessor(AudioProcessor processor) {
        return processors.remove(processor);
    }

    /**
     * Removes the processor at the specified index.
     *
     * @param index the index of the processor to remove
     * @return the removed processor
     */
    public AudioProcessor removeProcessor(int index) {
        return processors.remove(index);
    }

    /**
     * Returns an unmodifiable view of the processors in the chain.
     *
     * @return the list of processors
     */
    public List<AudioProcessor> getProcessors() {
        return Collections.unmodifiableList(processors);
    }

    /** Returns the number of processors in the chain. */
    public int size() {
        return processors.size();
    }

    /** Returns whether the chain is empty. */
    public boolean isEmpty() {
        return processors.isEmpty();
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
        int maxIntermediateNeeded = Math.max(processors.size() - 1, 0);
        intermediateBuffers = new float[maxIntermediateNeeded][channels][frames];
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
        if (bypassed || processors.isEmpty()) {
            copyBuffer(inputBuffer, outputBuffer, numFrames);
            return;
        }

        float[][] currentInput = inputBuffer;
        for (int i = 0; i < processors.size(); i++) {
            float[][] currentOutput;
            if (i == processors.size() - 1) {
                currentOutput = outputBuffer;
            } else if (intermediateBuffers != null && i < intermediateBuffers.length) {
                currentOutput = intermediateBuffers[i];
                clearBuffer(currentOutput, numFrames);
            } else {
                currentOutput = createTempBuffer(outputBuffer.length, numFrames);
            }
            processors.get(i).process(currentInput, currentOutput, numFrames);
            currentInput = currentOutput;
        }
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
        if (bypassed || processors.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AudioProcessor processor : processors) {
            total += processor.getLatencySamples();
        }
        return total;
    }

    /**
     * Resets all processors in the chain.
     */
    public void reset() {
        for (AudioProcessor processor : processors) {
            processor.reset();
        }
    }

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

    private static float[][] createTempBuffer(int channels, int frames) {
        return new float[channels][frames];
    }
}
