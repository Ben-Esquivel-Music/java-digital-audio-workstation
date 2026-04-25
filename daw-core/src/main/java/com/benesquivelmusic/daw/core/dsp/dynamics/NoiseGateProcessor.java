package com.benesquivelmusic.daw.core.dsp.dynamics;

import com.benesquivelmusic.daw.core.dsp.BiquadFilter;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.SidechainAwareProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

/**
 * Feature-complete noise gate with hysteresis, lookahead, and a sidechain
 * bandpass filter.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>The detection source (main input by default; the external sidechain
 *       buffer when {@link #processSidechain} is called and
 *       {@code sidechainEnabled} is true) is rectified to a peak amplitude
 *       per sample.</li>
 *   <li>When the sidechain bandpass filter is active, the detection signal
 *       is filtered through a {@link BiquadFilter#BAND_PASS BAND_PASS} biquad
 *       so the gate triggers only on in-band content (e.g., 50&nbsp;Hz–100&nbsp;Hz
 *       for a kick-mic gate).</li>
 *   <li>A 5-state machine ({@code CLOSED → ATTACK → OPEN → HOLD → RELEASE})
 *       drives the envelope. Hysteresis is implemented as two thresholds:
 *       the gate <b>opens</b> at {@code thresholdDb} and only <b>closes</b>
 *       once the detection signal falls below
 *       {@code thresholdDb − hysteresisDb}. This prevents chattering on
 *       signals that hover near the threshold.</li>
 *   <li>A delay line on the main input implements <b>lookahead</b>: the
 *       detector sees the signal {@code lookaheadMs} ahead of the gated
 *       audio, so the gate is fully open by the time the transient arrives
 *       at the output.</li>
 *   <li>The output gain interpolates between {@code rangeLinear}
 *       (gate fully closed; floor depth in dB) and {@code 1.0}
 *       (gate fully open) according to the envelope.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>{@link #process} and {@link #processSidechain} are
 * {@link RealTimeSafe @RealTimeSafe}: no allocation, no locks. Parameter
 * setters are safe to call from a UI thread; updates become visible to the
 * audio thread on the next buffer (scalar writes for primitives are
 * sufficient for the parameters here).</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class NoiseGateProcessor implements SidechainAwareProcessor {

    /** Public state of the gate, observed via {@link #getMeterSnapshot()}. */
    public enum GateState { CLOSED, ATTACK, OPEN, HOLD, RELEASE }

    /** Maximum lookahead in milliseconds (sized at construction). */
    private static final double MAX_LOOKAHEAD_MS = 10.0;

    private final int channels;
    private final double sampleRate;

    // ── Parameters ────────────────────────────────────────────────────────
    // Parameter setters write to these fields from any thread (typically UI).
    // The audio thread reads them at buffer boundaries; scalar primitive
    // writes are sufficient for the parameters here.
    private double thresholdDb     = -40.0;
    private double hysteresisDb    =   3.0;   // close threshold = open − hysteresis
    private double attackMs        =   1.0;
    private double holdMs          =  50.0;
    private double releaseMs       = 100.0;
    private double rangeDb         = -80.0;
    private double lookaheadMs     =   0.0;
    private boolean sidechainEnabled       = false;
    private volatile double sidechainFilterFreqHz = 80.0;
    private volatile double sidechainFilterQ      = 0.7;
    private boolean sidechainFilterEnabled = true;

    // Coefficients last applied to the bandpass filter (audio-thread owned).
    // The audio thread compares these with the volatile params at the start
    // of each buffer and recalculates only when they change — keeping the
    // BiquadFilter mutation single-threaded.
    private double appliedFilterFreqHz;
    private double appliedFilterQ;

    // ── State ─────────────────────────────────────────────────────────────
    private GateState state    = GateState.CLOSED;
    private double envelope    = 0.0;
    private double attackCoeff;
    private double releaseCoeff;
    private int holdSamples;
    private int holdCounter;

    // Lookahead delay line (per-channel ring buffer).
    private final float[][] lookaheadBuffer;
    private final int lookaheadCapacity;
    private int lookaheadWriteIdx;
    private int lookaheadDelaySamples;

    // Sidechain bandpass filter (single-channel: detection is mono-summed).
    private final BiquadFilter sidechainFilter;

    // Cross-thread meter publication — primitive volatile fields written by
    // the audio thread, read by the UI thread. The immutable
    // {@link MeterSnapshot} record is allocated only when the UI calls
    // {@link #getMeterSnapshot()}, keeping {@code process} allocation-free.
    private volatile GateState publishedState = GateState.CLOSED;
    private volatile double publishedEnvelope = 0.0;
    private volatile double publishedInputDb  = Double.NEGATIVE_INFINITY;
    private volatile double publishedOutputDb = Double.NEGATIVE_INFINITY;

    /**
     * Creates a noise gate.
     *
     * @param channels   number of audio channels (must be {@code > 0})
     * @param sampleRate the sample rate in Hz (must be {@code > 0})
     */
    public NoiseGateProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        // +1 so the maximum lookahead delay (MAX_LOOKAHEAD_MS) is reachable;
        // the read index trails the write index by `delay` samples, so the
        // ring buffer needs `maxDelay + 1` slots to keep them distinct.
        this.lookaheadCapacity = Math.max(2, (int) Math.ceil(MAX_LOOKAHEAD_MS * 0.001 * sampleRate) + 1);
        this.lookaheadBuffer = new float[channels][lookaheadCapacity];
        this.sidechainFilter = BiquadFilter.create(
                BiquadFilter.FilterType.BAND_PASS, sampleRate,
                sidechainFilterFreqHz, sidechainFilterQ, 0.0);
        this.appliedFilterFreqHz = sidechainFilterFreqHz;
        this.appliedFilterQ      = sidechainFilterQ;
        recalculateCoefficients();
    }

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        // No external sidechain → detector reads from the main input.
        processInternal(inputBuffer, inputBuffer, outputBuffer, numFrames, false);
    }

    @RealTimeSafe
    @Override
    public void processSidechain(float[][] inputBuffer, float[][] sidechainBuffer,
                                 float[][] outputBuffer, int numFrames) {
        boolean useExternal = sidechainEnabled && sidechainBuffer != null;
        processInternal(inputBuffer, useExternal ? sidechainBuffer : inputBuffer,
                outputBuffer, numFrames, useExternal);
    }

    private void processInternal(float[][] inputBuffer, float[][] detectionBuffer,
                                 float[][] outputBuffer, int numFrames,
                                 boolean externalSidechain) {
        // Refresh sidechain bandpass coefficients on the audio thread when
        // UI-thread setters have changed the cached freq/Q. Mutating the
        // BiquadFilter exclusively here keeps it single-threaded.
        double freqHz = sidechainFilterFreqHz;
        double qVal   = sidechainFilterQ;
        if (freqHz != appliedFilterFreqHz || qVal != appliedFilterQ) {
            sidechainFilter.recalculate(BiquadFilter.FilterType.BAND_PASS,
                    sampleRate, freqHz, qVal, 0.0);
            appliedFilterFreqHz = freqHz;
            appliedFilterQ      = qVal;
        }

        double thresholdLinear  = Math.pow(10.0,  thresholdDb / 20.0);
        double closeThresholdDb = thresholdDb - Math.max(0.0, hysteresisDb);
        double closeThresholdLin = Math.pow(10.0, closeThresholdDb / 20.0);
        double rangeLinear       = Math.pow(10.0, rangeDb / 20.0);
        boolean filterDetection  = sidechainFilterEnabled
                && (externalSidechain || sidechainEnabled);

        int detectionChannels = Math.min(channels, detectionBuffer.length);
        // Output writes must respect *both* input and output buffer lengths
        // — the AudioProcessor contract permits asymmetric buffers.
        int outputChannels    = Math.min(channels,
                Math.min(inputBuffer.length, outputBuffer.length));
        int delay             = Math.min(lookaheadDelaySamples, lookaheadCapacity - 1);

        double peakInput  = 0.0;
        double peakOutput = 0.0;

        for (int frame = 0; frame < numFrames; frame++) {
            // ── Detection: mono-sum then optional bandpass ───────────────
            double det = 0.0;
            for (int ch = 0; ch < detectionChannels; ch++) {
                det += detectionBuffer[ch][frame];
            }
            if (detectionChannels > 1) {
                det /= detectionChannels;
            }
            if (filterDetection) {
                det = sidechainFilter.processSampleDouble(det);
            }
            double peak = Math.abs(det);
            if (peak > peakInput) peakInput = peak;

            // ── State machine with hysteresis ────────────────────────────
            switch (state) {
                case CLOSED -> {
                    if (peak >= thresholdLinear) {
                        state = GateState.ATTACK;
                    }
                }
                case ATTACK -> {
                    envelope += (1.0 - envelope) * (1.0 - attackCoeff);
                    if (envelope >= 0.999) {
                        envelope = 1.0;
                        state = GateState.OPEN;
                    }
                    // Allow re-close while still attacking if signal collapses
                    if (peak < closeThresholdLin && envelope < 0.001) {
                        state = GateState.CLOSED;
                    }
                }
                case OPEN -> {
                    envelope = 1.0;
                    if (peak < closeThresholdLin) {
                        state = GateState.HOLD;
                        holdCounter = holdSamples;
                    }
                }
                case HOLD -> {
                    envelope = 1.0;
                    if (peak >= thresholdLinear) {
                        // Re-trigger before hold expires.
                        state = GateState.OPEN;
                    } else if (--holdCounter <= 0) {
                        state = GateState.RELEASE;
                    }
                }
                case RELEASE -> {
                    envelope *= releaseCoeff;
                    if (peak >= thresholdLinear) {
                        // New transient: re-open immediately.
                        state = GateState.ATTACK;
                    } else if (envelope <= rangeLinear + 1e-4) {
                        envelope = 0.0;
                        state = GateState.CLOSED;
                    }
                }
            }

            // Map envelope (0..1) to gain (range..1) so range is the floor.
            double gain = rangeLinear + envelope * (1.0 - rangeLinear);

            // ── Apply gain to (delayed) main input ───────────────────────
            int writeIdx = lookaheadWriteIdx;
            int readIdx  = writeIdx - delay;
            if (readIdx < 0) readIdx += lookaheadCapacity;
            for (int ch = 0; ch < outputChannels; ch++) {
                float inSample = inputBuffer[ch][frame];
                float delayed  = (delay == 0) ? inSample : lookaheadBuffer[ch][readIdx];
                lookaheadBuffer[ch][writeIdx] = inSample;
                float out = (float) (delayed * gain);
                outputBuffer[ch][frame] = out;
                double aOut = Math.abs(out);
                if (aOut > peakOutput) peakOutput = aOut;
            }
            lookaheadWriteIdx = writeIdx + 1;
            if (lookaheadWriteIdx >= lookaheadCapacity) {
                lookaheadWriteIdx = 0;
            }
        }

        // Publish meter readings to volatile primitives — no allocation here.
        // The immutable {@link MeterSnapshot} is constructed only when the
        // UI calls {@link #getMeterSnapshot()}.
        publishedState    = state;
        publishedEnvelope = envelope;
        publishedInputDb  = toDb(peakInput);
        publishedOutputDb = toDb(peakOutput);
    }

    private static double toDb(double linear) {
        return (linear > 0.0) ? 20.0 * Math.log10(linear) : Double.NEGATIVE_INFINITY;
    }

    // ── Parameter accessors ──────────────────────────────────────────────

    public double getThresholdDb() { return thresholdDb; }
    public void setThresholdDb(double v) { this.thresholdDb = v; }

    public double getHysteresisDb() { return hysteresisDb; }
    public void setHysteresisDb(double v) {
        this.hysteresisDb = Math.max(0.0, v);
    }

    public double getAttackMs() { return attackMs; }
    public void setAttackMs(double v) {
        this.attackMs = v;
        recalculateCoefficients();
    }

    public double getHoldMs() { return holdMs; }
    public void setHoldMs(double v) {
        this.holdMs = v;
        recalculateCoefficients();
    }

    public double getReleaseMs() { return releaseMs; }
    public void setReleaseMs(double v) {
        this.releaseMs = v;
        recalculateCoefficients();
    }

    public double getRangeDb() { return rangeDb; }
    public void setRangeDb(double v) { this.rangeDb = v; }

    public double getLookaheadMs() { return lookaheadMs; }
    public void setLookaheadMs(double v) {
        double clamped = Math.max(0.0, Math.min(MAX_LOOKAHEAD_MS, v));
        this.lookaheadMs = clamped;
        this.lookaheadDelaySamples = (int) Math.round(clamped * 0.001 * sampleRate);
    }

    public boolean isSidechainEnabled() { return sidechainEnabled; }
    public void setSidechainEnabled(boolean enabled) { this.sidechainEnabled = enabled; }

    public double getSidechainFilterFreqHz() { return sidechainFilterFreqHz; }
    public void setSidechainFilterFreqHz(double hz) {
        // Stash the new value; the audio thread refreshes the BiquadFilter
        // coefficients at the next buffer boundary to avoid concurrent
        // coefficient mutation while {@link BiquadFilter#processSampleDouble}
        // is running.
        this.sidechainFilterFreqHz = Math.max(20.0, Math.min(20000.0, hz));
    }

    public double getSidechainFilterQ() { return sidechainFilterQ; }
    public void setSidechainFilterQ(double q) {
        // See {@link #setSidechainFilterFreqHz} — refresh deferred to audio thread.
        this.sidechainFilterQ = Math.max(0.1, Math.min(10.0, q));
    }

    public boolean isSidechainFilterEnabled() { return sidechainFilterEnabled; }
    public void setSidechainFilterEnabled(boolean enabled) {
        this.sidechainFilterEnabled = enabled;
    }

    /** Returns the current gate state — reflects live audio-thread state. */
    public GateState getGateState() { return publishedState; }

    /**
     * Builds an immutable snapshot of the latest published meter readings.
     * Allocates on the calling (UI) thread; the audio thread itself never
     * allocates a {@link MeterSnapshot}.
     */
    public MeterSnapshot getMeterSnapshot() {
        return new MeterSnapshot(
                publishedState, publishedEnvelope,
                publishedInputDb, publishedOutputDb);
    }

    @Override
    public void reset() {
        state = GateState.CLOSED;
        envelope = 0.0;
        holdCounter = 0;
        lookaheadWriteIdx = 0;
        for (float[] ch : lookaheadBuffer) {
            java.util.Arrays.fill(ch, 0f);
        }
        sidechainFilter.reset();
        publishedState    = GateState.CLOSED;
        publishedEnvelope = 0.0;
        publishedInputDb  = Double.NEGATIVE_INFINITY;
        publishedOutputDb = Double.NEGATIVE_INFINITY;
    }

    @Override
    public int getInputChannelCount()  { return channels; }
    @Override
    public int getOutputChannelCount() { return channels; }

    /**
     * Reports the current lookahead delay in samples so the host's plugin
     * delay compensation (PDC) can keep parallel tracks aligned. The value
     * tracks {@link #setLookaheadMs(double)}.
     */
    @Override
    public int getLatencySamples() {
        return lookaheadDelaySamples;
    }

    private void recalculateCoefficients() {
        attackCoeff  = DynamicsCoefficients.envelope(attackMs,  sampleRate);
        releaseCoeff = DynamicsCoefficients.envelope(releaseMs, sampleRate);
        holdSamples  = (int) Math.max(0, holdMs * 0.001 * sampleRate);
    }

    /**
     * Immutable snapshot of the gate's instantaneous state, intended for
     * UI consumption (gate-status LED + level meter with threshold line).
     *
     * @param state           the current gate state
     * @param envelope        the gate envelope in {@code [0, 1]} where
     *                        {@code 0} is fully closed and {@code 1} is fully
     *                        open
     * @param inputLevelDb    detection-signal peak in dBFS for the last
     *                        processed buffer, or
     *                        {@link Double#NEGATIVE_INFINITY} if silent
     * @param outputLevelDb   output peak in dBFS for the last processed
     *                        buffer, or {@link Double#NEGATIVE_INFINITY} if
     *                        silent
     */
    public record MeterSnapshot(
            GateState state,
            double envelope,
            double inputLevelDb,
            double outputLevelDb) {

        /** Snapshot value representing "no activity" — gate closed, silent I/O. */
        public static final MeterSnapshot SILENT = new MeterSnapshot(
                GateState.CLOSED, 0.0,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);

        /**
         * Adapts this snapshot to the generic {@link PluginMeterSnapshot}
         * format. Gain reduction is derived from the actual input/output
         * level difference so the value reflects the audible attenuation
         * (including the {@code range} floor) rather than the raw envelope.
         */
        public PluginMeterSnapshot toPluginMeterSnapshot() {
            double grDb = 0.0;
            if (Double.isFinite(inputLevelDb) && Double.isFinite(outputLevelDb)) {
                grDb = Math.min(0.0, outputLevelDb - inputLevelDb);
            }
            return new PluginMeterSnapshot(grDb, inputLevelDb, outputLevelDb);
        }

        /** @return {@code true} when the gate is letting audio through. */
        public boolean isOpen() {
            return state == GateState.OPEN
                || state == GateState.HOLD
                || state == GateState.ATTACK;
        }
    }
}
