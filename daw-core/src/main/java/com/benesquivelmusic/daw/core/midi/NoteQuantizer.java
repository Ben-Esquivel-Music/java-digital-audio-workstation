package com.benesquivelmusic.daw.core.midi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Quantizes MIDI note positions and durations to a grid resolution.
 *
 * <p>Quantization snaps note start positions and optionally note durations
 * to the nearest grid boundary. The grid is defined by the number of
 * columns per beat and the quantize resolution in columns.</p>
 */
public final class NoteQuantizer {

    private NoteQuantizer() {
        // utility class
    }

    /**
     * Quantizes a single note's start position to the nearest multiple of
     * {@code gridColumns}.
     *
     * @param note        the note to quantize
     * @param gridColumns the grid resolution in columns (must be ≥ 1)
     * @return a new note with the quantized start position
     * @throws IllegalArgumentException if {@code gridColumns} is less than 1
     * @throws NullPointerException     if {@code note} is {@code null}
     */
    public static MidiNoteData quantizePosition(MidiNoteData note, int gridColumns) {
        Objects.requireNonNull(note, "note must not be null");
        if (gridColumns < 1) {
            throw new IllegalArgumentException(
                    "gridColumns must be >= 1: " + gridColumns);
        }
        int snappedStart = Math.round((float) note.startColumn() / gridColumns) * gridColumns;
        snappedStart = Math.max(0, snappedStart);
        return note.withStartColumn(snappedStart);
    }

    /**
     * Quantizes a single note's duration to the nearest multiple of
     * {@code gridColumns}, with a minimum of 1 column.
     *
     * @param note        the note to quantize
     * @param gridColumns the grid resolution in columns (must be ≥ 1)
     * @return a new note with the quantized duration
     * @throws IllegalArgumentException if {@code gridColumns} is less than 1
     * @throws NullPointerException     if {@code note} is {@code null}
     */
    public static MidiNoteData quantizeDuration(MidiNoteData note, int gridColumns) {
        Objects.requireNonNull(note, "note must not be null");
        if (gridColumns < 1) {
            throw new IllegalArgumentException(
                    "gridColumns must be >= 1: " + gridColumns);
        }
        int snappedDuration = Math.round((float) note.durationColumns() / gridColumns)
                * gridColumns;
        snappedDuration = Math.max(1, snappedDuration);
        return note.withDurationColumns(snappedDuration);
    }

    /**
     * Quantizes both position and duration of a note.
     *
     * @param note        the note to quantize
     * @param gridColumns the grid resolution in columns (must be ≥ 1)
     * @return a new note with quantized position and duration
     */
    public static MidiNoteData quantize(MidiNoteData note, int gridColumns) {
        MidiNoteData positionQuantized = quantizePosition(note, gridColumns);
        return quantizeDuration(positionQuantized, gridColumns);
    }

    /**
     * Quantizes all notes in a list, returning a new list of quantized notes.
     *
     * @param notes       the notes to quantize
     * @param gridColumns the grid resolution in columns (must be ≥ 1)
     * @return a new list of quantized notes
     * @throws NullPointerException if {@code notes} is {@code null}
     */
    public static List<MidiNoteData> quantizeAll(List<MidiNoteData> notes,
                                                 int gridColumns) {
        Objects.requireNonNull(notes, "notes must not be null");
        List<MidiNoteData> result = new ArrayList<>(notes.size());
        for (MidiNoteData note : notes) {
            result.add(quantize(note, gridColumns));
        }
        return result;
    }
}
