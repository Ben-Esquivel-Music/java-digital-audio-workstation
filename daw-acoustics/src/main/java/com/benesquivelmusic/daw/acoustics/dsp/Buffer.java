package com.benesquivelmusic.daw.acoustics.dsp;

import java.util.Arrays;

/**
 * Mutable resizable audio buffer. Ported from RoomAcoustiCpp {@code Buffer<Real>}.
 */
public final class Buffer {

    private double[] data;

    public Buffer() { data = new double[1]; }

    public Buffer(int length) { data = new double[length]; }

    public Buffer(double[] values) { data = Arrays.copyOf(values, values.length); }

    public Buffer(Buffer other) { data = Arrays.copyOf(other.data, other.data.length); }

    public void reset() { Arrays.fill(data, 0.0); }

    public int length() { return data.length; }

    public void resize(int numSamples) {
        if (data.length == numSamples) return;
        double[] newData = new double[numSamples];
        System.arraycopy(data, 0, newData, 0, Math.min(data.length, numSamples));
        data = newData;
    }

    public boolean valid() {
        for (double v : data) if (Double.isNaN(v)) return false;
        return true;
    }

    public double get(int i) { return data[i]; }
    public void set(int i, double v) { data[i] = v; }

    public Buffer mulLocal(double a) { for (int i = 0; i < data.length; i++) data[i] *= a; return this; }
    public Buffer addLocal(double a) { for (int i = 0; i < data.length; i++) data[i] += a; return this; }
    public Buffer addLocal(Buffer x) { for (int i = 0; i < data.length; i++) data[i] += x.data[i]; return this; }

    /** Returns the underlying data array directly (for performance). */
    public double[] rawData() { return data; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Buffer b)) return false;
        return Arrays.equals(data, b.data);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(data); }
}
