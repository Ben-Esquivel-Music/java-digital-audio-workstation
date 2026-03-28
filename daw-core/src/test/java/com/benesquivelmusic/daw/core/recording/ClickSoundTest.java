package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClickSoundTest {

    @Test
    void shouldHaveThreePresets() {
        assertThat(ClickSound.values()).hasSize(3);
    }

    @Test
    void woodblockShouldHaveExpectedFrequencies() {
        assertThat(ClickSound.WOODBLOCK.getAccentFrequencyHz()).isEqualTo(1000.0);
        assertThat(ClickSound.WOODBLOCK.getNormalFrequencyHz()).isEqualTo(800.0);
    }

    @Test
    void cowbellShouldHaveExpectedFrequencies() {
        assertThat(ClickSound.COWBELL.getAccentFrequencyHz()).isEqualTo(1200.0);
        assertThat(ClickSound.COWBELL.getNormalFrequencyHz()).isEqualTo(900.0);
    }

    @Test
    void electronicShouldHaveExpectedFrequencies() {
        assertThat(ClickSound.ELECTRONIC.getAccentFrequencyHz()).isEqualTo(1500.0);
        assertThat(ClickSound.ELECTRONIC.getNormalFrequencyHz()).isEqualTo(1100.0);
    }

    @Test
    void accentFrequencyShouldBeHigherThanNormalForAllPresets() {
        for (ClickSound sound : ClickSound.values()) {
            assertThat(sound.getAccentFrequencyHz())
                    .as("Accent frequency for %s", sound)
                    .isGreaterThan(sound.getNormalFrequencyHz());
        }
    }

    @Test
    void shouldLookUpByName() {
        assertThat(ClickSound.valueOf("WOODBLOCK")).isEqualTo(ClickSound.WOODBLOCK);
        assertThat(ClickSound.valueOf("COWBELL")).isEqualTo(ClickSound.COWBELL);
        assertThat(ClickSound.valueOf("ELECTRONIC")).isEqualTo(ClickSound.ELECTRONIC);
    }
}
