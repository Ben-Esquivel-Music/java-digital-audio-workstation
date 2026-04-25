package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.mixer.SendTap;

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
 * @param mode        the legacy send mode (pre-fader or post-fader),
 *                    retained for backwards compatibility
 * @param tap         the tap point at which the send draws audio (the
 *                    authoritative pre/post selector — see {@link SendTap})
 */
public record SendSnapshot(int targetIndex, double level, SendMode mode, SendTap tap) {

    public SendSnapshot {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(tap, "tap must not be null");
        if (targetIndex < 0) {
            throw new IllegalArgumentException("targetIndex must be >= 0: " + targetIndex);
        }
        if (level < 0.0 || level > 1.0) {
            throw new IllegalArgumentException("level must be between 0.0 and 1.0: " + level);
        }
    }

    /**
     * Legacy compatibility constructor that omits the {@link SendTap} and
     * derives it from the legacy {@link SendMode} (pre-fader stays
     * pre-fader; post-fader stays post-fader). Use the canonical
     * four-argument constructor to opt into {@link SendTap#PRE_INSERTS}.
     */
    public SendSnapshot(int targetIndex, double level, SendMode mode) {
        this(targetIndex, level, mode,
                mode == SendMode.PRE_FADER ? SendTap.PRE_FADER : SendTap.POST_FADER);
    }
}
