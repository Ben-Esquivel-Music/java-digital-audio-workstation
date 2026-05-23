package com.benesquivelmusic.daw.app.ui.layout;

import com.benesquivelmusic.daw.app.ui.dock.DockEntry;
import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Observable view model for the persistent <strong>dock manifest</strong>
 * bar at the bottom of the main window (UI Design Book §4 Concept D,
 * story 282).
 *
 * <p>The manifest enumerates every registered panel — docked or
 * floating — so the user can:</p>
 * <ul>
 *   <li>see at a glance which panels exist in the session;</li>
 *   <li>click a manifest tab to focus / unhide the corresponding
 *       panel;</li>
 *   <li>grab the §4 mockup's {@code ⋮⋮} grip handle to drag a panel out
 *       into a floating window — that interaction fires a
 *       {@link PanelDetachRequestedEvent} on the manifest tab so the
 *       owning controller can route it through
 *       {@link DockManager#float_(String, com.benesquivelmusic.daw.sdk.ui.Rectangle2D)}.</li>
 * </ul>
 *
 * <p>The model is intentionally <strong>UI-toolkit-agnostic</strong>:
 * it exposes an {@link ObservableList} of immutable {@link Entry}
 * records that the manifest bar binds to (e.g. via a
 * {@code ListView<Entry>}). The model itself never builds JavaFX nodes,
 * so it is unit-testable headlessly.</p>
 *
 * <p>The model subscribes to {@link DockManager#addListener(java.util.function.Consumer)}
 * in its constructor and rebuilds its list on every dock-layout change.
 * Call {@link #dispose()} when the model is no longer needed (e.g. when
 * the main window closes) to detach the listener and avoid leaks
 * (Skill §15).</p>
 */
public final class DockManifestModel {

    /**
     * One manifest entry — a panel id with the metadata the manifest
     * bar renders (display name for the tab label, icon name for the
     * glyph, current zone, and whether the panel is visible).
     *
     * @param panelId     {@link Dockable#dockId()} of the panel
     * @param displayName label shown on the tab
     * @param iconName    icon registry key (may be empty)
     * @param zone        current dock zone (or {@link DockZone#FLOATING})
     * @param visible     whether the panel is currently visible
     */
    public record Entry(String panelId,
                        String displayName,
                        String iconName,
                        DockZone zone,
                        boolean visible) {

        public Entry {
            Objects.requireNonNull(panelId, "panelId must not be null");
            Objects.requireNonNull(displayName, "displayName must not be null");
            Objects.requireNonNull(zone, "zone must not be null");
            if (iconName == null) iconName = "";
        }
    }

    private final DockManager dockManager;
    private final ObservableList<Entry> entries = FXCollections.observableArrayList();
    private final ObservableList<Entry> entriesView =
            FXCollections.unmodifiableObservableList(entries);
    private Runnable unsubscribe;

    /**
     * Creates a manifest model bound to the given dock manager. The
     * model immediately seeds its entries from the manager's current
     * layout and listens for subsequent changes.
     */
    public DockManifestModel(DockManager dockManager) {
        this.dockManager = Objects.requireNonNull(dockManager, "dockManager must not be null");
        rebuild(dockManager.layout());
        this.unsubscribe = dockManager.addListener(this::rebuild);
    }

    /**
     * Live, observable, <strong>unmodifiable</strong> list of manifest
     * entries. Ordering follows the dock layer's registration order so
     * the manifest is stable regardless of how panels are reshuffled
     * between zones (matches the §4 mockup: the manifest is a flat
     * strip, not grouped by zone). All mutations are internal to the
     * model — external consumers observe but cannot modify.
     */
    public ObservableList<Entry> entries() {
        return entriesView;
    }

    /** Returns the manifest entry for the given panel id, if any. */
    public Optional<Entry> entry(String panelId) {
        for (Entry e : entries) {
            if (e.panelId().equals(panelId)) return Optional.of(e);
        }
        return Optional.empty();
    }

    /**
     * Click handler for a manifest tab: makes the panel visible and (in
     * a real UI) raises focus. The manifest bar's tab control wires its
     * {@code onAction} to this method.
     */
    public void focusPanel(String panelId) {
        dockManager.setVisible(panelId, true);
    }

    /**
     * Releases the listener subscription. Call when the manifest model
     * is no longer needed to avoid the listener holding a reference to
     * the manager (Skill §15 — "Unbounded listener registration without
     * matching removal in dispose()").
     */
    public void dispose() {
        if (unsubscribe != null) {
            unsubscribe.run();
            unsubscribe = null;
        }
    }

    private void rebuild(DockLayout layout) {
        List<Entry> next = new ArrayList<>();
        for (Dockable panel : dockManager.registered()) {
            DockEntry e = layout.entry(panel.dockId()).orElse(null);
            DockZone zone = e == null ? panel.preferredZone() : e.zone();
            boolean visible = e == null || e.visible();
            next.add(new Entry(panel.dockId(), panel.displayName(),
                    panel.iconName() == null ? "" : panel.iconName(),
                    zone, visible));
        }
        // setAll fires a single change event — cheaper for ListView consumers.
        entries.setAll(next);
    }
}
