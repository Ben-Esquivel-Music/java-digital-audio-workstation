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

    // ── midiInstrumentIcon keyword matching ──────────────────────────────────

    @ParameterizedTest(name = "trackName=\"{0}\" → {1}")
    @CsvSource({
            "Drums,           DRUMS",
            "Percussion,      DRUMS",
            "My Guitar Track, GUITAR",
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
}
