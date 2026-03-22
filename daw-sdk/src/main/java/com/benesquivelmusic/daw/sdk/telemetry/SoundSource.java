package com.benesquivelmusic.daw.sdk.telemetry;

import java.util.Objects;

/**
 * Immutable description of a sound source in a room.
 *
 * <p>Represents a physical sound-emitting entity such as an instrument,
 * speaker, or vocalist at a specific location in the room.</p>
 *
 * @param name     a descriptive label for this source
 * @param position the 3D position of the source in the room
 * @param powerDb  the emitted sound power level in dB SPL
 */
public record SoundSource(String name, Position3D position, double powerDb) {

    public SoundSource {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(position, "position must not be null");
    }
}
