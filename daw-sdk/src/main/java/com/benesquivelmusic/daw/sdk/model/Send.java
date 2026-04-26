package com.benesquivelmusic.daw.sdk.model;

import java.util.Objects;
import java.util.UUID;

/**
 * An immutable send routing from a {@link MixerChannel} to a {@link Return}
 * bus.
 *
 * <p>Sends carry only their own state — level, tap point, and the id of the
 * target return — never references to mutable mixer or routing graph
 * objects. The full routing graph is reconstructed on demand by the audio
 * engine from the {@link Project} snapshot.</p>
 *
 * @param id        stable unique identifier
 * @param targetId  id of the target {@link Return} bus
 * @param level     send level in the linear range {@code [0.0, 1.0]}
 * @param tap       point in the channel's signal flow at which the send is taken
 */
public record Send(UUID id, UUID targetId, double level, SendTap tap) {

    public Send {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(tap, "tap must not be null");
        if (level < 0.0 || level > 1.0) {
            throw new IllegalArgumentException("level must be in [0.0, 1.0]: " + level);
        }
    }

    /** Creates a freshly-identified, post-fader send at zero level. */
    public static Send of(UUID targetId) {
        return new Send(UUID.randomUUID(), targetId, 0.0, SendTap.POST_FADER);
    }

    public Send withId(UUID id) {
        return new Send(id, targetId, level, tap);
    }

    public Send withTargetId(UUID targetId) {
        return new Send(id, targetId, level, tap);
    }

    public Send withLevel(double level) {
        return new Send(id, targetId, level, tap);
    }

    public Send withTap(SendTap tap) {
        return new Send(id, targetId, level, tap);
    }
}
