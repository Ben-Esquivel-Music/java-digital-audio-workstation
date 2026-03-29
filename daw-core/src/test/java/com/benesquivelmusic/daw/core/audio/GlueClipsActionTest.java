package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlueClipsActionTest {

    private UndoManager undoManager;
    private Track track;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
        track = new Track("Vocals", TrackType.AUDIO);
    }

    @Test
    void shouldGlueAdjacentClipsIntoOne() {
        AudioClip first = new AudioClip("Part A", 0.0, 4.0, "/audio/a.wav");
        AudioClip second = new AudioClip("Part B", 4.0, 4.0, "/audio/b.wav");
        track.addClip(first);
        track.addClip(second);

        GlueClipsAction action = new GlueClipsAction(track, first, second);
        undoManager.execute(action);

        assertThat(track.getClips()).hasSize(1);
        AudioClip merged = track.getClips().getFirst();
        assertThat(merged.getStartBeat()).isEqualTo(0.0);
        assertThat(merged.getDurationBeats()).isEqualTo(8.0);
        assertThat(merged.getEndBeat()).isEqualTo(8.0);
        assertThat(merged.getName()).isEqualTo("Part A");
    }

    @Test
    void shouldPreserveFadesFromOriginalClips() {
        AudioClip first = new AudioClip("Part A", 0.0, 4.0, null);
        first.setFadeInBeats(1.0);
        first.setFadeInCurveType(FadeCurveType.S_CURVE);
        AudioClip second = new AudioClip("Part B", 4.0, 4.0, null);
        second.setFadeOutBeats(0.5);
        second.setFadeOutCurveType(FadeCurveType.EQUAL_POWER);
        track.addClip(first);
        track.addClip(second);

        GlueClipsAction action = new GlueClipsAction(track, first, second);
        undoManager.execute(action);

        AudioClip merged = track.getClips().getFirst();
        assertThat(merged.getFadeInBeats()).isEqualTo(1.0);
        assertThat(merged.getFadeInCurveType()).isEqualTo(FadeCurveType.S_CURVE);
        assertThat(merged.getFadeOutBeats()).isEqualTo(0.5);
        assertThat(merged.getFadeOutCurveType()).isEqualTo(FadeCurveType.EQUAL_POWER);
    }

    @Test
    void shouldUndoGlueAndRestoreBothClips() {
        AudioClip first = new AudioClip("Part A", 0.0, 4.0, null);
        AudioClip second = new AudioClip("Part B", 4.0, 4.0, null);
        track.addClip(first);
        track.addClip(second);

        GlueClipsAction action = new GlueClipsAction(track, first, second);
        undoManager.execute(action);
        assertThat(track.getClips()).hasSize(1);

        undoManager.undo();

        assertThat(track.getClips()).hasSize(2);
        assertThat(track.getClips()).contains(first, second);
    }

    @Test
    void shouldRedoGlueAfterUndo() {
        AudioClip first = new AudioClip("Part A", 2.0, 4.0, null);
        AudioClip second = new AudioClip("Part B", 6.0, 4.0, null);
        track.addClip(first);
        track.addClip(second);

        GlueClipsAction action = new GlueClipsAction(track, first, second);
        undoManager.execute(action);
        undoManager.undo();

        undoManager.redo();

        assertThat(track.getClips()).hasSize(1);
        AudioClip merged = track.getClips().getFirst();
        assertThat(merged.getStartBeat()).isEqualTo(2.0);
        assertThat(merged.getDurationBeats()).isEqualTo(8.0);
    }

    @Test
    void shouldHaveCorrectDescription() {
        AudioClip first = new AudioClip("A", 0.0, 4.0, null);
        AudioClip second = new AudioClip("B", 4.0, 4.0, null);
        GlueClipsAction action = new GlueClipsAction(track, first, second);

        assertThat(action.description()).isEqualTo("Glue Clips");
    }

    @Test
    void shouldRejectReversedClipOrder() {
        AudioClip first = new AudioClip("A", 8.0, 4.0, null);
        AudioClip second = new AudioClip("B", 0.0, 4.0, null);

        assertThatThrownBy(() -> new GlueClipsAction(track, first, second))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldPreserveSourceOffsetAndGain() {
        AudioClip first = new AudioClip("A", 0.0, 4.0, "/audio/a.wav");
        first.setSourceOffsetBeats(2.0);
        first.setGainDb(-3.0);
        AudioClip second = new AudioClip("B", 4.0, 4.0, null);
        track.addClip(first);
        track.addClip(second);

        GlueClipsAction action = new GlueClipsAction(track, first, second);
        undoManager.execute(action);

        AudioClip merged = track.getClips().getFirst();
        assertThat(merged.getSourceOffsetBeats()).isEqualTo(2.0);
        assertThat(merged.getGainDb()).isEqualTo(-3.0);
        assertThat(merged.getSourceFilePath()).isEqualTo("/audio/a.wav");
    }
}
