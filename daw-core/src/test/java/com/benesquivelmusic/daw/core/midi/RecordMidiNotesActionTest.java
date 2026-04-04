package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordMidiNotesActionTest {

    private MidiClip clip;
    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        clip = new MidiClip();
        undoManager = new UndoManager();
    }

    @Test
    void shouldHaveDescriptiveName() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        RecordMidiNotesAction action = new RecordMidiNotesAction(clip, java.util.List.of(note));

        assertThat(action.description()).isEqualTo("Record MIDI");
    }

    @Test
    void shouldNotDuplicateNotesOnFirstExecute() {
        // Simulate real-time recording: notes are already in the clip
        MidiNoteData note1 = MidiNoteData.of(60, 0, 4, 100);
        MidiNoteData note2 = MidiNoteData.of(64, 4, 4, 90);
        clip.addNote(note1);
        clip.addNote(note2);

        RecordMidiNotesAction action = new RecordMidiNotesAction(clip, java.util.List.of(note1, note2));
        action.execute(); // first execute should be a no-op

        assertThat(clip.size()).isEqualTo(2);
    }

    @Test
    void shouldRemoveAllNotesOnUndo() {
        MidiNoteData note1 = MidiNoteData.of(60, 0, 4, 100);
        MidiNoteData note2 = MidiNoteData.of(64, 4, 4, 90);
        clip.addNote(note1);
        clip.addNote(note2);

        RecordMidiNotesAction action = new RecordMidiNotesAction(clip, java.util.List.of(note1, note2));
        action.execute();
        action.undo();

        assertThat(clip.isEmpty()).isTrue();
    }

    @Test
    void shouldReAddNotesOnRedo() {
        MidiNoteData note1 = MidiNoteData.of(60, 0, 4, 100);
        MidiNoteData note2 = MidiNoteData.of(64, 4, 4, 90);
        clip.addNote(note1);
        clip.addNote(note2);

        RecordMidiNotesAction action = new RecordMidiNotesAction(clip, java.util.List.of(note1, note2));
        action.execute(); // first: no-op
        action.undo();    // removes notes
        action.execute();  // second: re-adds notes

        assertThat(clip.size()).isEqualTo(2);
        assertThat(clip.getNotes()).containsExactly(note1, note2);
    }

    @Test
    void shouldWorkWithUndoManager() {
        MidiNoteData note1 = MidiNoteData.of(60, 0, 4, 100);
        MidiNoteData note2 = MidiNoteData.of(64, 4, 4, 90);
        MidiNoteData note3 = MidiNoteData.of(67, 8, 4, 80);

        // Simulate real-time recording
        clip.addNote(note1);
        clip.addNote(note2);
        clip.addNote(note3);

        undoManager.execute(new RecordMidiNotesAction(clip, java.util.List.of(note1, note2, note3)));
        assertThat(clip.size()).isEqualTo(3);

        undoManager.undo();
        assertThat(clip.isEmpty()).isTrue();

        undoManager.redo();
        assertThat(clip.size()).isEqualTo(3);
    }

    @Test
    void shouldReturnRecordedNotes() {
        MidiNoteData note1 = MidiNoteData.of(60, 0, 4, 100);
        MidiNoteData note2 = MidiNoteData.of(64, 4, 4, 90);

        RecordMidiNotesAction action = new RecordMidiNotesAction(clip, java.util.List.of(note1, note2));

        assertThat(action.getNotes()).containsExactly(note1, note2);
    }

    @Test
    void shouldHandleEmptyNoteList() {
        RecordMidiNotesAction action = new RecordMidiNotesAction(clip, java.util.List.of());
        action.execute();
        action.undo();

        assertThat(clip.isEmpty()).isTrue();
    }

    @Test
    void shouldRejectNullClip() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        assertThatThrownBy(() -> new RecordMidiNotesAction(null, java.util.List.of(note)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullNotes() {
        assertThatThrownBy(() -> new RecordMidiNotesAction(clip, null))
                .isInstanceOf(NullPointerException.class);
    }
}
