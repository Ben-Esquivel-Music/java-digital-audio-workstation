package com.benesquivelmusic.daw.core.dsp.eq;

import com.benesquivelmusic.daw.core.dsp.BiquadFilter;
import com.benesquivelmusic.daw.core.dsp.LinearPhaseFilter;
import com.benesquivelmusic.daw.core.mixer.InsertEffect;
import com.benesquivelmusic.daw.core.reference.ReferenceTrack;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;
import java.util.Objects;

/**
 * Spectrum-matched (a.k.a. "match EQ") processor.
 *
 * <p>Analyzes the long-term-average magnitude spectrum of a source signal and
 * a reference signal, computes the difference as a target frequency response,
 * and applies that response as either a minimum-phase IIR cascade of
 * third-octave peak biquads or a linear-phase FIR filter.</p>
 *
 * <h2>Typical workflow</h2>
 * <ol>
 *   <li>Load a reference ({@link #analyzeReference(float[][])} or
 *       {@link #analyzeReference(ReferenceTrack)}).</li>
 *   <li>Capture the source spectrum ({@link #analyzeSource(float[][])} or
 *       {@link #captureSource()} during playback).</li>
 *   <li>Call {@link #updateMatch()} to rebuild the matching filter.</li>
 *   <li>Feed audio through {@link #process(float[][], float[][], int)}.
 *       Before a match filter is built the processor is a unit pass-through.</li>
 * </ol>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li>FFT size — 1024 / 2048 / 4096.</li>
 *   <li>Smoothing — critical-band (Bark), third-octave, or sixth-octave.</li>
 *   <li>Amount — 0 .. 1 blend between an identity curve and the full match.</li>
 *   <li>Phase mode — minimum-phase biquad cascade or linear-phase FIR.</li>
 * </ul>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
@RealTimeSafe
@InsertEffect(type = "MATCH_EQ", displayName = "Match EQ")
public final class MatchEqProcessor implements AudioProcessor {

    /** Permitted FFT sizes for spectral analysis. */
    public enum FftSize {
        SIZE_1024(1024),
        SIZE_2048(2048),
        SIZE_4096(4096);

        private final int value;
        FftSize(int value) { this.value = value; }
        public int value() { return value; }

        /** Returns the enum constant for the given FFT length. */
        public static FftSize of(int value) {
            for (FftSize f : values()) if (f.value == value) return f;
            throw new IllegalArgumentException("unsupported FFT size: " + value);
        }
    }

    /** Spectrum smoothing mode. */
    public enum Smoothing {
        /** Psychoacoustic critical-band (Bark-scale) smoothing. */
        CRITICAL_BAND,
        /** 1/3-octave smoothing. */
        THIRD_OCTAVE,
        /** 1/6-octave smoothing. */
        SIXTH_OCTAVE
    }

    /** Filter implementation / phase mode. */
    public enum PhaseMode {
        /** Minimum-phase IIR cascade of third-octave peak biquads (zero latency). */
        MINIMUM_PHASE,
        /** Linear-phase FIR filter (constant group delay, introduces latency). */
        LINEAR_PHASE
    }

    /** Default FIR order used for {@link PhaseMode#LINEAR_PHASE}. */
    public static final int DEFAULT_FIR_ORDER = 2047;

    /** ISO 1/3-octave center frequencies used for the minimum-phase cascade. */
    private static final double[] THIRD_OCTAVE_BANDS = {
            20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400,
            500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000,
            6300, 8000, 10000, 12500, 16000, 20000
    };

    /** Per-band Q factor for the minimum-phase cascade (third-octave ≈ 4.318). */
    private static final double CASCADE_Q = 4.318;

    /** Maximum per-band gain magnitude for the minimum-phase cascade (±24 dB). */
    private static final double MAX_BAND_GAIN_DB = 24.0;

    private final int channels;
    private final double sampleRate;

    // Parameters
    private FftSize fftSize = FftSize.SIZE_2048;
    private Smoothing smoothing = Smoothing.THIRD_OCTAVE;
    private double amount = 1.0;
    private PhaseMode phaseMode = PhaseMode.MINIMUM_PHASE;
    private int firOrder = DEFAULT_FIR_ORDER;

    // Captured spectra (length fftSize/2 + 1). May be null before analysis.
    private double[] sourceSpectrum;
    private double[] referenceSpectrum;

    // Live ("capture source") running spectrum accumulator.
    private double[] liveAccumulator;   // sum of per-bin magnitudes
    private long liveFrameCount;         // FFT frames accumulated
    private final float[][] liveInputBuffer; // ring buffer per channel
    private int liveWriteIndex;
    // Pre-allocated scratch for the live-capture FFT. Allocated by
    // startLiveCapture() (or setFftSize while capturing) so process() never
    // allocates on the audio thread.
    private double[] liveWindow;
    private double[] liveFftRe;
    private double[] liveFftIm;

    // Last-computed target curve (length fftSize/2 + 1).
    private double[] targetCurve;

    // Active filters. One per channel for FIR. Cascade is shared across channels
    // but needs per-channel state so we keep channel arrays for both modes.
    private BiquadFilter[][] cascade;       // [channel][band]
    private LinearPhaseFilter[] firFilters; // [channel]

    /**
     * Creates a match EQ processor.
     *
     * @param channels   number of audio channels (must be positive)
     * @param sampleRate the sample rate in Hz (must be positive)
     */
    public MatchEqProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.liveInputBuffer = new float[channels][fftSize.value()];
    }

    // ---- Parameter getters / setters --------------------------------------

    public FftSize getFftSize() { return fftSize; }

    /**
     * Sets the FFT size used for spectral analysis and filter design.
     * Clears any captured spectra (their length no longer matches) and
     * invalidates the current match filter.
     */
    public void setFftSize(FftSize size) {
        this.fftSize = Objects.requireNonNull(size, "size must not be null");
        this.sourceSpectrum = null;
        this.referenceSpectrum = null;
        this.targetCurve = null;
        this.cascade = null;
        this.firFilters = null;
        this.liveAccumulator = null;
        this.liveFrameCount = 0;
        this.liveWriteIndex = 0;
        this.liveWindow = null;
        this.liveFftRe = null;
        this.liveFftIm = null;
        for (int c = 0; c < channels; c++) {
            this.liveInputBuffer[c] = new float[size.value()];
        }
    }

    public Smoothing getSmoothing() { return smoothing; }

    public void setSmoothing(Smoothing s) {
        this.smoothing = Objects.requireNonNull(s, "smoothing must not be null");
    }

    public double getAmount() { return amount; }

    /**
     * Sets the match amount.
     *
     * @param amount0to1 0.0 = bypass (identity curve), 1.0 = full spectrum match
     */
    public void setAmount(double amount0to1) {
        if (!Double.isFinite(amount0to1) || amount0to1 < 0.0 || amount0to1 > 1.0) {
            throw new IllegalArgumentException(
                    "amount must be in [0, 1]: " + amount0to1);
        }
        this.amount = amount0to1;
    }

    public PhaseMode getPhaseMode() { return phaseMode; }

    public void setPhaseMode(PhaseMode mode) {
        this.phaseMode = Objects.requireNonNull(mode, "mode must not be null");
    }

    public int getFirOrder() { return firOrder; }

    public void setFirOrder(int firOrder) {
        if (firOrder < 3) {
            throw new IllegalArgumentException("firOrder must be >= 3: " + firOrder);
        }
        this.firOrder = firOrder;
    }

    /**
     * Returns the processing latency in samples. Zero in minimum-phase mode;
     * {@code (firOrder - 1) / 2} (after rounding firOrder to odd) in
     * linear-phase mode when a match is active.
     */
    public int getLatencySamples() {
        if (phaseMode == PhaseMode.LINEAR_PHASE && firFilters != null) {
            return firFilters[0].getLatency();
        }
        return 0;
    }

    // ---- Spectrum capture / analysis --------------------------------------

    /**
     * Analyzes the long-term-average magnitude spectrum of the given
     * (channel-interleaved) audio buffer and stores it as the source spectrum.
     *
     * <p>The spectrum is averaged over all channels and all FFT frames (Hann
     * window, 50% overlap).</p>
     *
     * @param audio audio data as {@code [channel][sample]}
     */
    public void analyzeSource(float[][] audio) {
        this.sourceSpectrum = computeLtas(audio);
    }

    /**
     * Analyzes the long-term-average magnitude spectrum of the given audio
     * and stores it as the reference spectrum.
     *
     * @param audio audio data as {@code [channel][sample]}
     */
    public void analyzeReference(float[][] audio) {
        this.referenceSpectrum = computeLtas(audio);
    }

    /**
     * Analyzes the reference spectrum from a {@link ReferenceTrack} loaded by
     * story 041. No-op if the track has no audio data.
     *
     * @param track the reference track to analyze
     */
    public void analyzeReference(ReferenceTrack track) {
        Objects.requireNonNull(track, "track must not be null");
        float[][] data = track.getAudioData();
        if (data == null) return;
        analyzeReference(data);
    }

    /**
     * Directly sets the source spectrum (for deserialization or scripted use).
     * The array length must equal {@code fftSize/2 + 1}.
     */
    public void setSourceSpectrum(double[] spectrum) {
        this.sourceSpectrum = copySpectrum(spectrum, "source");
    }

    /**
     * Directly sets the reference spectrum (for deserialization or scripted use).
     * The array length must equal {@code fftSize/2 + 1}.
     */
    public void setReferenceSpectrum(double[] spectrum) {
        this.referenceSpectrum = copySpectrum(spectrum, "reference");
    }

    public double[] getSourceSpectrum() {
        return sourceSpectrum == null ? null : sourceSpectrum.clone();
    }

    public double[] getReferenceSpectrum() {
        return referenceSpectrum == null ? null : referenceSpectrum.clone();
    }

    /** Returns the most recently computed target magnitude curve, or {@code null}. */
    public double[] getTargetCurve() {
        return targetCurve == null ? null : targetCurve.clone();
    }

    /** Returns {@code true} if a matching filter is currently active. */
    public boolean isMatchActive() {
        return (phaseMode == PhaseMode.MINIMUM_PHASE && cascade != null)
            || (phaseMode == PhaseMode.LINEAR_PHASE && firFilters != null);
    }

    /**
     * Freezes the currently-accumulated live source spectrum (populated by
     * pass-through calls to {@link #process(float[][], float[][], int)} when
     * live capture is enabled) as the source spectrum.
     *
     * @return {@code true} if enough frames have been accumulated; {@code false}
     *         if the live accumulator is empty (nothing to capture)
     */
    public boolean captureSource() {
        if (liveAccumulator == null || liveFrameCount == 0) return false;
        double[] avg = new double[liveAccumulator.length];
        for (int k = 0; k < avg.length; k++) {
            avg[k] = liveAccumulator[k] / liveFrameCount;
        }
        this.sourceSpectrum = avg;
        return true;
    }

    /** Enables live source spectrum capture driven by {@code process()}. */
    public void startLiveCapture() {
        int size = fftSize.value();
        this.liveAccumulator = new double[size / 2 + 1];
        this.liveFrameCount = 0;
        this.liveWriteIndex = 0;
        for (int c = 0; c < channels; c++) {
            Arrays.fill(this.liveInputBuffer[c], 0f);
        }
        // Preallocate FFT scratch so accumulateLive() (called on the audio
        // thread) never has to allocate.
        this.liveWindow = hann(size);
        this.liveFftRe = new double[size];
        this.liveFftIm = new double[size];
    }

    /** Stops live capture and clears the running accumulator. */
    public void stopLiveCapture() {
        this.liveAccumulator = null;
        this.liveFrameCount = 0;
        this.liveWindow = null;
        this.liveFftRe = null;
        this.liveFftIm = null;
    }

    // ---- Match curve + filter build --------------------------------------

    /**
     * Rebuilds the matching filter from the currently captured source and
     * reference spectra.
     *
     * <p>If either spectrum is missing, the current filter is cleared and the
     * processor becomes a pass-through.</p>
     */
    public void updateMatch() {
        if (sourceSpectrum == null || referenceSpectrum == null) {
            clearFilters();
            targetCurve = null;
            return;
        }

        int half = fftSize.value() / 2 + 1;
        // 1) smooth both spectra
        double[] smoothSrc = smooth(sourceSpectrum);
        double[] smoothRef = smooth(referenceSpectrum);

        // 2) build target magnitude as amount-blended ratio ref/src
        double[] target = new double[half];
        double floor = 1e-12;
        for (int k = 0; k < half; k++) {
            double ratio = (smoothRef[k] + floor) / (smoothSrc[k] + floor);
            // Blend: amount=0 → 1 (flat), amount=1 → ratio. In log domain the
            // blend is perceptually linear, so interpolate in log magnitude.
            double ratioDb = 20.0 * Math.log10(Math.max(ratio, floor));
            double blended = amount * ratioDb;
            target[k] = Math.pow(10.0, blended / 20.0);
        }
        this.targetCurve = target;

        clearFilters();
        switch (phaseMode) {
            case MINIMUM_PHASE -> buildCascade(target);
            case LINEAR_PHASE  -> buildFir(target);
        }
    }

    private void buildCascade(double[] target) {
        // Sample the target curve at each third-octave center frequency and
        // build a cascade of peak biquads with that gain per band.
        int half = target.length;
        int fft = (half - 1) * 2;
        BiquadFilter[][] bank = new BiquadFilter[channels][];
        for (int c = 0; c < channels; c++) {
            BiquadFilter[] bands = new BiquadFilter[THIRD_OCTAVE_BANDS.length];
            for (int b = 0; b < THIRD_OCTAVE_BANDS.length; b++) {
                double freq = THIRD_OCTAVE_BANDS[b];
                if (freq >= sampleRate * 0.5) {
                    bands[b] = BiquadFilter.create(
                            BiquadFilter.FilterType.PEAK_EQ, sampleRate,
                            sampleRate * 0.49, CASCADE_Q, 0.0);
                    continue;
                }
                double bin = freq * fft / sampleRate;
                double mag = interp(target, bin);
                double gainDb = 20.0 * Math.log10(Math.max(mag, 1e-6));
                gainDb = Math.max(-MAX_BAND_GAIN_DB, Math.min(MAX_BAND_GAIN_DB, gainDb));
                bands[b] = BiquadFilter.create(
                        BiquadFilter.FilterType.PEAK_EQ, sampleRate,
                        freq, CASCADE_Q, gainDb);
            }
            bank[c] = bands;
        }
        this.cascade = bank;
    }

    private void buildFir(double[] target) {
        int order = firOrder % 2 == 0 ? firOrder + 1 : firOrder;
        LinearPhaseFilter[] fb = new LinearPhaseFilter[channels];
        for (int c = 0; c < channels; c++) {
            fb[c] = LinearPhaseFilter.fromMagnitudeResponse(target, order);
        }
        this.firFilters = fb;
    }

    private void clearFilters() {
        this.cascade = null;
        this.firFilters = null;
    }

    // ---- AudioProcessor ---------------------------------------------------

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        // Always mirror input → output first, then filter in-place if we have
        // a matching filter. This also lets us accumulate the live spectrum
        // from the input signal non-destructively.
        int nCh = Math.min(channels, Math.min(inputBuffer.length, outputBuffer.length));
        for (int c = 0; c < nCh; c++) {
            System.arraycopy(inputBuffer[c], 0, outputBuffer[c], 0, numFrames);
        }

        if (liveAccumulator != null) {
            accumulateLive(inputBuffer, nCh, numFrames);
        }

        if (phaseMode == PhaseMode.MINIMUM_PHASE && cascade != null) {
            for (int c = 0; c < nCh; c++) {
                BiquadFilter[] bands = cascade[c];
                for (BiquadFilter bq : bands) {
                    bq.process(outputBuffer[c], 0, numFrames);
                }
            }
        } else if (phaseMode == PhaseMode.LINEAR_PHASE && firFilters != null) {
            for (int c = 0; c < nCh; c++) {
                firFilters[c].process(outputBuffer[c], 0, numFrames);
            }
        }
    }

    @Override
    public void reset() {
        if (cascade != null) {
            for (BiquadFilter[] bands : cascade) {
                for (BiquadFilter bq : bands) bq.reset();
            }
        }
        if (firFilters != null) {
            for (LinearPhaseFilter f : firFilters) f.reset();
        }
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }

    /** Returns the sample rate this processor was constructed with, in Hz. */
    public double getSampleRate() { return sampleRate; }

    /** Returns the number of audio channels this processor was constructed with. */
    public int getChannelCount() { return channels; }

    // ---- Internal helpers -------------------------------------------------

    /**
     * Computes the long-term-average magnitude spectrum (mean over channels,
     * Hann window, 50% overlap) at the current FFT size.
     */
    private double[] computeLtas(float[][] audio) {
        Objects.requireNonNull(audio, "audio must not be null");
        int size = fftSize.value();
        int hop = size / 2;
        int half = size / 2 + 1;
        double[] sum = new double[half];
        long frames = 0;

        double[] window = hann(size);
        double[] re = new double[size];
        double[] im = new double[size];

        for (float[] channel : audio) {
            if (channel == null || channel.length < size) continue;
            int last = channel.length - size;
            for (int start = 0; start <= last; start += hop) {
                Arrays.fill(im, 0.0);
                for (int i = 0; i < size; i++) {
                    re[i] = channel[start + i] * window[i];
                }
                fft(re, im, size);
                for (int k = 0; k < half; k++) {
                    double mag = Math.hypot(re[k], im[k]);
                    sum[k] += mag;
                }
                frames++;
            }
        }

        if (frames == 0) {
            throw new IllegalArgumentException(
                    "audio too short for FFT size " + size + " (need >= " + size + " samples)");
        }
        for (int k = 0; k < half; k++) sum[k] /= frames;
        return sum;
    }

    private void accumulateLive(float[][] input, int nCh, int numFrames) {
        int size = fftSize.value();
        int half = size / 2 + 1;
        // Scratch buffers are preallocated by startLiveCapture(); bail out if
        // capture wasn't started (defensive, the caller already checks).
        double[] window = liveWindow;
        double[] re = liveFftRe;
        double[] im = liveFftIm;
        if (window == null || re == null || im == null) return;

        for (int frame = 0; frame < numFrames; frame++) {
            for (int c = 0; c < nCh; c++) {
                liveInputBuffer[c][liveWriteIndex] = input[c][frame];
            }
            liveWriteIndex++;
            if (liveWriteIndex >= size) {
                // Full block captured — take FFT of each channel and accumulate.
                for (int c = 0; c < nCh; c++) {
                    Arrays.fill(im, 0.0);
                    for (int i = 0; i < size; i++) {
                        re[i] = liveInputBuffer[c][i] * window[i];
                    }
                    fft(re, im, size);
                    for (int k = 0; k < half; k++) {
                        liveAccumulator[k] += Math.hypot(re[k], im[k]);
                    }
                    liveFrameCount++;
                }
                // Shift by hop (50%) for overlap on next accumulation.
                int hop = size / 2;
                for (int c = 0; c < nCh; c++) {
                    System.arraycopy(liveInputBuffer[c], hop,
                            liveInputBuffer[c], 0, size - hop);
                }
                liveWriteIndex = size - hop;
            }
        }
    }

    /** Smooths a half-spectrum magnitude array using the current smoothing mode. */
    private double[] smooth(double[] halfSpectrum) {
        int half = halfSpectrum.length;
        int fft = (half - 1) * 2;
        double binHz = sampleRate / fft;
        double[] out = new double[half];
        for (int k = 0; k < half; k++) {
            double freq = Math.max(k * binHz, binHz * 0.5);
            double bw = smoothingBandwidthHz(freq);
            int loBin = Math.max(0, (int) Math.floor((freq - bw * 0.5) / binHz));
            int hiBin = Math.min(half - 1, (int) Math.ceil((freq + bw * 0.5) / binHz));
            double sum = 0.0;
            int count = 0;
            for (int j = loBin; j <= hiBin; j++) {
                sum += halfSpectrum[j];
                count++;
            }
            out[k] = count == 0 ? halfSpectrum[k] : sum / count;
        }
        return out;
    }

    private double smoothingBandwidthHz(double freqHz) {
        return switch (smoothing) {
            case THIRD_OCTAVE -> freqHz * (Math.pow(2, 1.0 / 6) - Math.pow(2, -1.0 / 6));
            case SIXTH_OCTAVE -> freqHz * (Math.pow(2, 1.0 / 12) - Math.pow(2, -1.0 / 12));
            // Traunmüller-style Bark bandwidth, used as a critical-band proxy.
            case CRITICAL_BAND ->
                    25.0 + 75.0 * Math.pow(1.0 + 1.4 * Math.pow(freqHz / 1000.0, 2.0), 0.69);
        };
    }

    private double[] copySpectrum(double[] spectrum, String label) {
        Objects.requireNonNull(spectrum, label + " spectrum must not be null");
        int expected = fftSize.value() / 2 + 1;
        if (spectrum.length != expected) {
            throw new IllegalArgumentException(
                    label + " spectrum length " + spectrum.length
                    + " does not match expected " + expected + " for FFT size "
                    + fftSize.value());
        }
        return spectrum.clone();
    }

    private static double interp(double[] array, double pos) {
        if (pos <= 0) return array[0];
        if (pos >= array.length - 1) return array[array.length - 1];
        int lo = (int) Math.floor(pos);
        double frac = pos - lo;
        return array[lo] * (1.0 - frac) + array[lo + 1] * frac;
    }

    private static double[] hann(int size) {
        double[] w = new double[size];
        for (int i = 0; i < size; i++) {
            w[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        return w;
    }

    // In-place radix-2 Cooley–Tukey FFT. Equivalent to the helper in
    // LinearPhaseFilter, duplicated here to keep the eq subpackage free of
    // package-private coupling to the parent DSP package.
    private static void fft(double[] real, double[] imag, int n) {
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                double t = real[i]; real[i] = real[j]; real[j] = t;
                t = imag[i]; imag[i] = imag[j]; imag[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wR = Math.cos(ang), wI = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cR = 1.0, cI = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = u + len / 2;
                    double tR = cR * real[v] - cI * imag[v];
                    double tI = cR * imag[v] + cI * real[v];
                    real[v] = real[u] - tR;
                    imag[v] = imag[u] - tI;
                    real[u] += tR;
                    imag[u] += tI;
                    double nR = cR * wR - cI * wI;
                    cI = cR * wI + cI * wR;
                    cR = nR;
                }
            }
        }
    }
}
