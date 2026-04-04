package com.benesquivelmusic.daw.core.dsp;

/**
 * First-order antiderivative antialiasing (ADAA) waveshaper.
 *
 * <p>ADAA suppresses aliasing artifacts from nonlinear waveshaping at zero
 * additional oversampling cost. Instead of evaluating the transfer function
 * directly, it computes the antiderivative {@code F} of the waveshaping
 * function {@code f} and uses the finite-difference formula:</p>
 *
 * <pre>
 *     y[n] = (F(x[n]) - F(x[n-1])) / (x[n] - x[n-1])
 * </pre>
 *
 * <p>When {@code x[n] ≈ x[n-1]} (the ill-conditioned case), the formula
 * degenerates and a L'Hôpital fallback to the direct evaluation {@code f(x[n])}
 * is used instead.</p>
 *
 * <p>References:</p>
 * <ul>
 *   <li>"Antiderivative Antialiasing Techniques in Nonlinear Wave Digital
 *       Structures" (2021) — first- and second-order ADAA for diode clippers
 *       and guitar distortion circuits</li>
 *   <li>Parker et al., "Reducing the Aliasing of Nonlinear Waveshaping Using
 *       Continuous-Time Convolution" (2016)</li>
 * </ul>
 *
 * <p>This class is stateful (tracks the previous input sample per channel) and
 * is intended for real-time sample-by-sample processing. It can be used as a
 * zero-latency, zero-oversampling alternative to the oversampled
 * {@link WaveshaperProcessor}.</p>
 */
public final class AdaaWaveshaper {

    /**
     * A waveshaping transfer function paired with its analytical antiderivative.
     *
     * <p>Implementations must provide both {@link #apply(double)} (the transfer
     * function itself) and {@link #antiderivative(double)} (its analytical
     * antiderivative, i.e. the indefinite integral).</p>
     */
    public interface TransferFunction {

        /**
         * Evaluates the waveshaping transfer function.
         *
         * @param x the input sample
         * @return the shaped output sample
         */
        double apply(double x);

        /**
         * Evaluates the antiderivative (indefinite integral) of this transfer
         * function. The constant of integration may be chosen arbitrarily
         * (only differences are used).
         *
         * @param x the input value
         * @return F(x) where F'(x) = apply(x)
         */
        double antiderivative(double x);
    }

    /**
     * Threshold below which {@code |x[n] - x[n-1]|} is considered
     * ill-conditioned, triggering the L'Hôpital fallback.
     */
    private static final double EPSILON = 1e-5;

    // --- Built-in transfer functions with analytical antiderivatives ---

    /**
     * Hyperbolic tangent soft-clip transfer function.
     *
     * <ul>
     *   <li>{@code f(x) = tanh(x)}</li>
     *   <li>{@code F(x) = ln(cosh(x))}</li>
     * </ul>
     */
    public static final TransferFunction TANH = new TransferFunction() {
        @Override
        public double apply(double x) {
            return Math.tanh(x);
        }

        @Override
        public double antiderivative(double x) {
            // ln(cosh(x)) — numerically stable form for large |x|
            double absX = Math.abs(x);
            if (absX > 10.0) {
                // For large |x|, cosh(x) ≈ e^|x|/2, so ln(cosh(x)) ≈ |x| - ln(2)
                return absX - Math.log(2.0);
            }
            return Math.log(Math.cosh(x));
        }
    };

    /**
     * Hard-clip transfer function that limits output to {@code [-1, 1]}.
     *
     * <ul>
     *   <li>{@code f(x) = clamp(x, -1, 1)}</li>
     *   <li>{@code F(x) = x²/2} for {@code |x| ≤ 1}, {@code |x| - 1/2} for {@code |x| > 1}</li>
     * </ul>
     */
    public static final TransferFunction HARD_CLIP = new TransferFunction() {
        @Override
        public double apply(double x) {
            return Math.max(-1.0, Math.min(1.0, x));
        }

        @Override
        public double antiderivative(double x) {
            if (x <= -1.0) {
                return -x - 0.5;
            } else if (x >= 1.0) {
                return x - 0.5;
            } else {
                return x * x / 2.0;
            }
        }
    };

