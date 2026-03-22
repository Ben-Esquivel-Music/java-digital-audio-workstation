package com.benesquivelmusic.daw.core.analysis;

import java.util.Random;

/**
 * Comprehensive test signal generator for system calibration, measurement,
 * and plugin testing.
 *
 * <p>Generates the standard set of test stimuli used in professional audio
 * engineering: sine sweeps, white and pink noise, impulse responses,
 * multi-tone stimuli, and calibrated silence. All signals are returned as
 * {@code float[]} (mono) or {@code float[][]} (stereo) at a configurable
 * sample rate.</p>
 *
 * <h3>Signal Types</h3>
 * <ul>
 *   <li><b>Sine sweep</b> — logarithmic or linear, with configurable start/end
 *       frequency, duration, and fade-in/out. Logarithmic sweeps provide
 *       constant energy per octave and are the preferred excitation stimulus
 *       for room response measurement.</li>
 *   <li><b>White noise</b> — flat power spectral density across all
 *       frequencies.</li>
 *   <li><b>Pink noise</b> — 1/f spectral slope using the Voss–McCartney
 *       algorithm (pure Java, no lookup tables).</li>
 *   <li><b>Impulse</b> — single-sample Dirac impulse at a configurable
 *       position, or a windowed (Hann) impulse of configurable width.</li>
 *   <li><b>Multi-tone</b> — configurable set of simultaneous sinusoids at
 *       specified frequencies and amplitudes.</li>
 *   <li><b>Silence</b> — calibrated digital silence for noise-floor
 *       measurement.</li>
 * </ul>
 *
 * <h3>AES Research References</h3>
 * <ul>
 *   <li>"A New Electronic Audio Sweep-Frequency Generator" (1949) —
 *       foundational reference for logarithmic and linear frequency sweep
 *       design.</li>
 *   <li>"Excitation Stimuli For Simultaneous Deconvolution of Room Responses"
 *       (2023) — recommends exponential sine sweeps for best SNR and
 *       distortion separation.</li>
 *   <li>"Use of Repetitive Multi-Tone Sequences to Estimate Nonlinear
 *       Response of a Loudspeaker to Music" (2017) — multi-tone test signals
 *       for nonlinear system characterization.</li>
 * </ul>
 */
public final class TestSignalGenerator {

    private TestSignalGenerator() {
        // utility class
    }

    // ---------------------------------------------------------------
    // Sine sweep
    // ---------------------------------------------------------------

    /**
     * Generates a logarithmic (exponential) sine sweep.
     *
     * <p>The instantaneous frequency increases exponentially from
     * {@code startFrequency} to {@code endFrequency} over the given
     * duration, providing constant energy per octave — the preferred
     * excitation stimulus for room and system impulse response measurement.</p>
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @param startFrequency start frequency in Hz (must be positive and less than endFrequency)
     * @param endFrequency   end frequency in Hz (must be positive and less than or equal to Nyquist)
     * @param fadeSeconds    fade-in and fade-out duration in seconds (must be non-negative)
     * @return a mono {@code float[]} containing the sweep signal
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static float[] logarithmicSweep(double sampleRate, double durationSeconds,
                                           double startFrequency, double endFrequency,
                                           double fadeSeconds) {
        validateSweepParameters(sampleRate, durationSeconds, startFrequency, endFrequency, fadeSeconds);

        int totalSamples = (int) (sampleRate * durationSeconds);
        float[] output = new float[totalSamples];

        double logRatio = Math.log(endFrequency / startFrequency);

        for (int i = 0; i < totalSamples; i++) {
            double t = i / sampleRate;
            double phase = 2.0 * Math.PI * startFrequency * durationSeconds
                    * (Math.exp(t * logRatio / durationSeconds) - 1.0) / logRatio;
            output[i] = (float) Math.sin(phase);
        }

        applyFades(output, sampleRate, fadeSeconds);
        return output;
    }

    /**
     * Generates a logarithmic sine sweep with no fade.
     *
     * @param sampleRate     the sample rate in Hz
     * @param durationSeconds duration in seconds
     * @param startFrequency start frequency in Hz
     * @param endFrequency   end frequency in Hz
     * @return a mono {@code float[]} containing the sweep signal
     */
    public static float[] logarithmicSweep(double sampleRate, double durationSeconds,
                                           double startFrequency, double endFrequency) {
        return logarithmicSweep(sampleRate, durationSeconds, startFrequency, endFrequency, 0.0);
    }

