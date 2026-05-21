package com.benesquivelmusic.daw.app.ui.density;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

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

/**
 * Story 278 — applying a density adds exactly one {@code .density-*}
 * class to the scene root, and switching density swaps it (the old class
 * is removed, not accumulated). This is the no-restart contract probe
 * for the root-scope class, mirroring {@code ThemeSwitchSmokeTest}'s
 * "overlay appended not accumulated" assertion.
 *
 * <p>FX-harness pitfalls honoured: a real {@link Scene}, work done on the
 * FX thread, and all assertions/exceptions captured into an
 * {@link AtomicReference} and rethrown on the test thread (assertions
 * thrown inside a {@code runLater} runnable are otherwise swallowed).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class DensityClassAppliedTest {

    @Test
    void applyAddsExactlyOneDensityClassAndSwitchSwapsIt() throws Exception {
        Preferences node = Preferences.userRoot()
                .node("densityClassApplied_" + System.nanoTime());
        try {
            onFxThread(() -> {
                DensityManager mgr = new DensityManager(node);
                mgr.setActiveDensity(DensityMode.COMPACT);

                BorderPane root = new BorderPane();
                Scene scene = new Scene(root, 100, 100);
                mgr.applyTo(scene);

                assertThat(root.getStyleClass())
                        .as("COMPACT must add density-compact and neither other")
                        .contains("density-compact")
                        .doesNotContain("density-comfortable", "density-touch");

                // Live switch — NO second applyTo, just flip the property.
                mgr.setActiveDensity(DensityMode.TOUCH);
                assertThat(root.getStyleClass())
                        .as("switching to TOUCH must swap the class, not accumulate")
                        .contains("density-touch")
                        .doesNotContain("density-compact", "density-comfortable");
                assertThat(root.getStyleClass()
                                .stream()
                                .filter(c -> c.startsWith("density-"))
                                .count())
                        .as("exactly one density-* class is present at any time")
                        .isEqualTo(1);
                return null;
            });
        } finally {
            removeQuietly(node);
        }
    }

    // ── helpers (capture + rethrow — assertions in runLater are swallowed) ──

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
