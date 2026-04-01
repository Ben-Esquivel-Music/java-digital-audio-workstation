package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InterpolationTest {

    @Test
    void lerpScalar() {
        assertThat(Interpolation.lerp(0.0, 10.0, 0.5)).isCloseTo(5.0, within(1e-10));
        assertThat(Interpolation.lerp(0.0, 10.0, 0.0)).isCloseTo(0.0, within(1e-10));
        assertThat(Interpolation.lerp(0.0, 10.0, 1.0)).isCloseTo(10.0, within(1e-10));
    }

    @Test
    void equalsWithinThreshold() {
        assertThat(Interpolation.equals(1.0, 1.0)).isTrue();
        assertThat(Interpolation.equals(1.0, 1.0000001)).isTrue();
        assertThat(Interpolation.equals(1.0, 2.0)).isFalse();
    }

    @Test
    void coefficientsEqual() {
        Coefficients a = new Coefficients(new double[]{1.0, 2.0, 3.0});
        Coefficients b = new Coefficients(new double[]{1.0, 2.0, 3.0});
        assertThat(Interpolation.equals(a, b)).isTrue();
    }

    @Test
    void coefficientsNotEqual() {
        Coefficients a = new Coefficients(new double[]{1.0, 2.0, 3.0});
        Coefficients b = new Coefficients(new double[]{1.0, 2.0, 4.0});
        assertThat(Interpolation.equals(a, b)).isFalse();
    }
}
