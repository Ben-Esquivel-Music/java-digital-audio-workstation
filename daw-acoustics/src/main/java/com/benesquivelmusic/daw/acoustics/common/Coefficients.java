package com.benesquivelmusic.daw.acoustics.common;

import java.util.Arrays;

/**
 * Mutable coefficient array with element-wise arithmetic. Ported from RoomAcoustiCpp {@code Coefficients}.
 */
public class Coefficients {

    protected double[] data;

    public Coefficients(int length) { this(length, 0.0); }

    public Coefficients(int length, double value) {
        data = new double[length];
        Arrays.fill(data, value);
    }

    public Coefficients(double[] values) { data = Arrays.copyOf(values, values.length); }

    public Coefficients(Coefficients other) { this(other.data); }

    public int length() { return data.length; }

    public void update(double[] values) { data = Arrays.copyOf(values, values.length); }

    public double get(int i) { return data[i]; }
    public void set(int i, double v) { data[i] = v; }

    // In-place operations
    public Coefficients logLocal() {
        for (int i = 0; i < data.length; i++) data[i] = Math.log(data[i]);
        return this;
    }

    public Coefficients pow10Local() {
        for (int i = 0; i < data.length; i++) data[i] = Definitions.pow10(data[i]);
        return this;
    }

    public Coefficients sqrtLocal() {
        for (int i = 0; i < data.length; i++) data[i] = Math.sqrt(data[i]);
        return this;
    }

    public Coefficients setAll(double x) { Arrays.fill(data, x); return this; }
    public Coefficients negateLocal() { for (int i = 0; i < data.length; i++) data[i] = -data[i]; return this; }

    public Coefficients addLocal(Coefficients v) { for (int i = 0; i < data.length; i++) data[i] += v.data[i]; return this; }
    public Coefficients subLocal(Coefficients v) { for (int i = 0; i < data.length; i++) data[i] -= v.data[i]; return this; }
    public Coefficients mulLocal(Coefficients v) { for (int i = 0; i < data.length; i++) data[i] *= v.data[i]; return this; }
    public Coefficients divLocal(Coefficients v) { for (int i = 0; i < data.length; i++) data[i] /= v.data[i]; return this; }

    public Coefficients addLocal(double a) { for (int i = 0; i < data.length; i++) data[i] += a; return this; }
    public Coefficients mulLocal(double a) { for (int i = 0; i < data.length; i++) data[i] *= a; return this; }
    public Coefficients divLocal(double a) { return mulLocal(1.0 / a); }

    public boolean allLessThan(double a) { for (double v : data) if (v >= a) return false; return true; }
    public boolean allGreaterThan(double a) { for (double v : data) if (v <= a) return false; return true; }
    public boolean allLessOrEqual(double a) { for (double v : data) if (v > a) return false; return true; }
    public boolean allGreaterOrEqual(double a) { for (double v : data) if (v < a) return false; return true; }

    // Static operations
    public static Coefficients add(Coefficients u, Coefficients v) { return new Coefficients(u).addLocal(v); }
    public static Coefficients sub(Coefficients u, Coefficients v) { return new Coefficients(u).subLocal(v); }
    public static Coefficients mul(Coefficients u, Coefficients v) { return new Coefficients(u).mulLocal(v); }
    public static Coefficients div(Coefficients u, Coefficients v) { return new Coefficients(u).divLocal(v); }

    public static Coefficients add(Coefficients v, double a) { return new Coefficients(v).addLocal(a); }
    public static Coefficients sub(Coefficients v, double a) { return new Coefficients(v).addLocal(-a); }
    public static Coefficients mul(Coefficients v, double a) { return new Coefficients(v).mulLocal(a); }
    public static Coefficients mul(double a, Coefficients v) { return mul(v, a); }
    public static Coefficients div(Coefficients v, double a) { return new Coefficients(v).mulLocal(1.0 / a); }
    public static Coefficients div(double a, Coefficients v) {
        Coefficients r = new Coefficients(v.length(), a);
        r.divLocal(v);
        return r;
    }

    public static Coefficients sin(Coefficients v) {
        Coefficients r = new Coefficients(v);
        for (int i = 0; i < r.data.length; i++) r.data[i] = Math.sin(r.data[i]);
        return r;
    }

    public static Coefficients cos(Coefficients v) {
        Coefficients r = new Coefficients(v);
        for (int i = 0; i < r.data.length; i++) r.data[i] = Math.cos(r.data[i]);
        return r;
    }

    public static Coefficients abs(Coefficients v) {
        Coefficients r = new Coefficients(v);
        for (int i = 0; i < r.data.length; i++) r.data[i] = Math.abs(r.data[i]);
        return r;
    }

    public static double sum(Coefficients v) {
        double s = 0.0;
        for (double d : v.data) s += d;
        return s;
    }

    public static Coefficients pow(Coefficients v, double x) {
        Coefficients r = new Coefficients(v);
        for (int i = 0; i < r.data.length; i++) r.data[i] = Math.pow(r.data[i], x);
        return r;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Coefficients c)) return false;
        return Arrays.equals(data, c.data);
    }

    @Override
    public int hashCode() { return Arrays.hashCode(data); }

    @Override
    public String toString() {
        if (data.length == 0) return "[ ]";
        StringBuilder sb = new StringBuilder("[ ").append(data[0]);
        for (int i = 1; i < data.length; i++) sb.append(", ").append(data[i]);
        return sb.append(" ]").toString();
    }

    /** Returns a copy of the underlying data array. */
    public double[] toArray() { return Arrays.copyOf(data, data.length); }
}
