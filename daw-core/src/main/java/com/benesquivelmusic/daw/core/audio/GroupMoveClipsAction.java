package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.clip.LockedClipException;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;
import com.benesquivelmusic.daw.sdk.event.ClipEvent;

import java.time.Instant;
import java.util.*;

/**
 * An undoable action that moves multiple {@link AudioClip}s by a common
 * beat delta, optionally transferring them to different tracks (cross-track
 * group move).
 *
 * <p>When {@code trackDelta} is zero, every clip simply shifts its start
 * beat by {@code beatDelta}. When {@code trackDelta} is non-zero, each clip
 * is removed from its current track and added to a track that is
 * {@code trackDelta} positions away in the provided track list.</p>
 *
 * <p>Clips whose target track index would fall outside the track list
 * bounds are left on their original track (only the beat shift is applied).</p>
 *
 * <p>A comp clip ({@link AudioClip#compOutputOwner()}) moved to another track
 * keeps its owner, as with {@link CrossTrackMoveAction}: the owner is inert
 * on another track, where the clip behaves as a user clip. The move clears
 * it only when the destination track's comping holds the clip as
 * superseded output, so such a clip lands as a user clip instead of being
 * refused. The undo puts the saved owner back before it returns the clip
 * to its original track, where {@link Track#addClip} refuses it if a
 * recompile has replaced it since (PR #984 review).</p>
 *
 * <p>As in {@link CrossTrackMoveAction}, whether the move clears a clip's
 * owner is decided once per clip, on the first {@link #execute()}, and a
 * redo replays that decision: a clip whose owner the first execute kept
 * keeps the owner the undo restored, so {@link Track#addClip} refuses it if
 * a recompile on the destination has replaced it since, and the destination
 * ends without it, as that recompile left it after the original move.
 * Deciding again would land the replaced clip as a user clip; when it sits
 * where it was sliced from and the comp still selects its lane there, that
 * range plays twice. Each entry replays its own decision.</p>
 */
public final class GroupMoveClipsAction implements UndoableAction {

    private final List<Map.Entry<Track, AudioClip>> entries;
    private final double beatDelta;
    private final int trackDelta;
    private final List<Track> allTracks;

    // Captured state for undo
    private final List<Double> previousStartBeats = new ArrayList<>();
    private final List<Track> previousTracks = new ArrayList<>();
    private final List<Track> newTracks = new ArrayList<>();
    /** Each clip's comp owner before the move ({@code null} for a user clip). */
    private final List<CompOutputOwner> previousOwners = new ArrayList<>();
    /**
     * Per entry, whether the cross-track move clears the clip's comp owner.
     * The array is {@code null} until the first {@link #execute()} decides
     * every entry; every redo then replays it. Same-track entries stay
     * {@code false}.
     */
    private boolean[] clearsOwners;

    /**
     * Creates a new group-move action.
     *
     * @param entries    the (track, clip) pairs to move; the list is
     *                   defensively copied and must not be empty
     * @param beatDelta  the beat offset to apply to every clip
     * @param trackDelta the track offset (positive = down, negative = up);
     *                   zero means same-track move
     * @param allTracks  the full ordered track list used to resolve cross-track
     *                   destinations; may be {@code null} or empty, in which
     *                   case cross-track movement is silently disabled even
     *                   when {@code trackDelta != 0}
     * @throws NullPointerException     if {@code entries} is {@code null}
     * @throws IllegalArgumentException if {@code entries} is empty
     */
    public GroupMoveClipsAction(List<Map.Entry<Track, AudioClip>> entries,
                                double beatDelta,
                                int trackDelta,
                                List<Track> allTracks) {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.beatDelta = beatDelta;
        this.trackDelta = trackDelta;
        this.allTracks = allTracks != null
                ? Collections.unmodifiableList(new ArrayList<>(allTracks))
                : List.of();
    }

    @Override
    public String description() {
        return "Move Clips";
    }

