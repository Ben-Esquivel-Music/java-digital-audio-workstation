package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.app.ui.WorkspaceManager;
import com.benesquivelmusic.daw.app.ui.WorkspaceStore;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import com.benesquivelmusic.daw.sdk.ui.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check that a {@link DockLayout} round-trips through
 * {@link Workspace} and {@link WorkspaceStore} so workspace switches
 * actually restore the dock state.
 */
class WorkspaceDockIntegrationTest {

    /** Recording host that bridges {@link DockManager} and {@link WorkspaceManager}. */
    private static final class IntegrationHost
            implements WorkspaceManager.Host, DockManager.Host {

        final DockManager dockManager;
        final Map<String, Boolean> visible = new LinkedHashMap<>();

        IntegrationHost(FloatingWindowStore store) {
            this.dockManager = new DockManager(this, store);
        }

        // ── DockManager.Host ────────────────────────────────────────────────
        @Override public void onLayoutChanged(DockLayout newLayout) { /* test stub */ }

        // ── WorkspaceManager.Host ───────────────────────────────────────────
        @Override public List<String> knownPanelIds() {
            return List.copyOf(visible.keySet());
        }
        @Override public boolean isPanelVisible(String panelId) {
            return visible.getOrDefault(panelId, false);
        }
        @Override public void setPanelVisible(String panelId, boolean v) {
            visible.put(panelId, v);
        }
        @Override public String captureDockLayoutJson() {
            return dockManager.captureJson();
        }
        @Override public void applyDockLayoutJson(String json) {
            dockManager.applyJson(json);
        }
    }

    private record TestPanel(String dockId, String displayName, DockZone preferredZone)
            implements Dockable { }

    @Test
    void dockLayoutSurvivesWorkspaceSaveAndApply(@TempDir Path tmp) {
        IntegrationHost host = new IntegrationHost(
                new FloatingWindowStore(tmp.resolve("floating.json")));
        host.dockManager.register(new TestPanel("arrangement", "Arrangement", DockZone.CENTER));
        host.dockManager.register(new TestPanel("mixer", "Mixer", DockZone.BOTTOM));
        host.visible.put("arrangement", true);
        host.visible.put("mixer", true);

        // User detaches the mixer to a 2nd-monitor floating window.
        Rectangle2D mixerBounds = new Rectangle2D(2000, 100, 1024, 600);
        host.dockManager.float_("mixer", mixerBounds);

        WorkspaceStore store = new WorkspaceStore(tmp.resolve("workspaces"));
        WorkspaceManager mgr = new WorkspaceManager(store, host, false);
        mgr.saveCurrentAs("Mixing-2-monitor");

        // Reset live layout — pretend the user switched to another workspace.
        host.dockManager.move("mixer", DockZone.BOTTOM, 0);
        assertThat(host.dockManager.layout().entry("mixer").get().zone())
                .isEqualTo(DockZone.BOTTOM);

        // Restore the saved workspace and assert the floating mixer is back.
        Workspace saved = mgr.findByName("Mixing-2-monitor").orElseThrow();
        assertThat(saved.dockLayoutJson()).isNotEmpty();
        mgr.apply(saved);

        DockEntry mixer = host.dockManager.layout().entry("mixer").get();
        assertThat(mixer.zone()).isEqualTo(DockZone.FLOATING);
        assertThat(mixer.floatingBounds()).isEqualTo(mixerBounds);
    }

    @Test
    void workspaceWithoutDockLayoutLeavesDockManagerUntouched(@TempDir Path tmp) {
        // Backwards-compatibility: a workspace persisted before dockable
        // panels existed (empty dockLayoutJson) must NOT clobber the
        // current dock layout when applied.
        IntegrationHost host = new IntegrationHost(
                new FloatingWindowStore(tmp.resolve("floating.json")));
        host.dockManager.register(new TestPanel("mixer", "Mixer", DockZone.BOTTOM));
        host.visible.put("mixer", true);
        host.dockManager.move("mixer", DockZone.RIGHT, 0);

        Workspace legacy = new Workspace("Legacy", Map.of(), List.of(), Map.of());
        WorkspaceStore store = new WorkspaceStore(tmp.resolve("workspaces"));
        WorkspaceManager mgr = new WorkspaceManager(store, host, false);
        mgr.apply(legacy);

        assertThat(host.dockManager.layout().entry("mixer").get().zone())
                .isEqualTo(DockZone.RIGHT);
    }
}
