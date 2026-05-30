package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore;
import com.benesquivelmusic.daw.app.ui.layout.BuiltInLayouts;
import com.benesquivelmusic.daw.app.ui.layout.LayoutManager;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 287 — an analyzer panel's dock state round-trips through the
 * named-layout JSON (story 286 sibling surface): float the loudness panel,
 * save a named layout, switch to a built-in, re-load the saved layout, and
 * assert loudness is floating again. Proves analyzer panels participate in
 * View → Layout persistence for free once registered.
 *
 * <p>The {@link LayoutManager.Host} bridge wraps a real {@link DockManager}
 * (capture / apply JSON) — the same bridge {@code MainController.installLayoutManager()}
 * builds — so this exercises the production capture/restore path headlessly.</p>
 */
class VisualizationLayoutPersistedTest {

    private record TestPanel(String dockId, String displayName, DockZone preferredZone)
            implements Dockable {
        @Override public String iconName() { return "ICON_" + dockId; }
    }

    private static final class SilentHost implements DockManager.Host {
        @Override public void onLayoutChanged(DockLayout newLayout) { /* no-op */ }
    }

    /** Bridges the LayoutManager to the live DockManager, exactly as MainController does. */
    private static final class DockBridge implements LayoutManager.Host {
        private final DockManager dm;
        DockBridge(DockManager dm) { this.dm = dm; }
        @Override public String captureDockLayoutJson() { return dm.captureJson(); }
        @Override public void applyDockLayoutJson(String json) {
            if (json != null && !json.isBlank()) dm.applyJson(json);
        }
    }

    @Test
    void loudnessFloatRoundTripsThroughNamedLayout(@TempDir Path tmp) {
        DockManager dm = new DockManager(new SilentHost(),
                new FloatingWindowStore(tmp.resolve("f.json")));
        // Register the panels the built-in layouts reference so applyJson
        // does not drop them, plus loudness (the panel under test).
        dm.register(new TestPanel(DefaultWorkspaces.PANEL_ARRANGEMENT, "Arrangement", DockZone.CENTER));
        dm.register(new TestPanel(DefaultWorkspaces.PANEL_MIXER, "Mixer", DockZone.CENTER));
        dm.register(new TestPanel(DefaultWorkspaces.PANEL_BROWSER, "Browser", DockZone.LEFT));
        dm.register(new TestPanel(DefaultWorkspaces.PANEL_LOUDNESS, "Loudness", DockZone.BOTTOM));

        LayoutManager layouts = new LayoutManager(new DockBridge(dm));

        // Float loudness, then save the current dock arrangement as "Viz".
        Rectangle2D bounds = new Rectangle2D(300, 150, 520, 300);
        dm.float_(DefaultWorkspaces.PANEL_LOUDNESS, bounds);
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_LOUDNESS).get().zone())
                .isEqualTo(DockZone.FLOATING);
        layouts.saveCurrent("Viz");

        // Switch to a built-in (re-docks loudness per the Default layout).
        assertThat(layouts.load(BuiltInLayouts.DEFAULT)).isTrue();
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_LOUDNESS).get().zone())
                .isNotEqualTo(DockZone.FLOATING);

        // Re-load the saved layout — loudness must be floating again.
        assertThat(layouts.load("Viz")).isTrue();
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_LOUDNESS).get().zone())
                .isEqualTo(DockZone.FLOATING);
    }
}
