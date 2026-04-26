package com.benesquivelmusic.daw.sdk.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable mixer channel strip.
 *
 * <p>A {@code MixerChannel} carries the per-channel mix state — volume, pan,
 * mute/solo flags, phase invert, and the ordered list of {@link Send sends}
 * that route to {@link Return} buses. Insert effect plugins, plugin
 * supervisors, CPU budgets and runtime callbacks all live in the audio
 * engine layer (the legacy mutable
 * {@code com.benesquivelmusic.daw.core.mixer.MixerChannel}) and are not part
 * of the value identity.</p>
 *
 * @param id            stable unique identifier
 * @param name          display name
 * @param volume        linear volume in {@code [0.0, 1.0]}
 * @param pan           stereo pan in {@code [-1.0, 1.0]}
 * @param muted         whether the channel is muted
 * @param solo          whether the channel is soloed
 * @param phaseInverted whether the channel polarity is inverted
 * @param sends         ordered, immutable list of sends from this channel
 */
public record MixerChannel(
        UUID id,
        String name,
        double volume,
        double pan,
        boolean muted,
        boolean solo,
        boolean phaseInverted,
        List<Send> sends) {

    public MixerChannel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("volume must be in [0.0, 1.0]: " + volume);
        }
        if (pan < -1.0 || pan > 1.0) {
            throw new IllegalArgumentException("pan must be in [-1.0, 1.0]: " + pan);
        }
        sends = List.copyOf(Objects.requireNonNull(sends, "sends must not be null"));
    }

    /**
     * Creates a freshly-identified mixer channel with unity gain, centred,
     * unmuted, and no sends.
     */
    public static MixerChannel of(String name) {
        return new MixerChannel(UUID.randomUUID(), name, 1.0, 0.0, false, false, false, List.of());
    }

    public MixerChannel withId(UUID id) {
        return new MixerChannel(id, name, volume, pan, muted, solo, phaseInverted, sends);
    }

    public MixerChannel withName(String name) {
        return new MixerChannel(id, name, volume, pan, muted, solo, phaseInverted, sends);
    }

    public MixerChannel withVolume(double volume) {
        return new MixerChannel(id, name, volume, pan, muted, solo, phaseInverted, sends);
    }

    public MixerChannel withPan(double pan) {
        return new MixerChannel(id, name, volume, pan, muted, solo, phaseInverted, sends);
    }

    public MixerChannel withMuted(boolean muted) {
        return new MixerChannel(id, name, volume, pan, muted, solo, phaseInverted, sends);
    }

    public MixerChannel withSolo(boolean solo) {
        return new MixerChannel(id, name, volume, pan, muted, solo, phaseInverted, sends);
    }

    public MixerChannel withPhaseInverted(boolean phaseInverted) {
        return new MixerChannel(id, name, volume, pan, muted, solo, phaseInverted, sends);
    }

    public MixerChannel withSends(List<Send> sends) {
        return new MixerChannel(id, name, volume, pan, muted, solo, phaseInverted, sends);
    }
}
