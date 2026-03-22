package com.benesquivelmusic.daw.sdk.spatial;

/**
 * Ambisonic order defining the spatial resolution of the sound field encoding.
 *
 * <p>Higher orders provide finer spatial resolution at the cost of more channels.
 * The channel count for order <em>N</em> is {@code (N+1)²}, using ACN
 * (Ambisonic Channel Number) ordering and SN3D normalization.</p>
 *
 * <ul>
 *   <li>First Order (FOA): 4 channels — W, Y, Z, X</li>
 *   <li>Second Order: 9 channels</li>
 *   <li>Third Order: 16 channels</li>
 * </ul>
 */
public enum AmbisonicOrder {

    /** First-Order Ambisonics — 4 channels (W, Y, Z, X). */
    FIRST(1),

    /** Second-Order Ambisonics — 9 channels. */
    SECOND(2),

    /** Third-Order Ambisonics — 16 channels. */
    THIRD(3);

    private final int order;

    AmbisonicOrder(int order) {
        this.order = order;
    }

    /**
     * Returns the numeric order value.
     *
     * @return the Ambisonic order (1, 2, or 3)
     */
    public int order() {
        return order;
    }

    /**
     * Returns the number of Ambisonic channels for this order: {@code (order+1)²}.
     *
     * @return the channel count
     */
    public int channelCount() {
        return (order + 1) * (order + 1);
    }
}
