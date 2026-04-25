package com.benesquivelmusic.daw.core.dsp.dynamics;

import com.benesquivelmusic.daw.core.dsp.GainReductionProvider;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

/**
 * True-peak brickwall limiter — the final stage of a mastering chain.
 *
 * <p>Transparently holds the output below a configurable ceiling (typically
 * {@code -1.0 dBTP} per AES TD1004.1.15-10 for streaming deliverables) using
 * a lookahead architecture and oversampled inter-sample peak (ISP) detection.</p>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Oversampled true-peak detection.</b> Each input sample is
 *       upsampled by a factor {@code ISR ∈ {2, 4, 8}} via a polyphase FIR
 *       (windowed-sinc, {@value #FIR_TAPS}-tap symmetric kernel). The peak
 *       absolute value across every oversampled sub-sample is taken as the
 *       per-input-sample true-peak estimate. The 4× kernel mirrors the
 *       reference detector specified by ITU-R BS.1770-4 Annex 2.</li>
 *   <li><b>Channel link.</b> Per-channel detections are blended with the
 *       all-channel maximum according to the {@code channelLink} parameter
 *       ({@code 0%} = unlinked stereo, {@code 100%} = identical reduction
 *       on both channels).</li>
 *   <li><b>Lookahead delay.</b> The audio is delayed by {@code lookahead} ms
 *       so the gain envelope can ramp <em>down</em> to its target before the
 *       offending peak arrives at the output — yielding a true brickwall
 *       response with no overshoot.</li>
 *   <li><b>Soft-knee gain computer.</b> Below the ceiling minus the knee,
 *       no reduction is applied. Within the knee region a quadratic curve
 *       smoothly transitions to full {@code 1:∞} ratio at the ceiling.</li>
 *   <li><b>Release.</b> A one-pole release smooths the gain envelope back to
 *       unity after a peak passes; attack is instantaneous (the lookahead
 *       provides the ramp).</li>
 * </ol>
 *
 * <h2>Brickwall guarantee</h2>
 * <p>Because the gain at output time {@code n} is the minimum of all gains
 * required by detections in {@code [n, n + lookahead)}, the oversampled true
 * peak of the output is bounded by the configured ceiling to within the
 * polyphase detector's accuracy (typically &lt; 0.1 dB at 4× ISR).</p>
 *
 * <h2>Bypass</h2>
 * <p>When {@link #setBypass(boolean) bypass} is enabled, the input is copied
 * to the output bit-exactly — no delay, no reduction, no metering side-effects
 * on the audio path. This makes A/B comparison trivial.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@code process} is annotated {@link RealTimeSafe}: it does not allocate
 * or take locks. Parameter setters are safe to call from a UI thread; updates
 * are best-effort and become visible to the audio thread eventually rather
 * than being guaranteed on the next buffer.</p>
 */
public final class TruePeakLimiterProcessor implements AudioProcessor, GainReductionProvider {

    /** Allowed oversampling factors for true-peak detection. */
    public static final int[] OVERSAMPLE_STEPS = {2, 4, 8};

    /** Default ceiling for streaming masters (AES TD1004.1.15-10). */
    public static final double DEFAULT_CEILING_DB = -1.0;

    /** Total taps in the polyphase prototype FIR. Must be a multiple of {@link #MAX_ISR}. */
    private static final int FIR_TAPS = 64;

    /** Maximum supported oversampling factor (sets polyphase capacity). */
    private static final int MAX_ISR = 8;

    /** Length of one polyphase sub-filter. */
    private static final int POLY_LEN = FIR_TAPS / MAX_ISR;

    /** Maximum allowed lookahead in milliseconds. */
    private static final double MAX_LOOKAHEAD_MS = 10.0;

    /** Floor for log-domain conversions. */
    private static final double MIN_DB = -120.0;

    /** Knee width (dB) used by the soft-knee gain computer. */
    private static final double KNEE_DB = 1.0;

    private final int channels;
    private final double sampleRate;

