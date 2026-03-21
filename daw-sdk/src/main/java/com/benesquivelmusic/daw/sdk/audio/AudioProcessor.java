package com.benesquivelmusic.daw.sdk.audio;

/**
 * Interface for processing audio buffers.
 *
 * <p>Plugin developers implement this interface to apply effects, generate audio,
 * or analyze audio signals. The DAW calls {@link #process(float[][], float[][], int)}
 * on every audio buffer cycle.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>The {@code process} method is called on the real-time audio thread.
 * Implementations must avoid blocking operations (I/O, locks, allocation)
 * inside the process callback.</p>
 */
public interface AudioProcessor {

    /**
     * Processes audio data from the input buffer and writes results to the output buffer.
     *
     * <p>Each element of the outer array represents a channel; each element of the
     * inner array represents a sample frame. Sample values are in the range
     * {@code [-1.0f, 1.0f]}.</p>
     *
     * @param inputBuffer  the input audio buffer, indexed as {@code [channel][frame]}
     * @param outputBuffer the output audio buffer, indexed as {@code [channel][frame]}
     * @param numFrames    the number of sample frames to process
     */
    void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames);

    /**
     * Resets the internal state of this processor (e.g., clears delay lines).
     */
    void reset();

    /**
     * Returns the number of input channels this processor expects.
     *
     * @return number of input channels
     */
    int getInputChannelCount();

    /**
     * Returns the number of output channels this processor produces.
     *
     * @return number of output channels
     */
    int getOutputChannelCount();
}
