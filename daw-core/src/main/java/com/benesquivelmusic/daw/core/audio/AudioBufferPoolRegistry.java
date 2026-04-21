package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;

import java.util.Objects;

/**
 * A keyed registry of {@link AudioBufferPool}s indexed by
 * {@code (channelCount, blockSize, precision)}.
 *
 * <p>The audio graph contains buffers of many different shapes: a mono track
 * needs {@code (1, blockSize)}; a stereo bus needs {@code (2, blockSize)};
 * a 7.1.4 Atmos bed needs {@code (12, blockSize)}. Rather than allocate a
 * single oversized pool, the registry holds one small pool per shape so
 * acquisition stays O(1) and the audio thread never needs to resize a
 * buffer.</p>
 *
 * <p>All pools are pre-allocated up front during
 * {@link #register(int, int, MixPrecision, int)}. {@link #acquire(int, int, MixPrecision)}
 * and {@link #release(int, int, MixPrecision, AudioBuffer)} are allocation-free
 * at steady state and safe to call from the audio callback.</p>
 *
 * <p>Lookup is a short linear scan over the registered pools. For the
 * handful of buffer shapes a real session uses (typically &lt; 16) this
 * beats any hash-based structure because the latter would require
 * autoboxing the key components.</p>
 *
 * <p>Only the audio-thread entry points ({@link #pool},
 * {@link #acquire}, {@link #release}) are annotated {@link RealTimeSafe};
 * {@link #register} is called from the engine-construction thread and is
 * {@code synchronized}.</p>
 *
 * <p>Each shape may optionally also register an off-heap
 * {@link NativeAudioBufferPool} (FFM-backed, JEP 454) via
 * {@link #registerNative}. Native pools are ideal for buffers that live
 * for the whole session — they sit outside the GC heap so even ZGC does
 * not scan them, and all buffer memory is freed atomically when the
 * registry is closed.</p>
 */
public final class AudioBufferPoolRegistry implements AutoCloseable {

    /**
     * An (immutable) key describing a buffer shape. Kept package-private
     * on purpose: callers are expected to pass the three components
     * directly so the audio-thread path never constructs a {@code Key}.
     */
    private record Entry(int channels, int frames, MixPrecision precision, AudioBufferPool pool) {
        Entry {
            Objects.requireNonNull(precision, "precision must not be null");
            Objects.requireNonNull(pool, "pool must not be null");
        }
    }

    private Entry[] entries = new Entry[0];
    private NativeEntry[] nativeEntries = new NativeEntry[0];

    /**
     * Mirror of {@link Entry} for FFM-backed pools. Kept separate so that
     * registering a native pool never invalidates the on-heap lookup path.
     */
    private record NativeEntry(int channels, int frames, MixPrecision precision,
                               NativeAudioBufferPool pool) {
        NativeEntry {
            Objects.requireNonNull(precision, "precision must not be null");
            Objects.requireNonNull(pool, "pool must not be null");
        }
    }

    /**
     * Registers a pool for the given shape. Called during engine
     * construction, before the audio thread starts.
     *
     * @param channels  channel count, must be positive
     * @param frames    block size in sample frames, must be positive
     * @param precision sample precision, must not be null
     * @param poolSize  number of buffers to pre-allocate, must be positive
     * @return the newly registered pool
     * @throws IllegalStateException if a pool for this shape is already registered
     */
    public synchronized AudioBufferPool register(int channels,
                                                 int frames,
                                                 MixPrecision precision,
                                                 int poolSize) {
        Objects.requireNonNull(precision, "precision must not be null");
        if (findIndex(channels, frames, precision) >= 0) {
            throw new IllegalStateException(
                    "Pool already registered for (" + channels + ", " + frames
                            + ", " + precision + ")");
        }
        AudioBufferPool pool = new AudioBufferPool(poolSize, channels, frames);
        Entry[] copy = new Entry[entries.length + 1];
        System.arraycopy(entries, 0, copy, 0, entries.length);
        copy[entries.length] = new Entry(channels, frames, precision, pool);
        this.entries = copy;
        return pool;
    }

    /**
     * Returns the pool registered for the given shape, or {@code null} if
     * none has been registered. Allocation-free and real-time safe.
     */
    @RealTimeSafe
    public AudioBufferPool pool(int channels, int frames, MixPrecision precision) {
        int idx = findIndex(channels, frames, precision);
        return idx < 0 ? null : entries[idx].pool;
    }

