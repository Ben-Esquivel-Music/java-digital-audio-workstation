package com.benesquivelmusic.daw.core.dsp.dynamics;

import com.benesquivelmusic.daw.core.dsp.BiquadFilter;
import com.benesquivelmusic.daw.core.dsp.GainReductionProvider;
import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

/**
 * Frequency-conscious de-esser — a side-chain compressor that detects
 * sibilance via a band-pass filter and attenuates only the sibilant band
 * (split-band mode) or the full signal (wideband mode).
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>A pair of band-pass biquads (one per channel) extract the sibilant
 *       band from the input. Their centre frequency and Q are user-settable
 *       (typically 5–8&nbsp;kHz at Q&nbsp;≈&nbsp;1–2 for vocals).</li>
 *   <li>A peak-style level detector with fast attack / slow release tracks
 *       the band's envelope in dB.</li>
 *   <li>A 1:∞ "brick-wall" gain computer above the threshold determines the
 *       desired gain reduction, clamped to the user's <em>range</em> (max
 *       attenuation in dB) so the de-esser never overprocesses.</li>
 *   <li>In <b>split-band</b> mode the attenuation is applied <em>only</em> to
 *       the sibilant band. The same band-pass output drives both detection and
 *       attenuation, and the algebraic identity
 *       {@code out = in + sibilantBand · (g − 1)} (equivalent to
 *       {@code (in − sibilantBand) + sibilantBand · g}) reconstructs the
 *       non-sibilant portion of the signal without a second band-pass pass.
 *       When no reduction is happening ({@code g == 1}) split-band output is
 *       identical to the input (bit-exact null test).</li>
 *   <li>In <b>wideband</b> mode the attenuation is applied to the full signal,
 *       producing classic full-spectrum ducking similar to a side-chained
 *       compressor.</li>
 *   <li>In <b>listen</b> mode the output is replaced by the detection (sibilant)
 *       band so the user can tune {@code frequency} and {@code Q} to isolate
 *       the sibilance before setting {@code threshold}.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>{@code process} is annotated {@link RealTimeSafe}: it does not allocate
 * or take locks. Parameter setters are safe to call from a UI thread, but
 * because they use plain scalar writes, updates are best-effort and become
 * visible to the audio thread eventually rather than being guaranteed on the
 * next buffer.</p>
 */
public final class DeEsserProcessor implements AudioProcessor, GainReductionProvider {

    /** De-essing operating mode. */
    public enum Mode {
        /** Reduce gain on the entire signal — classic side-chained compressor behaviour. */
        WIDEBAND,
        /** Reduce gain only on the sibilant band; leave the rest of the spectrum untouched. */
        SPLIT_BAND
    }

    /** Floor for log-domain envelope to avoid log(0). */
    private static final double MIN_DB = -120.0;

    /** Default detection-band centre frequency (Hz) — typical for vocal sibilance. */
    private static final double DEFAULT_FREQUENCY_HZ = 6500.0;
    /** Default detection-band Q. */
    private static final double DEFAULT_Q = 1.4;
    /** Default attack time (ms) — fast enough to catch transient "ess". */
    private static final double ATTACK_MS = 1.0;
    /** Default release time (ms) — long enough to avoid pumping artefacts. */
    private static final double RELEASE_MS = 60.0;

    private final int channels;
    private final double sampleRate;

    // ── Parameters ────────────────────────────────────────────────────────
    private double frequencyHz   = DEFAULT_FREQUENCY_HZ;
    private double q             = DEFAULT_Q;
    private double thresholdDb   = -30.0;
    private double rangeDb       = 12.0;
    private Mode   mode          = Mode.SPLIT_BAND;
    private boolean listen       = false;

    // ── DSP state — one band-pass filter per channel.
    //  The filtered signal is reused both as the detection envelope source and,
    //  in split-band mode, as the band whose level is reduced.
    private final BiquadFilter[] detectorFilters;
    /** Pre-allocated scratch for the per-frame sibilant samples (one slot per channel). */
    private final double[] sibilantBuffer;
    /** Cached detector-coefficient state so we recalculate only on parameter changes. */
    private double cachedFreq = Double.NaN;
    private double cachedQ    = Double.NaN;

