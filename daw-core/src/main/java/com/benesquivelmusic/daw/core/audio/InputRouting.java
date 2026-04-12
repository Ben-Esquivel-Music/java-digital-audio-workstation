package com.benesquivelmusic.daw.core.audio;

/**
 * Specifies which hardware input channels a track records from.
 *
 * <p>The {@code firstChannel} is a zero-based index into the input device's
 * channel list. For example, {@code InputRouting(0, 2)} means "record from
 * input channels 1-2 (stereo)", and {@code InputRouting(2, 1)} means
 * "record from input channel 3 (mono)".</p>
 *
 * <p>The default for new tracks is {@link #DEFAULT_STEREO} which maps to
 * "Input 1-2".</p>
 *
 * @param firstChannel zero-based index of the first input channel, or
 *                     {@code -1} for no input (see {@link #NONE})
 * @param channelCount number of contiguous channels (1 for mono, 2 for stereo)
 */
public record InputRouting(int firstChannel, int channelCount) {

    /** No input assigned. */
    public static final InputRouting NONE = new InputRouting(-1, 0);

    /** Default stereo input from channels 1-2 (indices 0-1). */
    public static final InputRouting DEFAULT_STEREO = new InputRouting(0, 2);

    public InputRouting {
        if (firstChannel < -1) {
            throw new IllegalArgumentException("firstChannel must be >= -1: " + firstChannel);
        }
        if (channelCount < 0) {
            throw new IllegalArgumentException("channelCount must be >= 0: " + channelCount);
        }
    }

    /**
     * Returns {@code true} if no input is assigned.
     *
     * @return true if this routing has no input
     */
    public boolean isNone() {
        return firstChannel < 0 || channelCount == 0;
    }

    /**
     * Returns a human-readable display name for this routing.
     *
     * <p>Examples: "None", "Input 1", "Input 1-2", "Input 3-4".</p>
     *
     * @return the display name
     */
    public String displayName() {
        if (isNone()) {
            return "None";
        }
        if (channelCount == 1) {
            return "Input " + (firstChannel + 1);
        }
        return "Input " + (firstChannel + 1) + "-" + (firstChannel + channelCount);
    }
}
