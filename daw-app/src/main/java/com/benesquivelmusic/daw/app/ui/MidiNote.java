package com.benesquivelmusic.daw.app.ui;

/**
 * Represents a single MIDI note event in the piano roll editor.
 *
 * <p>Each note occupies one or more grid columns (time axis) at a specific
 * pitch row (vertical axis). The pitch row is a zero-based index into
 * the piano roll grid where {@code 0} is the topmost (highest pitch) row.</p>
 *
 * @param note            the pitch row index (0 = highest pitch,
 *                        up to {@code TOTAL_KEYS - 1} for the lowest)
 * @param startColumn     the grid column where the note begins (≥ 0)
 * @param durationColumns the length of the note in grid columns (≥ 1)
 * @param velocity        the MIDI velocity (0–127)
 */
public record MidiNote(int note, int startColumn, int durationColumns, int velocity) {

    /** Maximum pitch row index (8 octaves × 12 notes per octave − 1). */
    public static final int MAX_NOTE = 95;

    /** Maximum MIDI velocity value. */
    public static final int MAX_VELOCITY = 127;

    /** Default note duration in grid columns. */
    public static final int DEFAULT_DURATION = 1;

    /** Default MIDI velocity for newly inserted notes. */
    public static final int DEFAULT_VELOCITY = 100;

    /**
     * Compact constructor — validates all fields.
     */
    public MidiNote {
        if (note < 0 || note > MAX_NOTE) {
            throw new IllegalArgumentException("note out of range [0, " + MAX_NOTE + "]: " + note);
        }
        if (startColumn < 0) {
            throw new IllegalArgumentException("startColumn must be >= 0: " + startColumn);
        }
        if (durationColumns < 1) {
            throw new IllegalArgumentException("durationColumns must be >= 1: " + durationColumns);
        }
        if (velocity < 0 || velocity > MAX_VELOCITY) {
            throw new IllegalArgumentException("velocity out of range [0, " + MAX_VELOCITY + "]: " + velocity);
        }
    }
}
