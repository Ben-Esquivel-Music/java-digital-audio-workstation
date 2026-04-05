package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class SnapQuantizerTest {

    private static final int BEATS_PER_BAR_4_4 = 4;

    // ── Basic quantize behaviour ─────────────────────────────────────────────

    @Test
    void shouldSnapToNearestQuarterBeat() {
        // 1.3 beats → nearest quarter grid line is 1.0
        assertThat(SnapQuantizer.quantize(1.3, GridResolution.QUARTER, BEATS_PER_BAR_4_4))
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldSnapToNearestQuarterBeatRoundUp() {
        // 1.7 beats → nearest quarter grid line is 2.0
        assertThat(SnapQuantizer.quantize(1.7, GridResolution.QUARTER, BEATS_PER_BAR_4_4))
                .isCloseTo(2.0, within(1e-9));
    }

    @Test
    void shouldSnapExactPositionUnchanged() {
        assertThat(SnapQuantizer.quantize(4.0, GridResolution.QUARTER, BEATS_PER_BAR_4_4))
                .isCloseTo(4.0, within(1e-9));
    }

    @Test
    void shouldClampNegativeToZero() {
        assertThat(SnapQuantizer.quantize(-0.3, GridResolution.QUARTER, BEATS_PER_BAR_4_4))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldHandleZeroPosition() {
        assertThat(SnapQuantizer.quantize(0.0, GridResolution.QUARTER, BEATS_PER_BAR_4_4))
                .isCloseTo(0.0, within(1e-9));
    }

    // ── Each GridResolution ──────────────────────────────────────────────────

    @Test
    void shouldSnapToBarBoundary() {
        // In 4/4 time, bar = 4 beats. 5.9 → 4.0, 6.1 → 8.0
        assertThat(SnapQuantizer.quantize(5.9, GridResolution.BAR, BEATS_PER_BAR_4_4))
                .isCloseTo(4.0, within(1e-9));
        assertThat(SnapQuantizer.quantize(6.1, GridResolution.BAR, BEATS_PER_BAR_4_4))
                .isCloseTo(8.0, within(1e-9));
    }

    @Test
    void shouldSnapToHalfNoteBoundary() {
        // Half = 2.0 beats. 2.8 → 2.0, 3.1 → 4.0
        assertThat(SnapQuantizer.quantize(2.8, GridResolution.HALF, BEATS_PER_BAR_4_4))
                .isCloseTo(2.0, within(1e-9));
        assertThat(SnapQuantizer.quantize(3.1, GridResolution.HALF, BEATS_PER_BAR_4_4))
                .isCloseTo(4.0, within(1e-9));
    }

    @Test
    void shouldSnapToEighthNoteBoundary() {
        // Eighth = 0.5 beats. 1.3 → 1.5, 1.1 → 1.0
        assertThat(SnapQuantizer.quantize(1.3, GridResolution.EIGHTH, BEATS_PER_BAR_4_4))
                .isCloseTo(1.5, within(1e-9));
        assertThat(SnapQuantizer.quantize(1.1, GridResolution.EIGHTH, BEATS_PER_BAR_4_4))
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void shouldSnapToSixteenthNoteBoundary() {
        // Sixteenth = 0.25 beats. 0.37 → 0.25, 0.38 → 0.5
        assertThat(SnapQuantizer.quantize(0.37, GridResolution.SIXTEENTH, BEATS_PER_BAR_4_4))
                .isCloseTo(0.25, within(1e-9));
        assertThat(SnapQuantizer.quantize(0.38, GridResolution.SIXTEENTH, BEATS_PER_BAR_4_4))
                .isCloseTo(0.5, within(1e-9));
    }

    @Test
    void shouldSnapToThirtySecondNoteBoundary() {
        // 1/32 = 0.125 beats. 0.18 → 0.125, 0.19 → 0.25
        assertThat(SnapQuantizer.quantize(0.18, GridResolution.THIRTY_SECOND, BEATS_PER_BAR_4_4))
                .isCloseTo(0.125, within(1e-9));
        assertThat(SnapQuantizer.quantize(0.19, GridResolution.THIRTY_SECOND, BEATS_PER_BAR_4_4))
                .isCloseTo(0.25, within(1e-9));
    }

    @Test
    void shouldSnapToHalfTripletBoundary() {
        // Half triplet = 2/3 beats ≈ 0.6667
        double grid = 2.0 / 3.0;
        assertThat(SnapQuantizer.quantize(0.5, GridResolution.HALF_TRIPLET, BEATS_PER_BAR_4_4))
                .isCloseTo(grid, within(1e-9));
        assertThat(SnapQuantizer.quantize(0.2, GridResolution.HALF_TRIPLET, BEATS_PER_BAR_4_4))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldSnapToQuarterTripletBoundary() {
        // Quarter triplet = 1/3 beats ≈ 0.3333
        double grid = 1.0 / 3.0;
        assertThat(SnapQuantizer.quantize(0.2, GridResolution.QUARTER_TRIPLET, BEATS_PER_BAR_4_4))
                .isCloseTo(grid, within(1e-9));
        assertThat(SnapQuantizer.quantize(0.1, GridResolution.QUARTER_TRIPLET, BEATS_PER_BAR_4_4))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldSnapToEighthTripletBoundary() {
        // Eighth triplet = 0.5/3 ≈ 0.1667
        double grid = 0.5 / 3.0;
        assertThat(SnapQuantizer.quantize(0.1, GridResolution.EIGHTH_TRIPLET, BEATS_PER_BAR_4_4))
                .isCloseTo(grid, within(1e-9));
        assertThat(SnapQuantizer.quantize(0.04, GridResolution.EIGHTH_TRIPLET, BEATS_PER_BAR_4_4))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void shouldSnapToSixteenthTripletBoundary() {
        // Sixteenth triplet = 0.25/3 ≈ 0.0833
        double grid = 0.25 / 3.0;
        assertThat(SnapQuantizer.quantize(0.05, GridResolution.SIXTEENTH_TRIPLET, BEATS_PER_BAR_4_4))
                .isCloseTo(grid, within(1e-9));
        assertThat(SnapQuantizer.quantize(0.02, GridResolution.SIXTEENTH_TRIPLET, BEATS_PER_BAR_4_4))
                .isCloseTo(0.0, within(1e-9));
    }

    // ── All resolutions should snap exact grid positions to themselves ────────

    @ParameterizedTest
    @EnumSource(GridResolution.class)
    void exactGridPositionShouldRemainUnchanged(GridResolution resolution) {
        double grid = resolution.beatsPerGrid(BEATS_PER_BAR_4_4);
        double position = grid * 3; // 3rd grid line
        assertThat(SnapQuantizer.quantize(position, resolution, BEATS_PER_BAR_4_4))
                .isCloseTo(position, within(1e-9));
    }

    @ParameterizedTest
    @EnumSource(GridResolution.class)
    void zeroShouldAlwaysSnapToZero(GridResolution resolution) {
        assertThat(SnapQuantizer.quantize(0.0, resolution, BEATS_PER_BAR_4_4))
                .isCloseTo(0.0, within(1e-9));
    }

    // ── Different time signatures ────────────────────────────────────────────

    @Test
    void barResolutionShouldRespectBeatsPerBar() {
        // 3/4 time: bar = 3 beats. 4.0 → 3.0
        assertThat(SnapQuantizer.quantize(4.0, GridResolution.BAR, 3))
                .isCloseTo(3.0, within(1e-9));
        // 6/8 time: bar = 6 beats. 4.0 → 6.0
        assertThat(SnapQuantizer.quantize(4.0, GridResolution.BAR, 6))
                .isCloseTo(6.0, within(1e-9));
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    void shouldRejectNullResolution() {
        assertThatThrownBy(() -> SnapQuantizer.quantize(1.0, null, BEATS_PER_BAR_4_4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectZeroBeatsPerBar() {
        assertThatThrownBy(() -> SnapQuantizer.quantize(1.0, GridResolution.QUARTER, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeBeatsPerBar() {
        assertThatThrownBy(() -> SnapQuantizer.quantize(1.0, GridResolution.QUARTER, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
