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
}
