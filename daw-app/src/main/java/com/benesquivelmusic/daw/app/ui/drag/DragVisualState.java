package com.benesquivelmusic.daw.app.ui.drag;

import java.util.Objects;

/**
 * Aggregate snapshot of the visual state of an in-flight drag.
 *
 * <p>Returned by {@link DragVisualAdvisor#update} on every cursor move /
 * modifier change. The presenter consumes the four sub-fields and updates
 * the corresponding Scene-graph nodes (ghost {@code ImageView}, target
 * {@code Region} highlight, vertical snap {@code Line}, and
 * {@code Cursor} on the scene root).</p>
 *
 * @param ghost     the ghost preview to render at the cursor position
 * @param highlight the drop-target highlight (or
 *                  {@link DropTargetHighlight#NONE} for none)
 * @param cursor    the cursor variant to install on the scene root
 * @param snap      the snap-guide indicator (or
 *                  {@link SnapIndicator#HIDDEN})
 */
public record DragVisualState(
        GhostPreview ghost,
        DropTargetHighlight highlight,
        DragCursor cursor,
        SnapIndicator snap) {

    public DragVisualState {
        Objects.requireNonNull(ghost, "ghost");
        Objects.requireNonNull(highlight, "highlight");
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(snap, "snap");
    }
}
