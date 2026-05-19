package com.benesquivelmusic.daw.app.ui.theme;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
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
import static org.assertj.core.api.Assertions.offset;

/**
 * Story 277 — proof that the Studio Slate overlay re-themes the token
 * cascade. Builds a {@code .root-pane} scene (the same anchor
 * {@code main-view.fxml} declares — see {@code TokenResolutionSmokeTest}
 * for why the full FXML is intentionally not loaded: it transitively
 * spins up an {@code AudioEngine}), has {@link ThemeManager} apply the
 * Studio Slate overlay, runs a CSS pass, and asserts the resolved
 * {@code -accent} lookup is Palette B's warm orange {@code #FF7A45} and
 * <em>not</em> Palette A's indigo {@code #7C8CFF}.
 *
 * <p>This exercises the overlay-cascade path exactly (the singleton
 * wiring through {@code ThemeManager.getDefault()} is deliberately not
 * shared across the test fork): {@code ThemeManager} builds the ordered
 * {@code [styles.css, studio-slate.css]} list and the overlay (loaded
 * last) wins the JavaFX lookup-colour cascade. If any
 * Phase-1/2 control hard-coded a colour instead of consuming a token it
 * would not re-theme — that regression is caught structurally by
 * {@code LegacyHardcodedColorAuditTest}; this test proves the cascade
 * mechanism itself works.</p>
 *
 * <p>FX-harness pitfalls honoured: a real {@link Scene}, {@code
 * applyCss()} + {@code layout()} before reading resolved values, and all
 * assertions/exceptions captured into an {@link AtomicReference} and
 * rethrown on the test thread (assertions thrown inside a {@code
 * runLater} runnable are otherwise swallowed).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class ThemeSwitchSmokeTest {

    private static final Color PALETTE_A_ACCENT = Color.web("#7C8CFF");
    private static final Color PALETTE_B_ACCENT = Color.web("#FF7A45");

    @Test
    void studioSlateOverlayRethemesTheAccentToken() throws Exception {
        Preferences node = newPrefsNode();
        try {
        ThemeManager manager = newManager(node, ThemeManager.Theme.STUDIO_SLATE);

        Color accent = onFxThread(() -> {
            BorderPane rootPane = new BorderPane();
            rootPane.getStyleClass().add("root-pane");

            Region accentProbe = new Region();
            accentProbe.setStyle("-fx-background-color: -accent;");
            rootPane.getChildren().add(accentProbe);

            Scene scene = new Scene(rootPane, 100, 100);
            // ThemeManager owns the ordered stylesheet list; applying it
            // to the scene installs [styles.css, studio-slate.css].
            manager.applyTo(scene);
            assertThat(scene.getStylesheets())
                    .as("ThemeManager must install base + overlay, overlay last")
                    .hasSize(2);

            rootPane.applyCss();
            rootPane.layout();

            Paint fill = accentProbe.getBackground().getFills().get(0).getFill();
            assertThat(fill)
                    .as("-accent must resolve to a Color")
                    .isInstanceOf(Color.class);
            return (Color) fill;
        });

        assertCloseTo(accent, PALETTE_B_ACCENT, "Studio Slate -accent must be #FF7A45");
        assertThat(distance(accent, PALETTE_A_ACCENT))
                .as("Studio Slate -accent must NOT still be Palette A's #7C8CFF")
                .isGreaterThan(0.1);
        } finally {
            removeQuietly(node);
        }
    }

    @Test
    void baselineThemeKeepsPaletteAAccent() throws Exception {
        Preferences node = newPrefsNode();
        try {
        ThemeManager manager = newManager(node, ThemeManager.Theme.ONYX_REFINED);

        Color accent = onFxThread(() -> {
            BorderPane rootPane = new BorderPane();
            rootPane.getStyleClass().add("root-pane");
            Region probe = new Region();
            probe.setStyle("-fx-background-color: -accent;");
            rootPane.getChildren().add(probe);

            Scene scene = new Scene(rootPane, 100, 100);
            manager.applyTo(scene);
            assertThat(scene.getStylesheets())
                    .as("ONYX_REFINED is the baseline — only styles.css, no overlay")
                    .hasSize(1);
            rootPane.applyCss();
            rootPane.layout();
            return (Color) probe.getBackground().getFills().get(0).getFill();
        });

        assertCloseTo(accent, PALETTE_A_ACCENT, "baseline -accent must be #7C8CFF");
        } finally {
            removeQuietly(node);
        }
    }

    /** Resolved {@code -accent} and stylesheet count before / after a live switch. */
    private record Switch(Color before, Color after,
                           int sheetsBefore, int sheetsAfter) { }

    /**
     * Story 277's headline acceptance criterion: changing the theme
     * re-themes an <em>already-registered</em> scene with no restart and
     * no second {@code applyTo} call. Registers a scene under Onyx
     * Refined, asserts the baseline indigo, then flips the active theme
     * via {@link ThemeManager#setActiveTheme} only — the registered
     * scene must pick up Studio Slate's orange purely through
     * {@code reapplyAll()}. Guards the remove-then-append idempotency of
     * {@code applyOrderedSheets} (no overlay accumulation).
     */
    @Test
    void changingThemeRethemesAnAlreadyRegisteredSceneWithNoRestart()
            throws Exception {
        Preferences node = newPrefsNode();
        try {
        ThemeManager manager = newManager(node, ThemeManager.Theme.ONYX_REFINED);

        Switch result = onFxThread(() -> {
            BorderPane rootPane = new BorderPane();
            rootPane.getStyleClass().add("root-pane");
            Region accentProbe = new Region();
            accentProbe.setStyle("-fx-background-color: -accent;");
            rootPane.getChildren().add(accentProbe);

            Scene scene = new Scene(rootPane, 100, 100);
            manager.applyTo(scene); // registered once, under Onyx Refined
            rootPane.applyCss();
            rootPane.layout();
            Color before =
                    (Color) accentProbe.getBackground().getFills().get(0).getFill();
            int sheetsBefore = scene.getStylesheets().size();

            // The whole no-restart contract: NO second applyTo — just
            // flip the property. The listener -> reapplyAll() runs
            // synchronously here (already on the FX thread).
            manager.setActiveTheme(ThemeManager.Theme.STUDIO_SLATE);
            rootPane.applyCss();
            rootPane.layout();
            Color after =
                    (Color) accentProbe.getBackground().getFills().get(0).getFill();
            int sheetsAfter = scene.getStylesheets().size();

            return new Switch(before, after, sheetsBefore, sheetsAfter);
        });

        assertCloseTo(result.before(), PALETTE_A_ACCENT,
                "before the switch -accent must be Onyx Refined #7C8CFF");
        assertThat(result.sheetsBefore())
                .as("baseline registers only styles.css (no overlay)")
                .isEqualTo(1);

        assertCloseTo(result.after(), PALETTE_B_ACCENT,
                "after setActiveTheme the registered scene must re-theme "
                        + "to Studio Slate #FF7A45 with no restart / no re-applyTo");
        assertThat(result.sheetsAfter())
                .as("the overlay is appended (not accumulated): "
                        + "exactly [styles.css, studio-slate.css]")
                .isEqualTo(2);
        assertThat(distance(result.after(), result.before()))
                .as("the live switch must actually change the resolved accent")
                .isGreaterThan(0.1);
        } finally {
            removeQuietly(node);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Preferences newPrefsNode() {
        return Preferences.userRoot()
                .node("themeSwitchSmokeTest_" + System.nanoTime());
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

    private static void assertCloseTo(Color got, Color want, String why) {
        assertThat(got.getRed()).as(why + " (red)").isCloseTo(want.getRed(), offset(0.01));
        assertThat(got.getGreen()).as(why + " (green)").isCloseTo(want.getGreen(), offset(0.01));
        assertThat(got.getBlue()).as(why + " (blue)").isCloseTo(want.getBlue(), offset(0.01));
    }

    private static double distance(Color a, Color b) {
        double dr = a.getRed() - b.getRed();
        double dg = a.getGreen() - b.getGreen();
        double db = a.getBlue() - b.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db);
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
