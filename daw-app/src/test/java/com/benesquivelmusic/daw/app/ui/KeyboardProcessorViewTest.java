package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.midi.KeyboardPreset;
import com.benesquivelmusic.daw.core.midi.KeyboardProcessor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyboardProcessorViewTest {

    @Test
    void countWhiteKeysShouldReturnCorrectCountForSingleOctave() {
        // Octave 4 (C4..B4): 7 white keys per octave, plus one extra for C5
        // Actually range is from (lowestOctave+1)*12 to (highestOctave+2)*12-1
        // For octave 4 to 4: notes 60..71 (C4..B4) = 7 white keys
        // BUT range is (4+1)*12=60 to min(127, (4+2)*12-1)=71
        int count = KeyboardProcessorView.countWhiteKeys(4, 4);
        assertThat(count).isEqualTo(7);
    }

    @Test
    void countWhiteKeysShouldReturnCorrectCountForTwoOctaves() {
        // Octaves 4 to 5: notes 60..83 (C4..B5) = 14 white keys
        int count = KeyboardProcessorView.countWhiteKeys(4, 5);
        assertThat(count).isEqualTo(14);
    }

    @Test
    void countWhiteKeysShouldReturnCorrectCountForDefaultPianoRange() {
        // Grand piano preset: octaves 2–6 = 5 octaves = 35 white keys
        int count = KeyboardProcessorView.countWhiteKeys(2, 6);
        assertThat(count).isEqualTo(35);
    }

    @Test
    void countWhiteKeysShouldHandleMinOctave() {
        // Octave -1: notes 0..11 (C-1..B-1) = 7 white keys
        int count = KeyboardProcessorView.countWhiteKeys(-1, -1);
        assertThat(count).isEqualTo(7);
    }

    @Test
    void countWhiteKeysShouldCapAtNote127() {
        // Octave 9: notes 120..127 (C9..G9) = 5 white keys (C,D,E,F,G)
        int count = KeyboardProcessorView.countWhiteKeys(9, 9);
        assertThat(count).isEqualTo(5);
    }

    @Test
    void whiteKeyWidthConstantShouldBePositive() {
        assertThat(KeyboardProcessorView.WHITE_KEY_WIDTH).isGreaterThan(0);
    }

    @Test
    void blackKeyWidthShouldBeSmallerThanWhite() {
        assertThat(KeyboardProcessorView.BLACK_KEY_WIDTH)
                .isLessThan(KeyboardProcessorView.WHITE_KEY_WIDTH);
    }

    @Test
    void blackKeyHeightShouldBeSmallerThanWhite() {
        assertThat(KeyboardProcessorView.BLACK_KEY_HEIGHT)
                .isLessThan(KeyboardProcessorView.WHITE_KEY_HEIGHT);
    }

    @Test
    void notesPerOctaveShouldBe12() {
        assertThat(KeyboardProcessorView.NOTES_PER_OCTAVE).isEqualTo(12);
    }

    @Test
    void isBlackKeyShouldIdentifyBlackKeys() {
        // C=0 white, C#=1 black, D=2 white, D#=3 black, E=4 white
        // F=5 white, F#=6 black, G=7 white, G#=8 black, A=9 white, A#=10 black, B=11 white
        assertThat(KeyboardProcessor.isBlackKey(0)).isFalse();
        assertThat(KeyboardProcessor.isBlackKey(1)).isTrue();
        assertThat(KeyboardProcessor.isBlackKey(4)).isFalse();
        assertThat(KeyboardProcessor.isBlackKey(6)).isTrue();
        assertThat(KeyboardProcessor.isBlackKey(11)).isFalse();
    }

    @Test
    void factoryPresetsShouldAllBeValid() {
        KeyboardPreset[] presets = KeyboardPreset.factoryPresets();
        assertThat(presets).isNotEmpty();
        for (KeyboardPreset preset : presets) {
            assertThat(preset.name()).isNotBlank();
            assertThat(preset.lowestOctave()).isLessThanOrEqualTo(preset.highestOctave());
            assertThat(preset.defaultVelocity()).isBetween(1, 127);
        }
    }
}
