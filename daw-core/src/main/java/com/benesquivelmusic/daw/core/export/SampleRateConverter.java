package com.benesquivelmusic.daw.core.export;

/**
 * Sample rate converter using windowed sinc interpolation.
 *
 * <p>Converts audio from one sample rate to another using a high-quality
 * sinc interpolation kernel with a Kaiser window. The filter length is
 * configurable via the {@code quality} parameter (higher quality = longer
 * kernel, more accurate frequency response, less aliasing).</p>
 *
 * <p>For downsampling, a low-pass anti-aliasing filter at the target
 * Nyquist frequency is implicitly applied by the sinc kernel.</p>
 */
final class SampleRateConverter {

    /**
     * Number of zero-crossings on each side of the sinc kernel center.
     * Higher values yield better frequency response but cost more CPU.
     */
    private static final int KERNEL_HALF_WIDTH = 16;

    /** Kaiser window beta for ~-90 dB sidelobe attenuation. */
    private static final double KAISER_BETA = 9.0;

    private SampleRateConverter() {
        // utility class
    }

    /**
     * Converts a single channel of audio from one sample rate to another.
     *
     * @param input          the input sample array
     * @param sourceSampleRate the sample rate of the input in Hz
     * @param targetSampleRate the desired output sample rate in Hz
     * @return the resampled audio array
     */
    static float[] convert(float[] input, int sourceSampleRate, int targetSampleRate) {
        if (sourceSampleRate == targetSampleRate) {
            return input.clone();
        }

        double ratio = (double) targetSampleRate / sourceSampleRate;
        int outputLength = (int) Math.round(input.length * ratio);
        float[] output = new float[outputLength];

        // Cutoff frequency relative to the lower of the two sample rates
        double cutoff = Math.min(1.0, ratio);

        for (int i = 0; i < outputLength; i++) {
            double srcPos = i / ratio;
            int srcIndex = (int) Math.floor(srcPos);
            double frac = srcPos - srcIndex;

            double sum = 0.0;
            double weightSum = 0.0;

            for (int j = -KERNEL_HALF_WIDTH + 1; j <= KERNEL_HALF_WIDTH; j++) {
                int idx = srcIndex + j;
                if (idx < 0 || idx >= input.length) {
                    continue;
                }
                double x = j - frac;
                double sincVal = sinc(x * cutoff) * cutoff;
                double windowVal = kaiserWindow(x, KERNEL_HALF_WIDTH, KAISER_BETA);
                double weight = sincVal * windowVal;
                sum += input[idx] * weight;
                weightSum += weight;
            }

            output[i] = (weightSum != 0.0) ? (float) (sum / weightSum) : 0.0f;
        }

        return output;
    }

    /**
     * Normalized sinc function: sin(πx) / (πx), with sinc(0) = 1.
     */
    private static double sinc(double x) {
        if (Math.abs(x) < 1e-10) {
            return 1.0;
        }
        double pix = Math.PI * x;
        return Math.sin(pix) / pix;
    }

    /**
     * Kaiser window function.
     *
     * @param x    the sample position relative to center
     * @param halfWidth the half-width of the window
     * @param beta the Kaiser beta parameter
     * @return the window value, or 0.0 if outside the window
     */
    private static double kaiserWindow(double x, int halfWidth, double beta) {
        if (Math.abs(x) > halfWidth) {
            return 0.0;
        }
        double ratio = x / halfWidth;
        double arg = beta * Math.sqrt(1.0 - ratio * ratio);
        return besselI0(arg) / besselI0(beta);
    }

    /**
     * Modified Bessel function of the first kind, order zero.
     * Computed using the standard series expansion.
     */
    private static double besselI0(double x) {
        double sum = 1.0;
        double term = 1.0;
        double halfX = x / 2.0;
        for (int k = 1; k <= 25; k++) {
            term *= (halfX / k) * (halfX / k);
            sum += term;
            if (term < 1e-12 * sum) {
                break;
            }
        }
        return sum;
    }
}
