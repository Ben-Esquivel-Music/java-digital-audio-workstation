package com.benesquivelmusic.daw.core.analysis;

import java.util.Arrays;

import static com.benesquivelmusic.daw.core.analysis.LoudnessMeter.HISTOGRAM_BINS;
import static com.benesquivelmusic.daw.core.analysis.LoudnessMeter.HISTOGRAM_BIN_LU;
import static com.benesquivelmusic.daw.core.analysis.LoudnessMeter.HISTOGRAM_MIN_LUFS;

/**
 * Fixed-memory loudness distribution with bounded detail inside each bin.
 *
 * <p>Bin aggregates make a gate query one histogram walk plus at most 32
 * comparisons in the boundary bin. Ordered centroids preserve distinct
 * programme levels within that bin, avoiding the large population error
 * caused by including or excluding the entire bin. Inserting a 33rd distinct
 * value merges the nearest neighbouring centroids, retaining their exact
 * accumulated count and power. Only a gate cutting through a merged cluster,
 * or a percentile falling inside one, needs the cluster's power mean as an
 * approximation. No storage grows with programme duration.</p>
 *
 * <p>Single-writer analysis-thread state, including the most recent gate.</p>
 */
final class LoudnessHistogram {

    static final int CENTROIDS_PER_BIN = 32;
    private static final int BIN_STRIDE = CENTROIDS_PER_BIN + 1;

    private final long[] binCounts = new long[HISTOGRAM_BINS];
    private final double[] binPowers = new double[HISTOGRAM_BINS];
    private final int[] binSizes = new int[HISTOGRAM_BINS];
    private final long[] centroidCounts = new long[HISTOGRAM_BINS * BIN_STRIDE];
    private final double[] centroidPowers = new double[HISTOGRAM_BINS * BIN_STRIDE];

    private int firstGatedBin;
    private double gatePower;
    private long gatedBoundaryCount;
    private long gatedCount;
    private double gatedPower;

    void add(double lufs, double power) {
        int bin = binIndex(lufs);
        binCounts[bin]++;
        binPowers[bin] += power;

        int start = bin * BIN_STRIDE;
        int end = start + binSizes[bin];
        int insertion = start;
        while (insertion < end && centroidMean(insertion) < power) {
            insertion++;
        }
        if (insertion < end && centroidMean(insertion) == power) {
            centroidCounts[insertion]++;
            centroidPowers[insertion] += power;
            return;
        }
        moveCentroids(insertion, insertion + 1, end - insertion);
        centroidCounts[insertion] = 1;
        centroidPowers[insertion] = power;
        if (++binSizes[bin] > CENTROIDS_PER_BIN) {
            mergeNearest(start);
            binSizes[bin]--;
        }
    }

    private void mergeNearest(int start) {
        int nearest = start;
        double closest = Double.POSITIVE_INFINITY;
        for (int index = start; index < start + CENTROIDS_PER_BIN; index++) {
            // Relative power distance gives equal preference to equal LU separations.
            double distance = centroidMean(index + 1) / centroidMean(index) - 1.0;
            if (distance < closest) {
                nearest = index;
                closest = distance;
            }
        }
        centroidCounts[nearest] += centroidCounts[nearest + 1];
        centroidPowers[nearest] += centroidPowers[nearest + 1];
        moveCentroids(nearest + 2, nearest + 1, start + CENTROIDS_PER_BIN - nearest - 1);
    }

    private void moveCentroids(int source, int destination, int length) {
        System.arraycopy(centroidCounts, source, centroidCounts, destination, length);
        System.arraycopy(centroidPowers, source, centroidPowers, destination, length);
    }

    void gate(double thresholdLufs) {
        firstGatedBin = binIndex(thresholdLufs);
        gatePower = Math.pow(10.0, (thresholdLufs + 0.691) / 10.0);
        gatedCount = 0;
        gatedPower = 0.0;
        int start = firstGatedBin * BIN_STRIDE;
        for (int index = start; index < start + binSizes[firstGatedBin]; index++) {
            if (centroidMean(index) >= gatePower) {
                gatedCount += centroidCounts[index];
                gatedPower += centroidPowers[index];
            }
        }
        gatedBoundaryCount = gatedCount;
        for (int bin = firstGatedBin + 1; bin < HISTOGRAM_BINS; bin++) {
            gatedCount += binCounts[bin];
            gatedPower += binPowers[bin];
        }
    }

    long gatedCount() {
        return gatedCount;
    }

    double gatedPower() {
        return gatedPower;
    }

    double gatedValueAtRank(long rank) {
        if (rank < 0 || rank >= gatedCount) {
            throw new IndexOutOfBoundsException("Rank outside gated distribution: " + rank);
        }
        for (int bin = firstGatedBin; bin < HISTOGRAM_BINS; bin++) {
            long count = bin == firstGatedBin ? gatedBoundaryCount : binCounts[bin];
            if (rank >= count) {
                rank -= count;
                continue;
            }
            int start = bin * BIN_STRIDE;
            for (int index = start; index < start + binSizes[bin]; index++) {
                if (bin == firstGatedBin && centroidMean(index) < gatePower) {
                    continue;
                }
                if (rank < centroidCounts[index]) {
                    return -0.691 + 10.0 * Math.log10(centroidMean(index));
                }
                rank -= centroidCounts[index];
            }
        }
        throw new IllegalStateException("Gated histogram rank has no observation");
    }

    private double centroidMean(int index) {
        return centroidPowers[index] / centroidCounts[index];
    }

    private static int binIndex(double lufs) {
        int bin = (int) Math.floor((lufs - HISTOGRAM_MIN_LUFS) / HISTOGRAM_BIN_LU);
        return Math.max(0, Math.min(HISTOGRAM_BINS - 1, bin));
    }

    /** Number of retained centroids; package-private for the bounded-memory regression. */
    int retainedCentroidCount() {
        return Arrays.stream(binSizes).sum();
    }

    void clear() {
        Arrays.fill(binCounts, 0L);
        Arrays.fill(binPowers, 0.0);
        Arrays.fill(binSizes, 0);
        Arrays.fill(centroidCounts, 0L);
        Arrays.fill(centroidPowers, 0.0);
        firstGatedBin = 0;
        gatePower = 0.0;
        gatedBoundaryCount = 0;
        gatedCount = 0;
        gatedPower = 0.0;
    }
}