    /**
     * Rational soft-clip transfer function: {@code f(x) = x / (1 + |x|)}.
     *
     * <ul>
     *   <li>{@code f(x) = x / (1 + |x|)}</li>
     *   <li>{@code F(x) = |x| - ln(1 + |x|)} (even function)</li>
     * </ul>
     */
    public static final TransferFunction SOFT_CLIP = new TransferFunction() {
        @Override
        public double apply(double x) {
            return x / (1.0 + Math.abs(x));
        }

        @Override
        public double antiderivative(double x) {
            // ∫ x/(1+|x|) dx = |x| - ln(1+|x|) + C
            // This is an even function (the antiderivative of an odd function).
            double absX = Math.abs(x);
            return absX - Math.log1p(absX);
        }
    };

    private final TransferFunction transferFunction;
    private final int channels;
    private final double[] previousInput;
    private final boolean[] initialized;

    /**
     * Creates an ADAA waveshaper for the given transfer function and channel count.
     *
     * @param transferFunction the waveshaping function with its antiderivative
     * @param channels         number of audio channels (must be positive)
     * @throws IllegalArgumentException if channels is not positive
     * @throws NullPointerException     if transferFunction is null
     */
    public AdaaWaveshaper(TransferFunction transferFunction, int channels) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        this.transferFunction = java.util.Objects.requireNonNull(transferFunction,
                "transferFunction must not be null");
        this.channels = channels;
        this.previousInput = new double[channels];
        this.initialized = new boolean[channels];
    }

    /**
     * Processes a single sample for the given channel using first-order ADAA.
     *
     * <p>Computes the band-limited output using the antiderivative finite-difference
     * formula. Falls back to direct evaluation when the input difference is
     * below the numerical threshold. The very first sample after construction
     * or {@link #reset()} returns {@code f(x)} directly to avoid a transient
     * from the zero-initialized state.</p>
     *
     * @param input   the current input sample
     * @param channel the channel index, must be in {@code [0, getChannels())}
     * @return the antialiased output sample
     * @throws IndexOutOfBoundsException if channel is outside {@code [0, getChannels())}
     */
    public double process(double input, int channel) {
        java.util.Objects.checkIndex(channel, channels);
        return processUnchecked(input, channel);
    }

    /**
     * Processes a block of audio in-place for the given channel using first-order ADAA.
     *
     * @param buffer    the audio buffer (modified in place)
     * @param offset    start index within the buffer
     * @param numFrames number of frames to process
     * @param channel   the channel index, must be in {@code [0, getChannels())}
     * @throws IndexOutOfBoundsException if channel is outside {@code [0, getChannels())}
     *         or if the buffer range {@code [offset, offset + numFrames)} is out of bounds
     */
    public void processBlock(float[] buffer, int offset, int numFrames, int channel) {
        java.util.Objects.checkIndex(channel, channels);
        java.util.Objects.checkFromIndexSize(offset, numFrames, buffer.length);
        for (int i = 0; i < numFrames; i++) {
            buffer[offset + i] = (float) processUnchecked(buffer[offset + i], channel);
        }
    }

    private double processUnchecked(double input, int channel) {
        if (!initialized[channel]) {
            initialized[channel] = true;
            previousInput[channel] = input;
            return transferFunction.apply(input);
        }

        double xPrev = previousInput[channel];
        previousInput[channel] = input;

        double diff = input - xPrev;
        if (Math.abs(diff) < EPSILON) {
            // L'Hôpital fallback: when x[n] ≈ x[n-1], the limit of the
            // ADAA formula equals f(x[n])
            return transferFunction.apply(input);
        }
        return (transferFunction.antiderivative(input)
                - transferFunction.antiderivative(xPrev)) / diff;
    }

    /**
     * Resets the internal state (previous input samples) to zero.
     */
    public void reset() {
        java.util.Arrays.fill(previousInput, 0.0);
        java.util.Arrays.fill(initialized, false);
    }

    /**
     * Returns the transfer function used by this waveshaper.
     *
     * @return the transfer function
     */
    public TransferFunction getTransferFunction() {
        return transferFunction;
    }

    /**
     * Returns the number of channels this waveshaper supports.
     *
     * @return the channel count
     */
    public int getChannels() {
        return channels;
    }
}
