package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.mixer.InsertEffect;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Waveshaping distortion/saturation processor with configurable oversampling
 * (1×, 2×, 4×, 8×) to suppress aliasing artifacts caused by nonlinear transfer
 * functions.
 *
 * <p>Implements oversampled waveshaping using polyphase FIR half-band filters
 * for efficient upsampling and downsampling, as recommended by AES research
 * on antialiasing filter designs for nonlinear processing. The half-band
 * polyphase decomposition reduces computational cost: one polyphase phase
 * requires a full FIR convolution while the other reduces to a simple delay.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Built-in transfer functions: soft-clip (tanh), hard-clip, tube
 *       saturation, tape saturation, plus a user-defined {@code CUSTOM} curve
 *       built from control points</li>
 *   <li>Polyphase FIR half-band upsampler/downsampler pair for 2× oversampling,
 *       cascadable for 4×/8×; {@code NONE} bypasses the oversampling chain</li>
 *   <li>Configurable drive, wet/dry mix, and output gain</li>
 *   <li>Reports accurate {@link #getLatencySamples() latency} for plugin
 *       delay compensation based on the oversampling filter's group delay</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
@RealTimeSafe
@InsertEffect(type = "WAVESHAPER", displayName = "Waveshaper")
public final class WaveshaperProcessor implements AudioProcessor {

    /** Minimum drive in dB accepted by {@link #setDriveDb(double)}. */
    public static final double MIN_DRIVE_DB = 0.0;
    /** Maximum drive in dB accepted by {@link #setDriveDb(double)}. */
    public static final double MAX_DRIVE_DB = 48.0;
    /** Minimum output gain in dB accepted by {@link #setOutputGainDb(double)}. */
    public static final double MIN_OUTPUT_GAIN_DB = -12.0;
    /** Maximum output gain in dB accepted by {@link #setOutputGainDb(double)}. */
    public static final double MAX_OUTPUT_GAIN_DB = 12.0;

    /** Waveshaping transfer function. */
    public enum TransferFunction {
        /** Symmetric soft clipping using hyperbolic tangent. */
        SOFT_CLIP,
        /** Hard clipping to [-1, 1]. */
        HARD_CLIP,
        /** Asymmetric tube-style saturation emulating push-pull tube stages. */
        TUBE_SATURATION,
        /** Gentle tape-style saturation with subtle even-harmonic warmth. */
        TAPE_SATURATION,
        /**
         * User-supplied transfer curve defined by control points. The curve is
         * evaluated by piecewise-linear interpolation between the configured
         * points — see {@link WaveshaperProcessor#setCustomCurvePoints(double[], double[])}.
         */
        CUSTOM
    }

    /** Oversampling factor for antialiasing. */
    public enum OversampleFactor {
        /** 1× — no oversampling (processes at the native sample rate). */
        NONE(0),
        /** 2× oversampling (one cascaded half-band stage). */
        TWO_X(1),
        /** 4× oversampling (two cascaded half-band stages). */
        FOUR_X(2),
        /** 8× oversampling (three cascaded half-band stages). */
        EIGHT_X(3);

        private final int stages;

        OversampleFactor(int stages) {
            this.stages = stages;
        }

        /** Returns the number of cascaded 2× stages. */
        public int getStages() {
            return stages;
        }

        /** Returns the integer oversampling factor (1, 2, 4, or 8). */
        public int getFactor() {
            return 1 << stages;
        }
    }

    // Half-band FIR filter parameters (31-tap Kaiser-windowed, ~90 dB rejection)
    private static final int HALF_BAND_TAPS = 31;
    private static final int NUM_EVEN_PHASE_COEFFS = (HALF_BAND_TAPS + 1) / 2; // 16
    private static final int PASSTHROUGH_DELAY = (NUM_EVEN_PHASE_COEFFS - 1) / 2; // 7
    private static final int ODD_DELAY_SIZE = PASSTHROUGH_DELAY + 1; // 8
    private static final int MAX_OVERSAMPLED = 8; // max factor

    // Even-phase polyphase coefficients (precomputed once)
    private static final double[] EVEN_PHASE_COEFFS;

    static {
        double[] fullCoeffs = DspUtils.designHalfBandCoefficients(HALF_BAND_TAPS);
        EVEN_PHASE_COEFFS = new double[NUM_EVEN_PHASE_COEFFS];
        for (int j = 0; j < NUM_EVEN_PHASE_COEFFS; j++) {
            EVEN_PHASE_COEFFS[j] = fullCoeffs[2 * j];
        }
    }

    private final int channels;
    private final double sampleRate;
    private TransferFunction transferFunction;
    private OversampleFactor oversampleFactor;
    private double driveDb;
    private double mix;
    private double outputGainDb;

    // CUSTOM transfer function: strictly-monotonic x control points with paired y values.
    // Defaults to the identity curve (x -> x) within [-1, 1].
    private double[] customX = {-1.0, 0.0, 1.0};
    private double[] customY = {-1.0, 0.0, 1.0};

    // Upsample polyphase state: [stage][channel][NUM_EVEN_PHASE_COEFFS]
    private double[][][] upDelayLines;
    private int[][] upWritePos;

    // Downsample polyphase state
    private double[][][] downEvenDelayLines;   // [stage][channel][NUM_EVEN_PHASE_COEFFS]
    private int[][] downEvenWritePos;
    private double[][][] downOddDelayLines;    // [stage][channel][ODD_DELAY_SIZE]
    private int[][] downOddWritePos;

    // Pre-allocated work buffers for the oversampling chain (avoids allocation in process)
    private final double[] workBuf1 = new double[MAX_OVERSAMPLED];
    private final double[] workBuf2 = new double[MAX_OVERSAMPLED];

    /**
     * Creates a waveshaper processor with default settings.
     *
     * <p>Defaults: soft-clip transfer function, 2× oversampling, 0 dB drive,
     * 100% wet mix, 0 dB output gain.</p>
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public WaveshaperProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.transferFunction = TransferFunction.SOFT_CLIP;
        this.oversampleFactor = OversampleFactor.TWO_X;
        this.driveDb = 0.0;
        this.mix = 1.0;
        this.outputGainDb = 0.0;

        initFilterState();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);
        double driveLinear = Math.pow(10.0, driveDb / 20.0);
        double outputGainLinear = Math.pow(10.0, outputGainDb / 20.0);
        int numStages = oversampleFactor.getStages();

        // 1× oversampling fast path: shape directly at the native sample rate.
        if (numStages == 0) {
            for (int ch = 0; ch < activeCh; ch++) {
                for (int frame = 0; frame < numFrames; frame++) {
                    float dry = inputBuffer[ch][frame];
                    float wet = (float) (applyTransferFunction((float) (dry * driveLinear))
                            * outputGainLinear);
                    outputBuffer[ch][frame] = (float) (dry * (1.0 - mix) + wet * mix);
                }
            }
            return;
        }

        for (int ch = 0; ch < activeCh; ch++) {
            for (int frame = 0; frame < numFrames; frame++) {
                float dry = inputBuffer[ch][frame];

                // Upsample through cascaded 2× stages
                double[] src = workBuf1;
                double[] dst = workBuf2;
                src[0] = dry;
                int currentLen = 1;

                for (int stage = 0; stage < numStages; stage++) {
                    int newLen = currentLen * 2;
                    for (int i = 0; i < currentLen; i++) {
                        upsample2x(src[i], dst, i * 2, stage, ch);
                    }
                    currentLen = newLen;
                    double[] tmp = src;
                    src = dst;
                    dst = tmp;
                }

                // Apply waveshaping with drive at the oversampled rate
                for (int i = 0; i < currentLen; i++) {
                    src[i] = applyTransferFunction((float) (src[i] * driveLinear));
                }

                // Downsample through cascaded 2× stages (reverse order)
                for (int stage = numStages - 1; stage >= 0; stage--) {
                    int newLen = currentLen / 2;
                    for (int i = 0; i < newLen; i++) {
                        dst[i] = downsample2x(src[2 * i], src[2 * i + 1], stage, ch);
                    }
                    currentLen = newLen;
                    double[] tmp = src;
                    src = dst;
                    dst = tmp;
                }

                // Mix wet/dry and apply output gain
                float wet = (float) (src[0] * outputGainLinear);
                outputBuffer[ch][frame] = (float) (dry * (1.0 - mix) + wet * mix);
            }
        }
    }

    // --- Polyphase half-band upsampling/downsampling ---

    /**
     * Upsamples a single input sample to two output samples using the polyphase
     * half-band FIR decomposition.
     *
     * <p>Even output: full convolution with the even-phase coefficients (scaled ×2).
     * Odd output: delayed passthrough (the odd-phase reduces to h[center] = 0.5,
     * scaled ×2 = 1.0).</p>
     */
    private void upsample2x(double input, double[] output, int outOffset,
                             int stage, int ch) {
        upDelayLines[stage][ch][upWritePos[stage][ch]] = input;

        // Even output: 2 × Σ p0[j] × x[n-j]
        double sum = 0.0;
        int pos = upWritePos[stage][ch];
        for (int j = 0; j < NUM_EVEN_PHASE_COEFFS; j++) {
            sum += EVEN_PHASE_COEFFS[j] * upDelayLines[stage][ch][pos];
            pos--;
            if (pos < 0) {
                pos = NUM_EVEN_PHASE_COEFFS - 1;
            }
        }
        output[outOffset] = sum * 2.0;

        // Odd output: delayed passthrough (h[center]=0.5 × 2 = 1.0)
        int delayedPos = (upWritePos[stage][ch] - PASSTHROUGH_DELAY
                + NUM_EVEN_PHASE_COEFFS) % NUM_EVEN_PHASE_COEFFS;
        output[outOffset + 1] = upDelayLines[stage][ch][delayedPos];

        upWritePos[stage][ch] = (upWritePos[stage][ch] + 1) % NUM_EVEN_PHASE_COEFFS;
    }

    /**
     * Downsamples two input samples (even, odd) to one output sample using the
     * polyphase half-band FIR decomposition.
     */
    private double downsample2x(double evenSample, double oddSample,
                                int stage, int ch) {
        downEvenDelayLines[stage][ch][downEvenWritePos[stage][ch]] = evenSample;
        downOddDelayLines[stage][ch][downOddWritePos[stage][ch]] = oddSample;

        // Even-phase convolution: Σ p0[j] × x_even[n-j]
        double sum = 0.0;
        int pos = downEvenWritePos[stage][ch];
        for (int j = 0; j < NUM_EVEN_PHASE_COEFFS; j++) {
            sum += EVEN_PHASE_COEFFS[j] * downEvenDelayLines[stage][ch][pos];
            pos--;
            if (pos < 0) {
                pos = NUM_EVEN_PHASE_COEFFS - 1;
            }
        }

        // Odd-phase contribution: 0.5 × x_odd[n - PASSTHROUGH_DELAY]
        int delayedOddPos = (downOddWritePos[stage][ch] - PASSTHROUGH_DELAY
                + ODD_DELAY_SIZE) % ODD_DELAY_SIZE;
        sum += 0.5 * downOddDelayLines[stage][ch][delayedOddPos];

        downEvenWritePos[stage][ch] = (downEvenWritePos[stage][ch] + 1) % NUM_EVEN_PHASE_COEFFS;
        downOddWritePos[stage][ch] = (downOddWritePos[stage][ch] + 1) % ODD_DELAY_SIZE;

        return sum;
    }

    // --- Transfer functions ---

    private float applyTransferFunction(float x) {
        return switch (transferFunction) {
            case SOFT_CLIP -> (float) Math.tanh(x);
            case HARD_CLIP -> Math.max(-1.0f, Math.min(1.0f, x));
            case TUBE_SATURATION -> tubeSaturate(x);
            case TAPE_SATURATION -> tapeSaturate(x);
            case CUSTOM -> customCurve(x);
        };
    }

    /**
     * Evaluates the user-supplied custom transfer curve at {@code x} using
     * piecewise-linear interpolation between the configured control points.
     * Inputs outside the configured x-range are clamped to the first/last
     * control point's y-value (flat extrapolation).
     */
    private float customCurve(float x) {
        double[] xs = customX;
        double[] ys = customY;
        int n = xs.length;
        if (x <= xs[0]) {
            return (float) ys[0];
        }
        if (x >= xs[n - 1]) {
            return (float) ys[n - 1];
        }
        // Linear scan is fine for the small control-point counts typical of
        // a transfer curve (≤ ~32 points); avoids array-allocating a binary
        // search wrapper and keeps this path allocation-free.
        for (int i = 1; i < n; i++) {
            if (x <= xs[i]) {
                double x0 = xs[i - 1];
                double x1 = xs[i];
                double y0 = ys[i - 1];
                double y1 = ys[i];
                double t = (x - x0) / (x1 - x0);
                return (float) (y0 + t * (y1 - y0));
            }
        }
        return (float) ys[n - 1];
    }

    /**
     * Asymmetric tube-style soft clipping. Positive half saturates smoothly
     * toward +1; negative half compresses harder (emulates push-pull tube stages).
     */
    private static float tubeSaturate(float x) {
        if (x >= 0) {
            return (float) (1.0 - Math.exp(-x));
        } else {
            return (float) (-(1.0 - Math.exp(x)) * 0.8);
        }
    }

    /**
     * Magnetic tape saturation with subtle even-harmonic asymmetry for warmth.
     * Uses a rational soft-saturation curve biased by a small even-order term.
     */
    private static float tapeSaturate(float x) {
        double biased = x + 0.05 * x * Math.abs(x);
        return (float) (biased / (1.0 + Math.abs(biased)));
    }

    // --- Parameter accessors ---

    public TransferFunction getTransferFunction() {
        return transferFunction;
    }

    public void setTransferFunction(TransferFunction transferFunction) {
        this.transferFunction = Objects.requireNonNull(transferFunction);
    }

    public OversampleFactor getOversampleFactor() {
        return oversampleFactor;
    }

    public void setOversampleFactor(OversampleFactor oversampleFactor) {
        this.oversampleFactor = Objects.requireNonNull(oversampleFactor);
        initFilterState();
    }

    @ProcessorParam(id = 0, name = "Drive", min = 0.0, max = 48.0, defaultValue = 0.0, unit = "dB")
    public double getDriveDb() {
        return driveDb;
    }

    /**
     * Sets the input drive in dB.
     *
     * @param driveDb drive in dB, must be in [{@value #MIN_DRIVE_DB},
     *                {@value #MAX_DRIVE_DB}]
     * @throws IllegalArgumentException if {@code driveDb} is out of range
     */
    public void setDriveDb(double driveDb) {
        if (driveDb < MIN_DRIVE_DB || driveDb > MAX_DRIVE_DB) {
            throw new IllegalArgumentException(
                    "driveDb must be in [" + MIN_DRIVE_DB + ", " + MAX_DRIVE_DB + "]: " + driveDb);
        }
        this.driveDb = driveDb;
    }

    @ProcessorParam(id = 1, name = "Mix", min = 0.0, max = 1.0, defaultValue = 1.0)
    public double getMix() {
        return mix;
    }

    public void setMix(double mix) {
        if (mix < 0 || mix > 1.0) {
            throw new IllegalArgumentException("mix must be in [0, 1]: " + mix);
        }
        this.mix = mix;
    }

    @ProcessorParam(id = 2, name = "Output Gain", min = -12.0, max = 12.0, defaultValue = 0.0, unit = "dB")
    public double getOutputGainDb() {
        return outputGainDb;
    }

    /**
     * Sets the output gain in dB.
     *
     * @param outputGainDb output gain in dB, must be in
     *                     [{@value #MIN_OUTPUT_GAIN_DB}, {@value #MAX_OUTPUT_GAIN_DB}]
     * @throws IllegalArgumentException if {@code outputGainDb} is out of range
     */
    public void setOutputGainDb(double outputGainDb) {
        if (outputGainDb < MIN_OUTPUT_GAIN_DB || outputGainDb > MAX_OUTPUT_GAIN_DB) {
            throw new IllegalArgumentException("outputGainDb must be in ["
                    + MIN_OUTPUT_GAIN_DB + ", " + MAX_OUTPUT_GAIN_DB + "]: " + outputGainDb);
        }
        this.outputGainDb = outputGainDb;
    }

    /**
     * Returns an unmodifiable snapshot of the custom-curve control-point
     * x-coordinates. Defaults to {@code {-1, 0, 1}} (identity curve).
     */
    public List<Double> getCustomCurveXs() {
        var list = new java.util.ArrayList<Double>(customX.length);
        for (double v : customX) list.add(v);
        return List.copyOf(list);
    }

    /**
     * Returns an unmodifiable snapshot of the custom-curve control-point
     * y-coordinates. Defaults to {@code {-1, 0, 1}} (identity curve).
     */
    public List<Double> getCustomCurveYs() {
        var list = new java.util.ArrayList<Double>(customY.length);
        for (double v : customY) list.add(v);
        return List.copyOf(list);
    }

    /**
     * Sets the control points defining the {@link TransferFunction#CUSTOM}
     * transfer curve. The curve is evaluated by piecewise-linear interpolation
     * between the supplied points; inputs outside {@code xs[0]..xs[n-1]} are
     * clamped to the nearest endpoint y-value.
     *
     * @param xs strictly-monotonic increasing x-coordinates (at least 2 points)
     * @param ys y-coordinates paired with {@code xs}; must be the same length
     * @throws NullPointerException     if either array is {@code null}
     * @throws IllegalArgumentException if the arrays have different lengths,
     *                                  fewer than 2 points, or {@code xs} is
     *                                  not strictly monotonic increasing
     */
    public void setCustomCurvePoints(double[] xs, double[] ys) {
        Objects.requireNonNull(xs, "xs must not be null");
        Objects.requireNonNull(ys, "ys must not be null");
        if (xs.length != ys.length) {
            throw new IllegalArgumentException(
                    "xs and ys must have the same length: " + xs.length + " vs " + ys.length);
        }
        if (xs.length < 2) {
            throw new IllegalArgumentException("at least 2 control points are required");
        }
        for (int i = 1; i < xs.length; i++) {
            if (!(xs[i] > xs[i - 1])) {
                throw new IllegalArgumentException(
                        "xs must be strictly monotonic increasing at index " + i);
            }
        }
        // Defensive copy so callers cannot mutate our state after the fact.
        this.customX = Arrays.copyOf(xs, xs.length);
        this.customY = Arrays.copyOf(ys, ys.length);
    }

    @Override
    public void reset() {
        initFilterState();
    }

    @Override
    public int getInputChannelCount() {
        return channels;
    }

    @Override
    public int getOutputChannelCount() {
        return channels;
    }

    /**
     * Returns the processing latency introduced by the oversampling
     * upsample/downsample filter chain, in input-rate samples.
     *
     * <p>Each cascaded 2× polyphase half-band stage adds a group delay of
     * {@code 2 × PASSTHROUGH_DELAY = 14} samples at that stage's input rate.
     * For stage {@code k} (0-indexed, where {@code k = 0} is the outermost
     * stage operating at the native sample rate), the contribution in
     * native-rate samples is {@code 14 / 2^k}. The total latency at the
     * native sample rate is therefore:</p>
     *
     * <pre>
     *   latency = sum_{k=0..stages-1} 14 / 2^k
     * </pre>
     *
     * <p>Values for each factor (rounded to the nearest integer):</p>
     * <ul>
     *   <li>{@link OversampleFactor#NONE}   → 0 samples</li>
     *   <li>{@link OversampleFactor#TWO_X}  → 14 samples</li>
     *   <li>{@link OversampleFactor#FOUR_X} → 21 samples</li>
     *   <li>{@link OversampleFactor#EIGHT_X}→ 25 samples</li>
     * </ul>
     */
    @Override
    public int getLatencySamples() {
        int stages = oversampleFactor.getStages();
        if (stages == 0) {
            return 0;
        }
        double total = 0.0;
        for (int k = 0; k < stages; k++) {
            total += (2.0 * PASSTHROUGH_DELAY) / (1 << k);
        }
        return (int) Math.round(total);
    }

    private void initFilterState() {
        int numStages = oversampleFactor.getStages();

        upDelayLines = new double[numStages][channels][NUM_EVEN_PHASE_COEFFS];
        upWritePos = new int[numStages][channels];

        downEvenDelayLines = new double[numStages][channels][NUM_EVEN_PHASE_COEFFS];
        downEvenWritePos = new int[numStages][channels];
        downOddDelayLines = new double[numStages][channels][ODD_DELAY_SIZE];
        downOddWritePos = new int[numStages][channels];
    }
}
