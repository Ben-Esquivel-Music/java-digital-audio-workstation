package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.views.PerformanceStageView;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

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
 * Story 280 — activating Performance Stage replaces the standard
 * {@code BorderPane} content with a {@link PerformanceStageView}, and the
 * previously-active arrangement chrome is <em>hidden</em> (detached but
 * kept alive), not unloaded.
 *
 * <p>Lifecycle decision under test: {@code ViewNavigationController}
 * snapshots the {@code BorderPane}'s top / left / right / center into
 * fields and detaches them; it does not rebuild them. Deactivating
 * restores the very same node instances — verified here with an
 * {@code isSameAs} identity check — i.e. the "hide, not unload" choice
 * documented on {@code ViewNavigationController#savedTop}.</p>
 *
 * <p>FX-harness pitfalls honoured: a real {@link Scene}, work on the FX
 * thread, assertions captured into an {@link AtomicReference} and rethrown
 * on the test thread.</p>
 *
 * <p>Package placement: lives in the {@code ui} package (not {@code ui.views})
 * because the SUT is {@link ViewNavigationController} — a package-private
 * class — and only its activation lifecycle is under test. Tests of
 * {@link PerformanceStageView}'s public surface live in {@code ui.views}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class PerformanceStageActivationTest {

    @Test
    void activatingReplacesChromeWithPerformanceStageView() throws Exception {
        // Reduce Motion on → the 180 ms view transition is skipped, so the
        // assertions run against the final state deterministically.
        MotionManager reduced = new MotionManager(
                Preferences.userRoot().node("psActivation_" + System.nanoTime()));
        reduced.setReduceMotion(true);
        MotionManager.setDefaultForTest(reduced);
        try {
            onFxThread(() -> {
                BorderPane rootPane = new BorderPane();
                Node standardTop = new VBox(new Button("toolbar"));
                Node standardLeft = new VBox(new Label("track list"));
                Node standardCenter = new VBox(new Label("arrangement"));
                rootPane.setTop(standardTop);
                rootPane.setLeft(standardLeft);
                rootPane.setCenter(standardCenter);
                new Scene(rootPane, 1000, 700);

                ViewNavigationController controller = newController(rootPane);

                controller.switchView(DawView.PERFORMANCE_STAGE);

                assertThat(controller.isPerformanceStageActive())
                        .as("Performance Stage is active after switch")
                        .isTrue();
                assertThat(rootPane.getCenter())
                        .as("centre content is now the PerformanceStageView")
                        .isInstanceOf(PerformanceStageView.class);
                assertThat(rootPane.getTop())
                        .as("standard toolbar is detached (hidden) while staging")
                        .isNull();
                assertThat(rootPane.getLeft())
                        .as("standard track list is detached (hidden) while staging")
                        .isNull();

                // Hide-not-unload: deactivating restores the SAME instances.
                controller.switchView(DawView.PERFORMANCE_STAGE);
                assertThat(controller.isPerformanceStageActive()).isFalse();
                assertThat(rootPane.getCenter())
                        .as("the original centre node is restored, not rebuilt")
                        .isSameAs(standardCenter);
                assertThat(rootPane.getTop())
                        .as("the original toolbar node is restored, not rebuilt")
                        .isSameAs(standardTop);
                assertThat(rootPane.getLeft())
                        .as("the original track list is restored, not rebuilt")
                        .isSameAs(standardLeft);
                return null;
            });
        } finally {
            MotionManager.setDefaultForTest(null);
        }
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static ViewNavigationController newController(BorderPane rootPane) {
        DawProject project = new DawProject("PS Test", AudioFormat.STUDIO_QUALITY);
        project.addTrack(new Track("Drums", TrackType.AUDIO));
        project.addTrack(new Track("Bass", TrackType.AUDIO));

        Label statusBar = new Label();
        ToolbarStateStore store = new ToolbarStateStore(
                Preferences.userRoot().node("psActivationStore_" + System.nanoTime()));
        Button snap = new Button("snap");

        return new ViewNavigationController(
                rootPane, statusBar, store, snap,
                DawView.ARRANGEMENT, EditTool.POINTER, true, GridResolution.QUARTER,
                new StubHost(project));
    }

    /** Stub host — transport callbacks are inert; only navigation matters here. */
    private static final class StubHost implements ViewNavigationController.Host {
        private final DawProject project;

        StubHost(DawProject project) {
            this.project = project;
        }

        @Override public DawProject project() { return project; }
        @Override public UndoManager undoManager() { return new UndoManager(); }
        @Override public void onEditorTrim() { }
        @Override public void onEditorFadeIn() { }
        @Override public void onEditorFadeOut() { }
        @Override public void markProjectDirty() { }
        @Override public ResourceBundle messages() {
            return ResourceBundle.getBundle(
                    "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        }
        @Override public Label timeDisplay() { return new Label("00:00:00.0"); }
        @Override public void onPlay() { }
        @Override public void onStop() { }
        @Override public void onRecord() { }
        @Override public void onToggleLoop() { }
        @Override public void onOpenAudioSettings() { }
        @Override public void onNewProject() { }
        @Override public void onOpenProject() { }
        @Override public void onSaveProject() { }
        @Override public void onRecentProjects() { }
        // Story 281 — Workshop view binds to the inspector selection model;
        // this stub does not exercise Workshop, so a null model is correct.
        @Override
        public com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel
                inspectorSelectionModel() {
            return null;
        }
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
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not complete within 5 seconds");
        }
        if (err.get() != null) {
            throw new AssertionError("FX thread action failed", err.get());
        }
        return ref.get();
    }
}
