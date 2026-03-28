package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An undoable action that removes (cuts) multiple {@link AudioClip}s from
 * their respective {@link Track}s in a single operation.
 *
 * <p>Executing this action removes every clip from its track. Undoing it
 * re-adds every clip to its original track.</p>
 */
public final class CutClipsAction implements UndoableAction {

    private final List<Map.Entry<Track, AudioClip>> entries;

    /**
     * Creates a new cut-clips action.
     *
     * @param entries the (track, clip) pairs to cut; the list is defensively
     *                copied and must not be empty
     * @throws NullPointerException     if {@code entries} is {@code null}
     * @throws IllegalArgumentException if {@code entries} is empty
     */
    public CutClipsAction(List<Map.Entry<Track, AudioClip>> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    @Override
    public String description() {
        return "Cut Clips";
    }

    @Override
    public void execute() {
        for (Map.Entry<Track, AudioClip> entry : entries) {
            entry.getKey().removeClip(entry.getValue());
        }
    }

    @Override
    public void undo() {
        for (Map.Entry<Track, AudioClip> entry : entries) {
            entry.getKey().addClip(entry.getValue());
        }
    }
}
