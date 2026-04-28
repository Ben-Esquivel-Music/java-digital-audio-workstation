package com.benesquivelmusic.daw.core.plugin.builtin.midi;

import java.util.Objects;

/**
 * Immutable, sample-accurate MIDI message used by the MIDI-effect chain.
 *
 * <p>This is a minimal carrier for the small subset of MIDI events that the
 * built-in MIDI effects (such as the arpeggiator) consume and emit. Each
 * message carries a {@code sampleOffset} expressed in frames within the
 * current processing block — this allows {@link MidiEffectPlugin#process}
 * implementations to schedule outgoing notes at the correct sub-block
 * timing without an external timing API.</p>
 *
 * <p>Two convenience factory methods are provided for the most common
 * cases — note-on and note-off — which are the only events the
 * {@code ArpeggiatorPlugin} currently emits.</p>
 *
 * @param type         the MIDI message type
 * @param channel      the MIDI channel (0–15)
 * @param data1        first data byte (note number, controller, program, …)
 * @param data2        second data byte (velocity, controller value, …)
 * @param sampleOffset frame offset within the current block
 */
public record MidiMessage(Type type, int channel, int data1, int data2, int sampleOffset) {

    /** Maximum number of MIDI channels (0–15). */
    public static final int MAX_CHANNELS = 16;

    /** Maximum value for a 7-bit data byte. */
    public static final int MAX_DATA_VALUE = 127;

    /**
     * MIDI message types accepted by the {@link MidiEffectPlugin} contract.
     */
    public enum Type {
        /** Note-on: starts a note. */
        NOTE_ON,
        /** Note-off: stops a note. */
        NOTE_OFF,
        /** Control change. */
        CONTROL_CHANGE,
        /** Program change. */
        PROGRAM_CHANGE,
        /** Pitch bend. */
        PITCH_BEND
    }

    /**
     * Compact constructor — validates the channel/data ranges so that
     * downstream consumers (the synth renderer) never see an out-of-range
     * value emitted by a buggy effect.
     */
    public MidiMessage {
        Objects.requireNonNull(type, "type must not be null");
        if (channel < 0 || channel >= MAX_CHANNELS) {
            throw new IllegalArgumentException(
                    "channel must be 0–" + (MAX_CHANNELS - 1) + ": " + channel);
        }
        if (data1 < 0 || data1 > MAX_DATA_VALUE) {
            throw new IllegalArgumentException("data1 out of range: " + data1);
        }
        if (data2 < 0 || data2 > MAX_DATA_VALUE) {
            throw new IllegalArgumentException("data2 out of range: " + data2);
        }
        if (sampleOffset < 0) {
            throw new IllegalArgumentException("sampleOffset must be ≥ 0: " + sampleOffset);
        }
    }

    /** Convenience: create a note-on message at the given sample offset. */
    public static MidiMessage noteOn(int channel, int note, int velocity, int sampleOffset) {
        return new MidiMessage(Type.NOTE_ON, channel, note, velocity, sampleOffset);
    }

    /** Convenience: create a note-off message at the given sample offset. */
    public static MidiMessage noteOff(int channel, int note, int sampleOffset) {
        return new MidiMessage(Type.NOTE_OFF, channel, note, 0, sampleOffset);
    }
}
