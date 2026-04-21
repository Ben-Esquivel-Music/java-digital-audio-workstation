package com.benesquivelmusic.daw.core.dsp.dynamics;

import com.benesquivelmusic.daw.core.dsp.GainReductionProvider;
import com.benesquivelmusic.daw.core.mixer.InsertEffect;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

import java.util.Objects;

/**
 * SSL-style bus compressor — a feedforward VCA mix-bus compressor tuned for
 * gentle, program-dependent gain reduction.
 *
 * <p>Loosely modeled on the SSL 4000 G bus compressor (a "mix glue" processor),
 * this implementation provides:</p>
 * <ul>
 *   <li>Stepped ratios (1.5, 2, 4, 10) and stepped attacks/releases matching
 *       the classic hardware unit's switch positions.</li>
 *   <li>An {@code AUTO} release mode that makes the release time program-dependent
 *       — transients release faster than sustained compression, for a natural
 *       "breathing" behaviour.</li>
 *   <li>A wide soft knee (quadratic, ~10&nbsp;dB) for smooth, analog-style
 *       threshold transitions.</li>
 *   <li>An optional {@code DRIVE} harmonic-coloration stage that adds gentle
 *       2nd- and 3rd-order harmonics via a clipped cubic polynomial
 *       waveshaper.</li>
 *   <li>A dry/wet {@code MIX} control for parallel ("New York") compression.</li>
 *   <li>External sidechain via {@link SidechainAwareProcessor}.</li>
 *   <li>Gain-reduction metering via {@link GainReductionProvider} for UI meters.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>The {@code process} methods are annotated {@link RealTimeSafe} — they do
 * not allocate or take locks. Parameter setters are safe to call from a UI
 * thread: scalar writes are visible to the audio thread on the next buffer.</p>
 */
@InsertEffect(type = "BUS_COMPRESSOR", displayName = "Bus Compressor")
public final class BusCompressorProcessor implements SidechainAwareProcessor, GainReductionProvider {

    /** Standard SSL bus-comp ratio positions. */
    public static final double[] RATIO_STEPS = {1.5, 2.0, 4.0, 10.0};

    /** Standard SSL bus-comp attack positions (ms). */
    public static final double[] ATTACK_STEPS_MS = {0.1, 0.3, 1.0, 3.0, 10.0, 30.0};

    /** Standard SSL bus-comp fixed release positions (s). An additional
     *  {@link #releaseAuto} flag selects the program-dependent AUTO release. */
    public static final double[] RELEASE_STEPS_S = {0.1, 0.3, 0.6, 1.2};

    /** Fixed SSL-style soft-knee width in dB (total width; halfKnee = {@value}/2). */
    private static final double KNEE_DB = 10.0;

    /** Floor for log-domain envelope to avoid log(0). */
    private static final double MIN_DB = -120.0;

    private final int channels;
    private double sampleRate;

    // User-facing parameters
    private double thresholdDb   = -10.0;
    private double ratio         = 4.0;
    private double attackMs      = 10.0;
    private double releaseS      = 0.6;
    private boolean releaseAuto  = false;
    private double makeupGainDb  = 0.0;
    private double mix           = 1.0;   // 0..1 (wet amount)
    private boolean drive        = false;

    // Derived envelope coefficients
    private double attackCoeff;
    private double releaseCoeff;
    /** AUTO-release fast coefficient (~100 ms), cached on sample-rate change. */
    private double autoFastReleaseCoeff;
    /** AUTO-release slow coefficient (~1.2 s), cached on sample-rate change. */
    private double autoSlowReleaseCoeff;

    /** AUTO-release fast time constant (ms). */
    private static final double AUTO_FAST_RELEASE_MS = 100.0;
    /** AUTO-release slow time constant (ms). */
    private static final double AUTO_SLOW_RELEASE_MS = 1200.0;

    // Per-audio-thread state
    private double envelopeDb;
    private double currentGainReductionDb;
    /** Low-pass of recent gain reduction; drives AUTO release. */
    private double grSlowDb;
    /** Most-recent detection-source input peak level (dBFS). */
    private double lastInputLevelDb   = Double.NEGATIVE_INFINITY;
    /** Most-recent output peak level across channels (dBFS). */
    private double lastOutputLevelDb  = Double.NEGATIVE_INFINITY;

