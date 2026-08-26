package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocation-free, single-producer / single-consumer ring of fixed-size
 * interleaved sample buffers used by {@link CallbackBackendAdapter} to bridge
 * the non-real-time render pump and the legacy backend's real-time device
 * callback (story 316).
 *
 * <p>Two instances are in play, one per direction:</p>
 * <ul>
 *   <li><strong>Output.</strong> Producer is the engine render pump
 *       ({@code sink(AudioBlock)} &rarr; {@link #write(float[], int)});
 *       consumer is the device's real-time callback thread
 *       ({@link #readInto(float[])}), which consumes strictly in order.</li>
 *   <li><strong>Input.</strong> Producer is the device's real-time callback
 *       thread ({@link #write(float[], int)}); consumer is the
 *       {@code native-input-drain} thread ({@link #readInto(float[])}) —
 *       captured audio must stay contiguous, so it never skips a slot.</li>
 * </ul>
 *
 * <p>This is the house lock-free SPSC idiom
 * ({@link com.benesquivelmusic.daw.core.audio.performance.XrunEventRingBuffer}
 * is the canonical reference; daw-sdk's {@code AudioBlockRing} javadoc names
 * it as such): pre-allocated slots, {@link AtomicLong} head/tail,
 * {@code lazySet} release stores, drop-on-full rather than block. daw-sdk's
 * own {@code AudioBlockRing} is package-private in the SDK and daw-sdk must
 * not depend on daw-core, so the idiom is implemented here rather than
 * shared.</p>
 */
final class InterleavedBlockRing {

    private final float[][] slots;
    private final int mask;
    private final int capacity;
    private final int samplesPerBlock;
    private final AtomicLong head = new AtomicLong(0); // consumer
    private final AtomicLong tail = new AtomicLong(0); // producer

    /**
     * Creates a ring with at least {@code requestedCapacity} slots, each
     * holding {@code samplesPerBlock} interleaved float samples. Capacity is
     * rounded up to the next power of two so the index wrap is a mask.
     *
     * @param requestedCapacity minimum number of slots; must be positive
     * @param samplesPerBlock   interleaved samples per slot
     *                          ({@code channels * frames}); must be positive
     */
    InterleavedBlockRing(int requestedCapacity, int samplesPerBlock) {
        if (requestedCapacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be positive: " + requestedCapacity);
        }
        if (samplesPerBlock <= 0) {
            throw new IllegalArgumentException(
                    "samplesPerBlock must be positive: " + samplesPerBlock);
        }
        int cap = 1;
        while (cap < requestedCapacity) {
            cap <<= 1;
        }
        this.capacity = cap;
        this.mask = cap - 1;
        this.samplesPerBlock = samplesPerBlock;
        this.slots = new float[cap][samplesPerBlock];
    }

    /**
     * Copies up to {@link #samplesPerBlock()} samples from {@code source}
     * into the next free slot and publishes it. A shorter source is
     * zero-padded and a longer one truncated, so a momentarily
     * differently-shaped block never throws on the render or callback
     * thread. When the ring is full the <em>incoming</em> block is dropped
     * (overwriting the oldest slot would not be single-producer safe: the
     * consumer may be copying out of that very slot).
     *
     * @param source interleaved samples to publish; must not be null
     * @param length number of samples to take from {@code source}
     * @return {@code true} when the block was queued, {@code false} when the
     *         ring was full (or the arguments invalid) and it was dropped
     */
    @RealTimeSafe
    boolean write(float[] source, int length) {
        if (source == null || length < 0) {
            return false;
        }
        long t = tail.get();
        long h = head.get();
        if (t - h >= capacity) {
            return false; // full — drop the newest rather than block
        }
        float[] slot = slots[(int) (t & mask)];
        int copied = Math.min(Math.min(length, source.length), slot.length);
        System.arraycopy(source, 0, slot, 0, copied);
        if (copied < slot.length) {
            Arrays.fill(slot, copied, slot.length, 0f);
        }
        // lazySet (release store) is sufficient for SPSC and avoids the
        // StoreLoad fence a plain volatile set would impose on the RT
        // thread. The slot write above happens-before this store, so the
        // consumer's acquire-load of tail establishes the needed ordering.
        tail.lazySet(t + 1);
        return true;
    }

    /**
     * Consumes exactly one queued slot, oldest first, into
     * {@code destination}. Never skips a block — both directions here need
     * in-order delivery: playback must not reorder, captured audio must stay
     * contiguous.
     *
     * @param destination interleaved buffer to fill; must not be null.
     *                    Trailing elements beyond the slot size are zeroed
     * @return {@code true} when a block was copied, {@code false} when the
     *         ring was empty (in which case {@code destination} is untouched)
     */
    @RealTimeSafe
    boolean readInto(float[] destination) {
        if (destination == null) {
            return false;
        }
        long h = head.get();
        long t = tail.get();
        if (h >= t) {
            return false; // empty
        }
        float[] slot = slots[(int) (h & mask)];
        int copied = Math.min(destination.length, slot.length);
        System.arraycopy(slot, 0, destination, 0, copied);
        if (copied < destination.length) {
            Arrays.fill(destination, copied, destination.length, 0f);
        }
        // Release store after the copy: the producer may only reuse this slot
        // once it observes the advanced head.
        head.lazySet(h + 1);
        return true;
    }

    /** Returns the slot count (the requested capacity rounded up to a power of two). */
    int capacity() {
        return capacity;
    }

    /** Returns the interleaved sample count each slot holds. */
    int samplesPerBlock() {
        return samplesPerBlock;
    }

    /** Returns {@code true} when no published slot is pending. */
    @RealTimeSafe
    boolean isEmpty() {
        return head.get() >= tail.get();
    }

    /**
     * Returns {@code true} when the ring can accept another
     * {@link #write(float[], int)} without dropping it. Read-only on the
     * head/tail {@link AtomicLong}s, so it is safe to poll from any thread —
     * {@link CallbackBackendAdapter#awaitSinkCapacity(long)} polls it on the
     * non-real-time pump side for device-clock pacing; the answer is
     * naturally racy across threads, which is fine for pacing.
     */
    boolean hasSpace() {
        return size() < capacity;
    }

    /** Returns the number of published-but-unconsumed slots. */
    @RealTimeSafe
    int size() {
        long h = head.get();
        long t = tail.get();
        return (int) Math.max(0, t - h);
    }

    @Override
    public String toString() {
        return "InterleavedBlockRing[capacity=" + capacity
                + ", samplesPerBlock=" + samplesPerBlock + "]";
    }
}
