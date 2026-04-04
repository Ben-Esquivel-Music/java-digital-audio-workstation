package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.List;
import java.util.Objects;

/**
 * An undoable action that adds a batch of recorded MIDI notes to a
 * {@link MidiClip}.
 *
 * <p>On first execution, the notes are already present in the clip
 * (added in real time by {@link MidiRecorder}). Subsequent executions
 * (redo) re-add the notes. Undo removes all recorded notes from the
 * clip.</p>
 */
public final class RecordMidiNotesAction implements UndoableAction {

    private final MidiClip clip;
    private final List<MidiNoteData> notes;
    private boolean initialExecute = true;

    /**
     * Creates a new record-MIDI-notes action.
     *
     * @param clip  the clip the notes were recorded into
     * @param notes the recorded notes (snapshot at stop time)
     */
    public RecordMidiNotesAction(MidiClip clip, List<MidiNoteData> notes) {
        this.clip = Objects.requireNonNull(clip, "clip must not be null");
        Objects.requireNonNull(notes, "notes must not be null");
        this.notes = List.copyOf(notes);
    }

    @Override
    public String description() {
        return "Record MIDI";
    }

    @Override
    public void execute() {
        if (initialExecute) {
            // Notes are already in the clip from real-time recording
            initialExecute = false;
            return;
        }
        for (MidiNoteData note : notes) {
            clip.addNote(note);
        }
    }

    @Override
    public void undo() {
        for (MidiNoteData note : notes) {
            clip.removeNote(note);
        }
    }

    /**
     * Returns the recorded notes managed by this action.
     *
     * @return an unmodifiable list of recorded notes
     */
    public List<MidiNoteData> getNotes() {
        return notes;
    }
}
