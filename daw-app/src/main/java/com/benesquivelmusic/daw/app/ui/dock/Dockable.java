package com.benesquivelmusic.daw.app.ui.dock;

/**
 * Contract every top-level panel must satisfy to participate in the
 * {@link DockManager} system.
 *
 * <p>Implementations advertise:
 * <ul>
 *   <li>a stable {@link #dockId()} used as the persistence key (this is
 *       the same string used by {@code DefaultWorkspaces} so dock state
 *       can be cross-referenced with workspace panel state);</li>
 *   <li>a human-readable {@link #displayName()} shown on tab labels and
 *       in the View menu;</li>
 *   <li>an {@link #iconName()} that the icon registry resolves to the
 *       glyph drawn next to the tab label;</li>
 *   <li>a {@link #preferredZone()} used the very first time the user
 *       opens the panel — once the user moves it, their choice
 *       supersedes this hint.</li>
 * </ul>
 *
 * <p>The interface is deliberately decoupled from JavaFX so the dock
 * model is unit-testable without a screen — implementations may be
 * regular JavaFX nodes, but they may equally be plain test fixtures.</p>
 */
public interface Dockable {

    /**
     * Stable identifier (kebab-case ASCII recommended) used as the
     * persistence key. Must be unique across all dockables registered
     * with a single {@link DockManager}.
     */
    String dockId();

    /** Human-readable name shown on tab labels, in menus, and in tooltips. */
    String displayName();

    /**
     * Icon registry key (typically a {@code DawIcon} enum name); may be
     * {@code null} or empty if the panel has no associated glyph.
     */
    default String iconName() {
        return "";
    }

    /**
     * Default dock zone applied the first time the panel is shown. After
     * the user moves the panel, the user's choice persists and this
     * method is no longer consulted.
     */
    default DockZone preferredZone() {
        return DockZone.CENTER;
    }

    /**
     * Default floating bounds applied the very first time the user
     * detaches the panel. Returns {@code null} for panels that should
     * fall back to a generic default.
     */
    default com.benesquivelmusic.daw.sdk.ui.Rectangle2D defaultFloatingBounds() {
        return null;
    }
}
