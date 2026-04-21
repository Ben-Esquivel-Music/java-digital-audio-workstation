package com.benesquivelmusic.daw.core.midi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SlipMidiClipAction}. Verifies that every note shifts by
 * the same delta, that undo restores the original positions, and that a
 * negative delta that would push a note to a negative start column is
 * rejected (the service is responsible for pre-clamping).
 *
 * <p>Story 139 — {@code docs/user-stories/139-slip-edit-within-clip.md}.</p>
 */
class SlipMidiClipActionTest {

    @Test
    void executeShiftsAllNotesAndUndoRestoresThem() {
        MidiClip clip = new MidiClip();
        clip.addNote(MidiNoteData.of(60, 4, 2, 100));
        clip.addNote(MidiNoteData.of(62, 8, 2, 100));
        clip.addNote(MidiNoteData.of(64, 12, 2, 100));

        new SlipMidiClipAction(clip, 3).execute();

        assertThat(clip.getNote(0).startColumn()).isEqualTo(7);
        assertThat(clip.getNote(1).startColumn()).isEqualTo(11);
        assertThat(clip.getNote(2).startColumn()).isEqualTo(15);
    }

    @Test
    void undoReversesTheShift() {
        MidiClip clip = new MidiClip();
        clip.addNote(MidiNoteData.of(60, 6, 1, 100));
        clip.addNote(MidiNoteData.of(62, 10, 1, 100));

        SlipMidiClipAction action = new SlipMidiClipAction(clip, -4);
        action.execute();
        assertThat(clip.getNote(0).startColumn()).isEqualTo(2);
        assertThat(clip.getNote(1).startColumn()).isEqualTo(6);

        action.undo();
        assertThat(clip.getNote(0).startColumn()).isEqualTo(6);
        assertThat(clip.getNote(1).startColumn()).isEqualTo(10);
    }

    @Test
    void preservesPitchVelocityDurationAndChannel() {
        MidiClip clip = new MidiClip();
        clip.addNote(new MidiNoteData(64, 8, 4, 90, 3));

        new SlipMidiClipAction(clip, 5).execute();

        MidiNoteData shifted = clip.getNote(0);
        assertThat(shifted.noteNumber()).isEqualTo(64);
        assertThat(shifted.startColumn()).isEqualTo(13);
        assertThat(shifted.durationColumns()).isEqualTo(4);
        assertThat(shifted.velocity()).isEqualTo(90);
        assertThat(shifted.channel()).isEqualTo(3);
    }

    @Test
    void zeroDeltaIsNoOp() {
        MidiClip clip = new MidiClip();
        clip.addNote(MidiNoteData.of(60, 4, 1, 100));

        new SlipMidiClipAction(clip, 0).execute();

        assertThat(clip.getNote(0).startColumn()).isEqualTo(4);
    }

    @Test
    void emptyClipIsNoOp() {
        MidiClip clip = new MidiClip();
        // Should not throw
        new SlipMidiClipAction(clip, 5).execute();
        assertThat(clip.isEmpty()).isTrue();
    }

    @Test
    void deltaThatWouldPushNoteNegativeThrows() {
        MidiClip clip = new MidiClip();
        clip.addNote(MidiNoteData.of(60, 2, 1, 100));

        assertThatThrownBy(() -> new SlipMidiClipAction(clip, -5).execute())
                .isInstanceOf(IllegalStateException.class);
    }
}
