package com.benesquivelmusic.daw.app.ui.drag;

import java.util.Objects;

/**
 * Describes the highlight applied to the drop target currently under the
 * cursor.
 *
 * <p>Valid targets render with a soft tint matching the source kind;
 * invalid targets render no tint and instruct the presenter to display
 * a "no-drop" cursor (see {@link DragCursor#NO_DROP}).</p>
 *
 * @param kind   the drop target underneath the cursor (or
 *               {@link DropTargetKind#NONE} if there is none)
 * @param valid  whether the source can actually be dropped on
 *               {@link #kind}
 * @param tintRgba 8-character RGBA hex tint to apply to the target's
 *               highlight overlay (e.g. {@code "5fa8ff40"}); empty string
 *               when no tint should be drawn
 */
public record DropTargetHighlight(
        DropTargetKind kind,
        boolean valid,
        String tintRgba) {

    /** A "no highlight" sentinel — used when no drop target is hovered. */
    public static final DropTargetHighlight NONE =
            new DropTargetHighlight(DropTargetKind.NONE, false, "");

    public DropTargetHighlight {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(tintRgba, "tintRgba");
        if (!tintRgba.isEmpty() && tintRgba.length() != 8) {
            throw new IllegalArgumentException(
                    "tintRgba must be empty or an 8-char RRGGBBAA hex: "
                            + tintRgba);
        }
    }
}
