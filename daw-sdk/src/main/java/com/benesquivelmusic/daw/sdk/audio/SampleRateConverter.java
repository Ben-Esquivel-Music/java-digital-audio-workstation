package com.benesquivelmusic.daw.sdk.audio;

import java.util.Objects;

/**
 * Sealed interface describing a sample-rate converter (SRC) kernel used
 * to resample an audio clip or bus output from its native rate to the
 * session's rate (or vice-versa).
 *
 * <p>Real sessions routinely mix 44.1&nbsp;kHz stems, 48&nbsp;kHz
 * recordings and 96&nbsp;kHz captures; professional DAWs (Reaper, Pro
 * Tools, Logic, Cubase) resolve these by resampling each source
 * just-in-time with a user-selectable quality. This type exposes the
 * three bundled quality tiers — {@link Low}, {@link Medium}, and
 * {@link High} — each of which is an algebraic-data-type record so
 * consumers can use exhaustive {@code switch} expressions.</p>
 *
 * <h2>Quality tiers</h2>
 *
 * <table>
 *   <caption>Documented specifications of the bundled SRC kernels</caption>
 *   <thead>
 *     <tr><th>Tier</th><th>Algorithm</th><th>Passband&nbsp;ripple</th>
 *         <th>Stop-band attenuation</th><th>Relative CPU cost</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@link Low}</td>   <td>Linear interpolation</td>
 *         <td>&le; 3.0&nbsp;dB</td><td>&ge; 12&nbsp;dB</td><td>1&times;</td></tr>
 *     <tr><td>{@link Medium}</td><td>32-tap polyphase FIR (Hann)</td>
 *         <td>&le; 0.1&nbsp;dB</td><td>&ge; 60&nbsp;dB</td><td>~10&times;</td></tr>
 *     <tr><td>{@link High}</td>  <td>128-tap windowed sinc (Kaiser β≈12)</td>
 *         <td>&le; 0.01&nbsp;dB</td><td>&ge; 120&nbsp;dB</td><td>~40&times;</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>All implementations are pure functions of {@code (input,
 * sourceRateHz, targetRateHz)} — they allocate their output buffer and
 * carry no cross-call state, so the same instance can be reused from
 * any thread. Converters are <b>not</b> real-time safe (they allocate);
 * callers are expected to render them once into the engine's
 * {@code AudioBufferPool} and cache the result.</p>
 *
 * @see SourceRateMetadata
 */
