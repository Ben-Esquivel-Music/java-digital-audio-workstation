package com.benesquivelmusic.daw.sdk.midi;

/**
 * Immutable representation of a MIDI event.
 *
 * <p>Supports the standard MIDI channel voice messages: note on, note off,
 * control change, program change, and pitch bend. The {@link #data2()} field
 * is unused for program-change messages and should be zero.</p>
 *
 * @param type    the MIDI event type
 * @param channel the MIDI channel (0–15)
 * @param data1   first data byte — note number (0–127), controller number, or program number
 * @param data2   second data byte — velocity (0–127), controller value, or pitch-bend value (0–16383)
 */
public record MidiEvent(Type type, int channel, int data1, int data2) {

    /** Maximum number of MIDI channels. */
    public static final int MAX_CHANNELS = 16;

    /** Maximum value for a 7-bit MIDI data byte. */
    public static final int MAX_DATA_VALUE = 127;

    /** Maximum value for a 14-bit pitch-bend value. */
    public static final int MAX_PITCH_BEND = 16383;

    /** Center value for pitch bend (no bend). */
    public static final int PITCH_BEND_CENTER = 8192;

    /**
     * MIDI event types supported by the SoundFont renderer.
     */
    public enum Type {
        /** Note-on event: starts a note with a given velocity. */
        NOTE_ON,
        /** Note-off event: stops a note. */
        NOTE_OFF,
        /** Control change: adjusts a MIDI controller (e.g., modulation, volume, pan). */
        CONTROL_CHANGE,
        /** Program change: selects an instrument preset on a channel. */
        PROGRAM_CHANGE,
        /** Pitch bend: shifts pitch up or down on a channel. */
        PITCH_BEND
    }

    public MidiEvent {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (channel < 0 || channel >= MAX_CHANNELS) {
            throw new IllegalArgumentException(
                    "channel must be 0–" + (MAX_CHANNELS - 1) + ": " + channel);
        }
        if (type == Type.PITCH_BEND) {
            if (data1 < 0 || data1 > MAX_PITCH_BEND) {
                throw new IllegalArgumentException(
                        "pitch bend value must be 0–" + MAX_PITCH_BEND + ": " + data1);
            }
        } else {
            if (data1 < 0 || data1 > MAX_DATA_VALUE) {
                throw new IllegalArgumentException(
                        "data1 must be 0–" + MAX_DATA_VALUE + ": " + data1);
            }
            if (data2 < 0 || data2 > MAX_DATA_VALUE) {
                throw new IllegalArgumentException(
                        "data2 must be 0–" + MAX_DATA_VALUE + ": " + data2);
            }
        }
    }

    /**
     * Creates a note-on event.
     *
     * @param channel  the MIDI channel (0–15)
     * @param note     the note number (0–127)
     * @param velocity the velocity (1–127; 0 is treated as note-off by convention)
     * @return a note-on MIDI event
     */
    public static MidiEvent noteOn(int channel, int note, int velocity) {
        return new MidiEvent(Type.NOTE_ON, channel, note, velocity);
    }

    /**
     * Creates a note-off event.
     *
     * @param channel  the MIDI channel (0–15)
     * @param note     the note number (0–127)
     * @return a note-off MIDI event
     */
    public static MidiEvent noteOff(int channel, int note) {
        return new MidiEvent(Type.NOTE_OFF, channel, note, 0);
    }

    /**
     * Creates a control-change event.
     *
     * @param channel    the MIDI channel (0–15)
     * @param controller the controller number (0–127)
     * @param value      the controller value (0–127)
     * @return a control-change MIDI event
     */
    public static MidiEvent controlChange(int channel, int controller, int value) {
        return new MidiEvent(Type.CONTROL_CHANGE, channel, controller, value);
    }

    /**
     * Creates a program-change event.
     *
     * @param channel the MIDI channel (0–15)
     * @param program the program number (0–127)
     * @return a program-change MIDI event
     */
    public static MidiEvent programChange(int channel, int program) {
        return new MidiEvent(Type.PROGRAM_CHANGE, channel, program, 0);
    }

    /**
     * Creates a pitch-bend event.
     *
     * @param channel the MIDI channel (0–15)
     * @param value   the pitch-bend value (0–16383; 8192 = center/no bend)
     * @return a pitch-bend MIDI event
     */
    public static MidiEvent pitchBend(int channel, int value) {
        return new MidiEvent(Type.PITCH_BEND, channel, value, 0);
    }
}
