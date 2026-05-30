package com.benesquivelmusic.daw.app.ui.layout;

import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import javafx.event.EventHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 288 — {@code DetachEventCarriesDropBoundsTest}. The detach bridge
 * now honours a non-null drop-point {@link Rectangle2D} on the
 * {@link PanelDetachRequestedEvent} (it previously always passed
 * {@code null}), so the floating window opens where the user released the
 * grip drag.
 *
 * <p>Mirrors {@code PanelDetachEventBridgeTest}'s substitution exactly —
 * the FXML-loaded {@code MainController} hangs in tests
 * ({@code feedback_maincontroller_test_substitute.md}), so this exercises
 * the same one-liner the controller installs
 * ({@code e -> dm.float_(e.getPanelId(), e.getBounds())}) against a real
 * {@link DockManager}.</p>
 */
class DetachEventCarriesDropBoundsTest {

    private record TestPanel(String dockId, String displayName, DockZone preferredZone)
            implements Dockable {
        @Override public String iconName() { return "ICON"; }
    }

    private static final class SilentHost implements DockManager.Host {
        @Override public void onLayoutChanged(DockLayout newLayout) { /* no-op */ }
    }

    @Test
    void detachBridgeHonoursDropBounds(@TempDir Path tmp) {
        DockManager dm = new DockManager(new SilentHost(),
                new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("mixer", "Mixer", DockZone.BOTTOM));

        // The exact one-liner MainController.installLayoutManager() now
        // installs (story 288 — getBounds() replaces the prior null).
        EventHandler<PanelDetachRequestedEvent> handler =
                e -> dm.float_(e.getPanelId(), e.getBounds());

        Rectangle2D dropBounds = new Rectangle2D(10, 20, 300, 400);
        handler.handle(new PanelDetachRequestedEvent("mixer", dropBounds));

        var entry = dm.layout().entry("mixer").orElseThrow();
        assertThat(entry.zone()).isEqualTo(DockZone.FLOATING);
        assertThat(entry.floatingBounds())
                .as("floating window opens at the drop-point bounds")
                .isEqualTo(dropBounds);
    }

    @Test
    void nullBoundsStillFallsBackToDefault(@TempDir Path tmp) {
        // Backward-compatibility: a null-bounds detach (e.g. a menu-driven
        // float) keeps the prior remembered/default placement behaviour.
        DockManager dm = new DockManager(new SilentHost(),
                new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("mixer", "Mixer", DockZone.BOTTOM));

        EventHandler<PanelDetachRequestedEvent> handler =
                e -> dm.float_(e.getPanelId(), e.getBounds());

        handler.handle(new PanelDetachRequestedEvent("mixer")); // null bounds

        var entry = dm.layout().entry("mixer").orElseThrow();
        assertThat(entry.zone()).isEqualTo(DockZone.FLOATING);
        assertThat(entry.floatingBounds())
                .as("DockManager supplies fallback bounds when none given")
                .isNotNull();
    }
}
