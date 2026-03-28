package com.benesquivelmusic.daw.core.midi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidiClipTest {

    private MidiClip clip;

    @BeforeEach
    void setUp() {
        clip = new MidiClip();
    }

    @Test
    void shouldStartEmpty() {
        assertThat(clip.isEmpty()).isTrue();
        assertThat(clip.size()).isZero();
        assertThat(clip.getNotes()).isEmpty();
    }

    @Test
    void shouldAddNote() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        clip.addNote(note);

        assertThat(clip.size()).isEqualTo(1);
        assertThat(clip.getNote(0)).isEqualTo(note);
    }

    @Test
    void shouldRemoveNote() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        clip.addNote(note);

        boolean removed = clip.removeNote(note);

        assertThat(removed).isTrue();
        assertThat(clip.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnFalseWhenRemovingAbsentNote() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        boolean removed = clip.removeNote(note);

        assertThat(removed).isFalse();
    }

    @Test
    void shouldReplaceNote() {
        MidiNoteData original = MidiNoteData.of(60, 0, 1, 100);
        MidiNoteData replacement = MidiNoteData.of(72, 4, 2, 80);
        clip.addNote(original);

        MidiNoteData previous = clip.replaceNote(0, replacement);

        assertThat(previous).isEqualTo(original);
        assertThat(clip.getNote(0)).isEqualTo(replacement);
        assertThat(clip.size()).isEqualTo(1);
    }

    @Test
    void shouldFindIndexOfNote() {
        MidiNoteData note1 = MidiNoteData.of(60, 0, 1, 100);
        MidiNoteData note2 = MidiNoteData.of(72, 4, 2, 80);
        clip.addNote(note1);
        clip.addNote(note2);

        assertThat(clip.indexOf(note1)).isZero();
        assertThat(clip.indexOf(note2)).isEqualTo(1);
    }

    @Test
    void shouldReturnMinusOneForAbsentNote() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        assertThat(clip.indexOf(note)).isEqualTo(-1);
    }

    @Test
    void shouldClearAllNotes() {
        clip.addNote(MidiNoteData.of(60, 0, 1, 100));
        clip.addNote(MidiNoteData.of(72, 4, 2, 80));

        clip.clear();

        assertThat(clip.isEmpty()).isTrue();
    }

    @Test
    void shouldReturnUnmodifiableNoteList() {
        clip.addNote(MidiNoteData.of(60, 0, 1, 100));

        assertThatThrownBy(() -> clip.getNotes().add(MidiNoteData.of(72, 0, 1, 80)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullNote() {
        assertThatThrownBy(() -> clip.addNote(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullReplacement() {
        clip.addNote(MidiNoteData.of(60, 0, 1, 100));
        assertThatThrownBy(() -> clip.replaceNote(0, null))
                .isInstanceOf(NullPointerException.class);
    }
}
