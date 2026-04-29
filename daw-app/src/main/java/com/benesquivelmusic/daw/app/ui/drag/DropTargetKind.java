package com.benesquivelmusic.daw.app.ui.drag;

/**
 * The kinds of drop targets the arrangement and mixer surfaces accept.
 *
 * <p>{@link #NONE} represents "the cursor is not over a drop target" —
 * the advisor still emits a state in this case so the presenter can show
 * a neutral cursor without a highlight.</p>
 */
public enum DropTargetKind {
    /** A horizontal track lane in the arrangement view. */
    TRACK_LANE,
    /** A plugin insert slot on a track or bus. */
    INSERT_SLOT,
    /** A send slot on a track or bus. */
    SEND_SLOT,
    /** No drop target underneath the cursor. */
    NONE
}
