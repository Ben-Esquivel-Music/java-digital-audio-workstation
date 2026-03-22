package com.benesquivelmusic.daw.sdk.spatial;

import java.util.Objects;

/**
 * Describes the format of an Ambisonic signal: order, channel count,
 * and normalization convention.
 *
 * <p>This record uses ACN (Ambisonic Channel Number) channel ordering
 * and SN3D (Schmidt Semi-Normalization) by convention, the standard
 * combination for modern Ambisonics (AmbiX format).</p>
 *
 * @param order        the Ambisonic order
 * @param channelCount the total number of Ambisonic channels, equal to {@code (order+1)²}
 */
public record AmbisonicFormat(AmbisonicOrder order, int channelCount) {

    /** First-Order Ambisonics format — 4 channels. */
    public static final AmbisonicFormat FOA = new AmbisonicFormat(AmbisonicOrder.FIRST);

    /** Second-Order Ambisonics format — 9 channels. */
    public static final AmbisonicFormat SECOND_ORDER = new AmbisonicFormat(AmbisonicOrder.SECOND);

    /** Third-Order Ambisonics format — 16 channels. */
    public static final AmbisonicFormat THIRD_ORDER = new AmbisonicFormat(AmbisonicOrder.THIRD);

    /**
     * Creates an Ambisonic format from the given order.
     *
     * @param order the Ambisonic order
     */
    public AmbisonicFormat(AmbisonicOrder order) {
        this(order, order.channelCount());
    }

    public AmbisonicFormat {
        Objects.requireNonNull(order, "order must not be null");
        if (channelCount != order.channelCount()) {
            throw new IllegalArgumentException(
                    "channelCount must be (order+1)²: expected " + order.channelCount()
                            + " but got " + channelCount);
        }
    }
}
