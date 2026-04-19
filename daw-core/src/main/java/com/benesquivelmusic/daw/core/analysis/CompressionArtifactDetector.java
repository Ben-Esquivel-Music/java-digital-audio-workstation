package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.analysis.WindowType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Forensic analyzer that detects and classifies the statistical signatures of
 * lossy (perceptual) audio coding — primarily MP3 and AAC.
 *
 * <p>This detector complements pure bit-level integrity checking by measuring
 * the telltale spectral and temporal fingerprints that perceptual codecs
 * leave behind even after decoding back to PCM:</p>
 *
 * <ul>
 *   <li><b>Spectral low-pass cutoff</b> — MP3/AAC encoders aggressively
 *       discard content above an encoder-specific cutoff (typically ~11 kHz
 *       at 64 kbps, ~16 kHz at 128 kbps, ~19 kHz at 192 kbps, ~20 kHz at
 *       320 kbps). The sharp spectral "cliff" is the single strongest
 *       indicator of lossy encoding.</li>
 *   <li><b>Critical-band energy ratios</b> — per the 2022 AES paper
 *       <i>MP3 compression classification through audio analysis
 *       statistics</i>, the ratio of energy in adjacent Bark-scale bands
 *       above / below the codec cutoff varies systematically with bitrate.</li>
 *   <li><b>Pre-echo</b> — block-based codecs (MP3, AAC) smear transient
 *       energy backwards across the analysis block, producing a low-level
 *       tonal "whoosh" before sharp onsets.</li>
 *   <li><b>"Birdie" artifacts</b> — isolated narrowband tonal spikes (single
 *       FFT bins significantly above their local background) produced by
 *       quantization of individual MDCT coefficients at low bitrates.</li>
 * </ul>
 *
 * <p>Built entirely on top of {@link FftUtils} and follows the windowed-FFT
 * conventions used by {@link SpectrumAnalyzer}. Pure Java, no JNI.</p>
 *
 * <p><b>References</b></p>
 * <ul>
 *   <li>AES 2022 — <i>MP3 compression classification through audio analysis
 *       statistics</i></li>
 *   <li>AES 2018 — <i>Comparing the Effect of Audio Coding Artifacts on
 *       Objective Quality Measures and on Subjective Ratings</i></li>
 * </ul>
 *
 * @see FftUtils
 * @see SpectrumAnalyzer
 */
public final class CompressionArtifactDetector {

    /** Likely codec family detected by the classifier. */
    public enum CodecType {
        /** No lossy-coding artifacts detected; signal appears lossless. */
        LOSSLESS,
        /** Signature is consistent with MP3 (sharp spectral cliff, block pre-echo). */
        MP3,
        /** Signature is consistent with AAC (softer cutoff, fewer birdies than MP3). */
        AAC,
        /** Lossy compression is likely but the specific codec cannot be determined. */
        UNKNOWN_LOSSY
    }

    /** A single detected artifact at a given time / frequency location. */
    public enum ArtifactKind {
        /** Isolated narrowband tonal spike in the high-frequency region. */
        BIRDIE,
        /** Pre-echo: low-level tonal energy preceding a transient onset. */
        PRE_ECHO,
        /** Sharp spectral low-pass cutoff. */
        SPECTRAL_CUTOFF
    }

    /**
     * A single artifact location.
     *
     * @param kind         artifact type
     * @param timeSeconds  time at which the artifact was observed (seconds from start),
     *                     or {@code -1} for global artifacts (e.g. spectral cutoff)
     * @param frequencyHz  centre frequency of the artifact in Hz,
     *                     or {@code -1} if not applicable
     * @param magnitudeDb  magnitude / prominence of the artifact in dB
     */
    public record ArtifactLocation(ArtifactKind kind,
                                   double timeSeconds,
                                   double frequencyHz,
                                   double magnitudeDb) {}

