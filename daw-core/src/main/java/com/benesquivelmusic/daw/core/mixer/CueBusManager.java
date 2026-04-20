package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages every {@link CueBus} in a session and renders each one into an
 * independent stereo output buffer.
 *
 * <p>Rendering is allocation-free on the audio thread: given a map of
 * per-track <em>pre-fader</em> and <em>post-fader</em> channel buffers
 * (keyed by track id), {@link #renderCueBus(CueBus, Map, Map, float[][], int)}
 * sums the appropriate taps into the destination stereo buffer using each
 * {@link CueSend}'s gain, pan, and pre-fader flag. Pre-fader sends tap
 * straight out of the pre-fader buffer and therefore ignore moves on the
 * main control-room fader — this is what makes cue mixes stable while the
 * engineer is chasing a mix during tracking.</p>
 *
 * <p>The manager enforces unique {@code hardwareOutputIndex} values: no two
 * cue buses may be routed to the same physical output pair.</p>
 */
public final class CueBusManager {

    private final Map<UUID, CueBus> busses = new HashMap<>();
    private final List<UUID> order = new ArrayList<>();

    /** Returns every cue bus in insertion order. */
    public List<CueBus> getCueBusses() {
        List<CueBus> out = new ArrayList<>(order.size());
        for (UUID id : order) {
            out.add(busses.get(id));
        }
        return Collections.unmodifiableList(out);
    }

    /** Looks up a cue bus by its stable id, or returns {@code null}. */
    public CueBus getById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return busses.get(id);
    }

    /** Returns {@code true} if any cue bus is routed to {@code hardwareOutputIndex}. */
    public boolean isHardwareOutputInUse(int hardwareOutputIndex) {
        for (CueBus b : busses.values()) {
            if (b.hardwareOutputIndex() == hardwareOutputIndex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates and registers a new cue bus with the given label and hardware
     * output pair.
     *
     * @throws IllegalArgumentException if {@code hardwareOutputIndex} is
     *                                  already assigned to another cue bus
     */
    public CueBus createCueBus(String label, int hardwareOutputIndex) {
        if (isHardwareOutputInUse(hardwareOutputIndex)) {
            throw new IllegalArgumentException(
                    "hardware output " + hardwareOutputIndex + " is already assigned to a cue bus");
        }
        CueBus bus = CueBus.create(label, hardwareOutputIndex);
        addCueBus(bus);
        return bus;
    }

    /**
     * Registers an existing {@link CueBus}. Used by undo and deserialization.
     *
     * @throws IllegalArgumentException if a bus with the same id is already
     *                                  registered, or if its hardware output
     *                                  index is already in use
     */
    public void addCueBus(CueBus bus) {
        Objects.requireNonNull(bus, "bus must not be null");
        if (busses.containsKey(bus.id())) {
            throw new IllegalArgumentException("cue bus already registered: " + bus.id());
        }
        if (isHardwareOutputInUse(bus.hardwareOutputIndex())) {
            throw new IllegalArgumentException(
                    "hardware output " + bus.hardwareOutputIndex() + " is already assigned to a cue bus");
        }
        busses.put(bus.id(), bus);
        order.add(bus.id());
    }

    /** Removes the cue bus with the given id. Returns {@code true} if it was present. */
    public boolean removeCueBus(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        if (busses.remove(id) == null) {
            return false;
        }
        order.remove(id);
        return true;
    }

    /**
     * Replaces the stored cue bus atomically with {@code updated}. The bus
     * must already be registered under {@code updated.id()} and must not
     * change its hardware output index to one in use by another bus.
     */
    public void replace(CueBus updated) {
        Objects.requireNonNull(updated, "updated must not be null");
        CueBus existing = busses.get(updated.id());
        if (existing == null) {
            throw new IllegalArgumentException("cue bus not registered: " + updated.id());
        }
        if (existing.hardwareOutputIndex() != updated.hardwareOutputIndex()) {
            for (CueBus b : busses.values()) {
                if (b != existing && b.hardwareOutputIndex() == updated.hardwareOutputIndex()) {
                    throw new IllegalArgumentException(
                            "hardware output " + updated.hardwareOutputIndex()
                                    + " is already assigned to a cue bus");
                }
            }
        }
        busses.put(updated.id(), updated);
    }

    /**
     * Pre-fills the cue bus identified by {@code cueBusId} with a send for
     * every entry in {@code mainMix}, using each channel's current volume as
     * the send gain and current pan as the send pan. The sends default to
     * {@code preFader=true} — standard behavior during tracking so that
     * subsequent engineer fader moves do not disturb the performer's mix.
     *
     * @param cueBusId id of a registered cue bus
     * @param mainMix  per-track main-mix snapshot (volume, pan)
     */
    public void copyMainMix(UUID cueBusId, Map<UUID, MainMixLevel> mainMix) {
        Objects.requireNonNull(cueBusId, "cueBusId must not be null");
        Objects.requireNonNull(mainMix, "mainMix must not be null");
        CueBus bus = busses.get(cueBusId);
        if (bus == null) {
            throw new IllegalArgumentException("cue bus not registered: " + cueBusId);
        }
        for (Map.Entry<UUID, MainMixLevel> e : mainMix.entrySet()) {
            MainMixLevel level = e.getValue();
            bus = bus.withSend(new CueSend(e.getKey(), level.gain(), level.pan(), true));
        }
        busses.put(cueBusId, bus);
    }

    /**
     * Snapshot of the main-mix state for one track, used by
     * {@link #copyMainMix(UUID, Map)}.
     *
     * @param gain linear gain, {@code [0.0, 1.0]}
     * @param pan  stereo pan, {@code [-1.0, 1.0]}
     */
    public record MainMixLevel(double gain, double pan) {
        public MainMixLevel {
            if (gain < 0.0 || gain > 1.0) {
                throw new IllegalArgumentException("gain must be between 0.0 and 1.0: " + gain);
            }
            if (pan < -1.0 || pan > 1.0) {
                throw new IllegalArgumentException("pan must be between -1.0 and 1.0: " + pan);
            }
        }
    }

    /**
     * Sums every {@link CueSend} of {@code bus} into {@code out}, a stereo
     * buffer shaped {@code [2][numFrames]}. Sends tap {@code preFaderBuffers}
     * when {@link CueSend#preFader()} is {@code true}, and
     * {@code postFaderBuffers} otherwise. Missing buffer entries are treated
     * as silence — this lets the audio engine skip recording tracks cheaply.
     *
     * <p>This method performs no allocations and acquires no locks; it is
     * safe to call on the real-time audio thread.</p>
     *
     * @param bus               the cue bus to render
     * @param preFaderBuffers   pre-fader per-track audio, each value is
     *                          shaped {@code [audioChannels][frames]}; may
     *                          contain mono ({@code audioChannels==1}) or
     *                          stereo sources
     * @param postFaderBuffers  post-fader (volume + pan applied) per-track
     *                          audio, same shape as {@code preFaderBuffers}
     * @param out               destination stereo buffer, {@code [2][>=numFrames]}
     * @param numFrames         number of sample frames to mix
     */
    @RealTimeSafe
    public void renderCueBus(CueBus bus,
                             Map<UUID, float[][]> preFaderBuffers,
                             Map<UUID, float[][]> postFaderBuffers,
                             float[][] out,
                             int numFrames) {
        Objects.requireNonNull(bus, "bus must not be null");
        Objects.requireNonNull(out, "out must not be null");
        if (out.length < 2) {
            throw new IllegalArgumentException("out must have at least 2 channels");
        }
        // Clear destination stereo buffer.
        for (int f = 0; f < numFrames; f++) {
            out[0][f] = 0.0f;
            out[1][f] = 0.0f;
        }
        float master = (float) bus.masterGain();
        if (master <= 0.0f) {
            return;
        }
        for (CueSend send : bus.sends()) {
            float gain = (float) send.gain();
            if (gain <= 0.0f) {
                continue;
            }
            Map<UUID, float[][]> src = send.preFader() ? preFaderBuffers : postFaderBuffers;
            if (src == null) {
                continue;
            }
            float[][] buf = src.get(send.trackId());
            if (buf == null || buf.length == 0) {
                continue;
            }
            // Equal-power pan: leftGain = cos(θ), rightGain = sin(θ),
            // θ = (pan+1) * π/4 so θ=π/4 at center yields 0.707/0.707.
            double theta = (send.pan() + 1.0) * (Math.PI / 4.0);
            float leftGain = (float) (Math.cos(theta) * gain * master);
            float rightGain = (float) (Math.sin(theta) * gain * master);
            float[] srcL = buf[0];
            float[] srcR = buf.length > 1 ? buf[1] : buf[0];
            for (int f = 0; f < numFrames; f++) {
                out[0][f] += srcL[f] * leftGain;
                out[1][f] += srcR[f] * rightGain;
            }
        }
    }
}
