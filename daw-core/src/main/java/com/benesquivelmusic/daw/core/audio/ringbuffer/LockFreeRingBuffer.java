package com.benesquivelmusic.daw.core.audio.ringbuffer;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A lock-free Single-Producer Single-Consumer (SPSC) ring buffer for
 * inter-thread communication between the audio thread and other threads.
 *
 * <p>Uses atomic head/tail indices with acquire/release memory ordering
 * to guarantee visibility without locks. Both {@link #write(Object)} and
 * {@link #read()} are wait-free and allocation-free — safe to call on the
 * real-time audio thread.</p>
 *
 * <p>The capacity is rounded up to the next power of two for efficient
 * index masking.</p>
 *
 * @param <T> the element type
 */
@RealTimeSafe
public final class LockFreeRingBuffer<T> {

    private final Object[] buffer;
    private final int mask;
    private final int capacity;

    /*
     * Head and tail are monotonically increasing counters.
     * - head: index of the next slot to read (consumer advances this)
     * - tail: index of the next slot to write (producer advances this)
     *
     * AtomicLong provides the acquire/release semantics needed for
     * correct cross-thread visibility without locks.
     */
    private final AtomicLong head = new AtomicLong(0);
    private final AtomicLong tail = new AtomicLong(0);

    /**
     * Creates a ring buffer with at least the specified capacity.
     *
     * <p>The actual capacity is the smallest power of two &ge; {@code minCapacity}.</p>
     *
     * @param minCapacity the minimum number of elements the buffer must hold
     * @throws IllegalArgumentException if {@code minCapacity} is not positive
     */
    public LockFreeRingBuffer(int minCapacity) {
        if (minCapacity <= 0) {
            throw new IllegalArgumentException("minCapacity must be positive: " + minCapacity);
        }
        this.capacity = nextPowerOfTwo(minCapacity);
        this.mask = capacity - 1;
        this.buffer = new Object[capacity];
    }

    /**
     * Writes an element into the buffer (producer side).
     *
     * @param element the element to write (must not be {@code null})
     * @return {@code true} if the element was written, {@code false} if the buffer is full
     */
    @RealTimeSafe
    public boolean write(T element) {
        long currentTail = tail.get();
        long currentHead = head.get();
        if (currentTail - currentHead >= capacity) {
            return false;
        }
        buffer[(int) (currentTail & mask)] = element;
        tail.lazySet(currentTail + 1);
        return true;
    }

    /**
     * Reads an element from the buffer (consumer side).
     *
     * @return the element, or {@code null} if the buffer is empty
     */
    @SuppressWarnings("unchecked")
    @RealTimeSafe
    public T read() {
        long currentHead = head.get();
        long currentTail = tail.get();
        if (currentHead >= currentTail) {
            return null;
        }
        int index = (int) (currentHead & mask);
        T element = (T) buffer[index];
        buffer[index] = null;
        head.lazySet(currentHead + 1);
        return element;
    }

    /**
     * Returns the number of elements available to read.
     *
     * @return the number of elements in the buffer
     */
    @RealTimeSafe
    public int size() {
        return (int) (tail.get() - head.get());
    }

    /**
     * Returns whether the buffer is empty.
     *
     * @return {@code true} if empty
     */
    @RealTimeSafe
    public boolean isEmpty() {
        return head.get() >= tail.get();
    }

    /**
     * Returns whether the buffer is full.
     *
     * @return {@code true} if full
     */
    @RealTimeSafe
    public boolean isFull() {
        return tail.get() - head.get() >= capacity;
    }

    /**
     * Returns the total capacity of the buffer.
     *
     * @return the capacity (always a power of two)
     */
    public int capacity() {
        return capacity;
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) {
            return 1;
        }
        return Integer.highestOneBit(value - 1) << 1;
    }
}
