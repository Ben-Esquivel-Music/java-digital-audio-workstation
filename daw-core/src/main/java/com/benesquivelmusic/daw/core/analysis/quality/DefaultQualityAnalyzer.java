package com.benesquivelmusic.daw.core.analysis.quality;

import com.benesquivelmusic.daw.core.analysis.FftUtils;
import com.benesquivelmusic.daw.sdk.analysis.DynamicRangeMetrics;
import com.benesquivelmusic.daw.sdk.analysis.QualityAnalyzer;
import com.benesquivelmusic.daw.sdk.analysis.QualityReport;
import com.benesquivelmusic.daw.sdk.analysis.QualityThresholds;
import com.benesquivelmusic.daw.sdk.analysis.SignalQualityMetrics;
import com.benesquivelmusic.daw.sdk.analysis.SpectralQualityMetrics;
import com.benesquivelmusic.daw.sdk.analysis.StereoQualityMetrics;

import java.util.Arrays;

/**
 * Default implementation of {@link QualityAnalyzer} providing objective
 * audio quality metrics.
 *
 * <p>Implements signal quality (SNR, THD, crest factor), spectral quality
 * (flatness, centroid, bandwidth utilization), stereo quality (correlation,
 * width consistency, mono compatibility), and dynamic range (PLR, DR score)
 * metrics based on AES research on perceptual audio quality assessment.</p>
 *
 * <p>Uses a pure-Java Cooley–Tukey FFT for spectral analysis — no JNI required.</p>
 */
public final class DefaultQualityAnalyzer implements QualityAnalyzer {

    private static final double DB_FLOOR = -120.0;
    private static final int DEFAULT_FFT_SIZE = 4096;
    private static final double BANDWIDTH_THRESHOLD_DB = -60.0;

    /**
     * Creates a new quality analyzer instance.
     */
    public DefaultQualityAnalyzer() {
    }

    @Override
    public QualityReport analyze(float[] left, float[] right, int numFrames,
                                 double sampleRate, QualityThresholds thresholds) {
        // Compute mono mix for single-channel metrics
        float[] mono = new float[numFrames];
        for (int i = 0; i < numFrames; i++) {
            mono[i] = (left[i] + right[i]) * 0.5f;
        }

        var signal = analyzeSignalQuality(mono, numFrames);
        var spectral = analyzeSpectralQuality(mono, numFrames, sampleRate);
        var stereo = analyzeStereoQuality(left, right, numFrames);
        var dynamicRange = analyzeDynamicRange(mono, numFrames);

        return QualityReport.evaluate(signal, spectral, stereo, dynamicRange, thresholds);
    }

    @Override
    public SignalQualityMetrics analyzeSignalQuality(float[] samples, int numFrames) {
        if (numFrames == 0) {
            return SignalQualityMetrics.SILENCE;
        }

        double peak = 0.0;
        double sumSquares = 0.0;

        for (int i = 0; i < numFrames; i++) {
            double s = samples[i];
            double abs = Math.abs(s);
            if (abs > peak) {
                peak = abs;
            }
            sumSquares += s * s;
        }

        double rms = Math.sqrt(sumSquares / numFrames);

        // Crest factor: peak / RMS in dB
        double crestFactorDb;
        if (rms > 0 && peak > 0) {
            crestFactorDb = 20.0 * Math.log10(peak / rms);
        } else {
            crestFactorDb = 0.0;
        }

        // SNR estimation: signal power vs noise floor estimation
        // Sort absolute values to find the quietest portion as noise estimate
        double snrDb = estimateSnr(samples, numFrames, rms);

        // THD estimation via FFT: ratio of harmonic power to fundamental power
        double[] thdResult = estimateThd(samples, numFrames);
        double thdPercent = thdResult[0];
        double thdDb = thdResult[1];

        return new SignalQualityMetrics(snrDb, thdPercent, thdDb, crestFactorDb);
    }

    @Override
    public SpectralQualityMetrics analyzeSpectralQuality(float[] samples, int numFrames,
                                                         double sampleRate) {
        if (numFrames == 0) {
            return SpectralQualityMetrics.SILENCE;
        }

        int fftSize = Math.min(DEFAULT_FFT_SIZE, Integer.highestOneBit(numFrames));
        if (fftSize < 2) {
            return SpectralQualityMetrics.SILENCE;
        }

        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];
        double[] window = FftUtils.createHannWindow(fftSize);

        // Apply window and copy to FFT buffer
        int length = Math.min(numFrames, fftSize);
        for (int i = 0; i < length; i++) {
            real[i] = samples[i] * window[i];
        }

        FftUtils.fft(real, imag);

        int binCount = fftSize / 2;
        double[] magnitudes = new double[binCount];
        double totalPower = 0.0;
        double logSum = 0.0;
        double weightedFreqSum = 0.0;
        double binResolution = sampleRate / fftSize;
        int activeBins = 0;

