package com.benesquivelmusic.daw.core.dsp.saturation;

import com.benesquivelmusic.daw.core.dsp.BiquadFilter;
import com.benesquivelmusic.daw.core.mixer.InsertEffect;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;
import java.util.Objects;

/**
 * Harmonic Exciter / Psychoacoustic Enhancer.
 *
 * <p>Implements the classic "Aural Exciter" topology popularized by the Aphex
 * Aural Exciter in the 1970s: split a small amount of <em>high-frequency
 * content</em> off the input via a user-controlled high-pass crossover, push
 * that side-band through a controlled nonlinearity to manufacture 2nd- and
 * 3rd-order harmonics at predictable frequencies, then sum the harmonic
 * sideband back into the dry signal at a wet/dry mix level. The result is
 * perceptually <em>brighter</em> without raising broadband level — the
 * trick still standard in mastering and broadcast sweetening today
 * (iZotope Ozone Exciter, FabFilter Saturn, Logic Exciter, …).</p>
 *
 * <p>Three character modes pick different polynomial waveshapers, giving
 * each a distinguishable harmonic signature:</p>
 * <ul>
 *   <li>{@link Mode#CLASS_A_TUBE} — asymmetric quadratic-bias soft clip:
 *       emphasises 2nd-order harmonics (warm / "tube" character).</li>
 *   <li>{@link Mode#TRANSFORMER} — symmetric cubic ({@code x − x³/3}):
 *       emphasises 3rd-order ("iron transformer" character).</li>
 *   <li>{@link Mode#TAPE} — soft saturation with a small even-order bias
 *       (mixed 2nd + 3rd, "tape" character).</li>
 * </ul>
 *
 * <p>The nonlinearity is processed at <b>2× internal oversampling</b> with a
 * Hann-windowed-sinc lowpass anti-imaging / anti-aliasing FIR pair so the
 * generated harmonics do not fold back into the audible band. (When the
 * shared cross-processor oversampler from the saturation/waveshaping
 * subsystem becomes available, this internal pair can be replaced.)</p>
 *
 * <p>Pure Java — no JNI required.</p>
 */
@RealTimeSafe
@InsertEffect(type = "EXCITER", displayName = "Exciter")
public final class ExciterProcessor implements AudioProcessor {

    /** Minimum crossover frequency in Hz. */
    public static final double MIN_FREQUENCY_HZ = 1_000.0;
    /** Maximum crossover frequency in Hz. */
    public static final double MAX_FREQUENCY_HZ = 16_000.0;
    /** Minimum drive percentage. */
    public static final double MIN_DRIVE_PERCENT = 0.0;
    /** Maximum drive percentage. */
    public static final double MAX_DRIVE_PERCENT = 100.0;
    /** Minimum wet/dry mix percentage. */
    public static final double MIN_MIX_PERCENT = 0.0;
    /** Maximum wet/dry mix percentage. */
    public static final double MAX_MIX_PERCENT = 100.0;
    /** Minimum output gain in dB. */
    public static final double MIN_OUTPUT_GAIN_DB = -12.0;
    /** Maximum output gain in dB. */
    public static final double MAX_OUTPUT_GAIN_DB = 12.0;

    /** Q factor used by the high-pass band-split filter (Butterworth-flat). */
    private static final double HIGHPASS_Q = 0.707;
    /** Drive scaling — at 100% drive we push the highpass band ~+18 dB into the shaper. */
    private static final double MAX_DRIVE_LINEAR = 8.0;

    /** Harmonic-character mode — selects the waveshaper polynomial. */
    public enum Mode {
        /** Asymmetric soft clip: dominant 2nd-order ("Class A tube"). */
        CLASS_A_TUBE,
        /** Symmetric cubic {@code x - x³/3}: dominant 3rd-order ("transformer"). */
        TRANSFORMER,
        /** Soft saturation with even-order bias ("tape"). */
        TAPE
    }

