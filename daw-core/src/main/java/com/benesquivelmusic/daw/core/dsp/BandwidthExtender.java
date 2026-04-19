package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.core.analysis.FftUtils;
import com.benesquivelmusic.daw.core.mixer.InsertEffect;

import com.benesquivelmusic.daw.sdk.annotation.ProcessorParam;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

/**
 * Intelligent gap filling / bandwidth extension processor that restores
 * high-frequency content lost to lossy compression or band-limited recording.
 *
 * <p>The processor assumes the source signal has a spectral cutoff — either
 * detected with {@link #detectCutoffHz(float[], double)} (which mirrors the
 * algorithm used by
 * {@link com.benesquivelmusic.daw.core.analysis.LosslessIntegrityChecker}) or
 * supplied directly — and synthesizes plausible content between the cutoff
 * and a configurable target bandwidth.</p>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Isolate the band-limited source using a {@link BiquadFilter} lowpass
 *       at the cutoff frequency.</li>
 *   <li>Generate high-frequency content from the isolated source using one
 *       of three methods:
 *       <ul>
 *         <li><b>Spectral Band Replication (SBR)</b> — full-wave rectification
 *             transposes spectral energy up by integer multiples (even
 *             harmonics at 2f, 4f, ...).</li>
 *         <li><b>Harmonic extrapolation</b> — polynomial waveshaping
 *             generates a rich spectrum of odd and even harmonics of the
 *             detected tonal content.</li>
 *         <li><b>Noise shaping</b> — a pseudo-random noise carrier modulated
 *             by the envelope of the source, preserving transient dynamics
 *             without introducing tonal artifacts on noisy material.</li>
 *       </ul></li>
 *   <li>Bandlimit the generated content with a highpass at the cutoff
 *       (suppresses content already present in the dry signal) and a lowpass
 *       at the target bandwidth.</li>
 *   <li>Apply a perceptual high-shelf attenuation so the extrapolated content
 *       rolls off naturally — high frequencies should be gentler than the
 *       mid-band energy.</li>
 *   <li>Scale by the intensity parameter and blend with the dry signal.</li>
 * </ol>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li><b>Cutoff frequency</b> — The frequency above which original content
 *       is missing and new content will be synthesized (2–22 kHz).</li>
 *   <li><b>Target bandwidth</b> — The upper bound of the generated content;
 *       must be above the cutoff and below Nyquist.</li>
 *   <li><b>Generation method</b> — {@link GenerationMethod#SBR},
 *       {@link GenerationMethod#HARMONIC}, or {@link GenerationMethod#NOISE}.</li>
 *   <li><b>Intensity</b> — Linear gain applied to the generated content
 *       (0.0–1.0).</li>
 *   <li><b>Blend</b> — Dry/wet mix (0.0 = fully dry, 1.0 = fully wet).</li>
 * </ul>
 *
 * <h2>AES Research References</h2>
 * <ul>
 *   <li>Perceptually Controlled Selection of Alternatives for High-Frequency
 *       Content in Intelligent Gap Filling (2025)</li>
 *   <li>Sound Board: High-Resolution Audio (2015)</li>
 * </ul>
 *
 * <p>Pure-Java implementation — builds on {@link BiquadFilter},
 * {@link FftUtils}, and
 * {@link com.benesquivelmusic.daw.core.analysis.SpectrumAnalyzer}-compatible
 * spectral analysis.</p>
 */
@InsertEffect(type = "BANDWIDTH_EXTENDER", displayName = "Bandwidth Extender")
public final class BandwidthExtender implements AudioProcessor {

    /** High-frequency content generation strategies. */
    public enum GenerationMethod {
        /** Spectral Band Replication — full-wave rectification lifts content above the cutoff. */
        SBR,
        /** Harmonic extrapolation via polynomial waveshaping. */
        HARMONIC,
        /** Envelope-modulated noise shaping. */
        NOISE
    }

    /** Minimum allowed cutoff frequency in Hz. */
    public static final double MIN_CUTOFF_HZ = 2_000.0;

