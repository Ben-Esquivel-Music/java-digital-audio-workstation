package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages every {@link VcaGroup} in a session and computes the effective
 * VCA multiplier that should be applied to a member channel's level.
 *
 * <p>A VCA group is <em>not</em> a bus: it does not sum any audio. Instead,
 * the {@link com.benesquivelmusic.daw.core.audio.RenderPipeline RenderPipeline}
 * (or any other consumer of the mixer model) queries
 * {@link #effectiveLinearMultiplier(UUID)} for each channel and multiplies
 * the channel's own fader value by the returned scalar. Because the channel's
 * fader is preserved, the relative balance among the members is never disturbed
 * by a VCA fader move.</p>
 *
 * <p>A channel may belong to multiple VCAs; the multipliers compose, which is
 * equivalent to summing the dB values of every group the channel belongs to.
 * This is the standard behavior across Pro Tools, Logic, Studio One, and
 * Cubase.</p>
 *
 * <h2>Threading</h2>
 * <p>The full group list is held in a single immutable {@link Snapshot}
 * published through an {@link AtomicReference}. All mutating methods are
 * synchronized on the manager to provide atomic check-then-act semantics, and
 * replace the snapshot reference in a single publication. All read paths —
 * including {@link #getVcaGroups()}, {@link #getById(UUID)},
 * {@link #getGroupsForChannel(UUID)}, and {@link #effectiveLinearMultiplier(UUID)}
 * — read the reference once and operate on the resulting immutable snapshot,
 * so the real-time audio thread never acquires a lock and never observes a
 * partially-updated state. Mirrors the threading contract of
 * {@link CueBusManager}.</p>
 */
public final class VcaGroupManager {

    /**
     * Immutable point-in-time view of every registered VCA group.
     */
    private record Snapshot(List<VcaGroup> ordered, Map<UUID, VcaGroup> byId) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of());
    }

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

    /** Returns every VCA group in insertion order. */
    public List<VcaGroup> getVcaGroups() {
        return snapshot.get().ordered();
    }

    /** Looks up a VCA group by its stable id, or returns {@code null}. */
    public VcaGroup getById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return snapshot.get().byId().get(id);
    }

    /**
     * Returns every VCA group that currently lists {@code channelId} as a
     * member, in insertion order.
     */
    public List<VcaGroup> getGroupsForChannel(UUID channelId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        List<VcaGroup> result = new ArrayList<>();
        for (VcaGroup g : snapshot.get().ordered()) {
            if (g.hasMember(channelId)) {
                result.add(g);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Creates and registers a new empty VCA group at unity gain.
     *
     * @param label a non-{@code null} human-readable label
     * @return the newly registered group
     */
    public synchronized VcaGroup createVcaGroup(String label) {
        VcaGroup group = VcaGroup.create(label);
        snapshot.set(withAdded(snapshot.get(), group));
        return group;
    }

    /**
     * Creates a new VCA group containing the given members at unity gain.
     *
     * <p>Convenience for the "select several channels → right-click →
     * Create VCA" UX described in the issue.</p>
     */
    public synchronized VcaGroup createVcaGroup(String label, List<UUID> memberChannelIds) {
        Objects.requireNonNull(memberChannelIds, "memberChannelIds must not be null");
        VcaGroup group = new VcaGroup(UUID.randomUUID(), label, 0.0, null, memberChannelIds);
        snapshot.set(withAdded(snapshot.get(), group));
        return group;
    }

    /**
     * Registers an existing {@link VcaGroup}. Used by undo and deserialization
     * so that a group's id is preserved across save/load and undo/redo cycles.
     *
     * @throws IllegalArgumentException if a group with the same id is already
     *                                  registered
     */
    public synchronized void addVcaGroup(VcaGroup group) {
        Objects.requireNonNull(group, "group must not be null");
        Snapshot current = snapshot.get();
        if (current.byId().containsKey(group.id())) {
            throw new IllegalArgumentException("vca group already registered: " + group.id());
        }
        snapshot.set(withAdded(current, group));
    }

    /**
     * Removes the VCA group with the given id.
     *
     * @return {@code true} if the group was present and removed
     */
    public synchronized boolean removeVcaGroup(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        Snapshot current = snapshot.get();
        if (!current.byId().containsKey(id)) {
            return false;
        }
        List<VcaGroup> ordered = new ArrayList<>(current.ordered().size() - 1);
        Map<UUID, VcaGroup> byId = new LinkedHashMap<>(current.byId().size());
        for (VcaGroup g : current.ordered()) {
            if (!g.id().equals(id)) {
                ordered.add(g);
                byId.put(g.id(), g);
            }
        }
        snapshot.set(new Snapshot(
                Collections.unmodifiableList(ordered),
                Collections.unmodifiableMap(byId)));
        return true;
    }

    /**
     * Replaces the stored VCA group atomically with {@code updated}. The
     * group must already be registered under {@code updated.id()}.
     */
    public synchronized void replace(VcaGroup updated) {
        Objects.requireNonNull(updated, "updated must not be null");
        Snapshot current = snapshot.get();
        if (!current.byId().containsKey(updated.id())) {
            throw new IllegalArgumentException("vca group not registered: " + updated.id());
        }
        List<VcaGroup> ordered = new ArrayList<>(current.ordered().size());
        Map<UUID, VcaGroup> byId = new LinkedHashMap<>(current.byId().size());
        for (VcaGroup g : current.ordered()) {
            VcaGroup replacement = g.id().equals(updated.id()) ? updated : g;
            ordered.add(replacement);
            byId.put(replacement.id(), replacement);
        }
        snapshot.set(new Snapshot(
                Collections.unmodifiableList(ordered),
                Collections.unmodifiableMap(byId)));
    }

    /**
     * Convenience that updates only the master gain of {@code groupId}.
     *
     * @return the updated group
     * @throws IllegalArgumentException if no group with that id is registered
     */
    public synchronized VcaGroup setMasterGainDb(UUID groupId, double newGainDb) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        VcaGroup existing = getById(groupId);
        if (existing == null) {
            throw new IllegalArgumentException("vca group not registered: " + groupId);
        }
        VcaGroup updated = existing.withMasterGainDb(newGainDb);
        replace(updated);
        return updated;
    }

    /**
     * Convenience that adds {@code channelId} to {@code groupId}'s members.
     * If the channel is already a member, the call is a no-op and the
     * existing group is returned unchanged.
     *
     * @return the updated group
     */
    public synchronized VcaGroup addMember(UUID groupId, UUID channelId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(channelId, "channelId must not be null");
        VcaGroup existing = getById(groupId);
        if (existing == null) {
            throw new IllegalArgumentException("vca group not registered: " + groupId);
        }
        VcaGroup updated = existing.withMember(channelId);
        if (updated != existing) {
            replace(updated);
        }
        return updated;
    }

    /**
     * Convenience that removes {@code channelId} from {@code groupId}'s members.
     * If the channel is not currently a member, the call is a no-op.
     *
     * @return the updated group
     */
    public synchronized VcaGroup removeMember(UUID groupId, UUID channelId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(channelId, "channelId must not be null");
        VcaGroup existing = getById(groupId);
        if (existing == null) {
            throw new IllegalArgumentException("vca group not registered: " + groupId);
        }
        VcaGroup updated = existing.withoutMember(channelId);
        if (updated != existing) {
            replace(updated);
        }
        return updated;
    }

    /**
     * Returns the linear multiplier that should be applied to {@code channelId}'s
     * level by the {@link com.benesquivelmusic.daw.core.audio.RenderPipeline
     * RenderPipeline}. The multiplier is the product of the linear gains of
     * every VCA group that lists this channel as a member, which equivalently
     * means the sum of those groups' {@code masterGainDb} values.
     *
     * <p>If the channel does not belong to any VCA, this method returns
     * {@code 1.0} — preserving the channel's own fader exactly. If any VCA
     * the channel belongs to is at {@link VcaGroup#MIN_GAIN_DB}, the
     * multiplier collapses to {@code 0.0} to model "−∞ dB → silence".</p>
     *
     * <p>This method is allocation-free and lock-free; it reads the
     * {@code AtomicReference}-published snapshot once and iterates over the
     * immutable list. It is therefore safe to call on the real-time audio
     * thread.</p>
     *
     * @param channelId the channel id whose effective VCA multiplier is wanted
     * @return the linear multiplier in {@code [0.0, +∞)}
     */
    @RealTimeSafe
    public double effectiveLinearMultiplier(UUID channelId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        double totalDb = 0.0;
        boolean anyMembership = false;
        for (VcaGroup g : snapshot.get().ordered()) {
            if (g.hasMember(channelId)) {
                anyMembership = true;
                if (g.masterGainDb() <= VcaGroup.MIN_GAIN_DB) {
                    // Modelled as −∞ dB → silence.
                    return 0.0;
                }
                totalDb += g.masterGainDb();
            }
        }
        if (!anyMembership) {
            return 1.0;
        }
        // dB → linear: 10^(dB/20).
        return Math.pow(10.0, totalDb / 20.0);
    }

    /**
     * Returns the effective VCA gain in decibels — equivalent to
     * {@code 20 * log10(effectiveLinearMultiplier(channelId))} but computed
     * directly from the dB values to avoid round-tripping through the
     * exponential. Useful for meter / readout UIs.
     *
     * @return the sum of {@code masterGainDb} across every group that lists
     *         {@code channelId}, or {@code 0.0} if the channel is not in any
     *         VCA, or {@link VcaGroup#MIN_GAIN_DB} if any group is at the floor
     */
    public double effectiveGainDb(UUID channelId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        double totalDb = 0.0;
        boolean anyMembership = false;
        for (VcaGroup g : snapshot.get().ordered()) {
            if (g.hasMember(channelId)) {
                anyMembership = true;
                if (g.masterGainDb() <= VcaGroup.MIN_GAIN_DB) {
                    return VcaGroup.MIN_GAIN_DB;
                }
                totalDb += g.masterGainDb();
            }
        }
        return anyMembership ? totalDb : 0.0;
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private static Snapshot withAdded(Snapshot current, VcaGroup group) {
        List<VcaGroup> ordered = new ArrayList<>(current.ordered().size() + 1);
        ordered.addAll(current.ordered());
        ordered.add(group);
        Map<UUID, VcaGroup> byId = new LinkedHashMap<>(current.byId().size() + 1);
        byId.putAll(current.byId());
        byId.put(group.id(), group);
        return new Snapshot(
                Collections.unmodifiableList(ordered),
                Collections.unmodifiableMap(byId));
    }
}
