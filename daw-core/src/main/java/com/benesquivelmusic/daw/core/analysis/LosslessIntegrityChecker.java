package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.analysis.WindowType;

import java.util.Arrays;

/**
 * Forensic analyzer that detects whether a supposedly lossless audio file
 * (WAV, FLAC, …) has actually originated from a lossy source or been
 * upscaled from a lower sample rate or bit depth.
 *
 * <p>Mastering engineers often receive files labelled "lossless 24-bit /
 * 96 kHz" that are in fact re-wrapped MP3s, upsampled 44.1 kHz material, or
 * 16-bit PCM left-shifted into a 24-bit container. The tell-tale signatures
 * all live in the spectral envelope and in the integer sample values:</p>
 *
 * <ul>
 *   <li><b>Spectral cutoff</b> — lossy codecs discard content above an
 *       encoder-specific frequency (MP3 ~16 kHz at 128 kbps, ~19 kHz at
 *       192 kbps, AAC ~18 kHz, etc.). A sharp cliff well below Nyquist is
 *       diagnostic of a lossy source.</li>
 *   <li><b>Upsampling</b> — clean upsampling produces near-zero energy
 *       above the original Nyquist frequency. If average magnitude above
 *       {@code sampleRate / N} is orders of magnitude below the in-band
 *       level, the file was likely upsampled from
 *       {@code sampleRate / N * 2}.</li>
 *   <li><b>Bit-depth inflation</b> — raising the container depth without
 *       dithering leaves the low-order bits consistently zero. An integer
 *       view of the samples reveals the true effective bit depth via the
 *       number of trailing zero bits shared by every non-zero sample.</li>
 * </ul>
 *
 * <p>Built entirely on top of {@link FftUtils} and follows the windowed-FFT
 * conventions used by {@link SpectrumAnalyzer}. Pure Java, no JNI.</p>
 *
 * <p><b>References</b></p>
 * <ul>
 *   <li>AES 2015 — <i>Lossless Audio Checker: A Software for the Detection
 *       of Upscaling, Upsampling, and Transcoding in Lossless Musical
 *       Tracks</i></li>
 * </ul>
 *
 * @see FftUtils
 * @see SpectrumAnalyzer
 * @see CompressionArtifactDetector
 */
public final class LosslessIntegrityChecker {

    /** Likely original provenance of the analyzed signal. */
    public enum OriginFormat {
        /** No upscaling, upsampling, or lossy cutoff detected. */
        TRUE_LOSSLESS,
        /** Signal shows the spectral cutoff of an MP3 source. */
        UPCONVERTED_FROM_MP3,
        /** Signal shows the spectral cutoff of an AAC source. */
        UPCONVERTED_FROM_AAC,
        /** Signal shows a lossy-style cutoff but the codec is ambiguous. */
        UPCONVERTED_FROM_LOSSY,
        /** Signal shows a spectral gap consistent with upsampling. */
        UPSAMPLED,
        /** Samples use fewer effective bits than the container width. */
        UPSCALED_BIT_DEPTH,
        /** Multiple upscaling / lossy signatures detected simultaneously. */
        UPCONVERTED_MIXED
    }

    /**
     * Full integrity report returned by
     * {@link #analyze(float[], double)} or
     * {@link #analyze(float[], double, int)}.
     *
     * @param originFormat          most likely original provenance
     * @param spectralCutoffHz      detected sharp low-pass cutoff in Hz, or
     *                              {@code -1} if none was found
     * @param estimatedSourceRateHz estimated original sample rate before any
     *                              upsampling (Hz), or {@code -1} if no
     *                              upsampling was detected
     * @param effectiveBitDepth     estimated number of effective bits per
     *                              sample (≤ container depth), or {@code -1}
     *                              if bit-depth analysis could not be
     *                              performed (for example when called on
     *                              pure float input)
     * @param containerBitDepth     nominal container bit depth passed in by
     *                              the caller, or {@code -1} if unknown
     * @param confidence            confidence of the verdict in [0.0, 1.0]
     *                              where 0 is "no evidence" and 1 is "strong
     *                              evidence against losslessness"
     */
    public record Report(OriginFormat originFormat,
                         double spectralCutoffHz,
                         double estimatedSourceRateHz,
                         int effectiveBitDepth,
                         int containerBitDepth,
                         double confidence) {
        /** @return {@code true} iff the signal appears genuinely lossless. */
        public boolean isLikelyLossless() {
            return originFormat == OriginFormat.TRUE_LOSSLESS;
        }
    }

