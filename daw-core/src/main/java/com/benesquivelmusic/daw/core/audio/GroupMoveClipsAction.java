package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        previousStartBeats.clear();
        previousTracks.clear();
        newTracks.clear();

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

        for (Map.Entry<Track, AudioClip> entry : entries) {
            Track sourceTrack = entry.getKey();
            AudioClip clip = entry.getValue();
            previousStartBeats.add(clip.getStartBeat());
            previousTracks.add(sourceTrack);

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
                sourceTrack.removeClip(clip);
                clip.setStartBeat(newStart);
                targetTrack.addClip(clip);
            } else {
                clip.setStartBeat(newStart);
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
                currentTrack.removeClip(clip);
                clip.setStartBeat(originalStart);
                originalTrack.addClip(clip);
            } else {
                clip.setStartBeat(originalStart);
            }
        }
    }
}
