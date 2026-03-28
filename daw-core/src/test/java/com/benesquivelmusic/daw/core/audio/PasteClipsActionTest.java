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

class PasteClipsActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);

        PasteClipsAction action = new PasteClipsAction(
                List.of(Map.entry(track, clip)), track, 8.0);
        assertThat(action.description()).isEqualTo("Paste Clips");
    }

    @Test
    void shouldPasteDuplicateAtPlayheadOnSameTrack() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        PasteClipsAction action = new PasteClipsAction(
                List.of(Map.entry(track, clip)), null, 8.0);
        action.execute();

        assertThat(track.getClips()).hasSize(2);
        AudioClip pasted = track.getClips().get(1);
        assertThat(pasted.getStartBeat()).isCloseTo(8.0, offset(0.001));
        assertThat(pasted.getDurationBeats()).isCloseTo(4.0, offset(0.001));
        assertThat(pasted.getId()).isNotEqualTo(clip.getId());
    }

    @Test
    void shouldPasteToDifferentTrack() {
        Track source = new Track("Drums", TrackType.AUDIO);
        Track target = new Track("Bass", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 2.0, 4.0, null);
        source.addClip(clip);

        PasteClipsAction action = new PasteClipsAction(
                List.of(Map.entry(source, clip)), target, 10.0);
        action.execute();

        assertThat(source.getClips()).hasSize(1);
        assertThat(target.getClips()).hasSize(1);
        assertThat(target.getClips().get(0).getStartBeat()).isCloseTo(10.0, offset(0.001));
    }

    @Test
    void shouldPreserveRelativePositionsOfMultipleClips() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 2.0, 2.0, null);
        AudioClip clip2 = new AudioClip("snare", 6.0, 2.0, null);
        track.addClip(clip1);
        track.addClip(clip2);

        PasteClipsAction action = new PasteClipsAction(
                List.of(Map.entry(track, clip1), Map.entry(track, clip2)),
                null, 10.0);
        action.execute();

        assertThat(track.getClips()).hasSize(4);
        AudioClip pasted1 = track.getClips().get(2);
        AudioClip pasted2 = track.getClips().get(3);
        assertThat(pasted1.getStartBeat()).isCloseTo(10.0, offset(0.001));
        assertThat(pasted2.getStartBeat()).isCloseTo(14.0, offset(0.001));
    }

    @Test
    void shouldRemovePastedClipsOnUndo() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        PasteClipsAction action = new PasteClipsAction(
                List.of(Map.entry(track, clip)), null, 8.0);
        action.execute();
        assertThat(track.getClips()).hasSize(2);

        action.undo();
        assertThat(track.getClips()).hasSize(1);
        assertThat(track.getClips().get(0)).isSameAs(clip);
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new PasteClipsAction(
                List.of(Map.entry(track, clip)), null, 8.0));
        assertThat(track.getClips()).hasSize(2);

        undoManager.undo();
        assertThat(track.getClips()).hasSize(1);

        undoManager.redo();
        assertThat(track.getClips()).hasSize(2);
    }

    @Test
    void shouldRejectNullEntries() {
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> new PasteClipsAction(null, track, 0.0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyEntries() {
        Track track = new Track("Drums", TrackType.AUDIO);
        assertThatThrownBy(() -> new PasteClipsAction(List.of(), track, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativePlayheadBeat() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        assertThatThrownBy(() -> new PasteClipsAction(
                List.of(Map.entry(track, clip)), track, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
