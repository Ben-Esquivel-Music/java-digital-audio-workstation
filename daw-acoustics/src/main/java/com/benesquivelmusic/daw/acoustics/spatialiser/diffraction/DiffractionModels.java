package com.benesquivelmusic.daw.acoustics.spatialiser.diffraction;

import com.benesquivelmusic.daw.acoustics.common.Coefficients;
import com.benesquivelmusic.daw.acoustics.common.Definitions;
import com.benesquivelmusic.daw.acoustics.spatialiser.Edge;

/**
 * Diffraction model base class and concrete implementations.
 * Ported from RoomAcoustiCpp diffraction model hierarchy (Attenuate, LPF, UTD, BTM).
 */
public sealed interface DiffractionModels {

    /** Calculate the diffraction coefficient (scalar attenuation). */
    double calculate(DiffractionPath path, Edge edge, double frequency);

    /** Calculate multi-frequency diffraction coefficients. */
    default Coefficients calculate(DiffractionPath path, Edge edge, Coefficients frequencies) {
        Coefficients result = new Coefficients(frequencies.length());
        for (int i = 0; i < frequencies.length(); i++)
            result.set(i, calculate(path, edge, frequencies.get(i)));
        return result;
    }

    /** Simple angle-based attenuation model. */
    record Attenuate() implements DiffractionModels {
        @Override
        public double calculate(DiffractionPath path, Edge edge, double frequency) {
            double theta = edge.getExteriorAngle();
            double phiR = path.getPhiR();
            double phiS = path.getPhiS();
            double angleDiff = Math.abs(phiR - phiS);
            double attenuation = Math.max(0.0, 1.0 - angleDiff / theta);
            return attenuation;
        }
    }

    /** Low-pass filter diffraction model. */
    record LowPassModel() implements DiffractionModels {
        @Override
        public double calculate(DiffractionPath path, Edge edge, double frequency) {
            double theta = edge.getExteriorAngle();
            double phiR = path.getPhiR();
            double phiS = path.getPhiS();
            double angleDiff = Math.abs(phiR - phiS);
            double ratio = Math.max(0.0, 1.0 - angleDiff / theta);
            // Frequency-dependent attenuation: higher frequencies attenuate more
            double freqFactor = 1.0 / (1.0 + frequency / 1000.0 * (1.0 - ratio));
            return ratio * freqFactor;
        }
    }

    /** Uniform Theory of Diffraction model. */
    record UTD() implements DiffractionModels {
        @Override
        public double calculate(DiffractionPath path, Edge edge, double frequency) {
            double n = edge.getExteriorAngle() / Definitions.PI_1;
            if (n <= 0 || n >= 2) return 1.0;

            double rhoS = path.getRhoS();
            double rhoR = path.getRhoR();
            double phiS = path.getPhiS();
            double phiR = path.getPhiR();

            double k = Definitions.PI_2 * frequency / Definitions.SPEED_OF_SOUND;

            double L = rhoS * rhoR / (rhoS + rhoR);
            double sqrtL = Math.sqrt(2.0 * k * L);

            double d = 0;
            d += cotTerm(Definitions.PI_1 + (phiR - phiS), n, sqrtL);
            d += cotTerm(Definitions.PI_1 - (phiR - phiS), n, sqrtL);
            d += cotTerm(Definitions.PI_1 + (phiR + phiS), n, sqrtL);
            d += cotTerm(Definitions.PI_1 - (phiR + phiS), n, sqrtL);

            d *= -1.0 / (n * Definitions.PI_2 * k);
            return Math.min(Math.abs(d), 1.0);
        }

        private double cotTerm(double beta, double n, double sqrtL) {
            double nBeta = beta / (2.0 * n);
            int N = (int) Math.round(nBeta / Definitions.PI_1);
            double a = 2.0 * Math.cos(2.0 * n * Definitions.PI_1 * N - beta);
            a = Math.max(a, 0.0);
            double fresnelArg = sqrtL * Math.sqrt(a);
            double fresnelApprox = fresnelApprox(fresnelArg);
            double cotVal = Definitions.cot((Definitions.PI_1 * N * 2.0 * n - beta) / (2.0 * n));
            return cotVal * fresnelApprox;
        }

        private double fresnelApprox(double x) {
            if (x < 0.3) return Math.sqrt(Definitions.PI_1) * x;
            if (x < 3.0) return (1.0 - Math.exp(-0.7 * x * x));
            return 1.0;
        }
    }

    /** Biot-Tolstoy-Medwin model. */
    record BTM() implements DiffractionModels {
        @Override
        public double calculate(DiffractionPath path, Edge edge, double frequency) {
            double n = edge.getExteriorAngle() / Definitions.PI_1;
            if (n <= 0 || n >= 2) return 1.0;

            double rhoS = path.getRhoS();
            double rhoR = path.getRhoR();
            double phiS = path.getPhiS();
            double phiR = path.getPhiR();

            // BTM approximation: frequency-dependent
            double k = Definitions.PI_2 * frequency / Definitions.SPEED_OF_SOUND;
            double m = rhoS + rhoR;
            double l = Math.sqrt(rhoS * rhoS + rhoR * rhoR - 2.0 * rhoS * rhoR * Math.cos(phiR - phiS));
            if (l < Definitions.EPS) return 1.0;
            double eta = k * (m - l);
            double attenuation = Math.abs(btmKernel(eta, n, phiS, phiR));
            return Math.min(attenuation, 1.0);
        }

        private double btmKernel(double eta, double n, double phiS, double phiR) {
            double sum = 0;
            for (int sign1 = -1; sign1 <= 1; sign1 += 2) {
                for (int sign2 = -1; sign2 <= 1; sign2 += 2) {
                    double beta = sign1 * phiR + sign2 * phiS;
                    double cosTerm = Math.cos((Definitions.PI_1 - beta) / (2.0 * n));
                    if (Math.abs(cosTerm) > Definitions.EPS) {
                        sum += 1.0 / (n * (1.0 + eta) * cosTerm);
                    }
                }
            }
            return sum / 4.0;
        }
    }
}