    // --- Configuration ------------------------------------------------------

    private static final int FFT_SIZE = 4096;
    private static final int HOP_SIZE = FFT_SIZE / 2;
    private static final double DB_FLOOR = -140.0;

    /**
     * Codec-cutoff map used to classify the detected spectral cliff into a
     * likely source codec. Entries are scanned in order; the first that
     * brackets the measured cutoff wins.
     */
    private static final double[] MP3_CUTOFF_RANGE_HZ = { 14500.0, 17500.0 };
    private static final double[] AAC_CUTOFF_RANGE_HZ = { 17500.0, 19500.0 };

    /** dB drop from in-band average that counts as a "spectral cliff". */
    private static final double CUTOFF_DROP_DB = 40.0;

    /**
     * Candidate source sample rates (Hz) that the detector will try to
     * match when hunting for an upsampling signature. Ordered from highest
     * to lowest so that the most "benign" explanation wins first.
     */
    private static final double[] CANDIDATE_SOURCE_RATES_HZ = {
            88200.0, 96000.0, 48000.0, 44100.0, 32000.0, 22050.0, 16000.0, 11025.0, 8000.0
    };

    /**
     * Fraction of the candidate Nyquist below which we expect almost no
     * energy in genuine audio at that candidate rate. Used to detect the
     * characteristic "spectral gap" of a zero-stuffed upsampler.
     */
    private static final double UPSAMPLE_GAP_START_FRACTION = 0.98;

    /**
     * Minimum dB attenuation in the gap region (above the candidate
     * Nyquist) relative to the in-band reference for the signal to be
     * flagged as upsampled.
     */
    private static final double UPSAMPLE_GAP_MIN_DB = 60.0;

    /** Creates a new stateless checker. */
    public LosslessIntegrityChecker() {
    }

    // --- Public API ---------------------------------------------------------

    /**
     * Analyzes a mono audio buffer. Equivalent to
     * {@link #analyze(float[], double, int)} with {@code containerBitDepth}
     * set to {@code -1} (skip bit-depth analysis).
     *
     * @param samples     mono audio samples in [-1.0, 1.0]
     * @param sampleRate  sample rate in Hz ({@code > 0})
     * @return the integrity {@link Report}
     * @throws IllegalArgumentException if arguments are invalid
     */
    public Report analyze(float[] samples, double sampleRate) {
        return analyze(samples, sampleRate, -1);
    }

