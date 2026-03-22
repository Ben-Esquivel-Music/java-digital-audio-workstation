package com.benesquivelmusic.daw.core.dsp;

/**
 * Linkwitz-Riley 4th-order (LR4) crossover filter for band splitting.
 *
 * <p>Splits an audio signal into a low-frequency and a high-frequency band
 * at a specified crossover frequency. The two output bands sum to reconstruct
 * the original signal with flat magnitude response and zero phase difference
 * at the crossover point — the defining property of Linkwitz-Riley crossovers.</p>
 *
 * <h2>Implementation</h2>
 * <p>An LR4 crossover is constructed by cascading two 2nd-order Butterworth
 * filters (biquads) in series for each path. The low-pass path uses two
 * cascaded low-pass biquads; the high-pass path uses two cascaded high-pass
 * biquads. At the crossover frequency, each path is at −6 dB, so their
 * sum is 0 dB (unity).</p>
 *
 * <p>This class processes one channel of audio. For multi-channel operation,
 * create one {@code CrossoverFilter} per channel.</p>
 */
public final class CrossoverFilter {

    private final BiquadFilter lowPass1;
    private final BiquadFilter lowPass2;
    private final BiquadFilter highPass1;
    private final BiquadFilter highPass2;

    /**
     * Creates a Linkwitz-Riley 4th-order crossover at the given frequency.
     *
     * @param sampleRate         the audio sample rate in Hz
     * @param crossoverFrequency the crossover frequency in Hz
     * @throws IllegalArgumentException if sampleRate or crossoverFrequency is not positive,
     *                                  or if crossoverFrequency exceeds the Nyquist frequency
     */
    public CrossoverFilter(double sampleRate, double crossoverFrequency) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (crossoverFrequency <= 0) {
            throw new IllegalArgumentException(
                    "crossoverFrequency must be positive: " + crossoverFrequency);
        }
        if (crossoverFrequency >= sampleRate / 2.0) {
            throw new IllegalArgumentException(
                    "crossoverFrequency must be below Nyquist (" + sampleRate / 2.0
                            + " Hz): " + crossoverFrequency);
        }

        // Q = 0.7071 (1/√2) for Butterworth alignment — cascading two of these
        // produces a Linkwitz-Riley 4th-order response.
        double q = Math.sqrt(2.0) / 2.0;

        lowPass1 = BiquadFilter.create(
                BiquadFilter.FilterType.LOW_PASS, sampleRate, crossoverFrequency, q, 0.0);
        lowPass2 = BiquadFilter.create(
                BiquadFilter.FilterType.LOW_PASS, sampleRate, crossoverFrequency, q, 0.0);
        highPass1 = BiquadFilter.create(
                BiquadFilter.FilterType.HIGH_PASS, sampleRate, crossoverFrequency, q, 0.0);
        highPass2 = BiquadFilter.create(
                BiquadFilter.FilterType.HIGH_PASS, sampleRate, crossoverFrequency, q, 0.0);
    }

    /**
     * Processes a single sample through the crossover, producing low and high outputs.
     *
     * @param input  the input sample
     * @param output a two-element array: output[0] receives the low-band sample,
     *               output[1] receives the high-band sample
     */
    public void processSample(float input, float[] output) {
        float low = lowPass2.processSample(lowPass1.processSample(input));
        float high = highPass2.processSample(highPass1.processSample(input));
        output[0] = low;
        output[1] = high;
    }

    /**
     * Processes a buffer of samples, splitting into low-band and high-band outputs.
     *
     * @param input      the input sample buffer
     * @param lowOutput  the low-band output buffer
     * @param highOutput the high-band output buffer
     * @param offset     start offset in all buffers
     * @param length     number of samples to process
     */
    public void process(float[] input, float[] lowOutput, float[] highOutput,
                        int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            float sample = input[i];
            lowOutput[i] = lowPass2.processSample(lowPass1.processSample(sample));
            highOutput[i] = highPass2.processSample(highPass1.processSample(sample));
        }
    }

    /**
     * Resets the internal filter state (delay lines).
     */
    public void reset() {
        lowPass1.reset();
        lowPass2.reset();
        highPass1.reset();
        highPass2.reset();
    }
}
