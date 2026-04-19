package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pre-allocated pool of {@link AudioBuffer} instances for use on the
 * real-time audio thread.
 *
 * <p>All buffers are allocated up front during construction. The
 * {@link #acquire()} and {@link #release(AudioBuffer)} methods are lock-free
 * and allocation-free, making them safe to call from the audio callback.</p>
 *
 * <p>The pool uses a simple lock-free stack backed by an array and an
 * atomic top-of-stack index.</p>
 */
@RealTimeSafe
public final class AudioBufferPool {

    private final AudioBuffer[] pool;
    private final AtomicInteger top;
    private final int channels;
    private final int frames;

    /**
     * Cached double-precision backing buffer for {@link #viewDouble()}.
     * Allocated lazily on first call and reused on subsequent calls so
     * that the 64-bit mix bus path stays allocation-free at steady state.
     */
    private DoubleAudioBuffer cachedDoubleBuffer;

    /**
     * Creates a pool of pre-allocated audio buffers.
     *
     * @param poolSize the number of buffers to pre-allocate
     * @param channels the number of channels per buffer
     * @param frames   the number of sample frames per buffer
     */
    public AudioBufferPool(int poolSize, int channels, int frames) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive: " + poolSize);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        this.channels = channels;
        this.frames = frames;
        this.pool = new AudioBuffer[poolSize];
        for (int i = 0; i < poolSize; i++) {
            pool[i] = new AudioBuffer(channels, frames);
        }
        this.top = new AtomicInteger(poolSize);
    }

    /**
     * Acquires a buffer from the pool. The buffer is cleared (filled with silence)
     * before being returned.
     *
     * @return an {@link AudioBuffer}, or {@code null} if the pool is exhausted
     */
    @RealTimeSafe
    public AudioBuffer acquire() {
        int currentTop = top.get();
        while (currentTop > 0) {
            if (top.compareAndSet(currentTop, currentTop - 1)) {
                AudioBuffer buf = pool[currentTop - 1];
                pool[currentTop - 1] = null;
                buf.clear();
                return buf;
            }
            currentTop = top.get();
        }
        return null;
    }

    /**
     * Returns a buffer to the pool for reuse.
     *
     * @param buffer the buffer to release
     * @return {@code true} if the buffer was returned, {@code false} if the pool is full
     */
    @RealTimeSafe
    public boolean release(AudioBuffer buffer) {
        int currentTop = top.get();
        while (currentTop < pool.length) {
            if (top.compareAndSet(currentTop, currentTop + 1)) {
                pool[currentTop] = buffer;
                return true;
            }
            currentTop = top.get();
        }
        return false;
    }

    /**
     * Returns the number of buffers currently available in the pool.
     *
     * @return the available buffer count
     */
    public int available() {
        return top.get();
    }

    /**
     * Returns the total capacity of the pool.
     *
     * @return the pool size
     */
    public int capacity() {
        return pool.length;
    }

    /**
     * Returns the number of channels per buffer.
     *
     * @return the channel count
     */
    public int getChannels() {
        return channels;
    }

    /**
     * Returns the number of frames per buffer.
     *
     * @return the frame count
     */
    public int getFrames() {
        return frames;
    }

    /**
     * Returns a 32-bit single-precision {@link BufferView.FloatBufferView}
     * over an acquired {@link AudioBuffer}. The view shares storage with
     * the underlying buffer and is real-time safe to create.
     *
     * @param buffer a buffer acquired from this pool
     * @return a float view over the buffer
     * @see com.benesquivelmusic.daw.sdk.audio.MixPrecision#FLOAT_32
     */
    @RealTimeSafe
    public BufferView.FloatBufferView viewFloat(AudioBuffer buffer) {
        return BufferView.FloatBufferView.of(buffer);
    }

    /**
     * Returns a 64-bit double-precision {@link BufferView.DoubleBufferView}
     * over a shared {@link DoubleAudioBuffer} with the same channel and
     * frame dimensions as this pool.
     *
     * <p>The backing {@code DoubleAudioBuffer} is allocated once on the
     * first call and reused on subsequent calls (cleared to silence each
     * time), keeping the 64-bit mix bus path allocation-free at steady
     * state.</p>
     *
     * @return a double view sized to match this pool
     * @see com.benesquivelmusic.daw.sdk.audio.MixPrecision#DOUBLE_64
     */
    public BufferView.DoubleBufferView viewDouble() {
        if (cachedDoubleBuffer == null) {
            cachedDoubleBuffer = new DoubleAudioBuffer(channels, frames);
        }
        cachedDoubleBuffer.clear();
        return BufferView.DoubleBufferView.of(cachedDoubleBuffer);
    }
}
