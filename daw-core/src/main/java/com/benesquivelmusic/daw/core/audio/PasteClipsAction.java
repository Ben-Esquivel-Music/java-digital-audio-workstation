package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.*;

/**
 * An undoable action that pastes {@link AudioClip}s at a given playhead
 * position.
 *
 * <p>Each source clip is duplicated and repositioned so that the earliest
 * clip in the group lands on the playhead beat, while the relative spacing
 * between clips is preserved. The duplicated clips are added to a target
 * track (paste-to-different-track) <em>or</em> back to their original source
 * tracks.</p>
 *
 * <p>Undoing removes the pasted clips; redoing re-adds them.</p>
 */
public final class PasteClipsAction implements UndoableAction {

    private final List<Map.Entry<Track, AudioClip>> sourceEntries;
    private final Track targetTrack;
    private final double playheadBeat;

    private final List<Map.Entry<Track, AudioClip>> pastedEntries = new ArrayList<>();

    /**
     * Creates a new paste-clips action.
     *
     * @param sourceEntries the original (track, clip) pairs that were copied
     *                      or cut; used to compute relative positions and as
     *                      the default destination tracks when
     *                      {@code targetTrack} is {@code null}
     * @param targetTrack   the track to paste onto, or {@code null} to paste
     *                      each clip back to its original source track
     * @param playheadBeat  the beat position of the playhead (paste point)
     * @throws NullPointerException     if {@code sourceEntries} is {@code null}
     * @throws IllegalArgumentException if {@code sourceEntries} is empty or
     *                                  {@code playheadBeat} is negative
     */
    public PasteClipsAction(List<Map.Entry<Track, AudioClip>> sourceEntries,
                            Track targetTrack,
                            double playheadBeat) {
        Objects.requireNonNull(sourceEntries, "sourceEntries must not be null");
        if (sourceEntries.isEmpty()) {
            throw new IllegalArgumentException("sourceEntries must not be empty");
        }
        if (playheadBeat < 0) {
            throw new IllegalArgumentException(
                    "playheadBeat must not be negative: " + playheadBeat);
        }
        this.sourceEntries = Collections.unmodifiableList(new ArrayList<>(sourceEntries));
        this.targetTrack = targetTrack;
        this.playheadBeat = playheadBeat;
    }

    @Override
    public String description() {
        return "Paste Clips";
    }

    @Override
    public void execute() {
        pastedEntries.clear();

        double earliestBeat = Double.MAX_VALUE;
        for (Map.Entry<Track, AudioClip> entry : sourceEntries) {
            double start = entry.getValue().getStartBeat();
            if (start < earliestBeat) {
                earliestBeat = start;
            }
        }
        double offset = playheadBeat - earliestBeat;

        for (Map.Entry<Track, AudioClip> entry : sourceEntries) {
            AudioClip duplicate = entry.getValue().duplicate();
            duplicate.setStartBeat(entry.getValue().getStartBeat() + offset);

            Track destination = targetTrack != null ? targetTrack : entry.getKey();
            destination.addClip(duplicate);
            pastedEntries.add(Map.entry(destination, duplicate));
        }
    }

    @Override
    public void undo() {
        for (Map.Entry<Track, AudioClip> entry : pastedEntries) {
            entry.getKey().removeClip(entry.getValue());
        }
    }
}
