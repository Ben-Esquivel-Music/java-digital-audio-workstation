package com.benesquivelmusic.daw.acoustics.spatialiser;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Complex;
import com.benesquivelmusic.daw.acoustics.common.Definitions;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores directivity as spherical harmonics for given frequency bands.
 * Ported from RoomAcoustiCpp {@code Directivity}.
 */
public final class Directivity {

    private final List<List<Complex>> coefficients;
    private final List<Double> invDirectivityFactor;
    private final List<Double> fm;
    private List<Double> omniResponse;

    public Directivity(List<Double> fc, List<List<Complex>> coefficients, List<Double> invDirectivityFactor) {
        this.coefficients = coefficients;
        this.invDirectivityFactor = invDirectivityFactor;
        this.fm = new ArrayList<>();
        for (int i = 0; i < fc.size() - 1; i++)
            fm.add(fc.get(i) * Math.sqrt(fc.get(i + 1) / fc.get(i)));
        calculateOmniResponse();
    }

    /** Calculate the directivity response for given frequencies and direction. */
    public Coefficients calculateResponse(Coefficients frequencies, double theta, double phi) {
        Coefficients response = new Coefficients(frequencies.length());
        for (int i = 0; i < frequencies.length(); i++) {
            int bandIdx = findBand(frequencies.get(i));
            double mag = evaluateSH(bandIdx, theta, phi);
            response.set(i, mag);
        }
        return response;
    }

    /** Calculate the inverse directivity factor for given frequencies. */
    public Coefficients calculateInverseResponse(Coefficients frequencies) {
        Coefficients response = new Coefficients(frequencies.length());
        for (int i = 0; i < frequencies.length(); i++) {
            int bandIdx = findBand(frequencies.get(i));
            response.set(i, bandIdx < invDirectivityFactor.size() ? invDirectivityFactor.get(bandIdx) : 1.0);
        }
        return response;
    }

    private int findBand(double frequency) {
        for (int i = 0; i < fm.size(); i++)
            if (frequency < fm.get(i)) return i;
        return fm.size();
    }

    private double evaluateSH(int bandIdx, double theta, double phi) {
        if (bandIdx >= coefficients.size()) return 1.0;
        List<Complex> coeffs = coefficients.get(bandIdx);
        int maxOrder = (int) Math.sqrt(coeffs.size()) - 1;
        Complex output = coeffs.getFirst();
        for (int l = 1; l <= maxOrder; l++) {
            for (int m = -l; m <= l; m++) {
                int idx = l * l + l + m;
                if (idx >= coeffs.size()) break;
                Complex y = complexSphericalHarmonic(l, m, theta, phi);
                output = output.add(coeffs.get(idx).mul(y));
            }
        }
        return output.modulus();
    }

    /** Complex spherical harmonic Y_l^m(theta, phi). Matches C++ SphericalHarmonic. */
    private static Complex complexSphericalHarmonic(int l, int m, double theta, double phi) {
        boolean isNegative = m < 0;
        boolean isOdd = m % 2 != 0;
        m = Math.abs(m);
        double plm = Definitions.normalisedSHLegendrePlm(l, m, Math.cos(theta));
        Complex e = Complex.fromPolar(1.0, m * phi);
        if (isNegative) {
            e = new Complex(e.real(), -e.imag()); // conjugate
            if (isOdd) e = new Complex(-e.real(), -e.imag()); // negate
        }
        return e.mul(plm);
    }

    private void calculateOmniResponse() {
        Complex y00 = complexSphericalHarmonic(0, 0, 0.0, 0.0);
        omniResponse = new ArrayList<>();
        for (int i = 0; i < coefficients.size(); i++) {
            if (!coefficients.get(i).isEmpty()) {
                Complex scaled = coefficients.get(i).getFirst().mul(y00);
                coefficients.get(i).set(0, scaled);
                omniResponse.add(scaled.modulus());
            } else {
                omniResponse.add(1.0);
            }
        }
    }
}
