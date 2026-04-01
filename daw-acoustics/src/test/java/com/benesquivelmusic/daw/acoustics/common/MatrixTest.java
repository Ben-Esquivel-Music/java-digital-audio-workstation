package com.benesquivelmusic.daw.acoustics.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MatrixTest {

    @Test
    void identityMultiply() {
        Matrix identity = new Matrix(3, 3);
        for (int i = 0; i < 3; i++) identity.set(i, i, 1.0);

        Matrix m = new Matrix(new double[][]{{1, 2, 3}, {4, 5, 6}, {7, 8, 9}});
        Matrix result = Matrix.multiply(identity, m);

        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                assertThat(result.get(i, j)).isCloseTo(m.get(i, j), within(1e-10));
    }

    @Test
    void transpose() {
        Matrix m = new Matrix(new double[][]{{1, 2}, {3, 4}, {5, 6}});
        Matrix t = m.transpose();
        assertThat(t.rows()).isEqualTo(2);
        assertThat(t.cols()).isEqualTo(3);
        assertThat(t.get(0, 0)).isEqualTo(1);
        assertThat(t.get(1, 0)).isEqualTo(2);
        assertThat(t.get(0, 2)).isEqualTo(5);
    }

    @Test
    void inverse() {
        Matrix m = new Matrix(new double[][]{{4, 7}, {2, 6}});
        m.inverse();
        // Inverse of [[4,7],[2,6]] = [[0.6, -0.7], [-0.2, 0.4]]
        assertThat(m.get(0, 0)).isCloseTo(0.6, within(1e-10));
        assertThat(m.get(0, 1)).isCloseTo(-0.7, within(1e-10));
        assertThat(m.get(1, 0)).isCloseTo(-0.2, within(1e-10));
        assertThat(m.get(1, 1)).isCloseTo(0.4, within(1e-10));
    }

    @Test
    void addAndSubtract() {
        Matrix a = new Matrix(new double[][]{{1, 2}, {3, 4}});
        Matrix b = new Matrix(new double[][]{{5, 6}, {7, 8}});
        Matrix sum = Matrix.add(a, b);
        assertThat(sum.get(0, 0)).isEqualTo(6.0);
        assertThat(sum.get(1, 1)).isEqualTo(12.0);

        Matrix diff = Matrix.sub(a, b);
        assertThat(diff.get(0, 0)).isEqualTo(-4.0);
    }

    @Test
    void scalarMultiply() {
        Matrix m = new Matrix(new double[][]{{1, 2}, {3, 4}});
        Matrix result = Matrix.mul(3.0, m);
        assertThat(result.get(0, 0)).isEqualTo(3.0);
        assertThat(result.get(1, 1)).isEqualTo(12.0);
    }

    @Test
    void resetZeros() {
        Matrix m = new Matrix(new double[][]{{1, 2}, {3, 4}});
        m.reset();
        assertThat(m.get(0, 0)).isEqualTo(0.0);
        assertThat(m.get(1, 1)).isEqualTo(0.0);
    }
}