        for (int i = 1; i < binCount; i++) {
            double mag = Math.sqrt(real[i] * real[i] + imag[i] * imag[i]) / fftSize;
            double power = mag * mag;
            magnitudes[i] = power;
            totalPower += power;

            if (power > 0) {
                logSum += Math.log(power);
                activeBins++;
            }

            double freq = i * binResolution;
            weightedFreqSum += freq * power;
        }

        // Spectral flatness: geometric mean / arithmetic mean of power spectrum
        double spectralFlatness;
        if (activeBins > 0 && totalPower > 0) {
            double geometricMean = Math.exp(logSum / activeBins);
            double arithmeticMean = totalPower / activeBins;
            spectralFlatness = (arithmeticMean > 0) ? geometricMean / arithmeticMean : 0.0;
            spectralFlatness = Math.max(0.0, Math.min(1.0, spectralFlatness));
        } else {
            spectralFlatness = 0.0;
        }

        // Spectral centroid: weighted average frequency
        double spectralCentroidHz = (totalPower > 0) ? weightedFreqSum / totalPower : 0.0;

        // Bandwidth utilization: fraction of bins with significant energy
        double thresholdPower = Math.pow(10.0, BANDWIDTH_THRESHOLD_DB / 10.0);
        int significantBins = 0;
        for (int i = 1; i < binCount; i++) {
            if (magnitudes[i] > thresholdPower) {
                significantBins++;
            }
        }
        double bandwidthUtilization = (binCount > 1)
                ? (double) significantBins / (binCount - 1) : 0.0;

