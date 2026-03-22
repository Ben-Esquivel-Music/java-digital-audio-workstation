package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;

import java.util.Objects;

/**
 * A bed channel assignment that routes audio to a fixed speaker position.
 *
 * <p>In Dolby Atmos workflows, bed channels represent audio that is assigned
 * to a specific speaker in the monitoring layout (e.g. Left, Center, LFE).
 * Unlike audio objects, bed channels do not carry 3D position metadata —
 * they are routed directly to their assigned speaker.</p>
 *
 * @param trackId      the unique identifier of the associated track
 * @param speakerLabel the speaker position this bed channel is assigned to
 * @param gain         the bed channel gain in linear scale [0.0, 1.0]
 */
public record BedChannel(String trackId, SpeakerLabel speakerLabel, double gain) {

    public BedChannel {
        Objects.requireNonNull(trackId, "trackId must not be null");
        Objects.requireNonNull(speakerLabel, "speakerLabel must not be null");
        if (gain < 0.0 || gain > 1.0) {
            throw new IllegalArgumentException("gain must be in [0.0, 1.0]: " + gain);
        }
    }

    /**
     * Creates a bed channel with unity gain.
     *
     * @param trackId      the track identifier
     * @param speakerLabel the target speaker
     */
    public BedChannel(String trackId, SpeakerLabel speakerLabel) {
        this(trackId, speakerLabel, 1.0);
    }
}
