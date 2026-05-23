package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoveClipActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("t", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        assertThat(new MoveClipAction(track, clip, 8.0).description()).isEqualTo("Move Clip");
    }

    @Test
    void shouldMoveClipOnExecute() {
        Track track = new Track("t", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        MoveClipAction action = new MoveClipAction(track, clip, 8.0);
        action.execute();

        assertThat(clip.getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void shouldRestorePositionOnUndo() {
        Track track = new Track("t", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 2.0, 4.0, null);

        MoveClipAction action = new MoveClipAction(track, clip, 8.0);
        action.execute();
        action.undo();

        assertThat(clip.getStartBeat()).isEqualTo(2.0);
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("t", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new MoveClipAction(track, clip, 8.0));
        assertThat(clip.getStartBeat()).isEqualTo(8.0);

        undoManager.undo();
        assertThat(clip.getStartBeat()).isEqualTo(0.0);

        undoManager.redo();
        assertThat(clip.getStartBeat()).isEqualTo(8.0);
    }

    @Test
    void shouldRejectNullClip() {
        Track track = new Track("t", TrackType.AUDIO);
        assertThatThrownBy(() -> new MoveClipAction(track, null, 8.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullTrack() {
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        assertThatThrownBy(() -> new MoveClipAction(null, clip, 8.0))
                .isInstanceOf(NullPointerException.class);
    }
}
