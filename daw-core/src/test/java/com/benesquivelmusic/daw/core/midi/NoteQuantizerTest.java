package com.benesquivelmusic.daw.core.midi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoteQuantizerTest {

    @Test
    void shouldQuantizePositionToNearestGrid() {
        MidiNoteData note = MidiNoteData.of(60, 5, 1, 100);
        MidiNoteData quantized = NoteQuantizer.quantizePosition(note, 4);

        assertThat(quantized.startColumn()).isEqualTo(4);
    }

    @Test
    void shouldRoundUpPositionWhenCloserToNextGrid() {
        MidiNoteData note = MidiNoteData.of(60, 7, 1, 100);
        MidiNoteData quantized = NoteQuantizer.quantizePosition(note, 4);

        assertThat(quantized.startColumn()).isEqualTo(8);
    }

    @Test
    void shouldNotMoveAlreadyAlignedPosition() {
        MidiNoteData note = MidiNoteData.of(60, 8, 1, 100);
        MidiNoteData quantized = NoteQuantizer.quantizePosition(note, 4);

        assertThat(quantized.startColumn()).isEqualTo(8);
    }

    @Test
    void shouldClampNegativePositionToZero() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        MidiNoteData quantized = NoteQuantizer.quantizePosition(note, 4);

        assertThat(quantized.startColumn()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldQuantizeDurationToNearestGrid() {
        MidiNoteData note = MidiNoteData.of(60, 0, 3, 100);
        MidiNoteData quantized = NoteQuantizer.quantizeDuration(note, 4);

        assertThat(quantized.durationColumns()).isEqualTo(4);
    }

    @Test
    void shouldEnforcMinimumDurationOfOne() {
        // Duration of 1 with grid of 4 — rounds to 0, but clamped to 1
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        MidiNoteData quantized = NoteQuantizer.quantizeDuration(note, 4);

        assertThat(quantized.durationColumns()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldQuantizeBothPositionAndDuration() {
        MidiNoteData note = MidiNoteData.of(60, 5, 3, 100);
        MidiNoteData quantized = NoteQuantizer.quantize(note, 4);

        assertThat(quantized.startColumn()).isEqualTo(4);
        assertThat(quantized.durationColumns()).isEqualTo(4);
    }

    @Test
    void shouldQuantizeAllNotesInList() {
        List<MidiNoteData> notes = List.of(
                MidiNoteData.of(60, 1, 3, 100),
                MidiNoteData.of(64, 5, 5, 80)
        );
        List<MidiNoteData> quantized = NoteQuantizer.quantizeAll(notes, 4);

        assertThat(quantized).hasSize(2);
        assertThat(quantized.get(0).startColumn()).isEqualTo(0);
        assertThat(quantized.get(1).startColumn()).isEqualTo(4);
    }

    @Test
    void shouldWorkWithGridOfOne() {
        MidiNoteData note = MidiNoteData.of(60, 5, 3, 100);
        MidiNoteData quantized = NoteQuantizer.quantize(note, 1);

        assertThat(quantized.startColumn()).isEqualTo(5);
        assertThat(quantized.durationColumns()).isEqualTo(3);
    }

    @Test
    void shouldRejectGridColumnsLessThanOne() {
        MidiNoteData note = MidiNoteData.of(60, 0, 1, 100);
        assertThatThrownBy(() -> NoteQuantizer.quantizePosition(note, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullNote() {
        assertThatThrownBy(() -> NoteQuantizer.quantizePosition(null, 4))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullNoteList() {
        assertThatThrownBy(() -> NoteQuantizer.quantizeAll(null, 4))
                .isInstanceOf(NullPointerException.class);
    }
}
