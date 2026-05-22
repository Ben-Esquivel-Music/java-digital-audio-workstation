package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 280 — Performance Stage rendered at default resolution: transport
 * buttons are 64&nbsp;±&nbsp;2&nbsp;px tall, the clock font is 48&nbsp;px,
 * and a track tile row is 80&nbsp;±&nbsp;2&nbsp;px tall.
 *
 * <p>FX-harness pitfalls honoured: the view is attached to a real
 * {@link Scene} (with {@code styles.css}, since the {@code .size-stage} /
 * {@code .numeric-display-stage} rules are AUTHOR CSS), {@code applyCss()}
 * + {@code layout()} force a layout pass before any geometry is read, and
 * assertions are captured + rethrown on the test thread.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class PerformanceStageSizingTest {

    private static final String STYLES_CSS =
            "/com/benesquivelmusic/daw/app/ui/styles.css";

    @Test
    void transportButtonsClockAndTileRowsRenderAtStageSizes() throws Exception {
        onFxThread(() -> {
            PerformanceStageView view = newStageView(3);

            // The Performance Stage tokens (-surface-bg etc.) resolve from
            // the .root-pane scope; wrap the view so the cascade applies.
            StackPane root = new StackPane(view);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 1280, 800);
            scene.getStylesheets().add(
                    PerformanceStageSizingTest.class.getResource(STYLES_CSS).toExternalForm());
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();

            // Transport button height — 64 ± 2 px (.dawg-button.size-stage).
            double playHeight = view.playButton().getLayoutBounds().getHeight();
            assertThat(playHeight)
                    .as("PLAY transport button height must be 64 ± 2 px (was %s)", playHeight)
                    .isBetween(62.0, 66.0);

            // Clock font — exactly 48 px (.numeric-display-stage). Read the
            // resolved Font from the Label after applyCss.
            double clockFont = view.clockLabel().getFont().getSize();
            assertThat(clockFont)
                    .as("clock font must be 48 px (was %s)", clockFont)
                    .isEqualTo(48.0);

            // Track tile row — 80 ± 2 px (.track-strip.size-performance).
            TrackStrip firstTile = view.trackTiles().getFirst();
            double tileHeight = firstTile.getLayoutBounds().getHeight();
            assertThat(tileHeight)
                    .as("track tile row height must be 80 ± 2 px (was %s)", tileHeight)
                    .isBetween(78.0, 82.0);

            stage.close();
            return null;
        });
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static PerformanceStageView newStageView(int trackCount) {
        DawProject project = new DawProject("PS Sizing", AudioFormat.STUDIO_QUALITY);
        for (int i = 0; i < trackCount; i++) {
            project.addTrack(new Track("Track " + (i + 1), TrackType.AUDIO));
        }
        ResourceBundle messages = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        return new PerformanceStageView(project, messages, new InertHost());
    }

    /** Inert host — sizing does not exercise any callback. */
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
