package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DockManager}. The manager is intentionally
 * UI-toolkit-agnostic — these tests use a recording {@link
 * DockManager.Host} stub and require no JavaFX screen.
 */
class DockManagerTest {

    /** Minimal {@link Dockable} fixture. */
    private record TestPanel(String dockId, String displayName, DockZone preferredZone)
            implements Dockable {

        @Override public String iconName() { return "ICON_" + dockId; }
    }

    /** Recording host that captures every layout snapshot it is told about. */
    private static final class RecordingHost implements DockManager.Host {
        final List<DockLayout> snapshots = new ArrayList<>();
        Predicate noScreenIntersect = b -> false;

        @Override
        public void onLayoutChanged(DockLayout newLayout) {
            snapshots.add(newLayout);
        }

        @Override
        public boolean isScreenAvailableFor(Rectangle2D bounds) {
            return !noScreenIntersect.test(bounds);
        }
        interface Predicate {
            boolean test(Rectangle2D bounds);
        }
    }

    @Test
    void registerPlacesPanelInPreferredZone(@TempDir Path tmp) {
        RecordingHost host = new RecordingHost();
        DockManager dm = new DockManager(host, new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("arrangement", "Arrangement", DockZone.CENTER));
        dm.register(new TestPanel("mixer", "Mixer", DockZone.BOTTOM));

        assertThat(dm.layout().entry("arrangement").get().zone()).isEqualTo(DockZone.CENTER);
        assertThat(dm.layout().entry("mixer").get().zone()).isEqualTo(DockZone.BOTTOM);
        assertThat(host.snapshots).isNotEmpty();
    }

    @Test
    void floatThenReDockProducesIdenticalBounds(@TempDir Path tmp) {
        // The story explicitly requires this round-trip property.
        RecordingHost host = new RecordingHost();
        DockManager dm = new DockManager(host, new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("mixer", "Mixer", DockZone.BOTTOM));

        Rectangle2D bounds = new Rectangle2D(120, 80, 900, 480);
        dm.float_("mixer", bounds);
        assertThat(dm.layout().entry("mixer").get().zone()).isEqualTo(DockZone.FLOATING);
        assertThat(dm.layout().entry("mixer").get().floatingBounds()).isEqualTo(bounds);

        // Re-dock and float again — bounds must come back identical
        // because they were persisted to the floating-window store.
        dm.move("mixer", DockZone.BOTTOM, 0);
        dm.float_("mixer", null);
        assertThat(dm.layout().entry("mixer").get().floatingBounds()).isEqualTo(bounds);
    }

    @Test
    void floatingBoundsPersistAcrossManagerInstances(@TempDir Path tmp) {
        Path file = tmp.resolve("f.json");
        FloatingWindowStore store = new FloatingWindowStore(file);
        RecordingHost host1 = new RecordingHost();
        DockManager first = new DockManager(host1, store);
        first.register(new TestPanel("browser", "Browser", DockZone.LEFT));

        Rectangle2D bounds = new Rectangle2D(40, 60, 320, 720);
        first.float_("browser", bounds);

        // "Restart" — fresh store + fresh manager, same on-disk file.
        FloatingWindowStore reloaded = new FloatingWindowStore(file);
        RecordingHost host2 = new RecordingHost();
        DockManager second = new DockManager(host2, reloaded);
        second.register(new TestPanel("browser", "Browser", DockZone.LEFT));
        second.float_("browser", null);

        assertThat(second.layout().entry("browser").get().floatingBounds()).isEqualTo(bounds);
    }

    @Test
    void applyJsonReDocksFloatingPanelWhenMonitorMissing(@TempDir Path tmp) {
        RecordingHost host = new RecordingHost();
        // Pretend the user's secondary monitor is gone — every floating
        // bounds reports "no screen available".
        host.noScreenIntersect = b -> true;

        DockManager dm = new DockManager(host, new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("mixer", "Mixer", DockZone.BOTTOM));

        // Build a layout that floats the mixer somewhere far away.
        DockLayout floated = DockLayout.empty()
                .withEntry(DockEntry.floating("mixer",
                        new Rectangle2D(99999, 99999, 800, 480)));
        String json = DockLayoutJson.toJson(floated);

        dm.applyJson(json);

        // Because no monitor intersects those bounds, mixer must be
        // re-docked at its preferred zone (BOTTOM).
        assertThat(dm.layout().entry("mixer").get().zone()).isEqualTo(DockZone.BOTTOM);
        assertThat(dm.layout().entry("mixer").get().floatingBounds()).isNull();
    }

