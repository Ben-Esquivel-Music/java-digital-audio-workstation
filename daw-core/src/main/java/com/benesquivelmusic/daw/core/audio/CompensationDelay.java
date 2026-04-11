package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Arrays;

/**
 * A simple fixed-length sample delay used for plugin delay compensation (PDC).
 *
 * <p>Each instance delays audio by a fixed number of samples using a
 * per-channel ring buffer. The delay length is set at construction time
 * and cannot be changed — when compensation needs change, create a new
 * instance (off the audio thread) and swap it in atomically.</p>
 *
 * <p>All buffers are pre-allocated at construction time so that
 * {@link #process(float[][], int)} performs zero heap allocations —
 * safe to call on the real-time audio thread.</p>
 */
final class CompensationDelay {

    private final float[][] ringBuffer;
    private final int delaySamples;
    private int writePos;

    /**
     * Creates a compensation delay.
     *
     * @param audioChannels the number of audio channels (e.g., 2 for stereo)
     * @param delaySamples  the number of samples to delay (must be &ge; 0)
     */
    CompensationDelay(int audioChannels, int delaySamples) {
        if (audioChannels <= 0) {
            throw new IllegalArgumentException("audioChannels must be positive: " + audioChannels);
        }
        if (delaySamples < 0) {
            throw new IllegalArgumentException("delaySamples must be >= 0: " + delaySamples);
        }
        this.delaySamples = delaySamples;
        this.ringBuffer = delaySamples > 0 ? new float[audioChannels][delaySamples] : new float[0][0];
        this.writePos = 0;
    }

    /**
     * Returns the number of delay samples.
     *
     * @return the delay in sample frames
     */
    int getDelaySamples() {
        return delaySamples;
    }

    /**
     * Delays the audio in-place by the configured number of samples.
     *
     * <p>For the first {@code delaySamples} calls after construction or
     * {@link #reset()}, the output contains silence (zero) for the delayed
     * portion. This is the expected behavior for PDC — the initial silence
     * represents the alignment delay.</p>
     *
     * @param buffer    audio buffer {@code [channel][frame]}, modified in-place
     * @param numFrames the number of frames to process
     */
    @RealTimeSafe
    void process(float[][] buffer, int numFrames) {
        if (delaySamples == 0) {
            return;
        }
        int channels = Math.min(buffer.length, ringBuffer.length);
        for (int f = 0; f < numFrames; f++) {
            for (int ch = 0; ch < channels; ch++) {
                float delayed = ringBuffer[ch][writePos];
                ringBuffer[ch][writePos] = buffer[ch][f];
                buffer[ch][f] = delayed;
            }
            writePos = (writePos + 1) % delaySamples;
        }
    }

    /**
     * Resets the delay buffer to silence.
     */
    void reset() {
        for (float[] ch : ringBuffer) {
            Arrays.fill(ch, 0.0f);
        }
        writePos = 0;
    }
}
