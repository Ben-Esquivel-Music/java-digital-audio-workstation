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
 * Story 282 acceptance criterion ({@code DockManifestTest}): assert the
 * dock manifest at the bottom enumerates every currently-active panel;
 * close one panel, assert the manifest updates.
 *
 * <p>Tests the view-model layer only ({@link DockManifestModel}); the
 * actual JavaFX bar that renders the entries is a thin
 * {@code ListView<Entry>} consumer that the FX integration covers.</p>
 */
class DockManifestTest {

    private record TestPanel(String dockId, String displayName, DockZone preferredZone)
            implements Dockable {
        @Override public String iconName() { return "ICON_" + dockId; }
    }

    private static final class SilentHost implements DockManager.Host {
        @Override public void onLayoutChanged(DockLayout newLayout) { /* no-op */ }
    }

    @Test
    void manifestEnumeratesEveryRegisteredPanel(@TempDir Path tmp) {
        DockManager dm = new DockManager(new SilentHost(),
                new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("arrangement", "Arrangement", DockZone.CENTER));
        dm.register(new TestPanel("mixer",       "Mixer",       DockZone.BOTTOM));
        dm.register(new TestPanel("browser",     "Browser",     DockZone.LEFT));

        DockManifestModel manifest = new DockManifestModel(dm);
        assertThat(manifest.entries())
                .extracting(DockManifestModel.Entry::panelId)
                .containsExactlyInAnyOrder("arrangement", "mixer", "browser");
        assertThat(manifest.entries())
                .allMatch(DockManifestModel.Entry::visible);
        // Display name flows through from the Dockable.
        assertThat(manifest.entry("mixer")).get()
                .extracting(DockManifestModel.Entry::displayName)
                .isEqualTo("Mixer");

        // Close (hide) one panel — manifest entry must flip to invisible.
        dm.setVisible("mixer", false);
        assertThat(manifest.entry("mixer")).get()
                .extracting(DockManifestModel.Entry::visible)
                .isEqualTo(false);
        // Other panels untouched.
        assertThat(manifest.entry("arrangement")).get()
                .extracting(DockManifestModel.Entry::visible)
                .isEqualTo(true);
    }

    @Test
    void focusPanelMakesItVisibleAgain(@TempDir Path tmp) {
        DockManager dm = new DockManager(new SilentHost(),
                new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("inspector", "Inspector", DockZone.RIGHT));
        DockManifestModel manifest = new DockManifestModel(dm);
        dm.setVisible("inspector", false);
        assertThat(manifest.entry("inspector").get().visible()).isFalse();

        manifest.focusPanel("inspector");
        assertThat(manifest.entry("inspector").get().visible()).isTrue();
    }

    @Test
    void disposeStopsTrackingDockChanges(@TempDir Path tmp) {
        DockManager dm = new DockManager(new SilentHost(),
                new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("a", "A", DockZone.CENTER));
        DockManifestModel manifest = new DockManifestModel(dm);
        manifest.dispose();

        // After dispose, subsequent dock changes must not mutate the
        // manifest's observable list (no listener leaks).
        int snapshotSize = manifest.entries().size();
        dm.register(new TestPanel("b", "B", DockZone.RIGHT));
        assertThat(manifest.entries()).hasSize(snapshotSize);
    }
}
