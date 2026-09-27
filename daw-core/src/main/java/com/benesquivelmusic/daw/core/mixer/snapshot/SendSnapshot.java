package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.mixer.SendTap;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of the state of a single send on a mixer channel.
 *
 * <p>Story 322 — the target is captured by the return bus's stable
 * {@link com.benesquivelmusic.daw.core.mixer.MixerChannel#getId() id} and
 * {@link MixerSnapshot#applyTo(com.benesquivelmusic.daw.core.mixer.Mixer)}
 * restores it <em>by target</em>: the channel's {@code Send} aimed at that
 * bus receives the level and tap, whatever its position in the channel's
 * send list. The pre-322 snapshot was index-aligned on the send list and
 * carried a separate legacy scalar "send level" that no render path read;
 * both are gone. Persistence writes the id alongside the bus's index in the
 * mixer's return-bus list so a saved snapshot still resolves after a reload
 * regenerates bus ids (see {@code ProjectSerializer}).</p>
 *
 * @param targetId the id of the target return bus
 * @param level    the send level (0.0 – 1.0)
 * @param tap      the tap point at which the send draws audio (the
 *                 authoritative pre/post selector — see {@link SendTap})
 */
public record SendSnapshot(UUID targetId, double level, SendTap tap) {

    public SendSnapshot {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(tap, "tap must not be null");
        if (level < 0.0 || level > 1.0) {
            throw new IllegalArgumentException("level must be between 0.0 and 1.0: " + level);
        }
    }
}
