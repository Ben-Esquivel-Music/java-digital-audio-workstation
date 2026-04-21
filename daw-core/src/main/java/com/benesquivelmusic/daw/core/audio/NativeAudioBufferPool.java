package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A pre-allocated pool of off-heap {@link NativeAudioBuffer}-shaped buffers
 * backed by the Foreign Function &amp; Memory API
 * (JEP 454, final in JDK 22).
 *
 * <p>Why FFM instead of pooling {@link AudioBuffer}? Audio-engine buffers
 * live for the lifetime of a session and sit on hot paths inspected every
 * render callback. Keeping them off the Java heap:</p>
 * <ul>
 *   <li><b>Eliminates GC pressure.</b> Native memory is invisible to the
 *       garbage collector — even worst-case heap scans skip over it,
 *       shrinking the set of objects ZGC (JEP 439) has to trace.</li>
 *   <li><b>Gives deterministic de-allocation.</b> All buffer memory is
 *       allocated from a single {@link Arena} that the pool owns; closing
 *       the pool frees every buffer atomically, with no finalization,
 *       no {@code Cleaner} dependency, and no lingering references.</li>
 *   <li><b>Stays allocation-free on the audio thread.</b> Every buffer is
 *       allocated up front; {@link #acquire()} and {@link #release(PooledBuffer)}
 *       are lock-free operations backed by an {@link AtomicReferenceArray} that
 *       lets each slot be claimed atomically, preventing the publish-ordering
 *       race that a plain array + {@link AtomicInteger} top-of-stack would have.</li>
 * </ul>
 *
 * <p>The pool is thread-confined via {@link Arena#ofShared()} so that
 * consumer threads (UI, worker) may safely read the buffers between
 * render callbacks. Closing the pool after the audio thread stops is the
 * caller's responsibility.</p>
 */
public final class NativeAudioBufferPool implements AutoCloseable {

    /**
     * A single pooled buffer, carrying a channel-indexed view over a slice
     * of the pool's shared {@link Arena}.
     *
     * <p>{@link #channel(int)} returns a {@link MemorySegment} aliasing
     * one channel — no copy, no allocation, safe to call from the audio
     * thread.</p>
     */
    @RealTimeSafe
    public static final class PooledBuffer {
        private final MemorySegment full;
        private final int channels;
        private final int frames;
        private final long channelStrideBytes;
        /** Lightweight ownership guard — checked in {@link NativeAudioBufferPool#release}. */
        final int ownerPoolId;

        PooledBuffer(MemorySegment full, int channels, int frames, int ownerPoolId) {
            this.full = full;
            this.channels = channels;
            this.frames = frames;
            this.channelStrideBytes = (long) frames * Float.BYTES;
            this.ownerPoolId = ownerPoolId;
        }

        /** Returns the channel count. */
        @RealTimeSafe public int channels() { return channels; }

        /** Returns the frame count per channel. */
        @RealTimeSafe public int frames() { return frames; }

        /**
         * Returns the whole buffer as a {@link MemorySegment}
         * laid out channel-major: channel {@code c}, frame {@code f}
         * lives at byte offset {@code c * frames * 4 + f * 4}.
         */
        @RealTimeSafe public MemorySegment segment() { return full; }

        /**
         * Returns a {@link MemorySegment} aliasing a single channel's
         * {@code frames * 4} bytes. Zero-copy and allocation-free.
         */
        @RealTimeSafe
        public MemorySegment channel(int channel) {
            if (channel < 0 || channel >= channels) {
                throw new IndexOutOfBoundsException("channel: " + channel);
            }
            return full.asSlice((long) channel * channelStrideBytes, channelStrideBytes);
        }

        /**
         * Reads a single sample. Not preferred on the audio thread when a
         * whole-channel {@link #channel(int)} slice is already available;
         * provided for convenience.
         */
        @RealTimeSafe
        public float getSample(int channel, int frame) {
            long offset = (long) channel * channelStrideBytes + (long) frame * Float.BYTES;
            return full.get(ValueLayout.JAVA_FLOAT, offset);
        }

        /** Writes a single sample. */
        @RealTimeSafe
        public void setSample(int channel, int frame, float value) {
            long offset = (long) channel * channelStrideBytes + (long) frame * Float.BYTES;
            full.set(ValueLayout.JAVA_FLOAT, offset, value);
        }

        /** Fills the entire buffer with silence. */
        @RealTimeSafe
        public void clear() {
            full.fill((byte) 0);
        }
    }

    private static final AtomicInteger POOL_ID_SEQ = new AtomicInteger(0);

    private final int poolId;
    private final Arena arena;
    private final AtomicReferenceArray<PooledBuffer> pool;
    private final int channels;
    private final int frames;
    private volatile boolean closed;

    /**
     * Creates a pool of {@code poolSize} native buffers, all allocated
     * from a single shared {@link Arena} that the pool owns.
     */
    public NativeAudioBufferPool(int poolSize, int channels, int frames) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive: " + poolSize);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        this.poolId = POOL_ID_SEQ.getAndIncrement();
        this.channels = channels;
        this.frames = frames;
        this.arena = Arena.ofShared();

        long bufferBytes = (long) channels * frames * Float.BYTES;
        // One contiguous allocation holds every pooled buffer's storage;
        // slicing it into per-buffer segments costs no additional native
        // memory and keeps buffers cache-adjacent.
        MemorySegment block = arena.allocate(bufferBytes * poolSize, Float.BYTES);
        block.fill((byte) 0);

        this.pool = new AtomicReferenceArray<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            MemorySegment slice = block.asSlice(i * bufferBytes, bufferBytes);
            pool.set(i, new PooledBuffer(slice, channels, frames, this.poolId));
        }
    }

    /**
     * Acquires a buffer from the pool. The buffer is cleared to silence
     * before being returned.
     *
     * <p>Implemented as a forward linear scan over an {@link AtomicReferenceArray}:
     * each slot is claimed with a single {@code getAndSet} so that no two
     * callers can ever observe the same buffer, and no separate
     * top-of-stack pointer needs to be kept consistent with the slot write
     * (eliminating the publish-ordering race in the previous design).
     * Forward iteration matches CPU hardware prefetcher direction, favouring
     * cache locality for pool sizes up to a typical cache line.</p>
     *
     * @return a pooled buffer, or {@code null} if the pool is exhausted
     * @throws IllegalStateException if the pool has been closed
     */
    @RealTimeSafe
    public PooledBuffer acquire() {
        if (closed) {
            throw new IllegalStateException("Pool is closed");
        }
        for (int i = 0; i < pool.length(); i++) {
            PooledBuffer b = pool.getAndSet(i, null);
            if (b != null) {
                b.clear();
                return b;
            }
        }
        return null;
    }

    /**
     * Returns a buffer to the pool.
     *
     * <p>The slot is claimed atomically with {@link AtomicReferenceArray#compareAndSet}
     * (null → buffer), so concurrent releasers can never clobber each
     * other's writes. Both acquire and release scan forward from index 0,
     * which keeps the implementation simple and cache-friendly for the
     * primary SPSC audio-thread use case. If many threads release
     * simultaneously, they will CAS-retry past the first occupied slot,
     * but the pool size for any given buffer shape is small
     * (&lt; 32 in practice), bounding the worst-case scan.</p>
     *
     * @param buffer the buffer to return; must not be {@code null} and must
     *               originate from this pool (otherwise {@code false} is returned)
     * @return {@code true} if the buffer was returned, {@code false} if the
     *         pool is full, or the buffer belongs to a different pool
     * @throws NullPointerException if {@code buffer} is {@code null}
     */
    @RealTimeSafe
    public boolean release(PooledBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (buffer.ownerPoolId != poolId) {
            return false;
        }
        for (int i = 0; i < pool.length(); i++) {
            if (pool.compareAndSet(i, null, buffer)) {
                return true;
            }
        }
        return false;
    }

    /** Number of buffers currently available (counts non-null slots). */
    public int available() {
        int count = 0;
        for (int i = 0; i < pool.length(); i++) {
            if (pool.get(i) != null) count++;
        }
        return count;
    }

    /** Total pool capacity. */
    public int capacity() { return pool.length(); }

    /** Channels per buffer. */
    public int getChannels() { return channels; }

    /** Frames per buffer. */
    public int getFrames() { return frames; }

    /**
     * Frees every buffer's native memory in one call by closing the
     * underlying {@link Arena}. After close, {@link #acquire()} throws
     * and any outstanding segments become inaccessible.
     *
     * <p>Call this <em>after</em> the audio thread has stopped. The
     * operation is idempotent.</p>
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        arena.close();
    }

    /** {@code true} if {@link #close()} has been called. */
    public boolean isClosed() {
        return closed;
    }
}
