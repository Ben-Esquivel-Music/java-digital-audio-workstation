package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CountInModeTest {

    @Test
    void offShouldHaveZeroBars() {
        assertThat(CountInMode.OFF.getBars()).isZero();
    }

    @Test
    void oneBarShouldHaveOnebar() {
        assertThat(CountInMode.ONE_BAR.getBars()).isEqualTo(1);
    }

    @Test
    void twoBarsShouldHaveTwoBars() {
        assertThat(CountInMode.TWO_BARS.getBars()).isEqualTo(2);
    }

    @Test
    void fourBarsShouldHaveFourBars() {
        assertThat(CountInMode.FOUR_BARS.getBars()).isEqualTo(4);
    }

    @Test
    void offShouldReturnZeroTotalBeats() {
        assertThat(CountInMode.OFF.getTotalBeats(4)).isZero();
    }

    @Test
    void oneBarInFourFourShouldReturnFourBeats() {
        assertThat(CountInMode.ONE_BAR.getTotalBeats(4)).isEqualTo(4);
    }

    @Test
    void twoBarsShouldReturnCorrectTotalBeats() {
        assertThat(CountInMode.TWO_BARS.getTotalBeats(4)).isEqualTo(8);
    }

    @Test
    void fourBarsShouldReturnCorrectTotalBeats() {
        assertThat(CountInMode.FOUR_BARS.getTotalBeats(4)).isEqualTo(16);
    }

    @Test
    void shouldHandleThreeFourTimeSig() {
        assertThat(CountInMode.TWO_BARS.getTotalBeats(3)).isEqualTo(6);
    }

    @Test
    void shouldRejectNonPositiveBeatsPerBar() {
        assertThatThrownBy(() -> CountInMode.ONE_BAR.getTotalBeats(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beatsPerBar");
    }

    @Test
    void shouldRejectNegativeBeatsPerBar() {
        assertThatThrownBy(() -> CountInMode.ONE_BAR.getTotalBeats(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beatsPerBar");
    }

    @Test
    void shouldHaveCorrectEnumValues() {
        assertThat(CountInMode.values()).containsExactly(
                CountInMode.OFF,
                CountInMode.ONE_BAR,
                CountInMode.TWO_BARS,
                CountInMode.FOUR_BARS);
    }
}
