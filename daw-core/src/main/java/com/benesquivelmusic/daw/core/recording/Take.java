package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.audio.AudioClip;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single take (capture pass) produced by loop-record.
 *
 * <p>Each take references a distinct {@link AudioClip} and records the wall-clock
 * {@link Instant} at which the pass was captured. Takes are grouped together in
 * a {@link TakeGroup}; only the group's {@code activeIndex} take is played back
 * on the track lane at any one time.</p>
 *
 * @param id        stable identifier for this take (persisted with the project)
 * @param clip      the captured audio clip (each take is a distinct asset)
 * @param capturedAt wall-clock time at which this take was finalized
 */
public record Take(UUID id, AudioClip clip, Instant capturedAt) {

    public Take {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(clip, "clip must not be null");
        Objects.requireNonNull(capturedAt, "capturedAt must not be null");
    }

    /**
     * Convenience factory that assigns a fresh {@link UUID} and captures the
     * current {@link Instant}.
     */
    public static Take of(AudioClip clip) {
        return new Take(UUID.randomUUID(), clip, Instant.now());
    }
}
