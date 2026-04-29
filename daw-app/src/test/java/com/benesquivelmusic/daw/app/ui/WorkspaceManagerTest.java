package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.ui.PanelState;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import com.benesquivelmusic.daw.sdk.ui.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WorkspaceManager}. These tests exercise the manager's
 * pure logic — {@link WorkspaceManager.Host} is implemented as an
 * in-memory recording stub, so no JavaFX screen is required.
 */
class WorkspaceManagerTest {

    /**
     * Recording stub that mimics a tiny set of UI panels. Visibility,
     * zoom, scroll, bounds, and open-dialog state are all stored in
     * regular fields so tests can read them back after
     * {@link WorkspaceManager#apply}.
     */
    private static final class StubHost implements WorkspaceManager.Host {
        Map<String, Boolean> visible = new LinkedHashMap<>();
        Map<String, Double> zoom = new LinkedHashMap<>();
        Map<String, double[]> scroll = new LinkedHashMap<>();
        Map<String, Rectangle2D> bounds = new LinkedHashMap<>();
        List<String> openDialogs = new ArrayList<>();
        int closeAllDialogsCalls;

        StubHost() {
            for (String id : List.of("arrangement", "mixer", "editor", "browser")) {
                visible.put(id, false);
                zoom.put(id, 1.0);
                scroll.put(id, new double[] {0, 0});
                bounds.put(id, new Rectangle2D(0, 0, 100, 100));
            }
        }

        @Override public List<String> knownPanelIds() { return List.copyOf(visible.keySet()); }
        @Override public boolean isPanelVisible(String id) { return visible.getOrDefault(id, false); }
        @Override public void setPanelVisible(String id, boolean v) { visible.put(id, v); }
        @Override public double panelZoom(String id) { return zoom.getOrDefault(id, 1.0); }
        @Override public void setPanelZoom(String id, double z) { zoom.put(id, z); }
        @Override public double[] panelScroll(String id) {
            return scroll.getOrDefault(id, new double[] {0, 0});
        }
        @Override public void setPanelScroll(String id, double sx, double sy) {
            scroll.put(id, new double[] {sx, sy});
        }
        @Override public Rectangle2D panelBounds(String id) { return bounds.get(id); }
        @Override public void setPanelBounds(String id, Rectangle2D r) { bounds.put(id, r); }
        @Override public List<String> openDialogIds() { return List.copyOf(openDialogs); }
        @Override public void openDialog(String id) { openDialogs.add(id); }
        @Override public void closeAllDialogs() { openDialogs.clear(); closeAllDialogsCalls++; }
    }