    /**
     * Full forensic report returned by
     * {@link #analyze(float[], double)}.
     *
     * @param codec                   most likely codec family
     * @param estimatedBitrateKbps    estimated bitrate in kbps, or {@code -1}
     *                                if the signal is classified as lossless
     * @param spectralCutoffHz        estimated low-pass cutoff frequency in Hz,
     *                                or {@code -1} if no sharp cutoff detected
     * @param preEchoCount            number of pre-echo events detected
     * @param birdieCount             number of birdie artifacts detected
     * @param severityScore           overall artifact severity in [0.0, 1.0]
     *                                (0 = pristine, 1 = severely compressed)
     * @param artifactLocations       immutable list of individual artifact
     *                                locations (time / frequency)
     */
    public record Report(CodecType codec,
                         int estimatedBitrateKbps,
                         double spectralCutoffHz,
                         int preEchoCount,
                         int birdieCount,
                         double severityScore,
                         List<ArtifactLocation> artifactLocations) {
        public Report {
            artifactLocations = List.copyOf(artifactLocations);
        }
    }

    // --- Configuration ------------------------------------------------------

    private static final int FFT_SIZE = 4096;
    private static final int HOP_SIZE = FFT_SIZE / 2;
    private static final double DB_FLOOR = -120.0;

    /**
     * MP3 cutoff → bitrate mapping (kbps), in descending cutoff order.
     * Derived from the encoder tables documented in the AES 2022 paper and
     * corroborated by the LAME encoder defaults.
     */
    private static final double[] BITRATE_CUTOFFS_HZ =
            { 20500, 19500, 17000, 16000, 15500, 11000, 5500 };
    private static final int[] BITRATE_KBPS =
            { 320,   192,   160,   128,   96,    64,    32  };

    /**
     * Maximum number of artifact locations returned in a {@link Report} —
     * keeps memory bounded on very long inputs.
     */
    private static final int MAX_ARTIFACT_LOCATIONS = 1024;

    /** dB drop from in-band average that counts as a "spectral cliff". */
    private static final double CUTOFF_DROP_DB = 40.0;

    /** dB prominence over the local spectral background to count as a birdie. */
    private static final double BIRDIE_PROMINENCE_DB = 18.0;

    /**
     * Minimum frequency (as a fraction of Nyquist) in which birdies are
     * searched; perceptual codecs rarely put birdies below ~4 kHz.
     */
    private static final double BIRDIE_MIN_FREQ_FRACTION = 0.2;

    /**
     * Time-domain energy ratio (pre-onset / onset) above which a transient is
     * flagged as pre-echo smeared by a block-based codec.
     */
    private static final double PRE_ECHO_RATIO = 0.25;

    /** Analysis window width for pre-echo detection, in samples. */
    private static final int PRE_ECHO_LOOKBACK = 256;

    /** Creates a new stateless detector. */
    public CompressionArtifactDetector() {
    }

    // --- Public API ---------------------------------------------------------

    /**
     * Analyzes a mono audio buffer and returns a full forensic report.
     *
     * @param samples     mono audio samples in [-1.0, 1.0]
     * @param sampleRate  sample rate in Hz ({@code > 0})
     * @return the forensic {@link Report}
     * @throws IllegalArgumentException if {@code samples} is null or
     *         {@code sampleRate} is not positive
     */
    public Report analyze(float[] samples, double sampleRate) {
        if (samples == null) {
            throw new IllegalArgumentException("samples must not be null");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }

        var locations = new ArrayList<ArtifactLocation>();

        // --- 1. Average magnitude spectrum across the whole buffer ---------
        double[] avgMagDb = averageMagnitudeSpectrumDb(samples, sampleRate);

        // --- 2. Spectral cutoff detection ----------------------------------
        double cutoffHz = detectSpectralCutoff(avgMagDb, sampleRate);
        if (cutoffHz > 0) {
            double nyquist = sampleRate * 0.5;
            // Only flag as a cliff-style artifact if the cutoff is meaningfully
            // below Nyquist (otherwise it's just a natural bandlimit).
            if (cutoffHz < nyquist * 0.97) {
                locations.add(new ArtifactLocation(
                        ArtifactKind.SPECTRAL_CUTOFF, -1.0, cutoffHz, CUTOFF_DROP_DB));
            } else {
                cutoffHz = -1.0;
            }
        }

        // --- 3. Critical-band energy ratios (used by the classifier) ------
        double highBandAttenuationDb =
                highBandAttenuationDb(avgMagDb, sampleRate, cutoffHz);

        // --- 4. Birdie detection (per-frame, high-frequency region) -------
        int birdieCount = detectBirdies(samples, sampleRate, cutoffHz, locations);

        // --- 5. Pre-echo detection (time domain, around transients) -------
        int preEchoCount = detectPreEcho(samples, sampleRate, locations);

        // --- 6. Classification & severity ---------------------------------
        int estimatedBitrate = classifyBitrate(cutoffHz);
        CodecType codec = classifyCodec(cutoffHz, highBandAttenuationDb,
                                        birdieCount, preEchoCount);
        double severity = computeSeverity(cutoffHz, sampleRate,
                                          highBandAttenuationDb,
                                          birdieCount, preEchoCount);

        // Trim artifact list to the configured maximum.
        List<ArtifactLocation> trimmed = locations.size() <= MAX_ARTIFACT_LOCATIONS
                ? locations
                : locations.subList(0, MAX_ARTIFACT_LOCATIONS);

        return new Report(codec, estimatedBitrate, cutoffHz,
                          preEchoCount, birdieCount, severity,
                          Collections.unmodifiableList(new ArrayList<>(trimmed)));
    }

