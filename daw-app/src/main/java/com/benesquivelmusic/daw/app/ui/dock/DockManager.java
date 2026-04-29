package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Coordinates the live dock layout: registers panels, exposes a fluent
 * API for moving them between zones (or floating them out), and notifies
 * the UI host whenever the layout changes.
 *
 * <p>The manager is intentionally <strong>UI-toolkit-agnostic</strong>:
 * the {@link Host} callback hides every JavaFX-specific operation
 * (creating tab pane nodes, opening a {@code Stage}, querying the
 * monitor list…) so the manager itself can be unit-tested in a headless
 * environment.</p>
 *
 * <p>Floating-window persistence is delegated to a
 * {@link FloatingWindowStore}; bounds are written every time a panel is
 * docked, undocked, moved, or resized so a crash mid-session never loses
 * more than the latest move.</p>
 *
 * <h2>Threading</h2>
 * Methods on this class are not thread-safe. All mutation is expected to
 * happen on the JavaFX application thread (the same thread the UI host
 * runs on). The {@link FloatingWindowStore} it talks to is itself
 * thread-safe.
 */
public final class DockManager {

    private static final Logger LOG = Logger.getLogger(DockManager.class.getName());

    /** Bounds used when no preferred or remembered floating bounds exist. */
    public static final Rectangle2D DEFAULT_FLOATING_BOUNDS =
            new Rectangle2D(120, 80, 800, 480);

    /**
     * Bridge between the manager and the live UI. Methods that mutate
     * the screen receive a {@link DockLayout} snapshot rather than fine-
     * grained deltas so the UI can reconcile in one pass — this keeps
     * workspace switches atomic.
     */
    public interface Host {

        /** Called whenever the layout changes; implementations re-render the UI. */
        void onLayoutChanged(DockLayout newLayout);

        /**
         * Returns whether a screen is available that intersects the
         * given bounds. Used to detect floating windows whose monitor
         * was disconnected so they can be re-docked. Default returns
         * {@code true} (no monitor awareness in tests).
         */
        default boolean isScreenAvailableFor(Rectangle2D bounds) {
            return true;
        }
    }

    private final Map<String, Dockable> panels = new LinkedHashMap<>();
    private final FloatingWindowStore floatingStore;
    private final Host host;
    private final List<Consumer<DockLayout>> listeners = new ArrayList<>();
    private DockLayout layout = DockLayout.empty();

    /** Creates a manager backed by the default per-user floating window store. */
    public DockManager(Host host) {
        this(host, new FloatingWindowStore());
    }

