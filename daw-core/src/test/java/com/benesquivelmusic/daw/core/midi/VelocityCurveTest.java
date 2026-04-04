package com.benesquivelmusic.daw.core.midi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityCurveTest {

    @Test
    void linearShouldReturnSameValue() {
        assertThat(VelocityCurve.LINEAR.apply(0)).isZero();
        assertThat(VelocityCurve.LINEAR.apply(64)).isEqualTo(64);
        assertThat(VelocityCurve.LINEAR.apply(127)).isEqualTo(127);
    }

    @Test
    void softShouldBoostLowVelocities() {
        // sqrt(0.5) ≈ 0.707 → 0.707 * 127 ≈ 90
        int result = VelocityCurve.SOFT.apply(64);
        assertThat(result).isGreaterThan(64);
        assertThat(result).isLessThanOrEqualTo(127);
    }

    @Test
    void softShouldReturnZeroForZero() {
        assertThat(VelocityCurve.SOFT.apply(0)).isZero();
    }

    @Test
    void softShouldReturn127ForMax() {
        assertThat(VelocityCurve.SOFT.apply(127)).isEqualTo(127);
    }

    @Test
    void hardShouldReduceLowVelocities() {
        // (64/127)^2 * 127 ≈ 32
        int result = VelocityCurve.HARD.apply(64);
        assertThat(result).isLessThan(64);
        assertThat(result).isGreaterThanOrEqualTo(0);
    }

    @Test
    void hardShouldReturnZeroForZero() {
        assertThat(VelocityCurve.HARD.apply(0)).isZero();
    }

    @Test
    void hardShouldReturn127ForMax() {
        assertThat(VelocityCurve.HARD.apply(127)).isEqualTo(127);
    }

    @Test
    void fixedShouldReturnClampedInput() {
        assertThat(VelocityCurve.FIXED.apply(50)).isEqualTo(50);
        assertThat(VelocityCurve.FIXED.apply(100)).isEqualTo(100);
        assertThat(VelocityCurve.FIXED.apply(127)).isEqualTo(127);
    }

    @ParameterizedTest
    @EnumSource(VelocityCurve.class)
    void shouldClampNegativeToZero(VelocityCurve curve) {
        assertThat(curve.apply(-10)).isGreaterThanOrEqualTo(0);
    }

    @ParameterizedTest
    @EnumSource(VelocityCurve.class)
    void shouldClampAboveMaxTo127(VelocityCurve curve) {
        assertThat(curve.apply(200)).isLessThanOrEqualTo(127);
    }

    @ParameterizedTest
    @EnumSource(VelocityCurve.class)
    void shouldAlwaysReturnInValidRange(VelocityCurve curve) {
        for (int v = 0; v <= 127; v++) {
            int mapped = curve.apply(v);
            assertThat(mapped).isBetween(0, 127);
        }
    }

    @Test
    void softCurveShouldBeMonotonicallyIncreasing() {
        int prev = VelocityCurve.SOFT.apply(0);
        for (int v = 1; v <= 127; v++) {
            int current = VelocityCurve.SOFT.apply(v);
            assertThat(current).isGreaterThanOrEqualTo(prev);
            prev = current;
        }
    }

    @Test
    void hardCurveShouldBeMonotonicallyIncreasing() {
        int prev = VelocityCurve.HARD.apply(0);
        for (int v = 1; v <= 127; v++) {
            int current = VelocityCurve.HARD.apply(v);
            assertThat(current).isGreaterThanOrEqualTo(prev);
            prev = current;
        }
    }

    @Test
    void clampShouldReturnMinForNegative() {
        assertThat(VelocityCurve.clamp(-5)).isZero();
    }

    @Test
    void clampShouldReturnMaxForAbove127() {
        assertThat(VelocityCurve.clamp(200)).isEqualTo(127);
    }

    @Test
    void clampShouldReturnValueWhenInRange() {
        assertThat(VelocityCurve.clamp(64)).isEqualTo(64);
    }

    @Test
    void maxVelocityConstantIs127() {
        assertThat(VelocityCurve.MAX_VELOCITY).isEqualTo(127);
    }

    @Test
    void allEnumValuesArePresent() {
        assertThat(VelocityCurve.values()).containsExactly(
                VelocityCurve.LINEAR, VelocityCurve.SOFT,
                VelocityCurve.HARD, VelocityCurve.FIXED);
    }
}
