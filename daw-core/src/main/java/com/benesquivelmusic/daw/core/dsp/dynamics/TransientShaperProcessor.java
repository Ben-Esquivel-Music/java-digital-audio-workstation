package com.benesquivelmusic.daw.core.dsp.dynamics;

import com.benesquivelmusic.daw.core.mixer.InsertEffect;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

/**
 * Transient shaper — a level-independent dynamics processor that boosts or
 * suppresses the attack and sustain portions of a signal independently of
 * absolute level (unlike a compressor, it does not care about thresholds).
 *
 * <p>Useful for making a mushy kick punchier, taming a ringy snare, or
 * extending the body of a guitar stab. Inspired by hardware classics such as
 * the SPL Transient Designer and Native Instruments' Transient Master.</p>
 *
 * <h2>Algorithm</h2>
 * <p>Two envelope followers are tracked per audio frame from the rectified
 * detection signal:</p>
 * <ul>
 *   <li><b>Fast</b>: short attack (≈0.1 ms) and short release (≈10 ms) —
 *       tracks the instantaneous level closely.</li>
 *   <li><b>Slow</b>: longer attack (≈15 ms) and longer release (≈200 ms) —
 *       a delayed, smoothed copy of the fast envelope.</li>
 * </ul>
 * <p>During the onset of a transient the fast envelope rises ahead of the
 * slow envelope; the dB difference is positive and is purely a function of
 * the <em>shape</em> of the onset, not of absolute level — so the algorithm
 * is intrinsically level-independent. During the decay tail the slow
 * envelope's longer release lags above the fast envelope, so the inverse
 * difference is positive throughout the sustain region:</p>
 * <pre>
 *   attackDiffDb  = max(0, 20·log10(envFast / envSlow))
 *   sustainDiffDb = max(0, 20·log10(envSlow / envFast))
 *   gainDb        = attackKnob  · attackDiffDb  · SCALE
 *                 + sustainKnob · sustainDiffDb · SCALE
 *                 + outputDb
 * </pre>
 * <p>where {@code attackKnob} and {@code sustainKnob} are normalised into
 * {@code [-1, 1]} (corresponding to {@code [-100%, +100%]}).</p>
 *
 * <h2>Stereo link</h2>
 * <p>The {@link #setChannelLink(double) channel-link} parameter (0..1) blends
 * between independent per-channel detection (0) and summed/linked detection
 * (1). At full link both channels share the same gain trajectory, preserving
 * the stereo image of percussive transients.</p>
 *
 * <h2>Input monitor</h2>
 * <p>When {@link #isInputMonitor() input monitor} is enabled, the output is
 * replaced by an audible representation of the transient detection signal —
 * useful when tuning attack/sustain on isolated material.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@link #process(float[][], float[][], int) process} is annotated
 * {@link RealTimeSafe} — it does not allocate. Parameter setters are safe to
 * invoke from a UI thread; updates become visible to the audio thread on the
 * next buffer.</p>
 */
@InsertEffect(type = "TRANSIENT_SHAPER", displayName = "Transient Shaper")
public final class TransientShaperProcessor implements AudioProcessor {

    // ── Fixed envelope time constants (ms) ──────────────────────────────────
    /** "Fast" follower attack — tight enough to catch sub-millisecond transients. */
    static final double FAST_ATTACK_MS  = 0.1;
    /** "Fast" follower release — short so the ratio between fast and slow collapses
     *  quickly once the transient passes. */
    static final double FAST_RELEASE_MS = 10.0;
    /** "Slow" follower attack — lags the fast follower during the onset transient. */
    static final double SLOW_ATTACK_MS  = 15.0;
    /** "Slow" follower release — long so the slow follower hangs above the fast
     *  follower during the decay tail (the sustain region). */
    static final double SLOW_RELEASE_MS = 200.0;

    /**
     * dB scale factor applied to the difference between the fast and slow
     * envelopes. Calibrated together with {@link #MAX_DIFF_DB} so that ±100 %
     * on a knob produces ±{@code 12} dB of peak modulation on a typical drum
     * transient.
     */
    private static final double KNOB_DB_SCALE = 2.0;

    /**
     * Cap on the per-sample envelope differential in dB. Caps modulation so
     * extreme transients do not produce extreme gain swings, and ensures the
     * algorithm behaves identically across input levels (otherwise a hot
     * signal could clip the output hard while a quiet copy would not).
     */
    private static final double MAX_DIFF_DB = 6.0;

    /** Floor used for log-domain computations to avoid {@code log(0)}. */
    private static final double LEVEL_FLOOR = 1.0e-9;

    private final int channels;
    private double sampleRate;

