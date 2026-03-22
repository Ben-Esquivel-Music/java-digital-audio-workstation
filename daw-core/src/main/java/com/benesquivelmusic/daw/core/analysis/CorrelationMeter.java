package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.dsp.BiquadFilter;
import com.benesquivelmusic.daw.sdk.visualization.CorrelationData;
import com.benesquivelmusic.daw.sdk.visualization.GoniometerData;
import com.benesquivelmusic.daw.sdk.visualization.VisualizationProvider;

/**
 * Real-time stereo correlation and balance meter.
 *
 * <p>Analyzes the phase relationship between left and right channels,
 * producing correlation coefficient, mid/side levels, stereo balance,
 * goniometer/vectorscope visualization data, and frequency-dependent
 * correlation analysis.</p>
 *
 * <p>Implements the correlation metering and stereo imaging analysis
 * described in the mastering-techniques research (§7 — Stereo Imaging)
 * for monitoring mono compatibility and stereo field health.</p>
 */
public final class CorrelationMeter implements VisualizationProvider<CorrelationData> {

    private static final double DB_FLOOR = -120.0;
    private static final double SQRT2_INV = 1.0 / Math.sqrt(2.0);
    private static final double PHASE_INVERSION_THRESHOLD = -0.5;

    private final double smoothingFactor;
    private double smoothedCorrelation;
    private volatile CorrelationData latestData;
    private volatile GoniometerData latestGoniometerData;

    /**
     * Creates a correlation meter with the specified smoothing factor.
     *
     * @param smoothingFactor exponential smoothing in [0, 1); 0 = no smoothing
     */
    public CorrelationMeter(double smoothingFactor) {
        if (smoothingFactor < 0 || smoothingFactor >= 1.0) {
            throw new IllegalArgumentException(
                    "smoothingFactor must be in [0, 1): " + smoothingFactor);
        }
        this.smoothingFactor = smoothingFactor;
        this.smoothedCorrelation = 1.0;
        this.latestData = CorrelationData.SILENCE;
        this.latestGoniometerData = GoniometerData.EMPTY;
    }

    /**
     * Creates a correlation meter with default smoothing (0.9).
     */
    public CorrelationMeter() {
        this(0.9);
    }

    /**
     * Processes stereo audio and updates correlation data.
     *
     * @param left      left channel samples
     * @param right     right channel samples
     * @param numFrames number of frames to process
     */
    public void process(float[] left, float[] right, int numFrames) {
        double sumLR = 0.0;
        double sumLL = 0.0;
        double sumRR = 0.0;
        double sumMidSquared = 0.0;
        double sumSideSquared = 0.0;
        double sumLeft = 0.0;
        double sumRight = 0.0;

        for (int i = 0; i < numFrames; i++) {
            double l = left[i];
            double r = right[i];
            sumLR += l * r;
            sumLL += l * l;
            sumRR += r * r;

            // Mid/side encoding
            double mid = (l + r) * 0.5;
            double side = (l - r) * 0.5;
            sumMidSquared += mid * mid;
            sumSideSquared += side * side;

            sumLeft += l * l;
            sumRight += r * r;
        }

        // Correlation coefficient
        double denominator = Math.sqrt(sumLL * sumRR);
        double rawCorrelation = (denominator > 0) ? sumLR / denominator : 1.0;
        rawCorrelation = Math.max(-1.0, Math.min(1.0, rawCorrelation));

        smoothedCorrelation = smoothingFactor * smoothedCorrelation
                + (1.0 - smoothingFactor) * rawCorrelation;

        // Mid/side RMS levels in dB
        double midRms = Math.sqrt(sumMidSquared / numFrames);
        double sideRms = Math.sqrt(sumSideSquared / numFrames);
        double midDb = LevelMeter.linearToDb(midRms);
        double sideDb = LevelMeter.linearToDb(sideRms);

        // Stereo balance: -1.0 = left, 0.0 = center, 1.0 = right
        double leftRms = Math.sqrt(sumLeft / numFrames);
        double rightRms = Math.sqrt(sumRight / numFrames);
        double totalRms = leftRms + rightRms;
        double balance = (totalRms > 0) ? (rightRms - leftRms) / totalRms : 0.0;

        latestData = new CorrelationData(smoothedCorrelation, midDb, sideDb, balance);
    }

    /**
     * Processes stereo audio and updates both correlation and goniometer data.
     *
     * <p>This method performs the same correlation analysis as {@link #process}
     * and additionally generates Lissajous XY and polar vectorscope data for
     * goniometer display.</p>
     *
     * @param left      left channel samples
     * @param right     right channel samples
     * @param numFrames number of frames to process
     */
    public void processWithGoniometer(float[] left, float[] right, int numFrames) {
        process(left, right, numFrames);
        latestGoniometerData = generateGoniometerData(left, right, numFrames);
    }

    /**
     * Generates goniometer/vectorscope data from stereo audio.
     *
     * <p>Computes Lissajous XY coordinates and polar vectorscope
     * representation for the given audio buffer:</p>
     * <ul>
     *   <li>X = (L − R) / √2 (side component)</li>
     *   <li>Y = (L + R) / √2 (mid component)</li>
     *   <li>magnitude = √(X² + Y²)</li>
     *   <li>angle = atan2(X, Y)</li>
     * </ul>
     *
     * @param left      left channel samples
     * @param right     right channel samples
     * @param numFrames number of frames to process
     * @return goniometer data for visualization
     */
    public static GoniometerData generateGoniometerData(float[] left, float[] right, int numFrames) {
        float[] xPoints = new float[numFrames];
        float[] yPoints = new float[numFrames];
        float[] magnitudes = new float[numFrames];
        float[] angles = new float[numFrames];

        for (int i = 0; i < numFrames; i++) {
            double l = left[i];
            double r = right[i];

            // Lissajous: X = side, Y = mid (rotated 45°)
            double x = (l - r) * SQRT2_INV;
            double y = (l + r) * SQRT2_INV;
            xPoints[i] = (float) x;
            yPoints[i] = (float) y;

            // Polar vectorscope
            magnitudes[i] = (float) Math.sqrt(x * x + y * y);
            angles[i] = (float) Math.atan2(x, y);
        }

        return new GoniometerData(xPoints, yPoints, magnitudes, angles, numFrames);
    }

