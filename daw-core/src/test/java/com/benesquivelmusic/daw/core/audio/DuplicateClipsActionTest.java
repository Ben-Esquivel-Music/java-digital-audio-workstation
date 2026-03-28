package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

class DuplicateClipsActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        DuplicateClipsAction action = new DuplicateClipsAction(
                List.of(Map.entry(track, clip)));
        assertThat(action.description()).isEqualTo("Duplicate Clips");
    }

    @Test
    void shouldDuplicateClipImmediatelyAfterOriginal() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 2.0, 4.0, null);
        track.addClip(clip);

        DuplicateClipsAction action = new DuplicateClipsAction(
                List.of(Map.entry(track, clip)));
        action.execute();

        assertThat(track.getClips()).hasSize(2);
        AudioClip duplicate = track.getClips().get(1);
        assertThat(duplicate.getStartBeat()).isCloseTo(6.0, offset(0.001));
        assertThat(duplicate.getDurationBeats()).isCloseTo(4.0, offset(0.001));
        assertThat(duplicate.getName()).isEqualTo(clip.getName());
        assertThat(duplicate.getId()).isNotEqualTo(clip.getId());
    }

    @Test
    void shouldDuplicateMultipleClipsOnDifferentTracks() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip bassLine = new AudioClip("bass-line", 0.0, 8.0, null);
        drums.addClip(kick);
        bass.addClip(bassLine);

        DuplicateClipsAction action = new DuplicateClipsAction(
                List.of(Map.entry(drums, kick), Map.entry(bass, bassLine)));
        action.execute();

        assertThat(drums.getClips()).hasSize(2);
        assertThat(drums.getClips().get(1).getStartBeat()).isCloseTo(4.0, offset(0.001));

        assertThat(bass.getClips()).hasSize(2);
        assertThat(bass.getClips().get(1).getStartBeat()).isCloseTo(8.0, offset(0.001));
    }

    @Test
    void shouldRemoveDuplicatesOnUndo() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        DuplicateClipsAction action = new DuplicateClipsAction(
                List.of(Map.entry(track, clip)));
        action.execute();
        assertThat(track.getClips()).hasSize(2);

        action.undo();
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isSameAs(clip);
    }

    @Test
    void shouldPreserveClipProperties() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, "/audio/kick.wav");
        clip.setGainDb(-3.0);
        clip.setReversed(true);
        clip.setFadeInBeats(0.5);
        clip.setFadeOutBeats(1.0);
        track.addClip(clip);

        DuplicateClipsAction action = new DuplicateClipsAction(
                List.of(Map.entry(track, clip)));
        action.execute();

        AudioClip duplicate = track.getClips().get(1);
        assertThat(duplicate.getGainDb()).isCloseTo(-3.0, offset(0.001));
        assertThat(duplicate.isReversed()).isTrue();
        assertThat(duplicate.getFadeInBeats()).isCloseTo(0.5, offset(0.001));
        assertThat(duplicate.getFadeOutBeats()).isCloseTo(1.0, offset(0.001));
        assertThat(duplicate.getSourceFilePath()).isEqualTo("/audio/kick.wav");
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new DuplicateClipsAction(
                List.of(Map.entry(track, clip))));
        assertThat(track.getClips()).hasSize(2);

        undoManager.undo();
        assertThat(track.getClips()).hasSize(1);

        undoManager.redo();
        assertThat(track.getClips()).hasSize(2);
    }

    @Test
    void shouldRejectNullEntries() {
        assertThatThrownBy(() -> new DuplicateClipsAction(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyEntries() {
        assertThatThrownBy(() -> new DuplicateClipsAction(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
