package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

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
}
