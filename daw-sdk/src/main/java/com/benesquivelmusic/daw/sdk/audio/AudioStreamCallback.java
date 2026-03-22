package com.benesquivelmusic.daw.sdk.audio;

/**
 * Callback interface for real-time audio processing.
 *
 * <p>Implementations receive audio input and must fill the output buffer
 * with processed audio. The callback is invoked on a high-priority
 * audio thread and must avoid blocking operations.</p>
 */
@FunctionalInterface
public interface AudioStreamCallback {

    /**
     * Called by the audio backend to process a buffer of audio.
     *
     * @param inputBuffer  the input audio data {@code [channel][frame]},
     *                     or an empty array if there is no input
     * @param outputBuffer the output audio data {@code [channel][frame]}
     *                     to fill with processed audio
     * @param numFrames    the number of sample frames in this buffer
     */
    void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames);
}
