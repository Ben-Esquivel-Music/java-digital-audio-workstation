package com.benesquivelmusic.daw.app.ui.drag;

/**
 * Modifier keys that can be held during a drag to alter its semantics.
 *
 * <p>These are platform-neutral logical names. The presentation layer
 * maps platform-specific keys to these values (typically Ctrl/Cmd ↦
 * {@link #DUPLICATE}, Alt/Option ↦ {@link #LINK}, Shift ↦
 * {@link #DISABLE_SNAP}).</p>
 */
public enum DragModifier {
    /** Ctrl on Win/Linux, Cmd on macOS — the drop creates an independent copy. */
    DUPLICATE,
    /** Alt/Option — the drop creates an alias that tracks the original. */
    LINK,
    /** Shift — temporarily disables snap so the drop position is free. */
    DISABLE_SNAP
}