    /**
     * Analyzes a mono audio buffer whose samples were decoded from an
     * integer PCM container of {@code containerBitDepth} bits per sample.
     *
     * @param samples            mono audio samples in [-1.0, 1.0]
     * @param sampleRate         sample rate in Hz ({@code > 0})
     * @param containerBitDepth  nominal container bit depth (e.g. 16, 24,
     *                           32), or {@code -1} to disable bit-depth
     *                           analysis
     * @return the integrity {@link Report}
     * @throws IllegalArgumentException if {@code samples} is null,
     *         {@code sampleRate} is not positive, or
     *         {@code containerBitDepth} is neither {@code -1} nor in
     *         {@code [1, 32]}
     */
    public Report analyze(float[] samples, double sampleRate, int containerBitDepth) {
        if (samples == null) {
            throw new IllegalArgumentException("samples must not be null");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (containerBitDepth != -1 && (containerBitDepth < 1 || containerBitDepth > 32)) {
            throw new IllegalArgumentException(
                    "containerBitDepth must be -1 or in [1, 32]: " + containerBitDepth);
        }

        // --- 1. Average magnitude spectrum across the whole buffer ---------
        double[] avgMagDb = averageMagnitudeSpectrumDb(samples, sampleRate);

        // --- 2. Spectral cutoff detection ----------------------------------
        double cutoffHz = detectSpectralCutoff(avgMagDb, sampleRate);
        double sourceRateHz = -1.0;

        // --- 3. Reconcile cutoff with upsampling ---------------------------
        // Codec cutoffs (MP3 ~16 kHz, AAC ~18 kHz) take precedence. Only if
        // the cutoff aligns with a canonical source-rate Nyquist *and* falls
        // outside all known codec ranges do we re-attribute it to
        // upsampling rather than lossy coding.
        if (cutoffHz > 0 && !inCodecRange(cutoffHz)) {
            double matched = matchCanonicalSourceRate(cutoffHz, sampleRate);
            if (matched > 0) {
                sourceRateHz = matched;
                cutoffHz = -1.0;
            }
        }

        // --- 4. Gap-based upsample detection (catches soft upsamplers) ----
        if (sourceRateHz < 0 && cutoffHz < 0) {
            sourceRateHz = detectUpsampleSourceRate(avgMagDb, sampleRate);
        }

        // --- 4. Bit-depth analysis -----------------------------------------
        int effectiveBitDepth = containerBitDepth > 0
                ? detectEffectiveBitDepth(samples, containerBitDepth)
                : -1;

        boolean bitDepthUpscaled =
                effectiveBitDepth > 0 && effectiveBitDepth < containerBitDepth;

        // --- 5. Classification --------------------------------------------
        OriginFormat origin = classify(cutoffHz, sourceRateHz, bitDepthUpscaled);
        double confidence = computeConfidence(cutoffHz, sampleRate,
                                              sourceRateHz, bitDepthUpscaled,
                                              effectiveBitDepth, containerBitDepth);

        return new Report(origin, cutoffHz, sourceRateHz,
                          effectiveBitDepth, containerBitDepth, confidence);
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
     * Locates the frequency above which the averaged spectrum drops by more
     * than {@link #CUTOFF_DROP_DB} below its in-band mean and stays there.
     * Mirrors the algorithm used by {@link CompressionArtifactDetector} so
     * the two analyzers produce consistent verdicts.
     *
     * @return cutoff in Hz, or {@code -1.0} if no sharp cliff is found, or
     *         if the cutoff is within 3% of Nyquist (i.e. a natural
     *         bandlimit rather than a lossy cliff)
     */
    private static double detectSpectralCutoff(double[] avgMagDb, double sampleRate) {
        int binCount = avgMagDb.length;
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

    private static boolean inCodecRange(double cutoffHz) {
        return (cutoffHz >= MP3_CUTOFF_RANGE_HZ[0] && cutoffHz < MP3_CUTOFF_RANGE_HZ[1])
            || (cutoffHz >= AAC_CUTOFF_RANGE_HZ[0] && cutoffHz < AAC_CUTOFF_RANGE_HZ[1]);
    }

    /**
     * If {@code cutoffHz} falls close to the Nyquist frequency of one of
     * {@link #CANDIDATE_SOURCE_RATES_HZ}, return that rate. This lets us
     * attribute a cliff at e.g. 11 025 Hz in a 44.1 kHz file to upsampling
     * from 22.05 kHz rather than to a lossy encoder.
     */
    private static double matchCanonicalSourceRate(double cutoffHz, double sampleRate) {
        double tolerance = Math.max(50.0, sampleRate / FFT_SIZE * 8.0);
        for (double rate : CANDIDATE_SOURCE_RATES_HZ) {
            if (rate >= sampleRate * 0.999) continue;
            if (Math.abs(cutoffHz - rate * 0.5) <= tolerance) {
                return rate;
            }
        }
        return -1.0;
    }

    // --- Internals: upsampling ---------------------------------------------

    /**
     * Attempts to find a candidate source sample rate {@code Rs < sampleRate}
     * such that the averaged spectrum above {@code Rs / 2} is effectively
     * empty (≥ {@link #UPSAMPLE_GAP_MIN_DB} dB below the in-band reference)
     * but the spectrum below {@code Rs / 2} contains energy. That is the
     * characteristic fingerprint of a clean upsampler whose anti-image
     * filter did not leave a sharp audible cliff.
     *
     * @return estimated original sample rate in Hz, or {@code -1.0} if no
     *         such gap is detected
     */
    private static double detectUpsampleSourceRate(double[] avgMagDb,
                                                   double sampleRate) {
        int binCount = avgMagDb.length;
        double binHz = sampleRate / FFT_SIZE;

        // In-band reference: 200 Hz .. 2 kHz region.
        int refLo = Math.max(1, (int) Math.round(200.0 / binHz));
        int refHi = Math.min(binCount - 1, (int) Math.round(2000.0 / binHz));
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

        double gapThreshold = inBandMean - UPSAMPLE_GAP_MIN_DB;

        for (double candidateRate : CANDIDATE_SOURCE_RATES_HZ) {
            // Only interesting if the candidate is strictly lower than the
            // container rate — otherwise no upsampling could have occurred.
            if (candidateRate >= sampleRate * 0.999) continue;

            double candidateNyquist = candidateRate * 0.5;

            int gapStartBin = (int) Math.round(
                    candidateNyquist * UPSAMPLE_GAP_START_FRACTION / binHz);
            int gapEndBin = Math.min(binCount - 1,
                    (int) Math.round(sampleRate * 0.48 / binHz));
            if (gapEndBin - gapStartBin < 16) continue;

            // Mean magnitude in the gap region.
            double gapMean = 0.0;
            int gapCount = 0;
            for (int k = gapStartBin; k <= gapEndBin; k++) {
                gapMean += avgMagDb[k];
                gapCount++;
            }
            gapMean /= gapCount;

            // Also require that the band *below* the candidate Nyquist
            // actually carries signal — otherwise the whole spectrum is
            // near-silent and the gap is trivial.
            int belowLo = Math.max(refLo, 1);
            int belowHi = Math.max(belowLo + 1,
                    (int) Math.round(candidateNyquist * 0.5 / binHz));
            belowHi = Math.min(belowHi, binCount - 1);
            double belowMean = 0.0;
            int belowCount = 0;
            for (int k = belowLo; k <= belowHi; k++) {
                belowMean += avgMagDb[k];
                belowCount++;
            }
            if (belowCount == 0) continue;
            belowMean /= belowCount;

            if (gapMean <= gapThreshold
                    && belowMean > gapMean + UPSAMPLE_GAP_MIN_DB * 0.5) {
                return candidateRate;
            }
        }
        return -1.0;
    }

    // --- Internals: bit-depth analysis -------------------------------------

    /**
     * Estimates the effective bit depth of a float sample buffer that was
     * decoded from integer PCM of {@code containerBitDepth} bits. If every
     * non-zero sample, when reprojected onto the integer lattice, shares
     * {@code T} trailing zero bits, the effective bit depth is
     * {@code containerBitDepth - T}.
     *
     * <p>Samples are expected to be in the range [-1.0, 1.0]. Out-of-range
     * values are clipped to the integer range.</p>
     *
     * @return the effective bit depth in {@code [1, containerBitDepth]}, or
     *         {@code -1} if the buffer contains only zeros (so no
     *         inference is possible)
     */
    private static int detectEffectiveBitDepth(float[] samples, int containerBitDepth) {
        // Use the standard PCM convention: full-scale 1.0 maps to 2^(N-1).
        // With this scaling, re-quantizing a value already on a K-bit grid
        // (K < N) into N bits produces exactly (v_K << (N - K)), so the
        // shared trailing zeros of the integer view directly reveal the
        // bit-depth inflation amount.
        long scale = 1L << (containerBitDepth - 1);
        long maxAbs = scale - 1L;

        long orAcc = 0L;
        for (float s : samples) {
            double clamped = s;
            if (clamped > 1.0) clamped = 1.0;
            else if (clamped < -1.0) clamped = -1.0;
            long v = Math.round(clamped * scale);
            if (v > maxAbs) v = maxAbs;
            else if (v < -maxAbs) v = -maxAbs;
            if (v == 0L) continue;
            orAcc |= Math.abs(v);
            if ((orAcc & 1L) != 0L) {
                // LSB already set — no inflation possible, exit early.
                return containerBitDepth;
            }
        }
        if (orAcc == 0L) {
            return -1;
        }
        int trailing = Long.numberOfTrailingZeros(orAcc);
        int effective = containerBitDepth - trailing;
        if (effective < 1) effective = 1;
        if (effective > containerBitDepth) effective = containerBitDepth;
        return effective;
    }

    // --- Internals: classification -----------------------------------------

    private static OriginFormat classify(double cutoffHz,
                                         double sourceRateHz,
                                         boolean bitDepthUpscaled) {
        int flags = 0;
        if (cutoffHz > 0) flags++;
        if (sourceRateHz > 0) flags++;
        if (bitDepthUpscaled) flags++;

        if (flags == 0) {
            return OriginFormat.TRUE_LOSSLESS;
        }
        if (flags > 1) {
            return OriginFormat.UPCONVERTED_MIXED;
        }
        if (cutoffHz > 0) {
            if (cutoffHz >= MP3_CUTOFF_RANGE_HZ[0] && cutoffHz < MP3_CUTOFF_RANGE_HZ[1]) {
                return OriginFormat.UPCONVERTED_FROM_MP3;
            }
            if (cutoffHz >= AAC_CUTOFF_RANGE_HZ[0] && cutoffHz < AAC_CUTOFF_RANGE_HZ[1]) {
                return OriginFormat.UPCONVERTED_FROM_AAC;
            }
            return OriginFormat.UPCONVERTED_FROM_LOSSY;
        }
        if (sourceRateHz > 0) {
            return OriginFormat.UPSAMPLED;
        }
        return OriginFormat.UPSCALED_BIT_DEPTH;
    }

    private static double computeConfidence(double cutoffHz, double sampleRate,
                                            double sourceRateHz,
                                            boolean bitDepthUpscaled,
                                            int effectiveBitDepth,
                                            int containerBitDepth) {
        double nyquist = sampleRate * 0.5;

        double cutoffScore = 0.0;
        if (cutoffHz > 0) {
            // Lower cutoff → stronger (more confident) evidence.
            cutoffScore = clamp01(1.0 - (cutoffHz / nyquist));
        }

        double upsampleScore = 0.0;
        if (sourceRateHz > 0) {
            // Wider gap between container and source rate → stronger evidence.
            upsampleScore = clamp01(1.0 - (sourceRateHz / sampleRate));
        }

        double bitDepthScore = 0.0;
        if (bitDepthUpscaled && containerBitDepth > 0) {
            bitDepthScore = clamp01(
                    (double) (containerBitDepth - effectiveBitDepth) / containerBitDepth);
        }

        // Take the strongest single signal and give a smaller bonus for any
        // additional corroborating evidence. This avoids over-confidence
        // when a single weak signal exists and under-confidence when two
        // independent signals agree.
        double primary = Math.max(cutoffScore, Math.max(upsampleScore, bitDepthScore));
        double bonus = (cutoffScore + upsampleScore + bitDepthScore) - primary;
        return clamp01(primary + 0.2 * bonus);
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
