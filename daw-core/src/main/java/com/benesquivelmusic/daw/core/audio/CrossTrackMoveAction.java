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
 *
 * <p>A comp clip ({@link AudioClip#compOutputOwner()}) keeps its owner when
 * it moves to another track. There the owner is inert: only the owning
 * comping's own track scans for its output ({@link
 * com.benesquivelmusic.daw.core.comping.TakeComping#compOutputOn}) or
 * refuses it ({@link Track#addClip}), and another track's comping never
 * counts it as its own, so on another track the clip behaves as a user
 * clip: a swipe there never touches it. Moved back to its own track before
 * a recompile there, it is that comp's output again, and the next swipe
 * replaces it. The move clears the owner only when the destination track's
 * comping holds the clip as superseded output ({@link
 * com.benesquivelmusic.daw.core.comping.TakeComping#isSupersededOutput}) —
 * output a recompile replaced while the clip was away — so a deliberate
 * move of such a clip lands there as a user clip instead of being refused.
 * The undo puts the saved owner back before it returns the clip to the
 * source track; that re-add goes through {@link Track#addClip}, which
 * refuses the clip when a recompile there has replaced it since, so the
 * source track keeps the comp that recompile left (PR #984 review).</p>
 *
 * <p>Whether the move clears the owner is decided once, on the first
 * {@link #execute()}, against the comping state the user made the move in;
 * a redo replays that decision rather than deciding again. A redo of a
 * move that cleared the owner clears it again. A redo of a move that kept
 * it keeps the owner the undo restored, so {@link Track#addClip} refuses
 * the clip if a recompile on the destination has replaced it since, and the
 * destination ends without the clip, as that recompile left it after the
 * original move. Deciding again at redo time would instead land the
 * replaced clip as a user clip; when it sits where it was sliced from and
 * the comp still selects its lane there, that range plays twice.</p>
 */
public final class CrossTrackMoveAction implements UndoableAction {

    private final Track sourceTrack;
    private final Track targetTrack;
    private final AudioClip clip;
    private final double newStartBeat;
    private double previousStartBeat;
    /** The comp owner the clip had before the move; restored by {@link #undo()}. */
    private CompOutputOwner previousOwner;
    /**
     * Whether the move clears the clip's comp owner: {@code null} until the
     * first {@link #execute()} decides it, then replayed by every redo.
     */
    private Boolean clearsOwner;

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
        previousOwner = clip.compOutputOwner().orElse(null);
        boolean removed = sourceTrack.removeClip(clip);
        clip.setStartBeat(newStartBeat);
        if (clearsOwner == null) {
            // Output a recompile replaced while the clip was away: the
            // user's move lands it as a user clip, not refused. Decided
            // now only; a redo replays it (see the class doc).
            clearsOwner = targetTrack.getTakeComping().isSupersededOutput(clip);
        }
        if (clearsOwner) {
            clip.setCompOutputOwner(null);
        }
        boolean added = targetTrack.addClip(clip);
        Instant now = Instant.now();
        UUID clipId = UUID.fromString(clip.getId());
        if (removed) {
            EventBusPublisher.publish(new ClipEvent.Removed(
                    UUID.fromString(sourceTrack.getId()), clipId, now));
        }
        if (added) {
            EventBusPublisher.publish(new ClipEvent.Added(
                    UUID.fromString(targetTrack.getId()), clipId, now));
        }
    }

    @Override
    public void undo() {
        // False when a recompile on the target took the clip off it since.
        boolean removed = targetTrack.removeClip(clip);
        clip.setStartBeat(previousStartBeat);
        clip.setCompOutputOwner(previousOwner);
        // Refused when a recompile replaced the clip since (Track.addClip).
        boolean added = sourceTrack.addClip(clip);
        Instant now = Instant.now();
        UUID clipId = UUID.fromString(clip.getId());
        if (removed) {
            EventBusPublisher.publish(new ClipEvent.Removed(
                    UUID.fromString(targetTrack.getId()), clipId, now));
        }
        if (added) {
            EventBusPublisher.publish(new ClipEvent.Added(
                    UUID.fromString(sourceTrack.getId()), clipId, now));
        }
    }
}
