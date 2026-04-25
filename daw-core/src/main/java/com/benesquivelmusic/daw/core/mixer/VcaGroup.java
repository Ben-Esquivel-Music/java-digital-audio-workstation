package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.track.TrackColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A "voltage-controlled amplifier" group: a phantom fader that proportionally
 * scales the levels of every member channel, <em>without</em> creating a bus
 * and <em>without</em> introducing an additional summing point.
 *
 * <p>Every console and every modern DAW exposes VCA groups (Pro Tools VCA
 * tracks, Logic VCA faders, Studio One VCA channels, Cubase control-room
 * VCAs). The motivation is purely operational: an engineer can ride one
 * fader to make "all drums louder by 2 dB" while every drum channel keeps
 * its individual fader value, so the relative balance the engineer worked
 * to establish is preserved exactly.</p>
 *
 * <p>Audio routing is unaffected: a VCA group is <em>not</em> a bus. The
 * {@link VcaGroupManager} converts {@link #masterGainDb()} into a linear
 * multiplier and applies it to each member channel's effective gain during
 * {@link com.benesquivelmusic.daw.core.audio.RenderPipeline RenderPipeline}
 * evaluation. A channel may belong to multiple VCAs; the multipliers compose
 * (the dB values sum).</p>
 *
 * <p>This record is deeply immutable: {@link #memberChannelIds()} returns an
 * unmodifiable view, and the convenience {@code withX} methods return new
 * instances rather than mutating the existing one — matching the snapshot
 * style used elsewhere in this package (see
 * {@link com.benesquivelmusic.daw.core.mixer.CueBus CueBus}).</p>
 *
 * <h2>Color</h2>
 * <p>The original spec uses an abstract {@code Color color} field. To stay
 * consistent with the rest of the core API ({@link MixerChannel#getColor()}
 * also uses {@code TrackColor}), this record stores a {@link TrackColor}.
 * The field may be {@code null} if no color has been assigned.</p>
 *
 * @param id                stable identity; preserved across save/load
 * @param label             human-readable name shown in the mixer's VCA strip
 * @param masterGainDb      master fader, expressed in decibels relative to unity
 * @param color             optional color for the VCA strip, may be {@code null}
 * @param memberChannelIds  UUIDs of the member channels; immutable snapshot
 */
public record VcaGroup(UUID id,
                       String label,
                       double masterGainDb,
                       TrackColor color,
                       List<UUID> memberChannelIds) {

    /**
     * Lower bound for {@link #masterGainDb()}. Treated as "−∞ dB" (silence)
     * by {@link VcaGroupManager}. Mirrors the practical floor of every
     * console fader (Pro Tools / Logic / Studio One bottom out near −∞).
     */
    public static final double MIN_GAIN_DB = -120.0;

    /**
     * Upper bound for {@link #masterGainDb()}, matching the +12 dB of
     * headroom most consoles allow above unity gain.
     */
    public static final double MAX_GAIN_DB = 12.0;

    public VcaGroup {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(memberChannelIds, "memberChannelIds must not be null");
        if (Double.isNaN(masterGainDb)) {
            throw new IllegalArgumentException("masterGainDb must not be NaN");
        }
        if (masterGainDb < MIN_GAIN_DB || masterGainDb > MAX_GAIN_DB) {
            throw new IllegalArgumentException(
                    "masterGainDb must be in [" + MIN_GAIN_DB + ", " + MAX_GAIN_DB
                            + "]: " + masterGainDb);
        }
        // Defensive snapshot so callers cannot mutate the backing list afterwards
        // and any incoming nulls are rejected eagerly.
        List<UUID> snapshot = new ArrayList<>(memberChannelIds.size());
        for (UUID member : memberChannelIds) {
            Objects.requireNonNull(member, "memberChannelIds must not contain null");
            if (!snapshot.contains(member)) {
                snapshot.add(member);
            }
        }
        memberChannelIds = Collections.unmodifiableList(snapshot);
    }

    /** Creates an empty VCA group at unity gain (0 dB) with the given label. */
    public static VcaGroup create(String label) {
        return new VcaGroup(UUID.randomUUID(), label, 0.0, null, List.of());
    }

    /** Creates an empty VCA group at unity gain (0 dB) with the given label and color. */
    public static VcaGroup create(String label, TrackColor color) {
        return new VcaGroup(UUID.randomUUID(), label, 0.0, color, List.of());
    }

    /** Returns {@code true} if {@code channelId} is currently a member of this group. */
    public boolean hasMember(UUID channelId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        return memberChannelIds.contains(channelId);
    }

    /** Returns a copy with the given label. */
    public VcaGroup withLabel(String newLabel) {
        return new VcaGroup(id, newLabel, masterGainDb, color, memberChannelIds);
    }

    /** Returns a copy with the given master gain in dB. */
    public VcaGroup withMasterGainDb(double newGainDb) {
        return new VcaGroup(id, label, newGainDb, color, memberChannelIds);
    }

    /** Returns a copy with the given color (may be {@code null} to clear). */
    public VcaGroup withColor(TrackColor newColor) {
        return new VcaGroup(id, label, masterGainDb, newColor, memberChannelIds);
    }

    /**
     * Returns a copy of this group with {@code channelId} added to the
     * members. If the channel is already a member, returns {@code this}.
     */
    public VcaGroup withMember(UUID channelId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        if (memberChannelIds.contains(channelId)) {
            return this;
        }
        List<UUID> next = new ArrayList<>(memberChannelIds.size() + 1);
        next.addAll(memberChannelIds);
        next.add(channelId);
        return new VcaGroup(id, label, masterGainDb, color, next);
    }

    /**
     * Returns a copy of this group with {@code channelId} removed from the
     * members. If the channel is not a member, returns {@code this}.
     */
    public VcaGroup withoutMember(UUID channelId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        if (!memberChannelIds.contains(channelId)) {
            return this;
        }
        List<UUID> next = new ArrayList<>(memberChannelIds.size());
        for (UUID member : memberChannelIds) {
            if (!member.equals(channelId)) {
                next.add(member);
            }
        }
        return new VcaGroup(id, label, masterGainDb, color, next);
    }
}
