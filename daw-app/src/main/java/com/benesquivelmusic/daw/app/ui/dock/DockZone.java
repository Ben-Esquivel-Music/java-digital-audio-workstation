package com.benesquivelmusic.daw.app.ui.dock;

/**
 * The five docking zones of the main window plus the {@link #FLOATING}
 * pseudo-zone for panels detached into their own {@code Stage}.
 *
 * <p>The four edge zones ({@link #TOP}, {@link #BOTTOM}, {@link #LEFT},
 * {@link #RIGHT}) attach to the corresponding edges of the main {@code
 * BorderPane}; {@link #CENTER} holds the active document view; and
 * {@link #FLOATING} represents a panel detached into its own window for
 * use on a second monitor.</p>
 *
 * <p>The set of zones is intentionally fixed (not extensible) so that
 * {@link DockLayout} can be exhaustively pattern-matched in switch
 * expressions over a sealed value space.</p>
 */
public enum DockZone {
    /** Anchored to the top edge of the main window. */
    TOP,
    /** Anchored to the bottom edge of the main window. */
    BOTTOM,
    /** Anchored to the left edge of the main window. */
    LEFT,
    /** Anchored to the right edge of the main window. */
    RIGHT,
    /** Center document area of the main window. */
    CENTER,
    /** Detached into its own free-floating {@code Stage}. */
    FLOATING;

    /** Returns whether this zone represents a floating (detached) panel. */
    public boolean isFloating() {
        return this == FLOATING;
    }

    /** Parses a zone name (case-insensitive); returns {@code fallback} if unknown. */
    public static DockZone parseOr(String name, DockZone fallback) {
        if (name == null) return fallback;
        try {
            return DockZone.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
