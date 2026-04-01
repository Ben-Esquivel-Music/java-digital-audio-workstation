package com.benesquivelmusic.daw.acoustics.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DefinitionsTest {

    @Test
    void speedOfSoundIsCorrect() {
        assertThat(Definitions.SPEED_OF_SOUND).isCloseTo(343.5, within(0.01));
    }

    @Test
    void pow10RoundTripsWithLog10() {
        double val = 42.0;
        assertThat(Definitions.pow10(Definitions.log10(val))).isCloseTo(val, within(1e-10));
    }

    @Test
    void deg2RadAndBack() {
        assertThat(Definitions.deg2Rad(180.0)).isCloseTo(Math.PI, within(1e-10));
        assertThat(Definitions.rad2Deg(Math.PI)).isCloseTo(180.0, within(1e-10));
    }

    @Test
    void cotIdentity() {
        double angle = Math.PI / 4.0;
        assertThat(Definitions.cot(angle)).isCloseTo(1.0, within(1e-10));
    }

    @Test
    void factorialValues() {
        assertThat(Definitions.factorial(0)).isEqualTo(1.0);
        assertThat(Definitions.factorial(5)).isEqualTo(120.0);
    }

    @Test
    void doubleFactorialValues() {
        assertThat(Definitions.doubleFactorial(5)).isEqualTo(15.0); // 5 * 3 * 1
        assertThat(Definitions.doubleFactorial(6)).isEqualTo(48.0); // 6 * 4 * 2
    }

    @Test
    void safeAcosClamps() {
        assertThat(Definitions.safeAcos(2.0)).isCloseTo(0.0, within(1e-10));
        assertThat(Definitions.safeAcos(-2.0)).isCloseTo(Math.PI, within(1e-10));
    }

    @Test
    void signFunction() {
        assertThat(Definitions.sign(5.0)).isEqualTo(1.0);
        assertThat(Definitions.sign(-3.0)).isEqualTo(-1.0);
        assertThat(Definitions.sign(0.0)).isEqualTo(0.0);
    }

    @Test
    void roundFunction() {
        assertThat(Definitions.round(1.23456)).isCloseTo(1.235, within(1e-10));
    }

    @Test
    void roundWithDecimalPlaces() {
        assertThat(Definitions.round(1.23456, 2)).isCloseTo(1.23, within(1e-10));
    }
}
