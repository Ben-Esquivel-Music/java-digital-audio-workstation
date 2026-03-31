package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipEdgeTrimActionTest {

    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
    }

    @Test
    void shouldTrimLeftEdgeForward() {
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, "/audio/vocal.wav");
        clip.setSourceOffsetBeats(0.0);

        ClipEdgeTrimAction action = new ClipEdgeTrimAction(clip, 6.0, 6.0, 2.0);
        undoManager.execute(action);

        assertThat(clip.getStartBeat()).isEqualTo(6.0);
        assertThat(clip.getDurationBeats()).isEqualTo(6.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0);
    }

    @Test
    void shouldTrimRightEdge() {
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, "/audio/vocal.wav");

        ClipEdgeTrimAction action = new ClipEdgeTrimAction(clip, 4.0, 6.0, 0.0);
        undoManager.execute(action);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(6.0);
        assertThat(clip.getEndBeat()).isEqualTo(10.0);
    }

    @Test
    void shouldUndoAndRestoreOriginalState() {
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, "/audio/vocal.wav");
        clip.setSourceOffsetBeats(1.0);

        ClipEdgeTrimAction action = new ClipEdgeTrimAction(clip, 6.0, 6.0, 3.0);
        undoManager.execute(action);
        undoManager.undo();

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(1.0);
    }

    @Test
    void shouldRedoAfterUndo() {
        AudioClip clip = new AudioClip("Vocal", 4.0, 8.0, null);

        ClipEdgeTrimAction action = new ClipEdgeTrimAction(clip, 6.0, 6.0, 2.0);
        undoManager.execute(action);
        undoManager.undo();
        undoManager.redo();

        assertThat(clip.getStartBeat()).isEqualTo(6.0);
        assertThat(clip.getDurationBeats()).isEqualTo(6.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(2.0);
    }

    @Test
    void shouldHaveCorrectDescription() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        ClipEdgeTrimAction action = new ClipEdgeTrimAction(clip, 2.0, 6.0, 2.0);

        assertThat(action.description()).isEqualTo("Trim Clip Edge");
    }

    @Test
    void shouldRejectNullClip() {
        assertThatThrownBy(() -> new ClipEdgeTrimAction(null, 0.0, 4.0, 0.0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clip must not be null");
    }

    @Test
    void shouldRejectNegativeStartBeat() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        assertThatThrownBy(() -> new ClipEdgeTrimAction(clip, -1.0, 4.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newStartBeat must not be negative");
    }

    @Test
    void shouldRejectNonPositiveDuration() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        assertThatThrownBy(() -> new ClipEdgeTrimAction(clip, 0.0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newDurationBeats must be positive");
    }

    @Test
    void shouldRejectNegativeSourceOffset() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        assertThatThrownBy(() -> new ClipEdgeTrimAction(clip, 0.0, 4.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newSourceOffsetBeats must not be negative");
    }

    @Test
    void shouldExtendLeftEdgeBackward() {
        // Simulate a previously trimmed clip being extended back
        AudioClip clip = new AudioClip("Vocal", 6.0, 6.0, "/audio/vocal.wav");
        clip.setSourceOffsetBeats(2.0);

        // Extend left edge back to beat 4.0 (original position)
        ClipEdgeTrimAction action = new ClipEdgeTrimAction(clip, 4.0, 8.0, 0.0);
        undoManager.execute(action);

        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
        assertThat(clip.getSourceOffsetBeats()).isEqualTo(0.0);
    }
}