    @Test
    void applyJsonAtomicallyReplacesLayout(@TempDir Path tmp) {
        // Workspace switches must apply dock state atomically — the host
        // should observe the new layout via a single onLayoutChanged
        // callback, not a stream of intermediate states.
        RecordingHost host = new RecordingHost();
        DockManager dm = new DockManager(host, new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("a", "A", DockZone.TOP));
        dm.register(new TestPanel("b", "B", DockZone.BOTTOM));
        int snapsAfterRegister = host.snapshots.size();

        DockLayout target = DockLayout.empty()
                .withEntry(DockEntry.docked("a", DockZone.LEFT, 0, true))
                .withEntry(DockEntry.docked("b", DockZone.RIGHT, 0, true));
        dm.applyLayout(target);

        // Exactly one additional notification was emitted.
        assertThat(host.snapshots).hasSize(snapsAfterRegister + 1);
        assertThat(dm.layout().entry("a").get().zone()).isEqualTo(DockZone.LEFT);
        assertThat(dm.layout().entry("b").get().zone()).isEqualTo(DockZone.RIGHT);
    }

    @Test
    void applyLayoutDropsUnknownPanelsAndRestoresMissingOnes(@TempDir Path tmp) {
        RecordingHost host = new RecordingHost();
        DockManager dm = new DockManager(host, new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("a", "A", DockZone.TOP));

        DockLayout target = DockLayout.empty()
                .withEntry(DockEntry.docked("ghost", DockZone.LEFT, 0, true));
        dm.applyLayout(target);

        // 'ghost' is unknown → dropped. 'a' was missing from target →
        // restored at its preferred zone so the user never loses a panel.
        assertThat(dm.layout().contains("ghost")).isFalse();
        assertThat(dm.layout().entry("a").get().zone()).isEqualTo(DockZone.TOP);
    }

    @Test
    void toggleVisibleFlipsState(@TempDir Path tmp) {
        RecordingHost host = new RecordingHost();
        DockManager dm = new DockManager(host, new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("mixer", "Mixer", DockZone.BOTTOM));
        assertThat(dm.layout().entry("mixer").get().visible()).isTrue();
        dm.toggleVisible("mixer");
        assertThat(dm.layout().entry("mixer").get().visible()).isFalse();
        dm.toggleVisible("mixer");
        assertThat(dm.layout().entry("mixer").get().visible()).isTrue();
    }

    @Test
    void listenerReceivesLayoutNotifications(@TempDir Path tmp) {
        RecordingHost host = new RecordingHost();
        DockManager dm = new DockManager(host, new FloatingWindowStore(tmp.resolve("f.json")));
        List<DockLayout> received = new ArrayList<>();
        Runnable unsub = dm.addListener(received::add);

        dm.register(new TestPanel("a", "A", DockZone.TOP));
        dm.move("a", DockZone.BOTTOM, 0);
        int beforeUnsub = received.size();
        unsub.run();
        dm.move("a", DockZone.TOP, 0);
        // No more notifications after unsubscribe.
        assertThat(received.size()).isEqualTo(beforeUnsub);
    }

    @Test
    void captureAndApplyJsonRoundTrip(@TempDir Path tmp) {
        RecordingHost host = new RecordingHost();
        DockManager dm = new DockManager(host, new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(new TestPanel("a", "A", DockZone.TOP));
        dm.register(new TestPanel("b", "B", DockZone.RIGHT));
        dm.move("a", DockZone.LEFT, 0);

        String json = dm.captureJson();
        // Simulate switching layouts and back.
        dm.move("a", DockZone.CENTER, 0);
        dm.applyJson(json);

        assertThat(dm.layout().entry("a").get().zone()).isEqualTo(DockZone.LEFT);
        assertThat(dm.layout().entry("b").get().zone()).isEqualTo(DockZone.RIGHT);
    }
}