    /**
     * Generates a linear sine sweep.
     *
     * <p>The instantaneous frequency increases linearly from
     * {@code startFrequency} to {@code endFrequency} over the given
     * duration.</p>
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @param startFrequency start frequency in Hz (must be positive and less than endFrequency)
     * @param endFrequency   end frequency in Hz (must be positive and less than or equal to Nyquist)
     * @param fadeSeconds    fade-in and fade-out duration in seconds (must be non-negative)
     * @return a mono {@code float[]} containing the sweep signal
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static float[] linearSweep(double sampleRate, double durationSeconds,
                                      double startFrequency, double endFrequency,
                                      double fadeSeconds) {
        validateSweepParameters(sampleRate, durationSeconds, startFrequency, endFrequency, fadeSeconds);

        int totalSamples = (int) (sampleRate * durationSeconds);
        float[] output = new float[totalSamples];

        double freqSlope = (endFrequency - startFrequency) / durationSeconds;

        for (int i = 0; i < totalSamples; i++) {
            double t = i / sampleRate;
            double phase = 2.0 * Math.PI * (startFrequency * t + 0.5 * freqSlope * t * t);
            output[i] = (float) Math.sin(phase);
        }

        applyFades(output, sampleRate, fadeSeconds);
        return output;
    }

    /**
     * Generates a linear sine sweep with no fade.
     *
     * @param sampleRate     the sample rate in Hz
     * @param durationSeconds duration in seconds
     * @param startFrequency start frequency in Hz
     * @param endFrequency   end frequency in Hz
     * @return a mono {@code float[]} containing the sweep signal
     */
    public static float[] linearSweep(double sampleRate, double durationSeconds,
                                      double startFrequency, double endFrequency) {
        return linearSweep(sampleRate, durationSeconds, startFrequency, endFrequency, 0.0);
    }

    // ---------------------------------------------------------------
    // White noise
    // ---------------------------------------------------------------

    /**
     * Generates white noise with flat power spectral density.
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @return a mono {@code float[]} of uniformly distributed white noise in [-1, 1]
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static float[] whiteNoise(double sampleRate, double durationSeconds) {
        validateBasicParameters(sampleRate, durationSeconds);

        int totalSamples = (int) (sampleRate * durationSeconds);
        float[] output = new float[totalSamples];
        Random random = new Random(0);

        for (int i = 0; i < totalSamples; i++) {
            output[i] = (float) (random.nextDouble() * 2.0 - 1.0);
        }
        return output;
    }

    /**
     * Generates white noise with a specified random seed.
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @param seed           the random seed for reproducible output
     * @return a mono {@code float[]} of uniformly distributed white noise in [-1, 1]
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static float[] whiteNoise(double sampleRate, double durationSeconds, long seed) {
        validateBasicParameters(sampleRate, durationSeconds);

        int totalSamples = (int) (sampleRate * durationSeconds);
        float[] output = new float[totalSamples];
        Random random = new Random(seed);

        for (int i = 0; i < totalSamples; i++) {
            output[i] = (float) (random.nextDouble() * 2.0 - 1.0);
        }
        return output;
    }

    // ---------------------------------------------------------------
    // Pink noise (Voss–McCartney algorithm)
    // ---------------------------------------------------------------

    /**
     * Number of octave rows used by the Voss–McCartney pink noise generator.
     * 16 rows provide good spectral accuracy down to very low frequencies.
     */
    private static final int PINK_NOISE_ROWS = 16;

