package com.benesquivelmusic.daw.core.midi;

/**
 * Type of a piano-roll editing lane displayed underneath the note grid.
 *
 * <p>Every DAW's piano roll has a second pane underneath the notes for
 * velocity bars and a switchable CC (control-change) lane (Logic's
 * "Hyper Draw," Cubase's CC lanes, Ableton's "Envelope" editor, Reaper's
 * "MIDI Editor" CC row). This enum models the well-known lane presets
 * plus a generic {@link #ARBITRARY_CC} entry for any other CC number, and
 * a dedicated {@link #PITCH_BEND} entry for the 14-bit pitch-bend channel
 * message (which is not a CC but conventionally shown next to them).</p>
 *
 * @see MidiCcLane
 */
public enum MidiCcLaneType {

    /** Per-note velocity bars. Aligned with the note's start column. */
    VELOCITY,

    /** Mod-wheel — control change number 1. Often paired with CC 33 for 14-bit. */
    MOD_WHEEL,

    /** Expression — control change number 11. Often paired with CC 43 for 14-bit. */
    EXPRESSION,

    /** Sustain pedal — control change number 64. Switch (on/off) controller. */
    SUSTAIN,

    /** Pitch bend — 14-bit channel-voice message (0..16383, centre at 8192). */
    PITCH_BEND,

    /** Any other CC number, configured on the {@link MidiCcLane}. */
    ARBITRARY_CC;

    /**
     * The conventional MSB CC number for this lane type, or {@code -1}
     * for {@link #VELOCITY}, {@link #PITCH_BEND}, and {@link #ARBITRARY_CC}
     * (the latter carries its own CC number on {@link MidiCcLane}).
     *
     * @return the standard MSB CC number, or {@code -1}
     */
    public int defaultCcNumber() {
        return switch (this) {
            case MOD_WHEEL  -> 1;
            case EXPRESSION -> 11;
            case SUSTAIN    -> 64;
            default         -> -1;
        };
    }

    /**
     * The conventional LSB CC number that pairs with {@link #defaultCcNumber()}
     * to form a 14-bit high-resolution CC, or {@code -1} when no LSB pair
     * is defined.
     *
     * <p>Per the MIDI 1.0 specification, CCs 0–31 are MSBs whose LSB
     * counterparts live at {@code msb + 32}. CCs above 31 (such as
     * sustain at 64) are switch / single-byte controllers and have no
     * LSB pair.</p>
     *
     * @return the LSB CC number that pairs with the MSB, or {@code -1}
     */
    public int defaultLsbCcNumber() {
        int msb = defaultCcNumber();
        return (msb >= 0 && msb < 32) ? msb + 32 : -1;
    }

    /**
     * Whether this lane type natively supports 14-bit high-resolution
     * values (pitch bend always; CC pairs for MSBs in range 0–31).
     *
     * <p>For {@link #ARBITRARY_CC} the answer depends on the actual CC
     * number configured on the {@link MidiCcLane}, so this method returns
     * {@code false} — the lane constructor validates 14-bit eligibility
     * based on the concrete CC number.</p>
     *
     * @return {@code true} if 14-bit values are meaningful for this lane
     */
    public boolean supportsHighResolution() {
        return this == PITCH_BEND || defaultLsbCcNumber() >= 0;
    }
}
