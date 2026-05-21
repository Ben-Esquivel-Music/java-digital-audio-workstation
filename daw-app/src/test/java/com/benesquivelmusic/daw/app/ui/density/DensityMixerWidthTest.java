package com.benesquivelmusic.daw.app.ui.density;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;

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
 * Story 278 — the mixer-width half of the root → Java-skin bridge.
 * {@code MixerChannelStripSkin.widthForDensity()} resolves the density
 * from the scene root via {@link DensityMode#resolveFor} and returns
 * 72&nbsp;px for {@code COMPACT} and 88&nbsp;px for {@code COMFORTABLE}
 * (story §5.4). Story §5.4 only defines those two values for the mixer;
 * TOUCH is unspecified and deliberately maps to 88 — this test
 * intentionally only asserts Compact=72 and Comfortable=88 so it does
 * not over-fit the unspecified TOUCH mapping.
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class DensityMixerWidthTest {

    @Test
    void mixerStripWidthFollowsRootDensity() throws Exception {
        Preferences node = Preferences.userRoot()
                .node("densityMixerWidth_" + System.nanoTime());
        DensityManager mgr = new DensityManager(node);
        DensityManager.setDefaultForTest(mgr);
        try {
            onFxThread(() -> {
                MixerChannelStrip strip = MixerChannelStrip.create()
                        .name("Drums").build();

                BorderPane root = new BorderPane(strip);
                root.getStyleClass().add("root-pane");
                Scene scene = new Scene(root, 400, 500);
                mgr.applyTo(scene);

                mgr.setActiveDensity(DensityMode.COMPACT);
                root.applyCss();
                root.layout();
                assertThat(strip.prefWidth(-1))
                        .as("COMPACT root density must drive a 72 px mixer strip")
                        .isCloseTo(72.0, offset(1.0));

                mgr.setActiveDensity(DensityMode.COMFORTABLE);
                root.applyCss();
                root.layout();
                assertThat(strip.prefWidth(-1))
                        .as("COMFORTABLE root density must drive an 88 px mixer strip")
                        .isCloseTo(88.0, offset(1.0));
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