    /**
     * Generates pink noise (1/f spectrum) using the Voss–McCartney algorithm.
     *
     * <p>The Voss–McCartney method maintains a bank of independent white-noise
     * sources updated at octave-spaced rates. Summing them produces a signal
     * whose power spectral density falls at approximately 3 dB per octave
     * (−10 dB/decade), the defining characteristic of pink noise.</p>
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @return a mono {@code float[]} of pink noise normalized to approximately [-1, 1]
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static float[] pinkNoise(double sampleRate, double durationSeconds) {
        return pinkNoise(sampleRate, durationSeconds, 0);
    }

    /**
     * Generates pink noise with a specified random seed.
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @param seed           the random seed for reproducible output
     * @return a mono {@code float[]} of pink noise normalized to approximately [-1, 1]
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static float[] pinkNoise(double sampleRate, double durationSeconds, long seed) {
        validateBasicParameters(sampleRate, durationSeconds);

        int totalSamples = (int) (sampleRate * durationSeconds);
        float[] output = new float[totalSamples];
        Random random = new Random(seed);

        double[] rows = new double[PINK_NOISE_ROWS];
        double runningSum = 0.0;

        // Initialize rows
        for (int r = 0; r < PINK_NOISE_ROWS; r++) {
            rows[r] = random.nextDouble() * 2.0 - 1.0;
            runningSum += rows[r];
        }

        double maxAbsValue = 0.0;

        for (int i = 0; i < totalSamples; i++) {
            // Determine which rows to update: row r is updated when bit r
            // transitions from 0 to 1 (i.e., when the counter's bit r changes).
            int changed = (i > 0) ? (i ^ (i - 1)) : 0;
            for (int r = 0; r < PINK_NOISE_ROWS; r++) {
                if ((changed & (1 << r)) != 0) {
                    runningSum -= rows[r];
                    rows[r] = random.nextDouble() * 2.0 - 1.0;
                    runningSum += rows[r];
                }
            }

            // Add a white noise source for the highest octave
            double whiteComponent = random.nextDouble() * 2.0 - 1.0;
            double sample = (runningSum + whiteComponent) / (PINK_NOISE_ROWS + 1);
            output[i] = (float) sample;

            double absVal = Math.abs(sample);
            if (absVal > maxAbsValue) {
                maxAbsValue = absVal;
            }
        }

        // Normalize to [-1, 1]
        if (maxAbsValue > 0.0) {
            float normFactor = (float) (1.0 / maxAbsValue);
            for (int i = 0; i < totalSamples; i++) {
                output[i] *= normFactor;
            }
        }

        return output;
    }

    // ---------------------------------------------------------------
    // Impulse
    // ---------------------------------------------------------------

    /**
     * Generates a single-sample Dirac impulse at the specified position.
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @param impulseSample  the sample index at which the impulse occurs
     *                       (must be within [0, totalSamples))
     * @return a mono {@code float[]} containing a unit impulse
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static float[] impulse(double sampleRate, double durationSeconds, int impulseSample) {
        validateBasicParameters(sampleRate, durationSeconds);

        int totalSamples = (int) (sampleRate * durationSeconds);
        if (impulseSample < 0 || impulseSample >= totalSamples) {
            throw new IllegalArgumentException(
                    "impulseSample must be in [0, " + (totalSamples - 1) + "]: " + impulseSample);
        }

        float[] output = new float[totalSamples];
        output[impulseSample] = 1.0f;
        return output;
    }

    /**
     * Generates a single-sample Dirac impulse at sample index 0.
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @return a mono {@code float[]} containing a unit impulse at the start
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static float[] impulse(double sampleRate, double durationSeconds) {
        return impulse(sampleRate, durationSeconds, 0);
    }

    /**
     * Generates a windowed (Hann) impulse centered at the specified position.
     *
     * <p>The impulse is shaped by a Hann window of the given width, providing
     * a band-limited approximation of a Dirac impulse that avoids spectral
     * leakage in analysis applications.</p>
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @param centerSample   the center sample index of the windowed impulse
     * @param windowWidth    the total width of the Hann window in samples (must be positive and odd)
     * @return a mono {@code float[]} containing the windowed impulse
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static float[] windowedImpulse(double sampleRate, double durationSeconds,
                                          int centerSample, int windowWidth) {
        validateBasicParameters(sampleRate, durationSeconds);

        int totalSamples = (int) (sampleRate * durationSeconds);
        if (centerSample < 0 || centerSample >= totalSamples) {
            throw new IllegalArgumentException(
                    "centerSample must be in [0, " + (totalSamples - 1) + "]: " + centerSample);
        }
        if (windowWidth <= 0) {
            throw new IllegalArgumentException("windowWidth must be positive: " + windowWidth);
        }

        float[] output = new float[totalSamples];
        int halfWidth = windowWidth / 2;
        int start = Math.max(0, centerSample - halfWidth);
        int end = Math.min(totalSamples, centerSample + halfWidth + 1);

        for (int i = start; i < end; i++) {
            int windowIndex = i - (centerSample - halfWidth);
            double hannValue = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * windowIndex / (windowWidth - 1)));
            output[i] = (float) hannValue;
        }

        return output;
    }

    // ---------------------------------------------------------------
    // Multi-tone
    // ---------------------------------------------------------------

    /**
     * Generates a multi-tone signal: a sum of simultaneous sinusoids at the
     * specified frequencies and amplitudes.
     *
     * <p>Multi-tone signals are used for nonlinear system characterization
     * and intermodulation distortion measurement.</p>
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @param frequencies    array of tone frequencies in Hz (must be non-empty;
     *                       each must be positive and ≤ Nyquist)
     * @param amplitudes     array of per-tone linear amplitudes (same length as
     *                       {@code frequencies}; each must be non-negative)
     * @return a mono {@code float[]} containing the summed tones
     * @throws IllegalArgumentException if any parameter is out of range or
     *                                  arrays have different lengths
     */
    public static float[] multiTone(double sampleRate, double durationSeconds,
                                    double[] frequencies, double[] amplitudes) {
        validateBasicParameters(sampleRate, durationSeconds);
        if (frequencies == null || frequencies.length == 0) {
            throw new IllegalArgumentException("frequencies must be non-null and non-empty");
        }
        if (amplitudes == null || amplitudes.length != frequencies.length) {
            throw new IllegalArgumentException(
                    "amplitudes must be non-null and have the same length as frequencies");
        }

        double nyquist = sampleRate / 2.0;
        for (int t = 0; t < frequencies.length; t++) {
            if (frequencies[t] <= 0 || frequencies[t] > nyquist) {
                throw new IllegalArgumentException(
                        "frequency must be in (0, " + nyquist + "]: " + frequencies[t]);
            }
            if (amplitudes[t] < 0) {
                throw new IllegalArgumentException(
                        "amplitude must be non-negative: " + amplitudes[t]);
            }
        }

        int totalSamples = (int) (sampleRate * durationSeconds);
        float[] output = new float[totalSamples];

        for (int i = 0; i < totalSamples; i++) {
            double sample = 0.0;
            for (int t = 0; t < frequencies.length; t++) {
                sample += amplitudes[t] * Math.sin(2.0 * Math.PI * frequencies[t] * i / sampleRate);
            }
            output[i] = (float) sample;
        }

        return output;
    }

