package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.track.Track;

import java.util.Objects;

/**
 * Intent to select {@code track} as the focused track (§5.5). Raised by a click
 * on a track lane or mixer strip, by the keyboard, or by the cascade restoring
 * selection on load.
 *
 * @param track the track to select; must not be {@code null} (use
 *              {@link ClearSelectionCommand} to deselect)
 */
public record SelectTrackCommand(Track track) implements SelectionCommand {

    /** @throws NullPointerException if {@code track} is {@code null} */
    public SelectTrackCommand {
        Objects.requireNonNull(track, "track must not be null");
    }

    @Override
    public void execute(SelectionIntentHandler handler) {
        handler.selectTrack(track);
    }
}
