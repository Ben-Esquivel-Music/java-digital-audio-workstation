package com.benesquivelmusic.daw.sdk.audio;

/**
 * Package-private windowed-sinc resampler shared by
 * {@link SampleRateConverter.Medium} and {@link SampleRateConverter.High}.
 *
 * <p>The algorithm convolves the input signal with a truncated sinc
 * low-pass kernel whose cutoff is pinned to half the <em>lower</em> of
 * the two sample rates (so both up- and down-sampling are band-limited
 * to avoid aliasing). The kernel is optionally Kaiser-windowed (β≈12)
 * for the high-quality tier, or Hann-windowed for the medium tier.</p>
 *
 * <p>Not real-time safe: allocates the output buffer.</p>
 */
final class SincResampler {

    private SincResampler() {}

    /**
     * Resamples a mono signal.
     *
     * @param input          source samples
     * @param sourceRateHz   source rate
     * @param targetRateHz   target rate
     * @param halfTaps       half the filter length (kernel = 2 * halfTaps + 1)
     * @param kaiser         {@code true} = Kaiser window (β≈12),
     *                       {@code false} = Hann window
     * @return newly-allocated resampled buffer
     */
    static float[] resample(float[] input,
                            int sourceRateHz,
                            int targetRateHz,
                            int halfTaps,
                            boolean kaiser) {
        int n = input.length;
        int outLen = (int) Math.round((long) n * (double) targetRateHz / (double) sourceRateHz);
        float[] out = new float[outLen];

        double ratio = (double) sourceRateHz / (double) targetRateHz;
        // Cutoff (normalized to source rate): 0.5 when upsampling; 0.5/ratio when downsampling.
        double cutoff = Math.min(1.0, 1.0 / ratio) * 0.5;
        // Kernel scaling compensates for the lowpass gain loss.
        double scale = 2.0 * cutoff;
        double beta = 12.0;
        double i0Beta = kaiser ? bessel0(beta) : 1.0;

        for (int i = 0; i < outLen; i++) {
            double srcPos = i * ratio;
            int center = (int) Math.floor(srcPos);
            double frac = srcPos - center;

            double acc = 0.0;
            for (int k = -halfTaps + 1; k <= halfTaps; k++) {
                int idx = center + k;
                if (idx < 0 || idx >= n) {
                    continue;
                }
                double x = (k - frac);                 // tap position in source samples
                double arg = 2.0 * cutoff * x;         // normalized sinc argument
                double sincVal = (Math.abs(arg) < 1e-12) ? 1.0
                        : Math.sin(Math.PI * arg) / (Math.PI * arg);
                // Window: maps k - frac in [-halfTaps, halfTaps] to [-1, 1].
                double wArg = x / halfTaps;
                double window;
                if (wArg <= -1.0 || wArg >= 1.0) {
                    window = 0.0;
                } else if (kaiser) {
                    window = bessel0(beta * Math.sqrt(1.0 - wArg * wArg)) / i0Beta;
                } else {
                    // Hann
                    window = 0.5 * (1.0 + Math.cos(Math.PI * wArg));
                }
                acc += input[idx] * sincVal * window * scale;
            }
            out[i] = (float) acc;
        }
        return out;
    }

    /** Modified Bessel function of the first kind, order 0 — series approximation. */
    private static double bessel0(double x) {
        double sum = 1.0;
        double term = 1.0;
        double halfXSq = (x * x) / 4.0;
        for (int k = 1; k < 40; k++) {
            term *= halfXSq / (k * k);
            sum += term;
            if (term < 1e-15 * sum) {
                break;
            }
        }
        return sum;
    }
}
