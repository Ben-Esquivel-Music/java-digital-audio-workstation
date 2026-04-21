package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.midi.MidiEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pre-allocated pool of {@link MutableMidiEvent} slots used by the audio
 * thread to publish MIDI events without allocating a new
 * {@link com.benesquivelmusic.daw.sdk.midi.MidiEvent} record each callback.
 *
 * <p>The {@link MidiEvent} public type is an immutable record — perfect for
 * API boundaries but unusable on the audio thread because every emission
 * would allocate. This pool's {@link MutableMidiEvent} holders are mutable
 * and pre-allocated at construction; the audio thread acquires a holder,
 * fills it in place, publishes it to a ring buffer, and the consumer thread
 * releases it back to the pool.</p>
 *
 * <p>The pool is a wait-free lock-free stack — safe to call from the audio
 * callback.</p>
 */
@RealTimeSafe
public final class MidiEventPool {

    /**
     * A mutable, pooled MIDI event holder. Audio-thread producers fill the
     * fields in place; non-real-time consumers may read them or snapshot
     * them into an immutable {@link MidiEvent} record.
     *
     * <p>Field visibility is guaranteed by the happens-before ordering of
     * the lock-free ring buffer that transports these holders between
     * threads (see {@code LockFreeRingBuffer}).</p>
     */
    @RealTimeSafe
    public static final class MutableMidiEvent {
        private MidiEvent.Type type;
        private int channel;
        private int data1;
        private int data2;

        /** Replaces the current values with those from the given record. */
        @RealTimeSafe
        public void set(MidiEvent.Type type, int channel, int data1, int data2) {
            this.type = type;
            this.channel = channel;
            this.data1 = data1;
            this.data2 = data2;
        }

        /** Returns an immutable snapshot (allocates — use off the audio thread). */
        public MidiEvent toImmutable() {
            return new MidiEvent(type, channel, data1, data2);
        }

        @RealTimeSafe public MidiEvent.Type type() { return type; }
        @RealTimeSafe public int channel() { return channel; }
        @RealTimeSafe public int data1() { return data1; }
        @RealTimeSafe public int data2() { return data2; }
    }

    private final MutableMidiEvent[] pool;
    private final AtomicInteger top;

    /**
     * Creates a pool with {@code poolSize} pre-allocated holders.
     *
     * @param poolSize number of holders to pre-allocate; must be positive
     */
    public MidiEventPool(int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive: " + poolSize);
        }
        this.pool = new MutableMidiEvent[poolSize];
        for (int i = 0; i < poolSize; i++) {
            pool[i] = new MutableMidiEvent();
        }
        this.top = new AtomicInteger(poolSize);
    }

    /**
     * Acquires a free MIDI event holder. Returns {@code null} if the pool is
     * exhausted (the caller must treat exhaustion as a recoverable
     * back-pressure signal; never allocate a fresh holder on the audio thread).
     */
    @RealTimeSafe
    public MutableMidiEvent acquire() {
        int current = top.get();
        while (current > 0) {
            if (top.compareAndSet(current, current - 1)) {
                MutableMidiEvent ev = pool[current - 1];
                pool[current - 1] = null;
                return ev;
            }
            current = top.get();
        }
        return null;
    }

    /**
     * Returns a holder to the pool. Returns {@code false} if the pool is
     * already full.
     */
    @RealTimeSafe
    public boolean release(MutableMidiEvent ev) {
        int current = top.get();
        while (current < pool.length) {
            if (top.compareAndSet(current, current + 1)) {
                pool[current] = ev;
                return true;
            }
            current = top.get();
        }
        return false;
    }

    /** Available slots in the pool. */
    public int available() { return top.get(); }

    /** Total capacity of the pool. */
    public int capacity() { return pool.length; }
}