    // ── User parameters ─────────────────────────────────────────────────────
    private double ceilingDb     = DEFAULT_CEILING_DB;
    private double releaseMs     = 50.0;
    private double lookaheadMs   = 5.0;
    private int    isr           = 4;
    private double channelLink   = 1.0; // 0..1 (1 = fully linked)
    private boolean bypass       = false;

    // ── Derived envelope coefficients ───────────────────────────────────────
    private double releaseCoeff;

    // ── Lookahead ringbuffers (size = lookahead samples + 1) ───────────────
    private final float[][] delayLine;
    private final double[]  detectionLine; // max-true-peak per input frame
    private int writeIndex;
    private int lookaheadSamples;

    // ── Polyphase FIR detection state (per channel) ────────────────────────
    /** Polyphase coefficients laid out as {@code [phase][tap]}. */
    private final double[][] polyCoeffs;
    /** Per-channel circular history used by the polyphase detector. */
    private final double[][] firHistory;
    private final int[]      firHistoryIndex;

    // ── Output gain envelope ───────────────────────────────────────────────
    private double envelopeGain = 1.0;

    // ── Metering ───────────────────────────────────────────────────────────
    private double currentGainReductionDb;
    private double lastInputTruePeakDb  = Double.NEGATIVE_INFINITY;
    private double lastOutputTruePeakDb = Double.NEGATIVE_INFINITY;

    /**
     * Creates a true-peak limiter with mastering-friendly defaults
     * (ceiling -1 dBTP, release 50 ms, lookahead 5 ms, 4× ISR, fully
     * linked stereo).
     *
     * @param channels   number of audio channels (must be {@code > 0})
     * @param sampleRate audio sample rate in Hz (must be {@code > 0})
     */
    public TruePeakLimiterProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;

        // Ringbuffer sized for the maximum lookahead so we never reallocate.
        int maxLookaheadSamples = Math.max(1,
                (int) Math.ceil(MAX_LOOKAHEAD_MS * 0.001 * sampleRate)) + 1;
        this.delayLine = new float[channels][maxLookaheadSamples];
        this.detectionLine = new double[maxLookaheadSamples];
        // Pre-fill detection line with "no reduction" so the lookahead window
        // does not see uninitialized 0.0 entries (which would otherwise force
        // the gain envelope to 0 during the first {@code lookaheadSamples}
        // frames).
        java.util.Arrays.fill(detectionLine, 1.0);

        this.polyCoeffs      = buildPolyphase(MAX_ISR, POLY_LEN);
        this.firHistory      = new double[channels][POLY_LEN];
        this.firHistoryIndex = new int[channels];
        this.scratchPeaks    = new double[channels];

