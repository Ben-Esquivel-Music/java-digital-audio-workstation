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
 * An undoable action that moves an {@link AudioClip} from one track to another,
 * optionally updating the clip's start beat position.
 *
 * <p>Executing this action removes the clip from the source track, updates its
 * start beat, and adds it to the target track. Undoing reverses the operation.</p>
 */
public final class CrossTrackMoveAction implements UndoableAction {

    private final Track sourceTrack;
    private final Track targetTrack;
    private final AudioClip clip;
    private final double newStartBeat;
    private double previousStartBeat;

    /**
     * Creates a new cross-track move action.
     *
     * @param sourceTrack  the track currently containing the clip
     * @param targetTrack  the track to move the clip to
     * @param clip         the audio clip to move
     * @param newStartBeat the new start beat position for the clip
     */
    public CrossTrackMoveAction(Track sourceTrack, Track targetTrack,
                                AudioClip clip, double newStartBeat) {
        this.sourceTrack = Objects.requireNonNull(sourceTrack, "sourceTrack must not be null");
        this.targetTrack = Objects.requireNonNull(targetTrack, "targetTrack must not be null");
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.newStartBeat = newStartBeat;
    }

    @Override
    public String description() {
        return "Move Clip to Track";
    }

    @Override
    public void execute() {
        LockedClipException.requireUnlocked("Cross-track move", clip);
        previousStartBeat = clip.getStartBeat();
        sourceTrack.removeClip(clip);
        clip.setStartBeat(newStartBeat);
        targetTrack.addClip(clip);
        Instant now = Instant.now();
        UUID clipId = UUID.fromString(clip.getId());
        EventBusPublisher.publish(new ClipEvent.Removed(
                UUID.fromString(sourceTrack.getId()), clipId, now));
        EventBusPublisher.publish(new ClipEvent.Added(
                UUID.fromString(targetTrack.getId()), clipId, now));
    }

    @Override
    public void undo() {
        targetTrack.removeClip(clip);
        clip.setStartBeat(previousStartBeat);
        sourceTrack.addClip(clip);
        Instant now = Instant.now();
        UUID clipId = UUID.fromString(clip.getId());
        EventBusPublisher.publish(new ClipEvent.Removed(
                UUID.fromString(targetTrack.getId()), clipId, now));
        EventBusPublisher.publish(new ClipEvent.Added(
                UUID.fromString(sourceTrack.getId()), clipId, now));
    }
}
