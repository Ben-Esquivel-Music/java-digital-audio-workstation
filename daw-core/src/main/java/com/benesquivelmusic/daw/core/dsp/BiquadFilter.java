package com.benesquivelmusic.daw.core.dsp;

/**
 * A single biquad filter stage using the Direct Form II Transposed structure.
 *
 * <p>Provides factory methods for all standard filter types used in
 * professional audio processing: low-pass, high-pass, band-pass,
 * peak/bell EQ, low-shelf, and high-shelf.</p>
 *
 * <p>Coefficients follow the Robert Bristow-Johnson Audio EQ Cookbook
 * formulas — the industry standard for biquad filter design. This is
 * a pure-Java implementation — no JNI required.</p>
 */
public final class BiquadFilter {

    /** Supported biquad filter types. */
    public enum FilterType {
        LOW_PASS, HIGH_PASS, BAND_PASS, PEAK_EQ, LOW_SHELF, HIGH_SHELF, NOTCH
    }

    private double b0, b1, b2, a1, a2;
    private double z1, z2;

    /**
     * Creates a biquad filter with explicit coefficients.
     */
    private BiquadFilter(double b0, double b1, double b2, double a1, double a2) {
        this.b0 = b0;
        this.b1 = b1;
        this.b2 = b2;
        this.a1 = a1;
        this.a2 = a2;
    }

    /**
     * Creates a biquad filter of the specified type.
     *
     * @param type       the filter type
     * @param sampleRate the sample rate in Hz
     * @param frequency  the center/cutoff frequency in Hz
     * @param q          the Q factor (resonance)
     * @param gainDb     gain in dB (only used for PEAK_EQ, LOW_SHELF, HIGH_SHELF)
     * @return a new biquad filter
     */
    public static BiquadFilter create(FilterType type, double sampleRate,
                                      double frequency, double q, double gainDb) {
        double w0 = 2.0 * Math.PI * frequency / sampleRate;
        double cosW0 = Math.cos(w0);
        double sinW0 = Math.sin(w0);
        double alpha = sinW0 / (2.0 * q);
        double A = Math.pow(10.0, gainDb / 40.0);

        double b0, b1, b2, a0, a1, a2;

        switch (type) {
            case LOW_PASS -> {
                b0 = (1.0 - cosW0) / 2.0;
                b1 = 1.0 - cosW0;
                b2 = (1.0 - cosW0) / 2.0;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha;
            }
            case HIGH_PASS -> {
                b0 = (1.0 + cosW0) / 2.0;
                b1 = -(1.0 + cosW0);
                b2 = (1.0 + cosW0) / 2.0;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha;
            }
            case BAND_PASS -> {
                b0 = alpha;
                b1 = 0.0;
                b2 = -alpha;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha;
            }
            case PEAK_EQ -> {
                b0 = 1.0 + alpha * A;
                b1 = -2.0 * cosW0;
                b2 = 1.0 - alpha * A;
                a0 = 1.0 + alpha / A;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha / A;
            }
            case LOW_SHELF -> {
                double sqrtA2alpha = 2.0 * Math.sqrt(A) * alpha;
                b0 = A * ((A + 1) - (A - 1) * cosW0 + sqrtA2alpha);
                b1 = 2.0 * A * ((A - 1) - (A + 1) * cosW0);
                b2 = A * ((A + 1) - (A - 1) * cosW0 - sqrtA2alpha);
                a0 = (A + 1) + (A - 1) * cosW0 + sqrtA2alpha;
                a1 = -2.0 * ((A - 1) + (A + 1) * cosW0);
                a2 = (A + 1) + (A - 1) * cosW0 - sqrtA2alpha;
            }
            case HIGH_SHELF -> {
                double sqrtA2alpha = 2.0 * Math.sqrt(A) * alpha;
                b0 = A * ((A + 1) + (A - 1) * cosW0 + sqrtA2alpha);
                b1 = -2.0 * A * ((A - 1) + (A + 1) * cosW0);
                b2 = A * ((A + 1) + (A - 1) * cosW0 - sqrtA2alpha);
                a0 = (A + 1) - (A - 1) * cosW0 + sqrtA2alpha;
                a1 = 2.0 * ((A - 1) - (A + 1) * cosW0);
                a2 = (A + 1) - (A - 1) * cosW0 - sqrtA2alpha;
            }
            case NOTCH -> {
                b0 = 1.0;
                b1 = -2.0 * cosW0;
                b2 = 1.0;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha;
            }
            default -> throw new IllegalArgumentException("Unknown filter type: " + type);
        }

        // Normalize by a0
        return new BiquadFilter(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    /**
     * Processes a single sample through the filter.
     *
     * @param input the input sample
     * @return the filtered output sample
     */
    public float processSample(float input) {
        double output = b0 * input + z1;
        z1 = b1 * input - a1 * output + z2;
        z2 = b2 * input - a2 * output;
        return (float) output;
    }

    /**
     * Processes a buffer of samples in-place.
     *
     * @param buffer the sample buffer
     * @param offset start offset
     * @param length number of samples to process
     */
    public void process(float[] buffer, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            buffer[i] = processSample(buffer[i]);
        }
    }

    /**
     * Resets the filter state (delay line).
     */
    public void reset() {
        z1 = z2 = 0.0;
    }

    /**
     * Recalculates the filter coefficients.
     *
     * @param type       the filter type
     * @param sampleRate the sample rate in Hz
     * @param frequency  the center/cutoff frequency in Hz
     * @param q          the Q factor
     * @param gainDb     gain in dB
     */
    public void recalculate(FilterType type, double sampleRate,
                            double frequency, double q, double gainDb) {
        BiquadFilter temp = create(type, sampleRate, frequency, q, gainDb);
        this.b0 = temp.b0;
        this.b1 = temp.b1;
        this.b2 = temp.b2;
        this.a1 = temp.a1;
        this.a2 = temp.a2;
    }
}
