package com.benesquivelmusic.daw.sdk.ui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of a user-saved UI <em>workspace</em> — the equivalent
 * of Cubase Workspaces, Logic Screensets, Studio One Views, and Pro Tools
 * Window Configurations.
 *
 * <p>A workspace records the visual arrangement of the DAW (which panels
 * are open, where they live, how they are zoomed/scrolled, and which
 * dialogs are visible) under a user-chosen name. The host application
 * (typically {@code daw-app}'s {@code WorkspaceManager}) is responsible
 * for capturing the live UI state into a {@code Workspace} and applying
 * a stored {@code Workspace} back onto the live UI.</p>
 *
 * <p>Workspaces are <strong>per-user</strong>, not per-project, so the
 * user's "Mixing" layout is available in every project they open. They
 * are persisted as JSON under {@code ~/.daw/workspaces/} and may be
 * exported/imported for sharing across machines or with collaborators.</p>
 *
 * <p>Switching workspaces preserves all <em>non-UI</em> state (playhead,
 * selection, transport, audio engine), it only rearranges panels.</p>
 *
 * @param name         the user-visible workspace name (must be non-blank)
 * @param panelStates  visibility/zoom/scroll for each addressable panel,
 *                     keyed by stable panel id (e.g. {@code "browser"},
 *                     {@code "mixer"}, {@code "arrangement"})
 * @param openDialogs  list of stable dialog ids that should be re-opened
 *                     when the workspace is restored (empty if none)
 * @param panelBounds     on-screen bounds for each addressable panel, keyed
 *                        by the same stable id used in {@code panelStates}
 * @param dockLayoutJson  opaque JSON blob describing the panel dock layout
 *                        (zones, tab order, floating window bounds) for
 *                        {@code daw-app}'s {@code DockManager}; empty
 *                        string for workspaces that predate dockable panels
 *                        (forward-compatible default).
 */
public record Workspace(String name,
                        Map<String, PanelState> panelStates,
                        List<String> openDialogs,
                        Map<String, Rectangle2D> panelBounds,
                        String dockLayoutJson) {

    /**
     * Validates and defensively copies every collection so the resulting
     * record is fully immutable.
     */
    public Workspace {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(panelStates, "panelStates must not be null");
        Objects.requireNonNull(openDialogs, "openDialogs must not be null");
        Objects.requireNonNull(panelBounds, "panelBounds must not be null");
        Objects.requireNonNull(dockLayoutJson, "dockLayoutJson must not be null");
        panelStates = Map.copyOf(panelStates);
        openDialogs = List.copyOf(openDialogs);
        panelBounds = Map.copyOf(panelBounds);
    }

    /**
     * Backwards-compatible constructor used by callers written before
     * {@link #dockLayoutJson} existed. Equivalent to passing an empty
     * dock-layout string.
     */
    public Workspace(String name,
                     Map<String, PanelState> panelStates,
                     List<String> openDialogs,
                     Map<String, Rectangle2D> panelBounds) {
        this(name, panelStates, openDialogs, panelBounds, "");
    }

    /** Returns a copy of this workspace renamed to {@code newName}. */
    public Workspace withName(String newName) {
        return new Workspace(newName, panelStates, openDialogs, panelBounds, dockLayoutJson);
    }

    /** Returns a copy of this workspace with the given dock layout. */
    public Workspace withDockLayoutJson(String newDockLayoutJson) {
        return new Workspace(name, panelStates, openDialogs, panelBounds,
                newDockLayoutJson == null ? "" : newDockLayoutJson);
    }
}
