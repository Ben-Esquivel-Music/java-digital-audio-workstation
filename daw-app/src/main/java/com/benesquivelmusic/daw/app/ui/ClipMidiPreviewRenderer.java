package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.midi.MidiNoteData;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * Renders the miniature piano-roll preview inside an arrangement MIDI
 * clip rectangle — a row of tiny note bars positioned vertically by pitch
 * and horizontally by beat.
 *
 * <p>Stateless utility: all positioning and scaling state is passed in
 * explicitly so the renderer can be tested with a mock
 * {@link GraphicsContext} and shared between clips.</p>
 */
final class ClipMidiPreviewRenderer {

    static final Color MIDI_NOTE_COLOR = Color.web("#ffffff", 0.7);

    private static final double MIDI_NOTE_HEIGHT_FRACTION = 0.08;
    private static final double BEATS_PER_COLUMN = EditorView.BEATS_PER_COLUMN;

    /** Preview note bounds and range summary, computed from the note list. */
    record PreviewBounds(double startBeat, double durationBeats,
                         int minColumn, int minNote, int maxNote) {
    }

    private ClipMidiPreviewRenderer() {
    }

    /**
     * Scans {@code notes} to determine the MIDI clip bounding beats and
     * pitch range. Returns {@code null} if the list is empty.
     */
    static PreviewBounds computeBounds(List<MidiNoteData> notes) {
        if (notes.isEmpty()) {
            return null;
        }
        int minColumn = Integer.MAX_VALUE;
        int maxEndColumn = 0;
        int minNote = MidiNoteData.MAX_NOTE_NUMBER;
        int maxNote = 0;
        for (MidiNoteData note : notes) {
            if (note.startColumn() < minColumn) {
                minColumn = note.startColumn();
            }
            if (note.endColumn() > maxEndColumn) {
                maxEndColumn = note.endColumn();
            }
            if (note.noteNumber() < minNote) {
                minNote = note.noteNumber();
            }
            if (note.noteNumber() > maxNote) {
                maxNote = note.noteNumber();
            }
        }
        double clipStartBeat = minColumn * BEATS_PER_COLUMN;
        double clipDurationBeats = (maxEndColumn - minColumn) * BEATS_PER_COLUMN;
        if (clipDurationBeats <= 0) {
            clipDurationBeats = BEATS_PER_COLUMN;
        }
        return new PreviewBounds(clipStartBeat, clipDurationBeats, minColumn, minNote, maxNote);
    }

    /**
     * Draws mini piano-roll note rectangles inside the given clip bounds.
     *
     * @param gc            the graphics context to draw on
     * @param notes         the MIDI notes to render
     * @param bounds        pre-computed note bounding box
     * @param clipX         clip left edge in canvas pixels
     * @param clipY         clip top edge in canvas pixels
     * @param clipWidth     clip width in pixels
     * @param clipHeight    clip height in pixels
     * @param pixelsPerBeat horizontal scale
     */
    static void drawNotes(GraphicsContext gc, List<MidiNoteData> notes,
                          PreviewBounds bounds,
                          double clipX, double clipY,
                          double clipWidth, double clipHeight,
                          double pixelsPerBeat) {
        int noteRange = bounds.maxNote() - bounds.minNote() + 1;
        if (noteRange < 1) {
            noteRange = 1;
        }
        double noteAreaY = clipY + 14;
        double noteAreaHeight = clipHeight - 18;
        if (noteAreaHeight < 4) {
            noteAreaHeight = 4;
        }
        double noteHeight = Math.max(2.0, Math.min(noteAreaHeight / noteRange,
                clipHeight * MIDI_NOTE_HEIGHT_FRACTION));

        gc.setFill(MIDI_NOTE_COLOR);
        for (MidiNoteData note : notes) {
            double nx = clipX + (note.startColumn() - bounds.minColumn()) * BEATS_PER_COLUMN * pixelsPerBeat;
            double nw = note.durationColumns() * BEATS_PER_COLUMN * pixelsPerBeat;
            double pitchFraction = noteRange > 1
                    ? (double) (bounds.maxNote() - note.noteNumber()) / (noteRange - 1)
                    : 0.5;
            double ny = noteAreaY + pitchFraction * (noteAreaHeight - noteHeight);
            gc.fillRect(nx, ny, Math.max(1, nw), noteHeight);
        }
    }
}
