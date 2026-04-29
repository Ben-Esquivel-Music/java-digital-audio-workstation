package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.ui.PanelState;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import com.benesquivelmusic.daw.sdk.ui.Workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates the lifecycle of customizable user workspaces — saving the
 * current panel arrangement, switching to a stored workspace, listing
 * available workspaces, and exporting/importing them as JSON.
 *
 * <p>The manager is intentionally <strong>UI-toolkit-agnostic</strong>:
 * all interaction with the live JavaFX UI happens through the {@link Host}
 * callback. That keeps {@code WorkspaceManager} unit-testable without a
 * screen and keeps the toolkit-specific code (which JavaFX panels exist,
 * how to show/hide them, how to read their bounds) inside
 * {@code MainController}.</p>
 *
 * <p>Switching workspaces only rearranges panels — the engine, transport,
 * playhead, and selection are owned by other controllers and are
 * <strong>not</strong> touched by this class.</p>
 *
 * <h2>Default workspaces</h2>
 * On first use the manager seeds {@link DefaultWorkspaces#all()} into the
 * store so the user always has Tracking/Editing/Mixing/Mastering/Spatial/Minimal
 * available. Callers that want to opt out can pass {@code seedDefaults=false}
 * to {@link #WorkspaceManager(WorkspaceStore, Host, boolean)}.
 *
 * <h2>Numbered slots</h2>
 * The first nine workspaces (in {@link #listAll} order) are addressable
 * by slot number 1–9, matching {@code DawAction.WORKSPACE_1}…
 * {@code WORKSPACE_9}. Slots beyond 9 are still saved and listed but have
 * no factory keyboard accelerator.
 */
public final class WorkspaceManager {

    private static final Logger LOG = Logger.getLogger(WorkspaceManager.class.getName());

    /** Maximum number of keyboard-addressable workspaces (Ctrl+Alt+1…9). */
    public static final int MAX_NUMBERED_SLOTS = 9;

    /**
     * Callback bridging the manager and the live JavaFX UI. The
     * implementations live in {@code MainController} (production) or in
     * tests (recording stubs).
     */
    public interface Host {

        /**
         * Returns the panel ids the host knows about. Used as the catalog
         * when capturing the current workspace; missing ids in a stored
         * workspace are simply ignored when restoring (forward-compatible).
         */
        List<String> knownPanelIds();

        /** Returns whether the named panel is currently visible. */
        boolean isPanelVisible(String panelId);

        /** Shows/hides the named panel. Implementations may no-op for unknown ids. */
        void setPanelVisible(String panelId, boolean visible);

        /**
         * Returns the current zoom factor for the named panel ({@code 1.0}
         * if the panel has no dedicated zoom). Default returns {@code 1.0}.
         */
        default double panelZoom(String panelId) {
            return 1.0;
        }

        /** Applies the given zoom factor to the named panel. Default is a no-op. */
        default void setPanelZoom(String panelId, double zoom) { }

        /** Returns the current scroll offset for the named panel. Default is {@code (0,0)}. */
        default double[] panelScroll(String panelId) {
            return new double[] {0, 0};
        }

        /** Applies the scroll offset to the named panel. Default is a no-op. */
        default void setPanelScroll(String panelId, double scrollX, double scrollY) { }

        /** Returns the current bounds for the named panel, or {@code null} if unknown. */
        default Rectangle2D panelBounds(String panelId) {
            return null;
        }

        /** Applies the given bounds to the named panel. Default is a no-op. */
        default void setPanelBounds(String panelId, Rectangle2D bounds) { }

        /** Returns the ids of the dialogs that are currently visible. Default is empty. */
        default List<String> openDialogIds() {
            return List.of();
        }

        /** Re-opens the given dialog by id. Default is a no-op. */
        default void openDialog(String dialogId) { }

        /** Closes any currently-open dialogs. Default is a no-op. */
        default void closeAllDialogs() { }
    }

    private final WorkspaceStore store;
    private final Host host;

    /** Caches list ordering between invocations so slot 1..9 are stable. */
    private List<Workspace> cache;

    /**
     * Creates a manager rooted at {@code ~/.daw/workspaces/} that seeds
     * the default workspaces on first run.
     */
    public WorkspaceManager(Host host) {
        this(new WorkspaceStore(), host, true);
    }

    /** Creates a manager backed by the given store; seeds defaults on first run. */
    public WorkspaceManager(WorkspaceStore store, Host host) {
        this(store, host, true);
    }

    /** Full constructor — used by tests that don't want to seed defaults. */
    public WorkspaceManager(WorkspaceStore store, Host host, boolean seedDefaults) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
        if (seedDefaults && store.listAll().isEmpty()) {
            for (Workspace w : DefaultWorkspaces.all()) {
                store.save(w);
            }
            LOG.fine("Seeded " + DefaultWorkspaces.all().size() + " default workspaces");
        }
        refresh();
    }

    /** Returns all stored workspaces sorted by filename for stable slot assignment. */
    public List<Workspace> listAll() {
        return cache;
    }

    /** Returns the workspace at the given 1-based slot, or empty if unfilled. */
    public Optional<Workspace> slot(int oneBasedIndex) {
        if (oneBasedIndex < 1 || oneBasedIndex > cache.size()) return Optional.empty();
        return Optional.of(cache.get(oneBasedIndex - 1));
    }

    /** Returns a workspace by name, or empty. */
    public Optional<Workspace> findByName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        for (Workspace w : cache) {
            if (w.name().equals(name)) return Optional.of(w);
        }
        return Optional.empty();
    }

    /**
     * Captures the current UI state into a {@link Workspace} without
     * persisting it. Use {@link #saveCurrentAs(String)} to store and
     * register the result in the same step.
     */
    public Workspace captureCurrent(String name) {
        Objects.requireNonNull(name, "name must not be null");
        Map<String, PanelState> states = new LinkedHashMap<>();
        Map<String, Rectangle2D> bounds = new LinkedHashMap<>();
        for (String id : host.knownPanelIds()) {
            boolean visible = host.isPanelVisible(id);
            double zoom = sanitizeZoom(host.panelZoom(id));
            double[] scroll = host.panelScroll(id);
            double sx = scroll != null && scroll.length >= 2 ? sanitizeFinite(scroll[0]) : 0;
            double sy = scroll != null && scroll.length >= 2 ? sanitizeFinite(scroll[1]) : 0;
            states.put(id, new PanelState(visible, zoom, sx, sy));
            Rectangle2D b = host.panelBounds(id);
            if (b != null) bounds.put(id, b);
        }
        List<String> dialogs = List.copyOf(Optional.ofNullable(host.openDialogIds()).orElse(List.of()));
        return new Workspace(name, states, dialogs, bounds);
    }

    /** Captures the current UI state and persists it under {@code name}. */
    public Workspace saveCurrentAs(String name) {
        Workspace ws = captureCurrent(name);
        store.save(ws);
        refresh();
        LOG.fine(() -> "Saved workspace '" + name + "'");
        return ws;
    }

    /**
     * Applies the given workspace to the live UI: shows/hides each known
     * panel, restores its zoom/scroll/bounds, and re-opens persisted
     * dialogs. Unknown panel ids in the workspace are silently ignored
     * (forward compatibility).
     */
    public void apply(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        host.closeAllDialogs();
        for (String id : host.knownPanelIds()) {
            PanelState state = workspace.panelStates().get(id);
            if (state == null) {
                continue; // not part of this workspace — leave as-is
            }
            host.setPanelVisible(id, state.visible());
            host.setPanelZoom(id, state.zoom());
            host.setPanelScroll(id, state.scrollX(), state.scrollY());
            Rectangle2D bounds = workspace.panelBounds().get(id);
            if (bounds != null) host.setPanelBounds(id, bounds);
        }
        for (String dialogId : workspace.openDialogs()) {
            host.openDialog(dialogId);
        }
        LOG.fine(() -> "Applied workspace '" + workspace.name() + "'");
    }

    /** Convenience: switch to the workspace at the given 1-based slot. */
    public boolean switchToSlot(int oneBasedIndex) {
        Optional<Workspace> ws = slot(oneBasedIndex);
        ws.ifPresent(this::apply);
        return ws.isPresent();
    }

    /** Convenience: switch to a workspace by name. */
    public boolean switchToName(String name) {
        Optional<Workspace> ws = findByName(name);
        ws.ifPresent(this::apply);
        return ws.isPresent();
    }

    /** Deletes the workspace with the given name; returns {@code true} on success. */
    public boolean delete(String name) {
        boolean removed = store.delete(name);
        if (removed) refresh();
        return removed;
    }

    /** Exports the named workspace to a path (for sharing). */
    public void exportTo(String name, Path target) throws IOException {
        Workspace ws = findByName(name)
                .orElseThrow(() -> new IOException("No workspace named '" + name + "'"));
        store.exportTo(ws, target);
    }

    /**
     * Imports a workspace from {@code source} and registers it in the
     * store, replacing any existing workspace with the same name.
     */
    public Workspace importFrom(Path source) throws IOException {
        Workspace ws = store.importFrom(source);
        store.save(ws);
        refresh();
        return ws;
    }

    /** Reloads the in-memory cache from disk. Visible for tests. */
    void refresh() {
        this.cache = store.listAll();
    }

    private static double sanitizeZoom(double z) {
        if (!Double.isFinite(z) || z <= 0.0) return 1.0;
        return z;
    }

    private static double sanitizeFinite(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }

    /** Returns the underlying store (for tests and advanced callers). */
    public WorkspaceStore store() {
        return store;
    }
}
