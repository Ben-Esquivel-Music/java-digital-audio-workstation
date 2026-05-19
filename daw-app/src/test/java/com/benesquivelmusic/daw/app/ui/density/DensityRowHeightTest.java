package com.benesquivelmusic.daw.app.ui.density;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Story 278 — the load-bearing root → Java-skin bridge probe. A
 * {@link TrackStrip} computes its row height in {@code TrackStripSkin}
 * from the density class on the <em>scene root</em> (JavaFX style classes
 * do not inherit to descendants), resolved via the single shared
 * {@link DensityMode#resolveFor}.
 *
 * <p>This test asserts <strong>all three</strong> densities explicitly:
 * {@code COMPACT} → 24&nbsp;px and {@code TOUCH} → 32&nbsp;px both differ
 * from the 28&nbsp;px Comfortable default, so a broken bridge that
 * silently falls back to 28 is caught — not just the default value. It
 * also exercises a <em>live</em> switch (no rebuild, no re-applyTo) so
 * the skin's density listener / re-measure path is covered. Keep this
 * test permanently — it guards the contract.</p>
 *
 * <p>The skin reaches the global density via
 * {@code DensityManager.getDefault()}; the test installs an isolated
 * instance through {@link DensityManager#setDefaultForTest} so the
 * resolver and the test drive the same density.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class DensityRowHeightTest {

    @Test
    void trackStripRowHeightFollowsRootDensityForAllThreeModes() throws Exception {
        Preferences node = Preferences.userRoot()
                .node("densityRowHeight_" + System.nanoTime());
        DensityManager mgr = new DensityManager(node);
        DensityManager.setDefaultForTest(mgr);
        try {
            onFxThread(() -> {
                TrackStrip strip = TrackStrip.create()
                        .trackIndex(1).name("Drums").build();

                BorderPane root = new BorderPane(strip);
                root.getStyleClass().add("root-pane");
                Scene scene = new Scene(root, 400, 200);
                mgr.applyTo(scene);

                // COMPACT → 24 (differs from the 28 default).
                mgr.setActiveDensity(DensityMode.COMPACT);
                root.applyCss();
                root.layout();
                assertThat(strip.prefHeight(-1))
                        .as("COMPACT root density must drive a 24 px TrackStrip")
                        .isCloseTo(24.0, offset(1.0));

                // COMFORTABLE → 28 (the default).
                mgr.setActiveDensity(DensityMode.COMFORTABLE);
                root.applyCss();
                root.layout();
                assertThat(strip.prefHeight(-1))
                        .as("COMFORTABLE root density must drive a 28 px TrackStrip")
                        .isCloseTo(28.0, offset(1.0));

                // TOUCH → 32 (differs from the 28 default — the second
                // non-default probe that catches a broken bridge).
                mgr.setActiveDensity(DensityMode.TOUCH);
                root.applyCss();
                root.layout();
                assertThat(strip.prefHeight(-1))
                        .as("TOUCH root density must drive a 32 px TrackStrip")
                        .isCloseTo(32.0, offset(1.0));
                return null;
            });
        } finally {
            DensityManager.setDefaultForTest(null);
            removeQuietly(node);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static <T> T onFxThread(Supplier<T> supplier) throws Exception {
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
            throw new IllegalStateException("FX thread did not complete within 5 seconds");
        }
        if (err.get() != null) {
            throw new AssertionError("FX thread action failed", err.get());
        }
        return ref.get();
    }

    private static void removeQuietly(Preferences node) {
        try {
            node.removeNode();
        } catch (BackingStoreException ignored) {
            // Best-effort cleanup; must not mask test results.
        }
    }
}
