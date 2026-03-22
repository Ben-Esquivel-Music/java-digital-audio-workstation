package com.benesquivelmusic.daw.core.dsp;

/**
 * Utility for encoding a stereo signal (Left/Right) to Mid/Side representation.
 *
 * <p>Mid/Side encoding separates the center (mono-compatible) content from
 * the stereo (difference) content:</p>
 * <ul>
 *   <li><b>Mid</b> = (L + R) × 0.5 — center content</li>
 *   <li><b>Side</b> = (L − R) × 0.5 — stereo content</li>
 * </ul>
 *
 * <p>Use with {@link MidSideDecoder} to convert back to Left/Right after
 * independent processing of mid and side channels.</p>
 */
public final class MidSideEncoder {

    private MidSideEncoder() {}

    /**
     * Encodes stereo Left/Right buffers into Mid/Side buffers.
     *
     * @param left      the left channel input
     * @param right     the right channel input
     * @param mid       the mid channel output (must be at least {@code numFrames} long)
     * @param side      the side channel output (must be at least {@code numFrames} long)
     * @param numFrames the number of sample frames to encode
     */
    public static void encode(float[] left, float[] right,
                              float[] mid, float[] side, int numFrames) {
        for (int i = 0; i < numFrames; i++) {
            mid[i] = (left[i] + right[i]) * 0.5f;
            side[i] = (left[i] - right[i]) * 0.5f;
        }
    }
}
