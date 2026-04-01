package com.benesquivelmusic.daw.acoustics.common;

import java.util.Arrays;
import java.util.Random;

/**
 * Mutable dense matrix backed by a 2-D {@code double} array. Ported from RoomAcoustiCpp {@code Matrix}.
 */
public class Matrix {

    protected int rows;
    protected int cols;
    protected double[][] data;

    public Matrix() { this(0, 0); }

    public Matrix(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        data = new double[rows][cols];
    }

    public Matrix(double[][] matrix) {
        this.rows = matrix.length;
        this.cols = rows > 0 ? matrix[0].length : 0;
        data = new double[rows][cols];
        for (int i = 0; i < rows; i++)
            System.arraycopy(matrix[i], 0, data[i], 0, cols);
    }

    public int rows() { return rows; }
    public int cols() { return cols; }

    public void reset() {
        for (double[] row : data) Arrays.fill(row, 0.0);
    }

    public void addColumn(double[] column, int c) {
        for (int i = 0; i < rows; i++) data[i][c] = column[i];
    }

    public void addRow(double[] row, int r) {
        System.arraycopy(row, 0, data[r], 0, cols);
    }

    public double[] getRow(int r) { return data[r]; }

    public double[] getColumn(int c) {
        double[] col = new double[rows];
        for (int i = 0; i < rows; i++) col[i] = data[i][c];
        return col;
    }

    public double get(int r, int c) { return data[r][c]; }
    public void set(int r, int c, double v) { data[r][c] = v; }

    public Matrix transpose() {
        Matrix t = new Matrix(cols, rows);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                t.data[j][i] = data[i][j];
        return t;
    }

    /** In-place Gauss-Jordan inverse. */
    public void inverse() {
        int n = rows;
        double[][] aug = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(data[i], 0, aug[i], 0, n);
            aug[i][n + i] = 1.0;
        }
        for (int i = 0; i < n; i++) {
            int maxRow = i;
            for (int k = i + 1; k < n; k++)
                if (Math.abs(aug[k][i]) > Math.abs(aug[maxRow][i])) maxRow = k;
            double[] tmp = aug[i]; aug[i] = aug[maxRow]; aug[maxRow] = tmp;
            double pivot = aug[i][i];
            for (int j = 0; j < 2 * n; j++) aug[i][j] /= pivot;
            for (int k = 0; k < n; k++) {
                if (k == i) continue;
                double factor = aug[k][i];
                for (int j = 0; j < 2 * n; j++) aug[k][j] -= factor * aug[i][j];
            }
        }
        for (int i = 0; i < n; i++)
            System.arraycopy(aug[i], n, data[i], 0, n);
    }

    /** Element-wise log10 in place. */
    public void log10() {
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                data[i][j] = Definitions.log10(data[i][j]);
    }

    /** Element-wise pow10 in place. */
    public void pow10() {
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                data[i][j] = Definitions.pow10(data[i][j]);
    }

    /** Clamp each element to at least {@code min}. */
    public void max(double min) {
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                data[i][j] = Math.max(data[i][j], min);
    }

    /** Clamp each element to at most {@code max}. */
    public void min(double max) {
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                data[i][j] = Math.min(data[i][j], max);
    }

    public void randomUniformDistribution() { randomUniformDistribution(0.0, 1.0); }

    public void randomUniformDistribution(double a, double b) {
        Random rng = new Random(100);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                data[i][j] = a + (b - a) * rng.nextDouble();
    }

    // Compound assignment operators
    public Matrix addLocal(Matrix m) {
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) data[i][j] += m.data[i][j];
        return this;
    }

    public Matrix subLocal(Matrix m) {
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) data[i][j] -= m.data[i][j];
        return this;
    }

    public Matrix mulLocal(double a) {
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) data[i][j] *= a;
        return this;
    }

    public Matrix divLocal(double a) { return mulLocal(1.0 / a); }

    public Matrix addScalarLocal(double a) {
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) data[i][j] += a;
        return this;
    }

    // Static operations
    public static Matrix add(Matrix u, Matrix v) {
        Matrix out = new Matrix(u.rows, u.cols);
        for (int i = 0; i < u.rows; i++)
            for (int j = 0; j < u.cols; j++)
                out.data[i][j] = u.data[i][j] + v.data[i][j];
        return out;
    }

    public static Matrix sub(Matrix u, Matrix v) {
        Matrix out = new Matrix(u.rows, u.cols);
        for (int i = 0; i < u.rows; i++)
            for (int j = 0; j < u.cols; j++)
                out.data[i][j] = u.data[i][j] - v.data[i][j];
        return out;
    }

    public static Matrix negate(Matrix m) {
        Matrix out = new Matrix(m.rows, m.cols);
        for (int i = 0; i < m.rows; i++)
            for (int j = 0; j < m.cols; j++)
                out.data[i][j] = -m.data[i][j];
        return out;
    }

    public static void multiply(Matrix out, Matrix u, Matrix v) {
        for (int i = 0; i < u.rows; i++)
            for (int j = 0; j < v.cols; j++) {
                double sum = 0.0;
                for (int k = 0; k < u.cols; k++) sum += u.data[i][k] * v.data[k][j];
                out.data[i][j] = sum;
            }
    }

    public static Matrix multiply(Matrix u, Matrix v) {
        Matrix out = new Matrix(u.rows, v.cols);
        multiply(out, u, v);
        return out;
    }

    public static Matrix mul(double a, Matrix m) {
        Matrix out = new Matrix(m.rows, m.cols);
        for (int i = 0; i < m.rows; i++)
            for (int j = 0; j < m.cols; j++)
                out.data[i][j] = a * m.data[i][j];
        return out;
    }

    public static Matrix div(Matrix m, double a) { return mul(1.0 / a, m); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Matrix m)) return false;
        if (rows != m.rows || cols != m.cols) return false;
        for (int i = 0; i < rows; i++)
            if (!Arrays.equals(data[i], m.data[i])) return false;
        return true;
    }

    @Override
    public int hashCode() { return Arrays.deepHashCode(data); }
}
