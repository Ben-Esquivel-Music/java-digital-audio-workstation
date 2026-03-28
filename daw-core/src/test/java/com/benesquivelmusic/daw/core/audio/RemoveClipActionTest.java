package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoveClipActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        assertThat(new RemoveClipAction(track, clip).description()).isEqualTo("Remove Clip");
    }

    @Test
    void shouldRemoveClipOnExecute() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        RemoveClipAction action = new RemoveClipAction(track, clip);
        action.execute();

        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldReAddClipOnUndo() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        RemoveClipAction action = new RemoveClipAction(track, clip);
        action.execute();
        action.undo();

        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new RemoveClipAction(track, clip));
        assertThat(track.getClips()).isEmpty();

        undoManager.undo();
        assertThat(track.getClips()).containsExactly(clip);

        undoManager.redo();
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldRejectNullTrack() {
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        assertThatThrownBy(() -> new RemoveClipAction(null, clip))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullClip() {
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> new RemoveClipAction(track, null))
                .isInstanceOf(NullPointerException.class);
    }
}
