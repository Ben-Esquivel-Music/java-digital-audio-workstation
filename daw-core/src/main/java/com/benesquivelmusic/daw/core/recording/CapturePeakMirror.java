package com.benesquivelmusic.daw.core.recording;

import java.util.Objects;

/**
 * The bounded, decimated peak mirror of one take lane (Recording Reliability
 * book §4.5): {@link #BUCKETS} buckets of one minimum and one maximum each,
 * in one array allocated at construction and never replaced, whatever the
 * length of the take.
 *
 * <p>Every bucket covers {@link #framesPerBucket()} frames, a power of two
 * that starts at {@link #INITIAL_FRAMES_PER_BUCKET}. When a frame arrives
 * with every bucket full, neighbouring buckets are merged in place — the
 * smaller minimum and the larger maximum of each pair — which frees the
 * upper half of the array and doubles the frames per bucket. A merge only
 * ever takes the minimum of minimums and the maximum of maximums, so a
 * single sample that was a bucket's extreme is still inside the bucket that
 * covers its frame after any number of merges. The frame count is exact.</p>
 *
 * <p><strong>Samples that are not numbers.</strong> A NaN sample is left out
 * of the fold, whichever row and whichever frame of a bucket it is in: a
 * frame's extremes are those of its samples that are numbers, and a frame
 * with none — every folded sample NaN, or no rows — counts as silence
 * (0). So no bucket ever holds NaN, and a merge, which only compares bucket
 * values, cannot spread one. Infinities are numbers here: they order as
 * usual and stay a bucket's extreme through every merge.</p>
 *
 * <p><strong>Thread.</strong> One writer and one reader, the same thread: in
 * the pipeline the {@code capture-flush} thread, which feeds the mirror with
 * the frames a lane's session recorded, takes the snapshots and resets it
 * when a loop lane opens. Not thread-safe, and nothing of it is reachable
 * from the audio callback.</p>
 */
final class CapturePeakMirror {

    /** Buckets the mirror holds. */
    static final int BUCKETS = 2048;

    /** Frames a bucket covers until the first merge. */
    static final int INITIAL_FRAMES_PER_BUCKET = 256;

    /** Minimum at {@code 2i}, maximum at {@code 2i + 1}. */
    private final float[] minMax = new float[2 * BUCKETS];
    private long framesPerBucket = INITIAL_FRAMES_PER_BUCKET;
    /** Buckets holding {@link #framesPerBucket} frames each. */
    private int fullBuckets;
    /** Frames in the bucket after the full ones; less than {@link #framesPerBucket}. */
    private long openFrames;
    private long totalFrames;
    private boolean changedSinceSnapshot;

    /**
     * Folds {@code frames} frames of {@code block} into the mirror: for each
     * frame the smallest and the largest sample across the first
     * {@code rows} rows, NaN samples left out (class note). With no rows,
     * or in a frame whose folded samples are all NaN, the frame counts as
     * silence. The arguments are checked before anything is folded, so a
     * refused call leaves the mirror as it was.
     *
     * @param block  the audio {@code [row][frame]}
     * @param rows   rows to fold; at most {@code block.length}
     * @param frames frames to fold, from frame 0; not negative, and at most each folded row's length
     * @throws IllegalArgumentException if {@code rows} or {@code frames} is out of range
     */
    void add(float[][] block, int rows, int frames) {
        Objects.requireNonNull(block, "block must not be null");
        if (rows < 0 || rows > block.length) {
            throw new IllegalArgumentException("rows must be in [0, " + block.length + "]: " + rows);
        }
        if (frames < 0) {
            throw new IllegalArgumentException("frames must not be negative: " + frames);
        }
        for (int row = 0; row < rows; row++) {
            if (frames > block[row].length) {
                throw new IllegalArgumentException("frames must be at most the " + block[row].length
                        + " frame(s) of row " + row + ": " + frames);
            }
        }
        for (int frame = 0; frame < frames; frame++) {
            // Every comparison with NaN is false, so a NaN sample moves
            // neither extreme, in whichever row it is.
            float lo = Float.POSITIVE_INFINITY;
            float hi = Float.NEGATIVE_INFINITY;
            for (int row = 0; row < rows; row++) {
                float sample = block[row][frame];
                if (sample < lo) {
                    lo = sample;
                }
                if (sample > hi) {
                    hi = sample;
                }
            }
            if (lo > hi) {
                // No sample of this frame is a number: silence.
                lo = 0f;
                hi = 0f;
            }
            addFrame(lo, hi);
        }
        if (frames > 0) {
            changedSinceSnapshot = true;
        }
    }

    private void addFrame(float lo, float hi) {
        if (openFrames == 0) {
            if (fullBuckets == BUCKETS) {
                mergeNeighbours();
            }
            minMax[2 * fullBuckets] = lo;
            minMax[2 * fullBuckets + 1] = hi;
        } else {
            if (lo < minMax[2 * fullBuckets]) {
                minMax[2 * fullBuckets] = lo;
            }
            if (hi > minMax[2 * fullBuckets + 1]) {
                minMax[2 * fullBuckets + 1] = hi;
            }
        }
        openFrames++;
        totalFrames++;
        if (openFrames == framesPerBucket) {
            fullBuckets++;
            openFrames = 0;
        }
    }

    /** Every bucket is full: merges bucket {@code 2i} and {@code 2i + 1} into bucket {@code i}. */
    private void mergeNeighbours() {
        for (int i = 0; i < BUCKETS / 2; i++) {
            float lo = Math.min(minMax[4 * i], minMax[4 * i + 2]);
            float hi = Math.max(minMax[4 * i + 1], minMax[4 * i + 3]);
            minMax[2 * i] = lo;
            minMax[2 * i + 1] = hi;
        }
        fullBuckets = BUCKETS / 2;
        framesPerBucket *= 2;
    }

    /** Empties the mirror for a new lane; the array stays the one it was. */
    void reset() {
        framesPerBucket = INITIAL_FRAMES_PER_BUCKET;
        fullBuckets = 0;
        openFrames = 0;
        totalFrames = 0;
        changedSinceSnapshot = false;
    }

    /**
     * Copies the buckets in use into a snapshot — one copy, made by the
     * snapshot's constructor from the mirror's own array — and clears
     * {@link #hasChangedSinceSnapshot()} once the snapshot exists.
     *
     * @param trackId    id of the track the lane belongs to
     * @param laneIndex  the lane's index
     * @param sampleRate the lane's sample rate
     */
    CapturePeakSnapshot snapshot(String trackId, int laneIndex, double sampleRate) {
        CapturePeakSnapshot snapshot = new CapturePeakSnapshot(trackId, laneIndex, sampleRate, totalFrames,
                framesPerBucket, bucketCount(), minMax);
        changedSinceSnapshot = false;
        return snapshot;
    }

    /** Returns whether frames were added since the last snapshot or reset. */
    boolean hasChangedSinceSnapshot() {
        return changedSinceSnapshot;
    }

    /** Returns the buckets in use: the full ones and the one being filled, if it holds a frame. */
    int bucketCount() {
        return fullBuckets + (openFrames > 0 ? 1 : 0);
    }

    long framesPerBucket() {
        return framesPerBucket;
    }

    long totalFrames() {
        return totalFrames;
    }

    /** Test seam: the backing array itself, to assert it is never replaced. */
    float[] backingArray() {
        return minMax;
    }
}
