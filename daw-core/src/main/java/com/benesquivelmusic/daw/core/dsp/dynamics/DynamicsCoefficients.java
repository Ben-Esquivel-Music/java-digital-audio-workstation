package com.benesquivelmusic.daw.core.dsp.dynamics;

/**
 * Small package-private utility shared by the {@code dynamics} processors.
 *
 * <p>Mirrors {@code DspUtils.envelopeCoefficient} from the parent {@code dsp}
 * package; duplicated here only because that method is package-private and
 * cannot be reused across package boundaries without widening its visibility.</p>
 */
final class DynamicsCoefficients {

    private DynamicsCoefficients() {
        // utility
    }

    /**
     * Computes an exponential envelope coefficient for the given time constant.
     *
     * @param timeMs     the time constant in milliseconds
     * @param sampleRate the audio sample rate in Hz
     * @return the envelope coefficient in {@code [0, 1)}; zero for {@code timeMs <= 0}
     */
    static double envelope(double timeMs, double sampleRate) {
        return (timeMs > 0)
                ? Math.exp(-1.0 / (timeMs * 0.001 * sampleRate))
                : 0.0;
    }
}
