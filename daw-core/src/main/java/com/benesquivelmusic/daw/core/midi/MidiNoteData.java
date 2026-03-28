package com.benesquivelmusic.daw.core.midi;

/**
 * Immutable representation of a MIDI note within a {@link MidiClip}.
 *
 * <p>Each note has a pitch (MIDI note number 0–127), a start position and
 * duration expressed in grid columns, a velocity (0–127), and a MIDI
 * channel (0–15).</p>
 *
 * @param noteNumber      the MIDI note number (0–127)
 * @param startColumn     the grid column where the note begins (≥ 0)
 * @param durationColumns the length of the note in grid columns (≥ 1)
 * @param velocity        the MIDI velocity (0–127)
 * @param channel         the MIDI channel (0–15)
 */
public record MidiNoteData(int noteNumber, int startColumn, int durationColumns,
                           int velocity, int channel) {

    /** Maximum MIDI note number. */
    public static final int MAX_NOTE_NUMBER = 127;

    /** Maximum MIDI velocity value. */
    public static final int MAX_VELOCITY = 127;

    /** Maximum MIDI channel number. */
    public static final int MAX_CHANNEL = 15;

    /** Default note duration in grid columns. */
    public static final int DEFAULT_DURATION = 1;

    /** Default MIDI velocity for newly inserted notes. */
    public static final int DEFAULT_VELOCITY = 100;

    /** Default MIDI channel. */
    public static final int DEFAULT_CHANNEL = 0;

    /**
     * Compact constructor — validates all fields.
     */
    public MidiNoteData {
        if (noteNumber < 0 || noteNumber > MAX_NOTE_NUMBER) {
            throw new IllegalArgumentException(
                    "noteNumber must be 0–" + MAX_NOTE_NUMBER + ": " + noteNumber);
        }
        if (startColumn < 0) {
            throw new IllegalArgumentException(
                    "startColumn must be >= 0: " + startColumn);
        }
        if (durationColumns < 1) {
            throw new IllegalArgumentException(
                    "durationColumns must be >= 1: " + durationColumns);
        }
        if (velocity < 0 || velocity > MAX_VELOCITY) {
            throw new IllegalArgumentException(
                    "velocity must be 0–" + MAX_VELOCITY + ": " + velocity);
        }
        if (channel < 0 || channel > MAX_CHANNEL) {
            throw new IllegalArgumentException(
                    "channel must be 0–" + MAX_CHANNEL + ": " + channel);
        }
    }

    /**
     * Creates a note with default channel (0).
     *
     * @param noteNumber      the MIDI note number (0–127)
     * @param startColumn     the grid column where the note begins
     * @param durationColumns the length in grid columns
     * @param velocity        the MIDI velocity (0–127)
     * @return a new note on channel 0
     */
    public static MidiNoteData of(int noteNumber, int startColumn,
                                  int durationColumns, int velocity) {
        return new MidiNoteData(noteNumber, startColumn, durationColumns,
                velocity, DEFAULT_CHANNEL);
    }

    /**
     * Returns a copy of this note with a different start column.
     *
     * @param newStartColumn the new start column
     * @return a new note with the updated position
     */
    public MidiNoteData withStartColumn(int newStartColumn) {
        return new MidiNoteData(noteNumber, newStartColumn, durationColumns,
                velocity, channel);
    }

    /**
     * Returns a copy of this note with a different note number.
     *
     * @param newNoteNumber the new MIDI note number
     * @return a new note with the updated pitch
     */
    public MidiNoteData withNoteNumber(int newNoteNumber) {
        return new MidiNoteData(newNoteNumber, startColumn, durationColumns,
                velocity, channel);
    }

    /**
     * Returns a copy of this note with a different duration.
     *
     * @param newDurationColumns the new duration in grid columns
     * @return a new note with the updated duration
     */
    public MidiNoteData withDurationColumns(int newDurationColumns) {
        return new MidiNoteData(noteNumber, startColumn, newDurationColumns,
                velocity, channel);
    }

    /**
     * Returns a copy of this note with a different velocity.
     *
     * @param newVelocity the new velocity
     * @return a new note with the updated velocity
     */
    public MidiNoteData withVelocity(int newVelocity) {
        return new MidiNoteData(noteNumber, startColumn, durationColumns,
                newVelocity, channel);
    }

    /**
     * Returns the end column (exclusive) of this note.
     *
     * @return {@code startColumn + durationColumns}
     */
    public int endColumn() {
        return startColumn + durationColumns;
    }
}
