package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.sdk.visualization.LoudnessData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multitrack mix feature analyzer.
 *
 * <p>Extracts the low-level audio features defined in the AES 2016 paper
 * <em>Variation in Multitrack Mixes: Analysis of Low-level Audio Signal
 * Features</em> for each track in a session and summarizes them with
 * session-level aggregate statistics. Also supports comparing two mixes
 * feature-by-feature (e.g. current vs. reference) via
 * {@link #compare(MixFeatureReport, MixFeatureReport)}.</p>
 *
 * <p>Features extracted per track:</p>
 * <ul>
 *   <li>RMS level (dBFS), peak level (dBFS), crest factor (dB)</li>
 *   <li>Spectral centroid (Hz), spectral spread (Hz), spectral flux</li>
 *   <li>Stereo width in [0, 1] (derived from side/(mid+side) energy)</li>
 *   <li>Integrated loudness (LUFS, ITU-R BS.1770)</li>
 *   <li>Per-band energy ratios (low/mid/high at 250 Hz / 4 kHz by default)</li>
 * </ul>
 *
 * <p>Session-level aggregates include arithmetic means of each feature,
 * session dynamic range (loudest peak − softest RMS), and energy-weighted
 * band ratios.</p>
 *
 * <p>Leverages the existing {@link SpectrumAnalyzer}, {@link LevelMeter},
 * {@link LoudnessMeter}, and {@link CorrelationMeter} where appropriate
 * for consistency with the rest of the analysis pipeline.</p>
 *
 * <p>This is a pure-Java, offline analyzer: feed it complete per-track
 * buffers and it returns a {@link MixFeatureReport}.</p>
 */
public final class MixFeatureAnalyzer {

    private static final double DB_FLOOR = -120.0;

    /** Default low/mid crossover (Hz) used for band energy ratios. */
    public static final double DEFAULT_LOW_MID_HZ = 250.0;
    /** Default mid/high crossover (Hz) used for band energy ratios. */
    public static final double DEFAULT_MID_HIGH_HZ = 4000.0;

    private static final int FFT_SIZE = 2048;
    private static final int HOP_SIZE = 1024;

    private final double sampleRate;
    private final double lowMidHz;
    private final double midHighHz;

    /**
     * Immutable input descriptor for a single track to analyze.
     *
     * @param name  human-readable track name (used in the report)
     * @param left  left-channel samples (also used for mono tracks)
     * @param right right-channel samples; pass the same array as {@code left}
     *              for mono tracks
     */
    public record Track(String name, float[] left, float[] right) {
        public Track {
            if (name == null) {
                throw new IllegalArgumentException("name must not be null");
            }
            if (left == null || right == null) {
                throw new IllegalArgumentException("channel arrays must not be null");
            }
            if (left.length != right.length) {
                throw new IllegalArgumentException(
                        "left/right channel lengths must match: "
                                + left.length + " vs " + right.length);
            }
        }

        /** Creates a mono track (left used for both channels). */
        public static Track mono(String name, float[] samples) {
            return new Track(name, samples, samples);
        }
    }

    /**
     * Creates a new analyzer with default band edges (250 Hz, 4 kHz).
     *
     * @param sampleRate audio sample rate in Hz
     */
    public MixFeatureAnalyzer(double sampleRate) {
        this(sampleRate, DEFAULT_LOW_MID_HZ, DEFAULT_MID_HIGH_HZ);
    }

    /**
     * Creates a new analyzer with custom low/mid and mid/high crossover
     * frequencies.
     *
     * @param sampleRate audio sample rate in Hz
     * @param lowMidHz   low/mid crossover frequency (Hz)
     * @param midHighHz  mid/high crossover frequency (Hz)
     */
    public MixFeatureAnalyzer(double sampleRate, double lowMidHz, double midHighHz) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (lowMidHz <= 0 || midHighHz <= lowMidHz) {
            throw new IllegalArgumentException(
                    "band edges must satisfy 0 < lowMidHz < midHighHz: "
                            + lowMidHz + ", " + midHighHz);
        }
        this.sampleRate = sampleRate;
        this.lowMidHz = lowMidHz;
        this.midHighHz = midHighHz;
    }

    /**
     * Analyzes the given tracks and produces a {@link MixFeatureReport}.
     *
     * @param tracks the tracks to analyze
     * @return a report containing per-track features and aggregate statistics
     */
    public MixFeatureReport analyze(List<Track> tracks) {
        if (tracks == null) {
            throw new IllegalArgumentException("tracks must not be null");
        }
        if (tracks.isEmpty()) {
            return MixFeatureReport.empty();
        }

        List<MixFeatureReport.TrackFeatures> results = new ArrayList<>(tracks.size());
        for (Track t : tracks) {
            results.add(analyzeTrack(t));
        }
        return new MixFeatureReport(results, aggregate(results));
    }

    private MixFeatureReport.TrackFeatures analyzeTrack(Track track) {
        int n = track.left().length;

        // Mono mix for level / spectral features
        float[] mono = new float[n];
        for (int i = 0; i < n; i++) {
            mono[i] = (track.left()[i] + track.right()[i]) * 0.5f;
        }

        // RMS, peak, crest factor
        double peak = 0.0;
        double sumSquares = 0.0;
        for (int i = 0; i < n; i++) {
            double abs = Math.abs(mono[i]);
            if (abs > peak) peak = abs;
            sumSquares += (double) mono[i] * mono[i];
        }
        double rms = (n > 0) ? Math.sqrt(sumSquares / n) : 0.0;
        double rmsDb = linearToDb(rms);
        double peakDb = linearToDb(peak);
        double crestFactorDb = (rms > 0 && peak > 0) ? 20.0 * Math.log10(peak / rms) : 0.0;

        // Spectral features (centroid, spread, flux, band ratios)
        SpectralResult spectral = computeSpectralFeatures(mono);

        // Stereo width
        double stereoWidth = computeStereoWidth(track.left(), track.right(), n);

        // Integrated loudness via existing LoudnessMeter
        double integratedLufs = computeIntegratedLufs(track.left(), track.right(), n);

        return new MixFeatureReport.TrackFeatures(
                track.name(), rmsDb, peakDb, crestFactorDb,
                spectral.centroidHz, spectral.spreadHz, spectral.flux,
                stereoWidth, integratedLufs, spectral.bandRatios);
    }

    private record SpectralResult(
            double centroidHz,
            double spreadHz,
            double flux,
            MixFeatureReport.BandEnergyRatios bandRatios) {}

    private SpectralResult computeSpectralFeatures(float[] mono) {
        int n = mono.length;
        if (n < 2) {
            return new SpectralResult(0, 0, 0, MixFeatureReport.BandEnergyRatios.EMPTY);
        }

        double[] window = FftUtils.createHannWindow(FFT_SIZE);
        double[] real = new double[FFT_SIZE];
        double[] imag = new double[FFT_SIZE];

        int binCount = FFT_SIZE / 2;
        double binResolution = sampleRate / FFT_SIZE;

        double centroidSum = 0.0;
        double spreadSum = 0.0;
        double powerSum = 0.0;
        double fluxSum = 0.0;
        int frameCount = 0;

        double lowEnergy = 0.0;
        double midEnergy = 0.0;
        double highEnergy = 0.0;

        double[] prevMag = null;

        for (int start = 0; start + FFT_SIZE <= n; start += HOP_SIZE) {
            // Window and zero-pad imag
            for (int i = 0; i < FFT_SIZE; i++) {
                real[i] = mono[start + i] * window[i];
                imag[i] = 0.0;
            }
            FftUtils.fft(real, imag);

            double[] mag = new double[binCount];
            double framePower = 0.0;
            double framePowerWeightedFreq = 0.0;

            for (int i = 0; i < binCount; i++) {
                double m = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]) / FFT_SIZE;
                mag[i] = m;
                double p = m * m;
                framePower += p;

                double freq = i * binResolution;
                framePowerWeightedFreq += freq * p;

                if (freq < lowMidHz) {
                    lowEnergy += p;
                } else if (freq < midHighHz) {
                    midEnergy += p;
                } else {
                    highEnergy += p;
                }
            }

            if (framePower > 0) {
                double frameCentroid = framePowerWeightedFreq / framePower;
                double variance = 0.0;
                for (int i = 0; i < binCount; i++) {
                    double freq = i * binResolution;
                    double d = freq - frameCentroid;
                    variance += d * d * (mag[i] * mag[i]);
                }
                double frameSpread = Math.sqrt(variance / framePower);

                centroidSum += frameCentroid * framePower;
                spreadSum += frameSpread * framePower;
                powerSum += framePower;
            }

            // Spectral flux: L2 distance of magnitude spectra between frames
            if (prevMag != null) {
                double diffSum = 0.0;
                for (int i = 0; i < binCount; i++) {
                    double d = mag[i] - prevMag[i];
                    diffSum += d * d;
                }
                fluxSum += Math.sqrt(diffSum);
            }
            prevMag = mag;
            frameCount++;
        }

        double centroidHz = (powerSum > 0) ? centroidSum / powerSum : 0.0;
        double spreadHz = (powerSum > 0) ? spreadSum / powerSum : 0.0;
        double flux = (frameCount > 1) ? fluxSum / (frameCount - 1) : 0.0;

        double totalBandEnergy = lowEnergy + midEnergy + highEnergy;
        MixFeatureReport.BandEnergyRatios ratios = (totalBandEnergy > 0)
                ? new MixFeatureReport.BandEnergyRatios(
                        lowEnergy / totalBandEnergy,
                        midEnergy / totalBandEnergy,
                        highEnergy / totalBandEnergy)
                : MixFeatureReport.BandEnergyRatios.EMPTY;

        return new SpectralResult(centroidHz, spreadHz, flux, ratios);
    }

    /**
     * Computes stereo width as side/(mid + side) RMS ratio in [0, 1].
     * 0 corresponds to perfect mono (L == R), 1 corresponds to a pure
     * side signal (L == −R).
     */
    private static double computeStereoWidth(float[] left, float[] right, int numFrames) {
        if (numFrames == 0) {
            return 0.0;
        }
        double sumMidSquared = 0.0;
        double sumSideSquared = 0.0;
        for (int i = 0; i < numFrames; i++) {
            double l = left[i];
            double r = right[i];
            double mid = (l + r) * 0.5;
            double side = (l - r) * 0.5;
            sumMidSquared += mid * mid;
            sumSideSquared += side * side;
        }
        double midRms = Math.sqrt(sumMidSquared / numFrames);
        double sideRms = Math.sqrt(sumSideSquared / numFrames);
        double total = midRms + sideRms;
        return (total > 0) ? sideRms / total : 0.0;
    }

    /**
     * Computes integrated LUFS for the given stereo buffer using the
     * existing {@link LoudnessMeter}.
     */
    private double computeIntegratedLufs(float[] left, float[] right, int numFrames) {
        int blockSize = (int) Math.round(sampleRate * 0.1); // 100 ms blocks
        if (blockSize <= 0 || numFrames < blockSize) {
            return Double.NEGATIVE_INFINITY;
        }
        LoudnessMeter meter = new LoudnessMeter(sampleRate, blockSize);
        int offset = 0;
        float[] lBlock = new float[blockSize];
        float[] rBlock = new float[blockSize];
        while (offset + blockSize <= numFrames) {
            System.arraycopy(left, offset, lBlock, 0, blockSize);
            System.arraycopy(right, offset, rBlock, 0, blockSize);
            meter.process(lBlock, rBlock, blockSize);
            offset += blockSize;
        }
        LoudnessData data = meter.getLatestData();
        return (data != null) ? data.integratedLufs() : Double.NEGATIVE_INFINITY;
    }

    private static MixFeatureReport.AggregateFeatures aggregate(
            List<MixFeatureReport.TrackFeatures> tracks) {
        if (tracks.isEmpty()) {
            return MixFeatureReport.AggregateFeatures.EMPTY;
        }

        int n = tracks.size();
        double rmsSum = 0, peakSum = 0, crestSum = 0;
        double centroidSum = 0, spreadSum = 0, fluxSum = 0, widthSum = 0;
        double lufsSum = 0;
        int lufsCount = 0;

        double loudestPeakDb = Double.NEGATIVE_INFINITY;
        double softestRmsDb = Double.POSITIVE_INFINITY;

        double lowSum = 0, midSum = 0, highSum = 0;

        for (MixFeatureReport.TrackFeatures t : tracks) {
            rmsSum += t.rmsDb();
            peakSum += t.peakDb();
            crestSum += t.crestFactorDb();
            centroidSum += t.spectralCentroidHz();
            spreadSum += t.spectralSpreadHz();
            fluxSum += t.spectralFlux();
            widthSum += t.stereoWidth();

            if (!Double.isInfinite(t.integratedLufs()) && !Double.isNaN(t.integratedLufs())) {
                lufsSum += t.integratedLufs();
                lufsCount++;
            }

            if (t.peakDb() > loudestPeakDb) loudestPeakDb = t.peakDb();
            if (t.rmsDb() < softestRmsDb && t.rmsDb() > DB_FLOOR) softestRmsDb = t.rmsDb();

            lowSum += t.bandEnergyRatios().low();
            midSum += t.bandEnergyRatios().mid();
            highSum += t.bandEnergyRatios().high();
        }

        double totalBand = lowSum + midSum + highSum;
        MixFeatureReport.BandEnergyRatios aggRatios = (totalBand > 0)
                ? new MixFeatureReport.BandEnergyRatios(
                        lowSum / totalBand, midSum / totalBand, highSum / totalBand)
                : MixFeatureReport.BandEnergyRatios.EMPTY;

        double meanLufs = (lufsCount > 0) ? lufsSum / lufsCount : Double.NEGATIVE_INFINITY;

        double dynamicRangeDb;
        if (loudestPeakDb > Double.NEGATIVE_INFINITY
                && softestRmsDb < Double.POSITIVE_INFINITY) {
            dynamicRangeDb = loudestPeakDb - softestRmsDb;
        } else {
            dynamicRangeDb = 0.0;
        }

        return new MixFeatureReport.AggregateFeatures(
                n,
                rmsSum / n,
                peakSum / n,
                crestSum / n,
                centroidSum / n,
                spreadSum / n,
                fluxSum / n,
                widthSum / n,
                meanLufs,
                dynamicRangeDb,
                aggRatios);
    }

    /**
     * Compares two mix reports feature by feature.
     *
     * <p>Per-track deltas are computed only for tracks whose names appear
     * in both reports (matched by {@code name}). Deltas are {@code b − a}
     * so positive values mean the second mix is higher.</p>
     *
     * @param a first (baseline) mix report
     * @param b second (candidate) mix report
     * @return comparison with per-track deltas and an aggregate delta
     */
    public static MixFeatureReport.Comparison compare(MixFeatureReport a, MixFeatureReport b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("reports must not be null");
        }

        Map<String, MixFeatureReport.TrackFeatures> byName = new LinkedHashMap<>();
        for (MixFeatureReport.TrackFeatures t : b.tracks()) {
            byName.put(t.name(), t);
        }

        List<MixFeatureReport.TrackFeatureDelta> deltas = new ArrayList<>();
        for (MixFeatureReport.TrackFeatures ta : a.tracks()) {
            MixFeatureReport.TrackFeatures tb = byName.get(ta.name());
            if (tb == null) continue;
            deltas.add(new MixFeatureReport.TrackFeatureDelta(
                    ta.name(),
                    tb.rmsDb() - ta.rmsDb(),
                    tb.peakDb() - ta.peakDb(),
                    tb.crestFactorDb() - ta.crestFactorDb(),
                    tb.spectralCentroidHz() - ta.spectralCentroidHz(),
                    tb.spectralSpreadHz() - ta.spectralSpreadHz(),
                    tb.spectralFlux() - ta.spectralFlux(),
                    tb.stereoWidth() - ta.stereoWidth(),
                    tb.integratedLufs() - ta.integratedLufs()));
        }

        MixFeatureReport.AggregateFeatures aa = a.aggregate();
        MixFeatureReport.AggregateFeatures ab = b.aggregate();
        MixFeatureReport.AggregateFeatureDelta aggDelta = new MixFeatureReport.AggregateFeatureDelta(
                ab.meanRmsDb() - aa.meanRmsDb(),
                ab.meanPeakDb() - aa.meanPeakDb(),
                ab.meanCrestFactorDb() - aa.meanCrestFactorDb(),
                ab.meanSpectralCentroidHz() - aa.meanSpectralCentroidHz(),
                ab.meanSpectralSpreadHz() - aa.meanSpectralSpreadHz(),
                ab.meanSpectralFlux() - aa.meanSpectralFlux(),
                ab.meanStereoWidth() - aa.meanStereoWidth(),
                ab.meanIntegratedLufs() - aa.meanIntegratedLufs(),
                ab.dynamicRangeDb() - aa.dynamicRangeDb());

        return new MixFeatureReport.Comparison(deltas, aggDelta);
    }

    private static double linearToDb(double linear) {
        if (linear <= 0.0) {
            return DB_FLOOR;
        }
        return Math.max(20.0 * Math.log10(linear), DB_FLOOR);
    }
}
