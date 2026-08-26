package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SPSC semantics of {@link InterleavedBlockRing} (story 316): pre-allocated
 * slots, in-order consumption, drop-on-full, zero-pad/truncate on shape
 * mismatch — the house {@code XrunEventRingBuffer} idiom over interleaved
 * sample blocks.
 */
class InterleavedBlockRingTest {

    @Test
    void rejectsNonPositiveConstructionArguments() {
        assertThatThrownBy(() -> new InterleavedBlockRing(0, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterleavedBlockRing(4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capacityRoundsUpToAPowerOfTwo() {
        assertThat(new InterleavedBlockRing(3, 8).capacity()).isEqualTo(4);
        assertThat(new InterleavedBlockRing(4, 8).capacity()).isEqualTo(4);
        assertThat(new InterleavedBlockRing(5, 8).samplesPerBlock()).isEqualTo(8);
    }

    @Test
    void readIntoConsumesBlocksStrictlyInOrder() {
        InterleavedBlockRing ring = new InterleavedBlockRing(4, 4);
        assertThat(ring.write(new float[]{1f, 1f, 1f, 1f}, 4)).isTrue();
        assertThat(ring.write(new float[]{2f, 2f, 2f, 2f}, 4)).isTrue();

        float[] out = new float[4];
        assertThat(ring.readInto(out)).isTrue();
        assertThat(out).containsExactly(1f, 1f, 1f, 1f);
        assertThat(ring.readInto(out)).isTrue();
        assertThat(out).containsExactly(2f, 2f, 2f, 2f);
        assertThat(ring.readInto(out))
                .as("an empty ring reports empty and leaves the destination untouched")
                .isFalse();
        assertThat(out).containsExactly(2f, 2f, 2f, 2f);
    }

    @Test
    void writeDropsTheIncomingBlockWhenFull() {
        InterleavedBlockRing ring = new InterleavedBlockRing(2, 2);
        assertThat(ring.write(new float[]{1f, 1f}, 2)).isTrue();
        assertThat(ring.write(new float[]{2f, 2f}, 2)).isTrue();
        assertThat(ring.hasSpace()).isFalse();

        assertThat(ring.write(new float[]{3f, 3f}, 2))
                .as("the NEWEST block is dropped; queued ones are kept")
                .isFalse();

        float[] out = new float[2];
        assertThat(ring.readInto(out)).isTrue();
        assertThat(out).containsExactly(1f, 1f);
        assertThat(ring.hasSpace()).isTrue();
    }

    @Test
    void shorterSourceIsZeroPaddedAndLongerSourceTruncated() {
        InterleavedBlockRing ring = new InterleavedBlockRing(2, 4);
        assertThat(ring.write(new float[]{5f, 5f}, 2)).isTrue();
        float[] out = new float[4];
        assertThat(ring.readInto(out)).isTrue();
        assertThat(out).containsExactly(5f, 5f, 0f, 0f);

        assertThat(ring.write(new float[]{1f, 2f, 3f, 4f, 5f, 6f}, 6)).isTrue();
        assertThat(ring.readInto(out)).isTrue();
        assertThat(out).containsExactly(1f, 2f, 3f, 4f);
    }

    @Test
    void sizeAndEmptinessTrackTheProducerConsumerDistance() {
        InterleavedBlockRing ring = new InterleavedBlockRing(4, 2);
        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.size()).isZero();

        ring.write(new float[]{1f, 1f}, 2);
        ring.write(new float[]{2f, 2f}, 2);
        assertThat(ring.isEmpty()).isFalse();
        assertThat(ring.size()).isEqualTo(2);

        ring.readInto(new float[2]);
        assertThat(ring.size()).isEqualTo(1);
    }

    @Test
    void nullOrNegativeArgumentsAreToleratedNotThrown() {
        InterleavedBlockRing ring = new InterleavedBlockRing(2, 2);
        assertThat(ring.write(null, 2))
                .as("RT-path callers must never see a throw")
                .isFalse();
        assertThat(ring.write(new float[]{1f, 1f}, -1)).isFalse();
        assertThat(ring.readInto(null)).isFalse();
    }
}
