package com.benesquivelmusic.daw.sdk.spatial;

import java.util.List;
import java.util.Objects;

/**
 * Mixer model for an Ambisonic track.
 *
 * <p>An Ambisonic track carries an N-channel B-format audio stream
 * encoded in ACN (Ambisonic Channel Number) ordering with SN3D
 * (Schmidt Semi-Normalization) — the modern open standard known as
 * "AmbiX". The {@link AmbisonicOrder order} determines the channel
 * count: 4 for first order, 9 for second order, 16 for third order.</p>
 *
 * <p>Each track records:</p>
 * <ul>
 *   <li>The display {@code name} shown on the strip;</li>
 *   <li>The Ambisonic {@link AmbisonicOrder order};</li>
 *   <li>The list of {@code channelAssignments} that map each Ambisonic
 *       channel index (0&hellip;{@code order.channelCount()-1}) to a
 *       physical input/file channel index;</li>
 *   <li>The selected {@link AmbisonicDecoder output decoder} that feeds
 *       the session's monitoring path (bed bus or stereo monitoring).</li>
 * </ul>
 *
 * <p>The number of channel assignments must equal
 * {@code order.channelCount()}; this invariant is enforced on
 * construction.</p>
 *
 * @param name               the display name; must not be blank
 * @param order              the Ambisonic order
 * @param channelAssignments the per-channel input mapping; size must
 *                           equal {@code order.channelCount()}
 * @param decoder            the chosen output decoder
 */
public record AmbisonicTrack(
        String name,
        AmbisonicOrder order,
        List<Integer> channelAssignments,
        AmbisonicDecoder decoder) {

    public AmbisonicTrack {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(channelAssignments, "channelAssignments must not be null");
        Objects.requireNonNull(decoder, "decoder must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (channelAssignments.size() != order.channelCount()) {
            throw new IllegalArgumentException(
                    "channelAssignments size must equal order.channelCount(): expected "
                            + order.channelCount() + " but got " + channelAssignments.size());
        }
        for (Integer assignment : channelAssignments) {
            Objects.requireNonNull(assignment, "channelAssignments must not contain null");
            if (assignment < 0) {
                throw new IllegalArgumentException(
                        "channelAssignments must be non-negative: " + assignment);
            }
        }
        channelAssignments = List.copyOf(channelAssignments);
    }

    /**
     * Convenience constructor that maps Ambisonic channel <em>i</em> to
     * input channel <em>i</em> for {@code i = 0 .. order.channelCount()-1}.
     *
     * @param name    the display name
     * @param order   the Ambisonic order
     * @param decoder the chosen output decoder
     */
    public AmbisonicTrack(String name, AmbisonicOrder order, AmbisonicDecoder decoder) {
        this(name, order, defaultAssignments(order), decoder);
    }

    /**
     * Returns the number of B-format channels carried by this track.
     *
     * @return the channel count
     */
    public int channelCount() {
        return order.channelCount();
    }

    /**
     * Returns a copy of this track with the given decoder selected.
     *
     * @param newDecoder the new decoder; must not be {@code null}
     * @return a new {@code AmbisonicTrack} with the updated decoder
     */
    public AmbisonicTrack withDecoder(AmbisonicDecoder newDecoder) {
        Objects.requireNonNull(newDecoder, "newDecoder must not be null");
        return new AmbisonicTrack(name, order, channelAssignments, newDecoder);
    }

    private static List<Integer> defaultAssignments(AmbisonicOrder order) {
        Objects.requireNonNull(order, "order must not be null");
        Integer[] ids = new Integer[order.channelCount()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = i;
        }
        return List.of(ids);
    }
}
