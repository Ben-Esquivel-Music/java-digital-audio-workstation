package com.benesquivelmusic.daw.core.mixer;

/**
 * Specifies where a mixer channel's audio is routed after mixing.
 *
 * <p>The default routing is {@link #MASTER}, which sums the channel's audio
 * into the master bus. Alternative routings send the channel's audio to
 * specific hardware output channels, bypassing the master bus entirely.</p>
 *
 * <p>For hardware outputs, {@code firstChannel} is a zero-based index into
 * the output device's channel list. For example, {@code OutputRouting(2, 2)}
 * means "send to hardware output channels 3-4 (stereo)".</p>
 *
 * @param firstChannel zero-based index of the first output channel, or
 *                     {@code -1} for the master bus (see {@link #MASTER})
 * @param channelCount number of contiguous channels (1 for mono, 2 for stereo)
 */
public record OutputRouting(int firstChannel, int channelCount) {

    /** Route to the master bus (default). */
    public static final OutputRouting MASTER = new OutputRouting(-1, 0);

    public OutputRouting {
        if (firstChannel < -1) {
            throw new IllegalArgumentException("firstChannel must be >= -1: " + firstChannel);
        }
        if (channelCount < 0) {
            throw new IllegalArgumentException("channelCount must be >= 0: " + channelCount);
        }
    }

    /**
     * Returns {@code true} if this routing sends audio to the master bus.
     *
     * @return true if routed to master
     */
    public boolean isMaster() {
        return firstChannel < 0;
    }

    /**
     * Returns a human-readable display name for this routing.
     *
     * <p>Examples: "Master", "Output 1", "Output 3-4".</p>
     *
     * @return the display name
     */
    public String displayName() {
        if (isMaster()) {
            return "Master";
        }
        if (channelCount == 1) {
            return "Output " + (firstChannel + 1);
        }
        return "Output " + (firstChannel + 1) + "-" + (firstChannel + channelCount);
    }
}