    // 15-tap Hann-windowed-sinc lowpass FIR used at the 2× oversampled rate.
    // Cutoff = 0.25 of the oversampled rate = 0.5 of the native Nyquist (i.e.
    // it is a half-band lowpass — perfect for both anti-imaging on the way up
    // and anti-aliasing on the way down).
    private static final int FIR_TAPS = 15;
    private static final double[] FIR_COEFFS = designLowpassFir(FIR_TAPS, 0.25);

    private final int channels;
    private final double sampleRate;

    private double frequencyHz;
    private double drivePercent;
    private double mixPercent;
    private double outputGainDb;
    private Mode mode;

    // Per-channel high-pass biquads for the side-chain band-split.
    private final BiquadFilter[] highPass;

    // Per-channel FIR delay lines: one for upsample (anti-imaging), one for
    // downsample (anti-aliasing). Both run at the 2× oversampled rate.
    private final double[][] upState;
    private final int[] upPos;
    private final double[][] downState;
    private final int[] downPos;

    /**
     * Creates an exciter with sensible defaults: 8 kHz crossover, 25% drive,
     * 25% mix, 0 dB output, {@link Mode#CLASS_A_TUBE}.
     *
     * @param channels   number of audio channels (must be positive)
     * @param sampleRate sample rate in Hz (must be positive)
     */
    public ExciterProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.frequencyHz = 8_000.0;
        this.drivePercent = 25.0;
        this.mixPercent = 25.0;
        this.outputGainDb = 0.0;
        this.mode = Mode.CLASS_A_TUBE;

