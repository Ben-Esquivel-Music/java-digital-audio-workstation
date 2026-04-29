package com.benesquivelmusic.daw.app.ui.drag;

/**
 * The cursor variant the presenter should render during a drag.
 *
 * <p>The advisor selects the cursor based on drop-target validity and the
 * active {@link DragModifier}s. Modifier cursors are mutually exclusive
 * and follow a deterministic priority documented on
 * {@link DragVisualAdvisor}.</p>
 */
public enum DragCursor {
    /** Default move cursor — valid drop with no modifiers active. */
    DEFAULT,
    /** "+" cursor — Ctrl/Cmd held, drop will duplicate the source. */
    COPY,
    /** Link/alias cursor — Alt/Option held, drop will create an alias. */
    LINK,
    /** Free-position cursor — Shift held, snap temporarily disabled. */
    NO_SNAP,
    /** Universal "no" cursor — drop on this target is invalid. */
    NO_DROP
}
