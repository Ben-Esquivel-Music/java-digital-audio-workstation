package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that adds an {@link AudioClip} to a {@link Track}.
 *
 * <p>Executing this action adds the clip to the track. Undoing it removes
 * the clip.</p>
 */
public final class AddClipAction implements UndoableAction {

    private final Track track;
    private final AudioClip clip;

    /**
     * Creates a new add-clip action.
     *
     * @param track the track to add the clip to
     * @param clip  the audio clip to add
     */
    public AddClipAction(Track track, AudioClip clip) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
    }

    @Override
    public String description() {
        return "Add Clip";
    }

    @Override
    public void execute() {
        track.addClip(clip);
        EventBusPublisher.publish(new ClipEvent.Added(
                UUID.fromString(track.getId()),
                UUID.fromString(clip.getId()),
                Instant.now()));
    }

    @Override
    public void undo() {
        track.removeClip(clip);
        EventBusPublisher.publish(new ClipEvent.Removed(
                UUID.fromString(track.getId()),
                UUID.fromString(clip.getId()),
                Instant.now()));
    }
}