    @Override
    public void execute() {
        // Atomic lock check: refuse the entire group if any clip is
        // locked, so the model is never half-mutated.
        int lockedCount = 0;
        for (Map.Entry<Track, AudioClip> entry : entries) {
            if (entry.getValue().isLocked()) {
                lockedCount++;
            }
        }
        if (lockedCount > 0) {
            throw new LockedClipException("Move", lockedCount);
        }

        previousStartBeats.clear();
        previousTracks.clear();
        newTracks.clear();
        previousOwners.clear();

        // Clamp the beat delta once for the whole group so that the
        // earliest clip does not go below beat 0 and relative spacing
        // is preserved.
        double minimumStartBeat = Double.POSITIVE_INFINITY;
        for (Map.Entry<Track, AudioClip> entry : entries) {
            minimumStartBeat = Math.min(minimumStartBeat, entry.getValue().getStartBeat());
        }
        double effectiveBeatDelta = Math.max(beatDelta, -minimumStartBeat);

        // Precompute track-index map for O(1) cross-track lookups.
        Map<Track, Integer> trackIndex;
        if (trackDelta != 0 && !allTracks.isEmpty()) {
            trackIndex = new HashMap<>(allTracks.size());
            for (int i = 0; i < allTracks.size(); i++) {
                trackIndex.put(allTracks.get(i), i);
            }
        } else {
            trackIndex = Map.of();
        }

        boolean firstExecute = clearsOwners == null;
        if (firstExecute) {
            clearsOwners = new boolean[entries.size()];
        }

        for (int i = 0; i < entries.size(); i++) {
            Track sourceTrack = entries.get(i).getKey();
            AudioClip clip = entries.get(i).getValue();
            previousStartBeats.add(clip.getStartBeat());
            previousTracks.add(sourceTrack);
            previousOwners.add(clip.compOutputOwner().orElse(null));

            double newStart = clip.getStartBeat() + effectiveBeatDelta;
            Track targetTrack = sourceTrack;

            if (trackDelta != 0 && !allTracks.isEmpty()) {
                Integer sourceIdx = trackIndex.get(sourceTrack);
                if (sourceIdx != null) {
                    int targetIdx = sourceIdx + trackDelta;
                    if (targetIdx >= 0 && targetIdx < allTracks.size()) {
                        targetTrack = allTracks.get(targetIdx);
                    }
                }
            }

            if (targetTrack != sourceTrack) {
                boolean removed = sourceTrack.removeClip(clip);
                clip.setStartBeat(newStart);
                if (firstExecute) {
                    // Output a recompile replaced while the clip was away: the
                    // user's move lands it as a user clip, not refused. Decided
                    // now only; a redo replays it (see the class doc).
                    clearsOwners[i] = targetTrack.getTakeComping().isSupersededOutput(clip);
                }
                if (clearsOwners[i]) {
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
            } else {
                clip.setStartBeat(newStart);
                EventBusPublisher.publish(new ClipEvent.Moved(
                        UUID.fromString(sourceTrack.getId()),
                        UUID.fromString(clip.getId()),
                        Instant.now()));
            }
            newTracks.add(targetTrack);
        }
    }

    @Override
    public void undo() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            AudioClip clip = entries.get(i).getValue();
            Track currentTrack = newTracks.get(i);
            Track originalTrack = previousTracks.get(i);
            double originalStart = previousStartBeats.get(i);

            if (currentTrack != originalTrack) {
                // False when a recompile on that track took the clip off it since.
                boolean removed = currentTrack.removeClip(clip);
                clip.setStartBeat(originalStart);
                clip.setCompOutputOwner(previousOwners.get(i));
                // Refused when a recompile replaced the clip since (Track.addClip).
                boolean added = originalTrack.addClip(clip);
                Instant now = Instant.now();
                UUID clipId = UUID.fromString(clip.getId());
                if (removed) {
                    EventBusPublisher.publish(new ClipEvent.Removed(
                            UUID.fromString(currentTrack.getId()), clipId, now));
                }
                if (added) {
                    EventBusPublisher.publish(new ClipEvent.Added(
                            UUID.fromString(originalTrack.getId()), clipId, now));
                }
            } else {
                clip.setStartBeat(originalStart);
                EventBusPublisher.publish(new ClipEvent.Moved(
                        UUID.fromString(originalTrack.getId()),
                        UUID.fromString(clip.getId()),
                        Instant.now()));
            }
        }
    }
}