    /**
     * Creates a bus compressor with SSL-style defaults (threshold -10 dB,
     * ratio 4:1, attack 10 ms, release 0.6 s).
     *
     * @param channels   number of audio channels (must be &gt; 0)
     * @param sampleRate audio sample rate in Hz (must be &gt; 0)
     */
    public BusCompressorProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.envelopeDb = MIN_DB;
        this.grSlowDb = 0.0;
        recalculateCoefficients();
    }

    // ── AudioProcessor ───────────────────────────────────────────────────────

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        processInternal(inputBuffer, inputBuffer, outputBuffer, numFrames);
    }

    @RealTimeSafe
    @Override
    public void processSidechain(float[][] inputBuffer, float[][] sidechainBuffer,
                                 float[][] outputBuffer, int numFrames) {
        processInternal(inputBuffer, sidechainBuffer, outputBuffer, numFrames);
    }

    private void processInternal(float[][] inputBuffer, float[][] detectionBuffer,
                                 float[][] outputBuffer, int numFrames) {
        double makeupLinear = dbToLinear(makeupGainDb);
        double wet = mix;
        double dry = 1.0 - mix;
        int detCh = Math.min(channels, detectionBuffer.length);
        int outCh = Math.min(channels, inputBuffer.length);
        double peakInLevel = 0.0;
        double peakOutLevel = 0.0;

        for (int frame = 0; frame < numFrames; frame++) {
            // Peak-style detection across detection-source channels
            double level = 0.0;
            for (int ch = 0; ch < detCh; ch++) {
                double s = Math.abs(detectionBuffer[ch][frame]);
                if (s > level) level = s;
            }
            if (level > peakInLevel) peakInLevel = level;
            double inputDb = (level > 0) ? 20.0 * Math.log10(level) : MIN_DB;

            // Log-domain envelope: fast attack when rising, release coefficient otherwise
            double coeff;
            if (inputDb > envelopeDb) {
                coeff = attackCoeff;
            } else if (releaseAuto) {
                // Program-dependent release: blend fast and slow release based on
                // how long the compressor has been gain-reducing. grSlowDb tracks
                // long-term reduction; a larger |grSlowDb| slows release, so
                // sustained programme material releases slowly while transients
                // release quickly — the classic SSL "breathing" behaviour.
                double weight = Math.min(1.0, Math.abs(grSlowDb) / 6.0);
                coeff = autoFastReleaseCoeff + (autoSlowReleaseCoeff - autoFastReleaseCoeff) * weight;
            } else {
                coeff = releaseCoeff;
            }
            envelopeDb = coeff * envelopeDb + (1.0 - coeff) * inputDb;

            // Gain computer with wide soft knee
            double gainReductionDb = computeGainReduction(envelopeDb);
            currentGainReductionDb = gainReductionDb;
            // Long-term GR tracker — very slow low-pass used by AUTO release.
            grSlowDb = 0.9995 * grSlowDb + 0.0005 * gainReductionDb;

            double compGain = dbToLinear(gainReductionDb) * makeupLinear;

            // Apply compression + optional DRIVE coloration, then dry/wet mix
            for (int ch = 0; ch < outCh; ch++) {
                float in = inputBuffer[ch][frame];
                double processed = in * compGain;
                if (drive) {
                    processed = saturate(processed);
                }
                double out = dry * in + wet * processed;
                outputBuffer[ch][frame] = (float) out;
                double absOut = Math.abs(out);
                if (absOut > peakOutLevel) peakOutLevel = absOut;
            }
        }
        lastInputLevelDb  = (peakInLevel  > 0) ? 20.0 * Math.log10(peakInLevel)  : Double.NEGATIVE_INFINITY;
        lastOutputLevelDb = (peakOutLevel > 0) ? 20.0 * Math.log10(peakOutLevel) : Double.NEGATIVE_INFINITY;
    }

    @RealTimeSafe
    @Override
    public void processDouble(double[][] inputBuffer, double[][] outputBuffer, int numFrames) {
        double makeupLinear = dbToLinear(makeupGainDb);
        double wet = mix;
        double dry = 1.0 - mix;
        int detCh = Math.min(channels, inputBuffer.length);
        int outCh = detCh;
        double peakInLevel = 0.0;
        double peakOutLevel = 0.0;

        for (int frame = 0; frame < numFrames; frame++) {
            double level = 0.0;
            for (int ch = 0; ch < detCh; ch++) {
                double s = Math.abs(inputBuffer[ch][frame]);
                if (s > level) level = s;
            }
            if (level > peakInLevel) peakInLevel = level;
            double inputDb = (level > 0) ? 20.0 * Math.log10(level) : MIN_DB;

            double coeff;
            if (inputDb > envelopeDb) {
                coeff = attackCoeff;
            } else if (releaseAuto) {
                double weight = Math.min(1.0, Math.abs(grSlowDb) / 6.0);
                coeff = autoFastReleaseCoeff + (autoSlowReleaseCoeff - autoFastReleaseCoeff) * weight;
            } else {
                coeff = releaseCoeff;
            }
            envelopeDb = coeff * envelopeDb + (1.0 - coeff) * inputDb;

            double gainReductionDb = computeGainReduction(envelopeDb);
            currentGainReductionDb = gainReductionDb;
            grSlowDb = 0.9995 * grSlowDb + 0.0005 * gainReductionDb;

            double compGain = dbToLinear(gainReductionDb) * makeupLinear;

            for (int ch = 0; ch < outCh; ch++) {
                double in = inputBuffer[ch][frame];
                double processed = in * compGain;
                if (drive) {
                    processed = saturate(processed);
                }
                double out = dry * in + wet * processed;
                outputBuffer[ch][frame] = out;
                double absOut = Math.abs(out);
                if (absOut > peakOutLevel) peakOutLevel = absOut;
            }
        }
        lastInputLevelDb  = (peakInLevel  > 0) ? 20.0 * Math.log10(peakInLevel)  : Double.NEGATIVE_INFINITY;
        lastOutputLevelDb = (peakOutLevel > 0) ? 20.0 * Math.log10(peakOutLevel) : Double.NEGATIVE_INFINITY;
    }

    /**
     * SSL-style soft-knee gain computer. Returns a non-positive value in dB.
     */
    private double computeGainReduction(double inputDb) {
        double halfKnee = KNEE_DB / 2.0;
        double over = inputDb - thresholdDb;

        if (over <= -halfKnee) {
            return 0.0;
        }
        double slope = 1.0 - 1.0 / ratio;
        if (over >= halfKnee) {
            return -over * slope;
        }
        // Quadratic knee: 0 at over=-halfKnee, -halfKnee*slope at over=+halfKnee
        double x = over + halfKnee; // in [0, knee]
        return -(x * x) / (2.0 * KNEE_DB) * slope;
    }

    /**
     * Cubic-polynomial "DRIVE" saturator that adds gentle 2nd- and 3rd-order
     * harmonics — {@code y = x - a·x² - b·x³}, clipped to ±1.
     * Coefficients are small so the null-sum transfer is almost linear near zero.
     */
    private static double saturate(double x) {
        // Soft, asymmetric saturation for analog-style harmonics
        double x2 = x * x;
        double y = x - 0.05 * x2 - 0.15 * x * x2;
        if (y > 1.0) y = 1.0;
        else if (y < -1.0) y = -1.0;
        return y;
    }

    private static double dbToLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    // ── GainReductionProvider ───────────────────────────────────────────────

    @Override
    public double getGainReductionDb() {
        return currentGainReductionDb;
    }

    /**
     * Returns an immutable {@link PluginMeterSnapshot} capturing the current
     * gain-reduction reading together with the most-recently-measured peak
     * input and output levels. Intended for UI consumption (e.g., driving a
     * needle-style VU meter in the plugin view).
     *
     * <p>This method is allocation-friendly: a single record is created per
     * call and no audio-thread mutable state is allocated during processing.
     * Call from the UI thread.</p>
     */
    public PluginMeterSnapshot getMeterSnapshot() {
        return new PluginMeterSnapshot(
                currentGainReductionDb,
                lastInputLevelDb,
                lastOutputLevelDb);
    }

    // ── AudioProcessor housekeeping ─────────────────────────────────────────

    @Override
    public void reset() {
        envelopeDb = MIN_DB;
        currentGainReductionDb = 0.0;
        grSlowDb = 0.0;
        lastInputLevelDb  = Double.NEGATIVE_INFINITY;
        lastOutputLevelDb = Double.NEGATIVE_INFINITY;
    }

    @Override
    public boolean supportsDouble() {
        return true;
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    // ── Parameters ──────────────────────────────────────────────────────────

    @ProcessorParam(id = 0, name = "Threshold", min = -40.0, max = 0.0,
            defaultValue = -10.0, unit = "dB")
    public double getThresholdDb() { return thresholdDb; }
    public void setThresholdDb(double thresholdDb) {
        if (thresholdDb < -40.0 || thresholdDb > 0.0) {
            throw new IllegalArgumentException("thresholdDb must be in [-40, 0]: " + thresholdDb);
        }
        this.thresholdDb = thresholdDb;
    }

    @ProcessorParam(id = 1, name = "Ratio", min = 1.5, max = 10.0, defaultValue = 4.0)
    public double getRatio() { return ratio; }
    /**
     * Sets the ratio to the nearest SSL stepped value in {@link #RATIO_STEPS}.
     */
    public void setRatio(double ratio) {
        if (ratio < 1.0) {
            throw new IllegalArgumentException("ratio must be >= 1.0: " + ratio);
        }
        this.ratio = snap(ratio, RATIO_STEPS);
    }

    @ProcessorParam(id = 2, name = "Attack", min = 0.1, max = 30.0,
            defaultValue = 10.0, unit = "ms")
    public double getAttackMs() { return attackMs; }
    /**
     * Sets the attack to the nearest SSL stepped value in {@link #ATTACK_STEPS_MS}.
     */
    public void setAttackMs(double attackMs) {
        if (attackMs <= 0) {
            throw new IllegalArgumentException("attackMs must be > 0: " + attackMs);
        }
        this.attackMs = snap(attackMs, ATTACK_STEPS_MS);
        recalculateCoefficients();
    }

    @ProcessorParam(id = 3, name = "Release", min = 0.1, max = 1.2,
            defaultValue = 0.6, unit = "s")
    public double getReleaseS() { return releaseS; }
    /**
     * Sets the release to the nearest SSL stepped value in {@link #RELEASE_STEPS_S}.
     * Does not affect the {@link #isReleaseAuto() AUTO} flag.
     */
    public void setReleaseS(double releaseS) {
        if (releaseS <= 0) {
            throw new IllegalArgumentException("releaseS must be > 0: " + releaseS);
        }
        this.releaseS = snap(releaseS, RELEASE_STEPS_S);
        recalculateCoefficients();
    }

    public boolean isReleaseAuto() { return releaseAuto; }
    public void setReleaseAuto(boolean releaseAuto) { this.releaseAuto = releaseAuto; }

    @ProcessorParam(id = 4, name = "Makeup Gain", min = 0.0, max = 24.0,
            defaultValue = 0.0, unit = "dB")
    public double getMakeupGainDb() { return makeupGainDb; }
    public void setMakeupGainDb(double makeupGainDb) {
        if (makeupGainDb < 0.0 || makeupGainDb > 24.0) {
            throw new IllegalArgumentException("makeupGainDb must be in [0, 24]: " + makeupGainDb);
        }
        this.makeupGainDb = makeupGainDb;
    }

    @ProcessorParam(id = 5, name = "Mix", min = 0.0, max = 1.0, defaultValue = 1.0)
    public double getMix() { return mix; }
    /**
     * Sets the dry/wet mix. {@code 0.0} passes the input through bit-exact
     * (the "null test"); {@code 1.0} is fully compressed signal.
     *
     * @param mix the wet amount in {@code [0, 1]}
     */
    public void setMix(double mix) {
        if (mix < 0.0 || mix > 1.0) {
            throw new IllegalArgumentException("mix must be in [0, 1]: " + mix);
        }
        this.mix = mix;
    }

    public boolean isDrive() { return drive; }
    public void setDrive(boolean drive) { this.drive = drive; }

    /** Returns the sample rate this processor was configured for. */
    public double getSampleRate() { return sampleRate; }

    // ── Internals ──────────────────────────────────────────────────────────

    private void recalculateCoefficients() {
        attackCoeff  = DynamicsCoefficients.envelope(attackMs,      sampleRate);
        releaseCoeff = DynamicsCoefficients.envelope(releaseS * 1000.0, sampleRate);
        autoFastReleaseCoeff = DynamicsCoefficients.envelope(AUTO_FAST_RELEASE_MS, sampleRate);
        autoSlowReleaseCoeff = DynamicsCoefficients.envelope(AUTO_SLOW_RELEASE_MS, sampleRate);
    }

    /** Returns the step value nearest to {@code value}. */
    private static double snap(double value, double[] steps) {
        Objects.requireNonNull(steps, "steps");
        double best = steps[0];
        double bestDist = Math.abs(value - best);
        for (int i = 1; i < steps.length; i++) {
            double d = Math.abs(value - steps[i]);
            if (d < bestDist) { bestDist = d; best = steps[i]; }
        }
        return best;
    }
}