public sealed interface SampleRateConverter
        permits SampleRateConverter.Low,
                SampleRateConverter.Medium,
                SampleRateConverter.High {

    /** User-facing quality tiers exposed in the settings dialog. */
    enum QualityTier {
        /** Linear interpolation — fastest, lowest fidelity. */
        LOW,
        /** 32-tap polyphase FIR — good balance. */
        MEDIUM,
        /** 128-tap windowed-sinc — reference quality. */
        HIGH
    }

    /** @return the quality tier of this converter. */
    QualityTier tier();

    /** @return documented worst-case passband ripple in dB. */
    double passbandRippleDb();

    /** @return documented minimum stop-band attenuation in dB. */
    double stopbandAttenuationDb();

    /**
     * Resamples a mono signal from {@code sourceRateHz} to
     * {@code targetRateHz}.
     *
     * <p>When the two rates are equal the input is returned unchanged
     * (defensive copy) — callers should still check rate equality
     * upstream and skip SRC entirely for the hot path.</p>
     *
     * @param input          the source samples (must not be {@code null})
     * @param sourceRateHz   the source sample rate in Hz (positive)
     * @param targetRateHz   the target sample rate in Hz (positive)
     * @return a newly-allocated buffer of resampled samples
     * @throws NullPointerException     if {@code input} is null
     * @throws IllegalArgumentException if either rate is not positive
     */
    float[] process(float[] input, int sourceRateHz, int targetRateHz);

    /**
     * Resamples a multi-channel buffer channel-by-channel.
     *
     * @param input          {@code [channel][sample]} input, non-null
     * @param sourceRateHz   the source sample rate in Hz (positive)
     * @param targetRateHz   the target sample rate in Hz (positive)
     * @return resampled {@code [channel][sample]} buffer
     */
    default float[][] process(float[][] input, int sourceRateHz, int targetRateHz) {
        Objects.requireNonNull(input, "input must not be null");
        float[][] out = new float[input.length][];
        for (int c = 0; c < input.length; c++) {
            Objects.requireNonNull(input[c], "input channel must not be null");
            out[c] = process(input[c], sourceRateHz, targetRateHz);
        }
        return out;
    }

    /**
     * Returns the expected output frame count for a given input length.
     *
     * @param inputFrames  number of input frames (non-negative)
     * @param sourceRateHz source sample rate (positive)
     * @param targetRateHz target sample rate (positive)
     * @return the rounded output length in frames
     */
    static int estimateOutputFrames(int inputFrames, int sourceRateHz, int targetRateHz) {
        if (inputFrames < 0) {
            throw new IllegalArgumentException("inputFrames must be non-negative: " + inputFrames);
        }
        validateRates(sourceRateHz, targetRateHz);
        return (int) Math.round((long) inputFrames * (double) targetRateHz / (double) sourceRateHz);
    }

    /** Returns the converter instance for the given tier. */
    static SampleRateConverter of(QualityTier tier) {
        Objects.requireNonNull(tier, "tier must not be null");
        return switch (tier) {
            case LOW    -> new Low();
            case MEDIUM -> new Medium();
            case HIGH   -> new High();
        };
    }

    private static void validateRates(int sourceRateHz, int targetRateHz) {
        if (sourceRateHz <= 0) {
            throw new IllegalArgumentException("sourceRateHz must be positive: " + sourceRateHz);
        }
        if (targetRateHz <= 0) {
            throw new IllegalArgumentException("targetRateHz must be positive: " + targetRateHz);
        }
    }

    // ---------------------------------------------------------------
    // Implementations
    // ---------------------------------------------------------------

    /**
     * Linear-interpolation converter — the cheapest tier.
     *
     * <p>Suitable for scrub/preview, draft mixes, or for 1:1 integer
     * ratios where aliasing is inaudible. Not suitable for final
     * render.</p>
     */
    record Low() implements SampleRateConverter {
        @Override public QualityTier tier() { return QualityTier.LOW; }
        @Override public double passbandRippleDb()      { return 3.0; }
        @Override public double stopbandAttenuationDb() { return 12.0; }

        @Override
        public float[] process(float[] input, int sourceRateHz, int targetRateHz) {
            Objects.requireNonNull(input, "input must not be null");
            validateRates(sourceRateHz, targetRateHz);
            if (sourceRateHz == targetRateHz) {
                return input.clone();
            }
            int outLen = estimateOutputFrames(input.length, sourceRateHz, targetRateHz);
            float[] out = new float[outLen];
            double ratio = (double) sourceRateHz / (double) targetRateHz;
            int n = input.length;
            for (int i = 0; i < outLen; i++) {
                double srcPos = i * ratio;
                int i0 = (int) Math.floor(srcPos);
                double frac = srcPos - i0;
                float s0 = (i0 >= 0 && i0 < n) ? input[i0] : 0f;
                float s1 = (i0 + 1 >= 0 && i0 + 1 < n) ? input[i0 + 1] : 0f;
                out[i] = (float) (s0 + (s1 - s0) * frac);
            }
            return out;
        }
    }

    /**
     * 32-tap polyphase FIR converter with a Hann window — the default
     * recommended tier.
     */
    record Medium() implements SampleRateConverter {
        private static final int HALF_TAPS = 16;  // total kernel = 32

        @Override public QualityTier tier() { return QualityTier.MEDIUM; }
        @Override public double passbandRippleDb()      { return 0.1; }
        @Override public double stopbandAttenuationDb() { return 60.0; }

        @Override
        public float[] process(float[] input, int sourceRateHz, int targetRateHz) {
            Objects.requireNonNull(input, "input must not be null");
            validateRates(sourceRateHz, targetRateHz);
            if (sourceRateHz == targetRateHz) {
                return input.clone();
            }
            return SincResampler.resample(input, sourceRateHz, targetRateHz, HALF_TAPS, false);
        }
    }

    /**
     * 128-tap windowed-sinc converter with a Kaiser window — reference
     * quality, used for final render and mix-down.
     */
    record High() implements SampleRateConverter {
        private static final int HALF_TAPS = 64;  // total kernel = 128

        @Override public QualityTier tier() { return QualityTier.HIGH; }
        @Override public double passbandRippleDb()      { return 0.01; }
        @Override public double stopbandAttenuationDb() { return 120.0; }

        @Override
        public float[] process(float[] input, int sourceRateHz, int targetRateHz) {
            Objects.requireNonNull(input, "input must not be null");
            validateRates(sourceRateHz, targetRateHz);
            if (sourceRateHz == targetRateHz) {
                return input.clone();
            }
            return SincResampler.resample(input, sourceRateHz, targetRateHz, HALF_TAPS, true);
        }
    }
}
