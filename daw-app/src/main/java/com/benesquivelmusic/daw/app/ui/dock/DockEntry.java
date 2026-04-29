package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import java.util.Objects;

/**
 * Immutable record describing the on-screen placement of a single
 * {@link Dockable} panel.
 *
 * @param panelId        the {@link Dockable#dockId()} of the panel this
 *                       entry belongs to (non-blank)
 * @param zone           the dock zone the panel currently occupies
 * @param tabIndex       0-based ordinal of the panel within its zone's
 *                       tab strip; ignored for {@link DockZone#FLOATING}
 *                       panels
 * @param visible        whether the panel is currently visible (collapsed
 *                       panels stay in their zone but render no tab)
 * @param floatingBounds bounds of the floating window when {@code zone}
 *                       is {@link DockZone#FLOATING}; ignored otherwise
 *                       and may be {@code null}
 */
public record DockEntry(String panelId,
                        DockZone zone,
                        int tabIndex,
                        boolean visible,
                        Rectangle2D floatingBounds) {

    public DockEntry {
        Objects.requireNonNull(panelId, "panelId must not be null");
        if (panelId.isBlank()) {
            throw new IllegalArgumentException("panelId must not be blank");
        }
        Objects.requireNonNull(zone, "zone must not be null");
        if (tabIndex < 0) {
            throw new IllegalArgumentException("tabIndex must be >= 0, got " + tabIndex);
        }
    }

    /** Convenience: creates an entry for a docked (non-floating) panel. */
    public static DockEntry docked(String panelId, DockZone zone, int tabIndex, boolean visible) {
        if (zone == DockZone.FLOATING) {
            throw new IllegalArgumentException("docked() cannot be used with FLOATING zone");
        }
        return new DockEntry(panelId, zone, tabIndex, visible, null);
    }

    /** Convenience: creates an entry for a floating panel with the given bounds. */
    public static DockEntry floating(String panelId, Rectangle2D bounds) {
        Objects.requireNonNull(bounds, "bounds must not be null for floating entry");
        return new DockEntry(panelId, DockZone.FLOATING, 0, true, bounds);
    }

    /** Returns a copy of this entry with the given visibility. */
    public DockEntry withVisible(boolean newVisible) {
        return new DockEntry(panelId, zone, tabIndex, newVisible, floatingBounds);
    }

    /** Returns a copy of this entry with the given tab index. */
    public DockEntry withTabIndex(int newTabIndex) {
        return new DockEntry(panelId, zone, newTabIndex, visible, floatingBounds);
    }

    /** Returns a copy of this entry with the given floating bounds. */
    public DockEntry withFloatingBounds(Rectangle2D newBounds) {
        return new DockEntry(panelId, zone, tabIndex, visible, newBounds);
    }
}
