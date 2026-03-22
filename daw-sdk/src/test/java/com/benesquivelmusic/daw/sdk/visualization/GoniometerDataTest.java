package com.benesquivelmusic.daw.sdk.visualization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoniometerDataTest {

    @Test
    void shouldCreateWithValidData() {
        float[] x = {0.1f, 0.2f, 0.3f};
        float[] y = {0.4f, 0.5f, 0.6f};
        float[] mag = {0.41f, 0.54f, 0.67f};
        float[] ang = {0.24f, 0.38f, 0.46f};

        var data = new GoniometerData(x, y, mag, ang, 3);

        assertThat(data.pointCount()).isEqualTo(3);
        assertThat(data.xPoints()).hasSize(3);
        assertThat(data.yPoints()).hasSize(3);
        assertThat(data.magnitudes()).hasSize(3);
        assertThat(data.angles()).hasSize(3);
    }

    @Test
    void shouldProvideEmptyConstant() {
        assertThat(GoniometerData.EMPTY.pointCount()).isZero();
        assertThat(GoniometerData.EMPTY.xPoints()).isEmpty();
        assertThat(GoniometerData.EMPTY.yPoints()).isEmpty();
        assertThat(GoniometerData.EMPTY.magnitudes()).isEmpty();
        assertThat(GoniometerData.EMPTY.angles()).isEmpty();
    }

    @Test
    void shouldDefensivelyCopyArrays() {
        float[] x = {1.0f};
        float[] y = {2.0f};
        float[] mag = {3.0f};
        float[] ang = {4.0f};

        var data = new GoniometerData(x, y, mag, ang, 1);

        // Mutating the original arrays should not affect the record
        x[0] = 99.0f;
        y[0] = 99.0f;
        assertThat(data.xPoints()[0]).isEqualTo(1.0f);
        assertThat(data.yPoints()[0]).isEqualTo(2.0f);
    }

    @Test
    void shouldRejectNullXPoints() {
        assertThatThrownBy(() -> new GoniometerData(
                null, new float[0], new float[0], new float[0], 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullYPoints() {
        assertThatThrownBy(() -> new GoniometerData(
                new float[0], null, new float[0], new float[0], 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullMagnitudes() {
        assertThatThrownBy(() -> new GoniometerData(
                new float[0], new float[0], null, new float[0], 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullAngles() {
        assertThatThrownBy(() -> new GoniometerData(
                new float[0], new float[0], new float[0], null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativePointCount() {
        assertThatThrownBy(() -> new GoniometerData(
                new float[0], new float[0], new float[0], new float[0], -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectArraysShorterThanPointCount() {
        assertThatThrownBy(() -> new GoniometerData(
                new float[2], new float[2], new float[2], new float[2], 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAllowArraysLongerThanPointCount() {
        var data = new GoniometerData(
                new float[5], new float[5], new float[5], new float[5], 3);
        assertThat(data.pointCount()).isEqualTo(3);
    }
}