    // User-facing parameters
    private double attackPercent  = 0.0;   // -100..+100
    private double sustainPercent = 0.0;   // -100..+100
    private double outputDb       = 0.0;   // -12..+12
    private boolean inputMonitor  = false;
    private double channelLink    = 1.0;   // 0..1 (0 = independent, 1 = fully linked)

    // Derived envelope coefficients
    private double fastAttackCoeff;
    private double fastReleaseCoeff;
    private double slowAttackCoeff;
    private double slowReleaseCoeff;

    // Per-channel envelope state (linear-domain rectified)
    private final double[] envFast;
    private final double[] envSlow;

    // Most-recent metering values
    private double lastInputLevelDb  = Double.NEGATIVE_INFINITY;
    private double lastOutputLevelDb = Double.NEGATIVE_INFINITY;
    /** Most-recent transient-detection magnitude in dB (attack diff, last channel). */
    private double lastTransientDb   = 0.0;

    /**
     * Creates a transient shaper with neutral defaults (attack/sustain 0 %,
     * output 0 dB, input monitor off, fully linked stereo detection).
     *
     * @param channels   number of audio channels (must be {@code > 0})
     * @param sampleRate audio sample rate in Hz (must be {@code > 0})
     */
    public TransientShaperProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels   = channels;
        this.sampleRate = sampleRate;
        this.envFast    = new double[channels];
        this.envSlow    = new double[channels];
        recalculateCoefficients();
    }

    // ── AudioProcessor ───────────────────────────────────────────────────────

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        double attackKnob   = attackPercent  / 100.0;
        double sustainKnob  = sustainPercent / 100.0;
        double outputLinear = dbToLinear(outputDb);
        boolean monitor     = inputMonitor;
        double link         = channelLink;
        int chCount         = Math.min(channels, Math.min(inputBuffer.length, outputBuffer.length));

        double peakIn  = 0.0;
        double peakOut = 0.0;
        double lastTrans = 0.0;

        for (int frame = 0; frame < numFrames; frame++) {
            // Compute summed (link) magnitude across active channels.
            double summed = 0.0;
            for (int ch = 0; ch < chCount; ch++) {
                double a = Math.abs(inputBuffer[ch][frame]);
                if (a > summed) summed = a;
                if (a > peakIn) peakIn = a;
            }

            for (int ch = 0; ch < chCount; ch++) {
                double in = inputBuffer[ch][frame];
                double absLocal = Math.abs(in);
                // Stereo-link: blend per-channel and summed magnitude for detection.
                double det = (1.0 - link) * absLocal + link * summed;
                if (det < LEVEL_FLOOR) det = LEVEL_FLOOR;

                // Single envelope pair: fast/slow attack and release. During
                // an attack onset envFast leads envSlow; during the decay tail
                // envSlow's longer release hangs above envFast.
                envFast[ch] = updateEnvelope(envFast[ch], det, fastAttackCoeff, fastReleaseCoeff);
                envSlow[ch] = updateEnvelope(envSlow[ch], det, slowAttackCoeff, slowReleaseCoeff);

                double f = Math.max(envFast[ch], LEVEL_FLOOR);
                double s = Math.max(envSlow[ch], LEVEL_FLOOR);

                // Level-independent differentials in dB — depend only on
                // envelope shape, not on absolute signal level. Bounded so
                // the ±100 % gain swing is consistent across signal levels.
                double diffDb        = 20.0 * Math.log10(f / s);
                double attackDiffDb  = Math.min(MAX_DIFF_DB, Math.max(0.0,  diffDb));
                double sustainDiffDb = Math.min(MAX_DIFF_DB, Math.max(0.0, -diffDb));
                lastTrans = attackDiffDb;

                double gainDb = attackKnob  * attackDiffDb  * KNOB_DB_SCALE
                              + sustainKnob * sustainDiffDb * KNOB_DB_SCALE;
                double gainLinear = dbToLinear(gainDb) * outputLinear;

                double out;
                if (monitor) {
                    // Audible representation of the transient detection envelope:
                    // the rectified attack-difference (dB) is mapped to amplitude
                    // and given the original sample's sign so it stays musical.
                    double sign = (in >= 0.0) ? 1.0 : -1.0;
                    double amp  = Math.min(1.0, attackDiffDb / 12.0);
                    out = sign * amp * outputLinear;
                } else {
                    out = in * gainLinear;
                    if (out >  1.0) out =  1.0;
                    else if (out < -1.0) out = -1.0;
                }
                outputBuffer[ch][frame] = (float) out;
                double absOut = Math.abs(out);
                if (absOut > peakOut) peakOut = absOut;
            }
        }

        lastInputLevelDb   = (peakIn  > 0) ? 20.0 * Math.log10(peakIn)  : Double.NEGATIVE_INFINITY;
        lastOutputLevelDb  = (peakOut > 0) ? 20.0 * Math.log10(peakOut) : Double.NEGATIVE_INFINITY;
        lastTransientDb    = lastTrans;
    }

    /** One-pole envelope follower: rises with {@code attackCoeff}, falls with {@code releaseCoeff}. */
    private static double updateEnvelope(double current, double target,
                                         double attackCoeff, double releaseCoeff) {
        double coeff = (target > current) ? attackCoeff : releaseCoeff;
        return coeff * current + (1.0 - coeff) * target;
    }

    private static double dbToLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    // ── AudioProcessor housekeeping ─────────────────────────────────────────

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            envFast[ch] = 0.0;
            envSlow[ch] = 0.0;
        }
        lastInputLevelDb  = Double.NEGATIVE_INFINITY;
        lastOutputLevelDb = Double.NEGATIVE_INFINITY;
        lastTransientDb   = 0.0;
    }

    @Override
    public int getInputChannelCount()  { return channels; }
    @Override
    public int getOutputChannelCount() { return channels; }

    /** Returns the sample rate this processor was configured for. */
    public double getSampleRate() { return sampleRate; }

    // ── Parameters ──────────────────────────────────────────────────────────

    @ProcessorParam(id = 0, name = "Attack",  min = -100.0, max = 100.0,
            defaultValue = 0.0, unit = "%")
    public double getAttackPercent() { return attackPercent; }
    public void setAttackPercent(double attackPercent) {
        if (attackPercent < -100.0 || attackPercent > 100.0) {
            throw new IllegalArgumentException(
                    "attackPercent must be in [-100, 100]: " + attackPercent);
        }
        this.attackPercent = attackPercent;
    }

    @ProcessorParam(id = 1, name = "Sustain", min = -100.0, max = 100.0,
            defaultValue = 0.0, unit = "%")
    public double getSustainPercent() { return sustainPercent; }
    public void setSustainPercent(double sustainPercent) {
        if (sustainPercent < -100.0 || sustainPercent > 100.0) {
            throw new IllegalArgumentException(
                    "sustainPercent must be in [-100, 100]: " + sustainPercent);
        }
        this.sustainPercent = sustainPercent;
    }

    @ProcessorParam(id = 2, name = "Output",  min = -12.0, max = 12.0,
            defaultValue = 0.0, unit = "dB")
    public double getOutputDb() { return outputDb; }
    public void setOutputDb(double outputDb) {
        if (outputDb < -12.0 || outputDb > 12.0) {
            throw new IllegalArgumentException(
                    "outputDb must be in [-12, 12]: " + outputDb);
        }
        this.outputDb = outputDb;
    }

    public boolean isInputMonitor() { return inputMonitor; }
    public void setInputMonitor(boolean inputMonitor) { this.inputMonitor = inputMonitor; }

    @ProcessorParam(id = 3, name = "Channel Link", min = 0.0, max = 1.0,
            defaultValue = 1.0)
    public double getChannelLink() { return channelLink; }
    /**
     * Sets the stereo detection link amount.
     *
     * @param channelLink {@code 0.0} = fully independent per-channel detection,
     *                    {@code 1.0} = fully linked (summed) detection
     */
    public void setChannelLink(double channelLink) {
        if (channelLink < 0.0 || channelLink > 1.0) {
            throw new IllegalArgumentException(
                    "channelLink must be in [0, 1]: " + channelLink);
        }
        this.channelLink = channelLink;
    }

    // ── Metering ────────────────────────────────────────────────────────────

    /**
     * Returns an immutable {@link PluginMeterSnapshot} suitable for driving a
     * UI meter. The "gain reduction" field carries the most-recent transient
     * detection magnitude in dB (positive — interpreted as transient energy
     * rather than reduction); input/output fields carry the most-recent peak
     * levels in dBFS.
     */
    public PluginMeterSnapshot getMeterSnapshot() {
        return new PluginMeterSnapshot(lastTransientDb, lastInputLevelDb, lastOutputLevelDb);
    }

    /** Returns the most-recently measured transient-detection magnitude in dB. */
    public double getTransientDetectionDb() { return lastTransientDb; }

    // ── Internals ──────────────────────────────────────────────────────────

    private void recalculateCoefficients() {
        fastAttackCoeff  = DynamicsCoefficients.envelope(FAST_ATTACK_MS,  sampleRate);
        fastReleaseCoeff = DynamicsCoefficients.envelope(FAST_RELEASE_MS, sampleRate);
        slowAttackCoeff  = DynamicsCoefficients.envelope(SLOW_ATTACK_MS,  sampleRate);
        slowReleaseCoeff = DynamicsCoefficients.envelope(SLOW_RELEASE_MS, sampleRate);
    }
}
