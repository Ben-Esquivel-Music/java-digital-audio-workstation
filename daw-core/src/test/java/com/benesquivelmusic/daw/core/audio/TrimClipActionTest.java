package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrimClipActionTest {

    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
    }

    @Test
    void shouldTrimClipToSmallerRange() {
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, "/audio/vocal.wav");
        clip.setSourceOffsetBeats(1.0);

        TrimClipAction action = new TrimClipAction(clip, 6.0, 10.0);
        undoManager.execute(action);

        assertThat(clip.getStartBeat()).isEqualTo(6.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getEndBeat()).isEqualTo(10.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(3.0);
    }

    @Test
    void shouldUndoTrimAndRestoreOriginalRange() {
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, "/audio/vocal.wav");
        clip.setSourceOffsetBeats(1.0);

        TrimClipAction action = new TrimClipAction(clip, 6.0, 10.0);
        undoManager.execute(action);
        undoManager.undo();

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
        assertThat(clip.getEndBeat()).isEqualTo(12.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(1.0);
    }

    @Test
    void shouldRedoTrimAfterUndo() {
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, "/audio/vocal.wav");

        TrimClipAction action = new TrimClipAction(clip, 6.0, 10.0);
        undoManager.execute(action);
        undoManager.undo();

        undoManager.redo();

        assertThat(clip.getStartBeat()).isEqualTo(6.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
    }

    @Test
    void shouldTrimStartOnly() {
        AudioClip clip = new AudioClip("Test", 0.0, 16.0, null);

        TrimClipAction action = new TrimClipAction(clip, 4.0, 16.0);
        undoManager.execute(action);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(12.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(4.0);
    }

    @Test
    void shouldTrimEndOnly() {
        AudioClip clip = new AudioClip("Test", 0.0, 16.0, null);

        TrimClipAction action = new TrimClipAction(clip, 0.0, 12.0);
        undoManager.execute(action);

        assertThat(clip.getStartBeat()).isEqualTo(0.0);
        assertThat(clip.getDurationBeats()).isEqualTo(12.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
    }

    @Test
    void shouldHaveCorrectDescription() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        TrimClipAction action = new TrimClipAction(clip, 2.0, 6.0);

        assertThat(action.description()).isEqualTo("Trim Clip");
    }
}
