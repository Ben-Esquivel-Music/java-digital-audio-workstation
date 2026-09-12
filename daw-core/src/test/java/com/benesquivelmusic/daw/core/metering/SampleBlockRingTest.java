package com.benesquivelmusic.daw.core.metering;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SampleBlockRingTest {

    private static final int FRAMES = 8;

    @Test
    void capacityRoundsUpToAPowerOfTwo() {
        assertThat(new SampleBlockRing(5, FRAMES).capacity()).isEqualTo(8);
        assertThat(new SampleBlockRing(1, FRAMES).capacity()).isEqualTo(1);
        assertThat(new SampleBlockRing(4, 32).blockFrames()).isEqualTo(32);
    }

    @Test
    void constructorRejectsNonPositiveArguments() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SampleBlockRing(0, FRAMES));
        assertThatIllegalArgumentException().isThrownBy(() -> new SampleBlockRing(4, 0));
    }

    @Test
    void emptyRingReadsMinusOne() {
        SampleBlockRing ring = new SampleBlockRing(4, FRAMES);
        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.size()).isZero();
        assertThat(ring.readInto(scratch())).isEqualTo(-1);
        assertThat(ring.droppedBlocks()).isZero();
    }

    @Test
    void blocksComeOutInFifoOrder() {
        SampleBlockRing ring = new SampleBlockRing(4, FRAMES);
        for (int i = 0; i < 3; i++) {
            ring.write(block(i, 2), 2, FRAMES);
        }
        assertThat(ring.size()).isEqualTo(3);
        float[][] dst = scratch();
        for (int i = 0; i < 3; i++) {
            assertThat(ring.readInto(dst)).isEqualTo(FRAMES);
            assertThat(ring.lastChannelCount()).isEqualTo(2);
            assertThat(dst[0]).containsOnly(i);
            assertThat(dst[1]).containsOnly(i + 100f);
        }
        assertThat(ring.readInto(dst)).isEqualTo(-1);
        assertThat(ring.droppedBlocks()).isZero();
    }

    @Test
    void dropOldestKeepsTheNewestCapacityBlocksAndCountsExactly() {
        SampleBlockRing ring = new SampleBlockRing(4, FRAMES);
        for (int i = 0; i < 10; i++) {
            ring.write(block(i, 1), 1, FRAMES);
        }
        assertThat(ring.size()).isEqualTo(4);
        List<Float> seen = drainFirstLane(ring);
        assertThat(seen).containsExactly(6f, 7f, 8f, 9f);
        assertThat(ring.droppedBlocks()).isEqualTo(6L);

        for (int i = 10; i < 17; i++) {
            ring.write(block(i, 1), 1, FRAMES);
        }
        assertThat(drainFirstLane(ring)).containsExactly(13f, 14f, 15f, 16f);
        assertThat(ring.droppedBlocks()).isEqualTo(9L);
    }

    @Test
    void oversizedBlocksAreClampedAndCountedShortBlocksKeepTheirLength() {
        SampleBlockRing ring = new SampleBlockRing(2, FRAMES);
        float[][] wide = new float[2][FRAMES * 2];
        Arrays.fill(wide[0], 3f);
        ring.write(wide, 2, FRAMES * 2);
        assertThat(ring.truncatedBlocks()).isEqualTo(1L);
        float[][] dst = scratch();
        assertThat(ring.readInto(dst)).isEqualTo(FRAMES);
        assertThat(dst[0]).containsOnly(3f);

        ring.write(block(4, 1), 1, 3);
        assertThat(ring.readInto(dst)).isEqualTo(3);
        assertThat(ring.truncatedBlocks()).isEqualTo(1L);

        ring.write(block(5, 1), 1, -2);
        assertThat(ring.readInto(dst)).isZero();
    }

    @Test
    void laneCountClampsToMaxChannelsAndSourceWidth() {
        SampleBlockRing ring = new SampleBlockRing(2, FRAMES);
        float[][] tooWide = new float[SampleBlockRing.MAX_CHANNELS + 4][FRAMES];
        ring.write(tooWide, tooWide.length, FRAMES);
        float[][] dst = new float[SampleBlockRing.MAX_CHANNELS + 4][FRAMES];
        assertThat(ring.readInto(dst)).isEqualTo(FRAMES);
        assertThat(ring.lastChannelCount()).isEqualTo(SampleBlockRing.MAX_CHANNELS);

        ring.write(block(1, 2), 6, FRAMES);
        assertThat(ring.readInto(dst)).isEqualTo(FRAMES);
        assertThat(ring.lastChannelCount()).isEqualTo(2);
    }

    @Test
    void doubleSourceIsNarrowedWhileCopying() {
        SampleBlockRing ring = new SampleBlockRing(2, FRAMES);
        double[][] src = new double[2][FRAMES];
        Arrays.fill(src[0], 0.25);
        Arrays.fill(src[1], -0.5);
        ring.write(src, 2, FRAMES);
        float[][] dst = scratch();
        assertThat(ring.readInto(dst)).isEqualTo(FRAMES);
        assertThat(dst[0]).containsOnly(0.25f);
        assertThat(dst[1]).containsOnly(-0.5f);
    }

    @Test
    void writeScaledDuplicatesAMonoSourceWithThePanGains() {
        SampleBlockRing ring = new SampleBlockRing(2, FRAMES);
        float[][] mono = new float[1][FRAMES];
        Arrays.fill(mono[0], 1f);
        ring.writeScaled(mono, 2, FRAMES, 0.25f, 0.75f, 1.0f);
        float[][] dst = scratch();
        assertThat(ring.readInto(dst)).isEqualTo(FRAMES);
        assertThat(ring.lastChannelCount()).isEqualTo(2);
        assertThat(dst[0]).containsOnly(0.25f);
        assertThat(dst[1]).containsOnly(0.75f);
    }

    @Test
    void writeScaledScalesAStereoSourcePerLane() {
        SampleBlockRing ring = new SampleBlockRing(2, FRAMES);
        float[][] stereo = new float[2][FRAMES];
        Arrays.fill(stereo[0], 1f);
        Arrays.fill(stereo[1], -1f);
        ring.writeScaled(stereo, 2, FRAMES, 0.5f, 0.5f, 1.0f);
        float[][] dst = scratch();
        assertThat(ring.readInto(dst)).isEqualTo(FRAMES);
        assertThat(dst[0]).containsOnly(0.5f);
        assertThat(dst[1]).containsOnly(-0.5f);
    }

    @ParameterizedTest
    @Timeout(value = 1, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @ValueSource(ints = {(1 << 30) + 1, Integer.MAX_VALUE})
    void constructorRejectsCapacitiesThatWouldOverflowRounding(int requestedBlocks) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SampleBlockRing(requestedBlocks, FRAMES))
                .withMessage("requestedBlocks exceeds maximum power-of-two capacity: " + requestedBlocks);
    }

    @Test
    void silenceClearsReusedSlotsAndPreservesEachBlockLength() {
        var ring = new SampleBlockRing(1, FRAMES);
        float[][] destination = scratch();
        ring.write(block(1, SampleBlockRing.MAX_CHANNELS), SampleBlockRing.MAX_CHANNELS, FRAMES);
        assertThat(ring.readInto(destination)).isEqualTo(FRAMES);

        ring.writeSilence(1, 3);
        assertThat(ring.readInto(destination)).isEqualTo(3);
        assertThat(ring.lastChannelCount()).isEqualTo(1);
        assertThat(Arrays.copyOf(destination[0], 3)).containsOnly(0f);

        ring.writeSilence(SampleBlockRing.MAX_CHANNELS, FRAMES);
        assertThat(ring.readInto(destination)).isEqualTo(FRAMES);
        assertThat(ring.lastChannelCount()).isEqualTo(SampleBlockRing.MAX_CHANNELS);
        for (float[] lane : destination) {
            assertThat(lane).containsOnly(0f);
        }
        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.droppedBlocks()).isZero();
    }

    @Test
    void anAbortedDeferredWriteReplacesThePendingFullRingSlotOnlyOnce() {
        var ring = new SampleBlockRing(1, FRAMES);
        var destination = scratch();
        ring.write(block(1, 2), 2, FRAMES);
        ring.deferPublication();
        ring.write(block(2, 2), 2, FRAMES);
        assertThat(ring.readInto(destination)).isEqualTo(-1);
        assertThat(ring.droppedBlocks()).isEqualTo(1);
        ring.write(block(3, 2), 2, FRAMES);
        ring.writeSilence(2, FRAMES);
        assertThat(ring.readInto(destination)).isEqualTo(-1);

        ring.completePublication();

        assertThat(ring.readInto(destination)).isEqualTo(FRAMES);
        assertThat(destination[0]).containsOnly(0f);
        assertThat(destination[1]).containsOnly(0f);
        assertThat(ring.lastChannelCount()).isEqualTo(2);
        assertThat(ring.readInto(destination)).isEqualTo(-1);
        assertThat(ring.droppedBlocks()).isEqualTo(1);
        ring.deferPublication();
        ring.write(block(4, 2), 2, FRAMES);
        ring.completePublication();
        assertThat(ring.readInto(destination)).isEqualTo(FRAMES);
        assertThat(destination[0]).containsOnly(4f);
        assertThat(ring.droppedBlocks()).isEqualTo(1);
    }

    @Test
    void silenceClampsFramesAndChannelsAndCountsTruncation() {
        var ring = new SampleBlockRing(1, FRAMES);
        float[][] destination = scratch();
        ring.writeSilence(SampleBlockRing.MAX_CHANNELS + 1, FRAMES + 1);
        assertThat(ring.readInto(destination)).isEqualTo(FRAMES);
        assertThat(ring.lastChannelCount()).isEqualTo(SampleBlockRing.MAX_CHANNELS);
        assertThat(ring.truncatedBlocks()).isEqualTo(1);

        ring.writeSilence(-1, -1);
        assertThat(ring.readInto(destination)).isZero();
        assertThat(ring.lastChannelCount()).isZero();
        assertThat(ring.truncatedBlocks()).isEqualTo(1);
    }

    @Test
    void scaledSurroundLanesUseVolumeAndClampToTheMeterCapacity() {
        var ring = new SampleBlockRing(1, FRAMES);
        float[][] source = block(1, SampleBlockRing.MAX_CHANNELS + 2);
        ring.writeScaled(source, source.length, FRAMES, 0.25, 0.5, 0.75);
        float[][] result = scratch();
        assertThat(ring.readInto(result)).isEqualTo(FRAMES);
        assertThat(ring.lastChannelCount()).isEqualTo(SampleBlockRing.MAX_CHANNELS);
        for (int lane = 2; lane < SampleBlockRing.MAX_CHANNELS; lane++) {
            assertThat(result[lane]).containsOnly(source[lane][0] * 0.75f);
        }
    }

    @Test
    void scaledNullAndShortLanesClearSamplesFromThePreviousSlotUse() {
        var ring = new SampleBlockRing(1, FRAMES);
        ring.writeScaled(block(3, 3), 3, FRAMES, 1, 1, 1);
        float[][] result = scratch();
        ring.readInto(result);
        ring.writeScaled(new float[][]{{2f}, null, {4f, 4f}}, 3, FRAMES, 0.5, 0.25, 0.125);
        assertThat(ring.readInto(result)).isEqualTo(FRAMES);
        assertThat(result[0]).containsExactly(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        assertThat(result[1]).containsOnly(0f);
        assertThat(result[2]).containsExactly(0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f);
    }

    @Test
    void nullOrEmptySourcesAreIgnored() {
        SampleBlockRing ring = new SampleBlockRing(2, FRAMES);
        ring.write((float[][]) null, 2, FRAMES);
        ring.write((double[][]) null, 2, FRAMES);
        ring.writeScaled(null, 2, FRAMES, 1f, 1f, 1f);
        ring.writeScaled(new float[0][], 0, FRAMES, 1f, 1f, 1f);
        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.readInto(null)).isEqualTo(-1);
    }

    @Test
    void shortSourceLanesAreZeroPaddedToTheWrittenLength() {
        SampleBlockRing ring = new SampleBlockRing(2, FRAMES);
        float[][] src = {new float[] {1f, 1f}, new float[FRAMES]};
        ring.write(src, 2, FRAMES);
        float[][] dst = scratch();
        Arrays.fill(dst[0], 9f);
        assertThat(ring.readInto(dst)).isEqualTo(FRAMES);
        assertThat(dst[0]).containsExactly(1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f);
    }

    /**
     * Producer and consumer on two threads: every block read is intact (all
     * frames carry the block's value), sequence numbers only ever rise, and
     * every written block was either read or counted dropped — nothing is
     * duplicated, nothing vanishes silently.
     */
    @Test
    void concurrentProducerNeverCorruptsOrDuplicatesABlock() throws Exception {
        final int total = 200_000;
        SampleBlockRing ring = new SampleBlockRing(8, FRAMES);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = Thread.ofPlatform().name("ring-producer").unstarted(() -> {
            float[][] src = new float[1][FRAMES];
            for (int i = 1; i <= total; i++) {
                Arrays.fill(src[0], (float) i);
                ring.write(src, 1, FRAMES);
            }
        });
        long[] stats = new long[2]; // reads, last value
        Thread consumer = Thread.ofPlatform().name("ring-consumer").unstarted(() -> {
            float[][] dst = new float[1][FRAMES];
            float last = 0f;
            long reads = 0;
            try {
                boolean finalPass = false;
                while (true) {
                    if (!producer.isAlive()) {
                        finalPass = true;
                    }
                    int frames;
                    while ((frames = ring.readInto(dst)) >= 0) {
                        if (frames != FRAMES) {
                            throw new AssertionError("frames " + frames);
                        }
                        float value = dst[0][0];
                        for (int f = 1; f < FRAMES; f++) {
                            if (dst[0][f] != value) {
                                throw new AssertionError("torn block at value " + value);
                            }
                        }
                        if (value <= last) {
                            throw new AssertionError("non-monotonic: " + value + " after " + last);
                        }
                        last = value;
                        reads++;
                    }
                    if (finalPass) {
                        break;
                    }
                    Thread.onSpinWait();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
            stats[0] = reads;
            stats[1] = (long) last;
        });
        producer.start();
        consumer.start();
        producer.join(10_000L);
        consumer.join(10_000L);
        assertThat(producer.isAlive()).isFalse();
        assertThat(consumer.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(stats[0]).isPositive();
        assertThat(stats[1]).isEqualTo(total);
        assertThat(stats[0] + ring.droppedBlocks()).isEqualTo(total);
    }

    private static float[][] block(int value, int lanes) {
        float[][] src = new float[lanes][FRAMES];
        for (int ch = 0; ch < lanes; ch++) {
            Arrays.fill(src[ch], value + ch * 100f);
        }
        return src;
    }

    private static float[][] scratch() {
        return new float[SampleBlockRing.MAX_CHANNELS][FRAMES];
    }

    private static List<Float> drainFirstLane(SampleBlockRing ring) {
        List<Float> values = new ArrayList<>();
        float[][] dst = scratch();
        while (ring.readInto(dst) >= 0) {
            values.add(dst[0][0]);
        }
        return values;
    }
}
