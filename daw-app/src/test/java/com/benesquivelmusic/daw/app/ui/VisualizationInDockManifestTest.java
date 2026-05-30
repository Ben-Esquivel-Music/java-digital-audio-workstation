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
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.app.ui.layout.DockManifestModel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 287 — each visualization analyzer panel appears in the dock
 * manifest with the display name from the panel table.
 *
 * <p>This asserts the {@link DockManifestModel} (the view-model the
 * bottom manifest bar binds to) rather than rendering the private HBox
 * bar — per {@code feedback_javafx_headless_test_pitfalls.md}, testing
 * the model avoids the rasterization pitfalls of the live bar, and
 * {@code DockManifestModel} is fed directly by {@code DockManager.registered()}
 * so a manifest entry per registered panel is exactly what the rendered
 * bar shows (one {@code dawg-button} per entry, text = {@code displayName()}).
 * The real {@code Dockable} instances are built on the FX thread so the
 * display names are the production values.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class VisualizationInDockManifestTest {

    private record RoomTelemetryDockable() implements Dockable {
        @Override public String dockId()          { return DefaultWorkspaces.PANEL_ROOM_3D; }
        @Override public String displayName()     { return "Room 3D"; }
        @Override public String iconName()        { return "SURROUND"; }
        @Override public DockZone preferredZone() { return DockZone.RIGHT; }
    }

    private static final class SilentHost implements DockManager.Host {
        @Override public void onLayoutChanged(DockLayout newLayout) { /* no-op */ }
    }

    @Test
    void everyVisualizationPanelHasAManifestEntryWithItsDisplayName(@TempDir Path tmp) throws Exception {
        DockManifestModel manifest = onFx(() -> {
            DockManager dm = new DockManager(new SilentHost(),
                    new FloatingWindowStore(tmp.resolve("f.json")));
            dm.register(new DockableVisualizationPanel(DefaultWorkspaces.PANEL_SPECTRUM,
                    "Spectrum", "SPECTRUM", DockZone.BOTTOM, DawIcon.SPECTRUM,
                    "tile-header-accent-green", new SpectrumDisplay()));
            dm.register(new DockableVisualizationPanel(DefaultWorkspaces.PANEL_LEVELS,
                    "Peak / RMS", "PEAK", DockZone.BOTTOM, DawIcon.PEAK,
                    "tile-header-accent-orange", new LevelMeterDisplay()));
            dm.register(new DockableVisualizationPanel(DefaultWorkspaces.PANEL_WAVEFORM,
                    "Oscilloscope", "OSCILLOSCOPE", DockZone.BOTTOM, DawIcon.OSCILLOSCOPE,
                    "tile-header-accent-cyan", new WaveformDisplay()));
            dm.register(new DockableVisualizationPanel(DefaultWorkspaces.PANEL_CORRELATION,
                    "Correlation", "PHASE_METER", DockZone.BOTTOM, DawIcon.PHASE_METER,
                    "tile-header-accent-red", new CorrelationDisplay()));
            dm.register(new DockableVisualizationPanel(DefaultWorkspaces.PANEL_LOUDNESS,
                    "Loudness", "LOUDNESS_METER", DockZone.BOTTOM, DawIcon.LOUDNESS_METER,
                    "tile-header-accent-purple", new LoudnessDisplay()));
            dm.register(new DockableVisualizationPanel(DefaultWorkspaces.PANEL_TUNER,
                    "Tuner", "MUSIC_NOTE", DockZone.BOTTOM, DawIcon.MUSIC_NOTE,
                    "tile-header-accent-green", new TunerDisplay()));
            TelemetryView telemetry = new TelemetryView();
            dm.register(telemetry.getSetupPanel());
            dm.register(new RoomTelemetryDockable());
            return new DockManifestModel(dm);
        });

        Map<String, String> expectedNames = Map.of(
                DefaultWorkspaces.PANEL_SPECTRUM,    "Spectrum",
                DefaultWorkspaces.PANEL_LEVELS,      "Peak / RMS",
                DefaultWorkspaces.PANEL_WAVEFORM,    "Oscilloscope",
                DefaultWorkspaces.PANEL_CORRELATION, "Correlation",
                DefaultWorkspaces.PANEL_LOUDNESS,    "Loudness",
                DefaultWorkspaces.PANEL_TUNER,       "Tuner",
                DefaultWorkspaces.PANEL_ROOM_3D,     "Room 3D",
                DefaultWorkspaces.PANEL_TELEMETRY,   "Telemetry");

        for (Map.Entry<String, String> e : expectedNames.entrySet()) {
            assertThat(manifest.entry(e.getKey()))
                    .as("manifest must contain an entry for '%s'", e.getKey())
                    .isPresent();
            assertThat(manifest.entry(e.getKey()).get().displayName())
                    .as("manifest display name for '%s'", e.getKey())
                    .isEqualTo(e.getValue());
        }
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
