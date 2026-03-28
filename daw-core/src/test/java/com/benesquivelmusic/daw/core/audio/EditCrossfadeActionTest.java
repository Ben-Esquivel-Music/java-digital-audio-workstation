package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.mastering.CrossfadeCurve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditCrossfadeActionTest {

    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        undoManager = new UndoManager();
    }

    @Test
    void shouldApplyNewCurveType() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        EditCrossfadeAction action = new EditCrossfadeAction(crossfade,
                CrossfadeCurve.EQUAL_POWER);
        undoManager.execute(action);

        assertThat(crossfade.getCurveType()).isEqualTo(CrossfadeCurve.EQUAL_POWER);
    }

    @Test
    void shouldUndoCurveTypeChange() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        EditCrossfadeAction action = new EditCrossfadeAction(crossfade,
                CrossfadeCurve.S_CURVE);
        undoManager.execute(action);
        undoManager.undo();

        assertThat(crossfade.getCurveType()).isEqualTo(CrossfadeCurve.LINEAR);
    }

    @Test
    void shouldRedoAfterUndo() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        EditCrossfadeAction action = new EditCrossfadeAction(crossfade,
                CrossfadeCurve.EQUAL_POWER);
        undoManager.execute(action);
        undoManager.undo();
        undoManager.redo();

        assertThat(crossfade.getCurveType()).isEqualTo(CrossfadeCurve.EQUAL_POWER);
    }

    @Test
    void shouldHaveCorrectDescription() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        EditCrossfadeAction action = new EditCrossfadeAction(crossfade,
                CrossfadeCurve.S_CURVE);

        assertThat(action.description()).isEqualTo("Edit Crossfade");
    }

    @Test
    void shouldPreserveOriginalAfterMultipleEdits() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        // First edit: LINEAR -> EQUAL_POWER
        EditCrossfadeAction action1 = new EditCrossfadeAction(crossfade,
                CrossfadeCurve.EQUAL_POWER);
        undoManager.execute(action1);
        assertThat(crossfade.getCurveType()).isEqualTo(CrossfadeCurve.EQUAL_POWER);

        // Second edit: EQUAL_POWER -> S_CURVE
        EditCrossfadeAction action2 = new EditCrossfadeAction(crossfade,
                CrossfadeCurve.S_CURVE);
        undoManager.execute(action2);
        assertThat(crossfade.getCurveType()).isEqualTo(CrossfadeCurve.S_CURVE);

        // Undo second: back to EQUAL_POWER
        undoManager.undo();
        assertThat(crossfade.getCurveType()).isEqualTo(CrossfadeCurve.EQUAL_POWER);

        // Undo first: back to LINEAR
        undoManager.undo();
        assertThat(crossfade.getCurveType()).isEqualTo(CrossfadeCurve.LINEAR);
    }

    @Test
    void shouldRejectNullCrossfade() {
        assertThatThrownBy(() -> new EditCrossfadeAction(null, CrossfadeCurve.LINEAR))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullCurveType() {
        AudioClip outgoing = new AudioClip("A", 0.0, 8.0, null);
        AudioClip incoming = new AudioClip("B", 6.0, 8.0, null);
        ClipCrossfade crossfade = new ClipCrossfade(outgoing, incoming,
                CrossfadeCurve.LINEAR);

        assertThatThrownBy(() -> new EditCrossfadeAction(crossfade, null))
                .isInstanceOf(NullPointerException.class);
    }
}
