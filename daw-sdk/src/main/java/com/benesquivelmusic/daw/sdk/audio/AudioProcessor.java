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

    /**
     * Indicates whether this processor natively supports 64-bit
     * double-precision audio I/O via
     * {@link #processDouble(double[][], double[][], int)}.
     *
     * <p>The default implementation returns {@code false}: processors that
     * benefit from double precision on the internal mix bus (EQs,
     * compressors, limiters, linear-phase processors, convolution reverbs)
     * should override this method to return {@code true} <em>and</em>
     * provide a native {@link #processDouble} implementation. Processors
     * that remain in {@code float} (the vast majority) inherit the default
     * double adapter implementation, which the DAW invokes transparently.</p>
     *
     * @return {@code true} if this processor has a native {@code double}
     *         processing path; {@code false} otherwise
     * @see MixPrecision
     */
    default boolean supportsDouble() {
        return false;
    }

    /**
     * Processes audio data in 64-bit double precision.
     *
     * <p>The default implementation is a transparent adapter that narrows
     * {@code double} input to {@code float}, delegates to
     * {@link #process(float[][], float[][], int)}, and widens the
     * {@code float} output back to {@code double}. This allows the DAW
     * to keep its summing bus in double precision without requiring every
     * processor to be rewritten.</p>
     *
     * <p>Processors that override {@link #supportsDouble()} to return
     * {@code true} should override this method with a native
     * double-precision implementation that bypasses the narrowing adapter.
     * Implementations must remain allocation-free and real-time safe.</p>
     *
     * <p><strong>Note:</strong> The default adapter allocates scratch
     * {@code float} buffers and is therefore not real-time safe on its own.
     * Hosts that call {@code processDouble} on the audio thread for
     * non-double-aware processors must provide pre-allocated narrowing
     * buffers via a dedicated adapter; see {@code MixerChannel} for the
     * reference implementation.</p>
     *
     * @param inputBuffer  the input audio buffer, indexed as {@code [channel][frame]}
     * @param outputBuffer the output audio buffer, indexed as {@code [channel][frame]}
     * @param numFrames    the number of sample frames to process
     * @since the 64-bit mix bus feature
     */
    default void processDouble(double[][] inputBuffer, double[][] outputBuffer, int numFrames) {
        int channels = Math.min(inputBuffer.length, outputBuffer.length);
        float[][] floatIn = new float[channels][numFrames];
        float[][] floatOut = new float[channels][numFrames];
        for (int ch = 0; ch < channels; ch++) {
            for (int f = 0; f < numFrames; f++) {
                floatIn[ch][f] = (float) inputBuffer[ch][f];
            }
        }
        process(floatIn, floatOut, numFrames);
        for (int ch = 0; ch < channels; ch++) {
            for (int f = 0; f < numFrames; f++) {
                outputBuffer[ch][f] = floatOut[ch][f];
            }
        }
    }

    /**
     * Returns the processing latency introduced by this processor, in samples.
     *
     * <p>Processors that buffer samples internally (e.g., linear-phase EQs,
     * look-ahead compressors, oversampled effects, convolution reverbs) should
     * override this method to report their latency so that the DAW can apply
     * plugin delay compensation (PDC) to keep all mixer channels aligned at
     * the summing bus.</p>
     *
     * <p>The default implementation returns {@code 0}, indicating no additional
     * latency — suitable for most processors that produce output immediately.</p>
     *
     * @return latency in sample frames, always &ge; 0
     */
    default int getLatencySamples() {
        return 0;
    }
}
