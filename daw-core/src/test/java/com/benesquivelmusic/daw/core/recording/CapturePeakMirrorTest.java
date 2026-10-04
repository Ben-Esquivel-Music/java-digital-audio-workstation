package com.benesquivelmusic.daw.core.recording;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * The lane's peak mirror is bounded and decimated (Recording Reliability
 * book §4.5): a fixed number of min/max buckets in one array that is never
 * replaced, the frames per bucket doubling each time the buckets fill, every
 * bucket holding the true extremes of the frames it covers, and an exact
 * frame count — for a take of any length. A sample that is not a number is
 * left out of the fold wherever it is; infinities are ordered like any
 * other sample.
 */
class CapturePeakMirrorTest {

    private static final int BUCKETS = CapturePeakMirror.BUCKETS;
    private static final int INITIAL = CapturePeakMirror.INITIAL_FRAMES_PER_BUCKET;
    private static final int BLOCK = 500; // deliberately not a divisor of a bucket

    /** A signal with no period shorter than the test: every bucket's extremes are its own. */
    private static float sample(long frame, int row) {
        long mixed = (frame * 2_654_435_761L + row * 40_503L) & 0xFFFF;
        return (mixed - 32_768) / 32_768f;
    }

    private static float[][] block(long firstFrame, int rows, int frames) {
        float[][] block = new float[rows][frames];
        for (int row = 0; row < rows; row++) {
            for (int i = 0; i < frames; i++) {
                block[row][i] = sample(firstFrame + i, row);
            }
        }
        return block;
    }

    /** Feeds frames {@code [from, to)} of the two-row signal in blocks of {@link #BLOCK}. */
    private static void feed(CapturePeakMirror mirror, long from, long to) {
        for (long frame = from; frame < to; frame += BLOCK) {
            int frames = (int) Math.min(BLOCK, to - frame);
            mirror.add(block(frame, 2, frames), 2, frames);
        }
    }

    /** Every bucket of {@code snapshot} holds the extremes of the frames it covers, across both rows. */
    private static void assertBucketsHoldTheTrueExtremes(CapturePeakSnapshot snapshot) {
        long perBucket = snapshot.framesPerBucket();
        for (int bucket = 0; bucket < snapshot.bucketCount(); bucket++) {
            long first = bucket * perBucket;
            long end = Math.min(first + perBucket, snapshot.totalFrames());
            float lo = Float.POSITIVE_INFINITY;
            float hi = Float.NEGATIVE_INFINITY;
            for (long frame = first; frame < end; frame++) {
                for (int row = 0; row < 2; row++) {
                    lo = Math.min(lo, sample(frame, row));
                    hi = Math.max(hi, sample(frame, row));
                }
            }
            if (snapshot.min(bucket) != lo || snapshot.max(bucket) != hi) {
                assertThat(new float[] {snapshot.min(bucket), snapshot.max(bucket)})
                        .as("bucket %d of %d at %d frames per bucket", bucket, snapshot.bucketCount(), perBucket)
                        .containsExactly(lo, hi);
            }
        }
    }

    @Test
    void theBucketsNeverExceedTheirNumberAndTheFramesPerBucketDoubleEachTimeTheyFill() {
        CapturePeakMirror mirror = new CapturePeakMirror();
        float[] backing = mirror.backingArray();
        assertThat(backing).hasSize(2 * BUCKETS);
        assertThat(Long.bitCount(INITIAL)).as("the frames per bucket start at a power of two").isEqualTo(1);

        long fed = 0;
        long perBucket = INITIAL;
        for (int doubling = 0; doubling < 4; doubling++) {
            // Exactly full: every bucket holds perBucket frames, and nothing has merged yet.
            long full = (long) BUCKETS * perBucket;
            feed(mirror, fed, full);
            fed = full;
            assertThat(mirror.framesPerBucket()).as("frames per bucket with %d frames in", fed).isEqualTo(perBucket);
            assertThat(mirror.bucketCount()).isEqualTo(BUCKETS);
            assertBucketsHoldTheTrueExtremes(mirror.snapshot("t", 0, 48_000.0));

            // One frame more: neighbours merge, the frames per bucket double.
            feed(mirror, fed, fed + 1);
            fed++;
            perBucket *= 2;
            assertThat(mirror.framesPerBucket()).as("frames per bucket after the buckets filled").isEqualTo(perBucket);
            assertThat(mirror.bucketCount()).as("half the buckets, and the one the new frame opened")
                    .isEqualTo(BUCKETS / 2 + 1);
            assertThat(mirror.totalFrames()).isEqualTo(fed);
            assertThat(mirror.backingArray()).as("the array the mirror was built with").isSameAs(backing);
            assertBucketsHoldTheTrueExtremes(mirror.snapshot("t", 0, 48_000.0));
        }
        assertThat(mirror.framesPerBucket()).isEqualTo(16L * INITIAL);
        assertThat(mirror.backingArray()).hasSize(2 * BUCKETS);
    }

