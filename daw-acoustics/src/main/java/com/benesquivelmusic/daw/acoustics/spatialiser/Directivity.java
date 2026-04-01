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
        double result = 0.0;
        int idx = 0;
        for (int l = 0; l <= maxOrder; l++) {
            for (int m = -l; m <= l; m++) {
                if (idx >= coeffs.size()) break;
                Complex c = coeffs.get(idx++);
                double ylm = realSphericalHarmonic(l, m, theta, phi);
                result += c.real() * ylm;
            }
        }
        return Math.max(0.0, result);
    }

    private static double realSphericalHarmonic(int l, int m, double theta, double phi) {
        double plm = Definitions.normalisedSHLegendrePlm(l, Math.abs(m), Math.cos(theta));
        if (m > 0) return Definitions.SQRT_2 * plm * Math.cos(m * phi);
        if (m < 0) return Definitions.SQRT_2 * plm * Math.sin(-m * phi);
        return plm;
    }

    private void calculateOmniResponse() {
        omniResponse = new ArrayList<>();
        for (int i = 0; i < coefficients.size(); i++) {
            if (!coefficients.get(i).isEmpty())
                omniResponse.add(coefficients.get(i).getFirst().real());
            else
                omniResponse.add(1.0);
        }
    }
}
