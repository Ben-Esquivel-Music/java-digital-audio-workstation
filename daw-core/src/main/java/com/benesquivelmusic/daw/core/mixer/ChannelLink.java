package com.benesquivelmusic.daw.core.mixer;

import java.util.Objects;
import java.util.UUID;

/**
 * An immutable description of a "channel link" between two adjacent mixer
 * channels — the standard hardware-console workflow for treating two mono
 * channels as a single stereo pair.
 *
 * <p>Every major DAW exposes this concept (Pro Tools' "Track Link," Cubase's
 * "Link Channels," Logic's stereo/mono toggle). Once linked, edits to one
 * channel propagate to its partner: faders move together, pans mirror left
 * and right around centre, mute and solo states are kept in sync, and (when
 * enabled) inserts and sends are kept in sync as well. Unlinking is purely
 * informational — it removes the link record but leaves both channels'
 * current values untouched.</p>
 *
 * <p>A link is identified by its {@link #leftChannelId() left} and
 * {@link #rightChannelId() right} channel UUIDs. The same UUIDs that
 * elsewhere identify member channels of {@link VcaGroup} are reused here, so
 * a link's identity remains stable across save/load and undo/redo cycles.
 * The "left" / "right" naming reflects the hardware console layout: the
 * left channel's pan position is the source value and the right channel's
 * pan position is the mirrored value (left at {@code -0.3} → right at
 * {@code +0.3}).</p>
 *
 * @param leftChannelId   stable UUID of the left (lower-indexed) channel
 * @param rightChannelId  stable UUID of the right (higher-indexed) channel
 * @param mode            how fader/level edits are propagated; see {@link LinkMode}
 * @param linkFaders      {@code true} to mirror volume/fader changes
 * @param linkPans        {@code true} to mirror pan position around centre
 * @param linkMuteSolo    {@code true} to mirror mute and solo state
 * @param linkInserts     {@code true} to mirror insert add/remove and parameter edits
 * @param linkSends       {@code true} to mirror per-send level/tap/destination edits
 */
public record ChannelLink(UUID leftChannelId,
                          UUID rightChannelId,
                          LinkMode mode,
                          boolean linkFaders,
                          boolean linkPans,
                          boolean linkMuteSolo,
                          boolean linkInserts,
                          boolean linkSends) {

    public ChannelLink {
        Objects.requireNonNull(leftChannelId, "leftChannelId must not be null");
        Objects.requireNonNull(rightChannelId, "rightChannelId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        if (leftChannelId.equals(rightChannelId)) {
            throw new IllegalArgumentException(
                    "leftChannelId and rightChannelId must differ");
        }
    }

    /**
     * Convenience factory for a "link everything" pair in
     * {@link LinkMode#RELATIVE} mode — the most common default in DAWs.
     */
    public static ChannelLink ofPair(UUID leftChannelId, UUID rightChannelId) {
        return new ChannelLink(leftChannelId, rightChannelId,
                LinkMode.RELATIVE, true, true, true, true, true);
    }

    /** Returns {@code true} if the link involves the given channel id (left or right). */
    public boolean involves(UUID channelId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        return leftChannelId.equals(channelId) || rightChannelId.equals(channelId);
    }

    /**
     * Returns the partner of {@code channelId} in this link, or {@code null}
     * if the channel is not part of this link.
     */
    public UUID partnerOf(UUID channelId) {
        Objects.requireNonNull(channelId, "channelId must not be null");
        if (leftChannelId.equals(channelId)) {
            return rightChannelId;
        }
        if (rightChannelId.equals(channelId)) {
            return leftChannelId;
        }
        return null;
    }

    /** Returns a copy with the given {@link LinkMode}. */
    public ChannelLink withMode(LinkMode newMode) {
        return new ChannelLink(leftChannelId, rightChannelId, newMode,
                linkFaders, linkPans, linkMuteSolo, linkInserts, linkSends);
    }

    /** Returns a copy with the {@code linkFaders} attribute toggled to the given value. */
    public ChannelLink withLinkFaders(boolean value) {
        return new ChannelLink(leftChannelId, rightChannelId, mode,
                value, linkPans, linkMuteSolo, linkInserts, linkSends);
    }

    /** Returns a copy with the {@code linkPans} attribute toggled to the given value. */
    public ChannelLink withLinkPans(boolean value) {
        return new ChannelLink(leftChannelId, rightChannelId, mode,
                linkFaders, value, linkMuteSolo, linkInserts, linkSends);
    }

    /** Returns a copy with the {@code linkMuteSolo} attribute toggled to the given value. */
    public ChannelLink withLinkMuteSolo(boolean value) {
        return new ChannelLink(leftChannelId, rightChannelId, mode,
                linkFaders, linkPans, value, linkInserts, linkSends);
    }

    /** Returns a copy with the {@code linkInserts} attribute toggled to the given value. */
    public ChannelLink withLinkInserts(boolean value) {
        return new ChannelLink(leftChannelId, rightChannelId, mode,
                linkFaders, linkPans, linkMuteSolo, value, linkSends);
    }

    /** Returns a copy with the {@code linkSends} attribute toggled to the given value. */
    public ChannelLink withLinkSends(boolean value) {
        return new ChannelLink(leftChannelId, rightChannelId, mode,
                linkFaders, linkPans, linkMuteSolo, linkInserts, value);
    }
}
