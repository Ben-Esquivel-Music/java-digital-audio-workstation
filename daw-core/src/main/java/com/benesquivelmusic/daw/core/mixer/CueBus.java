package com.benesquivelmusic.daw.core.mixer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An independent headphone/cue mix routed to a dedicated pair of hardware
 * outputs.
 *
 * <p>Every console and every professional DAW (Pro Tools "cue sends", Logic
 * "headphone mixes", Cubase "Studio Sends") exposes a way to build mixes
 * that are fully decoupled from the engineer's control-room mix: the singer
 * can have more reverb and less bass; the drummer can have zero reverb and
 * extra click. A {@code CueBus} is a first-class representation of one such
 * monitor mix. A session can have any number of them (one per performer) —
 * see {@link CueBusManager}.</p>
 *
 * <p>Each cue bus contains a list of {@link CueSend}s — one per track that
 * contributes to the mix — plus a {@code masterGain} applied after summing,
 * and a {@code hardwareOutputIndex} that identifies which pair of physical
 * outputs the bus feeds (e.g. outputs 3/4 for performer A, 5/6 for performer
 * B). The hardware index is a logical stereo-pair index (0-based): index
 * {@code N} is mapped by the audio engine onto physical output channels
 * {@code 2N / 2N+1} of the active {@link com.benesquivelmusic.daw.sdk.audio.AudioBackend
 * AudioBackend}.</p>
 *
 * <p>This record is deeply immutable: {@link #sends()} returns an unmodifiable
 * view, and the convenience {@code withX} methods return new instances rather
 * than mutating the existing one. {@link CueBusManager} stores the current
 * {@code CueBus} for each id and replaces it atomically whenever a send is
 * edited, giving real-time safe reads without locks.</p>
 *
 * @param id                   stable identity; preserved across save/load
 * @param label                human-readable name, shown in the cue-mix UI
 * @param hardwareOutputIndex  stereo-pair index of the physical output
 * @param sends                per-track contributions; immutable snapshot
 * @param masterGain           master gain applied after summing, {@code [0.0, 1.0]}
 */
public record CueBus(UUID id,
                     String label,
                     int hardwareOutputIndex,
                     List<CueSend> sends,
                     double masterGain) {

    public CueBus {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(sends, "sends must not be null");
        if (hardwareOutputIndex < 0) {
            throw new IllegalArgumentException(
                    "hardwareOutputIndex must be non-negative: " + hardwareOutputIndex);
        }
        if (masterGain < 0.0 || masterGain > 1.0) {
            throw new IllegalArgumentException(
                    "masterGain must be between 0.0 and 1.0: " + masterGain);
        }
        // Defensive snapshot so callers cannot mutate the backing list afterwards.
        sends = Collections.unmodifiableList(new ArrayList<>(sends));
    }

    /** Creates an empty cue bus with the given label and hardware output index. */
    public static CueBus create(String label, int hardwareOutputIndex) {
        return new CueBus(UUID.randomUUID(), label, hardwareOutputIndex, List.of(), 1.0);
    }

    /** Returns the send addressing {@code trackId}, or {@code null} if none. */
    public CueSend findSend(UUID trackId) {
        Objects.requireNonNull(trackId, "trackId must not be null");
        for (CueSend s : sends) {
            if (s.trackId().equals(trackId)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Returns a copy of this bus with {@code send} inserted or replacing any
     * existing send with the same {@code trackId}.
     */
    public CueBus withSend(CueSend send) {
        Objects.requireNonNull(send, "send must not be null");
        List<CueSend> next = new ArrayList<>(sends.size() + 1);
        boolean replaced = false;
        for (CueSend s : sends) {
            if (s.trackId().equals(send.trackId())) {
                next.add(send);
                replaced = true;
            } else {
                next.add(s);
            }
        }
        if (!replaced) {
            next.add(send);
        }
        return new CueBus(id, label, hardwareOutputIndex, next, masterGain);
    }

    /** Returns a copy of this bus with the send for {@code trackId} removed (if any). */
    public CueBus withoutSend(UUID trackId) {
        Objects.requireNonNull(trackId, "trackId must not be null");
        List<CueSend> next = new ArrayList<>(sends.size());
        for (CueSend s : sends) {
            if (!s.trackId().equals(trackId)) {
                next.add(s);
            }
        }
        return new CueBus(id, label, hardwareOutputIndex, next, masterGain);
    }

    /** Returns a copy of this bus with the given label. */
    public CueBus withLabel(String newLabel) {
        return new CueBus(id, newLabel, hardwareOutputIndex, sends, masterGain);
    }

    /** Returns a copy of this bus assigned to a different hardware output pair. */
    public CueBus withHardwareOutputIndex(int newIndex) {
        return new CueBus(id, label, newIndex, sends, masterGain);
    }

    /** Returns a copy of this bus with the given master gain. */
    public CueBus withMasterGain(double newMasterGain) {
        return new CueBus(id, label, hardwareOutputIndex, sends, newMasterGain);
    }
}
