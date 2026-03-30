package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.icons.DawIcon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link TrackStripController} helper logic that can be exercised
 * without a live JavaFX scene or toolkit.
 */
class TrackStripControllerTest {

    // ── computeDropTargetIndex ───────────────────────────────────────────────

    @Test
    void shouldReturnNegativeOneWhenDropOnSamePosition() {
        // Dragging track 1 and dropping above track 2 results in no-op (same position)
        assertThat(TrackStripController.computeDropTargetIndex(1, 1, true)).isEqualTo(-1);
    }

    @Test
    void shouldMoveTrackDownWhenDropBelow() {
        // 3 tracks [0,1,2]: drag 0, drop below track 2 → target = 2
        assertThat(TrackStripController.computeDropTargetIndex(0, 2, false)).isEqualTo(2);
    }

    @Test
    void shouldMoveTrackUpWhenDropAbove() {
        // 3 tracks [0,1,2]: drag 2, drop above track 0 → target = 0
        assertThat(TrackStripController.computeDropTargetIndex(2, 0, true)).isEqualTo(0);
    }

    @Test
    void shouldHandleAdjacentMoveDown() {
        // 3 tracks [0,1,2]: drag 0, drop below track 1 → target = 1
        assertThat(TrackStripController.computeDropTargetIndex(0, 1, false)).isEqualTo(1);
    }

    @Test
    void shouldHandleAdjacentMoveUp() {
        // 3 tracks [0,1,2]: drag 1, drop above track 0 → target = 0
        assertThat(TrackStripController.computeDropTargetIndex(1, 0, true)).isEqualTo(0);
    }

    @Test
    void shouldReturnNegativeOneWhenDropBelowSelf() {
        // Dragging track 1 and dropping below track 0 → stays at index 1 → no-op
        assertThat(TrackStripController.computeDropTargetIndex(1, 0, false)).isEqualTo(-1);
    }

    @Test
    void shouldHandleDropAboveTopHalfAdjacentBelow() {
        // 3 tracks [0,1,2]: drag 0, drop above track 1 → stays at 0 → no-op
        assertThat(TrackStripController.computeDropTargetIndex(0, 1, true)).isEqualTo(-1);
    }

    @Test
    void shouldHandleMoveToEnd() {
        // 5 tracks [0..4]: drag 0, drop below track 4 → target = 4
        assertThat(TrackStripController.computeDropTargetIndex(0, 4, false)).isEqualTo(4);
    }

    @Test
    void shouldHandleMoveToStart() {
        // 5 tracks [0..4]: drag 4, drop above track 0 → target = 0
        assertThat(TrackStripController.computeDropTargetIndex(4, 0, true)).isEqualTo(0);
    }

    @Test
    void shouldHandleMoveFromMiddleDown() {
        // 5 tracks [0..4]: drag 2, drop below track 3 → target = 3
        assertThat(TrackStripController.computeDropTargetIndex(2, 3, false)).isEqualTo(3);
    }

    @Test
    void shouldHandleMoveFromMiddleUp() {
        // 5 tracks [0..4]: drag 2, drop above track 1 → target = 1
        assertThat(TrackStripController.computeDropTargetIndex(2, 1, true)).isEqualTo(1);
    }

    // ── midiInstrumentIcon keyword matching ──────────────────────────────────

    @ParameterizedTest(name = "trackName=\"{0}\" → {1}")
    @CsvSource({
            "Drums,           DRUMS",
            "Percussion,      DRUMS",
            "My Guitar Track, GUITAR",
            "Bass Guitar,     BASS_GUITAR",
            "Electric Guitar, ELECTRIC_GUITAR",
            "Acoustic Guitar, ACOUSTIC_GUITAR",
            "Bass Line,       BASS_GUITAR",
            "Violin I,        VIOLIN",
            "Strings,         VIOLIN",
            "Cello,           CELLO",
            "Sax Solo,        SAXOPHONE",
            "Trumpet,         TRUMPET",
            "Trombone,        TROMBONE",
            "Tuba,            TUBA",
            "Flute Melody,    FLUTE",
            "Clarinet,        CLARINET",
            "Concert Harp,    HARP",
            "Harmonica,       HARMONICA",
            "Banjo,           BANJO",
            "Mandolin,        MANDOLIN",
            "Ukulele,         UKULELE",
            "Uke Strum,       UKULELE",
            "Accordion,       ACCORDION",
            "Xylophone,       XYLOPHONE",
            "Marimba,         XYLOPHONE",
            "Bongo,           BONGOS",
            "Djembe,          DJEMBE",
            "Maracas,         MARACAS",
            "Tambourine,      TAMBOURINE",
            "Electric,        ELECTRIC_GUITAR",
            "Acoustic,        ACOUSTIC_GUITAR",
            "Organ,           KEYBOARD",
            "Keyboard,        KEYBOARD",
            "Synth Lead,      EQUALIZER",
            "Warm Pad,        PAD"
    })
    void shouldMatchInstrumentKeyword(String trackName, DawIcon expected) {
        assertThat(TrackStripController.midiInstrumentIcon(trackName)).isEqualTo(expected);
    }

    @Test
    void shouldReturnPianoForUnrecognizedName() {
        assertThat(TrackStripController.midiInstrumentIcon("MIDI 1")).isEqualTo(DawIcon.PIANO);
    }

    @Test
    void shouldBeCaseInsensitive() {
        assertThat(TrackStripController.midiInstrumentIcon("DRUMS")).isEqualTo(DawIcon.DRUMS);
        assertThat(TrackStripController.midiInstrumentIcon("drums")).isEqualTo(DawIcon.DRUMS);
        assertThat(TrackStripController.midiInstrumentIcon("DrUmS")).isEqualTo(DawIcon.DRUMS);
    }

    @Test
    void shouldMatchSubstringInTrackName() {
        assertThat(TrackStripController.midiInstrumentIcon("My Cool Guitar Solo"))
                .isEqualTo(DawIcon.GUITAR);
        assertThat(TrackStripController.midiInstrumentIcon("Track 3 - bass"))
                .isEqualTo(DawIcon.BASS_GUITAR);
    }

    @Test
    void shouldReturnPianoForEmptyOrGenericName() {
        assertThat(TrackStripController.midiInstrumentIcon("")).isEqualTo(DawIcon.PIANO);
        assertThat(TrackStripController.midiInstrumentIcon("Instrument")).isEqualTo(DawIcon.PIANO);
        assertThat(TrackStripController.midiInstrumentIcon("Lead")).isEqualTo(DawIcon.PIANO);
    }

    @Test
    void shouldMatchFirstKeywordWhenMultiplePresent() {
        // "drum" comes before "guitar" in the check order
        assertThat(TrackStripController.midiInstrumentIcon("Drum Guitar")).isEqualTo(DawIcon.DRUMS);
    }

    @Test
    void shouldPreferCompoundGuitarNamesOverGenericGuitar() {
        assertThat(TrackStripController.midiInstrumentIcon("Bass Guitar"))
                .isEqualTo(DawIcon.BASS_GUITAR);
        assertThat(TrackStripController.midiInstrumentIcon("Electric Guitar"))
                .isEqualTo(DawIcon.ELECTRIC_GUITAR);
        assertThat(TrackStripController.midiInstrumentIcon("Acoustic Guitar"))
                .isEqualTo(DawIcon.ACOUSTIC_GUITAR);
        // Generic "guitar" without qualifier still returns GUITAR
        assertThat(TrackStripController.midiInstrumentIcon("Guitar Solo"))
                .isEqualTo(DawIcon.GUITAR);
    }
}
