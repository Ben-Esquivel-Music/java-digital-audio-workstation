package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.dsp.TruePeakDetector;
import com.benesquivelmusic.daw.sdk.visualization.LevelData;
import com.benesquivelmusic.daw.sdk.visualization.VisualizationProvider;

/**
 * Real-time audio level meter producing peak, RMS, and true peak measurements.
 *
 * <p>Processes mono audio frames and produces {@link LevelData} snapshots
 * with linear and dB-scaled values for sample peak, RMS, and ITU-R BS.1770-4
 * true peak. Supports configurable peak-hold time for display purposes.</p>
 *
 * <p>Implements professional metering behavior referenced in the
 * mastering-techniques research (§4 — Dynamics Processing, §8 — Loudness
 * Standards and Metering).</p>
 */
public final class LevelMeter implements VisualizationProvider<LevelData> {

    private static final double DB_FLOOR = -120.0;

    private final double peakDecayPerFrame;
    private double heldPeak;
    private double heldTruePeak;
    private final TruePeakDetector truePeakDetector;
    private volatile LevelData latestData;

    /**
     * Creates a level meter with the specified peak decay rate.
     *
     * @param peakDecayRate decay rate per frame in linear units (e.g., 0.9995)
     */
    public LevelMeter(double peakDecayRate) {
        if (peakDecayRate < 0 || peakDecayRate >= 1.0) {
            throw new IllegalArgumentException(
                    "peakDecayRate must be in [0, 1): " + peakDecayRate);
        }
        this.peakDecayPerFrame = peakDecayRate;
        this.heldPeak = 0.0;
        this.heldTruePeak = 0.0;
        this.truePeakDetector = new TruePeakDetector();
        this.latestData = LevelData.SILENCE;
    }

    /**
     * Creates a level meter with a default peak decay rate of 0.9995.
     */
    public LevelMeter() {
        this(0.9995);
    }

    /**
     * Processes a block of mono audio samples and updates the level data.
     *
     * @param samples audio samples
     * @param offset  start offset in the array
     * @param length  number of samples to process
     */
    public void process(float[] samples, int offset, int length) {
        double peak = 0.0;
        double sumSquares = 0.0;
        double truePeak = 0.0;

        for (int i = offset; i < offset + length; i++) {
            double abs = Math.abs(samples[i]);
            if (abs > peak) {
                peak = abs;
            }
            sumSquares += (double) samples[i] * samples[i];

            double tp = truePeakDetector.processSample(samples[i]);
            if (tp > truePeak) {
                truePeak = tp;
            }
        }

        double rms = Math.sqrt(sumSquares / length);

        // Peak hold with decay
        if (peak >= heldPeak) {
            heldPeak = peak;
        } else {
            heldPeak *= peakDecayPerFrame;
        }

        // True peak hold with decay
        if (truePeak >= heldTruePeak) {
            heldTruePeak = truePeak;
        } else {
            heldTruePeak *= peakDecayPerFrame;
        }

        double peakDb = linearToDb(heldPeak);
        double rmsDb = linearToDb(rms);
        boolean clipping = heldPeak > 1.0;
        double truePeakDb = linearToDb(heldTruePeak);

        latestData = new LevelData(heldPeak, rms, peakDb, rmsDb, clipping,
                heldTruePeak, truePeakDb);
    }

    /**
     * Convenience overload that processes the entire sample array.
     *
     * @param samples audio samples
     */
    public void process(float[] samples) {
        process(samples, 0, samples.length);
    }

    /**
     * Resets the meter to silence.
     */
    public void reset() {
        heldPeak = 0.0;
        heldTruePeak = 0.0;
        truePeakDetector.reset();
        latestData = LevelData.SILENCE;
    }

    @Override
    public LevelData getLatestData() {
        return latestData;
    }

    @Override
    public boolean hasData() {
        return latestData != null;
    }

    static double linearToDb(double linear) {
        if (linear <= 0.0) {
            return DB_FLOOR;
        }
        return Math.max(20.0 * Math.log10(linear), DB_FLOOR);
    }
}