    /**
     * Generates a multi-tone signal with equal amplitude for all tones.
     *
     * <p>The amplitude of each tone is set to {@code 1.0 / frequencies.length}
     * so that the peak of the summed signal stays near unity.</p>
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @param frequencies    array of tone frequencies in Hz
     * @return a mono {@code float[]} containing the summed tones
     */
    public static float[] multiTone(double sampleRate, double durationSeconds,
                                    double[] frequencies) {
        if (frequencies == null || frequencies.length == 0) {
            throw new IllegalArgumentException("frequencies must be non-null and non-empty");
        }
        double[] amplitudes = new double[frequencies.length];
        double amp = 1.0 / frequencies.length;
        for (int i = 0; i < amplitudes.length; i++) {
            amplitudes[i] = amp;
        }
        return multiTone(sampleRate, durationSeconds, frequencies, amplitudes);
    }

    // ---------------------------------------------------------------
    // Silence
    // ---------------------------------------------------------------

    /**
     * Generates calibrated digital silence for noise-floor measurement.
     *
     * @param sampleRate     the sample rate in Hz (must be positive)
     * @param durationSeconds duration in seconds (must be positive)
     * @return a mono {@code float[]} of zeros
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public static float[] silence(double sampleRate, double durationSeconds) {
        validateBasicParameters(sampleRate, durationSeconds);

        int totalSamples = (int) (sampleRate * durationSeconds);
        return new float[totalSamples];
    }

    // ---------------------------------------------------------------
    // Stereo variants
    // ---------------------------------------------------------------

    /**
     * Duplicates a mono signal into a stereo (2-channel) signal.
     *
     * @param mono the mono signal
     * @return a {@code float[2][]} where both channels contain identical copies
     *         of the input
     * @throws IllegalArgumentException if {@code mono} is null
     */
    public static float[][] toStereo(float[] mono) {
        if (mono == null) {
            throw new IllegalArgumentException("mono signal must not be null");
        }
        float[] left = new float[mono.length];
        float[] right = new float[mono.length];
        System.arraycopy(mono, 0, left, 0, mono.length);
        System.arraycopy(mono, 0, right, 0, mono.length);
        return new float[][]{left, right};
    }

