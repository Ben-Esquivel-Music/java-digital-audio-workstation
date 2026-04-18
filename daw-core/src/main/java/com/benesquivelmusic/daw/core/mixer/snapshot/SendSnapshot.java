package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.mixer.SendMode;

import java.util.Objects;

/**
 * Immutable snapshot of the state of a single send on a mixer channel.
 *
 * <p>The target is captured by its index in the mixer's return-bus list so
 * that the snapshot is independent of object identity and survives
 * serialization round-trips.</p>
 *
 * @param targetIndex zero-based index of the target return bus in the mixer
 * @param level       the send level (0.0 – 1.0)
 * @param mode        the send mode (pre-fader or post-fader)
 */
public record SendSnapshot(int targetIndex, double level, SendMode mode) {

    public SendSnapshot {
        Objects.requireNonNull(mode, "mode must not be null");
        if (targetIndex < 0) {
            throw new IllegalArgumentException("targetIndex must be >= 0: " + targetIndex);
        }
        if (level < 0.0 || level > 1.0) {
            throw new IllegalArgumentException("level must be between 0.0 and 1.0: " + level);
        }
    }
}
