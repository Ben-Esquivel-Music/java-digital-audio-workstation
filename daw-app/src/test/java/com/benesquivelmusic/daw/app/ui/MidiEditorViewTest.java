package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.midi.MidiClip;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.scene.Cursor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class MidiEditorViewTest {

    private MidiEditorView createOnFxThread() throws Exception {
        AtomicReference<MidiEditorView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new MidiEditorView());
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX thread timed out creating MidiEditorView")
                .isTrue();
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
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX thread timed out running action")
                .isTrue();
    }

    // ── Canvas tests ─────────────────────────────────────────────────────────

    @Test
    void shouldHavePianoRollCanvas() throws Exception {
        MidiEditorView view = createOnFxThread();

        assertThat(view.getPianoRollCanvas()).isNotNull();
        assertThat(view.getPianoRollCanvas().getHeight()).isGreaterThan(0);
    }

    @Test
    void shouldHaveVelocityCanvas() throws Exception {
        MidiEditorView view = createOnFxThread();

        assertThat(view.getVelocityCanvas()).isNotNull();
        assertThat(view.getVelocityCanvas().getHeight()).isGreaterThan(0);
    }

    // ── Tool tests ───────────────────────────────────────────────────────────

    @Test
    void shouldChangeCursorForPencilTool() throws Exception {
        MidiEditorView view = createOnFxThread();

        runOnFxThread(() -> view.setActiveEditTool(EditTool.PENCIL));

        assertThat(view.getPianoRollCanvas().getCursor()).isEqualTo(Cursor.CROSSHAIR);
    }

    @Test
    void shouldChangeCursorForEraserTool() throws Exception {
        MidiEditorView view = createOnFxThread();

        runOnFxThread(() -> view.setActiveEditTool(EditTool.ERASER));

        assertThat(view.getPianoRollCanvas().getCursor()).isEqualTo(Cursor.HAND);
    }

    @Test
    void shouldChangeCursorForPointerTool() throws Exception {
        MidiEditorView view = createOnFxThread();

        runOnFxThread(() -> view.setActiveEditTool(EditTool.PENCIL));
        runOnFxThread(() -> view.setActiveEditTool(EditTool.POINTER));

        assertThat(view.getPianoRollCanvas().getCursor()).isEqualTo(Cursor.DEFAULT);
    }

    // ── Note tests ───────────────────────────────────────────────────────────

    @Test
    void shouldStartWithEmptyNotes() throws Exception {
        MidiEditorView view = createOnFxThread();

        assertThat(view.getNotes()).isEmpty();
        assertThat(view.getSelectedNoteIndex()).isEqualTo(-1);
    }

    @Test
    void pencilToolShouldInsertNote() throws Exception {
        MidiEditorView view = createOnFxThread();

        runOnFxThread(() -> {
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
        assertThat(view.getNotes().getFirst().note()).isEqualTo(48);
        assertThat(view.getNotes().getFirst().startColumn()).isEqualTo(2);
    }

    @Test
    void eraserToolShouldDeleteNote() throws Exception {
        MidiEditorView view = createOnFxThread();

        runOnFxThread(() -> {
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
        MidiEditorView view = createOnFxThread();

        runOnFxThread(() -> {
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

    // ── MIDI clip sync tests ────────────────────────────────────────────────

    @Test
    void shouldLoadNotesFromMidiClip() throws Exception {
        MidiEditorView view = createOnFxThread();
        MidiClip clip = new MidiClip();
        clip.addNote(new MidiNoteData(60, 0, 4, 100, 0)); // C4

        runOnFxThread(() -> view.loadFromMidiClip(clip));

        assertThat(view.getNotes()).hasSize(1);
    }

    @Test
    void shouldAddRecordedNote() throws Exception {
        MidiEditorView view = createOnFxThread();
        MidiNoteData noteData = new MidiNoteData(60, 0, 4, 100, 0);

        runOnFxThread(() -> view.addRecordedNote(noteData));

        assertThat(view.getNotes()).hasSize(1);
    }

    // ── Note editing tests ──────────────────────────────────────────────────

    @Test
    void shouldDeleteSelectedNote() throws Exception {
        MidiEditorView view = createOnFxThread();

        // Insert note and select it
        runOnFxThread(() -> {
            view.setActiveEditTool(EditTool.PENCIL);
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
            view.setActiveEditTool(EditTool.POINTER);
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });
        assertThat(view.getSelectedNoteIndex()).isEqualTo(0);

        runOnFxThread(view::deleteSelectedNote);

        assertThat(view.getNotes()).isEmpty();
        assertThat(view.getSelectedNoteIndex()).isEqualTo(-1);
    }

    @Test
    void shouldMoveSelectedNote() throws Exception {
        MidiEditorView view = createOnFxThread();

        runOnFxThread(() -> {
            view.setActiveEditTool(EditTool.PENCIL);
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
            view.setActiveEditTool(EditTool.POINTER);
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });

        runOnFxThread(() -> view.moveSelectedNote(50, 5));

        assertThat(view.getNotes().getFirst().note()).isEqualTo(50);
        assertThat(view.getNotes().getFirst().startColumn()).isEqualTo(5);
    }

    @Test
    void shouldResizeSelectedNote() throws Exception {
        MidiEditorView view = createOnFxThread();

        runOnFxThread(() -> {
            view.setActiveEditTool(EditTool.PENCIL);
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
            view.setActiveEditTool(EditTool.POINTER);
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });

        runOnFxThread(() -> view.resizeSelectedNote(4));

        assertThat(view.getNotes().getFirst().durationColumns()).isEqualTo(4);
    }

    @Test
    void shouldSetSelectedNoteVelocity() throws Exception {
        MidiEditorView view = createOnFxThread();

        runOnFxThread(() -> {
            view.setActiveEditTool(EditTool.PENCIL);
            double colWidth = (view.getPianoRollCanvas().getWidth() - 48) / 32;
            double x = 48 + 2 * colWidth + colWidth / 2;
            double y = 48 * 12 + 6;
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
            view.setActiveEditTool(EditTool.POINTER);
            view.getPianoRollCanvas().fireEvent(
                    new javafx.scene.input.MouseEvent(
                            javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                            x, y, x, y, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, true, false, false, false, false, false, null));
        });

        runOnFxThread(() -> view.setSelectedNoteVelocity(80));

        assertThat(view.getNotes().getFirst().velocity()).isEqualTo(80);
    }

    // ── Snap tests ──────────────────────────────────────────────────────────

    @Test
    void shouldSnapColumnWithDefaultResolution() throws Exception {
        MidiEditorView view = createOnFxThread();
        view.setSnapState(true, GridResolution.QUARTER, 4);

        int snapped = view.snapColumn(3);

        assertThat(snapped).isGreaterThanOrEqualTo(0);
    }

    // ── Zoom tests ──────────────────────────────────────────────────────────

    @Test
    void applyZoomShouldResizeCanvasWidth() throws Exception {
        MidiEditorView view = createOnFxThread();

        double initialWidth = view.getPianoRollCanvas().getWidth();

        runOnFxThread(() -> view.applyZoom(2.0));

        assertThat(view.getPianoRollCanvas().getWidth()).isGreaterThan(initialWidth);
    }

    // ── Undo tests ──────────────────────────────────────────────────────────

    @Test
    void shouldSupportUndoManager() throws Exception {
        MidiEditorView view = createOnFxThread();
        UndoManager undoManager = new UndoManager();

        view.setUndoManager(undoManager);

        assertThat(view.getUndoManager()).isSameAs(undoManager);
    }

    // ── Utility tests ───────────────────────────────────────────────────────

    @Test
    void midiNoteNumberToRowShouldConvertCorrectly() {
        int row = MidiEditorView.midiNoteNumberToRow(60); // C4
        assertThat(row).isGreaterThanOrEqualTo(0);
        assertThat(row).isLessThan(MidiEditorView.TOTAL_KEYS);
    }

    @Test
    void rowToMidiNoteNumberShouldRoundTrip() {
        int noteNumber = 60;
        int row = MidiEditorView.midiNoteNumberToRow(noteNumber);
        int result = MidiEditorView.rowToMidiNoteNumber(row);
        assertThat(result).isEqualTo(noteNumber);
    }

    @Test
    void outOfRangeNoteNumberShouldReturnNegativeOne() {
        assertThat(MidiEditorView.midiNoteNumberToRow(-1)).isEqualTo(-1);
        assertThat(MidiEditorView.midiNoteNumberToRow(200)).isEqualTo(-1);
    }

    @Test
    void beatsPerColumnShouldBeSixteenthNote() {
        assertThat(MidiEditorView.BEATS_PER_COLUMN).isEqualTo(0.25);
    }
}
