package com.benesquivelmusic.daw.app.ui.theme;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;

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
 * Story 277 — proof that the Atelier overlay produces a genuinely
 * <em>light</em> UI. Applies the Atelier theme via {@link ThemeManager}
 * to a {@code .root-pane} scene (the {@code main-view.fxml} root anchor;
 * see {@code TokenResolutionSmokeTest} for why the full FXML is not
 * loaded), runs a CSS pass, and asserts the resolved root background —
 * {@code .root-pane { -fx-background-color: -surface-bg; }} — has every
 * RGB channel above {@code 240/255}.
 *
 * <p>This is a deliberately blunt canary: a contributor who adds a new
 * surface token to the Palette-A block but forgets to override it in
 * {@code atelier.css} would leave that surface dark; the
 * {@code -surface-bg} root is the single most visible regression point
 * and it must read as Atelier's {@code #F4F4F0} warm white.</p>
 *
 * <p>FX-harness pitfalls honoured: a real {@link Scene}, {@code
 * applyCss()} + {@code layout()} before reading resolved values, and any
 * failure captured and rethrown on the test thread.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class ThemeLightnessTest {

    /** 240/255 ≈ 0.941 — "light" threshold from the story's AC. */
    private static final double LIGHT_THRESHOLD = 240.0 / 255.0;

    @Test
    void atelierRootBackgroundIsLight() throws Exception {
        Preferences node = newPrefsNode();
        try {
        ThemeManager manager = newManager(node, ThemeManager.Theme.ATELIER);

        Color rootFill = onFxThread(() -> {
            BorderPane rootPane = new BorderPane();
            rootPane.getStyleClass().add("root-pane");
            Scene scene = new Scene(rootPane, 100, 100);
            manager.applyTo(scene);
            assertThat(scene.getStylesheets())
                    .as("Atelier must install base + atelier overlay")
                    .hasSize(2);
            rootPane.applyCss();
            rootPane.layout();
            Paint fill = rootPane.getBackground().getFills().get(0).getFill();
            assertThat(fill)
                    .as(".root-pane background must resolve to a Color")
                    .isInstanceOf(Color.class);
            return (Color) fill;
        });

        assertThat(rootFill.getRed())
                .as("Atelier root background red channel must be light (> 240/255)")
                .isGreaterThan(LIGHT_THRESHOLD);
        assertThat(rootFill.getGreen())
                .as("Atelier root background green channel must be light (> 240/255)")
                .isGreaterThan(LIGHT_THRESHOLD);
        assertThat(rootFill.getBlue())
                .as("Atelier root background blue channel must be light (> 240/255)")
                .isGreaterThan(LIGHT_THRESHOLD);
        } finally {
            removeQuietly(node);
        }
    }

    @Test
    void onyxRefinedRootBackgroundStaysDark() throws Exception {
        // Negative control: the baseline theme must remain the near-black
        // Palette-A surface — proves the lightness assertion above is the
        // overlay's doing, not an artefact of the probe.
        Preferences node = newPrefsNode();
        try {
        ThemeManager manager = newManager(node, ThemeManager.Theme.ONYX_REFINED);

        Color rootFill = onFxThread(() -> {
            BorderPane rootPane = new BorderPane();
            rootPane.getStyleClass().add("root-pane");
            Scene scene = new Scene(rootPane, 100, 100);
            manager.applyTo(scene);
            rootPane.applyCss();
            rootPane.layout();
            return (Color) rootPane.getBackground().getFills().get(0).getFill();
        });

        assertThat(rootFill.getRed())
                .as("Onyx Refined root background must stay dark (Palette A #0B0B0E)")
                .isLessThan(0.1);
        } finally {
            removeQuietly(node);
        }
    }

    private static Preferences newPrefsNode() {
        return Preferences.userRoot()
                .node("themeLightnessTest_" + System.nanoTime());
    }

    private static ThemeManager newManager(Preferences node, ThemeManager.Theme theme) {
        ThemeManager m = new ThemeManager(node);
        m.setActiveTheme(theme);
        return m;
    }

    private static void removeQuietly(Preferences node) {
        try {
            node.removeNode();
        } catch (BackingStoreException ignored) {
            // Best-effort cleanup; a failure here only leaves an empty
            // user-prefs node behind and must not mask test results.
        }
    }

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
}