        this.highPass = new BiquadFilter[channels];
        this.upState = new double[channels][FIR_TAPS];
        this.upPos = new int[channels];
        this.downState = new double[channels][FIR_TAPS];
        this.downPos = new int[channels];
        rebuildFilters();
    }

    private void rebuildFilters() {
        for (int ch = 0; ch < channels; ch++) {
            highPass[ch] = BiquadFilter.create(BiquadFilter.FilterType.HIGH_PASS,
                    sampleRate, frequencyHz, HIGHPASS_Q, 0.0);
        }
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, Math.min(inputBuffer.length, outputBuffer.length));
        double driveLinear = (drivePercent / 100.0) * MAX_DRIVE_LINEAR;
        double mix = mixPercent / 100.0;
        double outputGainLinear = Math.pow(10.0, outputGainDb / 20.0);

        // Bypass fast-path: when drive=0 or mix=0 the wet sideband is identically
        // zero, so we skip the high-pass + oversampled waveshaper chain entirely
        // and just apply the output trim. This makes drive=0%/mix=0% true bypass
        // (modulo output gain) and is consistent with bypass fast-paths in other
        // DSP processors (e.g. BassExtensionProcessor).
        if (driveLinear == 0.0 || mix == 0.0) {
            for (int ch = 0; ch < activeCh; ch++) {
                for (int frame = 0; frame < numFrames; frame++) {
                    outputBuffer[ch][frame] = (float) (inputBuffer[ch][frame] * outputGainLinear);
                }
            }
            return;
        }

        for (int ch = 0; ch < activeCh; ch++) {
            BiquadFilter hp = highPass[ch];
            for (int frame = 0; frame < numFrames; frame++) {
                double dry = inputBuffer[ch][frame];

                // 1. High-pass band-split — exciter only acts on HF content.
                double hpSide = hp.processSampleDouble(dry);

                // 2. Drive into the nonlinearity (side-chain only).
                double driven = hpSide * driveLinear;

                // 3. 2× oversampled waveshaping with anti-imaging / anti-aliasing FIR.
                double shapedSide = oversampledShape(driven, ch);

                // 4. Sum harmonic sideband back with the dry signal.
                double wet = dry + shapedSide * mix;

                outputBuffer[ch][frame] = (float) (wet * outputGainLinear);
            }
        }
    }

    /**
     * 2× oversampled application of the waveshaper for one input sample.
     *
     * <p>Performs zero-stuffing upsample → anti-imaging FIR → waveshape on both
     * oversampled samples → anti-aliasing FIR → decimate. The FIR coefficients
     * are normalized so the upsample step is followed by a multiply by 2 to
     * compensate for the energy lost to the inserted zero.</p>
     */
    private double oversampledShape(double input, int ch) {
        // --- Upsample (zero-stuff and FIR-filter) ---
        // Sample A: original × 2 (the ×2 compensates for the zero we'll insert next).
        double upA = pushAndConvolve(upState[ch], upPos, ch, input * 2.0);
        // Sample B: zero (zero-stuffing).
        double upB = pushAndConvolve(upState[ch], upPos, ch, 0.0);

        // --- Waveshape at the 2× rate ---
        double shapedA = waveshape(upA);
        double shapedB = waveshape(upB);

        // --- Downsample (FIR-filter then decimate) ---
        pushAndConvolve(downState[ch], downPos, ch, shapedA);
        return pushAndConvolve(downState[ch], downPos, ch, shapedB);
    }

    /**
     * Push one new sample into a circular FIR delay line and convolve with
     * {@link #FIR_COEFFS}.
     */
    private static double pushAndConvolve(double[] state, int[] posArr, int ch, double sample) {
        int p = posArr[ch];
        state[p] = sample;
        double sum = 0.0;
        int idx = p;
        for (int k = 0; k < FIR_TAPS; k++) {
            sum += FIR_COEFFS[k] * state[idx];
            idx--;
            if (idx < 0) idx = FIR_TAPS - 1;
        }
        posArr[ch] = (p + 1) % FIR_TAPS;
        return sum;
    }

    /** Apply the per-mode waveshaper polynomial. Output bounded by ~[-1, 1]. */
    private double waveshape(double x) {
        // Soft-saturate the input first to keep extreme drive bounded — also
        // ensures the shaper stays in the regime where the per-mode polynomial
        // dominates (the first-order tanh contribution is the same across modes).
        double s = Math.tanh(x);
        return switch (mode) {
            // Asymmetric exponential soft-clip — the classic "Class A" tube
            // curve: positive half saturates softly toward +1, negative half
            // is gentler. Produces strong 2nd-order harmonics with f(0) = 0.
            case CLASS_A_TUBE -> (s >= 0)
                    ? 1.0 - Math.exp(-s)
                    : -0.6 * (1.0 - Math.exp(s));
            // Symmetric cubic — pure 3rd-order generator (Chebyshev style).
            case TRANSFORMER -> s - (s * s * s) / 3.0;
            // Tape-like: rational soft saturation with a small even-order asymmetry.
            case TAPE -> {
                double biased = s + 0.15 * s * Math.abs(s);
                yield biased / (1.0 + Math.abs(biased));
            }
        };
    }

    /**
     * Designs an odd-length FIR lowpass with a Hann window.
     *
     * @param taps               filter length, must be odd
     * @param cutoffNormalized   cutoff as a fraction of the sample rate
     *                           (e.g. {@code 0.25} = fs/4)
     */
    private static double[] designLowpassFir(int taps, double cutoffNormalized) {
        if ((taps & 1) == 0) {
            throw new IllegalArgumentException("taps must be odd: " + taps);
        }
        double[] h = new double[taps];
        int center = taps / 2;
        double sum = 0.0;
        for (int i = 0; i < taps; i++) {
            int n = i - center;
            double sinc = (n == 0)
                    ? 2.0 * cutoffNormalized
                    : Math.sin(2.0 * Math.PI * cutoffNormalized * n) / (Math.PI * n);
            // Hann window
            double w = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (taps - 1)));
            h[i] = sinc * w;
            sum += h[i];
        }
        // Normalize to unity DC gain.
        for (int i = 0; i < taps; i++) {
            h[i] /= sum;
        }
        return h;
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            if (highPass[ch] != null) {
                highPass[ch].reset();
            }
            Arrays.fill(upState[ch], 0.0);
            Arrays.fill(downState[ch], 0.0);
            upPos[ch] = 0;
            downPos[ch] = 0;
        }
    }

    @Override
    public int getInputChannelCount() {
        return channels;
    }

    @Override
    public int getOutputChannelCount() {
        return channels;
    }

    // ---------------------------- parameter API ----------------------------

    @ProcessorParam(id = 0, name = "Frequency",
            min = MIN_FREQUENCY_HZ, max = MAX_FREQUENCY_HZ, defaultValue = 8000.0, unit = "Hz")
    public double getFrequencyHz() {
        return frequencyHz;
    }

    /**
     * Sets the high-pass crossover frequency.
     *
     * @param frequencyHz crossover frequency in Hz, in
     *                    [{@value #MIN_FREQUENCY_HZ}, {@value #MAX_FREQUENCY_HZ}]
     * @throws IllegalArgumentException if {@code frequencyHz} is out of range
     */
    public void setFrequencyHz(double frequencyHz) {
        if (frequencyHz < MIN_FREQUENCY_HZ || frequencyHz > MAX_FREQUENCY_HZ) {
            throw new IllegalArgumentException("frequencyHz must be in ["
                    + MIN_FREQUENCY_HZ + ", " + MAX_FREQUENCY_HZ + "]: " + frequencyHz);
        }
        this.frequencyHz = frequencyHz;
        rebuildFilters();
    }

    @ProcessorParam(id = 1, name = "Drive",
            min = MIN_DRIVE_PERCENT, max = MAX_DRIVE_PERCENT, defaultValue = 25.0, unit = "%")
    public double getDrivePercent() {
        return drivePercent;
    }

    /**
     * Sets the drive amount.
     *
     * @param drivePercent drive in percent, in [0, 100]
     * @throws IllegalArgumentException if out of range
     */
    public void setDrivePercent(double drivePercent) {
        if (drivePercent < MIN_DRIVE_PERCENT || drivePercent > MAX_DRIVE_PERCENT) {
            throw new IllegalArgumentException("drivePercent must be in ["
                    + MIN_DRIVE_PERCENT + ", " + MAX_DRIVE_PERCENT + "]: " + drivePercent);
        }
        this.drivePercent = drivePercent;
    }

    @ProcessorParam(id = 2, name = "Mix",
            min = MIN_MIX_PERCENT, max = MAX_MIX_PERCENT, defaultValue = 25.0, unit = "%")
    public double getMixPercent() {
        return mixPercent;
    }

    /**
     * Sets the wet/dry mix percentage.
     *
     * @param mixPercent mix in percent, in [0, 100]
     * @throws IllegalArgumentException if out of range
     */
    public void setMixPercent(double mixPercent) {
        if (mixPercent < MIN_MIX_PERCENT || mixPercent > MAX_MIX_PERCENT) {
            throw new IllegalArgumentException("mixPercent must be in ["
                    + MIN_MIX_PERCENT + ", " + MAX_MIX_PERCENT + "]: " + mixPercent);
        }
        this.mixPercent = mixPercent;
    }

    @ProcessorParam(id = 3, name = "Output Gain",
            min = MIN_OUTPUT_GAIN_DB, max = MAX_OUTPUT_GAIN_DB, defaultValue = 0.0, unit = "dB")
    public double getOutputGainDb() {
        return outputGainDb;
    }

    /**
     * Sets the output trim gain in dB.
     *
     * @param outputGainDb output gain in dB, in
     *                     [{@value #MIN_OUTPUT_GAIN_DB}, {@value #MAX_OUTPUT_GAIN_DB}]
     * @throws IllegalArgumentException if out of range
     */
    public void setOutputGainDb(double outputGainDb) {
        if (outputGainDb < MIN_OUTPUT_GAIN_DB || outputGainDb > MAX_OUTPUT_GAIN_DB) {
            throw new IllegalArgumentException("outputGainDb must be in ["
                    + MIN_OUTPUT_GAIN_DB + ", " + MAX_OUTPUT_GAIN_DB + "]: " + outputGainDb);
        }
        this.outputGainDb = outputGainDb;
    }

    /** Returns the current harmonic-character mode. */
    public Mode getMode() {
        return mode;
    }

    /**
     * Sets the harmonic-character mode.
     *
     * @param mode the mode; must not be {@code null}
     */
    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }
}
