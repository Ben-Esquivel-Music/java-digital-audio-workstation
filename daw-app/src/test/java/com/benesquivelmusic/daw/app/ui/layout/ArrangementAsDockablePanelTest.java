package com.benesquivelmusic.daw.app.ui.layout;

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
 * Story 282 acceptance criterion ({@code ArrangementAsDockablePanelTest}):
 * float the arrangement, assert the centre dock slot is empty and the
 * arrangement is in a separate window. Re-dock, assert centre slot is
 * restored.
 *
 * <p>Demonstrates the "arrangement is just another dockable panel"
 * requirement at the dock-model layer (the JavaFX integration is a
 * thin consumer of the resulting {@link DockLayout}).</p>
 */
class ArrangementAsDockablePanelTest {

    private record TestPanel(String dockId, String displayName, DockZone preferredZone)
            implements Dockable {
        @Override public String iconName() { return "ICON_" + dockId; }
    }

    private static final class SilentHost implements DockManager.Host {
        @Override public void onLayoutChanged(DockLayout newLayout) { /* no-op */ }
    }

    @Test
    void arrangementCanFloatAndReDock(@TempDir Path tmp) {
        DockManager dm = new DockManager(new SilentHost(),
                new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("arrangement", "Arrangement", DockZone.CENTER));
        // Pre-condition: arrangement starts docked in CENTER.
        assertThat(dm.layout().entriesInZone(DockZone.CENTER))
                .extracting(e -> e.panelId())
                .containsExactly("arrangement");

        // Float the arrangement.
        Rectangle2D bounds = new Rectangle2D(80, 80, 1280, 720);
        dm.float_("arrangement", bounds);

        // Centre slot is empty; arrangement is in its own floating "window".
        assertThat(dm.layout().entriesInZone(DockZone.CENTER)).isEmpty();
        assertThat(dm.layout().entry("arrangement")).get()
                .extracting(e -> e.zone())
                .isEqualTo(DockZone.FLOATING);
        assertThat(dm.layout().entry("arrangement").get().floatingBounds())
                .isEqualTo(bounds);

        // Re-dock back to CENTER.
        dm.move("arrangement", DockZone.CENTER, 0);
        assertThat(dm.layout().entriesInZone(DockZone.CENTER))
                .extracting(e -> e.panelId())
                .containsExactly("arrangement");
        assertThat(dm.layout().entry("arrangement").get().zone())
                .isEqualTo(DockZone.CENTER);
    }

    @Test
    void detachAndDockEventsCarryPanelIdentity() {
        // Round-trip the typed events the dock manifest and grip-handle
        // drag interaction fire (Skill §12 — typed events, not callbacks).
        PanelDetachRequestedEvent detach = new PanelDetachRequestedEvent("arrangement");
        assertThat(detach.panelId()).isEqualTo("arrangement");
        assertThat(detach.getEventType())
                .isEqualTo(PanelDetachRequestedEvent.PANEL_DETACH_REQUESTED);

        PanelDockRequestedEvent dock = new PanelDockRequestedEvent("arrangement", DockZone.CENTER);
        assertThat(dock.panelId()).isEqualTo("arrangement");
        assertThat(dock.targetZone()).isEqualTo(DockZone.CENTER);
        assertThat(dock.getEventType())
                .isEqualTo(PanelDockRequestedEvent.PANEL_DOCK_REQUESTED);
    }
}