    /** Maximum allowed cutoff frequency in Hz. */
    public static final double MAX_CUTOFF_HZ = 22_000.0;

    /** Minimum allowed target bandwidth in Hz. */
    public static final double MIN_TARGET_BANDWIDTH_HZ = 4_000.0;

    /** Maximum allowed target bandwidth in Hz. */
    public static final double MAX_TARGET_BANDWIDTH_HZ = 24_000.0;

    private static final double BUTTERWORTH_Q = 0.707;
    private static final double PERCEPTUAL_SHELF_GAIN_DB = -3.0;
    private static final int FFT_SIZE = 4096;
    private static final double CUTOFF_DROP_DB = 40.0;
    private static final double DB_FLOOR = -120.0;

    private final int channels;
    private final double sampleRate;

    private double cutoffHz;
    private double targetBandwidthHz;
    private GenerationMethod method;
    private double intensity;
    private double blend;

    // Per-channel filter chain. Volatile arrays are atomically replaced in
    // rebuildFilters() so the audio thread never observes a partial update.
    private volatile BiquadFilter[] sourceLp;
    private volatile BiquadFilter[] postHp;
    private volatile BiquadFilter[] postLp;
    private volatile BiquadFilter[] perceptualShelf;

    // Per-channel envelope follower state for NOISE mode.
    private final double[] envelopeState;

    // Per-channel pseudo-random noise state (xorshift). Avoids heap allocation
    // in process() which a java.util.Random would incur.
    private final long[] noiseState;

    /**
     * Creates a bandwidth extender with default settings:
     * 16 kHz cutoff, 20 kHz target bandwidth, SBR generation, intensity 0.5,
     * blend 1.0 (fully wet).
     *
     * @param channels   number of audio channels (must be positive)
     * @param sampleRate the sample rate in Hz (must be positive)
     */
    public BandwidthExtender(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.cutoffHz = Math.min(16_000.0, sampleRate * 0.45);
        this.targetBandwidthHz = Math.min(20_000.0, sampleRate * 0.49);
        if (this.targetBandwidthHz <= this.cutoffHz) {
            this.cutoffHz = this.targetBandwidthHz * 0.8;
        }
        this.method = GenerationMethod.SBR;
        this.intensity = 0.5;
        this.blend = 1.0;

        this.envelopeState = new double[channels];
        this.noiseState = new long[channels];
        for (int ch = 0; ch < channels; ch++) {
            // Seed per-channel PRNGs with distinct non-zero values so
            // channels generate decorrelated noise.
            this.noiseState[ch] = 0x9E3779B97F4A7C15L ^ ((long) (ch + 1) * 0xD1B54A32D192ED03L);
        }

        rebuildFilters();
    }

