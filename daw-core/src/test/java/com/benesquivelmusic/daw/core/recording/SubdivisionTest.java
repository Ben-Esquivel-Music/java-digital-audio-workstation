package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubdivisionTest {

    @Test
    void shouldHaveThreeSubdivisions() {
        assertThat(Subdivision.values()).hasSize(3);
    }

    @Test
    void quarterShouldHaveOneClickPerBeat() {
        assertThat(Subdivision.QUARTER.getClicksPerBeat()).isEqualTo(1);
    }

    @Test
    void eighthShouldHaveTwoClicksPerBeat() {
        assertThat(Subdivision.EIGHTH.getClicksPerBeat()).isEqualTo(2);
    }

    @Test
    void sixteenthShouldHaveFourClicksPerBeat() {
        assertThat(Subdivision.SIXTEENTH.getClicksPerBeat()).isEqualTo(4);
    }

    @Test
    void shouldLookUpByName() {
        assertThat(Subdivision.valueOf("QUARTER")).isEqualTo(Subdivision.QUARTER);
        assertThat(Subdivision.valueOf("EIGHTH")).isEqualTo(Subdivision.EIGHTH);
        assertThat(Subdivision.valueOf("SIXTEENTH")).isEqualTo(Subdivision.SIXTEENTH);
    }
}
