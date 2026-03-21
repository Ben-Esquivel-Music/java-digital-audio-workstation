package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.CorrelationData;
import com.benesquivelmusic.daw.sdk.visualization.VisualizationProvider;

/**
 * Real-time stereo correlation and balance meter.
 *
 * <p>Analyzes the phase relationship between left and right channels,
 * producing correlation coefficient, mid/side levels, and stereo balance
 * data for vectorscope and goniometer displays.</p>
 *
 * <p>Implements the correlation metering and stereo imaging analysis
 * described in the mastering-techniques research (§7 — Stereo Imaging)
 * for monitoring mono compatibility and stereo field health.</p>
 */
public final class CorrelationMeter implements VisualizationProvider<CorrelationData> {

    private static final double DB_FLOOR = -120.0;

    private final double smoothingFactor;
    private double smoothedCorrelation;
    private volatile CorrelationData latestData;

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
     * Resets the meter to default state.
     */
    public void reset() {
        smoothedCorrelation = 1.0;
        latestData = CorrelationData.SILENCE;
    }

    @Override
    public CorrelationData getLatestData() {
        return latestData;
    }

    @Override
    public boolean hasData() {
        return latestData != null;
    }
}
