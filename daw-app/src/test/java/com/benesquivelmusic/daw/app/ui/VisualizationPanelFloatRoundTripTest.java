package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 287 — a visualization analyzer panel can be floated out into its
 * own window and re-docked through the standard {@code DockManager} API
 * (the dock model is UI-toolkit-agnostic, so this needs no FX toolkit —
 * the analyzer participates exactly like any other dockable).
 */
class VisualizationPanelFloatRoundTripTest {

    /** Lightweight fixture standing in for a BOTTOM analyzer dockable. */
    private record TestPanel(String dockId, String displayName, DockZone preferredZone)
            implements Dockable {
        @Override public String iconName() { return "ICON_" + dockId; }
    }

    private static final class SilentHost implements DockManager.Host {
        @Override public void onLayoutChanged(DockLayout newLayout) { /* no-op */ }
    }

    @Test
    void spectrumFloatsThenReDocksToBottom(@TempDir Path tmp) {
        DockManager dm = new DockManager(new SilentHost(),
                new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel(DefaultWorkspaces.PANEL_SPECTRUM, "Spectrum", DockZone.BOTTOM));

        Rectangle2D bounds = new Rectangle2D(200, 120, 640, 320);
        dm.float_(DefaultWorkspaces.PANEL_SPECTRUM, bounds);
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_SPECTRUM).get().zone())
                .isEqualTo(DockZone.FLOATING);
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_SPECTRUM).get().floatingBounds())
                .isEqualTo(bounds);

        dm.move(DefaultWorkspaces.PANEL_SPECTRUM, DockZone.BOTTOM, 0);
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_SPECTRUM).get().zone())
                .isEqualTo(DockZone.BOTTOM);
    }
}
