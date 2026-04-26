package com.benesquivelmusic.daw.sdk.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable MIDI clip placed on a track's timeline.
 *
 * <p>The note list is defensively copied with {@link List#copyOf(java.util.Collection)}
 * so a {@code MidiClip} value cannot be mutated even if a caller retains a
 * reference to the original input list.</p>
 *
 * @param id            stable unique identifier
 * @param name          display name
 * @param startBeat     timeline start position in beats (must be {@code >= 0})
 * @param durationBeats length on the timeline in beats (must be {@code > 0})
 * @param notes         immutable list of MIDI note events (relative to the clip)
 */
public record MidiClip(
        UUID id,
        String name,
        double startBeat,
        double durationBeats,
        List<MidiNote> notes) {

    public MidiClip {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (startBeat < 0) {
            throw new IllegalArgumentException("startBeat must not be negative: " + startBeat);
        }
        if (durationBeats <= 0) {
            throw new IllegalArgumentException("durationBeats must be positive: " + durationBeats);
        }
        notes = List.copyOf(Objects.requireNonNull(notes, "notes must not be null"));
    }

    /** Creates a freshly-identified empty MIDI clip. */
    public static MidiClip of(String name, double startBeat, double durationBeats) {
        return new MidiClip(UUID.randomUUID(), name, startBeat, durationBeats, List.of());
    }

    /** Returns the timeline end-beat ({@code startBeat + durationBeats}). */
    public double endBeat() {
        return startBeat + durationBeats;
    }

    public MidiClip withId(UUID id) {
        return new MidiClip(id, name, startBeat, durationBeats, notes);
    }

    public MidiClip withName(String name) {
        return new MidiClip(id, name, startBeat, durationBeats, notes);
    }

    public MidiClip withStartBeat(double startBeat) {
        return new MidiClip(id, name, startBeat, durationBeats, notes);
    }

    public MidiClip withDurationBeats(double durationBeats) {
        return new MidiClip(id, name, startBeat, durationBeats, notes);
    }

    public MidiClip withNotes(List<MidiNote> notes) {
        return new MidiClip(id, name, startBeat, durationBeats, notes);
    }
}
