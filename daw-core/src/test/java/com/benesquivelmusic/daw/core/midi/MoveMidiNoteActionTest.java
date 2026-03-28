package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoveMidiNoteActionTest {

    private MidiClip clip;
    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        clip = new MidiClip();
        undoManager = new UndoManager();
    }

    @Test
    void shouldHaveDescriptiveName() {
        MidiNoteData original = MidiNoteData.of(60, 4, 2, 100);
        MidiNoteData moved = MidiNoteData.of(72, 8, 2, 100);
        MoveMidiNoteAction action = new MoveMidiNoteAction(clip, original, moved);

        assertThat(action.description()).isEqualTo("Move MIDI Note");
    }

    @Test
    void shouldMoveNoteOnExecute() {
        MidiNoteData original = MidiNoteData.of(60, 4, 2, 100);
        MidiNoteData moved = MidiNoteData.of(72, 8, 2, 100);
        clip.addNote(original);

        MoveMidiNoteAction action = new MoveMidiNoteAction(clip, original, moved);
        action.execute();

        assertThat(clip.getNotes()).containsExactly(moved);
    }

    @Test
    void shouldRestoreOriginalOnUndo() {
        MidiNoteData original = MidiNoteData.of(60, 4, 2, 100);
        MidiNoteData moved = MidiNoteData.of(72, 8, 2, 100);
        clip.addNote(original);

        MoveMidiNoteAction action = new MoveMidiNoteAction(clip, original, moved);
        action.execute();
        action.undo();

        assertThat(clip.getNotes()).containsExactly(original);
    }

    @Test
    void shouldWorkWithUndoManager() {
        MidiNoteData original = MidiNoteData.of(60, 4, 2, 100);
        MidiNoteData moved = MidiNoteData.of(72, 8, 2, 100);
        clip.addNote(original);

        undoManager.execute(new MoveMidiNoteAction(clip, original, moved));
        assertThat(clip.getNote(0)).isEqualTo(moved);

        undoManager.undo();
        assertThat(clip.getNote(0)).isEqualTo(original);

        undoManager.redo();
        assertThat(clip.getNote(0)).isEqualTo(moved);
    }

    @Test
    void shouldRejectNullClip() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        assertThatThrownBy(() -> new MoveMidiNoteAction(null, note, note))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullOriginalNote() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        assertThatThrownBy(() -> new MoveMidiNoteAction(clip, null, note))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullMovedNote() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        assertThatThrownBy(() -> new MoveMidiNoteAction(clip, note, null))
                .isInstanceOf(NullPointerException.class);
    }
}
