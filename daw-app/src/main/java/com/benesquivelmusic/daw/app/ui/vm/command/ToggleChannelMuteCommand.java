package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import java.util.Objects;

/**
 * Intent to set a mixer channel's muted flag directly (story 322, Audio Engine
 * Wiring Design Book §5.6 "Mixer strip vol/pan/mute/solo", "VCA mute/solo").
 * Raised by the mute button of a strip whose channel has <em>no</em> track —
 * a return bus, the master, a VCA member without a track — where
 * {@link ToggleMuteCommand} (which targets a {@code Track}) cannot apply. The
 * handler still dual-writes a paired {@code Track} when one exists, so either
 * command converges on the same lock-step state.
 *
 * @param channel the target channel; must not be {@code null}
 * @param muted   the requested mute state
 */
public record ToggleChannelMuteCommand(MixerChannel channel, boolean muted) implements TrackCommand {

    /** @throws NullPointerException if {@code channel} is {@code null} */
    public ToggleChannelMuteCommand {
        Objects.requireNonNull(channel, "channel must not be null");
    }

    @Override
    public void execute(TrackIntentHandler handler) {
        handler.toggleChannelMute(channel, muted);
    }
}
