package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeStretchClipActionTest {

    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
    }

    @Test
    void shouldApplyTimeStretchParameters() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);

        TimeStretchClipAction action = new TimeStretchClipAction(clip, 1.5, StretchQuality.HIGH);
        undoManager.execute(action);

        assertThat(clip.getTimeStretchRatio()).isEqualTo(1.5);
        assertThat(clip.getStretchQuality()).isEqualTo(StretchQuality.HIGH);
    }

    @Test
    void shouldUndoTimeStretchAndRestoreOriginalValues() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);
        clip.setTimeStretchRatio(0.8);
        clip.setStretchQuality(StretchQuality.LOW);

        TimeStretchClipAction action = new TimeStretchClipAction(clip, 2.0, StretchQuality.HIGH);
        undoManager.execute(action);
        undoManager.undo();

        assertThat(clip.getTimeStretchRatio()).isEqualTo(0.8);
        assertThat(clip.getStretchQuality()).isEqualTo(StretchQuality.LOW);
    }

    @Test
    void shouldRedoTimeStretchAfterUndo() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);

        TimeStretchClipAction action = new TimeStretchClipAction(clip, 1.5, StretchQuality.HIGH);
        undoManager.execute(action);
        undoManager.undo();

        undoManager.redo();

        assertThat(clip.getTimeStretchRatio()).isEqualTo(1.5);
        assertThat(clip.getStretchQuality()).isEqualTo(StretchQuality.HIGH);
    }

    @Test
    void shouldHaveCorrectDescription() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        TimeStretchClipAction action = new TimeStretchClipAction(clip, 1.0, StretchQuality.MEDIUM);

        assertThat(action.description()).isEqualTo("Time Stretch Clip");
    }

    @Test
    void shouldRejectNullClip() {
        assertThatThrownBy(() -> new TimeStretchClipAction(null, 1.5, StretchQuality.MEDIUM))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullQuality() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        assertThatThrownBy(() -> new TimeStretchClipAction(clip, 1.5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidStretchRatio() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        assertThatThrownBy(() -> new TimeStretchClipAction(clip, 0.1, StretchQuality.MEDIUM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeStretchClipAction(clip, 5.0, StretchQuality.MEDIUM))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
