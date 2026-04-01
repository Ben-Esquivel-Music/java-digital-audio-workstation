package com.benesquivelmusic.daw.acoustics.common;

import java.util.Random;

/**
 * Column vector (n × 1) backed by a {@link Matrix}. Ported from RoomAcoustiCpp {@code Vec}.
 */
public class Vec extends Matrix {

    public Vec() { super(); }

    public Vec(int length) { super(length, 1); }

    public Vec(double[] vector) {
        super(vector.length, 1);
        for (int i = 0; i < vector.length; i++) data[i][0] = vector[i];
    }

    public double get(int i) { return data[i][0]; }
    public void set(int i, double v) { data[i][0] = v; }

    public void randomNormalDistribution() {
        Random rng = new Random(100);
        for (int i = 0; i < rows; i++) data[i][0] = rng.nextGaussian();
    }

    public void randomUniformDistribution() { randomUniformDistribution(0.0, 1.0); }

    @Override
    public void randomUniformDistribution(double a, double b) {
        Random rng = new Random(100);
        for (int i = 0; i < rows; i++) data[i][0] = a + (b - a) * rng.nextDouble();
    }

    public void normalise() {
        double norm = calculateNormal();
        if (norm > 0.0)
            for (int i = 0; i < rows; i++) data[i][0] /= norm;
    }

    public double calculateNormal() {
        double sum = 0.0;
        for (int i = 0; i < rows; i++) sum += data[i][0] * data[i][0];
        return Math.sqrt(sum);
    }

    public void clampMin(double min) {
        for (int i = 0; i < rows; i++) data[i][0] = Math.max(data[i][0], min);
    }

    public void clampMax(double max) {
        for (int i = 0; i < rows; i++) data[i][0] = Math.min(data[i][0], max);
    }

    public double sum() {
        double s = 0.0;
        for (int i = 0; i < rows; i++) s += data[i][0];
        return s;
    }

    public double mean() { return sum() / rows; }
}
