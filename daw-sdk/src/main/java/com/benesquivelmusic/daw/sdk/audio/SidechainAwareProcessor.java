package com.benesquivelmusic.daw.sdk.audio;

/**
 * An {@link AudioProcessor} that can use an external sidechain signal for
 * detection (e.g., envelope following, gate triggering) while applying gain
 * changes to the main input signal.
 *
 * <p>When a sidechain source is configured in the mixer, the DAW calls
 * {@link #processSidechain(float[][], float[][], float[][], int)} instead
 * of the regular {@link #process(float[][], float[][], int)} method. The
 * sidechain buffer drives the processor's detector stage while the main
 * input passes through the gain-reduction stage.</p>
 *
 * <p>If no sidechain source is configured, the DAW calls the standard
 * {@code process} method and the processor uses its own input for
 * detection (internal sidechain).</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Like {@link AudioProcessor#process}, the {@code processSidechain}
 * method is called on the real-time audio thread. Implementations must
 * avoid blocking operations (I/O, locks, allocation).</p>
 */
public interface SidechainAwareProcessor extends AudioProcessor {

    /**
     * Processes audio with an external sidechain signal.
     *
     * <p>The {@code sidechainBuffer} drives the processor's detection stage
     * (envelope follower, gate trigger, etc.) while the {@code inputBuffer}
     * passes through the gain-reduction or gating stage and is written to
     * {@code outputBuffer}.</p>
     *
     * <p>Each buffer is indexed as {@code [channel][frame]}. Sample values
     * are in the range {@code [-1.0f, 1.0f]}.</p>
     *
     * @param inputBuffer     the main audio input {@code [channel][frame]}
     * @param sidechainBuffer the sidechain detection input {@code [channel][frame]}
     * @param outputBuffer    the output audio buffer {@code [channel][frame]}
     * @param numFrames       the number of sample frames to process
     */
    void processSidechain(float[][] inputBuffer, float[][] sidechainBuffer,
                          float[][] outputBuffer, int numFrames);
}
