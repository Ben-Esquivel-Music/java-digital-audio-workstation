package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An undoable action that glues (merges) two adjacent {@link AudioClip}s on
 * the same track into a single clip.
 *
 * <p>Executing this action removes both clips from the track and adds a new
 * merged clip that spans from the start of the first clip to the end of the
 * second clip. Undoing restores the original two clips.</p>
 *
 * <p>The merged clip is comp output only if both clips are output of the
 * same comp ({@link AudioClip#compOutputOwner()}) — two halves of a split
 * comp clip glued back — so the next recompile replaces it; a glue of
 * anything else is a user clip.</p>
 *
 * <p>A glue whose clips are not both on the track when it runs does
 * nothing, and its undo does nothing unless the merged clip is still on the
 * track. A recompile in between can have replaced them: glue two comp
 * halves, swipe, undo the swipe — the swipe's undo recompiled the main
 * lane, so the merged clip is gone. The defence against the comp then
 * playing twice is {@link Track#addClip}, which refuses comp output a
 * recompile replaced; these guards keep off-track clips unmutated and the
 * clip events honest — no merged clip is built, and nothing is announced,
 * for clips that are not there.</p>
 */
public final class GlueClipsAction implements UndoableAction {

    private final Track track;
    private final AudioClip first;
    private final AudioClip second;

    private AudioClip merged;
    /** Whether the last {@link #execute()} glued the clips; its undo reverts only then. */
    private boolean applied;

    /** Tolerance for comparing beat positions (accommodates floating-point imprecision). */
    private static final double BEAT_EPSILON = 0.001;

    /**
     * Creates a new glue-clips action.
     *
     * @param track  the track containing both clips
     * @param first  the earlier clip (by start beat)
     * @param second the later clip (by start beat)
     * @throws IllegalArgumentException if the clips are the same instance, are not
     *         in order, are not adjacent (i.e. the first clip's end beat does not
     *         approximately equal the second clip's start beat), or do not share
     *         the same source file path, source segment list and audio data buffer
     */
    public GlueClipsAction(Track track, AudioClip first, AudioClip second) {
        this.track = Objects.requireNonNull(track, "track must not be null");
        this.first = Objects.requireNonNull(first, "first must not be null");
        this.second = Objects.requireNonNull(second, "second must not be null");

        if (first == second) {
            throw new IllegalArgumentException("first and second must be distinct clip instances");
        }
        if (first.getStartBeat() > second.getStartBeat()) {
            throw new IllegalArgumentException(
                    "first clip must start before or at the same beat as second clip");
        }
        if (Math.abs(first.getEndBeat() - second.getStartBeat()) > BEAT_EPSILON) {
            throw new IllegalArgumentException(
                    "clips must be adjacent: first ends at " + first.getEndBeat()
                            + " but second starts at " + second.getStartBeat());
        }
        if (!Objects.equals(first.getSourceFilePath(), second.getSourceFilePath())) {
            throw new IllegalArgumentException(
                    "clips must share the same source file path to be glued");
        }
        // A recorded take's source is its whole segment list, of which the
        // source file path is only the first element (story 323).
        if (!first.getSourceSegmentPaths().equals(second.getSourceSegmentPaths())) {
            throw new IllegalArgumentException(
                    "clips must share the same source segment list to be glued");
        }
        if (first.getAudioData() != second.getAudioData()) {
            throw new IllegalArgumentException(
                    "clips must share the same audio data buffer to be glued");
        }
    }

    @Override
    public String description() {
        return "Glue Clips";
    }

    @Override
    public void execute() {
        applied = track.getClips().contains(first) && track.getClips().contains(second);
        if (!applied) {
            return;
        }
        double mergedStart = first.getStartBeat();
        double mergedDuration = second.getEndBeat() - first.getStartBeat();
        merged = new AudioClip(first.getName(), mergedStart, mergedDuration,
                first.getSourceFilePath());
        // Both halves reference the same segments (checked at construction);
        // the merged clip must keep referencing every one of them.
        merged.setSourceSegmentPaths(first.getSourceSegmentPaths());
        merged.setSourceOffsetBeats(first.getSourceOffsetBeats());
        merged.setGainDb(first.getGainDb());
        merged.setReversed(first.isReversed());
        merged.setFadeInBeats(first.getFadeInBeats());
        merged.setFadeInCurveType(first.getFadeInCurveType());
        merged.setFadeOutBeats(second.getFadeOutBeats());
        merged.setFadeOutCurveType(second.getFadeOutCurveType());
        merged.setTimeStretchRatio(first.getTimeStretchRatio());
        merged.setPitchShiftSemitones(first.getPitchShiftSemitones());
        merged.setStretchQuality(first.getStretchQuality());
        merged.setAudioData(first.getAudioData());
        // Comp output only when both clips are the same comp's output.
        merged.setCompOutputOwner(sameCompOutputOwner(first, second));

        track.removeClip(first);
        track.removeClip(second);
        track.addClip(merged);
        UUID trackId = UUID.fromString(track.getId());
        Instant now = Instant.now();
        EventBusPublisher.publish(new ClipEvent.Removed(
                trackId, UUID.fromString(first.getId()), now));
        EventBusPublisher.publish(new ClipEvent.Removed(
                trackId, UUID.fromString(second.getId()), now));
        EventBusPublisher.publish(new ClipEvent.Added(
                trackId, UUID.fromString(merged.getId()), now));
    }

    @Override
    public void undo() {
        if (!applied) {
            return;
        }
        applied = false;
        if (!track.removeClip(merged)) {
            // A recompile replaced the merged clip: its halves are not put
            // back over the comp the main lane holds now.
            return;
        }
        boolean firstAdded = track.addClip(first);
        boolean secondAdded = track.addClip(second);
        UUID trackId = UUID.fromString(track.getId());
        Instant now = Instant.now();
        EventBusPublisher.publish(new ClipEvent.Removed(
                trackId, UUID.fromString(merged.getId()), now));
        if (firstAdded) {
            EventBusPublisher.publish(new ClipEvent.Added(
                    trackId, UUID.fromString(first.getId()), now));
        }
        if (secondAdded) {
            EventBusPublisher.publish(new ClipEvent.Added(
                    trackId, UUID.fromString(second.getId()), now));
        }
    }

    /**
     * The comp both clips are output of, or {@code null} unless they share
     * one. The owner is a comp's compile pass, so one owner means one
     * comping and one compile: on that comping's own track, pieces of the
     * comp it holds now; on another track, where a moved comp clip keeps its
     * owner, pieces of that pass's output, whose tag is inert there.
     */
    private static CompOutputOwner sameCompOutputOwner(AudioClip first, AudioClip second) {
        CompOutputOwner owner = first.compOutputOwner().orElse(null);
        return owner != null && second.isCompOutputOf(owner) ? owner : null;
    }
}
