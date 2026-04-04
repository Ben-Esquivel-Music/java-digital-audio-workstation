package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

/**
 * Virtual analog distortion processor that models non-ideal operational
 * amplifier behavior — including slew-rate limiting, input offset voltage,
 * and finite open-loop gain.
 *
 * <p>These non-idealities produce the characteristic warmth and soft
 * saturation of analog distortion circuits that pure mathematical
 * waveshaping cannot replicate.</p>
 *
 * <h2>Signal Path</h2>
 * <ol>
 *   <li><b>Drive</b> — input gain stage (dB)</li>
 *   <li><b>Finite open-loop gain</b> — frequency-dependent gain rolloff
 *       using a first-order lowpass in the feedback path, modelling the
 *       op-amp's gain-bandwidth product limitation</li>
 *   <li><b>Slew-rate limiter</b> — clamps the sample-to-sample rate of
 *       change, emulating the maximum output voltage rate of real op-amps</li>
 *   <li><b>Diode clipper</b> — {@code sinh}-based approximation of the
 *       Shockley diode equation with configurable asymmetry</li>
 *   <li><b>Tone control</b> — post-distortion tilt EQ for tonal shaping</li>
 *   <li><b>Output level</b> — final gain stage (dB)</li>
 * </ol>
 *
 * <h2>AES Research References</h2>
 * <ul>
 *   <li>"Non-Ideal Operational Amplifier Emulation in Digital Model of Analog
 *       Distortion Effect Pedal" (2022)</li>
 *   <li>"Sound Matching an Analogue Levelling Amplifier Using the
 *       Newton-Raphson Method" (2025)</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class AnalogDistortionProcessor implements AudioProcessor {

    private static final double TWO_PI = 2.0 * Math.PI;

    // Default parameter values
    private static final double DEFAULT_DRIVE_DB = 0.0;
    private static final double DEFAULT_TONE = 0.0;
    private static final double DEFAULT_SLEW_RATE = 13.0;   // V/µs — typical TL072
    private static final double DEFAULT_ASYMMETRY = 0.0;
    private static final double DEFAULT_OUTPUT_LEVEL_DB = 0.0;

    // Op-amp model constants
    private static final double OPEN_LOOP_GAIN_DC = 100_000.0; // 100 dB
    private static final double DOMINANT_POLE_HZ = 10.0;       // ~10 Hz
    private static final double GBW = OPEN_LOOP_GAIN_DC * DOMINANT_POLE_HZ; // ~1 MHz

    // Diode clipper hardness — controls the transition from linear to
    // saturated behavior in the sinh-based Shockley diode model.
    // Derived from thermal voltage: 1/(2·Vt) ≈ 19, but we use a moderate
    // value that produces musically useful saturation in the normalised
    // [-1, 1] audio range.
    private static final double DIODE_HARDNESS = 5.0;

    private final int channels;
    private final double sampleRate;

    // Parameters
    private double driveDb;
    private double tone;
    private double slewRate;
    private double asymmetry;
    private double outputLevelDb;

    // Per-channel state for slew-rate limiting
    private double[] slewState;

    // Per-channel state for the open-loop gain first-order lowpass
    private double[] feedbackState;

    // Per-channel tone control (biquad high-shelf)
    private BiquadFilter[] toneFilters;

    // Derived coefficients (recomputed when parameters change)
    private double driveLinear;
    private double outputLinear;
    private double maxSlewPerSample;
    private double feedbackCoeff;  // lowpass coefficient for finite open-loop gain

    /**
     * Creates an analog distortion processor with default settings.
     *
     * <p>Defaults: 0 dB drive, 0 tone (neutral), 13 V/µs slew rate,
     * 0 asymmetry, 0 dB output level.</p>
     *
     * @param channels   number of audio channels (must be positive)
     * @param sampleRate the sample rate in Hz (must be positive)
     * @throws IllegalArgumentException if channels or sampleRate is not positive
     */
    public AnalogDistortionProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;

        this.driveDb = DEFAULT_DRIVE_DB;
        this.tone = DEFAULT_TONE;
        this.slewRate = DEFAULT_SLEW_RATE;
        this.asymmetry = DEFAULT_ASYMMETRY;
        this.outputLevelDb = DEFAULT_OUTPUT_LEVEL_DB;

        this.slewState = new double[channels];
        this.feedbackState = new double[channels];
        this.toneFilters = new BiquadFilter[channels];
        for (int ch = 0; ch < channels; ch++) {
            toneFilters[ch] = BiquadFilter.create(
                    BiquadFilter.FilterType.HIGH_SHELF, sampleRate, 800.0, 0.707, 0.0);
        }

        updateDerivedCoefficients();
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);
        double drive = driveLinear;
        double outGain = outputLinear;
        double maxSlew = maxSlewPerSample;
        double fbCoeff = feedbackCoeff;
        double asym = asymmetry;

        for (int ch = 0; ch < activeCh; ch++) {
            double slew = slewState[ch];
            double fb = feedbackState[ch];

            for (int i = 0; i < numFrames; i++) {
                double x = inputBuffer[ch][i] * drive;

                // 1. Finite open-loop gain: first-order lowpass models the
                //    closed-loop bandwidth limitation from the op-amp's
                //    gain-bandwidth product. Higher drive → lower bandwidth.
                fb = fb + (1.0 - fbCoeff) * (x - fb);

                // 2. Slew-rate limiting: clamp the rate of change
                double delta = fb - slew;
                if (delta > maxSlew) {
                    fb = slew + maxSlew;
                } else if (delta < -maxSlew) {
                    fb = slew - maxSlew;
                }
                slew = fb;

                // 3. Diode clipper: sinh-based Shockley equation approximation
                //    with configurable asymmetry
                double clipped = diodeClip(fb, asym);

                // 4. Tone control: high-shelf biquad tilt EQ
                float toned = toneFilters[ch].processSample((float) clipped);

                // 5. Output level
                outputBuffer[ch][i] = (float) (toned * outGain);
            }

            slewState[ch] = slew;
            feedbackState[ch] = fb;
        }
    }

    /**
     * Diode-clipper nonlinearity using a {@code sinh}-based approximation
     * of the Shockley diode equation.
     *
     * <p>Models a pair of anti-parallel diodes where the asymmetry parameter
     * shifts the clipping thresholds, producing even-harmonic content
     * characteristic of analog distortion circuits. Uses the inverse
     * hyperbolic sine function {@code arcsinh(x·k)/k} which provides
     * unity gain for small signals and smooth saturation for large signals,
     * matching the transfer characteristic of real diode clippers.</p>
     *
     * @param x    the input sample
     * @param asym the asymmetry offset in [-1, 1]
     * @return the clipped sample
     */
    private static double diodeClip(double x, double asym) {
        // Asymmetry shifts the operating point of the forward/reverse diode
        // pair, introducing even-order harmonics.
        double offset = asym * 0.2;
        double shifted = x + offset;

        // arcsinh(x·k)/k: unity gain at zero, smooth saturation for large |x|.
        // The hardness parameter k controls how quickly saturation onset occurs.
        double k = DIODE_HARDNESS;
        double scaled = shifted * k;
        double clipped = Math.log(scaled + Math.sqrt(scaled * scaled + 1.0)) / k;

        // Remove DC offset introduced by asymmetry
        if (offset != 0.0) {
            double offScaled = offset * k;
            clipped -= Math.log(offScaled + Math.sqrt(offScaled * offScaled + 1.0)) / k;
        }

        return clipped;
    }

    // --- Parameter accessors ---

    /** Returns the drive gain in dB. */
    public double getDriveDb() {
        return driveDb;
    }

    /**
     * Sets the drive gain in dB.
     *
     * @param driveDb the drive gain
     */
    public void setDriveDb(double driveDb) {
        this.driveDb = driveDb;
        this.driveLinear = Math.pow(10.0, driveDb / 20.0);
        updateFeedbackCoefficient();
    }

    /** Returns the tone control value in [-1, 1]. */
    public double getTone() {
        return tone;
    }

    /**
     * Sets the tone control. Negative values darken the tone (low-pass tilt),
     * positive values brighten it (high-pass tilt), and zero is neutral.
     *
     * @param tone the tone value in [-1, 1]
     * @throws IllegalArgumentException if tone is outside [-1, 1]
     */
    public void setTone(double tone) {
        if (tone < -1.0 || tone > 1.0) {
            throw new IllegalArgumentException("tone must be in [-1, 1]: " + tone);
        }
        this.tone = tone;
        updateToneFilters();
    }

    /** Returns the slew rate in V/µs. */
    public double getSlewRate() {
        return slewRate;
    }

    /**
     * Sets the slew rate in V/µs. Lower values produce more pronounced
     * slew-rate distortion, adding high-frequency compression and warmth.
     *
     * @param slewRate the slew rate in V/µs (must be positive)
     * @throws IllegalArgumentException if slewRate is not positive
     */
    public void setSlewRate(double slewRate) {
        if (slewRate <= 0) {
            throw new IllegalArgumentException("slewRate must be positive: " + slewRate);
        }
        this.slewRate = slewRate;
        updateSlewCoefficient();
    }

    /** Returns the asymmetry value in [-1, 1]. */
    public double getAsymmetry() {
        return asymmetry;
    }

    /**
     * Sets the diode clipper asymmetry. Non-zero values shift the
     * forward/reverse clipping thresholds apart, introducing even-harmonic
     * distortion.
     *
     * @param asymmetry the asymmetry in [-1, 1]
     * @throws IllegalArgumentException if asymmetry is outside [-1, 1]
     */
    public void setAsymmetry(double asymmetry) {
        if (asymmetry < -1.0 || asymmetry > 1.0) {
            throw new IllegalArgumentException("asymmetry must be in [-1, 1]: " + asymmetry);
        }
        this.asymmetry = asymmetry;
    }

    /** Returns the output level in dB. */
    public double getOutputLevelDb() {
        return outputLevelDb;
    }

    /**
     * Sets the output level in dB.
     *
     * @param outputLevelDb the output level gain
     */
    public void setOutputLevelDb(double outputLevelDb) {
        this.outputLevelDb = outputLevelDb;
        this.outputLinear = Math.pow(10.0, outputLevelDb / 20.0);
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(slewState, 0.0);
        java.util.Arrays.fill(feedbackState, 0.0);
        for (BiquadFilter filter : toneFilters) {
            filter.reset();
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

    // --- Coefficient computation ---

    private void updateDerivedCoefficients() {
        this.driveLinear = Math.pow(10.0, driveDb / 20.0);
        this.outputLinear = Math.pow(10.0, outputLevelDb / 20.0);
        updateSlewCoefficient();
        updateFeedbackCoefficient();
        updateToneFilters();
    }

    /**
     * Computes the maximum sample-to-sample change allowed by the slew-rate
     * limiter. The slew rate is specified in V/µs; we convert to normalised
     * units per sample assuming a ±10V op-amp output swing.
     */
    private void updateSlewCoefficient() {
        // slewRate is in V/µs. Convert to V/sample:
        // V/sample = slewRate * 1e6 / sampleRate  (µs → seconds)
        double voltsPerSample = (slewRate * 1e6) / sampleRate;
        // Normalise to [-1, 1] range (assuming ±10V swing)
        this.maxSlewPerSample = voltsPerSample / 10.0;
    }

    /**
     * Computes the lowpass coefficient for the finite open-loop gain model.
     *
     * <p>Models the gain-bandwidth product (GBW) limitation of the op-amp.
     * The closed-loop bandwidth decreases as the drive (closed-loop gain)
     * increases: {@code f_closed = GBW / gain}. At low drive settings the
     * filter is transparent; at high drive it produces a subtle HF rolloff
     * characteristic of real op-amp circuits.</p>
     */
    private void updateFeedbackCoefficient() {
        double closedLoopBandwidth = GBW / Math.max(driveLinear, 1.0);
        // Clamp to Nyquist — above Nyquist the filter has no effect
        closedLoopBandwidth = Math.min(closedLoopBandwidth, sampleRate / 2.0);
        double wc = TWO_PI * closedLoopBandwidth / sampleRate;
        this.feedbackCoeff = Math.exp(-wc);
    }

    /**
     * Recalculates per-channel tone control filters.
     *
     * <p>Uses a high-shelf biquad centred at 800 Hz. The tone parameter
     * in [-1, 1] maps to a gain range of [-6, +6] dB.</p>
     */
    private void updateToneFilters() {
        double gainDb = tone * 6.0;
        for (int ch = 0; ch < channels; ch++) {
            toneFilters[ch].recalculate(
                    BiquadFilter.FilterType.HIGH_SHELF, sampleRate, 800.0, 0.707, gainDb);
        }
    }
}
