package com.benesquivelmusic.daw.sdk.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable arrangement track.
 *
 * <p>A {@code Track} is the timeline-side counterpart of a
 * {@link MixerChannel}: it owns ordered references (by id) to the
 * {@link AudioClip audio clips} or {@link MidiClip MIDI clips} placed on its
 * timeline, and carries top-line transport flags (mute, solo, armed). The
 * actual clips live in the parent {@link Project} indexed by id, which
 * keeps the value graph flat and the structural-equality comparison cheap.</p>
 *
 * @param id              stable unique identifier
 * @param name            display name
 * @param type            content type carried by this track
 * @param volume          linear volume in {@code [0.0, 1.0]}
 * @param pan             stereo pan in {@code [-1.0, 1.0]}
 * @param muted           whether the track is muted
 * @param solo            whether the track is soloed
 * @param armed           whether the track is record-armed
 * @param phaseInverted   whether the track polarity is inverted
 * @param clipIds         ordered, immutable list of audio/MIDI clip ids on the track
 * @param mixerChannelId  id of the associated {@link MixerChannel}, or {@code null} if none
 */
public record Track(
        UUID id,
        String name,
        TrackType type,
        double volume,
        double pan,
        boolean muted,
        boolean solo,
        boolean armed,
        boolean phaseInverted,
        List<UUID> clipIds,
        UUID mixerChannelId) {

    public Track {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("volume must be in [0.0, 1.0]: " + volume);
        }
        if (pan < -1.0 || pan > 1.0) {
            throw new IllegalArgumentException("pan must be in [-1.0, 1.0]: " + pan);
        }
        clipIds = List.copyOf(Objects.requireNonNull(clipIds, "clipIds must not be null"));
    }

    /**
     * Creates a freshly-identified track with unity gain, centred, unmuted,
     * unsoloed, unarmed, no clips, and no associated mixer channel.
     */
    public static Track of(String name, TrackType type) {
        return new Track(UUID.randomUUID(), name, type, 1.0, 0.0,
                false, false, false, false, List.of(), null);
    }

    public Track withId(UUID id) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }

    public Track withName(String name) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }

    public Track withType(TrackType type) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }

    public Track withVolume(double volume) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }

    public Track withPan(double pan) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }

    public Track withMuted(boolean muted) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }

    public Track withSolo(boolean solo) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }

    public Track withArmed(boolean armed) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }

    public Track withPhaseInverted(boolean phaseInverted) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }

    public Track withClipIds(List<UUID> clipIds) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }

    public Track withMixerChannelId(UUID mixerChannelId) {
        return new Track(id, name, type, volume, pan, muted, solo, armed, phaseInverted, clipIds, mixerChannelId);
    }
}
