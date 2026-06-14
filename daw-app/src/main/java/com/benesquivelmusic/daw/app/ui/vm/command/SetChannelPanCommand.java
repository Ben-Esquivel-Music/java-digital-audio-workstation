package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import java.util.Objects;

/**
 * Intent to set a mixer channel's pan position (§5.2). Raised by the pan knob on
 * commit, never per drag-tick (§4.4). Channel-only.
 *
 * @param channel the target channel; must not be {@code null}
 * @param pan     the requested pan in [−1,1]
 */
public record SetChannelPanCommand(MixerChannel channel, double pan) implements TrackCommand {

    /** @throws NullPointerException if {@code channel} is {@code null} */
    public SetChannelPanCommand {
        Objects.requireNonNull(channel, "channel must not be null");
    }

    @Override
    public void execute(TrackIntentHandler handler) {
        handler.setPan(channel, pan);
    }
}