    /**
     * Returns the latest goniometer data produced by
     * {@link #processWithGoniometer}.
     *
     * @return latest goniometer data, or {@link GoniometerData#EMPTY}
     */
    public GoniometerData getLatestGoniometerData() {
        return latestGoniometerData;
    }

    /**
     * Analyzes per-frequency-band correlation between stereo channels.
     *
     * <p>Splits the signal into bands at the given edge frequencies using
     * {@link BiquadFilter} crossover filters and computes the correlation
     * coefficient independently for each band.</p>
     *
     * @param left       left channel samples
     * @param right      right channel samples
     * @param numFrames  number of frames to process
     * @param sampleRate the audio sample rate in Hz
     * @param bandEdges  sorted ascending crossover frequencies in Hz;
     *                   produces {@code bandEdges.length + 1} bands
     * @return per-band correlation coefficients (length = bandEdges.length + 1)
     */
    public static double[] analyzeFrequencyCorrelation(
            float[] left, float[] right, int numFrames,
            double sampleRate, double[] bandEdges) {

        int numBands = bandEdges.length + 1;
        double[] correlations = new double[numBands];

        // Split signals into bands
        float[][] bandLeft = new float[numBands][numFrames];
        float[][] bandRight = new float[numBands][numFrames];

        // Temporary buffers for cascaded splitting
        float[] remainingL = new float[numFrames];
        float[] remainingR = new float[numFrames];
        System.arraycopy(left, 0, remainingL, 0, numFrames);
        System.arraycopy(right, 0, remainingR, 0, numFrames);

        for (int b = 0; b < bandEdges.length; b++) {
            var lpL = BiquadFilter.create(
                    BiquadFilter.FilterType.LOW_PASS, sampleRate, bandEdges[b], 0.707, 0);
            var lpR = BiquadFilter.create(
                    BiquadFilter.FilterType.LOW_PASS, sampleRate, bandEdges[b], 0.707, 0);
            var hpL = BiquadFilter.create(
                    BiquadFilter.FilterType.HIGH_PASS, sampleRate, bandEdges[b], 0.707, 0);
            var hpR = BiquadFilter.create(
                    BiquadFilter.FilterType.HIGH_PASS, sampleRate, bandEdges[b], 0.707, 0);

            float[] nextRemainL = new float[numFrames];
            float[] nextRemainR = new float[numFrames];
            for (int i = 0; i < numFrames; i++) {
                bandLeft[b][i] = lpL.processSample(remainingL[i]);
                bandRight[b][i] = lpR.processSample(remainingR[i]);
                nextRemainL[i] = hpL.processSample(remainingL[i]);
                nextRemainR[i] = hpR.processSample(remainingR[i]);
            }
            remainingL = nextRemainL;
            remainingR = nextRemainR;
        }

        // Highest band is the remaining signal
        bandLeft[numBands - 1] = remainingL;
        bandRight[numBands - 1] = remainingR;

        // Compute correlation for each band
        for (int b = 0; b < numBands; b++) {
            correlations[b] = computeCorrelation(
                    bandLeft[b], bandRight[b], numFrames);
        }

        return correlations;
    }

    /**
     * Returns whether the latest measurement indicates a phase-inverted
     * signal (correlation below −0.5).
     *
     * @return {@code true} if the stereo signal appears phase-inverted
     */
    public boolean isPhaseInverted() {
        return smoothedCorrelation < PHASE_INVERSION_THRESHOLD;
    }

    /**
     * Returns a mono-compatibility score for the latest measurement.
     *
     * <p>The score ranges from {@code 0.0} (complete cancellation when
     * folded to mono) to {@code 1.0} (perfect mono compatibility). This
     * is derived from the correlation coefficient:
     * {@code (correlation + 1) / 2}.</p>
     *
     * @return mono compatibility score in [0.0, 1.0]
     */
    public double getMonoCompatibilityScore() {
        return (smoothedCorrelation + 1.0) / 2.0;
    }

    /**
     * Resets the meter to default state.
     */
    public void reset() {
        smoothedCorrelation = 1.0;
        latestData = CorrelationData.SILENCE;
        latestGoniometerData = GoniometerData.EMPTY;
    }

    @Override
    public CorrelationData getLatestData() {
        return latestData;
    }

    @Override
    public boolean hasData() {
        return latestData != null;
    }

    private static double computeCorrelation(float[] left, float[] right, int numFrames) {
        double sumLR = 0.0;
        double sumLL = 0.0;
        double sumRR = 0.0;

        for (int i = 0; i < numFrames; i++) {
            double l = left[i];
            double r = right[i];
            sumLR += l * r;
            sumLL += l * l;
            sumRR += r * r;
        }

        double denominator = Math.sqrt(sumLL * sumRR);
        if (denominator <= 0) {
            return 1.0;
        }
        return Math.max(-1.0, Math.min(1.0, sumLR / denominator));
    }
}
