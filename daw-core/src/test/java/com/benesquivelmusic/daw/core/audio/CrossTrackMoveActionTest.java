package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossTrackMoveActionTest {

    private UndoManager undoManager;
    private Track source;
    private Track target;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
        source = new Track("Track 1", TrackType.AUDIO);
        target = new Track("Track 2", TrackType.AUDIO);
    }

    @Test
    void shouldMoveClipToTargetTrack() {
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        source.addClip(clip);

        CrossTrackMoveAction action = new CrossTrackMoveAction(source, target, clip, 8.0);
        undoManager.execute(action);

        assertThat(source.getClips()).isEmpty();
        assertThat(target.getClips()).hasSize(1);
        assertThat(clip.getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void shouldUndoMoveAndRestoreToSourceTrack() {
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        source.addClip(clip);

        CrossTrackMoveAction action = new CrossTrackMoveAction(source, target, clip, 8.0);
        undoManager.execute(action);
        undoManager.undo();

        assertThat(source.getClips()).hasSize(1);
        assertThat(target.getClips()).isEmpty();
        assertThat(clip.getStartBeat()).isEqualTo(2.0);
    }

    @Test
    void shouldRedoMoveAfterUndo() {
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        source.addClip(clip);

        CrossTrackMoveAction action = new CrossTrackMoveAction(source, target, clip, 8.0);
        undoManager.execute(action);
        undoManager.undo();

        undoManager.redo();

        assertThat(source.getClips()).isEmpty();
        assertThat(target.getClips()).hasSize(1);
        assertThat(clip.getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void shouldHaveCorrectDescription() {
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);
        CrossTrackMoveAction action = new CrossTrackMoveAction(source, target, clip, 8.0);

        assertThat(action.description()).isEqualTo("Move Clip to Track");
    }

    @Test
    void shouldRejectNullSourceTrack() {
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);

        assertThatThrownBy(() -> new CrossTrackMoveAction(null, target, clip, 8.0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourceTrack");
    }

    @Test
    void shouldRejectNullTargetTrack() {
        AudioClip clip = new AudioClip("Vocal", 2.0, 4.0, null);

        assertThatThrownBy(() -> new CrossTrackMoveAction(source, null, clip, 8.0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("targetTrack");
    }

    @Test
    void shouldRejectNullClip() {
        assertThatThrownBy(() -> new CrossTrackMoveAction(source, target, null, 8.0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clip");
    }
}
