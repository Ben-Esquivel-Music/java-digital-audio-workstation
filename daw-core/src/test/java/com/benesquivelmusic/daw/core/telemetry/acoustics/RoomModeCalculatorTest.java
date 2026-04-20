package com.benesquivelmusic.daw.core.telemetry.acoustics;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.telemetry.SoundWaveTelemetryEngine;
import com.benesquivelmusic.daw.sdk.telemetry.ModeKind;
import com.benesquivelmusic.daw.sdk.telemetry.ModeSpectrum;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomMode;
import com.benesquivelmusic.daw.sdk.telemetry.SurfaceMaterialMap;
import com.benesquivelmusic.daw.sdk.telemetry.TelemetrySuggestion;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomModeCalculatorTest {

    private static final double C = RoomModeCalculator.SPEED_OF_SOUND_M_S;
    private static final double EPS = 0.5; // Hz tolerance

    // ------------------------------------------------------------------
    // Core axial-mode behaviour (the issue's canonical acceptance test)
    // ------------------------------------------------------------------

    @Test
    void shouldProduceCanonicalAxialModesFor5By4By3Room() {
        // Issue: "a 5 × 4 × 3 m room produces known axial modes at
        // c/10, c/8, c/6 Hz with their first overtones at double".
        RoomModeCalculator calc = new RoomModeCalculator();
        RoomDimensions dims = new RoomDimensions(4.0, 5.0, 3.0); // width=4, length=5, height=3

        ModeSpectrum spectrum = calc.calculate(dims, WallMaterial.DRYWALL);

        List<RoomMode> axial = spectrum.axialModes();
        // Fundamental axial frequencies: c/(2·Li).
        assertThat(firstAxialFreqFor(axial, /*nx*/ 0, /*ny*/ 1, /*nz*/ 0))
                .as("length-axis 1st axial (c/10)")
                .isCloseTo(C / 10.0, within(EPS));
        assertThat(firstAxialFreqFor(axial, 1, 0, 0))
                .as("width-axis 1st axial (c/8)")
                .isCloseTo(C / 8.0, within(EPS));
        assertThat(firstAxialFreqFor(axial, 0, 0, 1))
                .as("height-axis 1st axial (c/6)")
                .isCloseTo(C / 6.0, within(EPS));

        // First overtones are at double the fundamentals.
        assertThat(firstAxialFreqFor(axial, 0, 2, 0))
                .as("length-axis 1st overtone (2·c/10)")
                .isCloseTo(2.0 * C / 10.0, within(EPS));
        assertThat(firstAxialFreqFor(axial, 2, 0, 0))
                .as("width-axis 1st overtone (2·c/8)")
                .isCloseTo(2.0 * C / 8.0, within(EPS));
        assertThat(firstAxialFreqFor(axial, 0, 0, 2))
                .as("height-axis 1st overtone (2·c/6)")
                .isCloseTo(2.0 * C / 6.0, within(EPS));
    }

    @Test
    void shouldClassifyModesByIndexTriple() {
        RoomModeCalculator calc = new RoomModeCalculator(2);
        ModeSpectrum spectrum = calc.calculate(
                new RoomDimensions(4.0, 5.0, 3.0), WallMaterial.DRYWALL);

        // At least one mode of each kind exists.
        assertThat(spectrum.axialModes()).isNotEmpty();
        assertThat(spectrum.tangentialModes()).isNotEmpty();
        assertThat(spectrum.obliqueModes()).isNotEmpty();

        for (RoomMode m : spectrum.modes()) {
            int nonZero = (m.nx() == 0 ? 0 : 1) + (m.ny() == 0 ? 0 : 1) + (m.nz() == 0 ? 0 : 1);
            ModeKind expected = switch (nonZero) {
                case 1 -> ModeKind.AXIAL;
                case 2 -> ModeKind.TANGENTIAL;
                case 3 -> ModeKind.OBLIQUE;
                default -> throw new AssertionError("zero index triple leaked: " + m);
            };
            assertThat(m.kind()).as(m.toString()).isEqualTo(expected);
        }
    }

    @Test
    void shouldEmitModesSortedByFrequency() {
        RoomModeCalculator calc = new RoomModeCalculator(3);
        ModeSpectrum spectrum = calc.calculate(
                new RoomDimensions(4.0, 5.0, 3.0), WallMaterial.DRYWALL);
        double last = 0.0;
        for (RoomMode m : spectrum.modes()) {
            assertThat(m.frequencyHz()).isGreaterThanOrEqualTo(last);
            last = m.frequencyHz();
        }
    }

    @Test
    void shouldEnumerateExactlyMaxOrderCubedMinusOneModes() {
        // (maxOrder+1)^3 - 1 triples (excluding (0,0,0)) for max order n.
        RoomModeCalculator calc = new RoomModeCalculator(3);
        ModeSpectrum spectrum = calc.calculate(
                new RoomDimensions(4.0, 5.0, 3.0), WallMaterial.DRYWALL);
        assertThat(spectrum.modes()).hasSize(4 * 4 * 4 - 1);
    }

    // ------------------------------------------------------------------
    // Schroeder frequency
    // ------------------------------------------------------------------

    @Test
    void schroederFrequencyShouldMatchPublishedFormula() {
        // f_s = 2000 · √(T60 / V).
        double t60 = 0.5;
        double v = 60.0;
        double expected = 2000.0 * Math.sqrt(t60 / v);
        assertThat(RoomModeCalculator.schroederFrequencyHz(t60, v))
                .isCloseTo(expected, within(1.0e-6));
    }

    @Test
    void spectrumShouldExposeSchroederFrequencyConsistentWithSabineRt60() {
        RoomDimensions dims = new RoomDimensions(4.0, 5.0, 3.0);
        SurfaceMaterialMap materials = new SurfaceMaterialMap(WallMaterial.DRYWALL);
        double rt60 = SoundWaveTelemetryEngine.estimateRt60(dims, materials);
        double expected = 2000.0 * Math.sqrt(rt60 / dims.volume());

        ModeSpectrum spectrum = new RoomModeCalculator().calculate(dims, materials);
        assertThat(spectrum.schroederHz()).isCloseTo(expected, within(1.0e-6));
    }

    // ------------------------------------------------------------------
    // Magnitude at the listening position
    // ------------------------------------------------------------------

    @Test
    void roomCornerListenerShouldSeeEveryModeAtItsAntinode() {
        // At (0, 0, 0) all cos(nπ·0/L) = 1, so p = 1 → 0 dB for every mode.
        RoomModeCalculator calc = new RoomModeCalculator(2);
        ModeSpectrum spectrum = calc.calculate(
                new RoomDimensions(4.0, 5.0, 3.0),
                new Position3D(0, 0, 0),
                /* rt60 */ 0.5);
        for (RoomMode m : spectrum.modes()) {
            assertThat(m.magnitudeDb()).as(m.toString())
                    .isCloseTo(0.0, within(1.0e-9));
        }
    }

    @Test
    void listenerAtWidthNodeShouldNullifyFirstWidthAxialMode() {
        // cos(π·(L/2)/L) = cos(π/2) = 0 → deep null on the (1,0,0) mode.
        double lx = 4.0;
        RoomModeCalculator calc = new RoomModeCalculator(1);
        ModeSpectrum spectrum = calc.calculate(
                new RoomDimensions(lx, 5.0, 3.0),
                new Position3D(lx / 2.0, 0, 0),
                /* rt60 */ 0.5);
        RoomMode firstWidth = firstMode(spectrum.modes(), 1, 0, 0);
        assertThat(firstWidth.magnitudeDb())
                .as("listener at width-node must see a deep null on the (1,0,0) mode")
                .isLessThan(-40.0);
    }

    // ------------------------------------------------------------------
    // Suggestions
    // ------------------------------------------------------------------

    @Test
    void cubicRoomShouldFlagCoincidingAxialModes() {
        // A 3 × 3 × 3 cube has identical axial modes on all three axes
        // — a textbook Bolt/Bonello failure.
        RoomModeCalculator calc = new RoomModeCalculator();
        ModeSpectrum spectrum = calc.calculate(
                new RoomDimensions(3.0, 3.0, 3.0), WallMaterial.DRYWALL);

        List<TelemetrySuggestion> suggestions = calc.suggestMitigations(
                new RoomDimensions(3.0, 3.0, 3.0), spectrum);

        assertThat(suggestions).isNotEmpty();
        // Cluster suggestions should appear because identical modes
        // collapse to the same frequency (gap = 0 Hz  <  20 Hz threshold).
        assertThat(suggestions)
                .anySatisfy(s -> assertThat(s.description())
                        .containsIgnoringCase("cluster"));
    }

    @Test
    void doublyProportionedRoomShouldFlagBoltCriterion() {
        // 6m length is exactly 2× the 3m height — axial modes coincide.
        RoomDimensions dims = new RoomDimensions(3.0, 6.0, 3.0);
        RoomModeCalculator calc = new RoomModeCalculator();
        ModeSpectrum spectrum = calc.calculate(dims, WallMaterial.DRYWALL);
        List<TelemetrySuggestion> suggestions = calc.suggestMitigations(dims, spectrum);
        assertThat(suggestions)
                .anySatisfy(s -> assertThat(s.description())
                        .containsIgnoringCase("proportion"));
    }

    @Test
    void wellProportionedRoomShouldNotFlagProportions() {
        // 4 × 5 × 3 has no near-integer ratios (5/3=1.67, 4/3=1.33,
        // 5/4=1.25) so proportion check stays quiet.
        RoomDimensions dims = new RoomDimensions(4.0, 5.0, 3.0);
        assertThat(RoomModeCalculator.evaluateProportions(dims)).isNull();
    }

    // ------------------------------------------------------------------
    // Full-config convenience
    // ------------------------------------------------------------------

    @Test
    void shouldAcceptRoomConfigurationAndDefaultToCenterListener() {
        RoomConfiguration config = new RoomConfiguration(
                new RoomDimensions(4.0, 5.0, 3.0), WallMaterial.DRYWALL);
        ModeSpectrum spectrum = new RoomModeCalculator().calculate(config);
        assertThat(spectrum.modes()).isNotEmpty();
        assertThat(spectrum.schroederHz()).isPositive();
    }

    // ------------------------------------------------------------------
    // Input validation
    // ------------------------------------------------------------------

    @Test
    void shouldRejectInvalidMaxOrder() {
        assertThatThrownBy(() -> new RoomModeCalculator(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNonPositiveRt60() {
        RoomModeCalculator calc = new RoomModeCalculator();
        assertThatThrownBy(() -> calc.calculate(
                new RoomDimensions(4, 5, 3), new Position3D(1, 1, 1), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schroederFormulaShouldRejectInvalidInputs() {
        assertThatThrownBy(() -> RoomModeCalculator.schroederFrequencyHz(0, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoomModeCalculator.schroederFrequencyHz(0.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static double firstAxialFreqFor(List<RoomMode> modes, int nx, int ny, int nz) {
        return firstMode(modes, nx, ny, nz).frequencyHz();
    }

    private static RoomMode firstMode(List<RoomMode> modes, int nx, int ny, int nz) {
        return modes.stream()
                .filter(m -> m.nx() == nx && m.ny() == ny && m.nz() == nz)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no mode with indices (" + nx + "," + ny + "," + nz + ")"));
    }

    private static org.assertj.core.data.Offset<Double> within(double eps) {
        return org.assertj.core.data.Offset.offset(eps);
    }
}
