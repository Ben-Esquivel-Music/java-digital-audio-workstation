package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An undoable action that duplicates selected {@link AudioClip}s,
 * placing each copy immediately after the original on the same track.
 *
 * <p>Executing this action creates a duplicate of each clip and positions
 * it so that it starts where the original ends (i.e. at
 * {@code original.getStartBeat() + original.getDurationBeats()}).
 * Undoing removes the duplicated clips.</p>
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
        for (Map.Entry<Track, AudioClip> entry : entries) {
            Track track = entry.getKey();
            AudioClip original = entry.getValue();
            AudioClip duplicate = original.duplicate();
            duplicate.setStartBeat(original.getStartBeat() + original.getDurationBeats());
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
