package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 281 / story 277 — the Workshop view re-themes with no code
 * change. Its CSS is purely structural: every colour resolves from a
 * role token ({@code -surface-bg}, {@code -surface-1}, {@code -line-soft},
 * {@code -text-hi}, {@code -text-mute}), so switching the
 * {@link ThemeManager} token theme from Onyx Refined (near-black surfaces)
 * to Atelier (light surfaces with a navy accent) re-tints the view
 * automatically.
 *
 * <p>Verifies all three sub-panes re-theme (the story's Theme AC): the
 * Workshop root background, the right pane's panel background, and the
 * breadcrumb segment text fill (sampled through a representative
 * breadcrumb segment, populated via {@link WorkshopView#setFocusedPlugin}
 * so a real Label exists in the bar to read a resolved fill from).</p>
 *
 * <p>FX-harness pitfalls honoured: real {@link Scene}, {@code applyCss()}
 * after each theme apply, captured-and-rethrown assertions, Stage closed
 * in try/finally.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class WorkshopThemeTest {

    @Test
    void switchingToAtelierReThemesAllThreeSubPanesWithNoCodeChange() throws Exception {
        Preferences testNode = Preferences.userRoot().node("wsTheme_" + System.nanoTime());
        ThemeManager themeManager = new ThemeManager(testNode);
        ThemeManager.setDefaultForTest(themeManager);
        try {
            onFxThread(() -> {
                WorkshopView view = newWorkshopView();
                // Push a focused plugin so the breadcrumb populates with a
                // real segment whose text-fill the theme cascade can re-tint.
                view.setFocusedPlugin(3, "Serum", new Region());

                StackPane root = new StackPane(view);
                root.getStyleClass().add("root-pane");
                Scene scene = new Scene(root, 1280, 800);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                try {
                    // Onyx Refined (default) — capture resolved fills for
                    // all three sub-panes.
                    themeManager.setActiveTheme(ThemeManager.Theme.ONYX_REFINED);
                    themeManager.applyTo(scene);
                    root.applyCss();
                    root.layout();
                    Color onyxRoot = backgroundFill(view);
                    Color onyxRight = backgroundFill(view.rightPane());
                    Color onyxBreadcrumb = firstSegmentTextFill(view);

                    // Switch to Atelier — NO code change to the view.
                    themeManager.setActiveTheme(ThemeManager.Theme.ATELIER);
                    themeManager.applyTo(scene);
                    root.applyCss();
                    root.layout();
                    Color atelierRoot = backgroundFill(view);
                    Color atelierRight = backgroundFill(view.rightPane());
                    Color atelierBreadcrumb = firstSegmentTextFill(view);

                    // All three sub-panes must report a non-null resolved
                    // fill under both themes.
                    assertThat(onyxRoot).as("Onyx workshop-view background").isNotNull();
                    assertThat(onyxRight).as("Onyx right-pane background").isNotNull();
                    assertThat(onyxBreadcrumb).as("Onyx breadcrumb segment text fill").isNotNull();
                    assertThat(atelierRoot).as("Atelier workshop-view background").isNotNull();
                    assertThat(atelierRight).as("Atelier right-pane background").isNotNull();
                    assertThat(atelierBreadcrumb).as("Atelier breadcrumb segment text fill").isNotNull();

                    // And all three must differ between themes — the token
                    // cascade reached each sub-pane.
                    assertThat(colourDistance(onyxRoot, atelierRoot))
                            .as("workshop-view background must re-tint between themes "
                                    + "(Onyx=%s, Atelier=%s) — story 277 contract",
                                    onyxRoot, atelierRoot)
                            .isGreaterThan(0.1);
                    assertThat(colourDistance(onyxRight, atelierRight))
                            .as("right-pane background must re-tint between themes "
                                    + "(Onyx=%s, Atelier=%s)",
                                    onyxRight, atelierRight)
                            .isGreaterThan(0.1);
                    assertThat(colourDistance(onyxBreadcrumb, atelierBreadcrumb))
                            .as("breadcrumb segment text fill must re-tint between themes "
                                    + "(Onyx=%s, Atelier=%s)",
                                    onyxBreadcrumb, atelierBreadcrumb)
                            .isGreaterThan(0.1);
                } finally {
                    stage.close();
                }
                return null;
            });
        } finally {
            ThemeManager.setDefaultForTest(null);
            testNode.removeNode();
        }
    }

    /** Reads the first resolved background fill colour of a region. */
    private static Color backgroundFill(Region region) {
        Background bg = region.getBackground();
        if (bg == null || bg.getFills().isEmpty()) {
            return null;
        }
        return (bg.getFills().getFirst().getFill() instanceof Color c) ? c : null;
    }

    /**
     * Returns the resolved text fill of the first breadcrumb segment
     * {@code Label}. The bar populates with labels in
     * {@link com.benesquivelmusic.daw.app.ui.controls.BreadcrumbBar#rebuild}
     * whose style class is {@code breadcrumb-segment}.
     */
    private static Color firstSegmentTextFill(WorkshopView view) {
        return view.breadcrumb().getChildren().stream()
                .filter(n -> n.getStyleClass().contains("breadcrumb-segment"))
                .findFirst()
                .map(n -> (javafx.scene.control.Label) n)
                .map(label -> (label.getTextFill() instanceof Color c) ? c : null)
                .orElse(null);
    }

    /** Simple RGB Euclidean-ish distance, normalised to roughly [0, 1.7]. */
    private static double colourDistance(Color a, Color b) {
        double dr = a.getRed() - b.getRed();
        double dg = a.getGreen() - b.getGreen();
        double db = a.getBlue() - b.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static WorkshopView newWorkshopView() {
        ResourceBundle messages = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        return new WorkshopView(messages);
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
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not complete within 15 seconds");
        }
        if (err.get() != null) {
            throw new AssertionError("FX thread action failed", err.get());
        }
        return ref.get();
    }
}
