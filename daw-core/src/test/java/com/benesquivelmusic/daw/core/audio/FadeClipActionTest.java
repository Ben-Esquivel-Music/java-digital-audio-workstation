package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FadeClipActionTest {

    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
    }

    @Test
    void shouldApplyFadeParameters() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);

        FadeClipAction action = new FadeClipAction(clip,
                2.0, 3.0,
                FadeCurveType.EQUAL_POWER, FadeCurveType.S_CURVE);
        undoManager.execute(action);

        assertThat(clip.getFadeInBeats()).isEqualTo(2.0);
        assertThat(clip.getFadeOutBeats()).isEqualTo(3.0);
        assertThat(clip.getFadeInCurveType()).isEqualTo(FadeCurveType.EQUAL_POWER);
        assertThat(clip.getFadeOutCurveType()).isEqualTo(FadeCurveType.S_CURVE);
    }

    @Test
    void shouldUndoFadeAndRestoreOriginalValues() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);
        clip.setFadeInBeats(1.0);
        clip.setFadeOutBeats(1.5);
        clip.setFadeInCurveType(FadeCurveType.S_CURVE);
        clip.setFadeOutCurveType(FadeCurveType.EQUAL_POWER);

        FadeClipAction action = new FadeClipAction(clip,
                4.0, 5.0,
                FadeCurveType.LINEAR, FadeCurveType.LINEAR);
        undoManager.execute(action);
        undoManager.undo();

        assertThat(clip.getFadeInBeats()).isEqualTo(1.0);
        assertThat(clip.getFadeOutBeats()).isEqualTo(1.5);
        assertThat(clip.getFadeInCurveType()).isEqualTo(FadeCurveType.S_CURVE);
        assertThat(clip.getFadeOutCurveType()).isEqualTo(FadeCurveType.EQUAL_POWER);
    }

    @Test
    void shouldRedoFadeAfterUndo() {
        AudioClip clip = new AudioClip("Vocal", 0.0, 16.0, null);

        FadeClipAction action = new FadeClipAction(clip,
                2.0, 3.0,
                FadeCurveType.EQUAL_POWER, FadeCurveType.S_CURVE);
        undoManager.execute(action);
        undoManager.undo();

        undoManager.redo();

        assertThat(clip.getFadeInBeats()).isEqualTo(2.0);
        assertThat(clip.getFadeOutBeats()).isEqualTo(3.0);
        assertThat(clip.getFadeInCurveType()).isEqualTo(FadeCurveType.EQUAL_POWER);
        assertThat(clip.getFadeOutCurveType()).isEqualTo(FadeCurveType.S_CURVE);
    }

    @Test
    void shouldHaveCorrectDescription() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        FadeClipAction action = new FadeClipAction(clip,
                1.0, 1.0,
                FadeCurveType.LINEAR, FadeCurveType.LINEAR);

        assertThat(action.description()).isEqualTo("Adjust Clip Fade");
    }

    @Test
    void shouldHandleChangingOnlyFadeInCurveType() {
        AudioClip clip = new AudioClip("Test", 0.0, 8.0, null);
        clip.setFadeInBeats(1.0);
        clip.setFadeOutBeats(2.0);
        clip.setFadeInCurveType(FadeCurveType.LINEAR);
        clip.setFadeOutCurveType(FadeCurveType.LINEAR);

        FadeClipAction action = new FadeClipAction(clip,
                1.0, 2.0,
                FadeCurveType.S_CURVE, FadeCurveType.LINEAR);
        undoManager.execute(action);

        assertThat(clip.getFadeInCurveType()).isEqualTo(FadeCurveType.S_CURVE);
        assertThat(clip.getFadeOutCurveType()).isEqualTo(FadeCurveType.LINEAR);
        assertThat(clip.getFadeInBeats()).isEqualTo(1.0);
        assertThat(clip.getFadeOutBeats()).isEqualTo(2.0);
    }
}
