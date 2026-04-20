package com.benesquivelmusic.daw.sdk.transport;

/**
 * A frame-based (sample-accurate) punch-in / punch-out region on the timeline.
 *
 * <p>When a {@code PunchRegion} is installed on the transport with
 * {@link #enabled()} {@code == true}, the recording pipeline plays the track
 * back up to {@link #startFrames()}, seamlessly swaps to recording for the
 * range {@code [startFrames, endFrames)}, then swaps back to playback — the
 * classic auto-punch behavior found in every mature DAW.</p>
 *
 * <p>The region is global (not per-track) and is expressed in absolute sample
 * frames so that boundaries are sample-accurate regardless of tempo changes.
 * The {@link #enabled()} flag allows the region to be pre-armed from the UI
 * but temporarily disabled without losing its boundaries (e.g., the
 * {@code Shift+P} toggle described in the feature request).</p>
 *
 * @param startFrames the inclusive punch-in position, in sample frames
 *                    (must be &ge; 0)
 * @param endFrames   the exclusive punch-out position, in sample frames
 *                    (must be &gt; {@code startFrames})
 * @param enabled     {@code true} to activate the region, {@code false} to
 *                    retain the boundaries but gate recording as if no region
 *                    were set
 */
public record PunchRegion(long startFrames, long endFrames, boolean enabled) {

    /**
     * Canonical constructor; validates invariants.
     *
     * @throws IllegalArgumentException if {@code startFrames} is negative or
     *                                  {@code endFrames} is not strictly
     *                                  greater than {@code startFrames}
     */
    public PunchRegion {
        if (startFrames < 0) {
            throw new IllegalArgumentException(
                    "startFrames must not be negative: " + startFrames);
        }
        if (endFrames <= startFrames) {
            throw new IllegalArgumentException(
                    "endFrames must be greater than startFrames: startFrames="
                            + startFrames + ", endFrames=" + endFrames);
        }
    }

    /**
     * Creates an enabled punch region spanning {@code [startFrames, endFrames)}.
     *
     * @param startFrames the inclusive punch-in position
     * @param endFrames   the exclusive punch-out position
     * @return an enabled punch region
     */
    public static PunchRegion enabled(long startFrames, long endFrames) {
        return new PunchRegion(startFrames, endFrames, true);
    }

    /**
     * Returns a copy of this region with the given enabled flag.
     *
     * @param enabled the new enabled flag
     * @return a new {@code PunchRegion} with the same boundaries and the
     *         supplied enabled flag
     */
    public PunchRegion withEnabled(boolean enabled) {
        return new PunchRegion(startFrames, endFrames, enabled);
    }

    /**
     * Returns the duration of the region in sample frames.
     *
     * @return {@code endFrames - startFrames}
     */
    public long durationFrames() {
        return endFrames - startFrames;
    }

    /**
     * Tests whether the given absolute frame index lies within the region.
     *
     * <p>The region is half-open: {@code startFrames} is included and
     * {@code endFrames} is excluded, matching standard DAW punch semantics.</p>
     *
     * @param frame the absolute sample frame index
     * @return {@code true} if {@code startFrames <= frame < endFrames}
     */
    public boolean containsFrame(long frame) {
        return frame >= startFrames && frame < endFrames;
    }
}
