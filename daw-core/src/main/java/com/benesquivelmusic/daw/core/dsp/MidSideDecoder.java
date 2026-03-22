package com.benesquivelmusic.daw.core.dsp;

/**
 * Utility for decoding a Mid/Side signal back to stereo Left/Right.
 *
 * <p>Performs the inverse of {@link MidSideEncoder}:</p>
 * <ul>
 *   <li><b>Left</b> = Mid + Side</li>
 *   <li><b>Right</b> = Mid − Side</li>
 * </ul>
 */
public final class MidSideDecoder {

    private MidSideDecoder() {}

    /**
     * Decodes Mid/Side buffers into stereo Left/Right buffers.
     *
     * @param mid       the mid channel input
     * @param side      the side channel input
     * @param left      the left channel output (must be at least {@code numFrames} long)
     * @param right     the right channel output (must be at least {@code numFrames} long)
     * @param numFrames the number of sample frames to decode
     */
    public static void decode(float[] mid, float[] side,
                              float[] left, float[] right, int numFrames) {
        for (int i = 0; i < numFrames; i++) {
            left[i] = mid[i] + side[i];
            right[i] = mid[i] - side[i];
        }
    }
}
