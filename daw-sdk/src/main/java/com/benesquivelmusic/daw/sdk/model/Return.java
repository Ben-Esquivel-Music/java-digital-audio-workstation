package com.benesquivelmusic.daw.sdk.model;

import java.util.Objects;
import java.util.UUID;

/**
 * An immutable return bus.
 *
 * <p>A {@code Return} is the destination of one or more {@link Send sends}.
 * Like every other type in this package it carries no behaviour and no
 * references to engine objects — only the pure data that describes the
 * bus.</p>
 *
 * @param id     stable unique identifier
 * @param name   display name
 * @param volume linear volume in {@code [0.0, 1.0]}
 * @param pan    stereo pan in {@code [-1.0, 1.0]}
 * @param muted  whether the bus is muted
 */
public record Return(UUID id, String name, double volume, double pan, boolean muted) {

    public Return {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("volume must be in [0.0, 1.0]: " + volume);
        }
        if (pan < -1.0 || pan > 1.0) {
            throw new IllegalArgumentException("pan must be in [-1.0, 1.0]: " + pan);
        }
    }

    /** Creates a freshly-identified return bus with unity gain, centred and unmuted. */
    public static Return of(String name) {
        return new Return(UUID.randomUUID(), name, 1.0, 0.0, false);
    }

    public Return withId(UUID id) {
        return new Return(id, name, volume, pan, muted);
    }

    public Return withName(String name) {
        return new Return(id, name, volume, pan, muted);
    }

    public Return withVolume(double volume) {
        return new Return(id, name, volume, pan, muted);
    }

    public Return withPan(double pan) {
        return new Return(id, name, volume, pan, muted);
    }

    public Return withMuted(boolean muted) {
        return new Return(id, name, volume, pan, muted);
    }
}
