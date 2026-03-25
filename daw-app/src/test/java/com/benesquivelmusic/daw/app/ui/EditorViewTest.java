package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.display.WaveformDisplay;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.application.Platform;
import javafx.scene.Cursor;

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
        toolkitAvailable = false;
        CountDownLatch startupLatch = new CountDownLatch(1);
        try {
            Platform.startup(startupLatch::countDown);
            if (!startupLatch.await(5, TimeUnit.SECONDS)) {
                return;
            }
        } catch (IllegalStateException ignored) {
            // Toolkit already initialized — will verify below
        } catch (UnsupportedOperationException ignored) {
            // No display available (headless CI environment)
            return;
        }
        // Verify the FX Application Thread is actually processing events.
        // Platform.runLater() itself can block if the toolkit is wedged,
        // so we call it from a daemon thread with a timeout.
        CountDownLatch verifyLatch = new CountDownLatch(1);
        Thread verifier = new Thread(() -> {
            try {
                Platform.runLater(verifyLatch::countDown);
            } catch (Exception ignored) {
                // Platform.runLater failed — toolkit is not functional
            }
        });
        verifier.setDaemon(true);
        verifier.start();
        verifier.join(3000);
        toolkitAvailable = verifyLatch.await(3, TimeUnit.SECONDS);
    }

    private EditorView createOnFxThread() throws Exception {
        AtomicReference<EditorView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new EditorView());
            } finally {
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
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

    // ── Tool selection tests ─────────────────────────────────────────────────

    @Test
    void shouldDefaultToPointerTool() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getActiveEditTool()).isEqualTo(EditTool.POINTER);
    }

    @Test
    void shouldChangeActiveEditTool() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        runOnFxThread(() -> view.setActiveEditTool(EditTool.PENCIL));
        assertThat(view.getActiveEditTool()).isEqualTo(EditTool.PENCIL);

        runOnFxThread(() -> view.setActiveEditTool(EditTool.ERASER));
        assertThat(view.getActiveEditTool()).isEqualTo(EditTool.ERASER);

        runOnFxThread(() -> view.setActiveEditTool(EditTool.POINTER));
        assertThat(view.getActiveEditTool()).isEqualTo(EditTool.POINTER);
    }

    @Test
    void shouldChangeCursorToDefaultForPointer() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        runOnFxThread(() -> view.setActiveEditTool(EditTool.PENCIL));
        runOnFxThread(() -> view.setActiveEditTool(EditTool.POINTER));

        assertThat(view.getPianoRollCanvas().getCursor()).isEqualTo(Cursor.DEFAULT);
    }

    @Test
    void shouldChangeCursorToCrosshairForPencil() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        runOnFxThread(() -> view.setActiveEditTool(EditTool.PENCIL));

        assertThat(view.getPianoRollCanvas().getCursor()).isEqualTo(Cursor.CROSSHAIR);
    }

    @Test
    void shouldChangeCursorToHandForEraser() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        runOnFxThread(() -> view.setActiveEditTool(EditTool.ERASER));

        assertThat(view.getPianoRollCanvas().getCursor()).isEqualTo(Cursor.HAND);
    }

    @Test
    void shouldNotifyOnToolChangedCallback() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        AtomicReference<EditTool> notified = new AtomicReference<>();

        runOnFxThread(() -> {
            view.setOnToolChanged(notified::set);
            // Simulate clicking the pencil button
            view.getToolBar().getChildren().get(1).fireEvent(
                    new javafx.event.ActionEvent());
        });

        assertThat(notified.get()).isEqualTo(EditTool.PENCIL);
    }

    // ── Zoom tests ───────────────────────────────────────────────────────────

    @Test
    void shouldStartWithDefaultZoomLevel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getZoomLevel()).isNotNull();
        assertThat(view.getZoomLevel().getLevel()).isEqualTo(ZoomLevel.DEFAULT_ZOOM);
    }

    @Test
    void zoomInShouldIncreaseZoomLevel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        double initialZoom = view.getZoomLevel().getLevel();

        // Fire the zoom-in button (index 4 in toolbar: pointer=0, pencil=1, eraser=2, spacer=3, zoomIn=4)
        runOnFxThread(() -> view.getToolBar().getChildren().get(4).fireEvent(
                new javafx.event.ActionEvent()));

        assertThat(view.getZoomLevel().getLevel()).isGreaterThan(initialZoom);
    }

    @Test
    void zoomOutShouldDecreaseZoomLevel() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        // Zoom in first to have room to zoom out
        runOnFxThread(() -> view.getToolBar().getChildren().get(4).fireEvent(
                new javafx.event.ActionEvent()));
        double afterZoomIn = view.getZoomLevel().getLevel();

        // Now zoom out
        runOnFxThread(() -> view.getToolBar().getChildren().get(5).fireEvent(
                new javafx.event.ActionEvent()));

        assertThat(view.getZoomLevel().getLevel()).isLessThan(afterZoomIn);
    }

    @Test
    void zoomShouldResizeCanvasWidth() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        double initialWidth = view.getPianoRollCanvas().getWidth();

        runOnFxThread(() -> view.getToolBar().getChildren().get(4).fireEvent(
                new javafx.event.ActionEvent()));

        assertThat(view.getPianoRollCanvas().getWidth()).isGreaterThan(initialWidth);
    }

    // ── Note interaction tests ───────────────────────────────────────────────

    @Test
    void shouldStartWithEmptyNotes() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getNotes()).isEmpty();
        assertThat(view.getSelectedNoteIndex()).isEqualTo(-1);
    }

    @Test
    void pencilToolShouldInsertNote() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        runOnFxThread(() -> {
            view.setTrack(new Track("Piano", TrackType.MIDI));
            view.setActiveEditTool(EditTool.PENCIL);
            // Simulate click at grid position: column 2, note row 48
            // x = PIANO_KEY_WIDTH + 2 * colWidth + colWidth/2 (center of column 2)
            // y = 48 * KEY_HEIGHT + KEY_HEIGHT/2 (center of row 48)
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });

        assertThat(view.getNotes()).hasSize(1);
        assertThat(view.getNotes().getFirst().note()).isEqualTo(48);
        assertThat(view.getNotes().getFirst().startColumn()).isEqualTo(2);
    }

    @Test
    void pencilToolShouldNotInsertDuplicateNote() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        runOnFxThread(() -> {
            view.setTrack(new Track("Piano", TrackType.MIDI));
            view.setActiveEditTool(EditTool.PENCIL);
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            var mouseEvent = new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                    x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, false, null);
            view.getPianoRollCanvas().fireEvent(mouseEvent);
            view.getPianoRollCanvas().fireEvent(mouseEvent);
        });

        assertThat(view.getNotes()).hasSize(1);
    }

    @Test
    void eraserToolShouldDeleteNote() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        runOnFxThread(() -> {
            view.setTrack(new Track("Piano", TrackType.MIDI));
            view.setActiveEditTool(EditTool.PENCIL);
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });
        assertThat(view.getNotes()).hasSize(1);

        runOnFxThread(() -> {
            view.setActiveEditTool(EditTool.ERASER);
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });

        assertThat(view.getNotes()).isEmpty();
    }

    @Test
    void pointerToolShouldSelectNote() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        runOnFxThread(() -> {
            view.setTrack(new Track("Piano", TrackType.MIDI));
            view.setActiveEditTool(EditTool.PENCIL);
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });
        assertThat(view.getSelectedNoteIndex()).isEqualTo(-1);

        runOnFxThread(() -> {
            view.setActiveEditTool(EditTool.POINTER);
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });

        assertThat(view.getSelectedNoteIndex()).isEqualTo(0);
    }

    @Test
    void pointerToolShouldDeselectWhenClickingEmptySpace() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        runOnFxThread(() -> {
            view.setTrack(new Track("Piano", TrackType.MIDI));
            view.setActiveEditTool(EditTool.PENCIL);
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));

            // Select the note
            view.setActiveEditTool(EditTool.POINTER);
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });
        assertThat(view.getSelectedNoteIndex()).isEqualTo(0);

        // Click empty space (different row)
        runOnFxThread(() -> {
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 10 * colWidth + colWidth / 2;
            double y = 10 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });

        assertThat(view.getSelectedNoteIndex()).isEqualTo(-1);
    }

    @Test
    void toolBarButtonsShouldHaveActiveStyleClass() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        // Pointer is active by default — first button should have active class
        assertThat(view.getToolBar().getChildren().get(0).getStyleClass())
                .contains("editor-tool-button-active");
        assertThat(view.getToolBar().getChildren().get(1).getStyleClass())
                .doesNotContain("editor-tool-button-active");

        // Switch to pencil
        runOnFxThread(() -> view.setActiveEditTool(EditTool.PENCIL));
        assertThat(view.getToolBar().getChildren().get(0).getStyleClass())
                .doesNotContain("editor-tool-button-active");
        assertThat(view.getToolBar().getChildren().get(1).getStyleClass())
                .contains("editor-tool-button-active");
    }

    // ── Audio handle button tests ───────────────────────────────────────────

    @Test
    void audioHandleButtonsShouldBeDisabledByDefault() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();

        assertThat(view.getTrimButton()).isNotNull();
        assertThat(view.getTrimButton().isDisabled()).isTrue();
        assertThat(view.getFadeInButton().isDisabled()).isTrue();
        assertThat(view.getFadeOutButton().isDisabled()).isTrue();
    }

    @Test
    void audioHandleButtonsShouldBeDisabledForMidiTrack() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        Track midiTrack = new Track("Keys", TrackType.MIDI);

        runOnFxThread(() -> view.setTrack(midiTrack));

        assertThat(view.getTrimButton().isDisabled()).isTrue();
        assertThat(view.getFadeInButton().isDisabled()).isTrue();
        assertThat(view.getFadeOutButton().isDisabled()).isTrue();
    }

    @Test
    void audioHandleButtonsShouldBeDisabledForEmptyAudioTrack() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);

        runOnFxThread(() -> view.setTrack(audioTrack));

        assertThat(view.getTrimButton().isDisabled()).isTrue();
        assertThat(view.getFadeInButton().isDisabled()).isTrue();
        assertThat(view.getFadeOutButton().isDisabled()).isTrue();
    }

    @Test
    void audioHandleButtonsShouldBeEnabledForAudioTrackWithClip() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);
        audioTrack.addClip(new AudioClip("Take 1", 0.0, 8.0, "/audio/take1.wav"));

        runOnFxThread(() -> view.setTrack(audioTrack));

        assertThat(view.getTrimButton().isDisabled()).isFalse();
        assertThat(view.getFadeInButton().isDisabled()).isFalse();
        assertThat(view.getFadeOutButton().isDisabled()).isFalse();
    }

    @Test
    void trimButtonShouldFireCallback() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        AtomicReference<Boolean> trimFired = new AtomicReference<>(false);

        runOnFxThread(() -> {
            view.setOnTrimAction(() -> trimFired.set(true));
            view.getTrimButton().fire();
        });

        assertThat(trimFired.get()).isTrue();
    }

    @Test
    void fadeInButtonShouldFireCallback() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        AtomicReference<Boolean> fadeInFired = new AtomicReference<>(false);

        runOnFxThread(() -> {
            view.setOnFadeInAction(() -> fadeInFired.set(true));
            view.getFadeInButton().fire();
        });

        assertThat(fadeInFired.get()).isTrue();
    }

    @Test
    void fadeOutButtonShouldFireCallback() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        AtomicReference<Boolean> fadeOutFired = new AtomicReference<>(false);

        runOnFxThread(() -> {
            view.setOnFadeOutAction(() -> fadeOutFired.set(true));
            view.getFadeOutButton().fire();
        });

        assertThat(fadeOutFired.get()).isTrue();
    }

    @Test
    void audioHandleButtonsShouldDisableWhenTrackCleared() throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available (headless CI)");
        EditorView view = createOnFxThread();
        Track audioTrack = new Track("Vocals", TrackType.AUDIO);
        audioTrack.addClip(new AudioClip("Take 1", 0.0, 8.0, null));

        runOnFxThread(() -> view.setTrack(audioTrack));
        assertThat(view.getTrimButton().isDisabled()).isFalse();

        runOnFxThread(() -> view.setTrack(null));
        assertThat(view.getTrimButton().isDisabled()).isTrue();
        assertThat(view.getFadeInButton().isDisabled()).isTrue();
        assertThat(view.getFadeOutButton().isDisabled()).isTrue();
    }
}
