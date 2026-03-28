package com.benesquivelmusic.daw.core.midi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidiNoteDataTest {

    @Test
    void shouldCreateValidNote() {
        MidiNoteData note = new MidiNoteData(60, 4, 2, 100, 0);

        assertThat(note.noteNumber()).isEqualTo(60);
        assertThat(note.startColumn()).isEqualTo(4);
        assertThat(note.durationColumns()).isEqualTo(2);
        assertThat(note.velocity()).isEqualTo(100);
        assertThat(note.channel()).isZero();
    }

    @Test
    void shouldCreateNoteWithFactoryMethod() {
        MidiNoteData note = MidiNoteData.of(60, 4, 2, 100);

        assertThat(note.noteNumber()).isEqualTo(60);
        assertThat(note.channel()).isZero();
    }

    @Test
    void shouldCreateNoteAtBoundaryValues() {
        MidiNoteData low = new MidiNoteData(0, 0, 1, 0, 0);
        assertThat(low.noteNumber()).isZero();
        assertThat(low.velocity()).isZero();

        MidiNoteData high = new MidiNoteData(127, 1000, 32, 127, 15);
        assertThat(high.noteNumber()).isEqualTo(127);
        assertThat(high.velocity()).isEqualTo(127);
        assertThat(high.channel()).isEqualTo(15);
    }

    @Test
    void shouldRejectNegativeNoteNumber() {
        assertThatThrownBy(() -> new MidiNoteData(-1, 0, 1, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noteNumber");
    }

    @Test
    void shouldRejectNoteNumberTooHigh() {
        assertThatThrownBy(() -> new MidiNoteData(128, 0, 1, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noteNumber");
    }

    @Test
    void shouldRejectNegativeStartColumn() {
        assertThatThrownBy(() -> new MidiNoteData(60, -1, 1, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startColumn");
    }

    @Test
    void shouldRejectZeroDuration() {
        assertThatThrownBy(() -> new MidiNoteData(60, 0, 0, 100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationColumns");
    }

    @Test
    void shouldRejectNegativeVelocity() {
        assertThatThrownBy(() -> new MidiNoteData(60, 0, 1, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("velocity");
    }

    @Test
    void shouldRejectVelocityTooHigh() {
        assertThatThrownBy(() -> new MidiNoteData(60, 0, 1, 128, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("velocity");
    }

    @Test
    void shouldRejectNegativeChannel() {
        assertThatThrownBy(() -> new MidiNoteData(60, 0, 1, 100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void shouldRejectChannelTooHigh() {
        assertThatThrownBy(() -> new MidiNoteData(60, 0, 1, 100, 16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void shouldReturnEndColumn() {
        MidiNoteData note = MidiNoteData.of(60, 4, 3, 100);
        assertThat(note.endColumn()).isEqualTo(7);
    }

    @Test
    void withStartColumnShouldReturnNewNote() {
        MidiNoteData original = MidiNoteData.of(60, 4, 2, 100);
        MidiNoteData moved = original.withStartColumn(8);

        assertThat(moved.startColumn()).isEqualTo(8);
        assertThat(moved.noteNumber()).isEqualTo(60);
        assertThat(moved.durationColumns()).isEqualTo(2);
        assertThat(moved.velocity()).isEqualTo(100);
        assertThat(original.startColumn()).isEqualTo(4);
    }

    @Test
    void withNoteNumberShouldReturnNewNote() {
        MidiNoteData original = MidiNoteData.of(60, 4, 2, 100);
        MidiNoteData pitched = original.withNoteNumber(72);

        assertThat(pitched.noteNumber()).isEqualTo(72);
        assertThat(pitched.startColumn()).isEqualTo(4);
    }

    @Test
    void withDurationColumnsShouldReturnNewNote() {
        MidiNoteData original = MidiNoteData.of(60, 4, 2, 100);
        MidiNoteData resized = original.withDurationColumns(8);

        assertThat(resized.durationColumns()).isEqualTo(8);
        assertThat(original.durationColumns()).isEqualTo(2);
    }

    @Test
    void withVelocityShouldReturnNewNote() {
        MidiNoteData original = MidiNoteData.of(60, 4, 2, 100);
        MidiNoteData changed = original.withVelocity(50);

        assertThat(changed.velocity()).isEqualTo(50);
        assertThat(original.velocity()).isEqualTo(100);
    }

    @Test
    void equalNotesShouldBeEqual() {
        MidiNoteData a = new MidiNoteData(60, 4, 2, 100, 0);
        MidiNoteData b = new MidiNoteData(60, 4, 2, 100, 0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentNotesShouldNotBeEqual() {
        MidiNoteData a = MidiNoteData.of(60, 4, 2, 100);
        MidiNoteData b = MidiNoteData.of(61, 4, 2, 100);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void shouldHaveCorrectDefaultConstants() {
        assertThat(MidiNoteData.MAX_NOTE_NUMBER).isEqualTo(127);
        assertThat(MidiNoteData.MAX_VELOCITY).isEqualTo(127);
        assertThat(MidiNoteData.MAX_CHANNEL).isEqualTo(15);
        assertThat(MidiNoteData.DEFAULT_DURATION).isEqualTo(1);
        assertThat(MidiNoteData.DEFAULT_VELOCITY).isEqualTo(100);
        assertThat(MidiNoteData.DEFAULT_CHANNEL).isZero();
    }
}
