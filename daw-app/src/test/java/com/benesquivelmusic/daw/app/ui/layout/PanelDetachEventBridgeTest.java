package com.benesquivelmusic.daw.app.ui.layout;

import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore;

import javafx.event.EventHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 282 acceptance criterion ({@code PanelDetachEventBridgeTest}):
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

        // The same handler that MainController installs on rootPane:
        EventHandler<PanelDetachRequestedEvent> handler =
                e -> dm.float_(e.getPanelId(), null);

        // Fire the event through the handler, verifying the event's
        // panelId is what drives the DockManager call.
        handler.handle(new PanelDetachRequestedEvent("mixer"));

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

        // The same handler that MainController installs on rootPane:
        EventHandler<PanelDockRequestedEvent> handler =
                e -> dm.moveToEnd(e.getPanelId(), e.getTargetZone());

        // Fire the event through the handler, verifying the event's
        // panelId and targetZone are what drive the DockManager call.
        handler.handle(new PanelDockRequestedEvent("mixer", DockZone.BOTTOM));

        assertThat(dm.layout().entry("mixer").orElseThrow().zone())
                .isEqualTo(DockZone.BOTTOM);
    }
}
