package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.track.Track;

import java.util.Objects;

/**
 * Intent to set a track's muted flag (§1.3, §5.2). Raised by the arrangement
 * lane mute button and the mixer-strip mute button — both targeting the same
 * track, so one flag drives both surfaces. The handler mirrors the new state
 * onto the paired mixer channel.
 *
 * @param track the target track; must not be {@code null}
 * @param muted the requested mute state
 */
public record ToggleMuteCommand(Track track, boolean muted) implements TrackCommand {

    /** @throws NullPointerException if {@code track} is {@code null} */
    public ToggleMuteCommand {
        Objects.requireNonNull(track, "track must not be null");
    }

    @Override
    public void execute(TrackIntentHandler handler) {
        handler.toggleMute(track, muted);
    }
}
