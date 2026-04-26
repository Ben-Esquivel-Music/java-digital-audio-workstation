package com.benesquivelmusic.daw.sdk.model;

/**
 * An immutable MIDI note event inside a {@link MidiClip}.
 *
 * @param startBeat    timeline-relative start (in beats) within the clip
 * @param durationBeats note length in beats (must be {@code > 0})
 * @param pitch        MIDI pitch (0-127)
 * @param velocity     MIDI velocity (0-127)
 */
public record MidiNote(double startBeat, double durationBeats, int pitch, int velocity) {

    public MidiNote {
        if (startBeat < 0) {
            throw new IllegalArgumentException("startBeat must not be negative: " + startBeat);
        }
        if (durationBeats <= 0) {
            throw new IllegalArgumentException("durationBeats must be positive: " + durationBeats);
        }
        if (pitch < 0 || pitch > 127) {
            throw new IllegalArgumentException("pitch must be in [0, 127]: " + pitch);
        }
        if (velocity < 0 || velocity > 127) {
            throw new IllegalArgumentException("velocity must be in [0, 127]: " + velocity);
        }
    }

    public MidiNote withStartBeat(double startBeat) {
        return new MidiNote(startBeat, durationBeats, pitch, velocity);
    }

    public MidiNote withDurationBeats(double durationBeats) {
        return new MidiNote(startBeat, durationBeats, pitch, velocity);
    }

    public MidiNote withPitch(int pitch) {
        return new MidiNote(startBeat, durationBeats, pitch, velocity);
    }

    public MidiNote withVelocity(int velocity) {
        return new MidiNote(startBeat, durationBeats, pitch, velocity);
    }
}