    @Test
    void theBucketCountStaysBoundedAndTheFrameCountExactThroughATakeOfHours() {
        CapturePeakMirror mirror = new CapturePeakMirror();
        float[] backing = mirror.backingArray();
        float[][] silence = new float[1][4096];
        long frames = 0;
        long hours = 4L * 3600 * 48_000 + 123; // not a multiple of anything convenient
        while (frames < hours) {
            int now = (int) Math.min(silence[0].length, hours - frames);
            mirror.add(silence, 1, now);
            frames += now;
            if (mirror.bucketCount() > BUCKETS) {
                assertThat(mirror.bucketCount()).as("buckets in use at frame %d", frames).isLessThanOrEqualTo(BUCKETS);
            }
        }

        assertThat(mirror.totalFrames()).isEqualTo(hours);
        assertThat(mirror.backingArray()).isSameAs(backing).hasSize(2 * BUCKETS);
        CapturePeakSnapshot snapshot = mirror.snapshot("t", 0, 48_000.0);
        assertThat(snapshot.totalFrames()).isEqualTo(hours);
        assertThat(snapshot.bucketCount()).isBetween(BUCKETS / 2, BUCKETS);
        assertThat(Long.bitCount(snapshot.framesPerBucket())).isEqualTo(1);
        assertThat(snapshot.framesPerBucket()).as("fixture: the take was long enough to merge many times")
                .isGreaterThanOrEqualTo(1024L * INITIAL);
        assertThat((snapshot.totalFrames() + snapshot.framesPerBucket() - 1) / snapshot.framesPerBucket())
                .as("the buckets in use cover exactly the frames recorded").isEqualTo(snapshot.bucketCount());
    }

    @Test
    void aSingleSampleSpikeIsStillInItsBucketAfterEveryMerge() {
        CapturePeakMirror mirror = new CapturePeakMirror();
        long spikeUpAt = 3L * INITIAL + 17;
        long spikeDownAt = 1500L * INITIAL + 5;
        float[][] one = new float[1][1];
        long total = 64L * BUCKETS * INITIAL; // six merges
        float[][] quiet = new float[1][INITIAL];
        java.util.Arrays.fill(quiet[0], 0.01f);
        long frame = 0;
        while (frame < total) {
            if (frame == spikeUpAt - spikeUpAt % INITIAL || frame == spikeDownAt - spikeDownAt % INITIAL) {
                // The bucket that holds a spike is fed frame by frame.
                for (int i = 0; i < INITIAL; i++, frame++) {
                    one[0][0] = frame == spikeUpAt ? 0.9f : frame == spikeDownAt ? -0.8f : 0.01f;
                    mirror.add(one, 1, 1);
                }
            } else {
                mirror.add(quiet, 1, INITIAL);
                frame += INITIAL;
            }
        }

        CapturePeakSnapshot snapshot = mirror.snapshot("t", 0, 48_000.0);
        assertThat(snapshot.framesPerBucket()).as("fixture: six merges").isEqualTo(64L * INITIAL);
        int up = (int) (spikeUpAt / snapshot.framesPerBucket());
        int down = (int) (spikeDownAt / snapshot.framesPerBucket());
        assertThat(up).as("fixture: the spikes are in different buckets").isNotEqualTo(down);
        for (int bucket = 0; bucket < snapshot.bucketCount(); bucket++) {
            float expectedMax = bucket == up ? 0.9f : 0.01f;
            float expectedMin = bucket == down ? -0.8f : 0.01f;
            if (snapshot.max(bucket) != expectedMax || snapshot.min(bucket) != expectedMin) {
                assertThat(new float[] {snapshot.min(bucket), snapshot.max(bucket)}).as("bucket %d", bucket)
                        .containsExactly(expectedMin, expectedMax);
            }
        }
    }

