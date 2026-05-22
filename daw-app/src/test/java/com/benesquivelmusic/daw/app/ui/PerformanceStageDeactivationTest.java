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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
 * Story 280 — pressing {@code Esc} while Performance Stage is active
 * returns to the previously-active standard view (here MIXER, to prove the
 * controller restores <em>whatever</em> view was showing, not a hardcoded
 * ARRANGEMENT).
 *
 * <p>The {@code Esc} exit is implemented as a scene {@code KEY_PRESSED}
 * <em>filter</em> in {@code ViewNavigationController} so it consumes the
 * keypress before the standard {@code Esc}-bound {@code STOP} accelerator.
 * This test fires a real {@link KeyEvent} at the scene root to exercise
 * that filter rather than calling the exit method directly.</p>
 *
 * <p>FX-harness pitfalls honoured: real {@link Scene}, FX-thread work,
 * captured-and-rethrown assertions.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class PerformanceStageDeactivationTest {

    @Test
    void escReturnsToThePreviouslyActiveStandardView() throws Exception {
        MotionManager reduced = new MotionManager(
                Preferences.userRoot().node("psDeactivation_" + System.nanoTime()));
        reduced.setReduceMotion(true);
        MotionManager.setDefaultForTest(reduced);
        try {
            onFxThread(() -> {
                BorderPane rootPane = new BorderPane();
                Node mixerCentre = new VBox(new Label("mixer"));
                rootPane.setTop(new VBox(new Button("toolbar")));
                rootPane.setCenter(mixerCentre);
                new Scene(rootPane, 1000, 700);

                ViewNavigationController controller = newController(rootPane);
                // The standard view active when the stage is entered is MIXER.
                controller.switchView(DawView.MIXER);

                controller.switchView(DawView.PERFORMANCE_STAGE);
                assertThat(controller.isPerformanceStageActive()).isTrue();
                assertThat(rootPane.getCenter()).isInstanceOf(PerformanceStageView.class);

                // Fire a real Esc key press at the scene root — the
                // ViewNavigationController scene filter must consume it.
                rootPane.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,
                        "", "", KeyCode.ESCAPE, false, false, false, false));

                assertThat(controller.isPerformanceStageActive())
                        .as("Esc exits Performance Stage")
                        .isFalse();
                assertThat(controller.getActiveView())
                        .as("Esc restores the view active before staging (MIXER)")
                        .isEqualTo(DawView.MIXER);
                assertThat(rootPane.getCenter())
                        .as("the standard MIXER centre node is restored")
                        .isSameAs(controller.getMixerView());
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

        Label statusBar = new Label();
        ToolbarStateStore store = new ToolbarStateStore(
                Preferences.userRoot().node("psDeactivationStore_" + System.nanoTime()));
        Button snap = new Button("snap");

        ViewNavigationController controller = new ViewNavigationController(
                rootPane, statusBar, store, snap,
                DawView.ARRANGEMENT, EditTool.POINTER, true, GridResolution.QUARTER,
                new StubHost(project));
        // Seeds the view cache (mixer view etc.) so switchView(MIXER) works.
        controller.initializeViewNavigation();
        return controller;
    }

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
        @Override public void onPlay() { }
        @Override public void onStop() { }
        @Override public void onRecord() { }
        @Override public void onToggleLoop() { }
        @Override public void onOpenAudioSettings() { }
        @Override public void onOpenProjectMenu() { }
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
