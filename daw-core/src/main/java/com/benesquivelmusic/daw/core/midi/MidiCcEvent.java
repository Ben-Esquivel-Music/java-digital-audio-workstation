package com.benesquivelmusic.daw.core.midi;

/**
 * A single CC breakpoint inside a {@link MidiCcLane}.
 *
 * <p>Breakpoints are positioned on the same column grid used by
 * {@link MidiNoteData} (one column == one sixteenth note in the default
 * editor), and carry a value whose semantic depends on the lane type:</p>
 *
 * <ul>
 *   <li><b>7-bit CC</b> — {@code value} in {@code 0..127}</li>
 *   <li><b>14-bit CC pair</b> — {@code value} in {@code 0..16383} (MSB + LSB)</li>
 *   <li><b>Pitch bend</b> — {@code value} in {@code 0..16383}, where
 *       {@code 8192} is centre</li>
 * </ul>
 *
 * <p>Breakpoints render as a poly-line (line-segment interpolation) just
 * like an automation lane.</p>
 *
 * @param column the grid column where the breakpoint is placed (≥ 0)
 * @param value  the CC value (range depends on the lane type)
 */
public record MidiCcEvent(int column, int value) implements Comparable<MidiCcEvent> {

    /** Minimum 7-bit CC value. */
    public static final int MIN_7BIT = 0;

    /** Maximum 7-bit CC value. */
    public static final int MAX_7BIT = 127;

    /** Minimum 14-bit CC value. */
    public static final int MIN_14BIT = 0;

    /** Maximum 14-bit CC value (= 2^14 − 1). */
    public static final int MAX_14BIT = 16383;

    /** Centre value for 14-bit pitch bend. */
    public static final int PITCH_BEND_CENTRE = 8192;

    /**
     * Compact constructor — validates non-negative column and 14-bit value
     * range. (Lanes that store 7-bit values still fit comfortably in the
     * same range, so we use a single broad bound here.)
     */
    public MidiCcEvent {
        if (column < 0) {
            throw new IllegalArgumentException(
                    "column must be >= 0: " + column);
        }
        if (value < MIN_14BIT || value > MAX_14BIT) {
            throw new IllegalArgumentException(
                    "value must be 0–" + MAX_14BIT + ": " + value);
        }
    }

    /**
     * Returns the MSB (most-significant 7 bits) of a 14-bit value.
     *
     * @return the MSB byte (0–127)
     */
    public int msb() {
        return (value >>> 7) & 0x7F;
    }

    /**
     * Returns the LSB (least-significant 7 bits) of a 14-bit value.
     *
     * @return the LSB byte (0–127)
     */
    public int lsb() {
        return value & 0x7F;
    }

    /**
     * Constructs an event whose value is the combined MSB+LSB of a
     * 14-bit CC pair.
     *
     * @param column the grid column
     * @param msb    the MSB byte (0–127)
     * @param lsb    the LSB byte (0–127)
     * @return a new event with {@code value = (msb << 7) | lsb}
     */
    public static MidiCcEvent ofMsbLsb(int column, int msb, int lsb) {
        if (msb < 0 || msb > 127) {
            throw new IllegalArgumentException("msb must be 0–127: " + msb);
        }
        if (lsb < 0 || lsb > 127) {
            throw new IllegalArgumentException("lsb must be 0–127: " + lsb);
        }
        return new MidiCcEvent(column, (msb << 7) | lsb);
    }

    /**
     * Sort breakpoints by column ascending, then by value to keep
     * coincident events stable.
     */
    @Override
    public int compareTo(MidiCcEvent other) {
        int byColumn = Integer.compare(this.column, other.column);
        return byColumn != 0 ? byColumn : Integer.compare(this.value, other.value);
    }
}
