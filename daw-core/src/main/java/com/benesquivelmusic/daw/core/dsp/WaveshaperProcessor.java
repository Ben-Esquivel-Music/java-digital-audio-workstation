package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Objects;

/**
 * Waveshaping distortion/saturation processor with configurable oversampling
 * (2×, 4×, 8×) to suppress aliasing artifacts caused by nonlinear transfer
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
 *       saturation, tape saturation</li>
 *   <li>Polyphase FIR half-band upsampler/downsampler pair for 2× oversampling,
 *       cascadable for 4×/8×</li>
 *   <li>Configurable drive, wet/dry mix, and output gain</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class WaveshaperProcessor implements AudioProcessor {

    /** Waveshaping transfer function. */
    public enum TransferFunction {
        /** Symmetric soft clipping using hyperbolic tangent. */
        SOFT_CLIP,
        /** Hard clipping to [-1, 1]. */
        HARD_CLIP,
        /** Asymmetric tube-style saturation emulating push-pull tube stages. */
        TUBE_SATURATION,
        /** Gentle tape-style saturation with subtle even-harmonic warmth. */
        TAPE_SATURATION
    }

    /** Oversampling factor for antialiasing. */
    public enum OversampleFactor {
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

        /** Returns the integer oversampling factor (2, 4, or 8). */
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
        };
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

    public double getDriveDb() {
        return driveDb;
    }

    public void setDriveDb(double driveDb) {
        this.driveDb = driveDb;
    }

    public double getMix() {
        return mix;
    }

    public void setMix(double mix) {
        if (mix < 0 || mix > 1.0) {
            throw new IllegalArgumentException("mix must be in [0, 1]: " + mix);
        }
        this.mix = mix;
    }

    public double getOutputGainDb() {
        return outputGainDb;
    }

    public void setOutputGainDb(double outputGainDb) {
        this.outputGainDb = outputGainDb;
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
