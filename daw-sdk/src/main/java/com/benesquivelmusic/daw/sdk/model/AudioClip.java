package com.benesquivelmusic.daw.sdk.model;

import java.util.Objects;
import java.util.UUID;

/**
 * An immutable audio clip placed on a track's timeline.
 *
 * <p>This record is the value-oriented replacement for the legacy mutable
 * {@code com.benesquivelmusic.daw.core.audio.AudioClip}. It carries only the
 * pure-data state of the clip; large auxiliary buffers
 * (e.g. raw {@code float[][]} sample arrays) and behavioural concerns
 * (rendering, fades, time-stretch engines) are owned by the audio engine
 * and are not part of the value identity.</p>
 *
 * <p>Each field has a corresponding {@code withX(...)} method that returns a
 * new {@code AudioClip} with that field updated. Because records are
 * structurally compared, two {@code AudioClip} instances are equal when all
 * fields are equal — perfect for snapshot-based undo/redo and lock-free
 * concurrent reads.</p>
 *
 * @param id              stable unique identifier
 * @param name            display name
 * @param startBeat       timeline start position in beats (must be {@code >= 0})
 * @param durationBeats   length on the timeline in beats (must be {@code > 0})
 * @param sourceOffsetBeats offset into the source audio in beats
 * @param sourceFilePath  path to the source audio file, or {@code null} for in-memory clips
 * @param gainDb          clip gain in decibels
 * @param reversed        whether the clip is played reversed
 * @param locked          whether the clip is time-locked against position changes
 * @param fadeInBeats     fade-in length in beats (must be {@code >= 0})
 * @param fadeOutBeats    fade-out length in beats (must be {@code >= 0})
 */
public record AudioClip(
        UUID id,
        String name,
        double startBeat,
        double durationBeats,
        double sourceOffsetBeats,
        String sourceFilePath,
        double gainDb,
        boolean reversed,
        boolean locked,
        double fadeInBeats,
        double fadeOutBeats) {

    public AudioClip {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (startBeat < 0) {
            throw new IllegalArgumentException("startBeat must not be negative: " + startBeat);
        }
        if (durationBeats <= 0) {
            throw new IllegalArgumentException("durationBeats must be positive: " + durationBeats);
        }
        if (fadeInBeats < 0) {
            throw new IllegalArgumentException("fadeInBeats must not be negative: " + fadeInBeats);
        }
        if (fadeOutBeats < 0) {
            throw new IllegalArgumentException("fadeOutBeats must not be negative: " + fadeOutBeats);
        }
    }

    /**
     * Creates a freshly-identified clip with sensible defaults
     * (no offset, 0 dB gain, no fades, not reversed, not locked).
     */
    public static AudioClip of(String name, double startBeat, double durationBeats, String sourceFilePath) {
        return new AudioClip(UUID.randomUUID(), name, startBeat, durationBeats,
                0.0, sourceFilePath, 0.0, false, false, 0.0, 0.0);
    }

    /** Returns the timeline end-beat ({@code startBeat + durationBeats}). */
    public double endBeat() {
        return startBeat + durationBeats;
    }

    public AudioClip withId(UUID id) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }

    public AudioClip withName(String name) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }

    public AudioClip withStartBeat(double startBeat) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }

    public AudioClip withDurationBeats(double durationBeats) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }

    public AudioClip withSourceOffsetBeats(double sourceOffsetBeats) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }

    public AudioClip withSourceFilePath(String sourceFilePath) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }

    public AudioClip withGainDb(double gainDb) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }

    public AudioClip withReversed(boolean reversed) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }

    public AudioClip withLocked(boolean locked) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }

    public AudioClip withFadeInBeats(double fadeInBeats) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }

    public AudioClip withFadeOutBeats(double fadeOutBeats) {
        return new AudioClip(id, name, startBeat, durationBeats, sourceOffsetBeats,
                sourceFilePath, gainDb, reversed, locked, fadeInBeats, fadeOutBeats);
    }
}
