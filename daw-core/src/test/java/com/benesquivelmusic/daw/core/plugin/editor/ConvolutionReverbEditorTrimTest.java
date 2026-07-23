package com.benesquivelmusic.daw.core.plugin.editor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-math tests for {@link ConvolutionReverbEditor}'s trim-drag gesture
 * helpers (story 302 §8.3) — the semantics ported from the retired daw-app
 * {@code ConvolutionReverbPluginView}: a PRIMARY-button drag moves the
 * trim-start marker, a SECONDARY-button drag moves the trim-end marker, the
 * pointer fraction is clamped into {@code [0, 1]}, and a gesture that would
 * bring the markers within {@link ConvolutionReverbEditor#MIN_TRIM_GAP} of
 * each other is rejected (the marker stays put). No JavaFX toolkit, no
 * control or canvas construction.
 */
class ConvolutionReverbEditorTrimTest {

    // ── PRIMARY drag → trim start ────────────────────────────────────────

    @Test
    void primaryDragMovesTrimStartWhenGapRespected() {
        assertThat(ConvolutionReverbEditor.dragTrimStart(0.30, 0.0, 1.0))
                .isEqualTo(0.30);
    }

    @Test
    void primaryDragClampsBelowZero() {
        assertThat(ConvolutionReverbEditor.dragTrimStart(-0.5, 0.3, 1.0))
                .isEqualTo(0.0);
    }

    @Test
    void primaryDragRejectedWithinMinGapOfTrimEnd() {
        // 0.495 is not < 0.5 - 0.01, so the gesture is rejected: start unchanged.
        assertThat(ConvolutionReverbEditor.dragTrimStart(0.495, 0.2, 0.5))
                .isEqualTo(0.2);
    }

    @Test
    void primaryDragAcceptedJustOutsideMinGap() {
        // 0.485 < 0.5 - 0.01, so the gesture is accepted.
        assertThat(ConvolutionReverbEditor.dragTrimStart(0.485, 0.2, 0.5))
                .isEqualTo(0.485);
    }

    @Test
    void primaryDragClampedToOneIsRejectedAgainstTrimEnd() {
        // Raw 1.5 clamps to 1.0, which violates the min gap below end=1.0:
        // the drag never moves the start marker past (or onto) the end marker.
        assertThat(ConvolutionReverbEditor.dragTrimStart(1.5, 0.3, 1.0))
                .isEqualTo(0.3);
    }

    // ── SECONDARY drag → trim end ────────────────────────────────────────

    @Test
    void secondaryDragMovesTrimEndWhenGapRespected() {
        assertThat(ConvolutionReverbEditor.dragTrimEnd(0.80, 0.0, 1.0))
                .isEqualTo(0.80);
    }

    @Test
    void secondaryDragClampsAboveOne() {
        assertThat(ConvolutionReverbEditor.dragTrimEnd(1.7, 0.0, 0.8))
                .isEqualTo(1.0);
    }

    @Test
    void secondaryDragRejectedWithinMinGapOfTrimStart() {
        // 0.505 is not > 0.5 + 0.01, so the gesture is rejected: end unchanged.
        assertThat(ConvolutionReverbEditor.dragTrimEnd(0.505, 0.5, 0.9))
                .isEqualTo(0.9);
    }

    @Test
    void secondaryDragAcceptedJustOutsideMinGap() {
        // 0.515 > 0.5 + 0.01, so the gesture is accepted.
        assertThat(ConvolutionReverbEditor.dragTrimEnd(0.515, 0.5, 0.9))
                .isEqualTo(0.515);
    }

    @Test
    void secondaryDragClampedToZeroIsRejectedAgainstTrimStart() {
        // Raw -0.2 clamps to 0.0, which violates the min gap above start=0.0:
        // the drag never moves the end marker past (or onto) the start marker.
        assertThat(ConvolutionReverbEditor.dragTrimEnd(-0.2, 0.0, 0.8))
                .isEqualTo(0.8);
    }

    // ── Shared clamp ─────────────────────────────────────────────────────

    @Test
    void clampFractionBoundsIntoUnitInterval() {
        assertThat(ConvolutionReverbEditor.clampFraction(-0.25)).isEqualTo(0.0);
        assertThat(ConvolutionReverbEditor.clampFraction(0.42)).isEqualTo(0.42);
        assertThat(ConvolutionReverbEditor.clampFraction(1.25)).isEqualTo(1.0);
    }
}
