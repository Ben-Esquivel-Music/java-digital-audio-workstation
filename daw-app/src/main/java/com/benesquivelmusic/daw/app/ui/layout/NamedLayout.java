package com.benesquivelmusic.daw.app.ui.layout;

import java.util.Objects;

/**
 * Immutable record describing a single named Mission Control layout
 * (UI Design Book §4 Concept D, story 282).
 *
 * <p>A layout is a complete snapshot of the dock state: which panels are
 * docked where, which are floating, the floating-window positions, and
 * the split-pane ratios. The payload itself is carried opaquely as a
 * {@link com.benesquivelmusic.daw.app.ui.dock.DockLayout} JSON string —
 * this record never needs to peek inside it, so the persistence schema
 * can evolve independently in the dock layer.</p>
 *
 * <p>{@code NamedLayout} instances are value-type records with no
 * identity; equality is by {@code name + dockLayoutJson + builtIn}.</p>
 *
 * @param name           user-facing name; must be non-blank. For the five
 *                       built-in layouts these strings are resolved at
 *                       display time from {@code Messages.properties}
 *                       (Skill §14); user-saved names are arbitrary
 *                       strings entered in the "Save Layout As…" dialog
 * @param dockLayoutJson opaque dock-layout payload produced by
 *                       {@link com.benesquivelmusic.daw.app.ui.dock.DockLayoutJson};
 *                       never {@code null} but may be empty for a
 *                       layout that has not yet been captured
 * @param builtIn        {@code true} for the five canonical built-in
 *                       layouts that ship with the app and cannot be
 *                       renamed or deleted; {@code false} for user-saved
 *                       layouts that live in the project file
 */
public record NamedLayout(String name, String dockLayoutJson, boolean builtIn) {

    public NamedLayout {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(dockLayoutJson, "dockLayoutJson must not be null");
    }

    /** Convenience: creates a non-built-in (user-saved) layout. */
    public static NamedLayout user(String name, String dockLayoutJson) {
        return new NamedLayout(name, dockLayoutJson, false);
    }

    /** Convenience: creates a built-in layout. */
    public static NamedLayout builtIn(String name, String dockLayoutJson) {
        return new NamedLayout(name, dockLayoutJson, true);
    }

    /** Returns a copy of this layout with a new name (preserves builtIn flag). */
    public NamedLayout withName(String newName) {
        return new NamedLayout(newName, dockLayoutJson, builtIn);
    }

    /** Returns a copy of this layout with a new dock-layout JSON payload. */
    public NamedLayout withDockLayoutJson(String newDockLayoutJson) {
        return new NamedLayout(name, newDockLayoutJson, builtIn);
    }
}
