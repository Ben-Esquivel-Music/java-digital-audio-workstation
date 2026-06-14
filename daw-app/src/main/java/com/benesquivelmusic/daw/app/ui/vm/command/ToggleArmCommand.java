package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.track.Track;

import java.util.Objects;

/**
 * Intent to set a track's armed (record-ready) flag (§5.2). Arm is track-only —
 * there is no mixer-channel armed state — so unlike mute/solo this command
 * announces only a {@code TrackEvent.Armed}.
 *
 * @param track the target track; must not be {@code null}
 * @param armed the requested armed state
 */
public record ToggleArmCommand(Track track, boolean armed) implements TrackCommand {

    /** @throws NullPointerException if {@code track} is {@code null} */
    public ToggleArmCommand {
        Objects.requireNonNull(track, "track must not be null");
    }

    @Override
    public void execute(TrackIntentHandler handler) {
        handler.toggleArm(track, armed);
    }
}
