package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * The analysis lane's bounded, lock-free, single-producer / single-consumer
 * ring of whole sample blocks (book &sect;3.4): preallocated
 * {@code float[MAX_CHANNELS][blockFrames]} slots, sized in blocks at attach,
 * <strong>drop-oldest with a counter</strong> when the consumer falls behind.
 *
 * <h2>Producer (render thread)</h2>
 * <p>{@link #write(float[][], int, int)} and friends never block and never
 * refuse: they overwrite the oldest slot. A per-slot sequence (set to
 * {@code -1} before the copy and to the slot's logical block number after,
 * with a release store) lets the consumer detect that the slot it was copying
 * was overwritten underneath it. Oversized blocks are clamped to
 * {@link #blockFrames()} and counted in {@link #truncatedBlocks()}.</p>
 *
 * <h2>Consumer (analysis thread)</h2>
 * <p>{@link #readInto(float[][])} detects a lap ({@code tail - head > capacity})
 * or a torn slot, skips forward to the oldest surviving block, adds the
 * skipped count to {@link #droppedBlocks()} (a consumer-written single-writer
 * {@code volatile long}), and validates the slot sequence before and after
 * its copy. Consumer-side methods are deliberately not {@code @RealTimeSafe}:
 * they may retry.</p>
 *
 * <p>House idiom: {@link com.benesquivelmusic.daw.core.audio.performance.XrunEventRingBuffer}
 * (which drops the <em>newest</em>) with the drop policy inverted — an analyzer
 * wants the most recent audio, not the oldest.</p>
 */
public final class SampleBlockRing {

    /** Lanes per slot. */
    public static final int MAX_CHANNELS = MeterFrame.MAX_CHANNELS;

    /** Bounded retry budget for {@link #readInto(float[][])} against a lapping producer. */
    public static final int MAX_READ_ATTEMPTS = 16;

    private static final VarHandle TAIL;
    private static final VarHandle SLOT_SEQUENCE = MethodHandles.arrayElementVarHandle(long[].class);

    static {
        try {
            TAIL = MethodHandles.lookup().findVarHandle(SampleBlockRing.class, "tail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final float[][][] slots;
    private final long[] slotSequence;
    private final int[] slotChannels;
    private final int[] slotFrames;
    private final int capacity;
    private final int mask;
    private final int blockFrames;

    @SuppressWarnings("unused") // accessed through TAIL only
    private long tail;                       // producer-owned; released through TAIL
    private long head;                       // consumer-owned
    private int lastChannelCount;            // consumer-owned
    private volatile long droppedBlocks;     // consumer-written, single writer
    private volatile long truncatedBlocks;   // producer-written, single writer

    /**
     * Creates a ring of at least {@code requestedBlocks} slots (rounded up to
     * a power of two), each holding {@code blockFrames} frames per lane.
     *
     * @throws IllegalArgumentException if either argument is not positive
     */
    public SampleBlockRing(int requestedBlocks, int blockFrames) {
        if (requestedBlocks <= 0) {
            throw new IllegalArgumentException("requestedBlocks must be positive: " + requestedBlocks);
        }
        if (blockFrames <= 0) {
            throw new IllegalArgumentException("blockFrames must be positive: " + blockFrames);
        }
        int cap = 1;
        while (cap < requestedBlocks) {
            cap <<= 1;
        }
        this.capacity = cap;
        this.mask = cap - 1;
        this.blockFrames = blockFrames;
        this.slots = new float[cap][MAX_CHANNELS][blockFrames];
        this.slotSequence = new long[cap];
        Arrays.fill(slotSequence, -1L);
        this.slotChannels = new int[cap];
        this.slotFrames = new int[cap];
    }

    // Producer side.

    /**
     * Copies a planar block into the next slot, overwriting the oldest if the
     * ring is full. Lanes beyond {@link #MAX_CHANNELS} and frames beyond
     * {@link #blockFrames()} are dropped (the latter counted).
     */
    @RealTimeSafe
    public void write(float[][] source, int channels, int numFrames) {
        if (source == null) {
            return;
        }
        int lanes = clampLanes(source.length, channels);
        int frames = clampFrames(numFrames);
        long t = (long) TAIL.getOpaque(this);
        int index = (int) (t & mask);
        SLOT_SEQUENCE.setOpaque(slotSequence, index, -1L);
        VarHandle.storeStoreFence();
        float[][] slot = slots[index];
        for (int ch = 0; ch < lanes; ch++) {
            float[] src = source[ch];
            float[] dst = slot[ch];
            int copied = src == null ? 0 : Math.min(frames, src.length);
            if (copied > 0) {
                System.arraycopy(src, 0, dst, 0, copied);
            }
            if (copied < frames) {
                Arrays.fill(dst, copied, frames, 0f);
            }
        }
        commit(index, lanes, frames, t);
    }

    /** As {@link #write(float[][], int, int)}, narrowing a 64-bit accumulator while copying. */
    @RealTimeSafe
    public void write(double[][] source, int channels, int numFrames) {
        if (source == null) {
            return;
        }
        int lanes = clampLanes(source.length, channels);
        int frames = clampFrames(numFrames);
        long t = (long) TAIL.getOpaque(this);
        int index = (int) (t & mask);
        SLOT_SEQUENCE.setOpaque(slotSequence, index, -1L);
        VarHandle.storeStoreFence();
        float[][] slot = slots[index];
        for (int ch = 0; ch < lanes; ch++) {
            double[] src = source[ch];
            float[] dst = slot[ch];
            int copied = src == null ? 0 : Math.min(frames, src.length);
            for (int f = 0; f < copied; f++) {
                dst[f] = (float) src[f];
            }
            if (copied < frames) {
                Arrays.fill(dst, copied, frames, 0f);
            }
        }
        commit(index, lanes, frames, t);
    }

    /**
     * Writes the post-fader stereo image of a channel exactly as the mix sum
     * produces it: lane 0 is {@code source[0] * gainLeft}, lane 1 is
     * {@code source[1] * gainRight} — or {@code source[0] * gainRight} when
     * the source is mono (duplicated, as in the constant-power pan law). The
     * written block always carries two lanes; a source with more than two
     * lanes contributes only its first two here.
     */
    @RealTimeSafe
    public void writeScaled(float[][] source, int channels, int numFrames,
                            float gainLeft, float gainRight) {
        if (source == null) {
            return;
        }
        int lanes = clampLanes(source.length, channels);
        if (lanes == 0) {
            return;
        }
        int frames = clampFrames(numFrames);
        long t = (long) TAIL.getOpaque(this);
        int index = (int) (t & mask);
        SLOT_SEQUENCE.setOpaque(slotSequence, index, -1L);
        VarHandle.storeStoreFence();
        float[][] slot = slots[index];
        float[] left = source[0];
        float[] right = lanes >= 2 && source[1] != null ? source[1] : left;
        scaleInto(left, slot[0], frames, gainLeft);
        scaleInto(right, slot[1], frames, gainRight);
        commit(index, 2, frames, t);
    }

    @RealTimeSafe
    private static void scaleInto(float[] src, float[] dst, int frames, float gain) {
        int copied = src == null ? 0 : Math.min(frames, src.length);
        for (int f = 0; f < copied; f++) {
            dst[f] = src[f] * gain;
        }
        if (copied < frames) {
            Arrays.fill(dst, copied, frames, 0f);
        }
    }

    @RealTimeSafe
    private void commit(int index, int lanes, int frames, long t) {
        slotChannels[index] = lanes;
        slotFrames[index] = frames;
        SLOT_SEQUENCE.setRelease(slotSequence, index, t);
        TAIL.setRelease(this, t + 1L);
    }

    @RealTimeSafe
    private int clampFrames(int numFrames) {
        if (numFrames > blockFrames) {
            truncatedBlocks++;
            return blockFrames;
        }
        return numFrames < 0 ? 0 : numFrames;
    }

    @RealTimeSafe
    private static int clampLanes(int available, int channels) {
        int lanes = Math.min(channels, available);
        if (lanes < 0) {
            return 0;
        }
        return Math.min(lanes, MAX_CHANNELS);
    }

    // Consumer side (analysis thread). Not @RealTimeSafe: may retry.

    /**
     * Copies the oldest surviving block into {@code destination}
     * ({@code destination[lane][frame]}, at least {@link #MAX_CHANNELS} lanes
     * of {@link #blockFrames()} frames for a lossless read).
     *
     * @return the number of frames copied, or {@code -1} when the ring is
     *         empty (or no stable copy could be obtained within the retry
     *         budget — try again on the next drain)
     */
    public int readInto(float[][] destination) {
        if (destination == null) {
            return -1;
        }
        long t = (long) TAIL.getAcquire(this);
        long h = head;
        if (h >= t) {
            return -1;
        }
        if (t - h > capacity) {
            long oldest = t - capacity;
            droppedBlocks += oldest - h;
            h = oldest;
            head = h;
        }
        for (int attempt = 0; attempt < MAX_READ_ATTEMPTS; attempt++) {
            int index = (int) (h & mask);
            long before = (long) SLOT_SEQUENCE.getAcquire(slotSequence, index);
            if (before == h) {
                int lanes = slotChannels[index];
                int frames = slotFrames[index];
                float[][] slot = slots[index];
                int copyLanes = Math.min(lanes, destination.length);
                for (int ch = 0; ch < copyLanes; ch++) {
                    float[] dst = destination[ch];
                    if (dst != null) {
                        System.arraycopy(slot[ch], 0, dst, 0, Math.min(frames, dst.length));
                    }
                }
                VarHandle.loadLoadFence();
                long after = (long) SLOT_SEQUENCE.getAcquire(slotSequence, index);
                if (after == h) {
                    head = h + 1L;
                    lastChannelCount = lanes;
                    return frames;
                }
            }
            // Lapped (or torn mid-copy): skip to the oldest surviving block.
            t = (long) TAIL.getAcquire(this);
            long next = Math.max(h + 1L, t - capacity);
            droppedBlocks += next - h;
            h = next;
            head = h;
            if (h >= t) {
                return -1;
            }
        }
        return -1;
    }

    /** Lanes carried by the block most recently returned from {@link #readInto(float[][])}. */
    public int lastChannelCount() {
        return lastChannelCount;
    }

    /** Cumulative blocks the consumer never saw because the producer lapped it. */
    public long droppedBlocks() {
        return droppedBlocks;
    }

    /** Cumulative writes whose {@code numFrames} exceeded {@link #blockFrames()}. */
    public long truncatedBlocks() {
        return truncatedBlocks;
    }

    /** Slot count (the requested capacity rounded up to a power of two). */
    public int capacity() {
        return capacity;
    }

    /** Frames per lane per slot. */
    public int blockFrames() {
        return blockFrames;
    }

    /** Published-but-unconsumed blocks (consumer-side view; saturates at {@link #capacity()}). */
    public int size() {
        long t = (long) TAIL.getAcquire(this);
        long pending = t - head;
        if (pending < 0L) {
            return 0;
        }
        return (int) Math.min(pending, capacity);
    }

    /** {@code true} when nothing is pending (consumer-side view). */
    public boolean isEmpty() {
        return head >= (long) TAIL.getAcquire(this);
    }

    @Override
    public String toString() {
        return "SampleBlockRing[capacity=" + capacity + ", blockFrames=" + blockFrames
                + ", dropped=" + droppedBlocks + ", truncated=" + truncatedBlocks + "]";
    }
}
