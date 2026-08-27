package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.mastering.LoudnessSnapshot;
import com.benesquivelmusic.daw.sdk.visualization.ExportValidationResult;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessHistoryPoint;
import com.benesquivelmusic.daw.sdk.visualization.LoudnessTarget;
import com.benesquivelmusic.daw.sdk.visualization.VisualizationProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ITU-R BS.1770-4 / EBU R 128 loudness meter for LUFS measurement.
 *
 * <p>Implements K-frequency weighting and gated loudness measurement
 * producing momentary (400 ms), short-term (3 s), and integrated
 * loudness values in LUFS, the loudness range (LRA) of EBU Tech 3342,
 * and the sample peak. Provides platform-specific export validation via
 * {@link #validateForExport(LoudnessTarget)} and time-based loudness
 * history via {@link #getHistory()} for visualization.</p>
 *
 * <h2>Thread contract</h2>
 *
 * <p>{@link #process(float[], float[], int)}, {@link #reset()} and
 * {@link #resetIntegrated()} are <b>single-writer</b> and must be called
 * from the metering <b>analysis thread</b> (the {@code daw-metering-analysis}
 * drain of {@code com.benesquivelmusic.daw.core.metering}) or from an
 * <b>offline batch</b> loop such as export validation and loudness
 * normalization. They must <b>never</b> run on the real-time render
 * thread: {@code process} converts to dB ({@code Math.log10}), builds one
 * small {@link LoudnessData} record per block, takes a short lock while
 * recording the history point, and at the 10 Hz snapshot cadence calls
 * {@link SubmissionPublisher#offer}, which acquires a lock internally.
 * All of that is legal on the analysis thread precisely because that
 * thread is decoupled from the render thread by a sample ring
 * (design book §2.6, §4.3); none of it is legal on the render thread,
 * and no method of this class is annotated {@code @RealTimeSafe}.</p>
 *
 * <p>The read side ({@link #getLatestData()}, {@link #latestSnapshot()},
 * {@link #validateForExport}, {@link #isWithinTarget},
 * {@link #getHistory()}) may be called from any thread.</p>
 *
 * <h2>Bounded memory</h2>
 *
 * <p>Every per-block data structure is fixed-size, so a session of any
 * length (the design book budgets 12 hours) allocates nothing beyond the
 * per-block {@link LoudnessData} record:</p>
 * <ul>
 *   <li>Momentary and short-term mean-square windows are primitive rings
 *       sized from the block rate.</li>
 *   <li>The loudness history is a primitive ring of
 *       {@link #HISTORY_CAPACITY} points (6 min at 100 blocks/s);
 *       older points are dropped, and {@link #getHistory()} materializes
 *       a snapshot on the caller's thread.</li>
 *   <li>LRA and the integrated relative gate are computed from fixed
 *       {@value #HISTOGRAM_BINS}-bin histograms with {@value #HISTOGRAM_BIN_LU}
 *       LU resolution over [{@value #HISTOGRAM_MIN_LUFS}, {@value #HISTOGRAM_MAX_LUFS})
 *       LUFS plus streaming power sums — no list growth, no boxing, and
 *       no sort. LRA is recomputed at the 10 Hz snapshot cadence, so the
 *       histogram walk is bounded work per block.</li>
 * </ul>
 *
 * <h2>Gating</h2>
 *
 * <p>Integrated loudness applies the ITU-R BS.1770-4 two-stage gate to
 * the per-processing-block loudness: the absolute gate at
 * {@value #ABSOLUTE_GATE_LUFS} LUFS, then the relative gate
 * {@value #INTEGRATED_RELATIVE_GATE_LU} LU below the absolute-gated mean.
 * The gating block is the caller's processing block rather than the
 * 400 ms / 75 % overlap block of the recommendation; that block geometry
 * is a known deviation retained so the offline callers' measurements stay
 * comparable across releases. LRA applies the EBU Tech 3342 gates to the
 * short-term (3 s) loudness: absolute at {@value #ABSOLUTE_GATE_LUFS} LUFS,
 * relative at {@value #RELATIVE_GATE_LU} LU below the absolute-gated mean,
 * and reports the 95th minus the 10th percentile of the surviving
 * distribution.</p>
 *
 * <p>Directly supports the loudness standards and metering requirements
 * from the mastering-techniques research document (§8), including
 * platform-specific targets (Spotify −14 LUFS, Apple Music −16 LUFS,
 * YouTube −14 LUFS). Sources: ITU-R BS.1770-4 (K-weighting, gating),
 * EBU R 128, EBU Tech 3341 (metering), EBU Tech 3342 (loudness range).</p>
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
    /** EBU R128 broadcast recommended integrated loudness target. */
    public static final double TARGET_BROADCAST = -23.0;

    /**
     * ITU-R BS.1770-4 / EBU Tech 3342 absolute gate in LUFS. Blocks and
     * short-term readings at or below this loudness are discarded from
     * the integrated and LRA statistics.
     */
    public static final double ABSOLUTE_GATE_LUFS = -70.0;

    /**
     * EBU Tech 3342 relative gate for the loudness range, in LU relative
     * to the absolute-gated short-term mean. Short-term readings below
     * (mean + {@value}) LU are excluded from the LRA distribution.
     */
    public static final double RELATIVE_GATE_LU = -20.0;

    /**
     * ITU-R BS.1770-4 relative gate for integrated loudness, in LU
     * relative to the absolute-gated block mean. Blocks below
     * (mean + {@value}) LU are excluded from the integrated measurement.
     */
    public static final double INTEGRATED_RELATIVE_GATE_LU = -10.0;

    /**
     * Default capacity of the loudness history ring: 36 000 points,
     * i.e. six minutes at 100 blocks per second. Older points are dropped.
     */
    public static final int HISTORY_CAPACITY = 36_000;

    /** Lower edge of the loudness histograms (LUFS); coincides with the absolute gate. */
    static final double HISTOGRAM_MIN_LUFS = -70.0;
    /** Exclusive upper edge of the loudness histograms (LUFS); louder values clamp into the top bin. */
    static final double HISTOGRAM_MAX_LUFS = 10.0;
    /** Histogram resolution in LU. */
    static final double HISTOGRAM_BIN_LU = 0.1;
    /** Number of histogram bins: (10 − (−70)) / 0.1. */
    static final int HISTOGRAM_BINS = 800;

    private static final double LUFS_FLOOR = -120.0;
    private static final double EXPORT_LOUDNESS_TOLERANCE_LU = 1.0;
    private static final double LRA_LOW_PERCENTILE = 0.10;
    private static final double LRA_HIGH_PERCENTILE = 0.95;
    /** Guards floor/ceil bin arithmetic against binary rounding of exact bin edges. */
    private static final double BIN_EDGE_EPSILON = 1e-9;

    /**
     * Target publication interval, in seconds, for the
     * {@link #snapshotPublisher() snapshot publisher} and for the LRA
     * recompute. EBU R128 meters typically refresh at ~10 Hz so that
     * human eyes can track motion.
     */
    private static final double SNAPSHOT_INTERVAL_SECONDS = 0.1;

    private final double sampleRate;
    private final int blockSize;
    private final int momentaryFrames;
    private final int shortTermFrames;

    // K-weighting filter state (two cascaded biquad stages, per channel)
    // Index 0 = left, 1 = right
    private final double[] kw1_x1 = new double[2];
    private final double[] kw1_x2 = new double[2];
    private final double[] kw1_y1 = new double[2];
    private final double[] kw1_y2 = new double[2];
    private final double[] kw2_x1 = new double[2];
    private final double[] kw2_x2 = new double[2];
    private final double[] kw2_y1 = new double[2];
    private final double[] kw2_y2 = new double[2];
    private final double[] kw1Coeffs;
    private final double[] kw2Coeffs;

    // Ring buffers for per-block mean-square values
    private final double[] momentaryBuffer;
    private final double[] shortTermBuffer;
    private int momentaryIndex;
    private int shortTermIndex;
    private int momentaryCount;
    private int shortTermCount;

    // Integrated loudness: absolute-gated streaming sums plus a fixed
    // histogram (count + power per bin) for the BS.1770-4 relative gate.
    private double integratedSum;
    private long integratedBlocks;
    private final long[] integratedBins = new long[HISTOGRAM_BINS];
    private final double[] integratedBinPower = new double[HISTOGRAM_BINS];

    // Sample peak since the last reset (see LoudnessSnapshot: not oversampled).
    private double truePeak;

    // LRA (EBU Tech 3342): fixed histogram of absolute-gated short-term
    // readings plus streaming power sum/count for the relative gate.
    private final long[] lraBins = new long[HISTOGRAM_BINS];
    private long lraCount;
    private double lraPowerSum;
    // Recomputed at the snapshot cadence, not every block.
    private double loudnessRange;

    // Loudness history: fixed-capacity primitive ring, oldest dropped first.
    private final int historyCapacity;
    private final double[] historyTimestamp;
    private final double[] historyMomentary;
    private final double[] historyShortTerm;
    private final double[] historyIntegrated;
    private int historyHead;
    private int historySize;
    // Held for the few stores of one history point and for the copy in
    // getHistory(); never on the render thread (see class javadoc).
    private final ReentrantLock historyLock = new ReentrantLock();

    private long totalBlocksProcessed;

    private volatile LoudnessData latestData;

    // Subscribers run on the SubmissionPublisher's default (async)
    // executor, so a slow subscriber never stalls the analysis thread
    // that calls process().
    private final SubmissionPublisher<LoudnessSnapshot> snapshotPublisher
            = new SubmissionPublisher<>();
    private final long snapshotIntervalSamples;
    private long samplesSinceLastSnapshot;

    /**
     * Creates a loudness meter for the given sample rate and block size
     * with the default {@link #HISTORY_CAPACITY history capacity}.
     *
     * @param sampleRate the audio sample rate in Hz
     * @param blockSize  processing block size in samples
     */
    public LoudnessMeter(double sampleRate, int blockSize) {
        this(sampleRate, blockSize, HISTORY_CAPACITY);
    }

    /**
     * Creates a loudness meter with an explicit history-ring capacity.
     * Package-private so tests can exercise the ring bound cheaply.
     *
     * @param sampleRate      the audio sample rate in Hz
     * @param blockSize       processing block size in samples
     * @param historyCapacity maximum number of retained history points
     */
    LoudnessMeter(double sampleRate, int blockSize, int historyCapacity) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        if (historyCapacity <= 0) {
            throw new IllegalArgumentException("historyCapacity must be positive: " + historyCapacity);
        }
        this.sampleRate = sampleRate;
        this.blockSize = blockSize;

        // Ring sizes for momentary (400 ms) and short-term (3 s) windows
        double blocksPerSecond = sampleRate / blockSize;
        this.momentaryFrames = Math.max(1, (int) Math.ceil(0.4 * blocksPerSecond));
        this.shortTermFrames = Math.max(1, (int) Math.ceil(3.0 * blocksPerSecond));

        this.momentaryBuffer = new double[momentaryFrames];
        this.shortTermBuffer = new double[shortTermFrames];

        this.historyCapacity = historyCapacity;
        this.historyTimestamp = new double[historyCapacity];
        this.historyMomentary = new double[historyCapacity];
        this.historyShortTerm = new double[historyCapacity];
        this.historyIntegrated = new double[historyCapacity];

        // K-weighting coefficients (pre-calculated for 48 kHz, acceptable
        // approximation for other rates — production code would compute
        // exact bilinear transform coefficients per sample rate)
        kw1Coeffs = computeHighShelfCoeffs(sampleRate);
        kw2Coeffs = computeHighPassCoeffs(sampleRate);

        this.snapshotIntervalSamples = Math.max(1L,
                (long) Math.round(SNAPSHOT_INTERVAL_SECONDS * sampleRate));

        latestData = LoudnessData.SILENCE;
    }

    /**
     * Processes one block of audio. Analysis thread or offline batch only —
     * never the real-time render thread (see the class javadoc).
     *
     * <p>Per block this performs the K-weighting pass, updates the fixed
     * windows and histograms, computes the gated integrated loudness from
     * the histogram (bounded, {@value #HISTOGRAM_BINS} bins), records one
     * history point, and publishes one {@link LoudnessData}. At the 10 Hz
     * snapshot cadence it additionally recomputes LRA from the short-term
     * histogram and, if there are subscribers, offers a
     * {@link LoudnessSnapshot} to the {@link #snapshotPublisher()}.</p>
     *
     * @param leftChannel  left or mono channel samples
     * @param rightChannel right channel samples (may be same as left for mono)
     * @param numFrames    number of frames to process
     */
    public void process(float[] leftChannel, float[] rightChannel, int numFrames) {
        double blockMeanSquare = 0.0;
        double blockPeak = 0.0;

        for (int i = 0; i < numFrames; i++) {
            double sampleL = leftChannel[i];
            double sampleR = rightChannel[i];

            double framePeak = Math.max(Math.abs(sampleL), Math.abs(sampleR));
            if (framePeak > blockPeak) {
                blockPeak = framePeak;
            }

            // Apply K-weighting to left and right channels independently
            double weightedL = applyKWeighting(sampleL, 0);
            double weightedR = applyKWeighting(sampleR, 1);

            // Mean square (equal power for L/R)
            blockMeanSquare += (weightedL * weightedL + weightedR * weightedR) / 2.0;
        }

        blockMeanSquare /= numFrames;

        if (blockPeak > truePeak) {
            truePeak = blockPeak;
        }

        momentaryBuffer[momentaryIndex] = blockMeanSquare;
        momentaryIndex = (momentaryIndex + 1) % momentaryFrames;
        momentaryCount = Math.min(momentaryCount + 1, momentaryFrames);

        shortTermBuffer[shortTermIndex] = blockMeanSquare;
        shortTermIndex = (shortTermIndex + 1) % shortTermFrames;
        shortTermCount = Math.min(shortTermCount + 1, shortTermFrames);

        double momentaryLufs = meanSquareToLufs(windowMeanSquare(momentaryBuffer, momentaryCount));
        double shortTermMeanSquare = windowMeanSquare(shortTermBuffer, shortTermCount);
        double shortTermLufs = meanSquareToLufs(shortTermMeanSquare);

        accumulateLoudnessRange(shortTermMeanSquare, shortTermLufs);
        accumulateIntegrated(blockMeanSquare);
        double integratedLufs = computeIntegratedLufs();

        double truePeakDb = (truePeak > 0) ? 20.0 * Math.log10(truePeak) : LUFS_FLOOR;

        totalBlocksProcessed++;
        double timestampSeconds = (totalBlocksProcessed * blockSize) / sampleRate;
        recordHistory(timestampSeconds, momentaryLufs, shortTermLufs, integratedLufs);

        samplesSinceLastSnapshot += numFrames;
        boolean snapshotDue = samplesSinceLastSnapshot >= snapshotIntervalSamples;
        if (snapshotDue) {
            samplesSinceLastSnapshot = 0;
            loudnessRange = computeLoudnessRange();
        }

        latestData = new LoudnessData(momentaryLufs, shortTermLufs, integratedLufs,
                loudnessRange, truePeakDb);

        // Off-RT by contract: offer() locks internally. Throttled to ~10 Hz so
        // subscribers (UI meters, telemetry sinks) get smooth updates
        // independent of the audio block size.
        if (snapshotDue
                && !snapshotPublisher.isClosed()
                && snapshotPublisher.hasSubscribers()) {
            snapshotPublisher.offer(
                    new LoudnessSnapshot(momentaryLufs, shortTermLufs, integratedLufs,
                            loudnessRange, truePeakDb),
                    null);
        }
    }

    /**
     * Resets all meter state.
     */
    public void reset() {
        Arrays.fill(kw1_x1, 0);
        Arrays.fill(kw1_x2, 0);
        Arrays.fill(kw1_y1, 0);
        Arrays.fill(kw1_y2, 0);
        Arrays.fill(kw2_x1, 0);
        Arrays.fill(kw2_x2, 0);
        Arrays.fill(kw2_y1, 0);
        Arrays.fill(kw2_y2, 0);
        momentaryIndex = shortTermIndex = 0;
        momentaryCount = shortTermCount = 0;
        Arrays.fill(momentaryBuffer, 0);
        Arrays.fill(shortTermBuffer, 0);
        clearIntegratedState();
        clearLoudnessRangeState();
        truePeak = 0;
        totalBlocksProcessed = 0;
        clearHistory();
        latestData = LoudnessData.SILENCE;
        samplesSinceLastSnapshot = 0;
    }

    /**
     * Restarts the integrated loudness and loudness-range measurements
     * without clearing filter state, momentary/short-term windows,
     * history, or the peak.
     *
     * <p>This allows engineers to restart the programme-level statistics
     * at any point (e.g., after repositioning the playhead) while keeping
     * the running momentary and short-term meters intact.</p>
     */
    public void resetIntegrated() {
        clearIntegratedState();
        clearLoudnessRangeState();
    }

    /**
     * Returns whether the current integrated loudness is within the
     * acceptable range of the specified platform target.
     *
     * <p>The integrated loudness is considered "within target" if it
     * falls within ±1 LU of the target's integrated LUFS value.</p>
     *
     * @param target the loudness target to check against
     * @return {@code true} if integrated loudness is within ±1 LU of the target
     * @throws NullPointerException if {@code target} is null
     */
    public boolean isWithinTarget(LoudnessTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        LoudnessData data = latestData;
        double measuredLufs = data.integratedLufs();
        return Math.abs(measuredLufs - target.targetIntegratedLufs()) <= EXPORT_LOUDNESS_TOLERANCE_LU;
    }

    /**
     * Returns an unmodifiable snapshot of the loudness history recorded
     * since the last {@link #reset()}, oldest first, bounded by the
     * history capacity ({@link #HISTORY_CAPACITY} by default — older
     * points are dropped).
     *
     * <p>Each entry captures the momentary, short-term, and integrated LUFS
     * at a specific timestamp, suitable for rendering a loudness-over-time
     * graph. The list is materialized on the caller's thread and does not
     * change after it is returned.</p>
     *
     * @return unmodifiable list of history data points
     */
    public List<LoudnessHistoryPoint> getHistory() {
        historyLock.lock();
        try {
            List<LoudnessHistoryPoint> points = new ArrayList<>(historySize);
            int start = historyHead - historySize;
            if (start < 0) {
                start += historyCapacity;
            }
            for (int k = 0; k < historySize; k++) {
                int idx = start + k;
                if (idx >= historyCapacity) {
                    idx -= historyCapacity;
                }
                points.add(new LoudnessHistoryPoint(historyTimestamp[idx],
                        historyMomentary[idx], historyShortTerm[idx], historyIntegrated[idx]));
            }
            return Collections.unmodifiableList(points);
        } finally {
            historyLock.unlock();
        }
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
        Objects.requireNonNull(target, "target must not be null");

        LoudnessData data = latestData;
        double measuredLufs = data.integratedLufs();
        double measuredPeak = data.truePeakDbfs();

        double lufsDiff = Math.abs(measuredLufs - target.targetIntegratedLufs());
        boolean loudnessPass = lufsDiff <= EXPORT_LOUDNESS_TOLERANCE_LU;
        boolean truePeakPass = measuredPeak <= target.maxTruePeakDbtp();

        StringBuilder sb = new StringBuilder();
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

    /**
     * Returns the latest measurements as a {@link LoudnessSnapshot} —
     * the SDK-level data carrier that bundles M, S, I, LRA, and the
     * sample-domain peak (dBFS). Note that the peak is a sample peak,
     * not an oversampled true peak; see {@link LoudnessSnapshot} for
     * details.
     *
     * @return a snapshot of the most recent measurements
     */
    public LoudnessSnapshot latestSnapshot() {
        LoudnessData d = latestData;
        return new LoudnessSnapshot(
                d.momentaryLufs(),
                d.shortTermLufs(),
                d.integratedLufs(),
                d.loudnessRange(),
                d.truePeakDbfs());
    }

    /**
     * Returns the {@link Flow.Publisher} of {@link LoudnessSnapshot}
     * updates produced by this meter. The publisher emits at most
     * ~10 Hz (one snapshot every 100 ms of processed audio) so
     * downstream subscribers (UI, telemetry, logging) can drive
     * meters without being flooded by the audio block rate.
     *
     * <p><b>Threading:</b> {@link #process} calls
     * {@link SubmissionPublisher#offer} — a lock-taking call — which is
     * why {@code process} is confined to the analysis thread or an
     * offline batch loop, never the render thread. The publisher uses the
     * default {@link SubmissionPublisher} executor, so subscriber
     * {@code onNext} callbacks do not run on the thread that calls
     * {@code process}; a slow subscriber therefore cannot stall the
     * analysis drain. Subscribers must still be non-blocking and should
     * hand off to their own thread (e.g. the FX dispatcher for JavaFX
     * meters). The underlying {@link SubmissionPublisher} drops items
     * if a subscriber cannot keep up.</p>
     *
     * @return the snapshot publisher
     */
    public Flow.Publisher<LoudnessSnapshot> snapshotPublisher() {
        return snapshotPublisher;
    }

    /**
     * Closes the snapshot publisher and releases any associated
     * resources. Subsequent calls to {@link #process} will not
     * publish further snapshots.
     */
    public void close() {
        snapshotPublisher.close();
    }

    /** Number of processing blocks in the short-term (3 s) window; test seam. */
    int shortTermWindowBlocks() {
        return shortTermFrames;
    }

    /** Capacity of the history ring; test seam. */
    int historyCapacity() {
        return historyCapacity;
    }

    // ----------------------------------------------------------------
    // Streaming statistics
    // ----------------------------------------------------------------

    private void accumulateLoudnessRange(double shortTermMeanSquare, double shortTermLufs) {
        // Only once the window is full, and only above the absolute gate.
        if (shortTermCount >= shortTermFrames && shortTermLufs > ABSOLUTE_GATE_LUFS) {
            lraBins[binIndexFor(shortTermLufs)]++;
            lraCount++;
            lraPowerSum += shortTermMeanSquare;
        }
    }

    private void accumulateIntegrated(double blockMeanSquare) {
        double blockLufs = meanSquareToLufs(blockMeanSquare);
        if (blockLufs > ABSOLUTE_GATE_LUFS) {
            integratedSum += blockMeanSquare;
            integratedBlocks++;
            int bin = binIndexFor(blockLufs);
            integratedBins[bin]++;
            integratedBinPower[bin] += blockMeanSquare;
        }
    }

    /**
     * ITU-R BS.1770-4 gated integrated loudness: the power mean of the
     * absolute-gated blocks that also lie above the relative gate
     * ({@value #INTEGRATED_RELATIVE_GATE_LU} LU below the absolute-gated
     * mean). Bounded: one walk over the fixed histogram.
     */
    private double computeIntegratedLufs() {
        if (integratedBlocks == 0) {
            return LUFS_FLOOR;
        }
        double absoluteGatedMean = meanSquareToLufs(integratedSum / integratedBlocks);
        int firstBin = firstBinAtOrAbove(absoluteGatedMean + INTEGRATED_RELATIVE_GATE_LU);
        long count = 0;
        double power = 0.0;
        for (int b = firstBin; b < HISTOGRAM_BINS; b++) {
            count += integratedBins[b];
            power += integratedBinPower[b];
        }
        return (count == 0) ? absoluteGatedMean : meanSquareToLufs(power / count);
    }

    /**
     * EBU Tech 3342 loudness range: the 95th minus the 10th percentile of
     * the short-term loudness distribution after the absolute gate
     * ({@value #ABSOLUTE_GATE_LUFS} LUFS) and the relative gate
     * ({@value #RELATIVE_GATE_LU} LU below the absolute-gated mean).
     * Percentiles are read from the fixed histogram at bin-centre
     * resolution ({@value #HISTOGRAM_BIN_LU} LU); the rank convention
     * (0-based rank ⌊n·p⌋, top rank clamped to n−1) matches the former
     * copy-and-sort implementation.
     */
    private double computeLoudnessRange() {
        if (lraCount < 2) {
            return 0.0;
        }
        double gatedMean = meanSquareToLufs(lraPowerSum / lraCount);
        int firstBin = firstBinAtOrAbove(gatedMean + RELATIVE_GATE_LU);

        long n = 0;
        for (int b = firstBin; b < HISTOGRAM_BINS; b++) {
            n += lraBins[b];
        }
        if (n < 2) {
            return 0.0;
        }
        long lowRank = (long) Math.floor(n * LRA_LOW_PERCENTILE);
        long highRank = Math.min((long) Math.floor(n * LRA_HIGH_PERCENTILE), n - 1);

        double low = Double.NaN;
        double high = Double.NaN;
        long cumulative = 0;
        for (int b = firstBin; b < HISTOGRAM_BINS; b++) {
            cumulative += lraBins[b];
            if (Double.isNaN(low) && cumulative > lowRank) {
                low = binCentre(b);
            }
            if (cumulative > highRank) {
                high = binCentre(b);
                break;
            }
        }
        return Math.max(0.0, high - low);
    }

    /** Bin containing {@code lufs}, clamped into the histogram range. */
    private static int binIndexFor(double lufs) {
        int bin = (int) Math.floor((lufs - HISTOGRAM_MIN_LUFS) / HISTOGRAM_BIN_LU + BIN_EDGE_EPSILON);
        return Math.max(0, Math.min(HISTOGRAM_BINS - 1, bin));
    }

    /**
     * First bin whose lower edge is at or above {@code thresholdLufs};
     * {@link #HISTOGRAM_BINS} when the threshold is above every bin.
     */
    private static int firstBinAtOrAbove(double thresholdLufs) {
        int bin = (int) Math.ceil((thresholdLufs - HISTOGRAM_MIN_LUFS) / HISTOGRAM_BIN_LU - BIN_EDGE_EPSILON);
        return Math.max(0, Math.min(HISTOGRAM_BINS, bin));
    }

    private static double binCentre(int bin) {
        return HISTOGRAM_MIN_LUFS + (bin + 0.5) * HISTOGRAM_BIN_LU;
    }

    private void clearIntegratedState() {
        integratedSum = 0;
        integratedBlocks = 0;
        Arrays.fill(integratedBins, 0L);
        Arrays.fill(integratedBinPower, 0.0);
    }

    private void clearLoudnessRangeState() {
        Arrays.fill(lraBins, 0L);
        lraCount = 0;
        lraPowerSum = 0;
        loudnessRange = 0.0;
    }

    // ----------------------------------------------------------------
    // History ring
    // ----------------------------------------------------------------

    private void recordHistory(double timestampSeconds, double momentaryLufs,
                               double shortTermLufs, double integratedLufs) {
        historyLock.lock();
        try {
            historyTimestamp[historyHead] = timestampSeconds;
            historyMomentary[historyHead] = momentaryLufs;
            historyShortTerm[historyHead] = shortTermLufs;
            historyIntegrated[historyHead] = integratedLufs;
            historyHead++;
            if (historyHead == historyCapacity) {
                historyHead = 0;
            }
            if (historySize < historyCapacity) {
                historySize++;
            }
        } finally {
            historyLock.unlock();
        }
    }

    private void clearHistory() {
        historyLock.lock();
        try {
            historyHead = 0;
            historySize = 0;
        } finally {
            historyLock.unlock();
        }
    }

    // ----------------------------------------------------------------
    // K-weighting filters (simplified biquad cascade)
    // ----------------------------------------------------------------

    private double applyKWeighting(double sample, int ch) {
        // Stage 1: High shelf (+4 dB above ~1500 Hz)
        double y1 = kw1Coeffs[0] * sample + kw1Coeffs[1] * kw1_x1[ch] + kw1Coeffs[2] * kw1_x2[ch]
                - kw1Coeffs[3] * kw1_y1[ch] - kw1Coeffs[4] * kw1_y2[ch];
        kw1_x2[ch] = kw1_x1[ch];
        kw1_x1[ch] = sample;
        kw1_y2[ch] = kw1_y1[ch];
        kw1_y1[ch] = y1;

        // Stage 2: High-pass (~60 Hz, 2nd order)
        double y2 = kw2Coeffs[0] * y1 + kw2Coeffs[1] * kw2_x1[ch] + kw2Coeffs[2] * kw2_x2[ch]
                - kw2Coeffs[3] * kw2_y1[ch] - kw2Coeffs[4] * kw2_y2[ch];
        kw2_x2[ch] = kw2_x1[ch];
        kw2_x1[ch] = y1;
        kw2_y2[ch] = kw2_y1[ch];
        kw2_y1[ch] = y2;

        return y2;
    }

    private static double windowMeanSquare(double[] buffer, int count) {
        if (count == 0) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += buffer[i];
        }
        return sum / count;
    }

    private static double meanSquareToLufs(double meanSquare) {
        if (meanSquare <= 0) return LUFS_FLOOR;
        return -0.691 + 10.0 * Math.log10(meanSquare);
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
