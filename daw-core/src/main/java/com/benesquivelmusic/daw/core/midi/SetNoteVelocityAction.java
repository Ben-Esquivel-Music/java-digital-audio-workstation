package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that changes the velocity of a MIDI note within a
 * {@link MidiClip}.
 */
public final class SetNoteVelocityAction implements UndoableAction {

    private final MidiClip clip;
    private final MidiNoteData originalNote;
    private final MidiNoteData updatedNote;

    /**
     * Creates a new set-velocity action.
     *
     * @param clip         the clip containing the note
     * @param originalNote the note before the velocity change
     * @param newVelocity  the new velocity (0–127)
     */
    public SetNoteVelocityAction(MidiClip clip, MidiNoteData originalNote,
                                 int newVelocity) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        this.originalNote = Objects.requireNonNull(originalNote,
                "originalNote must not be null");
        this.updatedNote = originalNote.withVelocity(newVelocity);
    }

    @Override
    public String description() {
        return "Set Note Velocity";
    }

    @Override
    public void execute() {
        int index = clip.indexOf(originalNote);
        if (index >= 0) {
            clip.replaceNote(index, updatedNote);
        }
    }

    @Override
    public void undo() {
        int index = clip.indexOf(updatedNote);
        if (index >= 0) {
            clip.replaceNote(index, originalNote);
        }
    }
}
