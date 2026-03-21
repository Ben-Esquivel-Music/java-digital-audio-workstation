package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An ordered chain of {@link AudioProcessor} instances applied in series.
 *
 * <p>Audio passes through each processor in insertion order. The output
 * of one processor feeds the input of the next. The chain can be
 * bypassed, in which case input is copied directly to output.</p>
 */
public final class EffectsChain {

    private final List<AudioProcessor> processors = new ArrayList<>();
    private boolean bypassed;

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
     * Processes audio through the entire chain.
     *
     * <p>If the chain is bypassed or empty, input is copied directly
     * to output.</p>
     *
     * @param inputBuffer  input audio data {@code [channel][frame]}
     * @param outputBuffer output audio data {@code [channel][frame]}
     * @param numFrames    the number of frames to process
     */
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (bypassed || processors.isEmpty()) {
            copyBuffer(inputBuffer, outputBuffer, numFrames);
            return;
        }

        float[][] currentInput = inputBuffer;
        for (int i = 0; i < processors.size(); i++) {
            float[][] currentOutput = (i == processors.size() - 1)
                    ? outputBuffer
                    : createTempBuffer(outputBuffer.length, numFrames);
            processors.get(i).process(currentInput, currentOutput, numFrames);
            currentInput = currentOutput;
        }
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

    private static float[][] createTempBuffer(int channels, int frames) {
        return new float[channels][frames];
    }
}