    // --- Internals: averaged spectrum --------------------------------------

    private static double[] averageMagnitudeSpectrumDb(float[] samples, double sampleRate) {
        int binCount = FFT_SIZE / 2;
        double[] sumMag = new double[binCount];
        double[] window = FftUtils.createWindow(WindowType.HANN, FFT_SIZE);
        double[] real = new double[FFT_SIZE];
        double[] imag = new double[FFT_SIZE];
        int frames = 0;

        for (int start = 0; start + FFT_SIZE <= samples.length; start += HOP_SIZE) {
            for (int i = 0; i < FFT_SIZE; i++) {
                real[i] = samples[start + i] * window[i];
                imag[i] = 0.0;
            }
            FftUtils.fft(real, imag);
            for (int k = 0; k < binCount; k++) {
                double mag = Math.sqrt(real[k] * real[k] + imag[k] * imag[k]) / FFT_SIZE;
                sumMag[k] += mag;
            }
            frames++;
        }

        double[] db = new double[binCount];
        if (frames == 0) {
            Arrays.fill(db, DB_FLOOR);
            return db;
        }
        for (int k = 0; k < binCount; k++) {
            double avg = sumMag[k] / frames;
            db[k] = avg > 0 ? Math.max(20.0 * Math.log10(avg), DB_FLOOR) : DB_FLOOR;
        }
        return db;
    }

    // --- Internals: spectral cutoff ----------------------------------------

    /**
     * Locates the frequency above which the average spectrum drops by more
     * than {@link #CUTOFF_DROP_DB} below its in-band mean and stays there.
     *
     * @return cutoff in Hz, or {@code -1.0} if no sharp cliff is found
     */
    private static double detectSpectralCutoff(double[] avgMagDb, double sampleRate) {
        int binCount = avgMagDb.length;
        double binHz = sampleRate / FFT_SIZE;

        // In-band reference: mean magnitude in the 200 Hz .. 4 kHz region,
        // which almost always contains energy in real music.
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

        // If the signal is silent or near-silent, do not report a cutoff.
        if (inBandMean <= DB_FLOOR + 10.0) {
            return -1.0;
        }

        double threshold = inBandMean - CUTOFF_DROP_DB;

        // Walk from high frequency downwards; find the highest bin whose
        // magnitude exceeds the threshold. The next bin above it is the cutoff.
        for (int k = binCount - 1; k > refHi; k--) {
            if (avgMagDb[k] > threshold) {
                // Require at least 8 consecutive bins above threshold below
                // it to avoid noise triggering a false cliff.
                int stable = 0;
                for (int j = k; j >= Math.max(refHi, k - 16); j--) {
                    if (avgMagDb[j] > threshold) stable++;
                }
                if (stable >= 8) {
                    return (k + 1) * binHz;
                }
            }
        }
        return -1.0;
    }

