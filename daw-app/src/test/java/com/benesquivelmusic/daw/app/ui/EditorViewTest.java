package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.application.Platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EditorViewTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void initToolkit() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
            latch.await(5, TimeUnit.SECONDS);
            toolkitAvailable = true;
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized
            toolkitAvailable = true;
        } catch (UnsupportedOperationException ignored) {
            // No display available (headless CI environment)
            toolkitAvailable = false;
        }
    }

    private EditorView createOnFxThread() throws Exception {
        AtomicReference<EditorView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ref.set(new EditorView());
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            action.run();
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldStartInEmptyMode() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view).isNotNull();
        assertThat(view.getMode()).isEqualTo(EditorView.Mode.EMPTY);
        assertThat(view.getSelectedTrack()).isNull();
    }

    @Test
    void shouldShowPlaceholderWhenEmpty() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getContentArea().getChildren()).hasSize(1);
        assertThat(view.getContentArea().getChildren().get(0))
                .isSameAs(view.getPlaceholderLabel());
    }

    @Test
    void shouldSwitchToMidiModeForMidiTrack() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        Track midiTrack = new Track("Piano", TrackType.MIDI);

        runOnFxThread(() -> view.setTrack(midiTrack));

        assertThat(view.getMode()).isEqualTo(EditorView.Mode.MIDI);
        assertThat(view.getSelectedTrack()).isSameAs(midiTrack);
    }

    @Test
    void shouldSwitchToAudioModeForAudioTrack() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);

        runOnFxThread(() -> view.setTrack(audioTrack));

        assertThat(view.getMode()).isEqualTo(EditorView.Mode.AUDIO);
        assertThat(view.getSelectedTrack()).isSameAs(audioTrack);
    }

    @Test
    void shouldSwitchBackToEmptyWhenTrackCleared() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);

        runOnFxThread(() -> view.setTrack(audioTrack));
        assertThat(view.getMode()).isEqualTo(EditorView.Mode.AUDIO);

        runOnFxThread(() -> view.setTrack(null));
        assertThat(view.getMode()).isEqualTo(EditorView.Mode.EMPTY);
        assertThat(view.getSelectedTrack()).isNull();
    }

    @Test
    void shouldHaveEditorPanelStyleClass() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getStyleClass()).contains("editor-panel");
    }

    @Test
    void shouldHaveToolBar() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getToolBar()).isNotNull();
        // Pointer, Pencil, Eraser, Spacer, ZoomIn, ZoomOut
        assertThat(view.getToolBar().getChildren()).hasSize(6);
    }

    @Test
    void shouldHaveWaveformDisplayInAudioMode() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getWaveformDisplay()).isNotNull();
        assertThat(view.getWaveformDisplay()).isInstanceOf(WaveformDisplay.class);
    }

    @Test
    void shouldHavePianoRollCanvasInMidiMode() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getPianoRollCanvas()).isNotNull();
        assertThat(view.getPianoRollCanvas().getHeight()).isGreaterThan(0);
    }

    @Test
    void shouldHaveVelocityCanvasInMidiMode() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getVelocityCanvas()).isNotNull();
        assertThat(view.getVelocityCanvas().getHeight()).isGreaterThan(0);
    }

    @Test
    void shouldTreatAuxTrackAsAudioMode() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        Track auxTrack = new Track("Bus 1", TrackType.AUX);

        runOnFxThread(() -> view.setTrack(auxTrack));

        assertThat(view.getMode()).isEqualTo(EditorView.Mode.AUDIO);
    }

    @Test
    void shouldTreatMasterTrackAsAudioMode() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        Track masterTrack = new Track("Master", TrackType.MASTER);

        runOnFxThread(() -> view.setTrack(masterTrack));

        assertThat(view.getMode()).isEqualTo(EditorView.Mode.AUDIO);
    }

    @Test
    void placeholderShouldContainMessage() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getPlaceholderLabel().getText())
                .contains("No track or clip selected");
    }

    @Test
    void modeShouldHaveThreeValues() {
        assertThat(EditorView.Mode.values()).hasSize(3);
        assertThat(EditorView.Mode.values())
                .containsExactly(EditorView.Mode.EMPTY, EditorView.Mode.MIDI, EditorView.Mode.AUDIO);
    }
}
