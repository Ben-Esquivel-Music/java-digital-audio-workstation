package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore;
import com.benesquivelmusic.daw.app.ui.display.CorrelationDisplay;
import com.benesquivelmusic.daw.app.ui.display.DockableVisualizationPanel;
import com.benesquivelmusic.daw.app.ui.display.LevelMeterDisplay;
import com.benesquivelmusic.daw.app.ui.display.LoudnessDisplay;
import com.benesquivelmusic.daw.app.ui.display.SpectrumDisplay;
import com.benesquivelmusic.daw.app.ui.display.TunerDisplay;
import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 287 — asserts that all eight visualization analyzer panels are
 * registered as first-class {@link Dockable}s with the correct id and
 * preferred zone.
 *
 * <p>Per {@code feedback_maincontroller_test_substitute.md} this uses the
 * in-process substitution: a real {@link DockManager} with a silent
 * {@link DockManager.Host} and the real {@code Dockable} instances
 * {@code MainController.registerVisualizationDockables()} would register —
 * the six {@link DockableVisualizationPanel} analyzer adapters plus the
 * shared {@link TelemetryView}'s setup panel and a Room-3D metadata
 * dockable. {@code MainController} itself is not FXML-loaded (it hangs —
 * AudioEngine / autosave / scene listeners spin up). The displays and
 * adapters are JavaFX nodes, so they are built on the FX thread.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class VisualizationDockablesRegisteredTest {

    private static final class SilentHost implements DockManager.Host {
        @Override public void onLayoutChanged(DockLayout newLayout) { /* no-op */ }
    }

    /** Builds a manager with the real eight viz dockables registered (FX thread). */
    private DockManager buildManagerWithVizDockables(Path tmp) throws Exception {
        return onFx(() -> {
            DockManager dm = new DockManager(new SilentHost(),
                    new FloatingWindowStore(tmp.resolve("f.json")));
            // Exercise the real production registration list (ids, zones, the
            // six adapter registrations plus telemetry / Room-3D) via the
            // package-private seam, so a regression in MainController is caught
            // here instead of being masked by a re-implemented test fixture.
            MainController.registerVisualizationDockables(dm,
                    new java.util.LinkedHashMap<>(),
                    new SpectrumDisplay(), new LevelMeterDisplay(), new WaveformDisplay(),
                    new CorrelationDisplay(), new LoudnessDisplay(), new TunerDisplay(),
                    new TelemetryView());
            return dm;
        });
    }

    @Test
    void allEightVisualizationPanelsAreRegisteredWithExpectedZones(@TempDir Path tmp) throws Exception {
        DockManager dm = buildManagerWithVizDockables(tmp);

        Map<String, DockZone> expectedZones = Map.of(
                DefaultWorkspaces.PANEL_SPECTRUM,    DockZone.BOTTOM,
                DefaultWorkspaces.PANEL_LEVELS,      DockZone.BOTTOM,
                DefaultWorkspaces.PANEL_WAVEFORM,    DockZone.BOTTOM,
                DefaultWorkspaces.PANEL_CORRELATION, DockZone.BOTTOM,
                DefaultWorkspaces.PANEL_LOUDNESS,    DockZone.BOTTOM,
                DefaultWorkspaces.PANEL_TUNER,       DockZone.BOTTOM,
                DefaultWorkspaces.PANEL_ROOM_3D,     DockZone.RIGHT,
                DefaultWorkspaces.PANEL_TELEMETRY,   DockZone.RIGHT);

        for (Map.Entry<String, DockZone> e : expectedZones.entrySet()) {
            Optional<Dockable> panel = dm.panel(e.getKey());
            assertThat(panel)
                    .as("panel '%s' must be registered with the DockManager", e.getKey())
                    .isPresent();
            assertThat(panel.get().dockId()).isEqualTo(e.getKey());
            assertThat(panel.get().preferredZone())
                    .as("panel '%s' preferred zone", e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    void theSixBottomAnalyzersAdvertiseBottomZone(@TempDir Path tmp) throws Exception {
        DockManager dm = buildManagerWithVizDockables(tmp);
        for (String id : new String[]{
                DefaultWorkspaces.PANEL_SPECTRUM, DefaultWorkspaces.PANEL_LEVELS,
                DefaultWorkspaces.PANEL_WAVEFORM, DefaultWorkspaces.PANEL_CORRELATION,
                DefaultWorkspaces.PANEL_LOUDNESS, DefaultWorkspaces.PANEL_TUNER}) {
            assertThat(dm.panel(id)).get()
                    .extracting(Dockable::preferredZone)
                    .isEqualTo(DockZone.BOTTOM);
        }
    }

    @Test
    void telemetryAndRoom3dAdvertiseRightZone(@TempDir Path tmp) throws Exception {
        DockManager dm = buildManagerWithVizDockables(tmp);
        assertThat(dm.panel(DefaultWorkspaces.PANEL_TELEMETRY)).get()
                .extracting(Dockable::preferredZone).isEqualTo(DockZone.RIGHT);
        assertThat(dm.panel(DefaultWorkspaces.PANEL_ROOM_3D)).get()
                .extracting(Dockable::preferredZone).isEqualTo(DockZone.RIGHT);
    }

    private static <T> T onFx(Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try { ref.set(callable.call()); }
            catch (Exception e) { err.set(e); }
            finally { latch.countDown(); }
        });
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (err.get() != null) throw err.get();
        return ref.get();
    }
}
