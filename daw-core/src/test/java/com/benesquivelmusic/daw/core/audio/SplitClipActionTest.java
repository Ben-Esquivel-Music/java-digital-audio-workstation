package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SplitClipActionTest {

    private UndoManager undoManager;
    private Track track;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
        track = new Track("Vocals", TrackType.AUDIO);
    }

    @Test
    void shouldSplitClipAndAddSecondToTrack() {
        AudioClip clip = new AudioClip("Take 1", 4.0, 8.0, "/audio/take1.wav");
        clip.setGainDb(-2.0);
        track.addClip(clip);

        SplitClipAction action = new SplitClipAction(track, clip, 8.0);
        undoManager.execute(action);

        assertThat(track.getClips()).hasSize(2);
        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(4.0);
        assertThat(clip.getEndBeat()).isEqualTo(8.0);

        AudioClip second = track.getClips().get(1);
        assertThat(second.getStartBeat()).isEqualTo(8.0);
        assertThat(second.getDurationBeats()).isEqualTo(4.0);
        assertThat(second.getEndBeat()).isEqualTo(12.0);
        assertThat(second.getGainDb()).isEqualTo(-2.0);
    }

    @Test
    void shouldUndoSplitAndRestoreOriginalClip() {
        AudioClip clip = new AudioClip("Take 1", 4.0, 8.0, "/audio/take1.wav");
        clip.setFadeOutBeats(2.0);
        track.addClip(clip);

        SplitClipAction action = new SplitClipAction(track, clip, 8.0);
        undoManager.execute(action);
        assertThat(track.getClips()).hasSize(2);

        undoManager.undo();

        assertThat(track.getClips()).hasSize(1);
        assertThat(clip.getStartBeat()).isEqualTo(4.0);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
        assertThat(clip.getFadeOutBeats()).isEqualTo(2.0);
    }

    @Test
    void shouldRedoSplitAfterUndo() {
        AudioClip clip = new AudioClip("Take 1", 0.0, 16.0, null);
        track.addClip(clip);

        SplitClipAction action = new SplitClipAction(track, clip, 8.0);
        undoManager.execute(action);
        undoManager.undo();

        undoManager.redo();

        assertThat(track.getClips()).hasSize(2);
        assertThat(clip.getDurationBeats()).isEqualTo(8.0);
    }

    @Test
    void shouldHaveCorrectDescription() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        SplitClipAction action = new SplitClipAction(track, clip, 4.0);

        assertThat(action.description()).isEqualTo("Split Clip");
    }

    @Test
    void shouldPreserveFadeOutCurveTypeOnSecondClip() {
        AudioClip clip = new AudioClip("Take 1", 0.0, 16.0, null);
        clip.setFadeOutBeats(2.0);
        clip.setFadeOutCurveType(FadeCurveType.S_CURVE);
        track.addClip(clip);

        SplitClipAction action = new SplitClipAction(track, clip, 8.0);
        undoManager.execute(action);

        AudioClip second = track.getClips().get(1);
        assertThat(second.getFadeOutBeats()).isEqualTo(2.0);
        assertThat(second.getFadeOutCurveType()).isEqualTo(FadeCurveType.S_CURVE);
    }

    @Test
    void shouldPreserveAudioDataOnSecondClip() {
        float[][] audioData = {{0.1f, 0.2f, 0.3f, 0.4f}};
        AudioClip clip = new AudioClip("Take 1", 0.0, 16.0, null);
        clip.setAudioData(audioData);
        track.addClip(clip);

        SplitClipAction action = new SplitClipAction(track, clip, 8.0);
        undoManager.execute(action);

        AudioClip second = track.getClips().get(1);
        assertThat(second.getAudioData()).isSameAs(audioData);
    }
}
