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
}
