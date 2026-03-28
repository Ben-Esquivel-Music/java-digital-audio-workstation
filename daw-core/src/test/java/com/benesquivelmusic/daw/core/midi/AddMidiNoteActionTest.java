package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddMidiNoteActionTest {

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
        AddMidiNoteAction action = new AddMidiNoteAction(clip, note);

        assertThat(action.description()).isEqualTo("Add MIDI Note");
    }

    @Test
    void shouldAddNoteOnExecute() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        AddMidiNoteAction action = new AddMidiNoteAction(clip, note);
        action.execute();

        assertThat(clip.getNotes()).containsExactly(note);
    }

    @Test
    void shouldRemoveNoteOnUndo() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        AddMidiNoteAction action = new AddMidiNoteAction(clip, note);
        action.execute();
        action.undo();

        assertThat(clip.isEmpty()).isTrue();
    }

    @Test
    void shouldWorkWithUndoManager() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);

        undoManager.execute(new AddMidiNoteAction(clip, note));
        assertThat(clip.size()).isEqualTo(1);

        undoManager.undo();
        assertThat(clip.isEmpty()).isTrue();

        undoManager.redo();
        assertThat(clip.size()).isEqualTo(1);
    }

    @Test
    void shouldRejectNullClip() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        assertThatThrownBy(() -> new AddMidiNoteAction(null, note))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullNote() {
        assertThatThrownBy(() -> new AddMidiNoteAction(clip, null))
                .isInstanceOf(NullPointerException.class);
    }
}
