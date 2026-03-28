package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that adds a MIDI note to a {@link MidiClip}.
 */
public final class AddMidiNoteAction implements UndoableAction {

    private final MidiClip clip;
    private final MidiNoteData note;

    /**
     * Creates a new add-note action.
     *
     * @param clip the clip to add the note to
     * @param note the note to add
     */
    public AddMidiNoteAction(MidiClip clip, MidiNoteData note) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.note = Objects.requireNonNull(note, "note must not be null");
    }

    @Override
    public String description() {
        return "Add MIDI Note";
    }

    @Override
    public void execute() {
        clip.addNote(note);
    }

    @Override
    public void undo() {
        clip.removeNote(note);
    }
}
