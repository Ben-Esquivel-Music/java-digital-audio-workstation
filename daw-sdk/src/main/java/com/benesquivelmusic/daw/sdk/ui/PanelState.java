package com.benesquivelmusic.daw.sdk.ui;

/**
 * Captured visual state of a single named UI panel inside a {@link Workspace}.
 *
 * <p>{@code PanelState} stores the visibility flag and panel-specific
 * zoom/scroll values so that switching back to a workspace restores not only
 * <em>which</em> panels were open but also <em>how</em> the user had each one
 * scrolled and zoomed (e.g. arrangement zoom, mixer scroll position).</p>
 *
 * @param visible {@code true} if the panel is shown when the workspace is restored
 * @param zoom    panel-specific zoom factor ({@code 1.0} = native zoom, must be {@code > 0})
 * @param scrollX horizontal scroll offset (pixels or panel units; {@code 0} = no offset)
 * @param scrollY vertical scroll offset
 */
public record PanelState(boolean visible, double zoom, double scrollX, double scrollY) {

    /** Reasonable default state: visible, native zoom, no scroll. */
    public static final PanelState DEFAULT = new PanelState(true, 1.0, 0.0, 0.0);

    /** Convenient hidden-panel state. */
    public static final PanelState HIDDEN = new PanelState(false, 1.0, 0.0, 0.0);

    public PanelState {
        if (!Double.isFinite(zoom) || zoom <= 0.0) {
            throw new IllegalArgumentException("zoom must be finite and > 0, got " + zoom);
        }
        if (!Double.isFinite(scrollX) || !Double.isFinite(scrollY)) {
            throw new IllegalArgumentException("scroll values must be finite numbers");
        }
    }

    /** Returns a copy of this state with {@code visible} set to the given value. */
    public PanelState withVisible(boolean newVisible) {
        return new PanelState(newVisible, zoom, scrollX, scrollY);
    }
}
