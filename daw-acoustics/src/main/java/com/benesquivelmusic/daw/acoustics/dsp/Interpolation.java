package com.benesquivelmusic.daw.acoustics.dsp;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Definitions;

/**
 * Linear interpolation and approximate-equality utilities.
 * Ported from RoomAcoustiCpp {@code Interpolate.h}.
 */
public final class Interpolation {

    private Interpolation() {}

    /** Linearly interpolates between two scalar values. */
    public static double lerp(double start, double end, double factor) {
        return start * (1.0 - factor) + end * factor;
    }

    /** Linearly interpolates start buffer towards end buffer.
     *  If start is longer than end, remaining samples are faded towards zero. */
    public static void lerp(Buffer start, Buffer end, int startLength, double factor) {
        int len = end.length();
        for (int i = 0; i < len; i++) {
            start.set(i, start.get(i) * (1.0 - factor) + factor * end.get(i));
        }
        for (int i = len; i < startLength; i++) {
            start.set(i, start.get(i) * (1.0 - factor));
        }
    }

    /** Linearly interpolates coefficient arrays in place. */
    public static void lerp(Coefficients start, Coefficients end, double factor) {
        start.mulLocal(1.0 - factor);
        Coefficients scaled = Coefficients.mul(end, factor);
        start.addLocal(scaled);
    }

    /** Approximate equality of two doubles. */
    public static boolean equals(double a, double b) { return equals(a, b, Definitions.EPS); }

    public static boolean equals(double a, double b, double threshold) {
        return !(a > b + threshold || a < b - threshold);
    }

    /** Approximate equality of two Coefficients arrays. */
    public static boolean equals(Coefficients u, Coefficients v) { return equals(u, v, Definitions.EPS); }

    public static boolean equals(Coefficients u, Coefficients v, double threshold) {
        if (u.length() != v.length()) return false;
        for (int i = 0; i < u.length(); i++)
            if (u.get(i) > v.get(i) + threshold || u.get(i) < v.get(i) - threshold) return false;
        return true;
    }

    /** Approximate equality of two Buffers up to a given length. */
    public static boolean equals(Buffer u, Buffer v, int length) { return equals(u, v, length, Definitions.EPS); }

    public static boolean equals(Buffer u, Buffer v, int length, double threshold) {
        for (int i = 0; i < length; i++)
            if (u.get(i) > v.get(i) + threshold || u.get(i) < v.get(i) - threshold) return false;
        for (int i = length; i < u.length(); i++)
            if (u.get(i) > threshold || u.get(i) < -threshold) return false;
        return true;
    }
}
