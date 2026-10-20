package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that splits an {@link AudioClip} at a given beat position.
 *
 * <p>Executing this action splits the clip into two parts: the original clip
 * is truncated to end at the split point, and a new clip covering the
 * remainder is added to the same track. Undoing the action restores the
 * original clip's properties and removes the second clip from the track.
 * Both halves keep the clip's comp owner ({@link AudioClip#splitAt}), so a
 * recompile replaces both.</p>
 *
 * <p>A split whose clip is no longer on the track when it runs does nothing,
 * and neither does its undo. That happens to a redo of a split of comp
 * output: split, swipe, undo the swipe, undo the split, redo the split —
 * the swipe's undo recompiled the main lane, so the clip the split names
 * left the track. The defence against a stray half playing over the fresh
 * comp is {@link Track#addClip}, which refuses comp output a recompile
 * replaced; this guard keeps the off-track clip unmutated and the clip
 * events honest.</p>
 */
public final class SplitClipAction implements UndoableAction {

    private final Track track;
    private final AudioClip clip;
    private final double splitBeat;

    // Snapshot for undo
    private double originalDuration;
    private double originalFadeOutBeats;
    private AudioClip secondClip;
    /** Whether the last {@link #execute()} split the clip; its undo reverts only then. */
    private boolean applied;

    /**
     * Creates a new split-clip action.
     *
     * @param track     the track containing the clip
     * @param clip      the clip to split
     * @param splitBeat the beat position at which to split
     */
    public SplitClipAction(Track track, AudioClip clip, double splitBeat) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.splitBeat = splitBeat;
    }

    @Override
    public String description() {
        return "Split Clip";
    }

    @Override
    public void execute() {
        applied = track.getClips().contains(clip);
        if (!applied) {
            return;
        }
        originalDuration = clip.getDurationBeats();
        originalFadeOutBeats = clip.getFadeOutBeats();
        secondClip = clip.splitAt(splitBeat);
        track.addClip(secondClip);
        // Compound-order semantics: trim the original clip first, then
        // surface the new sibling. Subscribers can rely on this ordering
        // to treat the pair as a single split when they share a track.
        UUID trackId = UUID.fromString(track.getId());
        Instant now = Instant.now();
        EventBusPublisher.publish(new ClipEvent.Trimmed(
                trackId, UUID.fromString(clip.getId()), now));
        EventBusPublisher.publish(new ClipEvent.Added(
                trackId, UUID.fromString(secondClip.getId()), now));
    }

    @Override
    public void undo() {
        if (!applied) {
            return;
        }
        applied = false;
        track.removeClip(secondClip);
        clip.setDurationBeats(originalDuration);
        clip.setFadeOutBeats(originalFadeOutBeats);
        // Reverse the execute order: remove the sibling first, then
        // surface the restored trim on the original clip.
        UUID trackId = UUID.fromString(track.getId());
        Instant now = Instant.now();
        EventBusPublisher.publish(new ClipEvent.Removed(
                trackId, UUID.fromString(secondClip.getId()), now));
        EventBusPublisher.publish(new ClipEvent.Trimmed(
                trackId, UUID.fromString(clip.getId()), now));
    }
}
