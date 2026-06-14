package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import java.util.Objects;

/**
 * Intent to set a mixer channel's linear volume (§5.2). Raised by the fader on
 * commit, never per drag-tick (§4.4). Channel-only — the audio engine reads the
 * channel's volume.
 *
 * @param channel the target channel; must not be {@code null}
 * @param volume  the requested linear volume in [0,1]
 */
public record SetChannelVolumeCommand(MixerChannel channel, double volume) implements TrackCommand {

    /** @throws NullPointerException if {@code channel} is {@code null} */
    public SetChannelVolumeCommand {
        Objects.requireNonNull(channel, "channel must not be null");
    }

    @Override
    public void execute(TrackIntentHandler handler) {
        handler.setVolume(channel, volume);
    }
}
