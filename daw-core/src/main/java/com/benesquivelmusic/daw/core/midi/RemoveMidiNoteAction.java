package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that removes a MIDI note from a {@link MidiClip}.
 */
public final class RemoveMidiNoteAction implements UndoableAction {

    private final MidiClip clip;
    private final MidiNoteData note;
    private int removedIndex = -1;

    /**
     * Creates a new remove-note action.
     *
     * @param clip the clip to remove the note from
     * @param note the note to remove
     */
    public RemoveMidiNoteAction(MidiClip clip, MidiNoteData note) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.note = Objects.requireNonNull(note, "note must not be null");
    }

    @Override
    public String description() {
        return "Delete MIDI Note";
    }

    @Override
    public void execute() {
        removedIndex = clip.indexOf(note);
        clip.removeNote(note);
    }

    @Override
    public void undo() {
        clip.addNote(note);
    }
}
