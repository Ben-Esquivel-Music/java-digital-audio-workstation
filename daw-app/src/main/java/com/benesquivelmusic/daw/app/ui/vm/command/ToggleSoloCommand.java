package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.track.Track;

import java.util.Objects;

/**
 * Intent to set a track's solo flag (§1.3, §5.2). Raised by the arrangement
 * lane solo button and the mixer-strip solo button — both targeting the same
 * track. The handler mirrors the new state onto the paired mixer channel, and
 * the registry recomputes every channel's effective mute from the new
 * project-wide solo state.
 *
 * @param track  the target track; must not be {@code null}
 * @param soloed the requested solo state
 */
public record ToggleSoloCommand(Track track, boolean soloed) implements TrackCommand {

    /** @throws NullPointerException if {@code track} is {@code null} */
    public ToggleSoloCommand {
        Objects.requireNonNull(track, "track must not be null");
    }

    @Override
    public void execute(TrackIntentHandler handler) {
        handler.toggleSolo(track, soloed);
    }
}
