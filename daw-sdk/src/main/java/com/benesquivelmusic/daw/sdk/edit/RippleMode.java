package com.benesquivelmusic.daw.sdk.edit;

/**
 * Ripple-edit scope for clip delete, cut, and move operations.
 *
 * <p>"Ripple edit" is the industry-standard pattern for editing song structure
 * or spoken-word timelines: deleting or moving a clip shifts later clips to
 * close the gap, so removing 8 bars from the middle of a song does not leave
 * silence behind. The scope controls which tracks participate in the shift.</p>
 *
 * <p>Equivalents in other DAWs:</p>
 * <ul>
 *   <li>Pro Tools — "Shuffle" edit mode (PER_TRACK) and "Shuffle All" (ALL_TRACKS)</li>
 *   <li>Cubase — "Ripple" (PER_TRACK) and "Ripple All" (ALL_TRACKS)</li>
 *   <li>Reaper — "Ripple editing — per track" and "Ripple editing — all tracks"</li>
 * </ul>
 *
 * <p>When {@link #OFF}, edits are non-rippling: deleting a clip leaves a gap,
 * and moving a clip does not displace later clips. This is the default.</p>
 */
public enum RippleMode {

    /**
     * No rippling — clip deletes leave gaps and moves do not displace later clips.
     * This is the default behaviour and matches classic non-linear editing.
     */
    OFF,

    /**
     * Rippling is confined to the track on which the edit occurred: later clips
     * on the same track shift to close the gap (on delete) or follow the moved
     * clip (on move). Other tracks are untouched, keeping sync between parts
     * that should stay aligned (e.g. a drum loop that must not follow a vocal
     * edit).
     */
    PER_TRACK,

    /**
     * Rippling cascades across every track: later clips on <em>every</em> track
     * shift by the same amount so the entire arrangement tightens up. This is
     * the right mode when editing whole song sections — the standard workflow
     * for removing bars from a mix or tightening a podcast conversation.
     *
     * <p>ALL_TRACKS rippling is destructive across tracks and easily affects
     * material the user did not intend to move. UIs are expected to warn the
     * user the first time they enable it in a session (with a "don't ask again"
     * suppression option).</p>
     */
    ALL_TRACKS;

    /**
     * Returns the next mode in the cycle {@code OFF → PER_TRACK → ALL_TRACKS → OFF},
     * for a toolbar button that advances the mode on every click.
     *
     * @return the next ripple mode
     */
    public RippleMode next() {
        RippleMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** Returns a short, user-facing label for status bars and tooltips. */
    public String displayName() {
        return switch (this) {
            case OFF -> "Ripple: Off";
            case PER_TRACK -> "Ripple: Per Track";
            case ALL_TRACKS -> "Ripple: All Tracks";
        };
    }
}
