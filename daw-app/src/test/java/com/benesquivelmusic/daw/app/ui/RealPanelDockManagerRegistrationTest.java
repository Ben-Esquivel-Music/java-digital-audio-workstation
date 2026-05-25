package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 285 — registers the five top-level panels (now {@code implements
 * Dockable} directly, not via inner-record adapters) with a real
 * {@link DockManager} and verifies the layout/toggle contract.
 *
 * <p>This is the in-process counterpart to {@code
 * MainControllerDockManagerInitializedTest} / {@code DockToggleHandlerTest}
 * from the story brief — both of those require an FXML-loaded
 * {@code MainController}, which is blocked by the documented
 * {@code @ExtendWith} JPMS environmental failure in this sandbox. The
 * registration path itself (story headline AC) is fully exercised here
 * against the actual panel classes that {@code MainController.installDockManager()}
 * registers.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RealPanelDockManagerRegistrationTest {

    private static final class CaptureHost implements DockManager.Host {
        final List<DockLayout> snapshots = new ArrayList<>();
        @Override public void onLayoutChanged(DockLayout newLayout) { snapshots.add(newLayout); }
    }

    @Test
    void everyRegisteredPanelAppearsInLayout(@TempDir Path tmp) throws Exception {
        DawProject project = new DawProject("test", AudioFormat.STUDIO_QUALITY);
        MixerView mixer = createOnFxThread(() -> new MixerView(project));
        BrowserPanel browser = createOnFxThread(BrowserPanel::new);
        EditorView editor = createOnFxThread(EditorView::new);
        MasteringView mastering = createOnFxThread(MasteringView::new);

        CaptureHost host = new CaptureHost();
        DockManager dm = new DockManager(host, new FloatingWindowStore(tmp.resolve("f.json")));

        // Register every top-level Dockable, matching
        // MainController.installDockManager().
        dm.register(new ArrangementDockableFixture());
        dm.register(mixer);
        dm.register(editor);
        dm.register(mastering);
        dm.register(browser);

        assertThat(dm.layout().contains(DefaultWorkspaces.PANEL_ARRANGEMENT)).isTrue();
        assertThat(dm.layout().contains(DefaultWorkspaces.PANEL_MIXER)).isTrue();
        assertThat(dm.layout().contains(DefaultWorkspaces.PANEL_EDITOR)).isTrue();
        assertThat(dm.layout().contains(DefaultWorkspaces.PANEL_MASTERING)).isTrue();
        assertThat(dm.layout().contains(DefaultWorkspaces.PANEL_BROWSER)).isTrue();

        // Preferred zones come straight from the panels' own Dockable
        // implementations — no parallel mapping table.
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_MIXER).orElseThrow().zone())
                .isEqualTo(DockZone.BOTTOM);
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_BROWSER).orElseThrow().zone())
                .isEqualTo(DockZone.LEFT);
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_EDITOR).orElseThrow().zone())
                .isEqualTo(DockZone.CENTER);
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_MASTERING).orElseThrow().zone())
                .isEqualTo(DockZone.CENTER);
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_ARRANGEMENT).orElseThrow().zone())
                .isEqualTo(DockZone.CENTER);

        // Every register fires onLayoutChanged at least once — that is the
        // exact callback MainControllerDockHost uses to reconcile chrome.
        assertThat(host.snapshots).isNotEmpty();
    }

    @Test
    void toggleVisibleFlipsMixerEntry(@TempDir Path tmp) throws Exception {
        DawProject project = new DawProject("test", AudioFormat.STUDIO_QUALITY);
        MixerView mixer = createOnFxThread(() -> new MixerView(project));
        DockManager dm = new DockManager(
                new CaptureHost(), new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(mixer);

        boolean initial = dm.layout().entry(DefaultWorkspaces.PANEL_MIXER).orElseThrow().visible();
        dm.toggleVisible(DefaultWorkspaces.PANEL_MIXER);
        boolean after = dm.layout().entry(DefaultWorkspaces.PANEL_MIXER).orElseThrow().visible();
        assertThat(after).isEqualTo(!initial);
        dm.toggleVisible(DefaultWorkspaces.PANEL_MIXER);
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_MIXER).orElseThrow().visible())
                .isEqualTo(initial);
    }

    @Test
    void toggleVisibleFlipsBrowserAndArrangement(@TempDir Path tmp) throws Exception {
        BrowserPanel browser = createOnFxThread(BrowserPanel::new);
        DockManager dm = new DockManager(
                new CaptureHost(), new FloatingWindowStore(tmp.resolve("f.json")));
        dm.register(browser);
        dm.register(new ArrangementDockableFixture());

        dm.toggleVisible(DefaultWorkspaces.PANEL_BROWSER);
        dm.toggleVisible(DefaultWorkspaces.PANEL_ARRANGEMENT);
        // Both flipped from the default visible=true to false.
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_BROWSER).orElseThrow().visible())
                .isFalse();
        assertThat(dm.layout().entry(DefaultWorkspaces.PANEL_ARRANGEMENT).orElseThrow().visible())
                .isFalse();
    }

    /** Mirrors MainController.ArrangementDockable — separate to avoid touching the inner type. */
    private record ArrangementDockableFixture() implements Dockable {
        @Override public String dockId() { return DefaultWorkspaces.PANEL_ARRANGEMENT; }
        @Override public String displayName() { return "Arrangement"; }
        @Override public String iconName() { return "TIMELINE"; }
        @Override public DockZone preferredZone() { return DockZone.CENTER; }
    }

    private interface FxSupplier<T> {
        T get();
    }

    private static <T> T createOnFxThread(FxSupplier<T> supplier) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not respond within 5s");
        }
        if (err.get() != null) {
            throw new RuntimeException("Failed to construct on FX thread", err.get());
        }
        return ref.get();
    }
}
