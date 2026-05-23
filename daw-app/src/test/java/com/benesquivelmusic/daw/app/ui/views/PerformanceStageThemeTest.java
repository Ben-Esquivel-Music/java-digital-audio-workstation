package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Background;
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
 * Story 280 / story 277 — the Performance Stage re-themes with no code
 * change. The view's CSS is purely structural: every colour resolves from
 * a role token ({@code -surface-bg}, {@code -line-soft}, …), so switching
 * the {@code ThemeManager} token theme to Atelier (light surfaces, navy
 * accent) re-tints the stage automatically.
 *
 * <p>The probe is the resolved background {@link Color} of the
 * {@code .performance-stage-view} node, which is declared as
 * {@code -fx-background-color: -surface-bg}. Onyx Refined's
 * {@code -surface-bg} is near-black; Atelier's is a light surface — so a
 * non-trivial difference in the resolved fill proves the token cascade
 * reached the view without touching {@code PerformanceStageView.java}.</p>
 *
 * <p>FX-harness pitfalls honoured: real {@link Scene}, {@code applyCss()}
 * after each theme apply, captured-and-rethrown assertions.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class PerformanceStageThemeTest {

    @Test
    void switchingToAtelierReThemesTheStageWithNoCodeChange() throws Exception {
        Preferences testNode = Preferences.userRoot().node("psTheme_" + System.nanoTime());
        ThemeManager themeManager = new ThemeManager(testNode);
        ThemeManager.setDefaultForTest(themeManager);
        try {
            onFxThread(() -> {
                PerformanceStageView view = newStageView();
                StackPane root = new StackPane(view);
                root.getStyleClass().add("root-pane");
                Scene scene = new Scene(root, 1280, 800);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                try {
                    // Onyx Refined (default token theme) — capture the resolved
                    // background fill of the .performance-stage-view node.
                    themeManager.setActiveTheme(ThemeManager.Theme.ONYX_REFINED);
                    themeManager.applyTo(scene);
                    root.applyCss();
                    root.layout();
                    Color onyxFill = backgroundFill(view);

                    // Switch to Atelier — NO code change to the view.
                    themeManager.setActiveTheme(ThemeManager.Theme.ATELIER);
                    themeManager.applyTo(scene);
                    root.applyCss();
                    root.layout();
                    Color atelierFill = backgroundFill(view);

                    assertThat(onyxFill)
                            .as("the stage must have a resolved background fill under Onyx")
                            .isNotNull();
                    assertThat(atelierFill)
                            .as("the stage must have a resolved background fill under Atelier")
                            .isNotNull();
                    assertThat(colourDistance(onyxFill, atelierFill))
                            .as("switching to Atelier must re-tint the stage background "
                                    + "(Onyx=%s, Atelier=%s) — the -surface-bg token "
                                    + "cascade reaches the view with no code change",
                                    onyxFill, atelierFill)
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
    private static Color backgroundFill(PerformanceStageView view) {
        Background bg = view.getBackground();
        if (bg == null || bg.getFills().isEmpty()) {
            return null;
        }
        return (bg.getFills().getFirst().getFill() instanceof Color c) ? c : null;
    }

    /** Simple RGB Euclidean-ish distance, normalised to roughly [0, 1.7]. */
    private static double colourDistance(Color a, Color b) {
        double dr = a.getRed() - b.getRed();
        double dg = a.getGreen() - b.getGreen();
        double db = a.getBlue() - b.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static PerformanceStageView newStageView() {
        DawProject project = new DawProject("PS Theme", AudioFormat.STUDIO_QUALITY);
        project.addTrack(new Track("Track 1", TrackType.AUDIO));
        ResourceBundle messages = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        return new PerformanceStageView(project, messages, new InertHost());
    }

    private static final class InertHost implements PerformanceStageView.Host {
        @Override public void onPlay() { }
        @Override public void onStop() { }
        @Override public void onRecord() { }
        @Override public void onToggleLoop() { }
        @Override public void onExitPerformanceStage() { }
        @Override public void onOpenAudioSettings() { }
        @Override public void onNewProject() { }
        @Override public void onOpenProject() { }
        @Override public void onSaveProject() { }
        @Override public void onRecentProjects() { }
    }

    // ── FX helper (capture + rethrow — swallowed-assertion pitfall) ───────

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
