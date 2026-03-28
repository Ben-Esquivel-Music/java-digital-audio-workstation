package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that moves a MIDI note to a new position within a
 * {@link MidiClip}.
 *
 * <p>Moving changes the note number (pitch) and/or start column (time
 * position). The original note is replaced with the moved version.</p>
 */
public final class MoveMidiNoteAction implements UndoableAction {

    private final MidiClip clip;
    private final MidiNoteData originalNote;
    private final MidiNoteData movedNote;

    /**
     * Creates a new move-note action.
     *
     * @param clip         the clip containing the note
     * @param originalNote the note before the move
     * @param movedNote    the note after the move
     */
    public MoveMidiNoteAction(MidiClip clip, MidiNoteData originalNote,
                              MidiNoteData movedNote) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.originalNote = Objects.requireNonNull(originalNote,
                "originalNote must not be null");
        this.movedNote = Objects.requireNonNull(movedNote,
                "movedNote must not be null");
    }

    @Override
    public String description() {
        return "Move MIDI Note";
    }

    @Override
    public void execute() {
        int index = clip.indexOf(originalNote);
        if (index >= 0) {
            clip.replaceNote(index, movedNote);
        }
    }

    @Override
    public void undo() {
        int index = clip.indexOf(movedNote);
        if (index >= 0) {
            clip.replaceNote(index, originalNote);
        }
    }
}
