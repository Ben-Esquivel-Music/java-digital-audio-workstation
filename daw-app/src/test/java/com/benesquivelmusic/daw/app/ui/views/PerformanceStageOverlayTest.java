package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 280 — the floating {@code ☰} hamburger opens a translucent overlay
 * whose items expose the actions that don't live on the stage itself:
 * Switch to Standard View, Audio Settings, a Project/File sub-overlay
 * (New / Open / Save / Recent), and Exit.
 *
 * <p>Verifies the headline AC the original test set missed: the hamburger
 * actually opens the overlay, each overlay item dismisses the overlay and
 * invokes the corresponding {@link PerformanceStageView.Host} callback, and
 * the Project/File sub-panel pivots in/out without dismissing the overlay
 * (it is a drill-down, not an action).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class PerformanceStageOverlayTest {

    @Test
    void hamburgerOpensOverlayAndItemDismissesAndInvokesHost() throws Exception {
        onFxThread(() -> {
            RecordingHost host = new RecordingHost();
            PerformanceStageView view = newStageView(host);
            new Scene(view, 1280, 800);

            assertThat(view.overlay().isVisible())
                    .as("overlay starts closed")
                    .isFalse();
            assertThat(view.hamburgerButton().isVisible())
                    .as("hamburger starts visible")
                    .isTrue();

            view.hamburgerButton().fire();

            assertThat(view.overlay().isVisible())
                    .as("hamburger fire opens the overlay")
                    .isTrue();
            assertThat(view.hamburgerButton().isVisible())
                    .as("hamburger hides while the overlay is open")
                    .isFalse();
            assertThat(view.overlayMainPanel().isVisible())
                    .as("main overlay panel is the default open state")
                    .isTrue();
            assertThat(view.overlayFilePanel().isVisible())
                    .as("file sub-panel is hidden by default")
                    .isFalse();

            // Click the "Audio Settings" main-panel item — index 1
            // (standardView, audioSettings, projectMenu, exit).
            Button audioSettings = (Button) view.overlayMainPanel().getChildren().get(1);
            audioSettings.fire();

            assertThat(host.audioSettingsCalled.get())
                    .as("Audio Settings item invokes Host#onOpenAudioSettings")
                    .isTrue();
            assertThat(view.overlay().isVisible())
                    .as("overlay closes after a main-panel item runs")
                    .isFalse();
            assertThat(view.hamburgerButton().isVisible())
                    .as("hamburger reappears once the overlay closes")
                    .isTrue();
            return null;
        });
    }

    @Test
    void projectFileOpensSubPanelAndFileItemInvokesHost() throws Exception {
        onFxThread(() -> {
            RecordingHost host = new RecordingHost();
            PerformanceStageView view = newStageView(host);
            new Scene(view, 1280, 800);

            view.hamburgerButton().fire();
            // Main-panel index 2 = "Project / File…" — a panel-switch, not
            // an action: it pivots the overlay to the file sub-panel.
            Button projectMenu = (Button) view.overlayMainPanel().getChildren().get(2);
            projectMenu.fire();

            assertThat(view.overlay().isVisible())
                    .as("Project / File… keeps the overlay open (it is a drill-down)")
                    .isTrue();
            assertThat(view.overlayMainPanel().isVisible())
                    .as("main panel hides while the file sub-panel is showing")
                    .isFalse();
            assertThat(view.overlayFilePanel().isVisible())
                    .as("file sub-panel is now visible")
                    .isTrue();

            // File-panel indices: new, open, save, recent, back.
            Button fileOpen = (Button) view.overlayFilePanel().getChildren().get(1);
            fileOpen.fire();

            assertThat(host.openProjectCalled.get())
                    .as("Open file item invokes Host#onOpenProject")
                    .isTrue();
            assertThat(view.overlay().isVisible())
                    .as("overlay closes once a file action runs")
                    .isFalse();

            // Re-opening should reset to the main panel (file drill-down is
            // transient — proven by re-firing the hamburger).
            view.hamburgerButton().fire();
            assertThat(view.overlayMainPanel().isVisible())
                    .as("re-opening returns to the main panel, not the previous file sub-panel")
                    .isTrue();
            assertThat(view.overlayFilePanel().isVisible()).isFalse();
            return null;
        });
    }

    @Test
    void fileBackButtonReturnsToMainPanelWithoutClosingOverlay() throws Exception {
        onFxThread(() -> {
            RecordingHost host = new RecordingHost();
            PerformanceStageView view = newStageView(host);
            new Scene(view, 1280, 800);

            view.hamburgerButton().fire();
            ((Button) view.overlayMainPanel().getChildren().get(2)).fire(); // Project / File…
            assertThat(view.overlayFilePanel().isVisible()).isTrue();

            // File-panel index 4 = Back.
            Button back = (Button) view.overlayFilePanel().getChildren().get(4);
            back.fire();

            assertThat(view.overlay().isVisible())
                    .as("Back keeps the overlay open")
                    .isTrue();
            assertThat(view.overlayMainPanel().isVisible())
                    .as("Back restores the main panel")
                    .isTrue();
            assertThat(view.overlayFilePanel().isVisible()).isFalse();
            return null;
        });
    }

    // ── Harness ───────────────────────────────────────────────────────────

    private static PerformanceStageView newStageView(PerformanceStageView.Host host) {
        DawProject project = new DawProject("PS Overlay", AudioFormat.STUDIO_QUALITY);
        project.addTrack(new Track("Track 1", TrackType.AUDIO));
        ResourceBundle messages = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
        return new PerformanceStageView(project, messages, host);
    }

    /** Captures each Host callback so the tests can assert which one fired. */
    private static final class RecordingHost implements PerformanceStageView.Host {
        final AtomicBoolean exitCalled = new AtomicBoolean();
        final AtomicBoolean audioSettingsCalled = new AtomicBoolean();
        final AtomicBoolean newProjectCalled = new AtomicBoolean();
        final AtomicBoolean openProjectCalled = new AtomicBoolean();
        final AtomicBoolean saveProjectCalled = new AtomicBoolean();
        final AtomicBoolean recentProjectsCalled = new AtomicBoolean();

        @Override public void onPlay() { }
        @Override public void onStop() { }
        @Override public void onRecord() { }
        @Override public void onToggleLoop() { }
        @Override public void onExitPerformanceStage() { exitCalled.set(true); }
        @Override public void onOpenAudioSettings() { audioSettingsCalled.set(true); }
        @Override public void onNewProject() { newProjectCalled.set(true); }
        @Override public void onOpenProject() { openProjectCalled.set(true); }
        @Override public void onSaveProject() { saveProjectCalled.set(true); }
        @Override public void onRecentProjects() { recentProjectsCalled.set(true); }
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
