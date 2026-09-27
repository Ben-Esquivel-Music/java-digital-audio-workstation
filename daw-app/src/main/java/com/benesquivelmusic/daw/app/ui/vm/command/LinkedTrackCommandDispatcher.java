package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.ChannelLinkManager;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The production command sink for track/channel intents (story 322, Audio
 * Engine Wiring Design Book §5.6 "Stereo link"): executes each
 * {@link TrackCommand} on the {@link TrackIntentHandler}, then applies
 * <strong>stereo-link mirroring</strong> to the linked partner channel — the
 * one place a linked pair is kept in step, replacing {@code MixerView}'s
 * widget-map propagation ({@code propagateVolumeChange} and friends, whose
 * {@code propagationSuppressed} re-entry guard was the §1.4 smell).
 *
 * <h2>Mirroring</h2>
 *
 * <p>After the handler has run, if the source channel actually changed and is
 * a member of a {@link ChannelLink}, the partner follows through
 * {@link ChannelLinkManager}'s existing arithmetic — respecting the link's
 * per-attribute flags ({@code linkFaders}: {@link ChannelLinkManager#applyVolumeChange
 * absolute or relative}; {@code linkPans}: {@link ChannelLinkManager#applyPanChange
 * mirrored around centre}; {@code linkMuteSolo}: {@link ChannelLinkManager#applyMuteChange}
 * / {@link ChannelLinkManager#applySoloChange}). Those write the partner
 * {@code MixerChannel}; the handler is then invoked once more <em>for the
 * partner</em> with the partner's new value, which the per-surface VALIDATE
 * turns into a Track-only heal (the channel already matches) — so the partner's
 * arrangement lane follows too. Send mirroring is the send slider's own
 * concern ({@link ChannelLinkManager#applySendChange}); it is not a
 * {@code TrackCommand}.</p>
 *
 * <p><strong>No recursion:</strong> mirroring is done inline here, never by
 * re-dispatching a command through {@link #accept}, so a gesture on either
 * member produces exactly one round-trip and no re-entry guard is needed
 * (Control Synchronization Design Book §4.4).</p>
 *
 * <p>Arm and rename have no link semantics and are executed without mirroring.</p>
 */
public final class LinkedTrackCommandDispatcher implements Consumer<TrackCommand> {

    private final DawProject project;
    private final TrackIntentHandler handler;
    private final ChannelLinkManager links;
    private final Mixer mixer;

    /**
     * Creates a dispatcher over {@code project}'s link manager that executes
     * every command on {@code handler}.
     *
     * @param project the project whose {@link DawProject#getChannelLinkManager()
     *                links} govern mirroring; must not be {@code null}
     * @param handler the intent handler owning the mutation path; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public LinkedTrackCommandDispatcher(DawProject project, TrackIntentHandler handler) {
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.links = project.getChannelLinkManager();
        this.mixer = project.getMixer();
    }

    @Override
    public void accept(TrackCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        switch (command) {
            case SetChannelVolumeCommand c -> {
                double before = c.channel().getVolume();
                c.execute(handler);
                double after = c.channel().getVolume();
                if (after != before) {
                    mirrorVolume(c.channel(), before, after);
                }
            }
            case SetChannelPanCommand c -> {
                double before = c.channel().getPan();
                c.execute(handler);
                double after = c.channel().getPan();
                if (after != before) {
                    mirrorPan(c.channel(), after);
                }
            }
            case ToggleMuteCommand c -> {
                MixerChannel channel = project.getMixerChannelForTrack(c.track());
                boolean before = channel != null && channel.isMuted();
                c.execute(handler);
                if (channel != null && channel.isMuted() != before) {
                    mirrorMute(channel, channel.isMuted());
                }
            }
            case ToggleSoloCommand c -> {
                MixerChannel channel = project.getMixerChannelForTrack(c.track());
                boolean before = channel != null && channel.isSolo();
                c.execute(handler);
                if (channel != null && channel.isSolo() != before) {
                    mirrorSolo(channel, channel.isSolo());
                }
            }
            case ToggleArmCommand c -> c.execute(handler);
            case RenameTrackCommand c -> c.execute(handler); // a name has no link semantics
            case ToggleChannelMuteCommand c -> {
                boolean before = c.channel().isMuted();
                c.execute(handler);
                if (c.channel().isMuted() != before) {
                    mirrorMute(c.channel(), c.channel().isMuted());
                }
            }
            case ToggleChannelSoloCommand c -> {
                boolean before = c.channel().isSolo();
                c.execute(handler);
                if (c.channel().isSolo() != before) {
                    mirrorSolo(c.channel(), c.channel().isSolo());
                }
            }
        }
    }

    private void mirrorVolume(MixerChannel source, double before, double after) {
        ChannelLink link = links.getLink(source.getId());
        if (link == null || !link.linkFaders()) {
            return;
        }
        MixerChannel partner = partnerOf(link, source);
        if (partner == null) {
            return;
        }
        links.applyVolumeChange(link, source, partner, before, after);
        // Track-only heal: the partner channel already carries the new value.
        handler.setVolume(partner, partner.getVolume());
    }

    private void mirrorPan(MixerChannel source, double after) {
        ChannelLink link = links.getLink(source.getId());
        if (link == null || !link.linkPans()) {
            return;
        }
        MixerChannel partner = partnerOf(link, source);
        if (partner == null) {
            return;
        }
        links.applyPanChange(link, partner, after);
        handler.setPan(partner, partner.getPan());
    }

    private void mirrorMute(MixerChannel source, boolean muted) {
        ChannelLink link = links.getLink(source.getId());
        if (link == null || !link.linkMuteSolo()) {
            return;
        }
        MixerChannel partner = partnerOf(link, source);
        if (partner == null) {
            return;
        }
        links.applyMuteChange(link, partner, muted);
        handler.toggleChannelMute(partner, partner.isMuted());
    }

    private void mirrorSolo(MixerChannel source, boolean soloed) {
        ChannelLink link = links.getLink(source.getId());
        if (link == null || !link.linkMuteSolo()) {
            return;
        }
        MixerChannel partner = partnerOf(link, source);
        if (partner == null) {
            return;
        }
        links.applySoloChange(link, partner, soloed);
        handler.toggleChannelSolo(partner, partner.isSolo());
    }

    /**
     * Resolves the link's other member among the mixer's live track channels
     * and return buses, or {@code null} if it is not currently in the mixer (a
     * partner removed with its track — the link is stale until re-added).
     */
    private MixerChannel partnerOf(ChannelLink link, MixerChannel source) {
        UUID partnerId = link.partnerOf(source.getId());
        for (MixerChannel channel : mixer.getChannels()) {
            if (channel.getId().equals(partnerId)) {
                return channel;
            }
        }
        for (MixerChannel returnBus : mixer.getReturnBuses()) {
            if (returnBus.getId().equals(partnerId)) {
                return returnBus;
            }
        }
        return null;
    }
}
