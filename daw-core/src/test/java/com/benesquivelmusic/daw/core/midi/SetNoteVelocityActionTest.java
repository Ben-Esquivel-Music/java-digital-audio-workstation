package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetNoteVelocityActionTest {

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
        SetNoteVelocityAction action = new SetNoteVelocityAction(clip, note, 50);

        assertThat(action.description()).isEqualTo("Set Note Velocity");
    }

    @Test
    void shouldChangeVelocityOnExecute() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        clip.addNote(note);

        SetNoteVelocityAction action = new SetNoteVelocityAction(clip, note, 50);
        action.execute();

        assertThat(clip.getNote(0).velocity()).isEqualTo(50);
        assertThat(clip.getNote(0).noteNumber()).isEqualTo(60);
    }

    @Test
    void shouldRestoreOriginalVelocityOnUndo() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        clip.addNote(note);

        SetNoteVelocityAction action = new SetNoteVelocityAction(clip, note, 50);
        action.execute();
        action.undo();

        assertThat(clip.getNote(0).velocity()).isEqualTo(100);
    }

    @Test
    void shouldWorkWithUndoManager() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        clip.addNote(note);

        undoManager.execute(new SetNoteVelocityAction(clip, note, 50));
        assertThat(clip.getNote(0).velocity()).isEqualTo(50);

        undoManager.undo();
        assertThat(clip.getNote(0).velocity()).isEqualTo(100);

        undoManager.redo();
        assertThat(clip.getNote(0).velocity()).isEqualTo(50);
    }

    @Test
    void shouldRejectNullClip() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        assertThatThrownBy(() -> new SetNoteVelocityAction(null, note, 50))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullNote() {
        assertThatThrownBy(() -> new SetNoteVelocityAction(clip, null, 50))
                .isInstanceOf(NullPointerException.class);
    }
}
