package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomGeometrySolverTest {

    private static final double NOMINAL_POWER_DB = 85.0;
    private static final double MIC_DISTANCE = 1.0;

    @Test
    void reflectiveMaterialAtShortDistanceProducesLargerRoomThanAbsorbent() {
        RoomDimensions reflective =
                RoomGeometrySolver.solve(MIC_DISTANCE, WallMaterial.CONCRETE, NOMINAL_POWER_DB);
        RoomDimensions absorbent =
                RoomGeometrySolver.solve(MIC_DISTANCE, WallMaterial.ACOUSTIC_FOAM, NOMINAL_POWER_DB);

        // Same mic distance + low-absorption material → must be a larger
        // room than the same distance in an absorbent space.
        assertThat(reflective.volume()).isGreaterThan(absorbent.volume());
    }

    @Test
    void absorbentMaterialAtShortDistanceProducesSmallRoom() {
        RoomDimensions dims =
                RoomGeometrySolver.solve(MIC_DISTANCE, WallMaterial.ACOUSTIC_FOAM, NOMINAL_POWER_DB);

        // Heavy absorption + 1 m mic distance → a compact booth-sized room.
        assertThat(dims.width()).isLessThan(5.0);
        assertThat(dims.length()).isLessThan(5.0);
    }

    @Test
    void absorbentMaterialAtLongerDistanceProducesLargerRoomThanShortDistance() {
        RoomDimensions close =
                RoomGeometrySolver.solve(0.5, WallMaterial.CARPET, NOMINAL_POWER_DB);
        RoomDimensions far =
                RoomGeometrySolver.solve(4.0, WallMaterial.CARPET, NOMINAL_POWER_DB);

        assertThat(far.volume()).isGreaterThan(close.volume());
    }

    @Test
    void extremelyReflectiveMaterialClampsToValidDimensions() {
        // Marble has α = 0.01 — the raw solution would blow past the slider
        // upper bounds. Must clamp, not throw, and stay positive.
        RoomDimensions dims =
                RoomGeometrySolver.solve(5.0, WallMaterial.MARBLE, NOMINAL_POWER_DB);

        assertThat(dims.width()).isPositive().isLessThanOrEqualTo(RoomGeometrySolver.MAX_WIDTH);
        assertThat(dims.length()).isPositive().isLessThanOrEqualTo(RoomGeometrySolver.MAX_LENGTH);
        assertThat(dims.height()).isPositive().isLessThanOrEqualTo(RoomGeometrySolver.MAX_HEIGHT);
    }

    @Test
    void extremelyShortDistanceClampsToMinimumDimensions() {
        // A vanishingly small mic distance would solve to ~0 m — must clamp.
        RoomDimensions dims =
                RoomGeometrySolver.solve(0.01, WallMaterial.ACOUSTIC_FOAM, NOMINAL_POWER_DB);

        assertThat(dims.width()).isGreaterThanOrEqualTo(RoomGeometrySolver.MIN_WIDTH);
        assertThat(dims.length()).isGreaterThanOrEqualTo(RoomGeometrySolver.MIN_LENGTH);
        assertThat(dims.height()).isGreaterThanOrEqualTo(RoomGeometrySolver.MIN_HEIGHT);
    }

    @Test
    void nonPositiveDistanceDoesNotThrow() {
        // Zero / negative / NaN inputs should fall back to safe defaults
        // rather than throwing — the UI must remain usable.
        RoomDimensions zero =
                RoomGeometrySolver.solve(0.0, WallMaterial.DRYWALL, NOMINAL_POWER_DB);
        RoomDimensions negative =
                RoomGeometrySolver.solve(-1.0, WallMaterial.DRYWALL, NOMINAL_POWER_DB);
        RoomDimensions nan =
                RoomGeometrySolver.solve(Double.NaN, WallMaterial.DRYWALL, NOMINAL_POWER_DB);

        assertThat(zero.volume()).isPositive();
        assertThat(negative.volume()).isPositive();
        assertThat(nan.volume()).isPositive();
    }

    @Test
    void dimensionsAlwaysPositiveForAllMaterialsAndReasonableDistances() {
        for (WallMaterial material : WallMaterial.values()) {
            for (double distance : new double[] {0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0}) {
                RoomDimensions dims =
                        RoomGeometrySolver.solve(distance, material, NOMINAL_POWER_DB);
                assertThat(dims.width()).as("width for %s @ %.1f m", material, distance).isPositive();
                assertThat(dims.length()).as("length for %s @ %.1f m", material, distance).isPositive();
                assertThat(dims.height()).as("height for %s @ %.1f m", material, distance).isPositive();
            }
        }
    }

    @Test
    void louderSourceProducesLargerRoomThanQuieterSource() {
        RoomDimensions quiet =
                RoomGeometrySolver.solve(MIC_DISTANCE, WallMaterial.DRYWALL, 70.0);
        RoomDimensions loud =
                RoomGeometrySolver.solve(MIC_DISTANCE, WallMaterial.DRYWALL, 110.0);

        assertThat(loud.volume()).isGreaterThan(quiet.volume());
    }

    @Test
    void nullMaterialIsRejected() {
        assertThatThrownBy(() -> RoomGeometrySolver.solve(1.0, null, NOMINAL_POWER_DB))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void resultsPreserveTheTargetAspectRatioWhenUnclamped() {
        // For a mid-absorption material + typical distance the raw solution
        // sits well inside the slider ranges — so the aspect ratio
        // (1 : 4/3 : 2/3) should be preserved exactly.
        RoomDimensions dims =
                RoomGeometrySolver.solve(1.5, WallMaterial.CARPET, NOMINAL_POWER_DB);

        assertThat(dims.length() / dims.width()).isCloseTo(4.0 / 3.0,
                org.assertj.core.api.Assertions.within(1e-6));
        assertThat(dims.height() / dims.width()).isCloseTo(2.0 / 3.0,
                org.assertj.core.api.Assertions.within(1e-6));
    }
}
