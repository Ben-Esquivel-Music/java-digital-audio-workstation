package com.benesquivelmusic.daw.acoustics.common;

/**
 * A minimal complex number type for spherical-harmonic directivity calculations.
 * Ported from RoomAcoustiCpp {@code Complex}.
 */
public record Complex(double real, double imag) {

    public double modulus() { return Math.sqrt(real * real + imag * imag); }

    public Complex add(Complex b) { return new Complex(real + b.real, imag + b.imag); }
    public Complex sub(Complex b) { return new Complex(real - b.real, imag - b.imag); }
    public Complex mul(Complex b) {
        return new Complex(real * b.real - imag * b.imag, real * b.imag + imag * b.real);
    }
    public Complex mul(double s) { return new Complex(real * s, imag * s); }

    /** Constructs a complex number from polar form. */
    public static Complex fromPolar(double r, double theta) {
        return new Complex(r * Math.cos(theta), r * Math.sin(theta));
    }
}