    /**
     * Acquires a buffer of the given shape from the corresponding pool.
     * Returns {@code null} if no pool is registered or the pool is exhausted.
     */
    @RealTimeSafe
    public AudioBuffer acquire(int channels, int frames, MixPrecision precision) {
        AudioBufferPool p = pool(channels, frames, precision);
        return (p == null) ? null : p.acquire();
    }

    /**
     * Returns a buffer to the matching pool.
     *
     * @return {@code true} if the buffer was returned, {@code false} if the
     *         pool is full or not registered
     */
    @RealTimeSafe
    public boolean release(int channels, int frames, MixPrecision precision, AudioBuffer buffer) {
        AudioBufferPool p = pool(channels, frames, precision);
        return p != null && p.release(buffer);
    }

    /** Returns the number of registered pools. */
    public int size() {
        return entries.length;
    }

    // -----------------------------------------------------------------
    // FFM-backed native pool registration (JEP 454). The audio thread
    // uses these via nativePool()/acquireNative()/releaseNative(); the
    // registry closes all underlying Arenas atomically on close().
    // -----------------------------------------------------------------

    /**
     * Registers an off-heap (FFM) pool for the given shape. The pool
     * allocates all buffers from a single shared {@link java.lang.foreign.Arena};
     * closing the registry frees every buffer at once.
     *
     * @throws IllegalStateException if a native pool for this shape already exists
     */
    public synchronized NativeAudioBufferPool registerNative(int channels,
                                                             int frames,
                                                             MixPrecision precision,
                                                             int poolSize) {
        Objects.requireNonNull(precision, "precision must not be null");
        if (findNativeIndex(channels, frames, precision) >= 0) {
            throw new IllegalStateException(
                    "Native pool already registered for (" + channels + ", " + frames
                            + ", " + precision + ")");
        }
        NativeAudioBufferPool pool = new NativeAudioBufferPool(poolSize, channels, frames);
        NativeEntry[] copy = new NativeEntry[nativeEntries.length + 1];
        System.arraycopy(nativeEntries, 0, copy, 0, nativeEntries.length);
        copy[nativeEntries.length] = new NativeEntry(channels, frames, precision, pool);
        this.nativeEntries = copy;
        return pool;
    }

    /** Returns the native pool for the given shape, or {@code null}. */
    @RealTimeSafe
    public NativeAudioBufferPool nativePool(int channels, int frames, MixPrecision precision) {
        int idx = findNativeIndex(channels, frames, precision);
        return idx < 0 ? null : nativeEntries[idx].pool;
    }

    /**
     * Acquires a native (FFM-backed) buffer of the given shape.
     * Returns {@code null} if no native pool is registered or the pool is exhausted.
     */
    @RealTimeSafe
    public NativeAudioBufferPool.PooledBuffer acquireNative(int channels,
                                                            int frames,
                                                            MixPrecision precision) {
        NativeAudioBufferPool p = nativePool(channels, frames, precision);
        return (p == null) ? null : p.acquire();
    }

    /**
     * Returns a native buffer to the matching pool.
     *
     * @return {@code true} if the buffer was returned, {@code false} if the
     *         pool is full or not registered
     */
    @RealTimeSafe
    public boolean releaseNative(int channels, int frames, MixPrecision precision,
                                 NativeAudioBufferPool.PooledBuffer buffer) {
        NativeAudioBufferPool p = nativePool(channels, frames, precision);
        return p != null && p.release(buffer);
    }

    /** Number of registered native pools. */
    public int nativeSize() {
        return nativeEntries.length;
    }

    /**
     * Closes every registered native pool, deterministically freeing all
     * FFM-backed buffer memory in a single sweep. Must be called after
     * the audio thread has stopped. Idempotent; safe to call multiple
     * times. On-heap pools are unaffected (they are reclaimed by the GC).
     */
    @Override
    public synchronized void close() {
        NativeEntry[] snap = nativeEntries;
        for (NativeEntry ne : snap) {
            if (!ne.pool.isClosed()) {
                ne.pool.close();
            }
        }
    }

    @RealTimeSafe
    private int findNativeIndex(int channels, int frames, MixPrecision precision) {
        NativeEntry[] snap = nativeEntries;
        for (int i = 0; i < snap.length; i++) {
            NativeEntry e = snap[i];
            if (e.channels == channels && e.frames == frames && e.precision == precision) {
                return i;
            }
        }
        return -1;
    }

    @RealTimeSafe
    private int findIndex(int channels, int frames, MixPrecision precision) {
        Entry[] snap = entries;
        for (int i = 0; i < snap.length; i++) {
            Entry e = snap[i];
            if (e.channels == channels && e.frames == frames && e.precision == precision) {
                return i;
            }
        }
        return -1;
    }
}