    // ── Envelope and metering ────────────────────────────────────────────
    private double envelopeDb = MIN_DB;
    private final double attackCoeff;
    private final double releaseCoeff;
    private double currentGainReductionDb;
    private double lastInputLevelDb  = Double.NEGATIVE_INFINITY;
    private double lastOutputLevelDb = Double.NEGATIVE_INFINITY;

    /**
     * Creates a de-esser with sensible vocal-friendly defaults
     * (6.5&nbsp;kHz / Q&nbsp;1.4 / -30&nbsp;dB threshold / 12&nbsp;dB range, split-band mode).
     *
     * @param channels   number of audio channels (must be &gt; 0)
     * @param sampleRate audio sample rate in Hz (must be &gt; 0)
     */
    public DeEsserProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.detectorFilters = new BiquadFilter[channels];
        this.sibilantBuffer = new double[channels];
        for (int ch = 0; ch < channels; ch++) {
            detectorFilters[ch] = BiquadFilter.create(
                    BiquadFilter.FilterType.BAND_PASS, sampleRate, frequencyHz, q, 0.0);
        }
        cachedFreq = frequencyHz;
        cachedQ = q;
        this.attackCoeff  = DynamicsCoefficients.envelope(ATTACK_MS,  sampleRate);
        this.releaseCoeff = DynamicsCoefficients.envelope(RELEASE_MS, sampleRate);
    }

    // ── AudioProcessor ────────────────────────────────────────────────────

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        refreshFiltersIfNeeded();
        double thresh = thresholdDb;
        double range  = rangeDb;
        Mode m        = mode;
        boolean lst   = listen;
        double minGainLinear = dbToLinear(-range);
        int nCh = Math.min(channels, Math.min(inputBuffer.length, outputBuffer.length));
        double peakIn  = 0.0;
        double peakOut = 0.0;

        for (int frame = 0; frame < numFrames; frame++) {
            // 1) Detection: peak across channels of the sibilant-band signal.
            double sibPeak = 0.0;
            // A single band-pass per channel produces the sibilant sample reused for
            // both detection and (in split-band / listen modes) attenuation output —
            // avoids running the band-pass twice.
            double[] sibilantNow = sibilantBuffer;
            for (int ch = 0; ch < nCh; ch++) {
                double in = inputBuffer[ch][frame];
                double sib = detectorFilters[ch].processSampleDouble(in);
                sibilantNow[ch] = sib;
                double absSib = Math.abs(sib);
                if (absSib > sibPeak) sibPeak = absSib;
                double absIn = Math.abs(in);
                if (absIn > peakIn) peakIn = absIn;
            }

            double inputDb = (sibPeak > 0) ? 20.0 * Math.log10(sibPeak) : MIN_DB;

            // 2) Envelope follower (log domain).
            double coeff = (inputDb > envelopeDb) ? attackCoeff : releaseCoeff;
            envelopeDb = coeff * envelopeDb + (1.0 - coeff) * inputDb;

            // 3) Gain computer: 1:∞ above threshold, clamped to range.
            double over = envelopeDb - thresh;
            double grDb = (over > 0) ? -over : 0.0;
            if (grDb < -range) grDb = -range;
            currentGainReductionDb = grDb;
            double grLinear = dbToLinear(grDb);
            // Numerical clamp to never exceed the user-defined range
            if (grLinear < minGainLinear) grLinear = minGainLinear;

            // 4) Apply per mode.
            for (int ch = 0; ch < nCh; ch++) {
                double in = inputBuffer[ch][frame];
                double out;
                if (lst) {
                    // Solo the detection band so the user can tune freq/Q.
                    out = sibilantNow[ch];
                } else if (m == Mode.WIDEBAND) {
                    out = in * grLinear;
                } else { // SPLIT_BAND
                    // Reduce the sibilant band only; sum back with the rest.
                    // rest = in − sibilantBand   ⇒   out = rest + sibilantBand·grLinear
                    //                                 = in + sibilantBand · (grLinear − 1)
                    out = in + sibilantNow[ch] * (grLinear - 1.0);
                }
                outputBuffer[ch][frame] = (float) out;
                double absOut = Math.abs(out);
                if (absOut > peakOut) peakOut = absOut;
            }
        }

        lastInputLevelDb  = (peakIn  > 0) ? 20.0 * Math.log10(peakIn)  : Double.NEGATIVE_INFINITY;
        lastOutputLevelDb = (peakOut > 0) ? 20.0 * Math.log10(peakOut) : Double.NEGATIVE_INFINITY;
    }

    @Override
    public void reset() {
        envelopeDb = MIN_DB;
        currentGainReductionDb = 0.0;
        lastInputLevelDb  = Double.NEGATIVE_INFINITY;
        lastOutputLevelDb = Double.NEGATIVE_INFINITY;
        for (int ch = 0; ch < channels; ch++) {
            detectorFilters[ch].reset();
        }
    }

    @Override public int getInputChannelCount()  { return channels; }
    @Override public int getOutputChannelCount() { return channels; }

    // ── GainReductionProvider ────────────────────────────────────────────

    @Override
    public double getGainReductionDb() {
        return currentGainReductionDb;
    }

    /**
     * Returns an immutable {@link PluginMeterSnapshot} capturing the current
     * gain-reduction reading together with the most-recently-measured peak
     * input and output levels. Intended for UI consumption.
     */
    public PluginMeterSnapshot getMeterSnapshot() {
        return new PluginMeterSnapshot(
                currentGainReductionDb, lastInputLevelDb, lastOutputLevelDb);
    }

    // ── Parameters ────────────────────────────────────────────────────────

    @ProcessorParam(id = 0, name = "Frequency", min = 2000.0, max = 12000.0,
            defaultValue = DEFAULT_FREQUENCY_HZ, unit = "Hz")
    public double getFrequencyHz() { return frequencyHz; }
    public void setFrequencyHz(double frequencyHz) {
        if (frequencyHz < 2000.0 || frequencyHz > 12000.0) {
            throw new IllegalArgumentException(
                    "frequencyHz must be in [2000, 12000]: " + frequencyHz);
        }
        if (frequencyHz >= sampleRate / 2.0) {
            throw new IllegalArgumentException(
                    "frequencyHz must be below Nyquist (" + sampleRate / 2.0 + " Hz): "
                            + frequencyHz);
        }
        this.frequencyHz = frequencyHz;
    }

    @ProcessorParam(id = 1, name = "Q", min = 0.5, max = 4.0, defaultValue = DEFAULT_Q)
    public double getQ() { return q; }
    public void setQ(double q) {
        if (q < 0.5 || q > 4.0) {
            throw new IllegalArgumentException("Q must be in [0.5, 4.0]: " + q);
        }
        this.q = q;
    }

    @ProcessorParam(id = 2, name = "Threshold", min = -60.0, max = 0.0,
            defaultValue = -30.0, unit = "dB")
    public double getThresholdDb() { return thresholdDb; }
    public void setThresholdDb(double thresholdDb) {
        if (thresholdDb < -60.0 || thresholdDb > 0.0) {
            throw new IllegalArgumentException(
                    "thresholdDb must be in [-60, 0]: " + thresholdDb);
        }
        this.thresholdDb = thresholdDb;
    }

    @ProcessorParam(id = 3, name = "Range", min = 0.0, max = 20.0,
            defaultValue = 12.0, unit = "dB")
    public double getRangeDb() { return rangeDb; }
    public void setRangeDb(double rangeDb) {
        if (rangeDb < 0.0 || rangeDb > 20.0) {
            throw new IllegalArgumentException(
                    "rangeDb must be in [0, 20]: " + rangeDb);
        }
        this.rangeDb = rangeDb;
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        this.mode = mode;
    }

    public boolean isListen() { return listen; }
    public void setListen(boolean listen) { this.listen = listen; }

    /** Returns the sample rate this processor was configured for. */
    public double getSampleRate() { return sampleRate; }

    // ── Internals ────────────────────────────────────────────────────────

    /**
     * Recalculates band-pass coefficients if {@code frequency} or {@code Q}
     * has changed since the last buffer. Filter <em>state</em> (delay lines)
     * is preserved across coefficient updates.
     */
    private void refreshFiltersIfNeeded() {
        if (frequencyHz != cachedFreq || q != cachedQ) {
            for (int ch = 0; ch < channels; ch++) {
                detectorFilters[ch].recalculate(
                        BiquadFilter.FilterType.BAND_PASS, sampleRate, frequencyHz, q, 0.0);
            }
            cachedFreq = frequencyHz;
            cachedQ = q;
        }
    }

    private static double dbToLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }
}
