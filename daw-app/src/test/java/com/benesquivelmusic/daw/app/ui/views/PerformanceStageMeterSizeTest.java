package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;
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
 * Story 280 — the Performance Stage top-band master meter is the same
 * {@link LevelMeter} {@code Control} as the standard view, carrying the
 * {@code .size-performance} style class, and renders at 24&nbsp;×&nbsp;320
 * &nbsp;px (the dimensions declared by {@code level-meter.css}'s
 * {@code .size-performance} rule, story 267).
 *
 * <p>This proves the §2.5 promise — "a bigger meter is the same Control,
 * skinned larger" — rather than a parallel performance widget.</p>
 *
 * <p>FX-harness pitfalls honoured: real {@link Scene}, {@code applyCss()}
 * + {@code layout()} before geometry reads, captured-and-rethrown
 * assertions.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class PerformanceStageMeterSizeTest {

    private static final String STYLES_CSS =
            "/com/benesquivelmusic/daw/app/ui/styles.css";

    @Test
    void masterMeterIsSizePerformanceAtTwentyFourByThreeTwenty() throws Exception {
        onFxThread(() -> {
            PerformanceStageView view = newStageView();
            LevelMeter meter = view.busMeter();

            assertThat(meter.getStyleClass())
                    .as("the master meter must carry the size-performance variant")
                    .contains("size-performance");

            StackPane root = new StackPane(view);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 1280, 800);
            scene.getStylesheets().add(
                    PerformanceStageMeterSizeTest.class.getResource(STYLES_CSS).toExternalForm());
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                root.applyCss();
                root.layout();

                double width = meter.getLayoutBounds().getWidth();
                double height = meter.getLayoutBounds().getHeight();
                assertThat(width)
                        .as("size-performance meter width must be 24 px (was %s)", width)
                        .isBetween(22.0, 26.0);
                assertThat(height)
                        .as("size-performance meter height must be 320 px (was %s)", height)
                        .isBetween(318.0, 322.0);
            } finally {
                stage.close();
            }
            return null;
        });
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static PerformanceStageView newStageView() {
        DawProject project = new DawProject("PS Meter", AudioFormat.STUDIO_QUALITY);
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
