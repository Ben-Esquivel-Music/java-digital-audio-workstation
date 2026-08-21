package com.benesquivelmusic.daw.sdk.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocation-free, single-producer / single-consumer ring of fixed-size
 * interleaved sample buffers used by the ASIO streaming bridge (story 311).
 *
 * <p>Two instances are in play, one per direction:</p>
 * <ul>
 *   <li><strong>Output.</strong> Producer is the engine render thread
 *       ({@link AsioBackend#sink(AudioBlock)} &rarr; {@link #write(float[], int)});
 *       consumer is the driver's real-time callback thread
 *       ({@link #drainLatestInto(float[])}), which deliberately discards
 *       everything but the newest queued block — playback must not fall
 *       behind.</li>
 *   <li><strong>Input.</strong> Producer is the driver's real-time callback
 *       thread ({@link #write(float[], int)}); consumer is the
 *       {@code asio-input-drain} thread ({@link #readInto(float[])}), which
 *       consumes strictly <em>in order</em>, one slot per call — recording must
 *       not skip blocks.</li>
 * </ul>
 *
 * <p>This is a deliberate in-module sibling of
 * {@code com.benesquivelmusic.daw.core.audio.performance.XrunEventRingBuffer},
 * which is the house lock-free SPSC idiom (pre-allocated mutable slots,
 * {@link AtomicLong} head/tail, {@code lazySet} release stores, drop-on-full
 * rather than block). {@code daw-sdk} is the API floor and must not depend on
 * {@code daw-core}, so the idiom is re-implemented here rather than reused.</p>
 *
 * <p>A single ring must use exactly one of {@link #drainLatestInto(float[])} or
 * {@link #readInto(float[])}: they are alternative consumer disciplines over
 * the same single consumer index, not composable operations.</p>
 */
final class AudioBlockRing {

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
    AudioBlockRing(int requestedCapacity, int samplesPerBlock) {
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
     * into the next free slot and publishes it.
     *
     * <p>Length mismatches are tolerated rather than rejected: a shorter
     * source is zero-padded and a longer one is truncated, so a caller that
     * momentarily hands over a differently-shaped block never throws on the
     * render or callback thread.</p>
     *
     * <p>When the ring is full the <em>incoming</em> block is dropped and the
     * queued ones are kept. Overwriting the oldest slot instead would not be
     * single-producer safe: the consumer may be copying out of that very slot
     * at the time.</p>
     *
     * @param source interleaved samples to publish; must not be null
     * @param length number of samples to take from {@code source}
     * @return {@code true} when the block was queued, {@code false} when the
     *         ring was full and the block was dropped
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
        // StoreLoad fence a plain volatile set would impose on the render
        // thread. The slot write above happens-before this store, so the
        // consumer's acquire-load of tail establishes the needed ordering.
        tail.lazySet(t + 1);
        return true;
    }

    /**
     * Consumes every queued slot and copies the most recent one into
     * {@code destination} — the output-direction consumer discipline.
     *
     * <p>Draining all pending slots (instead of one per call) keeps the
     * callback thread from replaying stale audio when the render thread
     * momentarily runs ahead: everything older than the newest queued block is
     * discarded unheard. Note that this is only true of blocks the producer
     * managed to queue — {@link #write(float[], int)} drops incoming blocks
     * while the ring is full, so a stalled consumer loses the newest audio, not
     * the oldest.</p>
     *
     * @param destination interleaved buffer to fill; must not be null.
     *                    Trailing elements beyond the slot size are zeroed
     * @return {@code true} when a block was copied, {@code false} when the
     *         ring was empty (in which case {@code destination} is untouched)
     */
    @RealTimeSafe
    boolean drainLatestInto(float[] destination) {
        if (destination == null) {
            return false;
        }
        long h = head.get();
        long t = tail.get();
        if (h >= t) {
            return false; // empty
        }
        float[] latest = slots[(int) ((t - 1) & mask)];
        int copied = Math.min(destination.length, latest.length);
        System.arraycopy(latest, 0, destination, 0, copied);
        if (copied < destination.length) {
            Arrays.fill(destination, copied, destination.length, 0f);
        }
        // Release store after the copy: the producer may only reuse the
        // slots once it observes the advanced head.
        head.lazySet(t);
        return true;
    }

    /**
     * Consumes exactly one queued slot, oldest first, into
     * {@code destination} — the input-direction consumer discipline
     * (story 311).
     *
     * <p>Unlike {@link #drainLatestInto(float[])} this never skips a block:
     * captured audio handed to {@link AsioBackend#inputBlocks()} subscribers
     * must stay contiguous, so the {@code asio-input-drain} thread calls this
     * in a loop until it returns {@code false}.</p>
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
     * {@link #write(float[], int)} without dropping it — i.e.
     * {@link #size()} {@code <} {@link #capacity()}.
     *
     * <p>Read-only on the head/tail {@link AtomicLong}s, so it is safe to
     * poll from any thread. Used by {@link
     * AsioBackend#awaitSinkCapacity(long)} on the non-real-time render pump
     * side to pace production by output-ring occupancy (story 316); the
     * answer is naturally racy across threads, which is fine for pacing —
     * a stale {@code false} just costs one park slice.</p>
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
        return "AudioBlockRing[capacity=" + capacity
                + ", samplesPerBlock=" + samplesPerBlock + "]";
    }
}