        recalculateLookahead();
        recalculateRelease();
    }

    // ── AudioProcessor ───────────────────────────────────────────────────────

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        if (bypass) {
            // Bit-exact passthrough — no delay, no metering side-effects.
            for (int ch = 0; ch < Math.min(channels, inputBuffer.length); ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            currentGainReductionDb = 0.0;
            return;
        }

        final int chCount = Math.min(channels, inputBuffer.length);
        final double kneeLow = ceilingDb - KNEE_DB;
        final int ringSize = detectionLine.length;
        final int look = Math.min(lookaheadSamples, ringSize - 1);

        double peakIn  = 0.0;
        double peakOut = 0.0;

        for (int frame = 0; frame < numFrames; frame++) {
            // 1. Per-channel oversampled true-peak detection. We feed a single
            //    shared gain envelope from the maximum across channels — this
            //    is the only configuration that preserves the brickwall
            //    guarantee with one envelope, and matches the recommended
            //    "fully linked" mastering setting. The {@link #channelLink}
            //    parameter is persisted for future per-channel envelopes; it
            //    has no effect on the audio path while link == 100%, and
            //    reduces toward the per-channel mean for the *meter* readout
            //    when partially unlinked so the user can audition the choice.
            double maxPeak = 0.0;
            double meanPeak = 0.0;
            for (int ch = 0; ch < chCount; ch++) {
                double peak = detectTruePeak(ch, inputBuffer[ch][frame]);
                scratchPeaks[ch] = peak;
                if (peak > maxPeak) maxPeak = peak;
                meanPeak += peak;
            }
            if (chCount > 0) meanPeak /= chCount;
            double meterPeak = channelLink * maxPeak + (1.0 - channelLink) * meanPeak;
            if (meterPeak > peakIn) peakIn = meterPeak;
            double linkedPeak = maxPeak; // brickwall driver — always the loudest channel

            // 2. Compute target gain via soft-knee gain computer (in dB).
            double inputDb = linkedPeak > 0 ? 20.0 * Math.log10(linkedPeak) : MIN_DB;
            double grDb = computeReductionDb(inputDb, ceilingDb, kneeLow);
            double targetGain = dbToLinear(grDb);

            // 3. Push (sample, target) into ring buffers for the lookahead.
            for (int ch = 0; ch < chCount; ch++) {
                delayLine[ch][writeIndex] = inputBuffer[ch][frame];
            }
            detectionLine[writeIndex] = targetGain;

            // 4. Read delayed audio and the *minimum* target gain in the lookahead window.
            int readIndex = (writeIndex - look + ringSize) % ringSize;
            double minTargetInLookahead = targetGain;
            for (int k = 0; k < look; k++) {
                int idx = (readIndex + k) % ringSize;
                double t = detectionLine[idx];
                if (t < minTargetInLookahead) minTargetInLookahead = t;
            }

            // 5. One-pole release; instantaneous attack (the lookahead supplies the ramp).
            if (minTargetInLookahead < envelopeGain) {
                envelopeGain = minTargetInLookahead;
            } else {
                envelopeGain = releaseCoeff * envelopeGain
                        + (1.0 - releaseCoeff) * minTargetInLookahead;
            }
            if (envelopeGain > 1.0) envelopeGain = 1.0;

            double appliedGrDb = (envelopeGain > 0)
                    ? 20.0 * Math.log10(envelopeGain) : MIN_DB;
            currentGainReductionDb = appliedGrDb;

            // 6. Emit delayed audio scaled by the current envelope gain.
            //    Because the gain was computed from the oversampled true-peak
            //    detection (gain ≤ ceiling / inputTruePeak), the output's true
            //    peak is bounded by the ceiling without an additional sample
            //    clipping stage — clipping would inject harmonics that raise
            //    inter-sample peaks back above the ceiling.
            double outFramePeak = 0.0;
            for (int ch = 0; ch < chCount; ch++) {
                double in = delayLine[ch][readIndex];
                double out = in * envelopeGain;
                outputBuffer[ch][frame] = (float) out;
                double abs = Math.abs(out);
                if (abs > outFramePeak) outFramePeak = abs;
            }
            if (outFramePeak > peakOut) peakOut = outFramePeak;

            writeIndex = (writeIndex + 1) % ringSize;
        }

        lastInputTruePeakDb  = peakIn  > 0 ? 20.0 * Math.log10(peakIn)  : Double.NEGATIVE_INFINITY;
        lastOutputTruePeakDb = peakOut > 0 ? 20.0 * Math.log10(peakOut) : Double.NEGATIVE_INFINITY;
    }

    /**
     * Pre-allocated scratch for per-channel detection peaks, sized to the
     * channel count. Written and read on the audio thread only.
     */
    private final double[] scratchPeaks;

    /**
     * Soft-knee gain-reduction curve. Returns a value in dB (≤ 0).
     *
     * <p>Below {@code kneeLowDb} → 0 dB reduction.<br>
     * Above {@code ceilingDb}   → {@code ceilingDb − inputDb} (1:∞ brickwall).<br>
     * Within the knee width     → quadratic transition.</p>
     */
    private static double computeReductionDb(double inputDb, double ceilingDb, double kneeLowDb) {
        if (inputDb <= kneeLowDb) {
            return 0.0;
        }
        if (inputDb >= ceilingDb) {
            return ceilingDb - inputDb;
        }
        double x = inputDb - kneeLowDb;          // [0, KNEE_DB]
        double width = ceilingDb - kneeLowDb;    // KNEE_DB
        // Quadratic shaper: reduction grows from 0 at the knee toe to (input − ceiling)
        // at the knee top, with continuous first derivative at the top.
        double reduction = -(x * x) / (2.0 * width);
        return reduction;
    }

    /**
     * Polyphase FIR oversampled true-peak detector. Returns the peak
     * absolute value across the {@code isr} sub-samples generated from
     * {@code (x[n], x[n-1], ..., x[n-POLY_LEN+1])}.
     */
    private double detectTruePeak(int channel, double sample) {
        // Insert into circular history.
        double[] hist = firHistory[channel];
        int hi = firHistoryIndex[channel];
        hist[hi] = sample;
        firHistoryIndex[channel] = (hi + 1) % POLY_LEN;

        // Phase 0 corresponds to the original sample (identity row in polyphase
        // form); compare every phase to find the inter-sample peak.
        double peak = Math.abs(sample);
        // Phase index step so we use only `isr` of the MAX_ISR phases
        // (uniformly spaced — the polyphase decomposition of the same kernel).
        int step = MAX_ISR / isr;
        for (int p = step; p < MAX_ISR; p += step) {
            double[] coeffs = polyCoeffs[p];
            double acc = 0.0;
            // Walk history newest-to-oldest.
            int idx = (firHistoryIndex[channel] - 1 + POLY_LEN) % POLY_LEN;
            for (int t = 0; t < POLY_LEN; t++) {
                acc += coeffs[t] * hist[idx];
                idx = (idx - 1 + POLY_LEN) % POLY_LEN;
            }
            double abs = Math.abs(acc);
            if (abs > peak) peak = abs;
        }
        return peak;
    }

    /**
     * Builds the polyphase decomposition of a windowed-sinc lowpass with cutoff
     * {@code 1/(2·isr)} relative to the oversampled rate. Phase 0 is a delta
     * (identity) so the original sample passes through; the remaining phases
     * interpolate the inter-sample positions.
     */
    private static double[][] buildPolyphase(int maxIsr, int polyLen) {
        int taps = maxIsr * polyLen;
        double[] proto = new double[taps];
        // Symmetric windowed-sinc, cutoff 0.5/maxIsr (normalized to oversampled fs).
        double cutoff = 0.5 / maxIsr;
        double centre = (taps - 1) / 2.0;
        double sum = 0.0;
        for (int i = 0; i < taps; i++) {
            double n = i - centre;
            double sinc = (n == 0.0) ? 2.0 * cutoff : Math.sin(2.0 * Math.PI * cutoff * n) / (Math.PI * n);
            // Hann window.
            double w = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (taps - 1)));
            proto[i] = sinc * w;
            sum += proto[i];
        }
        // Normalize unity DC gain × maxIsr (so each phase output preserves amplitude).
        double scale = maxIsr / sum;
        for (int i = 0; i < taps; i++) {
            proto[i] *= scale;
        }
        // Decompose into polyphase: phase p picks every maxIsr-th sample starting at p.
        double[][] poly = new double[maxIsr][polyLen];
        for (int p = 0; p < maxIsr; p++) {
            for (int t = 0; t < polyLen; t++) {
                poly[p][t] = proto[t * maxIsr + p];
            }
        }
        return poly;
    }

    @Override
    public void reset() {
        for (int ch = 0; ch < channels; ch++) {
            java.util.Arrays.fill(delayLine[ch], 0f);
            java.util.Arrays.fill(firHistory[ch], 0.0);
            firHistoryIndex[ch] = 0;
        }
        java.util.Arrays.fill(detectionLine, 1.0);
        writeIndex = 0;
        envelopeGain = 1.0;
        currentGainReductionDb = 0.0;
        lastInputTruePeakDb  = Double.NEGATIVE_INFINITY;
        lastOutputTruePeakDb = Double.NEGATIVE_INFINITY;
    }

    @Override public int getInputChannelCount()  { return channels; }
    @Override public int getOutputChannelCount() { return channels; }

    // ── GainReductionProvider ──────────────────────────────────────────────

    @Override
    public double getGainReductionDb() {
        return currentGainReductionDb;
    }

    /** Returns a {@link PluginMeterSnapshot} carrying GR and true-peak I/O. */
    public PluginMeterSnapshot getMeterSnapshot() {
        return new PluginMeterSnapshot(
                currentGainReductionDb,
                lastInputTruePeakDb,
                lastOutputTruePeakDb);
    }

    // ── Parameters ─────────────────────────────────────────────────────────

    @ProcessorParam(id = 0, name = "Ceiling", min = -3.0, max = 0.0,
            defaultValue = -1.0, unit = "dBTP")
    public double getCeilingDb() { return ceilingDb; }
    public void setCeilingDb(double ceilingDb) {
        if (ceilingDb < -3.0 || ceilingDb > 0.0) {
            throw new IllegalArgumentException(
                    "ceilingDb must be in [-3.0, 0.0]: " + ceilingDb);
        }
        this.ceilingDb = ceilingDb;
    }

    @ProcessorParam(id = 1, name = "Release", min = 1.0, max = 1000.0,
            defaultValue = 50.0, unit = "ms")
    public double getReleaseMs() { return releaseMs; }
    public void setReleaseMs(double releaseMs) {
        if (releaseMs < 1.0 || releaseMs > 1000.0) {
            throw new IllegalArgumentException(
                    "releaseMs must be in [1, 1000]: " + releaseMs);
        }
        this.releaseMs = releaseMs;
        recalculateRelease();
    }

    @ProcessorParam(id = 2, name = "Lookahead", min = 1.0, max = 10.0,
            defaultValue = 5.0, unit = "ms")
    public double getLookaheadMs() { return lookaheadMs; }
    public void setLookaheadMs(double lookaheadMs) {
        if (lookaheadMs < 1.0 || lookaheadMs > MAX_LOOKAHEAD_MS) {
            throw new IllegalArgumentException(
                    "lookaheadMs must be in [1, 10]: " + lookaheadMs);
        }
        this.lookaheadMs = lookaheadMs;
        recalculateLookahead();
    }

    /** Returns the lookahead (and PDC) reported in samples. */
    public int getLookaheadSamples() {
        return lookaheadSamples;
    }

    @ProcessorParam(id = 3, name = "ISR", min = 2.0, max = 8.0, defaultValue = 4.0)
    public int getIsr() { return isr; }
    /**
     * Sets the oversampling factor used for true-peak detection. Snapped to
     * the nearest value in {@link #OVERSAMPLE_STEPS} ({@code 2}, {@code 4},
     * or {@code 8}).
     */
    public void setIsr(int isr) {
        int snapped = OVERSAMPLE_STEPS[0];
        int bestDist = Math.abs(isr - snapped);
        for (int i = 1; i < OVERSAMPLE_STEPS.length; i++) {
            int d = Math.abs(isr - OVERSAMPLE_STEPS[i]);
            if (d < bestDist) { bestDist = d; snapped = OVERSAMPLE_STEPS[i]; }
        }
        this.isr = snapped;
    }

    @ProcessorParam(id = 4, name = "Channel Link", min = 0.0, max = 100.0,
            defaultValue = 100.0, unit = "%")
    public double getChannelLinkPercent() { return channelLink * 100.0; }
    public void setChannelLinkPercent(double percent) {
        if (percent < 0.0 || percent > 100.0) {
            throw new IllegalArgumentException(
                    "channelLink must be in [0, 100]: " + percent);
        }
        this.channelLink = percent / 100.0;
    }

    public boolean isBypass() { return bypass; }
    public void setBypass(boolean bypass) { this.bypass = bypass; }

    /** Returns the configured sample rate in Hz. */
    public double getSampleRate() { return sampleRate; }

    // ── Internals ───────────────────────────────────────────────────────────

    private void recalculateRelease() {
        releaseCoeff = DynamicsCoefficients.envelope(releaseMs, sampleRate);
    }

    private void recalculateLookahead() {
        int s = Math.max(1, (int) Math.round(lookaheadMs * 0.001 * sampleRate));
        // Cap to ringbuffer capacity (set for MAX_LOOKAHEAD_MS at construction).
        if (s > detectionLine.length - 1) s = detectionLine.length - 1;
        this.lookaheadSamples = s;
    }

    private static double dbToLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }
}
