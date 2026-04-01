package com.benesquivelmusic.daw.acoustics.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CoefficientsTest {

    @Test
    void constructWithValue() {
        Coefficients c = new Coefficients(5, 2.0);
        for (int i = 0; i < 5; i++)
            assertThat(c.get(i)).isEqualTo(2.0);
    }

    @Test
    void addAndSub() {
        Coefficients a = new Coefficients(new double[]{1, 2, 3});
        Coefficients b = new Coefficients(new double[]{4, 5, 6});
        Coefficients sum = Coefficients.add(a, b);
        assertThat(sum.get(0)).isEqualTo(5.0);
        assertThat(sum.get(2)).isEqualTo(9.0);

        Coefficients diff = Coefficients.sub(a, b);
        assertThat(diff.get(0)).isEqualTo(-3.0);
    }

    @Test
    void mulAndDiv() {
        Coefficients c = new Coefficients(new double[]{2, 4, 6});
        Coefficients scaled = Coefficients.mul(c, 3.0);
        assertThat(scaled.get(0)).isEqualTo(6.0);
        assertThat(scaled.get(2)).isEqualTo(18.0);

        Coefficients divided = Coefficients.div(c, 2.0);
        assertThat(divided.get(0)).isEqualTo(1.0);
    }

    @Test
    void elementWiseMultiply() {
        Coefficients a = new Coefficients(new double[]{1, 2, 3});
        Coefficients b = new Coefficients(new double[]{4, 5, 6});
        Coefficients product = Coefficients.mul(a, b);
        assertThat(product.get(0)).isEqualTo(4.0);
        assertThat(product.get(1)).isEqualTo(10.0);
        assertThat(product.get(2)).isEqualTo(18.0);
    }

    @Test
    void pow10AndLog() {
        Coefficients c = new Coefficients(new double[]{0, 1, 2});
        c.pow10Local();
        assertThat(c.get(0)).isCloseTo(1.0, within(1e-10));
        assertThat(c.get(1)).isCloseTo(10.0, within(1e-10));
        assertThat(c.get(2)).isCloseTo(100.0, within(1e-10));
    }

    @Test
    void comparisons() {
        Coefficients c = new Coefficients(new double[]{1, 2, 3});
        assertThat(c.allGreaterThan(0.0)).isTrue();
        assertThat(c.allGreaterThan(1.0)).isFalse();
        assertThat(c.allLessThan(4.0)).isTrue();
        assertThat(c.allLessThan(3.0)).isFalse();
    }

    @Test
    void sum() {
        Coefficients c = new Coefficients(new double[]{1, 2, 3, 4});
        assertThat(Coefficients.sum(c)).isEqualTo(10.0);
    }

    @Test
    void equality() {
        Coefficients a = new Coefficients(new double[]{1.0, 2.0});
        Coefficients b = new Coefficients(new double[]{1.0, 2.0});
        assertThat(a).isEqualTo(b);
    }
}
