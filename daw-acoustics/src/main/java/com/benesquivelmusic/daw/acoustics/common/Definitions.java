package com.benesquivelmusic.daw.acoustics.common;

/**
 * Constants and mathematical utility functions ported from RoomAcoustiCpp.
 * Uses {@code double} precision throughout (equivalent to C++ {@code DATA_TYPE_DOUBLE}).
 */
public final class Definitions {

    private Definitions() {}

    public static final int MAX_IMAGESOURCES = 1024;
    public static final int MAX_SOURCES = 128;

    public static final double T_CELSIUS = 20.0;
    public static final double SPEED_OF_SOUND = 331.5 + 0.6 * T_CELSIUS;
    public static final double INV_SPEED_OF_SOUND = 1.0 / SPEED_OF_SOUND;

    public static final double ROUND_FACTOR = 1e3;
    public static final double EPS = 0.000001;
    public static final double MIN_VALUE = 10.0 * Double.MIN_VALUE;

    // Mathematical constants
    public static final double PI_1 = Math.PI;
    public static final double PI_2 = 2.0 * PI_1;
    public static final double PI_4 = 4.0 * PI_1;
    public static final double PI_8 = 8.0 * PI_1;

    public static final double SQRT_2 = Math.sqrt(2.0);
    public static final double SQRT_3 = Math.sqrt(3.0);
    public static final double SQRT_6 = Math.sqrt(6.0);
    public static final double INV_SQRT_2 = 1.0 / SQRT_2;
    public static final double INV_SQRT_3 = 1.0 / SQRT_3;
    public static final double INV_SQRT_6 = 1.0 / SQRT_6;

    public static final double LOG_10 = Math.log(10.0);
    public static final double LOG2_10 = Math.log(10.0) / Math.log(2.0);
    public static final double INV_LOG2_10 = 1.0 / LOG2_10;

    public static final double PI_EPS = PI_1 + EPS;
    public static final double PI_SQ = PI_1 * PI_1;

    public static double deg2Rad(double x) { return Math.toRadians(x); }

    public static double rad2Deg(double x) { return Math.toDegrees(x); }

    public static double pow10(double x) { return Math.exp(LOG_10 * x); }

    public static double log10(double x) { return Math.log10(x); }

    public static double cot(double x) { return Math.cos(x) / Math.sin(x); }

    public static double safeAcos(double x) {
        return Math.acos(Math.max(-1.0, Math.min(1.0, x)));
    }

    public static double sign(double x) { return (x > 0 ? 1.0 : 0.0) - (x < 0 ? 1.0 : 0.0); }

    public static double round(double x) { return Math.round(x * ROUND_FACTOR) / ROUND_FACTOR; }

    public static double round(double x, int dp) {
        double factor = pow10(dp);
        return Math.round(x * factor) / factor;
    }

    public static double factorial(int n) {
        if (n <= 1) return 1.0;
        double result = 1.0;
        for (int i = 2; i <= n; i++) result *= i;
        return result;
    }

    public static double doubleFactorial(int n) {
        if (n <= 0) return 1.0;
        double result = 1.0;
        for (int i = n; i > 1; i -= 2) result *= i;
        return result;
    }

    public static double legendrePolynomial(int l, int m, double x) {
        if (l == 0 && m == 0) return 1.0;
        if (l == m) {
            double fac = (m % 2 == 0 ? 1 : -1) * doubleFactorial(2 * m - 1);
            return fac * Math.pow(1.0 - x * x, m / 2.0);
        }
        if (l == m + 1) return x * (2 * m + 1) * legendrePolynomial(m, m, x);
        double pLm1 = legendrePolynomial(l - 1, m, x);
        double pLm2 = legendrePolynomial(l - 2, m, x);
        return ((2.0 * l - 1) * x * pLm1 - (l + m - 1) * pLm2) / (l - m);
    }

    public static double normalisedSHLegendrePlm(int l, int m, double x) {
        if (m > l) return 0.0;
        double plm = legendrePolynomial(l, m, x);
        double norm = Math.sqrt((2.0 * l + 1.0) / PI_4 * factorial(l - m) / factorial(l + m));
        return norm * plm;
    }
}
