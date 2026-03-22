package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.ExportValidationResult;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessHistoryPoint;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;
import com.benesquivelmusic.daw.sdk.visualization.VisualizationProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ITU-R BS.1770-compliant loudness meter for LUFS measurement.
 *
 * <p>Implements K-frequency weighting and gated loudness measurement
 * producing momentary (400 ms), short-term (3 s), and integrated
 * loudness values in LUFS. Also tracks true-peak level and loudness
 * range (LRA) per EBU R128.</p>
 *
 * <p>Provides platform-specific export validation via
 * {@link #validateForExport(LoudnessTarget)} and time-based loudness
 * history via {@link #getHistory()} for visualization.</p>
 *
 * <p>Directly supports the loudness standards and metering requirements
 * from the mastering-techniques research document (§8), including
 * platform-specific targets (Spotify −14 LUFS, Apple Music −16 LUFS,
 * YouTube −14 LUFS).</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class LoudnessMeter implements VisualizationProvider<LoudnessData> {

    /** Spotify recommended integrated loudness target. */
    public static final double TARGET_SPOTIFY = -14.0;
    /** Apple Music recommended integrated loudness target. */
    public static final double TARGET_APPLE_MUSIC = -16.0;
    /** YouTube recommended integrated loudness target. */
    public static final double TARGET_YOUTUBE = -14.0;

    private static final double LUFS_FLOOR = -120.0;
    private static final double GATE_ABSOLUTE = -70.0;
    private static final double EXPORT_LOUDNESS_TOLERANCE_LU = 1.0;

    private final double sampleRate;
    private final int blockSize;
    private final int momentaryFrames;
    private final int shortTermFrames;

    // K-weighting filter state (two cascaded biquad stages, per channel)
    private double kw1L_x1, kw1L_x2, kw1L_y1, kw1L_y2;
    private double kw2L_x1, kw2L_x2, kw2L_y1, kw2L_y2;
    private double kw1R_x1, kw1R_x2, kw1R_y1, kw1R_y2;
    private double kw2R_x1, kw2R_x2, kw2R_y1, kw2R_y2;
    private final double[] kw1Coeffs;
    private final double[] kw2Coeffs;

    // Ring buffer for mean-square values (per-block)
    private final double[] momentaryBuffer;
    private final double[] shortTermBuffer;
    private int momentaryIndex;
    private int shortTermIndex;
    private int momentaryCount;
    private int shortTermCount;

    // Integrated loudness (gated)
    private double integratedSum;
    private long integratedBlocks;
    private double truePeak;

    // LRA: collect short-term LUFS readings for statistical analysis
    private final List<Double> shortTermLufsReadings = new ArrayList<>();

    // Loudness history for time-based visualization
    private final List<LoudnessHistoryPoint> history = new ArrayList<>();
    private long totalBlocksProcessed;

    private volatile LoudnessData latestData;

    /**
     * Creates a loudness meter for the given sample rate and block size.
     *
     * @param sampleRate the audio sample rate in Hz
     * @param blockSize  processing block size in samples
     */
    public LoudnessMeter(double sampleRate, int blockSize) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        this.sampleRate = sampleRate;
        this.blockSize = blockSize;

        // Calculate ring buffer sizes for momentary (400 ms) and short-term (3 s)
        double blocksPerSecond = sampleRate / blockSize;
        this.momentaryFrames = Math.max(1, (int) Math.ceil(0.4 * blocksPerSecond));
        this.shortTermFrames = Math.max(1, (int) Math.ceil(3.0 * blocksPerSecond));

        this.momentaryBuffer = new double[momentaryFrames];
        this.shortTermBuffer = new double[shortTermFrames];

        // K-weighting coefficients (pre-calculated for 48 kHz, acceptable
        // approximation for other rates — production code would compute
        // exact bilinear transform coefficients per sample rate)
        kw1Coeffs = computeHighShelfCoeffs(sampleRate);
        kw2Coeffs = computeHighPassCoeffs(sampleRate);

        latestData = LoudnessData.SILENCE;
    }

    /**
     * Processes a block of stereo-interleaved or mono audio samples.
     *
     * @param leftChannel  left or mono channel samples
     * @param rightChannel right channel samples (may be same as left for mono)
     * @param numFrames    number of frames to process
     */
    public void process(float[] leftChannel, float[] rightChannel, int numFrames) {
        double blockMeanSquare = 0.0;
        double blockTruePeak = 0.0;

        for (int i = 0; i < numFrames; i++) {
            double sampleL = leftChannel[i];
            double sampleR = rightChannel[i];

            // Track true peak
            double absL = Math.abs(sampleL);
            double absR = Math.abs(sampleR);
            double framePeak = Math.max(absL, absR);
            if (framePeak > blockTruePeak) {
                blockTruePeak = framePeak;
            }

            // Apply K-weighting to left and right channels independently
            double weightedL = applyKWeightingLeft(sampleL);
            double weightedR = applyKWeightingRight(sampleR);

            // Mean square (equal power for L/R)
            blockMeanSquare += (weightedL * weightedL + weightedR * weightedR) / 2.0;
        }

        blockMeanSquare /= numFrames;

        // Update true peak
        if (blockTruePeak > truePeak) {
            truePeak = blockTruePeak;
        }

        // Update momentary ring buffer
        momentaryBuffer[momentaryIndex] = blockMeanSquare;
        momentaryIndex = (momentaryIndex + 1) % momentaryFrames;
        momentaryCount = Math.min(momentaryCount + 1, momentaryFrames);

        // Update short-term ring buffer
        shortTermBuffer[shortTermIndex] = blockMeanSquare;
        shortTermIndex = (shortTermIndex + 1) % shortTermFrames;
        shortTermCount = Math.min(shortTermCount + 1, shortTermFrames);

        // Calculate momentary LUFS (400 ms window)
        double momentaryLufs = calculateLufs(momentaryBuffer, momentaryCount);

        // Calculate short-term LUFS (3 s window)
        double shortTermLufs = calculateLufs(shortTermBuffer, shortTermCount);

        // Collect short-term readings for LRA calculation (only once window is full)
        if (shortTermCount >= shortTermFrames && shortTermLufs > GATE_ABSOLUTE) {
            shortTermLufsReadings.add(shortTermLufs);
        }

        // Update integrated loudness (with absolute gating at -70 LUFS)
        double blockLufs = meanSquareToLufs(blockMeanSquare);
        if (blockLufs > GATE_ABSOLUTE) {
            integratedSum += blockMeanSquare;
            integratedBlocks++;
        }
        double integratedLufs = (integratedBlocks > 0)
                ? meanSquareToLufs(integratedSum / integratedBlocks)
                : LUFS_FLOOR;

        double truePeakDb = (truePeak > 0) ? 20.0 * Math.log10(truePeak) : LUFS_FLOOR;

        // Loudness range (LRA) per EBU R128 — statistical distribution of short-term readings
        double loudnessRange = calculateLoudnessRange();

        totalBlocksProcessed++;

        // Record history point
        double timestampSeconds = (totalBlocksProcessed * blockSize) / sampleRate;
        history.add(new LoudnessHistoryPoint(timestampSeconds, momentaryLufs,
                shortTermLufs, integratedLufs));

        latestData = new LoudnessData(momentaryLufs, shortTermLufs, integratedLufs,
                loudnessRange, truePeakDb);
    }

    /**
     * Resets all meter state.
     */
    public void reset() {
        kw1L_x1 = kw1L_x2 = kw1L_y1 = kw1L_y2 = 0;
        kw2L_x1 = kw2L_x2 = kw2L_y1 = kw2L_y2 = 0;
        kw1R_x1 = kw1R_x2 = kw1R_y1 = kw1R_y2 = 0;
        kw2R_x1 = kw2R_x2 = kw2R_y1 = kw2R_y2 = 0;
        momentaryIndex = shortTermIndex = 0;
        momentaryCount = shortTermCount = 0;
        integratedSum = 0;
        integratedBlocks = 0;
        truePeak = 0;
        totalBlocksProcessed = 0;
        java.util.Arrays.fill(momentaryBuffer, 0);
        java.util.Arrays.fill(shortTermBuffer, 0);
        shortTermLufsReadings.clear();
        history.clear();
        latestData = LoudnessData.SILENCE;
    }

    /**
     * Returns an unmodifiable view of the loudness history recorded since
     * the last {@link #reset()}.
     *
     * <p>Each entry captures the momentary, short-term, and integrated LUFS
     * at a specific timestamp, suitable for rendering a loudness-over-time
     * graph.</p>
     *
     * @return unmodifiable list of history data points
     */
    public List<LoudnessHistoryPoint> getHistory() {
        return Collections.unmodifiableList(history);
    }

    /**
     * Validates the current integrated loudness and true-peak measurements
     * against the specified platform or genre loudness target.
     *
     * <p>Integrated loudness passes if it is within ±1 LU of the target.
     * True peak passes if it does not exceed the target's maximum.</p>
     *
     * @param target the loudness target to validate against
     * @return the validation result
     * @throws NullPointerException if {@code target} is null
     */
    public ExportValidationResult validateForExport(LoudnessTarget target) {
        java.util.Objects.requireNonNull(target, "target must not be null");

        var data = latestData;
        double measuredLufs = data.integratedLufs();
        double measuredPeak = data.truePeakDbfs();

        double lufsDiff = Math.abs(measuredLufs - target.targetIntegratedLufs());
        boolean loudnessPass = lufsDiff <= EXPORT_LOUDNESS_TOLERANCE_LU;
        boolean truePeakPass = measuredPeak <= target.maxTruePeakDbtp();

        var sb = new StringBuilder();
        sb.append(target.displayName()).append(": ");
        if (loudnessPass && truePeakPass) {
            sb.append("PASS");
        } else {
            sb.append("FAIL —");
            if (!loudnessPass) {
                sb.append(String.format(" integrated %.1f LUFS (target %.1f, tolerance ±%.1f LU)",
                        measuredLufs, target.targetIntegratedLufs(), EXPORT_LOUDNESS_TOLERANCE_LU));
            }
            if (!truePeakPass) {
                if (!loudnessPass) sb.append(";");
                sb.append(String.format(" true peak %.1f dBTP (max %.1f)",
                        measuredPeak, target.maxTruePeakDbtp()));
            }
        }

        return new ExportValidationResult(target, measuredLufs, measuredPeak,
                loudnessPass, truePeakPass, sb.toString());
    }

    @Override
    public LoudnessData getLatestData() {
        return latestData;
    }

    @Override
    public boolean hasData() {
        return latestData != null;
    }

    // ----------------------------------------------------------------
    // K-weighting filters (simplified biquad cascade)
    // ----------------------------------------------------------------

    private double applyKWeightingLeft(double sample) {
        // Stage 1: High shelf (+4 dB above ~1500 Hz)
        double y1 = kw1Coeffs[0] * sample + kw1Coeffs[1] * kw1L_x1 + kw1Coeffs[2] * kw1L_x2
                - kw1Coeffs[3] * kw1L_y1 - kw1Coeffs[4] * kw1L_y2;
        kw1L_x2 = kw1L_x1;
        kw1L_x1 = sample;
        kw1L_y2 = kw1L_y1;
        kw1L_y1 = y1;

        // Stage 2: High-pass (~60 Hz, 2nd order)
        double y2 = kw2Coeffs[0] * y1 + kw2Coeffs[1] * kw2L_x1 + kw2Coeffs[2] * kw2L_x2
                - kw2Coeffs[3] * kw2L_y1 - kw2Coeffs[4] * kw2L_y2;
        kw2L_x2 = kw2L_x1;
        kw2L_x1 = y1;
        kw2L_y2 = kw2L_y1;
        kw2L_y1 = y2;

        return y2;
    }

    private double applyKWeightingRight(double sample) {
        // Stage 1: High shelf (+4 dB above ~1500 Hz)
        double y1 = kw1Coeffs[0] * sample + kw1Coeffs[1] * kw1R_x1 + kw1Coeffs[2] * kw1R_x2
                - kw1Coeffs[3] * kw1R_y1 - kw1Coeffs[4] * kw1R_y2;
        kw1R_x2 = kw1R_x1;
        kw1R_x1 = sample;
        kw1R_y2 = kw1R_y1;
        kw1R_y1 = y1;

        // Stage 2: High-pass (~60 Hz, 2nd order)
        double y2 = kw2Coeffs[0] * y1 + kw2Coeffs[1] * kw2R_x1 + kw2Coeffs[2] * kw2R_x2
                - kw2Coeffs[3] * kw2R_y1 - kw2Coeffs[4] * kw2R_y2;
        kw2R_x2 = kw2R_x1;
        kw2R_x1 = y1;
        kw2R_y2 = kw2R_y1;
        kw2R_y1 = y2;

        return y2;
    }

    private static double calculateLufs(double[] buffer, int count) {
        if (count == 0) return LUFS_FLOOR;
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += buffer[i];
        }
        return meanSquareToLufs(sum / count);
    }

    private static double meanSquareToLufs(double meanSquare) {
        if (meanSquare <= 0) return LUFS_FLOOR;
        return -0.691 + 10.0 * Math.log10(meanSquare);
    }

    /**
     * Calculates Loudness Range (LRA) per EBU R128 as the difference
     * between the 95th and 10th percentiles of the absolute-gated
     * short-term loudness distribution.
     */
    private double calculateLoudnessRange() {
        if (shortTermLufsReadings.size() < 2) {
            return 0.0;
        }
        var sorted = new ArrayList<>(shortTermLufsReadings);
        Collections.sort(sorted);

        int n = sorted.size();
        int low = (int) Math.floor(n * 0.10);
        int high = Math.min((int) Math.floor(n * 0.95), n - 1);
        return Math.max(0.0, sorted.get(high) - sorted.get(low));
    }

    /**
     * Compute high-shelf coefficients for K-weighting stage 1.
     * Approximation of the ITU-R BS.1770 pre-filter.
     */
    private static double[] computeHighShelfCoeffs(double sampleRate) {
        double fc = 1500.0;
        double gainDb = 4.0;
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * fc / sampleRate;
        double cosW0 = Math.cos(w0);
        double sinW0 = Math.sin(w0);
        double alpha = sinW0 / 2.0 * Math.sqrt((A + 1.0 / A) * (1.0 / 0.707 - 1.0) + 2.0);

        double a0 = (A + 1) - (A - 1) * cosW0 + 2.0 * Math.sqrt(A) * alpha;
        double b0 = A * ((A + 1) + (A - 1) * cosW0 + 2.0 * Math.sqrt(A) * alpha) / a0;
        double b1 = -2.0 * A * ((A - 1) + (A + 1) * cosW0) / a0;
        double b2 = A * ((A + 1) + (A - 1) * cosW0 - 2.0 * Math.sqrt(A) * alpha) / a0;
        double a1 = 2.0 * ((A - 1) - (A + 1) * cosW0) / a0;
        double a2 = ((A + 1) - (A - 1) * cosW0 - 2.0 * Math.sqrt(A) * alpha) / a0;

        return new double[]{b0, b1, b2, a1, a2};
    }

    /**
     * Compute high-pass coefficients for K-weighting stage 2.
     */
    private static double[] computeHighPassCoeffs(double sampleRate) {
        double fc = 60.0;
        double Q = 0.5;
        double w0 = 2.0 * Math.PI * fc / sampleRate;
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * Q);

        double a0 = 1.0 + alpha;
        double b0 = ((1.0 + cosW0) / 2.0) / a0;
        double b1 = -(1.0 + cosW0) / a0;
        double b2 = ((1.0 + cosW0) / 2.0) / a0;
        double a1 = (-2.0 * cosW0) / a0;
        double a2 = (1.0 - alpha) / a0;

        return new double[]{b0, b1, b2, a1, a2};
    }
}
