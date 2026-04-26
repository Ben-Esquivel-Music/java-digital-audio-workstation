package com.benesquivelmusic.daw.core.mixer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages every {@link ChannelLink} in a session and propagates linked
 * edits between paired mixer channels.
 *
 * <p>This is the model component behind the "stereo pair" / "Link
 * Channels" toggle that every console-style DAW exposes. The manager
 * stores the set of links and provides three families of operations:</p>
 *
 * <ol>
 *   <li><b>Link lifecycle</b> — {@link #link link}, {@link #unlink unlink},
 *       {@link #replace replace} create, remove, and update links. Both
 *       channels of a link must be unpaired before {@link #link link} can
 *       create a new pairing.</li>
 *   <li><b>Lookup</b> — {@link #getLink(UUID)},
 *       {@link #partnerOf(UUID)}, {@link #isLinked(UUID)} answer "is this
 *       channel paired and with whom?".</li>
 *   <li><b>Propagation</b> — {@link #applyVolumeChange applyVolumeChange},
 *       {@link #applyPanChange applyPanChange},
 *       {@link #applyMuteChange applyMuteChange},
 *       {@link #applySoloChange applySoloChange} take an edit on a source
 *       channel and apply the corresponding change to its partner per the
 *       link's mode and per-attribute toggles. Pan changes always
 *       <em>mirror</em> around centre (left at {@code -0.3} → right at
 *       {@code +0.3}); volume changes are either copied
 *       ({@link LinkMode#ABSOLUTE}) or shifted by the source's delta
 *       ({@link LinkMode#RELATIVE} — preserves the pre-existing offset).</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * <p>The link set is held in a single immutable {@link Snapshot} published
 * through an {@link AtomicReference}. All mutating methods are
 * synchronized; all read paths read the reference once and operate on the
 * resulting immutable snapshot, so observers (including UI updates) never
 * see a partially-updated state. Mirrors the threading contract of
 * {@link VcaGroupManager} and {@link CueBusManager}.</p>
 */
public final class ChannelLinkManager {

    /** Immutable point-in-time view of every registered link. */
    private record Snapshot(List<ChannelLink> ordered, Map<UUID, ChannelLink> byChannelId) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of());
    }

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

    /** Returns every registered link in insertion order. */
    public List<ChannelLink> getLinks() {
        return snapshot.get().ordered();
    }

    /**
     * Returns the link the given channel participates in, or {@code null}
     * if the channel is not linked. A channel can be in at most one link.
     */
    public ChannelLink getLink(UUID channelId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        return snapshot.get().byChannelId().get(channelId);
    }

    /** Returns {@code true} if the given channel currently participates in a link. */
    public boolean isLinked(UUID channelId) {
        return getLink(channelId) != null;
    }

    /**
     * Returns the channel id of {@code channelId}'s partner, or {@code null}
     * if the channel is not linked.
     */
    public UUID partnerOf(UUID channelId) {
        ChannelLink link = getLink(channelId);
        return link == null ? null : link.partnerOf(channelId);
    }

    /**
     * Registers the given link. Both channels must currently be unlinked.
     *
     * @throws IllegalStateException if either channel is already part of a link
     */
    public synchronized void link(ChannelLink link) {
        Objects.requireNonNull(link, "link must not be null");
        Snapshot current = snapshot.get();
        if (current.byChannelId().containsKey(link.leftChannelId())) {
            throw new IllegalStateException(
                    "left channel already linked: " + link.leftChannelId());
        }
        if (current.byChannelId().containsKey(link.rightChannelId())) {
            throw new IllegalStateException(
                    "right channel already linked: " + link.rightChannelId());
        }
        snapshot.set(withAdded(current, link));
    }

    /**
     * Removes the link containing {@code channelId} (left or right).
     * Channel-strip values are <em>not</em> reset by this operation —
     * unlinking preserves the current volume/pan/mute/solo of both
     * channels, mirroring the behavior described in the issue.
     *
     * @return the removed link, or {@code null} if the channel was not linked
     */
    public synchronized ChannelLink unlink(UUID channelId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        Snapshot current = snapshot.get();
        ChannelLink existing = current.byChannelId().get(channelId);
        if (existing == null) {
            return null;
        }
        snapshot.set(withRemoved(current, existing));
        return existing;
    }

    /**
     * Replaces a stored link with the given updated value. The updated
     * link must reference the same {@code (leftChannelId, rightChannelId)}
     * pair as the existing one — i.e. only mode/per-attribute toggles can
     * change via {@code replace}; to repair the channel pair, unlink and
     * re-link.
     *
     * @throws IllegalArgumentException if no matching link is registered
     */
    public synchronized void replace(ChannelLink updated) {
        Objects.requireNonNull(updated, "updated must not be null");
        Snapshot current = snapshot.get();
        ChannelLink existing = current.byChannelId().get(updated.leftChannelId());
        if (existing == null
                || !existing.leftChannelId().equals(updated.leftChannelId())
                || !existing.rightChannelId().equals(updated.rightChannelId())) {
            throw new IllegalArgumentException(
                    "no link registered for pair "
                            + updated.leftChannelId() + " / " + updated.rightChannelId());
        }
        // Rebuild ordered list preserving insertion order with the updated entry in place.
        // Match by stable channel-id pair rather than reference identity so this stays
        // correct even if the snapshot ever reconstructs link records.
        List<ChannelLink> ordered = new ArrayList<>(current.ordered().size());
        for (ChannelLink l : current.ordered()) {
            ordered.add(samePair(l, existing) ? updated : l);
        }
        snapshot.set(buildSnapshot(ordered));
    }

    // ── Propagation helpers ────────────────────────────────────────────────

    /**
     * Propagates a volume edit on {@code source} to {@code partner} per the
     * link's mode. In {@link LinkMode#ABSOLUTE} mode the partner is set to
     * the source's new value. In {@link LinkMode#RELATIVE} mode the partner
     * is shifted by the same delta the source moved; the resulting value is
     * clamped to the fader range {@code [0.0, 1.0]} so the partner's offset
     * is preserved as far as the fader range allows. (The source's new
     * value is assumed to already be within range — its caller validated
     * it via {@link MixerChannel#setVolume(double)} — so no additional
     * clamping is needed in {@code ABSOLUTE} mode.)
     *
     * <p>If {@link ChannelLink#linkFaders()} is {@code false} this method
     * is a no-op, mirroring the per-attribute toggle semantics described in
     * the issue.</p>
     *
     * @param link             the link governing this pair
     * @param source           the channel the user just edited
     * @param partner          the linked partner that should follow
     * @param oldSourceVolume  the source's volume <em>before</em> the edit
     * @param newSourceVolume  the source's volume <em>after</em> the edit
     */
    public void applyVolumeChange(ChannelLink link,
                                  MixerChannel source,
                                  MixerChannel partner,
                                  double oldSourceVolume,
                                  double newSourceVolume) {
        Objects.requireNonNull(link, "link must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(partner, "partner must not be null");
        if (!link.linkFaders()) {
            return;
        }
        double next = switch (link.mode()) {
            case ABSOLUTE -> newSourceVolume;
            // RELATIVE: shift by the source's delta, then clamp so the partner
            // stays within fader range when the offset would push it out.
            case RELATIVE -> clamp(
                    partner.getVolume() + (newSourceVolume - oldSourceVolume),
                    0.0, 1.0);
        };
        partner.setVolume(next);
    }

    /**
     * Propagates a pan edit on {@code source} to {@code partner} as a mirror
     * around centre — i.e. {@code partner.pan = -source.pan}. This is the
     * standard stereo-pair behaviour described in the issue ("left at -0.3
     * → right at +0.3") and is identical in both link modes because pan is
     * a position, not an offset.
     *
     * <p>If {@link ChannelLink#linkPans()} is {@code false} this method is
     * a no-op.</p>
     */
    public void applyPanChange(ChannelLink link, MixerChannel partner, double newSourcePan) {
        Objects.requireNonNull(link, "link must not be null");
        Objects.requireNonNull(partner, "partner must not be null");
        if (!link.linkPans()) {
            return;
        }
        partner.setPan(clamp(-newSourcePan, -1.0, 1.0));
    }

    /**
     * Propagates a mute edit on the source to {@code partner} (both members
     * always share mute state). No-op if {@link ChannelLink#linkMuteSolo()}
     * is {@code false}.
     */
    public void applyMuteChange(ChannelLink link, MixerChannel partner, boolean muted) {
        Objects.requireNonNull(link, "link must not be null");
        Objects.requireNonNull(partner, "partner must not be null");
        if (!link.linkMuteSolo()) {
            return;
        }
        partner.setMuted(muted);
    }

    /**
     * Propagates a solo edit on the source to {@code partner}. No-op if
     * {@link ChannelLink#linkMuteSolo()} is {@code false}.
     */
    public void applySoloChange(ChannelLink link, MixerChannel partner, boolean solo) {
        Objects.requireNonNull(link, "link must not be null");
        Objects.requireNonNull(partner, "partner must not be null");
        if (!link.linkMuteSolo()) {
            return;
        }
        partner.setSolo(solo);
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private static Snapshot withAdded(Snapshot current, ChannelLink link) {
        List<ChannelLink> ordered = new ArrayList<>(current.ordered().size() + 1);
        ordered.addAll(current.ordered());
        ordered.add(link);
        return buildSnapshot(ordered);
    }

    private static Snapshot withRemoved(Snapshot current, ChannelLink link) {
        List<ChannelLink> ordered = new ArrayList<>(current.ordered().size() - 1);
        // Match by stable channel-id pair rather than reference identity so
        // callers can pass in a freshly-constructed equivalent record.
        for (ChannelLink l : current.ordered()) {
            if (!samePair(l, link)) {
                ordered.add(l);
            }
        }
        return buildSnapshot(ordered);
    }

    private static boolean samePair(ChannelLink a, ChannelLink b) {
        return a.leftChannelId().equals(b.leftChannelId())
                && a.rightChannelId().equals(b.rightChannelId());
    }

    private static Snapshot buildSnapshot(List<ChannelLink> ordered) {
        Map<UUID, ChannelLink> byChannelId = new LinkedHashMap<>(ordered.size() * 2);
        for (ChannelLink l : ordered) {
            byChannelId.put(l.leftChannelId(), l);
            byChannelId.put(l.rightChannelId(), l);
        }
        return new Snapshot(
                Collections.unmodifiableList(ordered),
                Collections.unmodifiableMap(byChannelId));
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
