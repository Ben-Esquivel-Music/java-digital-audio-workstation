package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PitchShiftClipActionTest {

    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
    }

    @Test
    void shouldApplyPitchShiftParameters() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);

        PitchShiftClipAction action = new PitchShiftClipAction(clip, 7.0, StretchQuality.HIGH);
        undoManager.execute(action);

        assertThat(clip.getPitchShiftSemitones()).isEqualTo(7.0);
        assertThat(clip.getStretchQuality()).isEqualTo(StretchQuality.HIGH);
    }

    @Test
    void shouldUndoPitchShiftAndRestoreOriginalValues() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);
        clip.setPitchShiftSemitones(-3.0);
        clip.setStretchQuality(StretchQuality.LOW);

        PitchShiftClipAction action = new PitchShiftClipAction(clip, 12.0, StretchQuality.HIGH);
        undoManager.execute(action);
        undoManager.undo();

        assertThat(clip.getPitchShiftSemitones()).isEqualTo(-3.0);
        assertThat(clip.getStretchQuality()).isEqualTo(StretchQuality.LOW);
    }

    @Test
    void shouldRedoPitchShiftAfterUndo() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);

        PitchShiftClipAction action = new PitchShiftClipAction(clip, 7.0, StretchQuality.HIGH);
        undoManager.execute(action);
        undoManager.undo();

        undoManager.redo();

        assertThat(clip.getPitchShiftSemitones()).isEqualTo(7.0);
        assertThat(clip.getStretchQuality()).isEqualTo(StretchQuality.HIGH);
    }

    @Test
    void shouldHaveCorrectDescription() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        PitchShiftClipAction action = new PitchShiftClipAction(clip, 0.0, StretchQuality.MEDIUM);

        assertThat(action.description()).isEqualTo("Pitch Shift Clip");
    }

    @Test
    void shouldRejectNullClip() {
        assertThatThrownBy(() -> new PitchShiftClipAction(null, 7.0, StretchQuality.MEDIUM))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullQuality() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        assertThatThrownBy(() -> new PitchShiftClipAction(clip, 7.0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidPitchShift() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        assertThatThrownBy(() -> new PitchShiftClipAction(clip, -25.0, StretchQuality.MEDIUM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PitchShiftClipAction(clip, 25.0, StretchQuality.MEDIUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSupportCentAdjustments() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);

        PitchShiftClipAction action = new PitchShiftClipAction(clip, 0.5, StretchQuality.MEDIUM);
        undoManager.execute(action);

        assertThat(clip.getPitchShiftSemitones()).isEqualTo(0.5);
    }

    @Test
    void shouldSupportNegativePitchShift() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);

        PitchShiftClipAction action = new PitchShiftClipAction(clip, -5.0, StretchQuality.MEDIUM);
        undoManager.execute(action);

        assertThat(clip.getPitchShiftSemitones()).isEqualTo(-5.0);
    }
}
