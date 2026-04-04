package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An undoable action that duplicates selected {@link AudioClip}s as a group.
 *
 * <p>Executing this action creates a duplicate of each selected clip and
 * places the duplicated group immediately after the rightmost end beat of
 * the original selection while preserving relative spacing and source-track
 * assignments. Undoing removes the duplicated clips.</p>
 */
public final class DuplicateClipsAction implements UndoableAction {

    private final List<Map.Entry<Track, AudioClip>> entries;
    private final List<Map.Entry<Track, AudioClip>> duplicatedEntries = new ArrayList<>();

    /**
     * Creates a new duplicate-clips action.
     *
     * @param entries the (track, clip) pairs to duplicate; the list is
     *                defensively copied and must not be empty
     * @throws NullPointerException     if {@code entries} is {@code null}
     * @throws IllegalArgumentException if {@code entries} is empty
     */
    public DuplicateClipsAction(List<Map.Entry<Track, AudioClip>> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    @Override
    public String description() {
        return "Duplicate Clips";
    }

    @Override
    public void execute() {
        duplicatedEntries.clear();

        double earliestStartBeat = Double.MAX_VALUE;
        double rightmostEndBeat = Double.MIN_VALUE;
        for (Map.Entry<Track, AudioClip> entry : entries) {
            AudioClip original = entry.getValue();
            earliestStartBeat = Math.min(earliestStartBeat, original.getStartBeat());
            rightmostEndBeat = Math.max(rightmostEndBeat, original.getEndBeat());
        }
        double groupShift = rightmostEndBeat - earliestStartBeat;

        for (Map.Entry<Track, AudioClip> entry : entries) {
            Track track = entry.getKey();
            AudioClip original = entry.getValue();
            AudioClip duplicate = original.duplicate();
            duplicate.setStartBeat(original.getStartBeat() + groupShift);
            track.addClip(duplicate);
            duplicatedEntries.add(Map.entry(track, duplicate));
        }
    }

    @Override
    public void undo() {
        for (Map.Entry<Track, AudioClip> entry : duplicatedEntries) {
            entry.getKey().removeClip(entry.getValue());
        }
    }
}