    /** Creates a manager backed by the given floating window store. */
    public DockManager(Host host, FloatingWindowStore floatingStore) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.floatingStore = Objects.requireNonNull(floatingStore, "floatingStore must not be null");
    }

    /**
     * Registers a panel. The first registration places the panel in its
     * preferred zone; subsequent calls (e.g. after restart) reuse the
     * existing entry so the user's last placement is preserved.
     */
    public void register(Dockable panel) {
        Objects.requireNonNull(panel, "panel must not be null");
        String id = panel.dockId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("panel.dockId() must be non-blank");
        }
        panels.put(id, panel);
        if (!layout.contains(id)) {
            DockZone preferredZone = panel.preferredZone();
            DockZone zone = preferredZone == null || preferredZone == DockZone.FLOATING
                    ? DockZone.CENTER
                    : preferredZone;
            int nextIndex = layout.entriesInZone(zone).size();
            layout = layout.withEntry(DockEntry.docked(id, zone, nextIndex, true));
            fireChanged();
        }
    }

    /** Returns the panel registered under {@code id}, if any. */
    public Optional<Dockable> panel(String id) {
        return Optional.ofNullable(panels.get(id));
    }

    /** Unmodifiable view of every registered panel. */
    public Collection<Dockable> registered() {
        return java.util.Collections.unmodifiableCollection(panels.values());
    }

    /** Returns the current layout (immutable snapshot). */
    public DockLayout layout() {
        return layout;
    }

    /** Subscribes to layout-change events. Returns an unsubscribe runnable. */
    public Runnable addListener(Consumer<DockLayout> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    // ── Layout mutation ─────────────────────────────────────────────────────

    /** Moves a panel to {@code zone} at the given tab index. */
    public void move(String panelId, DockZone zone, int tabIndex) {
        Objects.requireNonNull(zone, "zone must not be null");
        if (zone == DockZone.FLOATING) {
            float_(panelId, null);
            return;
        }
        layout = layout.moveTo(panelId, zone, tabIndex);
        fireChanged();
    }

    /** Appends a panel at the end of {@code zone}'s tab strip. */
    public void moveToEnd(String panelId, DockZone zone) {
        Objects.requireNonNull(zone, "zone must not be null");
        if (zone == DockZone.FLOATING) {
            float_(panelId, null);
            return;
        }
        int end = layout.entriesInZone(zone).size();
        layout = layout.moveTo(panelId, zone, end);
        fireChanged();
    }

    /**
     * Detaches a panel into a floating window. {@code bounds} may be
     * {@code null} — the manager picks remembered bounds, then the
     * panel's default, then the system default.
     */
    public void float_(String panelId, Rectangle2D bounds) {
        Rectangle2D target = bounds;
        if (target == null) {
            target = floatingStore.getBounds(panelId).orElse(null);
        }
        if (target == null) {
            Dockable d = panels.get(panelId);
            if (d != null) target = d.defaultFloatingBounds();
        }
        if (target == null) target = DEFAULT_FLOATING_BOUNDS;
        layout = layout.moveToFloating(panelId, target);
        floatingStore.putBounds(panelId, target);
        fireChanged();
    }

    /** Updates the bounds of an already-floating panel (e.g. after a drag). */
    public void updateFloatingBounds(String panelId, Rectangle2D bounds) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        Optional<DockEntry> existing = layout.entry(panelId);
        if (existing.isEmpty() || existing.get().zone() != DockZone.FLOATING) {
            return;
        }
        layout = layout.withEntry(existing.get().withFloatingBounds(bounds));
        floatingStore.putBounds(panelId, bounds);
        fireChanged();
    }

    /** Toggles the visibility of a panel without changing its zone. */
    public void setVisible(String panelId, boolean visible) {
        if (!layout.contains(panelId)) return;
        DockLayout next = layout.setVisible(panelId, visible);
        if (next != layout) {
            layout = next;
            fireChanged();
        }
    }

    /**
     * Toggles a panel: if it isn't visible, makes it visible (and brings
     * it to the front of its tab strip); if it is, hides it. Used by the
     * {@code F3}/{@code F4}/{@code F5} keyboard shortcuts.
     */
    public void toggleVisible(String panelId) {
        Optional<DockEntry> entry = layout.entry(panelId);
        if (entry.isEmpty()) return;
        setVisible(panelId, !entry.get().visible());
    }

    // ── Workspace integration ───────────────────────────────────────────────

    /** Returns the current layout serialised to JSON. */
    public String captureJson() {
        return DockLayoutJson.toJson(layout);
    }

    /**
     * Replaces the current layout with the one parsed from {@code json}.
     * Unknown panels (not registered) are dropped; floating windows whose
     * monitor is unavailable are re-docked at their preferred zone.
     */
    public void applyJson(String json) {
        DockLayout parsed = DockLayoutJson.parse(json);
        applyLayout(parsed);
    }

    /**
     * Replaces the current layout atomically. Entries referring to
     * panels that have not been registered are dropped. Floating panels
     * whose monitor is no longer available are gracefully re-docked.
     */
    public void applyLayout(DockLayout incoming) {
        Objects.requireNonNull(incoming, "incoming must not be null");
        Map<String, DockEntry> result = new LinkedHashMap<>();
        Predicate<String> isKnown = panels::containsKey;
        for (DockEntry e : incoming.entries().values()) {
            if (!isKnown.test(e.panelId())) continue;
            DockEntry adjusted = e;
            if (e.zone() == DockZone.FLOATING) {
                Rectangle2D b = e.floatingBounds();
                if (b == null || !host.isScreenAvailableFor(b)) {
                    DockZone fallback = panels.get(e.panelId()).preferredZone();
                    if (fallback == null || fallback == DockZone.FLOATING) {
                        fallback = DockZone.CENTER;
                    }
                    adjusted = new DockEntry(e.panelId(), fallback, Integer.MAX_VALUE,
                            e.visible(), null);
                    final DockEntry redocked = adjusted;
                    LOG.fine(() -> "Re-docking " + e.panelId()
                            + " to " + redocked.zone() + " (no screen)");
                } else {
                    floatingStore.putBounds(e.panelId(), b);
                }
            }
            result.put(e.panelId(), adjusted);
        }
        // Place any registered panel that was missing from the layout
        // back into its preferred zone so the user is never left with a
        // panel that simply disappears after a workspace switch.
        for (Map.Entry<String, Dockable> me : panels.entrySet()) {
            if (!result.containsKey(me.getKey())) {
                Dockable d = me.getValue();
                DockZone zone = d.preferredZone() == null ? DockZone.CENTER : d.preferredZone();
                if (zone == DockZone.FLOATING) zone = DockZone.CENTER;
                result.put(me.getKey(),
                        DockEntry.docked(me.getKey(), zone, Integer.MAX_VALUE, true));
            }
        }
        layout = normalizeTabIndices(result.values());
        fireChanged();
    }

    /**
     * Renumbers tab indices across all zones in a single pass so each
     * zone has contiguous 0..N-1 indices in insertion order.
     */
    private static DockLayout normalizeTabIndices(Collection<DockEntry> entries) {
        Map<DockZone, Integer> nextTabIndexByZone = new LinkedHashMap<>();
        Map<String, DockEntry> normalized = new LinkedHashMap<>();
        for (DockEntry entry : entries) {
            if (entry.zone() == DockZone.FLOATING) {
                normalized.put(entry.panelId(), entry);
            } else {
                int tabIndex = nextTabIndexByZone.getOrDefault(entry.zone(), 0);
                normalized.put(entry.panelId(), new DockEntry(
                        entry.panelId(),
                        entry.zone(),
                        tabIndex,
                        entry.visible(),
                        null));
                nextTabIndexByZone.put(entry.zone(), tabIndex + 1);
            }
        }
        return DockLayout.of(normalized);
    }

    private void fireChanged() {
        host.onLayoutChanged(layout);
        for (Consumer<DockLayout> l : new ArrayList<>(listeners)) {
            l.accept(layout);
        }
    }
}