    @Test
    void theFoldTakesThePeakAcrossTheRowsItIsGivenAndOnlyThose() {
        CapturePeakMirror mirror = new CapturePeakMirror();
        float[][] block = {{0.1f, 0.2f}, {-0.5f, 0.4f}, {0.99f, -0.99f}};

        mirror.add(block, 2, 2);

        CapturePeakSnapshot snapshot = mirror.snapshot("t", 0, 48_000.0);
        assertThat(snapshot.bucketCount()).isEqualTo(1);
        assertThat(snapshot.min(0)).as("the third row is not routed").isEqualTo(-0.5f);
        assertThat(snapshot.max(0)).isEqualTo(0.4f);

        CapturePeakMirror noRows = new CapturePeakMirror();
        noRows.add(block, 0, 2);
        assertThat(noRows.totalFrames()).as("frames with no routed row still count").isEqualTo(2);
        assertThat(noRows.snapshot("t", 0, 48_000.0).minMax()).containsExactly(0f, 0f);
        assertThatThrownBy(() -> mirror.add(block, 4, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFoldCoversEveryRowItIsGivenNotOnlyTheFirst() {
        CapturePeakMirror mirror = new CapturePeakMirror();
        // The first row is quiet; the extremes are in the second and the third.
        float[][] block = {{0.1f, 0.1f, 0.1f}, {0.1f, 0.9f, 0.1f}, {0.1f, 0.1f, -0.8f}};

        mirror.add(block, 3, 3);

        CapturePeakSnapshot snapshot = mirror.snapshot("t", 0, 44_100.0);
        assertThat(snapshot.max(0)).as("the second row's peak").isEqualTo(0.9f);
        assertThat(snapshot.min(0)).as("the third row's dip").isEqualTo(-0.8f);
    }

    @Test
    void aSampleThatIsNotANumberIsLeftOutWhereverItIsAndNeverSpreadsThroughAMerge() {
        float nan = Float.NaN;
        // Row 0, first frame of a bucket: the position that used to seed the bucket.
        CapturePeakMirror first = new CapturePeakMirror();
        first.add(new float[][] {{nan, 0.25f, -0.5f}}, 1, 3);
        assertThat(first.snapshot("t", 0, 48_000.0).minMax())
                .as("NaN on a bucket's first frame, row 0: the bucket holds the extremes of the numbers")
                .containsExactly(-0.5f, 0.25f);

        // A later row, and a later frame of the bucket.
        CapturePeakMirror later = new CapturePeakMirror();
        later.add(new float[][] {{0.25f, 0.5f, 0.125f}, {nan, -0.75f, nan}}, 2, 3);
        assertThat(later.snapshot("t", 0, 48_000.0).minMax())
                .as("NaN in row 1, on the first and on a later frame").containsExactly(-0.75f, 0.5f);

        // Row 0 NaN beside a number in row 1, and a frame with no number at all.
        CapturePeakMirror mixed = new CapturePeakMirror();
        mixed.add(new float[][] {{nan, nan}, {0.75f, nan}}, 2, 2);
        assertThat(mixed.totalFrames()).as("a frame with no number still counts").isEqualTo(2);
        assertThat(mixed.snapshot("t", 0, 48_000.0).minMax())
                .as("the all-NaN frame is silence; the other frame's number is the peak").containsExactly(0f, 0.75f);

        // Across merges: one NaN frame opens every other bucket; all the rest is 0.5.
        CapturePeakMirror merged = new CapturePeakMirror();
        float[][] one = new float[1][1];
        long frames = 2L * BUCKETS * INITIAL + 1; // two merges: at 2048 × 256 + 1 frames and at 2048 × 512 + 1
        for (long frame = 0; frame < frames; frame++) {
            one[0][0] = frame % (2 * INITIAL) == 0 ? nan : 0.5f;
            merged.add(one, 1, 1);
        }
        CapturePeakSnapshot snapshot = merged.snapshot("t", 0, 48_000.0);
        assertThat(snapshot.framesPerBucket()).as("fixture: the buckets merged twice").isEqualTo(4L * INITIAL);
        for (float value : snapshot.minMax()) {
            if (Float.isNaN(value)) {
                fail("a NaN sample reached a bucket and survived the merges");
            }
        }
        for (int bucket = 0; bucket < snapshot.bucketCount() - 1; bucket++) {
            if (snapshot.min(bucket) != 0f || snapshot.max(bucket) != 0.5f) {
                fail("bucket " + bucket + " holds [" + snapshot.min(bucket) + ", " + snapshot.max(bucket)
                        + "]: its mono NaN frames are silence, every other frame is 0.5");
            }
        }
        assertThat(snapshot.max(snapshot.bucketCount() - 1))
                .as("the last bucket holds one frame, the NaN one: silence").isEqualTo(0f);
    }

    @Test
    void infinitiesAreOrderedLikeAnyOtherSampleAndStayTheirBucketsExtremeThroughAMerge() {
        CapturePeakMirror mirror = new CapturePeakMirror();
        float[][] one = new float[1][1];
        long frames = (long) BUCKETS * INITIAL + 1; // one merge
        for (long frame = 0; frame < frames; frame++) {
            one[0][0] = frame == 3 ? Float.POSITIVE_INFINITY
                    : frame == 3L * INITIAL + 5 ? Float.NEGATIVE_INFINITY : 0.25f;
            mirror.add(one, 1, 1);
        }

        CapturePeakSnapshot snapshot = mirror.snapshot("t", 0, 48_000.0);
        assertThat(snapshot.framesPerBucket()).as("fixture: the buckets merged").isEqualTo(2L * INITIAL);
        assertThat(snapshot.max(0)).as("frame 3").isEqualTo(Float.POSITIVE_INFINITY);
        assertThat(snapshot.min(0)).isEqualTo(0.25f);
        assertThat(snapshot.min(1)).as("frame 3 × 256 + 5").isEqualTo(Float.NEGATIVE_INFINITY);
        assertThat(snapshot.max(1)).isEqualTo(0.25f);
        assertThat(snapshot.min(2)).isEqualTo(0.25f);

        CapturePeakMirror single = new CapturePeakMirror();
        single.add(new float[][] {{Float.POSITIVE_INFINITY}}, 1, 1);
        assertThat(single.snapshot("t", 0, 48_000.0).minMax())
                .as("a lone infinity is both extremes of its frame")
                .containsExactly(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
    }

    @Test
    void aFrameCountTheBlockCannotHoldIsRefusedBeforeAnythingIsFolded() {
        CapturePeakMirror mirror = new CapturePeakMirror();
        float[][] block = {{0.5f, 0.5f, 0.5f}, {0.5f, 0.5f}};

        assertThatThrownBy(() -> mirror.add(block, 1, -1)).as("negative")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("frames");
        assertThatThrownBy(() -> mirror.add(block, 2, 3)).as("row 1 holds two frames")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("row 1");
        assertThatThrownBy(() -> mirror.add(block, 1, 4)).as("row 0 holds three frames")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("row 0");

        assertThat(mirror.totalFrames()).as("a refused call folded nothing").isZero();
        assertThat(mirror.bucketCount()).isZero();
        assertThat(mirror.hasChangedSinceSnapshot()).isFalse();
        mirror.add(block, 1, 3);
        assertThat(mirror.totalFrames()).as("only the folded row's length bounds the frames").isEqualTo(3);
        mirror.add(block, 0, 5);
        assertThat(mirror.totalFrames()).as("with no rows nothing bounds them").isEqualTo(8);
    }

    @Test
    void aSnapshotCopiesTheBucketsOutOfTheMirrorOnce() {
        CapturePeakMirror mirror = new CapturePeakMirror();
        mirror.add(new float[][] {{0.5f, -0.5f}}, 1, 2);

        CapturePeakSnapshot snapshot = mirror.snapshot("t", 0, 48_000.0);

        assertThat(snapshot.minMax()).as("the pairs in use, not the mirror's whole array").hasSize(2)
                .containsExactly(-0.5f, 0.5f);
        mirror.backingArray()[0] = 9f;
        assertThat(snapshot.min(0)).as("the snapshot is not a view of the mirror's array").isEqualTo(-0.5f);
    }

    @Test
    void aResetEmptiesTheMirrorForTheNextLaneAndKeepsItsArray() {
        CapturePeakMirror mirror = new CapturePeakMirror();
        float[] backing = mirror.backingArray();
        feed(mirror, 0, (long) BUCKETS * INITIAL + 1);
        assertThat(mirror.framesPerBucket()).isEqualTo(2L * INITIAL);
        assertThat(mirror.hasChangedSinceSnapshot()).isTrue();

        mirror.reset();

        assertThat(mirror.totalFrames()).isZero();
        assertThat(mirror.bucketCount()).isZero();
        assertThat(mirror.framesPerBucket()).isEqualTo(INITIAL);
        assertThat(mirror.hasChangedSinceSnapshot()).isFalse();
        assertThat(mirror.backingArray()).isSameAs(backing);
        CapturePeakSnapshot empty = mirror.snapshot("t", 3, 44_100.0);
        assertThat(empty.bucketCount()).isZero();
        assertThat(empty.minMax()).isEmpty();
        assertThat(empty.laneIndex()).isEqualTo(3);

        // What the lane before left in the array does not show in the new lane.
        mirror.add(new float[][] {{0.25f}}, 1, 1);
        assertThat(mirror.snapshot("t", 3, 44_100.0).minMax()).containsExactly(0.25f, 0.25f);
    }

    @Test
    void aSnapshotIsACopyNobodyCanChange() {
        CapturePeakMirror mirror = new CapturePeakMirror();
        mirror.add(new float[][] {{0.5f, -0.5f}}, 1, 2);
        assertThat(mirror.hasChangedSinceSnapshot()).isTrue();

        CapturePeakSnapshot snapshot = mirror.snapshot("track-1", 2, 96_000.0);

        assertThat(mirror.hasChangedSinceSnapshot()).as("taking the snapshot clears the mark").isFalse();
        assertThat(snapshot.trackId()).isEqualTo("track-1");
        assertThat(snapshot.laneIndex()).isEqualTo(2);
        assertThat(snapshot.sampleRate()).isEqualTo(96_000.0);
        assertThat(snapshot.totalFrames()).isEqualTo(2);
        assertThat(snapshot.framesPerBucket()).isEqualTo(INITIAL);
        assertThat(snapshot.bucketCount()).isEqualTo(1);
        assertThat(snapshot.minMax()).containsExactly(-0.5f, 0.5f);

        // Neither the mirror's later frames nor a caller's writes reach it.
        mirror.add(new float[][] {{0.75f}}, 1, 1);
        snapshot.minMax()[1] = 123f;
        assertThat(snapshot.minMax()).containsExactly(-0.5f, 0.5f);
        assertThat(snapshot.max(0)).isEqualTo(0.5f);
        float[] handedIn = {-1f, 1f};
        CapturePeakSnapshot built = new CapturePeakSnapshot("t", 0, 48_000.0, 1, 256, 1, handedIn);
        handedIn[0] = 0f;
        assertThat(built.min(0)).isEqualTo(-1f);
        assertThat(built).isEqualTo(new CapturePeakSnapshot("t", 0, 48_000.0, 1, 256, 1, new float[] {-1f, 1f}))
                .hasSameHashCodeAs(new CapturePeakSnapshot("t", 0, 48_000.0, 1, 256, 1, new float[] {-1f, 1f}));
        assertThatThrownBy(() -> snapshot.min(1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void aSnapshotRefusesAShapeTheMirrorCannotProduce() {
        float[] onePair = {0f, 0f};
        assertThatThrownBy(() -> new CapturePeakSnapshot("t", 0, 48_000.0, 1, 300, 1, onePair))
                .as("frames per bucket is a power of two").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapturePeakSnapshot("t", 0, 48_000.0, 257, 256, 1, onePair))
                .as("257 frames at 256 per bucket are two buckets").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapturePeakSnapshot("t", 0, 48_000.0, 1, 256, 1, new float[1]))
                .as("fewer values than the buckets need").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapturePeakSnapshot("t", 0, 48_000.0, 257, 256, 2, new float[3]))
                .as("fewer values than the buckets need").isInstanceOf(IllegalArgumentException.class);
        // A longer array is the mirror's own, handed over to be copied once:
        // the snapshot holds the pairs in use and nothing behind them.
        float[] longer = {-0.25f, 0.5f, 7f, 8f};
        CapturePeakSnapshot prefix = new CapturePeakSnapshot("t", 0, 48_000.0, 1, 256, 1, longer);
        assertThat(prefix.minMax()).containsExactly(-0.25f, 0.5f);
        assertThat(prefix).isEqualTo(new CapturePeakSnapshot("t", 0, 48_000.0, 1, 256, 1, new float[] {-0.25f, 0.5f}));
        assertThatThrownBy(() -> new CapturePeakSnapshot("t", -1, 48_000.0, 1, 256, 1, onePair))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapturePeakSnapshot(null, 0, 48_000.0, 1, 256, 1, onePair))
                .isInstanceOf(NullPointerException.class);
        assertThat(CapturePeakSnapshot.MAX_BUCKETS).isEqualTo(BUCKETS);
    }
}
