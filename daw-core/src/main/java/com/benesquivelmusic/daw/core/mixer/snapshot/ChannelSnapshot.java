package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of the state of a single mixer channel (track channel,
 * return bus, or master).
 *
 * <p>Captures the scalar channel values plus the state of each insert slot
 * and send. This is the per-channel building block of {@link MixerSnapshot}.
 * Story 322 removed the legacy scalar "send level" — send state is carried
 * only by {@link #sends()}, one {@link SendSnapshot} per {@code Send},
 * keyed by target return bus.</p>
 *
 * @param volume         the fader level (0.0 – 1.0)
 * @param pan            the pan position (−1.0 to 1.0)
 * @param muted          the mute state
 * @param solo           the solo state
 * @param phaseInverted  the phase-invert state
 * @param outputRouting  the output routing (never {@code null})
 * @param inserts        per-insert-slot state, in slot order (defensively copied, unmodifiable)
 * @param sends          per-send state, keyed by target bus (defensively copied, unmodifiable)
 * @param cpuBudget      the per-track CPU budget, or {@code null} if not configured
 */
public record ChannelSnapshot(double volume,
                              double pan,
                              boolean muted,
                              boolean solo,
                              boolean phaseInverted,
                              OutputRouting outputRouting,
                              List<InsertSnapshot> inserts,
                              List<SendSnapshot> sends,
                              TrackCpuBudget cpuBudget) {

    /**
     * Backward-compatible constructor for snapshots that do not carry a
     * CPU budget (pre-issue-553 callers, deserialized legacy projects).
     */
    public ChannelSnapshot(double volume, double pan, boolean muted,
                           boolean solo, boolean phaseInverted,
                           OutputRouting outputRouting,
                           List<InsertSnapshot> inserts,
                           List<SendSnapshot> sends) {
        this(volume, pan, muted, solo, phaseInverted,
             outputRouting, inserts, sends, null);
    }

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
        inserts = List.copyOf(inserts);
        sends = List.copyOf(sends);
    }
}
