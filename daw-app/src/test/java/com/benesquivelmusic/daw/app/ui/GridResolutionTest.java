package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class GridResolutionTest {

    @Test
    void shouldHaveTenResolutions() {
        assertThat(GridResolution.values()).hasSize(10);
    }

    @Test
    void shouldContainAllResolutionsInOrder() {
        assertThat(GridResolution.values())
                .containsExactly(
                        GridResolution.BAR,
                        GridResolution.HALF,
                        GridResolution.QUARTER,
                        GridResolution.EIGHTH,
                        GridResolution.SIXTEENTH,
                        GridResolution.THIRTY_SECOND,
                        GridResolution.HALF_TRIPLET,
                        GridResolution.QUARTER_TRIPLET,
                        GridResolution.EIGHTH_TRIPLET,
                        GridResolution.SIXTEENTH_TRIPLET);
    }

    @ParameterizedTest
    @EnumSource(GridResolution.class)
    void valueOfShouldRoundTrip(GridResolution resolution) {
        assertThat(GridResolution.valueOf(resolution.name())).isEqualTo(resolution);
    }

    @Test
    void barShouldBeFirstValue() {
        assertThat(GridResolution.values()[0]).isEqualTo(GridResolution.BAR);
    }

    @Test
    void displayNameShouldReturnReadableLabel() {
        assertThat(GridResolution.BAR.displayName()).isEqualTo("Bar");
        assertThat(GridResolution.HALF.displayName()).isEqualTo("1/2");
        assertThat(GridResolution.QUARTER.displayName()).isEqualTo("1/4");
        assertThat(GridResolution.EIGHTH.displayName()).isEqualTo("1/8");
        assertThat(GridResolution.SIXTEENTH.displayName()).isEqualTo("1/16");
        assertThat(GridResolution.THIRTY_SECOND.displayName()).isEqualTo("1/32");
        assertThat(GridResolution.HALF_TRIPLET.displayName()).isEqualTo("1/2T");
        assertThat(GridResolution.QUARTER_TRIPLET.displayName()).isEqualTo("1/4T");
        assertThat(GridResolution.EIGHTH_TRIPLET.displayName()).isEqualTo("1/8T");
        assertThat(GridResolution.SIXTEENTH_TRIPLET.displayName()).isEqualTo("1/16T");
    }

    @ParameterizedTest
    @EnumSource(GridResolution.class)
    void displayNameShouldNotBeNull(GridResolution resolution) {
        assertThat(resolution.displayName()).isNotNull().isNotEmpty();
    }

    @Test
    void quarterShouldBeDefaultResolution() {
        // QUARTER (1/4) is the most common default grid resolution in DAWs
        assertThat(GridResolution.QUARTER.ordinal()).isEqualTo(2);
    }

    // ── beatsPerGrid ─────────────────────────────────────────────────────────

    @Test
    void beatsPerGridShouldReturnCorrectValuesInCommonTime() {
        assertThat(GridResolution.BAR.beatsPerGrid(4)).isEqualTo(4.0);
        assertThat(GridResolution.HALF.beatsPerGrid(4)).isEqualTo(2.0);
        assertThat(GridResolution.QUARTER.beatsPerGrid(4)).isEqualTo(1.0);
        assertThat(GridResolution.EIGHTH.beatsPerGrid(4)).isEqualTo(0.5);
        assertThat(GridResolution.SIXTEENTH.beatsPerGrid(4)).isEqualTo(0.25);
        assertThat(GridResolution.THIRTY_SECOND.beatsPerGrid(4)).isEqualTo(0.125);
    }

    @Test
    void beatsPerGridTripletsShouldBeDividedByThree() {
        assertThat(GridResolution.HALF_TRIPLET.beatsPerGrid(4)).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(GridResolution.QUARTER_TRIPLET.beatsPerGrid(4)).isCloseTo(1.0 / 3.0, within(1e-9));
        assertThat(GridResolution.EIGHTH_TRIPLET.beatsPerGrid(4)).isCloseTo(0.5 / 3.0, within(1e-9));
        assertThat(GridResolution.SIXTEENTH_TRIPLET.beatsPerGrid(4)).isCloseTo(0.25 / 3.0, within(1e-9));
    }

    @Test
    void barShouldRespectBeatsPerBar() {
        assertThat(GridResolution.BAR.beatsPerGrid(3)).isEqualTo(3.0);
        assertThat(GridResolution.BAR.beatsPerGrid(6)).isEqualTo(6.0);
    }

    @ParameterizedTest
    @EnumSource(GridResolution.class)
    void beatsPerGridShouldBePositive(GridResolution resolution) {
        assertThat(resolution.beatsPerGrid(4)).isGreaterThan(0.0);
    }

    @Test
    void beatsPerGridShouldRejectNonPositiveBeatsPerBar() {
        assertThatThrownBy(() -> GridResolution.QUARTER.beatsPerGrid(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GridResolution.QUARTER.beatsPerGrid(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
