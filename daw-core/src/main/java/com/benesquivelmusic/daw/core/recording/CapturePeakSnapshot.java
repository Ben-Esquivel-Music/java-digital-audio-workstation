package com.benesquivelmusic.daw.core.recording;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable copy of the decimated peaks of one take lane while it is
 * being captured (Recording Reliability book §4.5): the audio the lane has
 * recorded so far, reduced to at most {@link #MAX_BUCKETS} buckets, each the
 * minimum and the maximum sample of the frames it covers, across the track's
 * routed channels.
 *
 * <p>Bucket {@code i} covers the frames
 * {@code [i × framesPerBucket, (i + 1) × framesPerBucket)} of the lane; the
 * last bucket covers what remains up to {@link #totalFrames()} and may hold
 * fewer frames than the others. {@link #framesPerBucket()} is a power of two
 * that doubles as the lane grows, so a later snapshot of the same lane can
 * hold fewer buckets than an earlier one.</p>
 *
 * <p>A snapshot is handed to the sink installed with
 * {@link RecordingPipeline#setPeakSnapshotSink} on the {@code capture-flush}
 * thread. The lane index tells the laps of a loop-record take apart: each
 * lane starts again at frame 0.</p>
 *
 * @param trackId         id of the armed track
 * @param laneIndex       loop-take lane the peaks belong to (0 for a plain take)
 * @param sampleRate      stream sample rate the lane is captured at, in Hz
 * @param totalFrames     frames the lane has recorded
 * @param framesPerBucket frames a full bucket covers; a positive power of two
 * @param bucketCount     buckets in use; at most {@link #MAX_BUCKETS}
 * @param minMax          the buckets as pairs — minimum at {@code 2i}, maximum
 *                        at {@code 2i + 1}; at least {@code 2 × bucketCount}
 *                        values, of which the first {@code 2 × bucketCount}
 *                        are copied on the way in — the snapshot holds
 *                        exactly those — and copied again on the way out
 */
public record CapturePeakSnapshot(String trackId, int laneIndex, double sampleRate, long totalFrames,
                                  long framesPerBucket, int bucketCount, float[] minMax) {

    /** The most buckets a snapshot holds, whatever the length of the take. */
    public static final int MAX_BUCKETS = CapturePeakMirror.BUCKETS;

    public CapturePeakSnapshot {
        Objects.requireNonNull(trackId, "trackId must not be null");
        Objects.requireNonNull(minMax, "minMax must not be null");
        if (laneIndex < 0) {
            throw new IllegalArgumentException("laneIndex must not be negative: " + laneIndex);
        }
        if (!(sampleRate > 0)) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (totalFrames < 0) {
            throw new IllegalArgumentException("totalFrames must not be negative: " + totalFrames);
        }
        if (framesPerBucket <= 0 || Long.bitCount(framesPerBucket) != 1) {
            throw new IllegalArgumentException("framesPerBucket must be a positive power of two: " + framesPerBucket);
        }
        if (bucketCount < 0 || bucketCount > MAX_BUCKETS) {
            throw new IllegalArgumentException("bucketCount must be in [0, " + MAX_BUCKETS + "]: " + bucketCount);
        }
        if (minMax.length < 2 * bucketCount) {
            throw new IllegalArgumentException("minMax must hold at least " + 2 * bucketCount + " values for "
                    + bucketCount + " bucket(s): " + minMax.length);
        }
        long covered = (totalFrames + framesPerBucket - 1) / framesPerBucket;
        if (covered != bucketCount) {
            throw new IllegalArgumentException(totalFrames + " frame(s) at " + framesPerBucket
                    + " per bucket need " + covered + " bucket(s), not " + bucketCount);
        }
        // The one copy: the pairs in use, whatever lies behind them.
        minMax = Arrays.copyOf(minMax, 2 * bucketCount);
    }

    /** Returns a copy of the min/max pairs; changing it does not change the snapshot. */
    @Override
    public float[] minMax() {
        return minMax.clone();
    }

    /**
     * Returns the smallest sample of bucket {@code bucket}, without copying.
     *
     * @throws IndexOutOfBoundsException if {@code bucket} is not in {@code [0, bucketCount)}
     */
    public float min(int bucket) {
        return minMax[2 * Objects.checkIndex(bucket, bucketCount)];
    }

    /**
     * Returns the largest sample of bucket {@code bucket}, without copying.
     *
     * @throws IndexOutOfBoundsException if {@code bucket} is not in {@code [0, bucketCount)}
     */
    public float max(int bucket) {
        return minMax[2 * Objects.checkIndex(bucket, bucketCount) + 1];
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CapturePeakSnapshot that
                && trackId.equals(that.trackId)
                && laneIndex == that.laneIndex
                && Double.compare(sampleRate, that.sampleRate) == 0
                && totalFrames == that.totalFrames
                && framesPerBucket == that.framesPerBucket
                && bucketCount == that.bucketCount
                && Arrays.equals(minMax, that.minMax);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(trackId, laneIndex, sampleRate, totalFrames, framesPerBucket, bucketCount)
                + Arrays.hashCode(minMax);
    }

    @Override
    public String toString() {
        return "CapturePeakSnapshot[trackId=" + trackId + ", laneIndex=" + laneIndex + ", sampleRate=" + sampleRate
                + ", totalFrames=" + totalFrames + ", framesPerBucket=" + framesPerBucket
                + ", bucketCount=" + bucketCount + "]";
    }
}