    @RealTimeSafe
    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, Math.min(inputBuffer.length, outputBuffer.length));

        if (blend == 0.0 || intensity == 0.0) {
            for (int ch = 0; ch < activeCh; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
            return;
        }

        // Snapshot volatile filter references for safe concurrent access.
        BiquadFilter[] lp = sourceLp;
        BiquadFilter[] hp = postHp;
        BiquadFilter[] bandLp = postLp;
        BiquadFilter[] shelf = perceptualShelf;

        GenerationMethod m = this.method;
        double gain = this.intensity;
        double mix = this.blend;
        // Envelope follower time constant: ~5 ms attack/release.
        double envCoeff = Math.exp(-1.0 / (0.005 * sampleRate));

        for (int ch = 0; ch < activeCh; ch++) {
            double env = envelopeState[ch];
            long prng = noiseState[ch];

            for (int frame = 0; frame < numFrames; frame++) {
                float dry = inputBuffer[ch][frame];

                // 1. Isolate the band-limited source.
                float source = lp[ch].processSample(dry);

                // 2. Generate candidate high-frequency content.
                float generated = switch (m) {
                    case SBR ->
                        // Full-wave rectification transposes energy upward,
                        // producing even harmonics (2f, 4f, ...). Subtracting
                        // the DC mean of |x| is approximated by the downstream
                        // highpass, so leave it in place here.
                        Math.abs(source);
                    case HARMONIC -> {
                        // Polynomial waveshaping: tanh-like via Padé approx.
                        // produces both odd and even harmonics.
                        float x = Math.max(-1.5f, Math.min(1.5f, source * 2.0f));
                        float x2 = x * x;
                        yield x * (27.0f + x2) / (27.0f + 9.0f * x2);
                    }
                    case NOISE -> {
                        // Envelope-modulated white noise. xorshift64 keeps
                        // the inner loop allocation-free and RT-safe.
                        prng ^= prng << 13;
                        prng ^= prng >>> 7;
                        prng ^= prng << 17;
                        float noise = (prng >> 40) * (1.0f / (1L << 23));
                        double absSource = Math.abs(source);
                        env = absSource + envCoeff * (env - absSource);
                        yield (float) (noise * env);
                    }
                };

                // 3. Bandlimit to the extension region.
                generated = hp[ch].processSample(generated);
                generated = bandLp[ch].processSample(generated);

                // 4. Perceptual spectral-slope shaping.
                generated = shelf[ch].processSample(generated);

                // 5. Scale by intensity and blend with dry.
                float enhanced = dry + generated * (float) gain;
                outputBuffer[ch][frame] = (float) (dry * (1.0 - mix) + enhanced * mix);
            }

            envelopeState[ch] = env;
            noiseState[ch] = prng;
        }

        // Pass through any extra output channels the processor doesn't cover.
        for (int ch = activeCh; ch < outputBuffer.length && ch < inputBuffer.length; ch++) {
            System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
        }
    }

    // --- Parameter accessors ---

    /**
     * Returns the detected/configured cutoff frequency in Hz.
     *
     * @return cutoff frequency
     */
    @ProcessorParam(id = 0, name = "Cutoff", min = MIN_CUTOFF_HZ, max = MAX_CUTOFF_HZ,
            defaultValue = 16_000.0, unit = "Hz")
    public double getCutoffHz() {
        return cutoffHz;
    }

    /**
     * Sets the cutoff frequency — the boundary above which content is
     * missing in the source and must be synthesized.
     *
     * @param cutoffHz cutoff frequency in {@code [MIN_CUTOFF_HZ, MAX_CUTOFF_HZ]}
     *                 and strictly less than the target bandwidth
     */
    public void setCutoffHz(double cutoffHz) {
        if (cutoffHz < MIN_CUTOFF_HZ || cutoffHz > MAX_CUTOFF_HZ) {
            throw new IllegalArgumentException(
                    "cutoffHz must be in [" + MIN_CUTOFF_HZ + ", "
                            + MAX_CUTOFF_HZ + "]: " + cutoffHz);
        }
        if (cutoffHz >= targetBandwidthHz) {
            throw new IllegalArgumentException(
                    "cutoffHz (" + cutoffHz + ") must be < targetBandwidthHz ("
                            + targetBandwidthHz + ")");
        }
        this.cutoffHz = cutoffHz;
        rebuildFilters();
    }

    /**
     * Returns the target bandwidth in Hz.
     *
     * @return target bandwidth
     */
    @ProcessorParam(id = 1, name = "Target Bandwidth", min = MIN_TARGET_BANDWIDTH_HZ,
            max = MAX_TARGET_BANDWIDTH_HZ, defaultValue = 20_000.0, unit = "Hz")
    public double getTargetBandwidthHz() {
        return targetBandwidthHz;
    }

    /**
     * Sets the upper bound of the generated high-frequency content.
     *
     * @param targetBandwidthHz target bandwidth in
     *        {@code [MIN_TARGET_BANDWIDTH_HZ, MAX_TARGET_BANDWIDTH_HZ]},
     *        strictly greater than the cutoff
     */
    public void setTargetBandwidthHz(double targetBandwidthHz) {
        if (targetBandwidthHz < MIN_TARGET_BANDWIDTH_HZ
                || targetBandwidthHz > MAX_TARGET_BANDWIDTH_HZ) {
            throw new IllegalArgumentException(
                    "targetBandwidthHz must be in ["
                            + MIN_TARGET_BANDWIDTH_HZ + ", "
                            + MAX_TARGET_BANDWIDTH_HZ + "]: " + targetBandwidthHz);
        }
        if (targetBandwidthHz <= cutoffHz) {
            throw new IllegalArgumentException(
                    "targetBandwidthHz (" + targetBandwidthHz + ") must be > cutoffHz ("
                            + cutoffHz + ")");
        }
        this.targetBandwidthHz = targetBandwidthHz;
        rebuildFilters();
    }

    /**
     * Returns the current generation method.
     *
     * @return generation method, never {@code null}
     */
    public GenerationMethod getMethod() {
        return method;
    }

    /**
     * Sets the high-frequency generation method.
     *
     * @param method generation strategy (must not be {@code null})
     */
    public void setMethod(GenerationMethod method) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        this.method = method;
    }

    /**
     * Returns the intensity (linear gain applied to generated HF content).
     *
     * @return intensity in {@code [0.0, 1.0]}
     */
    @ProcessorParam(id = 2, name = "Intensity", min = 0.0, max = 1.0, defaultValue = 0.5)
    public double getIntensity() {
        return intensity;
    }

    /**
     * Sets the intensity of the generated high-frequency content.
     *
     * @param intensity linear gain in {@code [0.0, 1.0]}
     */
    public void setIntensity(double intensity) {
        if (intensity < 0.0 || intensity > 1.0) {
            throw new IllegalArgumentException("intensity must be in [0.0, 1.0]: " + intensity);
        }
        this.intensity = intensity;
    }

    /**
     * Returns the dry/wet blend.
     *
     * @return blend in {@code [0.0, 1.0]}
     */
    @ProcessorParam(id = 3, name = "Blend", min = 0.0, max = 1.0, defaultValue = 1.0)
    public double getBlend() {
        return blend;
    }

    /**
     * Sets the dry/wet blend.
     *
     * @param blend 0.0 = fully dry, 1.0 = fully wet (extended)
     */
    public void setBlend(double blend) {
        if (blend < 0.0 || blend > 1.0) {
            throw new IllegalArgumentException("blend must be in [0.0, 1.0]: " + blend);
        }
        this.blend = blend;
    }

    @Override
    public void reset() {
        BiquadFilter[] lp = sourceLp;
        BiquadFilter[] hp = postHp;
        BiquadFilter[] bandLp = postLp;
        BiquadFilter[] shelf = perceptualShelf;
        for (int ch = 0; ch < channels; ch++) {
            lp[ch].reset();
            hp[ch].reset();
            bandLp[ch].reset();
            shelf[ch].reset();
            envelopeState[ch] = 0.0;
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

    private void rebuildFilters() {
        double nyquist = sampleRate * 0.5;
        double safeCutoff = Math.min(cutoffHz, nyquist * 0.95);
        double safeTarget = Math.min(targetBandwidthHz, nyquist * 0.99);
        // Place the shelf halfway through the extension region so the
        // perceptual roll-off applies smoothly above the cutoff.
        double shelfFreq = Math.min((safeCutoff + safeTarget) * 0.5, nyquist * 0.98);

        BiquadFilter[] newSourceLp = new BiquadFilter[channels];
        BiquadFilter[] newPostHp = new BiquadFilter[channels];
        BiquadFilter[] newPostLp = new BiquadFilter[channels];
        BiquadFilter[] newShelf = new BiquadFilter[channels];

        for (int ch = 0; ch < channels; ch++) {
            newSourceLp[ch] = BiquadFilter.create(
                    BiquadFilter.FilterType.LOW_PASS, sampleRate, safeCutoff,
                    BUTTERWORTH_Q, 0);
            newPostHp[ch] = BiquadFilter.create(
                    BiquadFilter.FilterType.HIGH_PASS, sampleRate, safeCutoff,
                    BUTTERWORTH_Q, 0);
            newPostLp[ch] = BiquadFilter.create(
                    BiquadFilter.FilterType.LOW_PASS, sampleRate, safeTarget,
                    BUTTERWORTH_Q, 0);
            newShelf[ch] = BiquadFilter.create(
                    BiquadFilter.FilterType.HIGH_SHELF, sampleRate, shelfFreq,
                    BUTTERWORTH_Q, PERCEPTUAL_SHELF_GAIN_DB);
        }

        sourceLp = newSourceLp;
        postHp = newPostHp;
        postLp = newPostLp;
        perceptualShelf = newShelf;
    }

    // --- Static cutoff detection -------------------------------------------

    /**
     * Detects the spectral cutoff (high-frequency rolloff point) of an
     * audio buffer using the same algorithm as
     * {@link com.benesquivelmusic.daw.core.analysis.LosslessIntegrityChecker}.
     *
     * <p>The algorithm computes an averaged spectrum over overlapping
     * Hann-windowed FFT frames and locates the highest bin above which the
     * magnitude drops by more than {@value #CUTOFF_DROP_DB} dB relative to
     * the 200 Hz–4 kHz in-band mean and stays below that threshold.</p>
     *
     * @param samples    the audio samples (mono)
     * @param sampleRate the sample rate in Hz
     * @return the detected cutoff in Hz, or {@code -1.0} if no cutoff is
     *         detected (e.g., the signal is broadband up to Nyquist, too
     *         short, or below the analysis floor)
     * @throws IllegalArgumentException if {@code samples} is {@code null} or
     *                                  {@code sampleRate <= 0}
     */
    public static double detectCutoffHz(float[] samples, double sampleRate) {
        if (samples == null) {
            throw new IllegalArgumentException("samples must not be null");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (samples.length < FFT_SIZE) {
            return -1.0;
        }

        int binCount = FFT_SIZE / 2;
        double[] avgMagDb = new double[binCount];
        double[] window = FftUtils.createHannWindow(FFT_SIZE);
        double[] real = new double[FFT_SIZE];
        double[] imag = new double[FFT_SIZE];

        int hop = FFT_SIZE / 2;
        int frames = 0;
        double[] sumMag = new double[binCount];

        for (int start = 0; start + FFT_SIZE <= samples.length; start += hop) {
            for (int i = 0; i < FFT_SIZE; i++) {
                real[i] = samples[start + i] * window[i];
                imag[i] = 0.0;
            }
            FftUtils.fft(real, imag);
            for (int k = 0; k < binCount; k++) {
                sumMag[k] += Math.sqrt(real[k] * real[k] + imag[k] * imag[k]);
            }
            frames++;
        }

        if (frames == 0) {
            return -1.0;
        }

        for (int k = 0; k < binCount; k++) {
            double m = sumMag[k] / frames;
            avgMagDb[k] = m > 0 ? 20.0 * Math.log10(m) : DB_FLOOR;
        }

        double binHz = sampleRate / FFT_SIZE;
        double nyquist = sampleRate * 0.5;

        int refLo = Math.max(1, (int) Math.round(200.0 / binHz));
        int refHi = Math.min(binCount - 1, (int) Math.round(4000.0 / binHz));
        if (refHi <= refLo) {
            return -1.0;
        }
        double inBandMean = 0.0;
        for (int k = refLo; k <= refHi; k++) {
            inBandMean += avgMagDb[k];
        }
        inBandMean /= (refHi - refLo + 1);

        if (inBandMean <= DB_FLOOR + 10.0) {
            return -1.0;
        }

        double threshold = inBandMean - CUTOFF_DROP_DB;

        for (int k = binCount - 1; k > refHi; k--) {
            if (avgMagDb[k] > threshold) {
                int stable = 0;
                for (int j = k; j >= Math.max(refHi, k - 16); j--) {
                    if (avgMagDb[j] > threshold) stable++;
                }
                if (stable >= 8) {
                    double cutoff = (k + 1) * binHz;
                    return cutoff < nyquist * 0.97 ? cutoff : -1.0;
                }
            }
        }
        return -1.0;
    }
}
