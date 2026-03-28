package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddClipActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        assertThat(new AddClipAction(track, clip).description()).isEqualTo("Add Clip");
    }

    @Test
    void shouldAddClipOnExecute() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        AddClipAction action = new AddClipAction(track, clip);
        action.execute();

        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldRemoveClipOnUndo() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        AddClipAction action = new AddClipAction(track, clip);
        action.execute();
        action.undo();

        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new AddClipAction(track, clip));
        assertThat(track.getClips()).containsExactly(clip);

        undoManager.undo();
        assertThat(track.getClips()).isEmpty();

        undoManager.redo();
        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldRejectNullTrack() {
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        assertThatThrownBy(() -> new AddClipAction(null, clip))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullClip() {
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> new AddClipAction(track, null))
                .isInstanceOf(NullPointerException.class);
    }
}
