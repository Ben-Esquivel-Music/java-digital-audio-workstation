package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.mixer.OutputRouting;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of the state of a single mixer channel (track channel,
 * return bus, or master).
 *
 * <p>Captures the scalar channel values plus the state of each insert slot
 * and send. This is the per-channel building block of {@link MixerSnapshot}.</p>
 *
 * @param volume         the fader level (0.0 – 1.0)
 * @param pan            the pan position (−1.0 to 1.0)
 * @param muted          the mute state
 * @param solo           the solo state
 * @param phaseInverted  the phase-invert state
 * @param sendLevel      the legacy single-send level (0.0 – 1.0)
 * @param outputRouting  the output routing (never {@code null})
 * @param inserts        per-insert-slot state, in slot order (defensively copied, unmodifiable)
 * @param sends          per-send state, in send order (defensively copied, unmodifiable)
 */
public record ChannelSnapshot(double volume,
                              double pan,
                              boolean muted,
                              boolean solo,
                              boolean phaseInverted,
                              double sendLevel,
                              OutputRouting outputRouting,
                              List<InsertSnapshot> inserts,
                              List<SendSnapshot> sends) {

    public ChannelSnapshot {
        Objects.requireNonNull(outputRouting, "outputRouting must not be null");
        Objects.requireNonNull(inserts, "inserts must not be null");
        Objects.requireNonNull(sends, "sends must not be null");
        if (volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("volume must be between 0.0 and 1.0: " + volume);
        }
        if (pan < -1.0 || pan > 1.0) {
            throw new IllegalArgumentException("pan must be between -1.0 and 1.0: " + pan);
        }
        if (sendLevel < 0.0 || sendLevel > 1.0) {
            throw new IllegalArgumentException("sendLevel must be between 0.0 and 1.0: " + sendLevel);
        }
        inserts = List.copyOf(inserts);
        sends = List.copyOf(sends);
    }
}
