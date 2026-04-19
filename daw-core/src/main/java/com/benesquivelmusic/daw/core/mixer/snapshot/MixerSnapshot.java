package com.benesquivelmusic.daw.core.mixer.snapshot;

import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Immutable snapshot of the complete state of a {@link Mixer} at a point in time.
 *
 * <p>A snapshot captures all values an engineer expects to be restored when
 * recalling a mix — per-channel volume, pan, mute, solo, phase-invert, send
 * levels, output routing, the bypass state and parameter values of every
 * insert effect, plus all send levels and modes. Master, track channels, and
 * return buses are all captured.</p>
 *
 * <p>Snapshots are purely declarative <strong>scalar</strong> data carriers.
 * They do not attempt to reconstruct mixer <em>structure</em> (adding or
 * removing channels, adding or removing inserts) — only the values of an
 * existing mixer are updated by {@link #applyTo(Mixer)}. If the mixer
 * structure has diverged from the snapshot (for example, an insert has been
 * removed), {@code applyTo} applies as much as matches by index and silently
 * skips the rest.</p>
 *
 * <p>Snapshots are serialized as part of the project file so that saved
 * snapshots persist across save/load.</p>
 *
 * @param name        a user-provided display name (e.g. "Vocal-Forward Mix")
 * @param timestamp   the capture time
 * @param master      state of the master channel
 * @param channels    state of each track channel, in mixer-order (unmodifiable)
 * @param returnBuses state of each return bus, in mixer-order (unmodifiable)
 */
public record MixerSnapshot(String name,
                            Instant timestamp,
                            ChannelSnapshot master,
                            List<ChannelSnapshot> channels,
                            List<ChannelSnapshot> returnBuses) {

    public MixerSnapshot {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(master, "master must not be null");
        Objects.requireNonNull(channels, "channels must not be null");
        Objects.requireNonNull(returnBuses, "returnBuses must not be null");
        channels = List.copyOf(channels);
        returnBuses = List.copyOf(returnBuses);
    }

    /**
     * Captures the current state of the given mixer as a new snapshot.
     *
     * @param mixer the mixer to capture
     * @param name  a user-provided display name
     * @return a new snapshot reflecting the mixer's current state
     */
    public static MixerSnapshot capture(Mixer mixer, String name) {
        Objects.requireNonNull(mixer, "mixer must not be null");
        Objects.requireNonNull(name, "name must not be null");

        List<MixerChannel> returnBusList = mixer.getReturnBuses();

        ChannelSnapshot master = captureChannel(mixer.getMasterChannel(), returnBusList);

        List<ChannelSnapshot> channels = new ArrayList<>(mixer.getChannels().size());
        for (MixerChannel ch : mixer.getChannels()) {
            channels.add(captureChannel(ch, returnBusList));
        }

        List<ChannelSnapshot> returnBuses = new ArrayList<>(returnBusList.size());
        for (MixerChannel rb : returnBusList) {
            returnBuses.add(captureChannel(rb, returnBusList));
        }

        return new MixerSnapshot(name, Instant.now(), master, channels, returnBuses);
    }

    /**
     * Applies this snapshot's state to the given mixer, restoring all
     * per-channel values and insert/send parameters.
     *
     * <p>The recall is index-aligned: the snapshot's i-th channel state is
     * applied to the mixer's i-th channel, i-th insert state to the i-th
     * insert, and so on. Extra channels or inserts on either side are ignored,
     * so snapshots are tolerant of limited structural drift.</p>
     *
     * @param mixer the mixer to update
     */
    public void applyTo(Mixer mixer) {
        Objects.requireNonNull(mixer, "mixer must not be null");

        applyChannel(master, mixer.getMasterChannel(), mixer);

        List<MixerChannel> mixerChannels = mixer.getChannels();
        int channelCount = Math.min(channels.size(), mixerChannels.size());
        for (int i = 0; i < channelCount; i++) {
            applyChannel(channels.get(i), mixerChannels.get(i), mixer);
        }

        List<MixerChannel> mixerReturnBuses = mixer.getReturnBuses();
        int returnCount = Math.min(returnBuses.size(), mixerReturnBuses.size());
        for (int i = 0; i < returnCount; i++) {
            applyChannel(returnBuses.get(i), mixerReturnBuses.get(i), mixer);
        }
    }

    // ── Capture helpers ─────────────────────────────────────────────────────

    private static ChannelSnapshot captureChannel(MixerChannel channel,
                                                  List<MixerChannel> returnBuses) {
        List<InsertSnapshot> inserts = new ArrayList<>(channel.getInsertSlots().size());
        for (InsertSlot slot : channel.getInsertSlots()) {
            inserts.add(captureInsert(slot));
        }

        List<SendSnapshot> sends = new ArrayList<>(channel.getSends().size());
        for (Send send : channel.getSends()) {
            int targetIndex = returnBuses.indexOf(send.getTarget());
            if (targetIndex >= 0) {
                sends.add(new SendSnapshot(targetIndex, send.getLevel(), send.getMode()));
            }
        }

        return new ChannelSnapshot(
                channel.getVolume(),
                channel.getPan(),
                channel.isMuted(),
                channel.isSolo(),
                channel.isPhaseInverted(),
                channel.getSendLevel(),
                channel.getOutputRouting(),
                inserts,
                sends,
                channel.getCpuBudget());
    }

    private static InsertSnapshot captureInsert(InsertSlot slot) {
        InsertEffectType type = slot.getEffectType();
        Map<Integer, Double> params;
        if (type == null || type == InsertEffectType.CLAP_PLUGIN) {
            params = Map.of();
            type = null;
        } else {
            params = InsertEffectFactory.getParameterValues(type, slot.getProcessor());
        }
        return new InsertSnapshot(type, slot.isBypassed(), params);
    }

    // ── Apply helpers ───────────────────────────────────────────────────────

    private static void applyChannel(ChannelSnapshot state, MixerChannel channel, Mixer mixer) {
        channel.setVolume(state.volume());
        channel.setPan(state.pan());
        channel.setMuted(state.muted());
        channel.setSolo(state.solo());
        channel.setPhaseInverted(state.phaseInverted());
        channel.setSendLevel(state.sendLevel());
        channel.setOutputRouting(state.outputRouting());
        channel.setCpuBudget(state.cpuBudget());

        // Apply insert state index-aligned: bypass + parameter values.
        List<InsertSlot> slots = channel.getInsertSlots();
        int insertCount = Math.min(state.inserts().size(), slots.size());
        for (int i = 0; i < insertCount; i++) {
            applyInsert(state.inserts().get(i), slots.get(i), channel, i);
        }

        // Apply send state index-aligned: level + mode. Targets are resolved
        // through the mixer's current return-bus list; sends whose target is
        // no longer present are skipped.
        List<Send> existingSends = channel.getSends();
        List<MixerChannel> returnBuses = mixer.getReturnBuses();
        int sendCount = Math.min(state.sends().size(), existingSends.size());
        for (int i = 0; i < sendCount; i++) {
            SendSnapshot snap = state.sends().get(i);
            Send send = existingSends.get(i);
            if (snap.targetIndex() >= 0 && snap.targetIndex() < returnBuses.size()) {
                send.setLevel(snap.level());
                send.setMode(snap.mode());
            }
        }
    }

    private static void applyInsert(InsertSnapshot state, InsertSlot slot,
                                    MixerChannel channel, int slotIndex) {
        // Update bypass via the channel so the effects chain is rebuilt.
        if (slot.isBypassed() != state.bypassed()) {
            channel.setInsertBypassed(slotIndex, state.bypassed());
        }

        // Re-apply parameter values if the slot's type matches the snapshot's.
        InsertEffectType type = state.effectType();
        if (type != null && type == slot.getEffectType()) {
            BiConsumer<Integer, Double> handler =
                    InsertEffectFactory.createParameterHandler(type, slot.getProcessor());
            for (Map.Entry<Integer, Double> entry : state.parameters().entrySet()) {
                handler.accept(entry.getKey(), entry.getValue());
            }
        }
    }
}