    private static double highBandAttenuationDb(double[] avgMagDb, double sampleRate,
                                                double cutoffHz) {
        int binCount = avgMagDb.length;
        double binHz = sampleRate / FFT_SIZE;
        double nyquist = sampleRate * 0.5;

        int lowLo = Math.max(1, (int) Math.round(200.0 / binHz));
        int lowHi = Math.min(binCount - 1, (int) Math.round(2000.0 / binHz));

        double highStartHz = cutoffHz > 0 ? cutoffHz : nyquist * 0.9;
        int highLo = Math.min(binCount - 1, (int) Math.round(highStartHz / binHz));
        int highHi = binCount - 1;
        if (highHi <= highLo || lowHi <= lowLo) {
            return 0.0;
        }

        double lowMean = 0.0;
        for (int k = lowLo; k <= lowHi; k++) lowMean += avgMagDb[k];
        lowMean /= (lowHi - lowLo + 1);

        double highMean = 0.0;
        for (int k = highLo; k <= highHi; k++) highMean += avgMagDb[k];
        highMean /= (highHi - highLo + 1);

        return lowMean - highMean;
    }

    // --- Internals: birdies -------------------------------------------------

    private static int detectBirdies(float[] samples, double sampleRate,
                                     double cutoffHz,
                                     List<ArtifactLocation> out) {
        int binCount = FFT_SIZE / 2;
        double binHz = sampleRate / FFT_SIZE;
        double nyquist = sampleRate * 0.5;

        double searchLoHz = Math.max(BIRDIE_MIN_FREQ_FRACTION * nyquist, 4000.0);
        double searchHiHz = cutoffHz > 0 ? cutoffHz : nyquist;
        int loBin = Math.max(2, (int) Math.round(searchLoHz / binHz));
        int hiBin = Math.min(binCount - 2, (int) Math.round(searchHiHz / binHz));
        if (hiBin - loBin < 16) {
            return 0;
        }

        double[] window = FftUtils.createWindow(WindowType.HANN, FFT_SIZE);
        double[] real = new double[FFT_SIZE];
        double[] imag = new double[FFT_SIZE];
        double[] magDb = new double[binCount];

        int count = 0;
        int frameIndex = 0;
        for (int start = 0; start + FFT_SIZE <= samples.length; start += HOP_SIZE, frameIndex++) {
            for (int i = 0; i < FFT_SIZE; i++) {
                real[i] = samples[start + i] * window[i];
                imag[i] = 0.0;
            }
            FftUtils.fft(real, imag);
            for (int k = 0; k < binCount; k++) {
                double mag = Math.sqrt(real[k] * real[k] + imag[k] * imag[k]) / FFT_SIZE;
                magDb[k] = mag > 0 ? Math.max(20.0 * Math.log10(mag), DB_FLOOR) : DB_FLOOR;
            }

            // Median-style local background over a 16-bin window, prominence = peak - median
            final int halfWin = 8;
            for (int k = loBin; k <= hiBin; k++) {
                int wLo = Math.max(0, k - halfWin);
                int wHi = Math.min(binCount - 1, k + halfWin);
                double[] buf = Arrays.copyOfRange(magDb, wLo, wHi + 1);
                Arrays.sort(buf);
                double median = buf[buf.length / 2];
                double prominence = magDb[k] - median;

                // Must also be a strict local maximum to count as isolated.
                if (prominence >= BIRDIE_PROMINENCE_DB
                        && magDb[k] > magDb[k - 1]
                        && magDb[k] > magDb[k + 1]) {
                    if (out.size() < MAX_ARTIFACT_LOCATIONS) {
                        double time = (start + FFT_SIZE * 0.5) / sampleRate;
                        out.add(new ArtifactLocation(
                                ArtifactKind.BIRDIE, time, k * binHz, prominence));
                    }
                    count++;
                }
            }
        }
        return count;
    }

    // --- Internals: pre-echo ------------------------------------------------

