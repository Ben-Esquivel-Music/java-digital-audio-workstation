package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class GroupMoveClipsActionTest {

    @Test
    void shouldHaveCorrectDescription() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);

        GroupMoveClipsAction action = new GroupMoveClipsAction(
                List.of(Map.entry(track, clip)), 2.0, 0, List.of(track));
        assertThat(action.description()).isEqualTo("Move Clips");
    }

    @Test
    void shouldMoveMultipleClipsBySameDelta() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 4.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);

        GroupMoveClipsAction action = new GroupMoveClipsAction(
                List.of(Map.entry(track, clip1), Map.entry(track, clip2)),
                2.0, 0, List.of(track));
        action.execute();

        assertThat(clip1.getStartBeat()).isCloseTo(2.0, offset(0.001));
        assertThat(clip2.getStartBeat()).isCloseTo(6.0, offset(0.001));
    }

    @Test
    void shouldUndoGroupMove() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 4.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);

        GroupMoveClipsAction action = new GroupMoveClipsAction(
                List.of(Map.entry(track, clip1), Map.entry(track, clip2)),
                2.0, 0, List.of(track));
        action.execute();
        action.undo();

        assertThat(clip1.getStartBeat()).isCloseTo(0.0, offset(0.001));
        assertThat(clip2.getStartBeat()).isCloseTo(4.0, offset(0.001));
    }

    @Test
    void shouldSupportCrossTrackGroupMove() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        AudioClip snare = new AudioClip("snare", 4.0, 4.0, null);
        drums.addClip(kick);
        drums.addClip(snare);

        GroupMoveClipsAction action = new GroupMoveClipsAction(
                List.of(Map.entry(drums, kick), Map.entry(drums, snare)),
                1.0, 1, List.of(drums, bass));
        action.execute();

        assertThat(drums.getClips()).isEmpty();
        assertThat(bass.getClips()).hasSize(2);
        assertThat(kick.getStartBeat()).isCloseTo(1.0, offset(0.001));
        assertThat(snare.getStartBeat()).isCloseTo(5.0, offset(0.001));
    }

    @Test
    void shouldUndoCrossTrackGroupMove() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        Track bass = new Track("Bass", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        drums.addClip(kick);

        GroupMoveClipsAction action = new GroupMoveClipsAction(
                List.of(Map.entry(drums, kick)),
                2.0, 1, List.of(drums, bass));
        action.execute();

        assertThat(drums.getClips()).isEmpty();
        assertThat(bass.getClips()).hasSize(1);

        action.undo();

        assertThat(drums.getClips()).hasSize(1);
        assertThat(bass.getClips()).isEmpty();
        assertThat(kick.getStartBeat()).isCloseTo(0.0, offset(0.001));
    }

    @Test
    void shouldClampGroupDeltaToPreserveRelativeSpacing() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip1 = new AudioClip("kick", 1.0, 4.0, null);
        AudioClip clip2 = new AudioClip("snare", 3.0, 4.0, null);
        track.addClip(clip1);
        track.addClip(clip2);

        // Beat delta of -5 would push clip1 to -4 and clip2 to -2,
        // but group clamping limits effective delta to -1 (min start = 1.0).
        GroupMoveClipsAction action = new GroupMoveClipsAction(
                List.of(Map.entry(track, clip1), Map.entry(track, clip2)),
                -5.0, 0, List.of(track));
        action.execute();

        assertThat(clip1.getStartBeat()).isCloseTo(0.0, offset(0.001));
        assertThat(clip2.getStartBeat()).isCloseTo(2.0, offset(0.001));
    }

    @Test
    void shouldKeepClipOnOriginalTrackWhenTargetOutOfBounds() {
        Track drums = new Track("Drums", TrackType.AUDIO);
        AudioClip kick = new AudioClip("kick", 0.0, 4.0, null);
        drums.addClip(kick);

        // Track delta of 5 but only 1 track exists
        GroupMoveClipsAction action = new GroupMoveClipsAction(
                List.of(Map.entry(drums, kick)),
                0.0, 5, List.of(drums));
        action.execute();

        assertThat(drums.getClips()).hasSize(1);
        assertThat(kick.getStartBeat()).isCloseTo(0.0, offset(0.001));
    }

    @Test
    void shouldWorkWithUndoManager() {
        Track track = new Track("Drums", TrackType.AUDIO);
        AudioClip clip = new AudioClip("kick", 0.0, 4.0, null);
        track.addClip(clip);
        UndoManager undoManager = new UndoManager();

        undoManager.execute(new GroupMoveClipsAction(
                List.of(Map.entry(track, clip)), 3.0, 0, List.of(track)));
        assertThat(clip.getStartBeat()).isCloseTo(3.0, offset(0.001));

        undoManager.undo();
        assertThat(clip.getStartBeat()).isCloseTo(0.0, offset(0.001));

        undoManager.redo();
        assertThat(clip.getStartBeat()).isCloseTo(3.0, offset(0.001));
    }

    @Test
    void shouldMoveClipsAcrossMultipleTracks() {
        Track t1 = new Track("Track 1", TrackType.AUDIO);
        Track t2 = new Track("Track 2", TrackType.AUDIO);
        Track t3 = new Track("Track 3", TrackType.AUDIO);
        AudioClip c1 = new AudioClip("clip1", 0.0, 4.0, null);
        AudioClip c2 = new AudioClip("clip2", 2.0, 4.0, null);
        t1.addClip(c1);
        t2.addClip(c2);

        // Move both clips down by 1 track and 1 beat
        GroupMoveClipsAction action = new GroupMoveClipsAction(
                List.of(Map.entry(t1, c1), Map.entry(t2, c2)),
                1.0, 1, List.of(t1, t2, t3));
        action.execute();

        assertThat(t1.getClips()).isEmpty();
        assertThat(t2.getClips()).hasSize(1);
        assertThat(t2.getClips().getFirst()).isSameAs(c1);
        assertThat(t3.getClips()).hasSize(1);
        assertThat(t3.getClips().getFirst()).isSameAs(c2);
        assertThat(c1.getStartBeat()).isCloseTo(1.0, offset(0.001));
        assertThat(c2.getStartBeat()).isCloseTo(3.0, offset(0.001));
    }

    @Test
    void shouldRejectNullEntries() {
        assertThatThrownBy(() -> new GroupMoveClipsAction(null, 1.0, 0, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyEntries() {
        assertThatThrownBy(() -> new GroupMoveClipsAction(List.of(), 1.0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
