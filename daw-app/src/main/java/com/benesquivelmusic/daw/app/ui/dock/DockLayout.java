package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of every dockable panel's current placement.
 *
 * <p>{@code DockLayout} is deliberately a value type: every mutation
 * returns a new layout, leaving the original intact. That means
 * controllers can freely share, snapshot, and roll back layouts without
 * defensive copying. The class also exposes a small set of pure helper
 * operations ({@link #moveTo}, {@link #moveToFloating}, {@link
 * #setVisible}) that compute the next layout for a single panel — all
 * tab-index renumbering is handled centrally so callers cannot produce
 * invalid (e.g. duplicated) tab indices.</p>
 */
public final class DockLayout {

    private static final DockLayout EMPTY = new DockLayout(Map.of());

    private final Map<String, DockEntry> entries;

    private DockLayout(Map<String, DockEntry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** Returns the empty layout (no panels registered). */
    public static DockLayout empty() {
        return EMPTY;
    }

    /** Returns a layout containing exactly the supplied entries. */
    public static DockLayout of(Map<String, DockEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        return new DockLayout(entries);
    }

    /** Unmodifiable view of every registered panel keyed by id. */
    public Map<String, DockEntry> entries() {
        return entries;
    }

    /** Returns the entry for the given panel id, or empty. */
    public Optional<DockEntry> entry(String panelId) {
        return Optional.ofNullable(entries.get(panelId));
    }

    /** Returns the entries currently in {@code zone}, sorted by tab index. */
    public List<DockEntry> entriesInZone(DockZone zone) {
        Objects.requireNonNull(zone, "zone must not be null");
        List<DockEntry> out = new ArrayList<>();
        for (DockEntry e : entries.values()) {
            if (e.zone() == zone) out.add(e);
        }
        out.sort((a, b) -> Integer.compare(a.tabIndex(), b.tabIndex()));
        return Collections.unmodifiableList(out);
    }

    /** Returns whether {@code panelId} is registered. */
    public boolean contains(String panelId) {
        return entries.containsKey(panelId);
    }

    /**
     * Returns a new layout with {@code entry} added (or replaced); tab
     * indices in the entry's zone are renumbered to 0..N-1 so duplicates
     * are impossible.
     */
    public DockLayout withEntry(DockEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        Map<String, DockEntry> next = new LinkedHashMap<>(entries);
        next.put(entry.panelId(), entry);
        return new DockLayout(renumberZones(next));
    }

    /** Returns a new layout with {@code panelId} removed. */
    public DockLayout without(String panelId) {
        if (!entries.containsKey(panelId)) return this;
        Map<String, DockEntry> next = new LinkedHashMap<>(entries);
        next.remove(panelId);
        return new DockLayout(renumberZones(next));
    }

    /**
     * Moves the named panel to {@code targetZone} at {@code targetTabIndex}.
     * Tab indices in both the source and target zones are renumbered so
     * the resulting layout is well-formed.
     *
     * <p>Cannot move to {@link DockZone#FLOATING} — use
     * {@link #moveToFloating} instead so the floating bounds are
     * provided.</p>
     */
    public DockLayout moveTo(String panelId, DockZone targetZone, int targetTabIndex) {
        Objects.requireNonNull(panelId, "panelId must not be null");
        Objects.requireNonNull(targetZone, "targetZone must not be null");
        if (targetZone == DockZone.FLOATING) {
            throw new IllegalArgumentException("Use moveToFloating() to move into FLOATING zone");
        }
        DockEntry current = entries.get(panelId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown panelId: " + panelId);
        }
        int clampedIndex = Math.max(0, targetTabIndex);
        Map<String, DockEntry> next = new LinkedHashMap<>(entries);
        next.put(panelId, new DockEntry(panelId, targetZone, clampedIndex, current.visible(), null));
        return new DockLayout(renumberZones(next));
    }

    /**
     * Detaches the named panel into a floating window at {@code bounds}.
     * Bounds are required so that the floating window has a deterministic
     * size and position — callers should fall back to
     * {@link Dockable#defaultFloatingBounds()} when the user has not
     * previously sized the window.
     */
    public DockLayout moveToFloating(String panelId, Rectangle2D bounds) {
        Objects.requireNonNull(panelId, "panelId must not be null");
        Objects.requireNonNull(bounds, "bounds must not be null");
        DockEntry current = entries.get(panelId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown panelId: " + panelId);
        }
        Map<String, DockEntry> next = new LinkedHashMap<>(entries);
        next.put(panelId, DockEntry.floating(panelId, bounds).withVisible(current.visible()));
        return new DockLayout(renumberZones(next));
    }

    /** Returns a layout with the named panel's visibility flipped. */
    public DockLayout setVisible(String panelId, boolean visible) {
        DockEntry e = entries.get(panelId);
        if (e == null) {
            throw new IllegalArgumentException("Unknown panelId: " + panelId);
        }
        if (e.visible() == visible) return this;
        Map<String, DockEntry> next = new LinkedHashMap<>(entries);
        next.put(panelId, e.withVisible(visible));
        return new DockLayout(next);
    }

    /** Returns a layout where every floating panel is forcibly re-docked at its preferred zone. */
    public DockLayout reDockAllFloating(java.util.function.Function<String, DockZone> preferredZoneOf) {
        Objects.requireNonNull(preferredZoneOf, "preferredZoneOf must not be null");
        Map<String, DockEntry> next = new LinkedHashMap<>(entries);
        boolean changed = false;
        for (Map.Entry<String, DockEntry> me : entries.entrySet()) {
            DockEntry e = me.getValue();
            if (e.zone() == DockZone.FLOATING) {
                DockZone fallback = preferredZoneOf.apply(e.panelId());
                if (fallback == null || fallback == DockZone.FLOATING) {
                    fallback = DockZone.CENTER;
                }
                next.put(me.getKey(),
                        new DockEntry(e.panelId(), fallback, Integer.MAX_VALUE, e.visible(), null));
                changed = true;
            }
        }
        return changed ? new DockLayout(renumberZones(next)) : this;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DockLayout other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "DockLayout" + entries;
    }

    /**
     * Renumbers tab indices within every non-floating zone to
     * 0..N-1, preserving the relative order of existing entries (entries
     * that share an index keep insertion order — which matches the
     * iteration order of {@link LinkedHashMap}).
     */
    private static Map<String, DockEntry> renumberZones(Map<String, DockEntry> raw) {
        Map<DockZone, List<DockEntry>> byZone = new java.util.EnumMap<>(DockZone.class);
        for (DockEntry e : raw.values()) {
            byZone.computeIfAbsent(e.zone(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<DockZone, List<DockEntry>> me : byZone.entrySet()) {
            if (me.getKey() == DockZone.FLOATING) continue; // tabIndex irrelevant
            // Stable sort by current tabIndex so the user-chosen order is preserved.
            me.getValue().sort((a, b) -> Integer.compare(a.tabIndex(), b.tabIndex()));
        }
        Map<String, DockEntry> result = new LinkedHashMap<>();
        // Iterate in original insertion order so unrelated panels retain
        // their place in the map; only tabIndex values are rewritten.
        Map<String, Integer> renumbered = new LinkedHashMap<>();
        for (Map.Entry<DockZone, List<DockEntry>> me : byZone.entrySet()) {
            if (me.getKey() == DockZone.FLOATING) continue;
            int i = 0;
            for (DockEntry e : me.getValue()) {
                renumbered.put(e.panelId(), i++);
            }
        }
        for (Map.Entry<String, DockEntry> me : raw.entrySet()) {
            DockEntry e = me.getValue();
            if (e.zone() == DockZone.FLOATING) {
                result.put(me.getKey(), e);
            } else {
                int idx = renumbered.getOrDefault(me.getKey(), e.tabIndex());
                result.put(me.getKey(), e.withTabIndex(idx));
            }
        }
        return result;
    }
}
