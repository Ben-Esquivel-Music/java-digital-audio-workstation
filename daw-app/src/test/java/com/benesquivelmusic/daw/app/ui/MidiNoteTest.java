package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidiNoteTest {

    @Test
    void shouldCreateValidNote() {
        var note = new MidiNote(60, 4, 2, 100);

        assertThat(note.note()).isEqualTo(60);
        assertThat(note.startColumn()).isEqualTo(4);
        assertThat(note.durationColumns()).isEqualTo(2);
        assertThat(note.velocity()).isEqualTo(100);
    }

    @Test
    void shouldCreateNoteAtBoundaryValues() {
        var low = new MidiNote(0, 0, 1, 0);
        assertThat(low.note()).isZero();
        assertThat(low.velocity()).isZero();

        var high = new MidiNote(MidiNote.MAX_NOTE, 1000, 32, MidiNote.MAX_VELOCITY);
        assertThat(high.note()).isEqualTo(95);
        assertThat(high.velocity()).isEqualTo(127);
    }

    @Test
    void shouldRejectNegativeNote() {
        assertThatThrownBy(() -> new MidiNote(-1, 0, 1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("note out of range");
    }

    @Test
    void shouldRejectNoteTooHigh() {
        assertThatThrownBy(() -> new MidiNote(96, 0, 1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("note out of range");
    }

    @Test
    void shouldRejectNegativeStartColumn() {
        assertThatThrownBy(() -> new MidiNote(60, -1, 1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startColumn");
    }

    @Test
    void shouldRejectZeroDuration() {
        assertThatThrownBy(() -> new MidiNote(60, 0, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationColumns");
    }

    @Test
    void shouldRejectNegativeVelocity() {
        assertThatThrownBy(() -> new MidiNote(60, 0, 1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("velocity");
    }

    @Test
    void shouldRejectVelocityTooHigh() {
        assertThatThrownBy(() -> new MidiNote(60, 0, 1, 128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("velocity");
    }

    @Test
    void shouldHaveCorrectDefaultConstants() {
        assertThat(MidiNote.MAX_NOTE).isEqualTo(95);
        assertThat(MidiNote.MAX_VELOCITY).isEqualTo(127);
        assertThat(MidiNote.DEFAULT_DURATION).isEqualTo(1);
        assertThat(MidiNote.DEFAULT_VELOCITY).isEqualTo(100);
    }

    @Test
    void equalNotesShouldBeEqual() {
        var a = new MidiNote(48, 8, 2, 80);
        var b = new MidiNote(48, 8, 2, 80);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentNotesShouldNotBeEqual() {
        var a = new MidiNote(48, 8, 2, 80);
        var b = new MidiNote(49, 8, 2, 80);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toStringShouldContainFieldValues() {
        var note = new MidiNote(60, 4, 2, 100);
        String str = note.toString();

        assertThat(str).contains("60");
        assertThat(str).contains("4");
        assertThat(str).contains("2");
        assertThat(str).contains("100");
    }
}
