package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResizeMidiNoteActionTest {

    private MidiClip clip;
    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        clip = new MidiClip();
        undoManager = new UndoManager();
    }

    @Test
    void shouldHaveDescriptiveName() {
        MidiNoteData note = MidiNoteData.of(60, 0, 2, 100);
        ResizeMidiNoteAction action = new ResizeMidiNoteAction(clip, note, 4);

        assertThat(action.description()).isEqualTo("Resize MIDI Note");
    }

    @Test
    void shouldResizeNoteOnExecute() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        clip.addNote(note);

        ResizeMidiNoteAction action = new ResizeMidiNoteAction(clip, note, 8);
        action.execute();

        assertThat(clip.getNote(0).durationColumns()).isEqualTo(8);
        assertThat(clip.getNote(0).noteNumber()).isEqualTo(60);
    }

    @Test
    void shouldRestoreOriginalDurationOnUndo() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        clip.addNote(note);

        ResizeMidiNoteAction action = new ResizeMidiNoteAction(clip, note, 8);
        action.execute();
        action.undo();

        assertThat(clip.getNote(0).durationColumns()).isEqualTo(2);
    }

    @Test
    void shouldWorkWithUndoManager() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);
        clip.addNote(note);

        undoManager.execute(new ResizeMidiNoteAction(clip, note, 8));
        assertThat(clip.getNote(0).durationColumns()).isEqualTo(8);

        undoManager.undo();
        assertThat(clip.getNote(0).durationColumns()).isEqualTo(2);

        undoManager.redo();
        assertThat(clip.getNote(0).durationColumns()).isEqualTo(8);
    }

    @Test
    void shouldRejectNullClip() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        assertThatThrownBy(() -> new ResizeMidiNoteAction(null, note, 4))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullNote() {
        assertThatThrownBy(() -> new ResizeMidiNoteAction(clip, null, 4))
                .isInstanceOf(NullPointerException.class);
    }
}
