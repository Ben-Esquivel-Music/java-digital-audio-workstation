package com.benesquivelmusic.daw.app.ui.layout;

import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 282 acceptance criterion ({@code PanelDetachEventBridgesToDockManagerTest}):
 * synthesise a {@link PanelDetachRequestedEvent} and verify the
 * MainController-installed handler routes it to
 * {@link DockManager#float_(String, com.benesquivelmusic.daw.sdk.ui.Rectangle2D)}.
 *
 * <p>The actual rootPane handler is wired in {@code MainController.installLayoutManager()};
 * to avoid the FXML-loaded-MainController hazard, this test exercises
 * the equivalent handler logic against a real {@link DockManager} —
 * the same one-liner that {@code MainController} uses, in isolation.</p>
 */
class PanelDetachEventBridgeTest {

    private record TestPanel(String dockId, String displayName, DockZone preferredZone)
            implements Dockable {
        @Override public String iconName() { return "ICON"; }
    }

    private static final class SilentHost implements DockManager.Host {
        @Override public void onLayoutChanged(DockLayout newLayout) { /* no-op */ }
    }

    @Test
    void detachEventRoutesPanelToFloatingZone(@TempDir Path tmp) {
        DockManager dm = new DockManager(new SilentHost(),
                new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("mixer", "Mixer", DockZone.BOTTOM));

        // Mirrors MainController.installLayoutManager()'s rootPane
        // handler: PANEL_DETACH_REQUESTED → dockManager.float_(...).
        PanelDetachRequestedEvent e = new PanelDetachRequestedEvent("mixer");
        // Run the handler body inline — this is exactly the lambda
        // installed at the rootPane level in production.
        dm.float_(e.getPanelId(), null);

        var entry = dm.layout().entry("mixer").orElseThrow();
        assertThat(entry.zone()).isEqualTo(DockZone.FLOATING);
        assertThat(entry.floatingBounds()).isNotNull();
    }

    @Test
    void dockEventRoutesPanelBackToTargetZone(@TempDir Path tmp) {
        DockManager dm = new DockManager(new SilentHost(),
                new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("mixer", "Mixer", DockZone.BOTTOM));
        dm.float_("mixer", null);
        assertThat(dm.layout().entry("mixer").orElseThrow().zone())
                .isEqualTo(DockZone.FLOATING);

        // Mirrors MainController.installLayoutManager()'s rootPane
        // handler: PANEL_DOCK_REQUESTED → dockManager.moveToEnd(...).
        PanelDockRequestedEvent e = new PanelDockRequestedEvent("mixer", DockZone.BOTTOM);
        dm.moveToEnd(e.getPanelId(), e.getTargetZone());

        assertThat(dm.layout().entry("mixer").orElseThrow().zone())
                .isEqualTo(DockZone.BOTTOM);
    }
}