    // ---------------------------------------------------------------
    // Validation helpers
    // ---------------------------------------------------------------

    private static void validateBasicParameters(double sampleRate, double durationSeconds) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("durationSeconds must be positive: " + durationSeconds);
        }
    }

    private static void validateSweepParameters(double sampleRate, double durationSeconds,
                                                double startFrequency, double endFrequency,
                                                double fadeSeconds) {
        validateBasicParameters(sampleRate, durationSeconds);
        if (startFrequency <= 0) {
            throw new IllegalArgumentException(
                    "startFrequency must be positive: " + startFrequency);
        }
        if (endFrequency <= 0) {
            throw new IllegalArgumentException(
                    "endFrequency must be positive: " + endFrequency);
        }
        if (startFrequency >= endFrequency) {
            throw new IllegalArgumentException(
                    "startFrequency must be less than endFrequency: "
                            + startFrequency + " >= " + endFrequency);
        }
        double nyquist = sampleRate / 2.0;
        if (endFrequency > nyquist) {
            throw new IllegalArgumentException(
                    "endFrequency must not exceed Nyquist frequency ("
                            + nyquist + "): " + endFrequency);
        }
        if (fadeSeconds < 0) {
            throw new IllegalArgumentException(
                    "fadeSeconds must be non-negative: " + fadeSeconds);
        }
        if (fadeSeconds * 2 > durationSeconds) {
            throw new IllegalArgumentException(
                    "total fade time (2 × " + fadeSeconds + ") exceeds duration (" + durationSeconds + ")");
        }
    }

    /**
     * Applies equal-power (raised-cosine) fade-in and fade-out to a signal.
     */
    private static void applyFades(float[] signal, double sampleRate, double fadeSeconds) {
        if (fadeSeconds <= 0) {
            return;
        }
        int fadeSamples = (int) (sampleRate * fadeSeconds);
        fadeSamples = Math.min(fadeSamples, signal.length / 2);

        for (int i = 0; i < fadeSamples; i++) {
            double gain = 0.5 * (1.0 - Math.cos(Math.PI * i / fadeSamples));
            signal[i] *= (float) gain;
            signal[signal.length - 1 - i] *= (float) gain;
        }
    }
}