    /**
     * Detects pre-echo: low-level energy smeared backwards in time before
     * strong transients. Uses a simple energy-ratio test comparing the
     * {@link #PRE_ECHO_LOOKBACK}-sample window immediately preceding a
     * transient to the quieter window preceding that.
     */
    private static int detectPreEcho(float[] samples, double sampleRate,
                                     List<ArtifactLocation> out) {
        int lookback = PRE_ECHO_LOOKBACK;
        if (samples.length < 4 * lookback) {
            return 0;
        }

        // RMS envelope with hop = lookback / 2 for coarse transient detection.
        int hop = lookback / 2;
        int frames = (samples.length - lookback) / hop + 1;
        double[] env = new double[frames];
        for (int f = 0; f < frames; f++) {
            int off = f * hop;
            double sum = 0.0;
            for (int i = 0; i < lookback; i++) {
                double s = samples[off + i];
                sum += s * s;
            }
            env[f] = Math.sqrt(sum / lookback);
        }

        int count = 0;
        // A transient is a frame whose RMS rises sharply (≥12 dB) vs. the
        // preceding frame. Pre-echo is detected when that preceding frame
        // nonetheless carries significant energy relative to the deeper
        // baseline (five frames further back, safely before any smearing
        // window).
        for (int f = 5; f < frames; f++) {
            double prev = env[f - 1];
            double cur = env[f];
            if (cur < 1e-4 || cur <= prev * 4.0) {
                continue;
            }
            double baseline = env[f - 5];
            // prev must stand well above the deep baseline (smeared tail) and
            // must not itself be negligible relative to the transient.
            if (prev > baseline * 3.0 && prev > cur * PRE_ECHO_RATIO * 0.2) {
                if (out.size() < MAX_ARTIFACT_LOCATIONS) {
                    double time = ((f - 1) * hop + lookback * 0.5) / sampleRate;
                    double magDb = 20.0 * Math.log10(Math.max(prev, 1e-12));
                    out.add(new ArtifactLocation(
                            ArtifactKind.PRE_ECHO, time, -1.0, magDb));
                }
                count++;
            }
        }
        return count;
    }

    // --- Internals: classification -----------------------------------------

    private static int classifyBitrate(double cutoffHz) {
        if (cutoffHz <= 0) {
            return -1;
        }
        for (int i = 0; i < BITRATE_CUTOFFS_HZ.length; i++) {
            if (cutoffHz >= BITRATE_CUTOFFS_HZ[i]) {
                return BITRATE_KBPS[i];
            }
        }
        // Below 5.5 kHz — assume the lowest tabulated bitrate.
        return BITRATE_KBPS[BITRATE_KBPS.length - 1];
    }

    private static CodecType classifyCodec(double cutoffHz,
                                           double highBandAttenuationDb,
                                           int birdieCount,
                                           int preEchoCount) {
        // No meaningful cutoff, no birdies, no pre-echo → lossless.
        if (cutoffHz <= 0 && birdieCount == 0 && preEchoCount == 0
                && highBandAttenuationDb < 12.0) {
            return CodecType.LOSSLESS;
        }
        // MP3 classic signature: sharp cliff + birdies + pre-echo.
        if (cutoffHz > 0 && birdieCount > 0 && preEchoCount > 0) {
            return CodecType.MP3;
        }
        // AAC signature: cliff present, fewer birdies (AAC quantization is
        // smoother), pre-echo still possible due to MDCT blocks.
        if (cutoffHz > 0 && birdieCount <= 2) {
            return CodecType.AAC;
        }
        return CodecType.UNKNOWN_LOSSY;
    }

    private static double computeSeverity(double cutoffHz, double sampleRate,
                                          double highBandAttenuationDb,
                                          int birdieCount, int preEchoCount) {
        double nyquist = sampleRate * 0.5;
        double cutoffScore = 0.0;
        if (cutoffHz > 0) {
            // Lower cutoff → higher severity.
            cutoffScore = clamp01(1.0 - (cutoffHz / nyquist));
        }
        double hbScore = clamp01(highBandAttenuationDb / 80.0);
        double birdieScore = clamp01(birdieCount / 100.0);
        double preEchoScore = clamp01(preEchoCount / 20.0);

        // Weighted average. Cutoff dominates because it is the strongest
        // indicator; birdies and pre-echo refine the estimate.
        double raw = 0.55 * cutoffScore
                   + 0.20 * hbScore
                   + 0.15 * birdieScore
                   + 0.10 * preEchoScore;
        return clamp01(raw);
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
