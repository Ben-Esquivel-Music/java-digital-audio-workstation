package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CutClipsActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        CutClipsAction action = new CutClipsAction(
                List.of(Map.entry(track, clip)));
        assertThat(action.description()).isEqualTo("Cut Clips");
    }

    @Test
    void shouldRemoveSingleClipOnExecute() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        CutClipsAction action = new CutClipsAction(
                List.of(Map.entry(track, clip)));
        action.execute();

        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldReAddSingleClipOnUndo() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        CutClipsAction action = new CutClipsAction(
                List.of(Map.entry(track, clip)));
        action.execute();
        action.undo();

        assertThat(track.getClips()).containsExactly(clip);
    }

    @Test
    void shouldRemoveMultipleClipsFromDifferentTracks() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip bassLine = new AudioClip("bass-line", 0.0, 8.0, null);
        drums.addClip(kick);
        bass.addClip(bassLine);

        CutClipsAction action = new CutClipsAction(
                List.of(Map.entry(drums, kick), Map.entry(bass, bassLine)));
        action.execute();

        assertThat(drums.getClips()).isEmpty();
        assertThat(bass.getClips()).isEmpty();
    }

    @Test
    void shouldReAddMultipleClipsOnUndo() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip bassLine = new AudioClip("bass-line", 0.0, 8.0, null);
        drums.addClip(kick);
        bass.addClip(bassLine);

        CutClipsAction action = new CutClipsAction(
                List.of(Map.entry(drums, kick), Map.entry(bass, bassLine)));
        action.execute();
        action.undo();

        assertThat(drums.getClips()).containsExactly(kick);
        assertThat(bass.getClips()).containsExactly(bassLine);
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new CutClipsAction(
                List.of(Map.entry(track, clip))));
        assertThat(track.getClips()).isEmpty();

        undoManager.undo();
        assertThat(track.getClips()).containsExactly(clip);

        undoManager.redo();
        assertThat(track.getClips()).isEmpty();
    }

    @Test
    void shouldRejectNullEntries() {
        assertThatThrownBy(() -> new CutClipsAction(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyEntries() {
        assertThatThrownBy(() -> new CutClipsAction(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
