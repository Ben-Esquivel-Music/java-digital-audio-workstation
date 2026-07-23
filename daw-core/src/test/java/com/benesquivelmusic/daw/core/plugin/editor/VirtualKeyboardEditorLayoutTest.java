package com.benesquivelmusic.daw.core.plugin.editor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-math tests for {@link VirtualKeyboardEditor}'s static keyboard-layout
 * model, ported from the retired daw-app {@code KeyboardProcessorViewTest}
 * (story 302 §8.3). Exercises only the package-private layout statics —
 * no JavaFX toolkit, no control or canvas construction.
 */
class VirtualKeyboardEditorLayoutTest {

    // ── countWhiteKeys (ported expectations) ───────────────────────────────

    @Test
    void countWhiteKeysShouldReturnCorrectCountForSingleOctave() {
        // Range is (lowestOctave+1)*12 to min(127, (highestOctave+2)*12-1):
        // octave 4 to 4 → notes 60..71 (C4..B4) = 7 white keys
        int count = VirtualKeyboardEditor.countWhiteKeys(4, 4);
        assertThat(count).isEqualTo(7);
    }

    @Test
    void countWhiteKeysShouldReturnCorrectCountForTwoOctaves() {
        // Octaves 4 to 5: notes 60..83 (C4..B5) = 14 white keys
        int count = VirtualKeyboardEditor.countWhiteKeys(4, 5);
        assertThat(count).isEqualTo(14);
    }

    @Test
    void countWhiteKeysShouldReturnCorrectCountForDefaultPianoRange() {
        // Grand piano preset: octaves 2–6 = 5 octaves = 35 white keys
        int count = VirtualKeyboardEditor.countWhiteKeys(2, 6);
        assertThat(count).isEqualTo(35);
    }

    @Test
    void countWhiteKeysShouldHandleMinOctave() {
        // Octave -1: notes 0..11 (C-1..B-1) = 7 white keys
        int count = VirtualKeyboardEditor.countWhiteKeys(-1, -1);
        assertThat(count).isEqualTo(7);
    }

    @Test
    void countWhiteKeysShouldCapAtNote127() {
        // Octave 9: notes 120..127 (C9..G9) = 5 white keys (C,D,E,F,G)
        int count = VirtualKeyboardEditor.countWhiteKeys(9, 9);
        assertThat(count).isEqualTo(5);
    }

    // ── Layout constants (ported expectations) ─────────────────────────────

    @Test
    void whiteKeyWidthConstantShouldBePositive() {
        assertThat(VirtualKeyboardEditor.WHITE_KEY_WIDTH).isGreaterThan(0);
    }

    @Test
    void blackKeyWidthShouldBeSmallerThanWhite() {
        assertThat(VirtualKeyboardEditor.BLACK_KEY_WIDTH)
                .isLessThan(VirtualKeyboardEditor.WHITE_KEY_WIDTH);
    }

    @Test
    void blackKeyHeightShouldBeSmallerThanWhite() {
        assertThat(VirtualKeyboardEditor.BLACK_KEY_HEIGHT)
                .isLessThan(VirtualKeyboardEditor.WHITE_KEY_HEIGHT);
    }

    @Test
    void notesPerOctaveShouldBe12() {
        assertThat(VirtualKeyboardEditor.NOTES_PER_OCTAVE).isEqualTo(12);
    }

    // ── hitTest (mouse → MIDI note mapping, now a pure static) ─────────────
    //
    // All cases use a single octave 4..4 keyboard: notes 60..71 (C4..B4),
    // 7 white keys, white key n at x = [n*28, n*28+28). Black keys sit at
    // bx = whiteIndex*28 - 9, 18 px wide: C#4=61 at [19,37), D#4=63 at
    // [47,65), F#4=66 at [103,121), G#4=68 at [131,149), A#4=70 at [159,177).

    @Test
    void hitTestShouldReturnMinusOneOutsideVerticalBounds() {
        assertThat(VirtualKeyboardEditor.hitTest(14, -1, 4, 4)).isEqualTo(-1);
        assertThat(VirtualKeyboardEditor.hitTest(14,
                VirtualKeyboardEditor.WHITE_KEY_HEIGHT + 1, 4, 4)).isEqualTo(-1);
    }

    @Test
    void hitTestShouldFindWhiteKeyBelowBlackKeyRegion() {
        // (14, 100) is inside C4's column, below the black-key height
        assertThat(VirtualKeyboardEditor.hitTest(14, 100, 4, 4)).isEqualTo(60);
    }

    @Test
    void hitTestShouldPreferBlackKeyInOverlapRegion() {
        // (20, 30) is inside both C4's white column and C#4's black key —
        // black keys overlap and win
        assertThat(VirtualKeyboardEditor.hitTest(20, 30, 4, 4)).isEqualTo(61);
    }

    @Test
    void hitTestShouldFindWhiteKeyBetweenBlackKeys() {
        // (40, 30) is above the black-key baseline but between C#4 [19,37)
        // and D#4 [47,65) — falls through to white key D4
        assertThat(VirtualKeyboardEditor.hitTest(40, 30, 4, 4)).isEqualTo(62);
    }

    @Test
    void hitTestShouldReturnMinusOneBeyondLastKey() {
        // 7 white keys end at x = 7 * 28 = 196
        assertThat(VirtualKeyboardEditor.hitTest(196.5, 100, 4, 4)).isEqualTo(-1);
        assertThat(VirtualKeyboardEditor.hitTest(250, 30, 4, 4)).isEqualTo(-1);
    }

    @Test
    void hitTestShouldFindLowestNoteAtLeftEdge() {
        assertThat(VirtualKeyboardEditor.hitTest(0, 90, 4, 4)).isEqualTo(60);
    }
}
