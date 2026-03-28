package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoveMidiNoteActionTest {

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
        RemoveMidiNoteAction action = new RemoveMidiNoteAction(clip, note);

        assertThat(action.description()).isEqualTo("Delete MIDI Note");
    }

    @Test
    void shouldRemoveNoteOnExecute() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        clip.addNote(note);

        RemoveMidiNoteAction action = new RemoveMidiNoteAction(clip, note);
        action.execute();

        assertThat(clip.isEmpty()).isTrue();
    }

    @Test
    void shouldReAddNoteOnUndo() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        clip.addNote(note);

        RemoveMidiNoteAction action = new RemoveMidiNoteAction(clip, note);
        action.execute();
        action.undo();

        assertThat(clip.getNotes()).containsExactly(note);
    }

    @Test
    void shouldWorkWithUndoManager() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        clip.addNote(note);

        undoManager.execute(new RemoveMidiNoteAction(clip, note));
        assertThat(clip.isEmpty()).isTrue();

        undoManager.undo();
        assertThat(clip.size()).isEqualTo(1);

        undoManager.redo();
        assertThat(clip.isEmpty()).isTrue();
    }

    @Test
    void shouldRejectNullClip() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        assertThatThrownBy(() -> new RemoveMidiNoteAction(null, note))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullNote() {
        assertThatThrownBy(() -> new RemoveMidiNoteAction(clip, null))
                .isInstanceOf(NullPointerException.class);
    }
}
