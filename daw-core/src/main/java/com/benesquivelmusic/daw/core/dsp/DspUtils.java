package com.benesquivelmusic.daw.core.dsp;

/**
 * Shared DSP utility methods used by audio processors.
 *
 * <p>Centralizes common calculations such as envelope coefficient
 * computation to avoid duplication across processor implementations.</p>
 */
final class DspUtils {

    private DspUtils() {
        // utility class
    }

    /**
     * Computes an exponential envelope coefficient for the given time constant.
     *
     * <p>Used by dynamics processors (compressor, limiter, noise gate) for
     * attack and release smoothing. A time constant of zero yields a
     * coefficient of zero (instant response).</p>
     *
     * @param timeMs     the time constant in milliseconds
     * @param sampleRate the audio sample rate in Hz
     * @return the envelope coefficient in [0, 1)
     */
    static double envelopeCoefficient(double timeMs, double sampleRate) {
        return (timeMs > 0)
                ? Math.exp(-1.0 / (timeMs * 0.001 * sampleRate))
                : 0.0;
    }

    /**
     * Reads from a circular delay line with linear interpolation.
     *
     * <p>Used by modulated-delay processors (chorus, Leslie) to produce
     * smooth, artifact-free output when the delay time is fractional.</p>
     *
     * @param buffer       the circular buffer
     * @param writePos     the current write position (the next position to be written)
     * @param delaySamples the fractional delay in samples
     * @param bufferLength the length of the circular buffer
     * @return the linearly interpolated sample at the requested delay
     */
    static float readInterpolated(float[] buffer, int writePos,
                                  double delaySamples, int bufferLength) {
        int delayInt = (int) delaySamples;
        double frac = delaySamples - delayInt;

        int readPos0 = (writePos - delayInt + bufferLength) % bufferLength;
        int readPos1 = (readPos0 - 1 + bufferLength) % bufferLength;

        return (float) (buffer[readPos0] * (1.0 - frac) + buffer[readPos1] * frac);
    }

    /**
     * Designs a half-band FIR lowpass filter using a Kaiser-windowed sinc.
     *
     * <p>Half-band filters are used for efficient 2× oversampling. They have the
     * property that all even-offset coefficients from the center (except the center
     * tap itself) are zero, enabling polyphase implementation where one phase
     * reduces to a simple delay.</p>
     *
     * @param numTaps the number of filter taps (forced to odd if even)
     * @return the filter coefficients
     */
    static double[] designHalfBandCoefficients(int numTaps) {
        if (numTaps < 3) {
            throw new IllegalArgumentException("numTaps must be >= 3: " + numTaps);
        }
        if (numTaps % 2 == 0) {
            numTaps++;
        }

        int center = (numTaps - 1) / 2;
        double[] h = new double[numTaps];

        // Kaiser window parameter for ~90 dB stopband rejection
        double beta = 8.0;
        double i0Beta = besselI0(beta);

        for (int n = 0; n < numTaps; n++) {
            int k = n - center;

            // Ideal half-band lowpass impulse response
            double ideal;
            if (k == 0) {
                ideal = 0.5;
            } else if (k % 2 == 0) {
                ideal = 0.0;
            } else {
                ideal = Math.sin(Math.PI * k / 2.0) / (Math.PI * k);
            }

            // Kaiser window
            double x = 2.0 * n / (numTaps - 1) - 1.0;
            double window = besselI0(beta * Math.sqrt(Math.max(0.0, 1.0 - x * x))) / i0Beta;

            h[n] = ideal * window;
        }

        // Force exact half-band properties
        h[center] = 0.5;
        for (int n = 0; n < numTaps; n++) {
            int k = n - center;
            if (k != 0 && k % 2 == 0) {
                h[n] = 0.0;
            }
        }

        return h;
    }

    /**
     * Computes the zeroth-order modified Bessel function of the first kind (I₀).
     *
     * <p>Used for Kaiser window computation in FIR filter design.</p>
     *
     * @param x the argument
     * @return I₀(x)
     */
    static double besselI0(double x) {
        double sum = 1.0;
        double term = 1.0;
        for (int k = 1; k <= 25; k++) {
            double halfXOverK = x / (2.0 * k);
            term *= halfXOverK * halfXOverK;
            sum += term;
        }
        return sum;
    }
}
