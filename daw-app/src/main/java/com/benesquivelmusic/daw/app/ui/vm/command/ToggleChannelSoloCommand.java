package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;

import java.util.Objects;

/**
 * Intent to set a mixer channel's solo flag directly (story 322, Audio Engine
 * Wiring Design Book §5.6). The channel-targeted sibling of
 * {@link ToggleSoloCommand}, for strips whose channel has no track (return
 * bus, master, VCA member without a track). The registry recomputes every
 * channel's effective mute from the new project-wide solo state; the handler
 * dual-writes a paired {@code Track} when one exists.
 *
 * @param channel the target channel; must not be {@code null}
 * @param soloed  the requested solo state
 */
public record ToggleChannelSoloCommand(MixerChannel channel, boolean soloed) implements TrackCommand {

    /** @throws NullPointerException if {@code channel} is {@code null} */
    public ToggleChannelSoloCommand {
        Objects.requireNonNull(channel, "channel must not be null");
    }

    @Override
    public void execute(TrackIntentHandler handler) {
        handler.toggleChannelSolo(channel, soloed);
    }
}