    @Test
    void seedsDefaultsOnFirstRun(@TempDir Path tmp) {
        var manager = new WorkspaceManager(new WorkspaceStore(tmp), new StubHost());
        List<String> names = manager.listAll().stream().map(Workspace::name).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "Tracking", "Editing", "Mixing", "Mastering", "Spatial", "Minimal");
    }

    @Test
    void doesNotReseedIfWorkspacesAlreadyPresent(@TempDir Path tmp) {
        var store = new WorkspaceStore(tmp);
        store.save(new Workspace("Custom", Map.of(), List.of(), Map.of()));
        var manager = new WorkspaceManager(store, new StubHost());
        assertThat(manager.listAll()).hasSize(1);
        assertThat(manager.listAll().getFirst().name()).isEqualTo("Custom");
    }

    @Test
    void saveCurrentAsPersistsAndRefreshesCache(@TempDir Path tmp) {
        var host = new StubHost();
        host.visible.put("arrangement", true);
        host.zoom.put("arrangement", 2.0);
        host.scroll.put("arrangement", new double[] {50, 75});

        var manager = new WorkspaceManager(new WorkspaceStore(tmp), host, false);
        Workspace saved = manager.saveCurrentAs("My Layout");

        assertThat(saved.name()).isEqualTo("My Layout");
        assertThat(saved.panelStates().get("arrangement").visible()).isTrue();
        assertThat(saved.panelStates().get("arrangement").zoom()).isEqualTo(2.0);
        assertThat(saved.panelStates().get("arrangement").scrollX()).isEqualTo(50.0);

        // Cache reflects new save
        assertThat(manager.findByName("My Layout")).isPresent();
    }

    /**
     * Round-trip test required by the issue: save a workspace, switch to a
     * different one, switch back, verify panel positions/visibility are
     * restored.
     */
    @Test
    void saveSwitchAndSwitchBackRestoresAllPanelState(@TempDir Path tmp) {
        var host = new StubHost();
        var manager = new WorkspaceManager(new WorkspaceStore(tmp), host, false);

        // Configure "Mixing": only mixer visible at zoom 1.5, scroll (10,20).
        host.visible.put("arrangement", false);
        host.visible.put("mixer", true);
        host.visible.put("editor", false);
        host.visible.put("browser", false);
        host.zoom.put("mixer", 1.5);
        host.scroll.put("mixer", new double[] {10, 20});
        host.bounds.put("mixer", new Rectangle2D(50, 60, 800, 500));
        manager.saveCurrentAs("Mixing");

        // Configure "Tracking": arrangement+browser visible.
        host.visible.put("arrangement", true);
        host.visible.put("mixer", false);
        host.visible.put("editor", false);
        host.visible.put("browser", true);
        host.zoom.put("arrangement", 0.75);
        host.scroll.put("arrangement", new double[] {200, 0});
        manager.saveCurrentAs("Tracking");

        // Mutate live state — simulate user reshaping the UI.
        host.visible.put("arrangement", false);
        host.visible.put("mixer", true);
        host.zoom.put("arrangement", 1.0);

        // Switch to Mixing and verify state is restored.
        assertThat(manager.switchToName("Mixing")).isTrue();
        assertThat(host.visible.get("arrangement")).isFalse();
        assertThat(host.visible.get("mixer")).isTrue();
        assertThat(host.visible.get("browser")).isFalse();
        assertThat(host.zoom.get("mixer")).isEqualTo(1.5);
        assertThat(host.scroll.get("mixer")).containsExactly(10.0, 20.0);
        assertThat(host.bounds.get("mixer")).isEqualTo(new Rectangle2D(50, 60, 800, 500));

        // Switch back to Tracking and verify *its* state is restored —
        // critically, panel-specific zoom/scroll come back.
        assertThat(manager.switchToName("Tracking")).isTrue();
        assertThat(host.visible.get("arrangement")).isTrue();
        assertThat(host.visible.get("mixer")).isFalse();
        assertThat(host.visible.get("browser")).isTrue();
        assertThat(host.zoom.get("arrangement")).isEqualTo(0.75);
        assertThat(host.scroll.get("arrangement")).containsExactly(200.0, 0.0);
    }

    @Test
    void switchToSlotAddressesFirstNineWorkspaces(@TempDir Path tmp) {
        var host = new StubHost();
        var manager = new WorkspaceManager(new WorkspaceStore(tmp), host); // seeds 6 defaults
        // Slots 1..6 exist, 7..9 do not.
        for (int i = 1; i <= 6; i++) {
            assertThat(manager.switchToSlot(i)).as("slot %d", i).isTrue();
        }
        assertThat(manager.switchToSlot(7)).isFalse();
        assertThat(manager.switchToSlot(0)).isFalse();
        assertThat(manager.switchToSlot(99)).isFalse();
    }

    @Test
    void exportAndImportRoundTrips(@TempDir Path tmp) throws Exception {
        var host = new StubHost();
        var manager = new WorkspaceManager(new WorkspaceStore(tmp), host, false);
        host.visible.put("arrangement", true);
        host.zoom.put("arrangement", 1.5);
        manager.saveCurrentAs("Shared");

        Path file = tmp.resolve("shared.json");
        manager.exportTo("Shared", file);

        // New, separate store / manager — simulates a different machine.
        Path otherDir = tmp.resolve("other");
        var otherManager = new WorkspaceManager(new WorkspaceStore(otherDir), host, false);
        Workspace imported = otherManager.importFrom(file);
        assertThat(imported.name()).isEqualTo("Shared");
        assertThat(otherManager.findByName("Shared")).isPresent();
    }

    @Test
    void applyClosesDialogsBeforeReopening(@TempDir Path tmp) {
        var host = new StubHost();
        host.openDialogs.add("preferences");
        var manager = new WorkspaceManager(new WorkspaceStore(tmp), host, false);

        Workspace ws = new Workspace("With Dialog",
                Map.of("arrangement", new PanelState(true, 1.0, 0, 0)),
                List.of("audio-settings"),
                Map.of());
        manager.apply(ws);

        assertThat(host.closeAllDialogsCalls).isEqualTo(1);
        assertThat(host.openDialogs).containsExactly("audio-settings");
    }

    @Test
    void deleteRemovesAndRefreshes(@TempDir Path tmp) {
        var host = new StubHost();
        var manager = new WorkspaceManager(new WorkspaceStore(tmp), host, false);
        manager.saveCurrentAs("Throwaway");
        assertThat(manager.findByName("Throwaway")).isPresent();
        assertThat(manager.delete("Throwaway")).isTrue();
        assertThat(manager.findByName("Throwaway")).isEmpty();
    }

    @Test
    void unknownPanelIdsInStoredWorkspaceAreIgnored(@TempDir Path tmp) {
        var host = new StubHost();
        var manager = new WorkspaceManager(new WorkspaceStore(tmp), host, false);
        // Workspace references a panel the host doesn't know about — must not throw.
        Workspace ws = new Workspace("Future",
                Map.of("future-panel", PanelState.DEFAULT,
                        "mixer", new PanelState(true, 1.0, 0, 0)),
                List.of(), Map.of());
        manager.apply(ws);
        assertThat(host.visible.get("mixer")).isTrue();
        assertThat(host.visible).doesNotContainKey("future-panel");
    }
}
