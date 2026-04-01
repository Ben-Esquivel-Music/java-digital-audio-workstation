package com.benesquivelmusic.daw.acoustics.common;

/**
 * Row vector (1 × n) backed by a {@link Matrix}. Ported from RoomAcoustiCpp {@code Rowvec}.
 */
public class Rowvec extends Matrix {

    public Rowvec() { super(); }

    public Rowvec(int length) { super(1, length); }

    public Rowvec(double[] vector) {
        super(1, vector.length);
        System.arraycopy(vector, 0, data[0], 0, vector.length);
    }

    public double get(int i) { return data[0][i]; }
    public void set(int i, double v) { data[0][i] = v; }

    public double sum() {
        double s = 0.0;
        for (int i = 0; i < cols; i++) s += data[0][i];
        return s;
    }

    public double mean() { return sum() / cols; }
}