        return new SpectralQualityMetrics(spectralFlatness, spectralCentroidHz,
                bandwidthUtilization);
    }

    @Override
    public StereoQualityMetrics analyzeStereoQuality(float[] left, float[] right,
                                                     int numFrames) {
        if (numFrames == 0) {
            return StereoQualityMetrics.SILENCE;
        }

        // Overall correlation coefficient
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
        double correlation = (denominator > 0) ? sumLR / denominator : 1.0;
        correlation = Math.max(-1.0, Math.min(1.0, correlation));

        // Stereo width consistency: measure width variation across blocks
        double stereoWidthConsistency = computeStereoWidthConsistency(left, right, numFrames);

        // Mono compatibility: (correlation + 1) / 2
        double monoCompatibility = (correlation + 1.0) / 2.0;

        return new StereoQualityMetrics(correlation, stereoWidthConsistency, monoCompatibility);
    }

    @Override
    public DynamicRangeMetrics analyzeDynamicRange(float[] samples, int numFrames) {
        if (numFrames == 0) {
            return DynamicRangeMetrics.SILENCE;
        }

        // Find peak level
        double peak = 0.0;
        double sumSquares = 0.0;
        for (int i = 0; i < numFrames; i++) {
            double abs = Math.abs(samples[i]);
            if (abs > peak) {
                peak = abs;
            }
            sumSquares += (double) samples[i] * samples[i];
        }
        double overallRms = Math.sqrt(sumSquares / numFrames);

        // PLR: peak-to-loudness ratio
        double plrDb;
        if (overallRms > 0 && peak > 0) {
            plrDb = 20.0 * Math.log10(peak / overallRms);
        } else {
            plrDb = 0.0;
        }

        // DR score: analyze RMS levels in blocks, find range between
        // loud and quiet sections
        double drScore = computeDrScore(samples, numFrames);

        return new DynamicRangeMetrics(plrDb, drScore);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Estimates SNR by comparing overall RMS to the noise floor estimated
     * from the quietest portion of the signal.
     */
    private double estimateSnr(float[] samples, int numFrames, double signalRms) {
        if (signalRms <= 0) {
            return 0.0;
        }

        // Compute RMS of short blocks and use the lowest as noise estimate
        int blockSize = Math.max(64, numFrames / 32);
        int numBlocks = numFrames / blockSize;
        if (numBlocks < 2) {
            // Not enough data for noise estimation; assume good SNR
            return 96.0;
        }

        double minBlockRms = Double.MAX_VALUE;
        for (int b = 0; b < numBlocks; b++) {
            double blockSum = 0.0;
            int offset = b * blockSize;
            for (int i = 0; i < blockSize; i++) {
                double s = samples[offset + i];
                blockSum += s * s;
            }
            double blockRms = Math.sqrt(blockSum / blockSize);
            if (blockRms < minBlockRms) {
                minBlockRms = blockRms;
            }
        }

        if (minBlockRms <= 0) {
            return 96.0; // Perfectly silent noise floor
        }

        return 20.0 * Math.log10(signalRms / minBlockRms);
    }

    /**
     * Estimates THD by finding the fundamental frequency via FFT and
     * measuring harmonic content relative to the fundamental.
     *
     * @return double[2]: [0] = THD percentage, [1] = THD in dB
     */
    private double[] estimateThd(float[] samples, int numFrames) {
        int fftSize = Math.min(DEFAULT_FFT_SIZE, Integer.highestOneBit(numFrames));
        if (fftSize < 4) {
            return new double[]{0.0, DB_FLOOR};
        }

        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];
        double[] window = FftUtils.createHannWindow(fftSize);

        int length = Math.min(numFrames, fftSize);
        for (int i = 0; i < length; i++) {
            real[i] = samples[i] * window[i];
        }

        FftUtils.fft(real, imag);

        // Find fundamental (highest magnitude bin, excluding DC)
        int binCount = fftSize / 2;
        int fundamentalBin = 1;
        double maxMag = 0.0;
        for (int i = 1; i < binCount; i++) {
            double mag = real[i] * real[i] + imag[i] * imag[i];
            if (mag > maxMag) {
                maxMag = mag;
                fundamentalBin = i;
            }
        }

        if (maxMag <= 0) {
            return new double[]{0.0, DB_FLOOR};
        }

        // Sum harmonic power (2nd through 6th harmonics)
        double fundamentalPower = maxMag;
        double harmonicPower = 0.0;
        for (int h = 2; h <= 6; h++) {
            int harmonicBin = fundamentalBin * h;
            if (harmonicBin < binCount) {
                harmonicPower += real[harmonicBin] * real[harmonicBin]
                        + imag[harmonicBin] * imag[harmonicBin];
            }
        }

        double thdRatio = Math.sqrt(harmonicPower / fundamentalPower);
        double thdPercent = thdRatio * 100.0;
        double thdDb = (thdRatio > 0) ? 20.0 * Math.log10(thdRatio) : DB_FLOOR;

        return new double[]{thdPercent, thdDb};
    }

    /**
     * Computes stereo width consistency by measuring how much the stereo width
     * varies across analysis blocks. Returns 1.0 for perfectly consistent width.
     */
    private double computeStereoWidthConsistency(float[] left, float[] right, int numFrames) {
        int blockSize = Math.max(64, numFrames / 16);
        int numBlocks = numFrames / blockSize;
        if (numBlocks < 2) {
            return 1.0;
        }

        double[] widths = new double[numBlocks];
        for (int b = 0; b < numBlocks; b++) {
            int offset = b * blockSize;
            double sumSideSquared = 0.0;
            double sumMidSquared = 0.0;
            for (int i = 0; i < blockSize; i++) {
                double l = left[offset + i];
                double r = right[offset + i];
                double mid = (l + r) * 0.5;
                double side = (l - r) * 0.5;
                sumMidSquared += mid * mid;
                sumSideSquared += side * side;
            }
            double midRms = Math.sqrt(sumMidSquared / blockSize);
            double sideRms = Math.sqrt(sumSideSquared / blockSize);
            double total = midRms + sideRms;
            widths[b] = (total > 0) ? sideRms / total : 0.0;
        }

        // Consistency = 1 - coefficient of variation
        double mean = 0.0;
        for (double w : widths) {
            mean += w;
        }
        mean /= numBlocks;

        if (mean <= 0) {
            return 1.0; // Mono signal, perfectly consistent
        }

        double variance = 0.0;
        for (double w : widths) {
            double diff = w - mean;
            variance += diff * diff;
        }
        variance /= numBlocks;
        double stdDev = Math.sqrt(variance);
        double cv = stdDev / mean;

        return Math.max(0.0, Math.min(1.0, 1.0 - cv));
    }

    /**
     * Computes a Dynamic Range (DR) score by analyzing RMS levels in short blocks.
     * The score represents the difference between the loudest and quietest
     * non-silent blocks in dB.
     */
    private double computeDrScore(float[] samples, int numFrames) {
        int blockSize = Math.max(64, numFrames / 32);
        int numBlocks = numFrames / blockSize;
        if (numBlocks < 2) {
            return 0.0;
        }

        double[] blockRmsDb = new double[numBlocks];
        for (int b = 0; b < numBlocks; b++) {
            double blockSum = 0.0;
            int offset = b * blockSize;
            for (int i = 0; i < blockSize; i++) {
                double s = samples[offset + i];
                blockSum += s * s;
            }
            double rms = Math.sqrt(blockSum / blockSize);
            blockRmsDb[b] = (rms > 0) ? 20.0 * Math.log10(rms) : DB_FLOOR;
        }

        Arrays.sort(blockRmsDb);

        // Find the highest RMS block
        double maxRmsDb = blockRmsDb[numBlocks - 1];

        // Find the 10th percentile RMS (quietest non-silent blocks)
        int lowIdx = (int) Math.floor(numBlocks * 0.1);
        double lowRmsDb = blockRmsDb[lowIdx];

        // Skip if quiet section is at the floor
        if (lowRmsDb <= DB_FLOOR) {
            // Use the lowest non-floor value
            for (double db : blockRmsDb) {
                if (db > DB_FLOOR) {
                    lowRmsDb = db;
                    break;
                }
            }
        }

        if (lowRmsDb <= DB_FLOOR || maxRmsDb <= DB_FLOOR) {
            return 0.0;
        }

        return Math.max(0.0, maxRmsDb - lowRmsDb);
    }

}
