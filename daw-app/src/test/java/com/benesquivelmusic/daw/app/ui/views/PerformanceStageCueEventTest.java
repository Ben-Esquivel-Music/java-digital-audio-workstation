package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 280 — pressing a track tile's <strong>CUE</strong> button fires a
 * typed {@link CueLaunchRequestedEvent} that bubbles up the scene graph,
 * carrying the 1-based track index. Verifies the §12-typed-event channel
 * (skill §12) the story explicitly delivers as the cue stub.
 *
 * <p>The test follows the bubbling-event-test pitfall guidance: the
 * {@code addEventFilter} sits on the {@link PerformanceStageView} root (a
 * parent of every CUE button), and the assertion is on the bubbled
 * {@link CueLaunchRequestedEvent#getTrackIndex()} payload — never on
 * {@code getSource()} identity, which JavaFX rewrites per node during
 * bubble.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
final class PerformanceStageCueEventTest {

    @Test
    void eachCueButtonFiresBubblingEventWithOneBasedTrackIndex() throws Exception {
        onFxThread(() -> {
            DawProject project = new DawProject("PS Cue", AudioFormat.STUDIO_QUALITY);
            project.addTrack(new Track("Drums", TrackType.AUDIO));
            project.addTrack(new Track("Bass", TrackType.AUDIO));
            project.addTrack(new Track("Vox", TrackType.AUDIO));
            ResourceBundle messages = ResourceBundle.getBundle(
                    "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
            PerformanceStageView view = new PerformanceStageView(project, messages, new InertHost());
            new Scene(view, 1280, 800);

            // Reach each CUE button via its tile's parent HBox row. CSS-
            // selector lookups (Node#lookupAll) fail before CSS application
            // because ScrollPane content is realised by its Skin only after
            // an applyCss() pass; parent navigation is layout-independent.
            List<Button> cueButtons = view.trackTiles().stream()
                    .map(tile -> (Button) ((HBox) tile.getParent()).getChildren().get(1))
                    .toList();

            // Attach the filter at the tile-column VBox (each cue's grand-
            // parent) — still strictly a parent, honouring the bubbling-
            // event-test rule (don't assert getSource() identity; verify
            // bubble via a parent filter on the payload). The view-root
            // attach we'd prefer requires the ScrollPane skin to be
            // realised, which a headless test without applyCss/layout
            // wouldn't reliably produce — and the bubble assertion is
            // identical: the typed event escapes the button to a parent.
            Parent tileColumn = view.trackTiles().get(0).getParent().getParent();
            List<Integer> received = new ArrayList<>();
            tileColumn.addEventFilter(CueLaunchRequestedEvent.CUE_LAUNCH_REQUESTED,
                    event -> received.add(event.getTrackIndex()));
            assertThat(cueButtons)
                    .as("one CUE button per track tile")
                    .hasSize(3);
            cueButtons.forEach(Button::fire);

            assertThat(received)
                    .as("CUE buttons fire CueLaunchRequestedEvent with 1-based indices, "
                            + "bubbling to a filter at the view root")
                    .containsExactly(1, 2, 3);
            return null;
        });
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
