package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dock.DockLayout;
import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 287 (ownership caveat) — the {@code Dockable} registered for
 * {@code PANEL_TELEMETRY} must be the <em>same</em> {@link TelemetrySetupPanel}
 * instance that {@link TelemetryView#getSetupPanel()} returns, proving no
 * duplicate panel is constructed for the dock (Decision 1 — "share one
 * eager instance"). A duplicate would silently diverge from
 * {@code TelemetryView}'s setup⇄display state machine.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class TelemetrySetupPanelSingleInstanceTest {

    private static final class SilentHost implements DockManager.Host {
        @Override public void onLayoutChanged(DockLayout newLayout) { /* no-op */ }
    }

    @Test
    void registeredTelemetryDockableIsTelemetryViewsOwnSetupPanel(@TempDir Path tmp) throws Exception {
        Object[] built = onFx(() -> {
            DockManager dm = new DockManager(new SilentHost(),
                    new FloatingWindowStore(tmp.resolve("f.json")));
            TelemetryView view = new TelemetryView();
            dm.register(view.getSetupPanel());
            return new Object[]{dm, view};
        });
        DockManager dm = (DockManager) built[0];
        TelemetryView view = (TelemetryView) built[1];

        assertThat(dm.panel(DefaultWorkspaces.PANEL_TELEMETRY))
                .as("a Dockable must be registered for PANEL_TELEMETRY")
                .isPresent();
        // Identity, not equality — the dock host must hold the very instance
        // TelemetryView owns, so the two never diverge.
        assertThat(dm.panel(DefaultWorkspaces.PANEL_TELEMETRY).get())
                .isSameAs(view.getSetupPanel());
    }

    @Test
    void telemetrySetupPanelAdvertisesTelemetryContract(@TempDir Path tmp) throws Exception {
        TelemetrySetupPanel panel = onFx(TelemetrySetupPanel::new);
        Dockable dockable = panel;
        assertThat(dockable.dockId()).isEqualTo(DefaultWorkspaces.PANEL_TELEMETRY);
        assertThat(dockable.displayName()).isEqualTo("Telemetry");
        assertThat(dockable.preferredZone()).isEqualTo(DockZone.RIGHT);
        assertThat(dockable.iconName()).isEqualTo("SURROUND");
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
