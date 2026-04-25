package com.benesquivelmusic.daw.core.track;

/**
 * Per-track fold/collapse state for the arrangement view's lane groups.
 *
 * <p>A 40-track session with automation lanes on half the tracks rapidly
 * consumes vertical real estate. {@code TrackFoldState} captures whether
 * each foldable lane group on a track — automation lanes, take/comping
 * lanes, and the MIDI editor lane — should be drawn at full height or
 * collapsed to a thin 3 px summary strip that still indicates data is
 * present without forcing the user to scroll past empty space.</p>
 *
 * <p>The state is a transparent immutable data carrier. Toggling fold
 * never touches contained clip, take, or automation data — the model is
 * preserved bit-exact across fold transitions; only the rendered height
 * of each lane group changes.</p>
 *
 * <p>{@code headerHeightOverride} is an optional override for the track
 * header height, in pixels. A value of {@code 0.0} (the default) means
 * "use the renderer's default header height". Positive values pin the
 * header to a specific pixel height — useful when the user wants a
 * compact header on a fully-folded track. Negative values are rejected.</p>
 *
 * @param automationFolded     whether automation lanes are collapsed
 * @param takesFolded          whether take/comping lanes are collapsed
 * @param midiFolded           whether the MIDI editor lane is collapsed
 * @param headerHeightOverride optional header height in pixels;
 *                             {@code 0.0} means "use renderer default"
 */
public record TrackFoldState(
        boolean automationFolded,
        boolean takesFolded,
        boolean midiFolded,
        double headerHeightOverride
) {

    /** All lane groups expanded; renderer uses its default header height. */
    public static final TrackFoldState UNFOLDED =
            new TrackFoldState(false, false, false, 0.0);

    /** Compact form: every foldable lane group collapsed. */
    public static final TrackFoldState ALL_FOLDED =
            new TrackFoldState(true, true, true, 0.0);

    /**
     * Pixel height of the thin summary strip rendered in place of a
     * folded lane group. Three pixels matches the issue spec ("a 3 px
     * summary strip showing 'N lanes folded'") and is enough for a
     * single-pixel border, a single-pixel highlight, and a single
     * background pixel.
     */
    public static final double SUMMARY_STRIP_HEIGHT_PX = 3.0;

    public TrackFoldState {
        if (Double.isNaN(headerHeightOverride) || Double.isInfinite(headerHeightOverride)) {
            throw new IllegalArgumentException(
                    "headerHeightOverride must be a finite number: " + headerHeightOverride);
        }
        if (headerHeightOverride < 0.0) {
            throw new IllegalArgumentException(
                    "headerHeightOverride must be >= 0.0: " + headerHeightOverride);
        }
    }

    /** Returns {@code true} if every foldable lane group is folded. */
    public boolean isFullyFolded() {
        return automationFolded && takesFolded && midiFolded;
    }

    /** Returns {@code true} if at least one lane group is folded. */
    public boolean isAnyFolded() {
        return automationFolded || takesFolded || midiFolded;
    }

    /** Returns a copy with {@code automationFolded} set to the given value. */
    public TrackFoldState withAutomationFolded(boolean folded) {
        return new TrackFoldState(folded, takesFolded, midiFolded, headerHeightOverride);
    }

    /** Returns a copy with {@code takesFolded} set to the given value. */
    public TrackFoldState withTakesFolded(boolean folded) {
        return new TrackFoldState(automationFolded, folded, midiFolded, headerHeightOverride);
    }

    /** Returns a copy with {@code midiFolded} set to the given value. */
    public TrackFoldState withMidiFolded(boolean folded) {
        return new TrackFoldState(automationFolded, takesFolded, folded, headerHeightOverride);
    }

    /** Returns a copy with the given header-height override (must be >= 0). */
    public TrackFoldState withHeaderHeightOverride(double pixels) {
        return new TrackFoldState(automationFolded, takesFolded, midiFolded, pixels);
    }

    /**
     * Returns the rendered height of a lane group that has the given
     * full-expanded {@code expandedHeightPx}, taking into account whether
     * the group is folded.
     *
     * <p>Folded groups collapse to {@link #SUMMARY_STRIP_HEIGHT_PX} so the
     * user can still see that data exists. Expanded groups keep their
     * full height. If {@code expandedHeightPx} is {@code 0.0} (the group
     * has no content to display, e.g. a track with no automation lanes)
     * the height is {@code 0.0} regardless of fold state.</p>
     *
     * @param folded            whether this lane group is folded
     * @param expandedHeightPx  the lane group's height when fully expanded
     * @return the effective rendered height in pixels
     * @throws IllegalArgumentException if {@code expandedHeightPx} is negative
     */
    public static double effectiveLaneHeight(boolean folded, double expandedHeightPx) {
        if (expandedHeightPx < 0.0) {
            throw new IllegalArgumentException(
                    "expandedHeightPx must be >= 0.0: " + expandedHeightPx);
        }
        if (expandedHeightPx == 0.0) {
            return 0.0;
        }
        return folded ? SUMMARY_STRIP_HEIGHT_PX : expandedHeightPx;
    }
}
