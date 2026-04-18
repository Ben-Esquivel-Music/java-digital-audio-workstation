package com.benesquivelmusic.daw.core.template;

import java.util.List;
import java.util.Objects;

/**
 * A reusable preset capturing the mixer channel state — insert effects, sends,
 * volume, and pan — independent of any particular track.
 *
 * <p>Unlike {@link TrackTemplate}, a channel strip preset is applied to an
 * <em>existing</em> mixer channel rather than used to create a new track. It
 * replaces the channel's current insert chain, its current sends, and sets the
 * volume/pan to the preset's values.</p>
 *
 * @param presetName the display name of this preset (for menus and pickers)
 * @param inserts    the ordered insert effect specs (stored as an unmodifiable copy)
 * @param sends      the send specs (stored as an unmodifiable copy)
 * @param volume     the volume in {@code [0.0, 1.0]}
 * @param pan        the pan in {@code [-1.0, 1.0]}
 */
public record ChannelStripPreset(String presetName,
                                 List<InsertEffectSpec> inserts,
                                 List<SendSpec> sends,
                                 double volume,
                                 double pan) {

    public ChannelStripPreset {
        Objects.requireNonNull(presetName, "presetName must not be null");
        Objects.requireNonNull(inserts, "inserts must not be null");
        Objects.requireNonNull(sends, "sends must not be null");
        if (presetName.isBlank()) {
            throw new IllegalArgumentException("presetName must not be blank");
        }
        if (volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("volume must be between 0.0 and 1.0: " + volume);
        }
        if (pan < -1.0 || pan > 1.0) {
            throw new IllegalArgumentException("pan must be between -1.0 and 1.0: " + pan);
        }
        inserts = List.copyOf(inserts);
        sends = List.copyOf(sends);
    }
}
