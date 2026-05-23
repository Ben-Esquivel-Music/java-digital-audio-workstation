package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.clip.LockedClipException;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that moves an {@link AudioClip} to a new start beat.
 *
 * <p>Executing this action repositions the clip on the timeline. Undoing it
 * restores the original start beat.</p>
 *
 * <p>Story 283 — the owning {@link Track} is now a constructor
 * parameter so {@code execute()}/{@code undo()} can publish
 * {@code ClipEvent.Moved(trackId, clipId, …)}. The previous 2-arg
 * constructor was removed (user accepts breaking API per the
 * "Replace internals over sibling-class additions" rule).</p>
 */
public final class MoveClipAction implements UndoableAction {

    private final Track track;
    private final AudioClip clip;
    private final double newStartBeat;
    private double previousStartBeat;

    /**
     * Creates a new move-clip action.
     *
     * @param track        the track that owns the clip (required for
     *                     event publication)
     * @param clip         the clip to move
     * @param newStartBeat the new start beat position
     */
    public MoveClipAction(Track track, AudioClip clip, double newStartBeat) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.newStartBeat = newStartBeat;
    }

    @Override
    public String description() {
        return "Move Clip";
    }

    @Override
    public void execute() {
        LockedClipException.requireUnlocked("Move", clip);
        previousStartBeat = clip.getStartBeat();
        clip.setStartBeat(newStartBeat);
        EventBusPublisher.publish(new ClipEvent.Moved(
                UUID.fromString(track.getId()),
                UUID.fromString(clip.getId()),
                Instant.now()));
    }

    @Override
    public void undo() {
        clip.setStartBeat(previousStartBeat);
        EventBusPublisher.publish(new ClipEvent.Moved(
                UUID.fromString(track.getId()),
                UUID.fromString(clip.getId()),
                Instant.now()));
    }
}
