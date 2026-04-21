package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.XrunEvent;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An allocation-free, single-producer/single-consumer ring buffer that
 * transports {@link XrunEvent} snapshots from the audio thread to a
 * non-real-time consumer (typically the UI or a logger).
 *
 * <p>The buffer owns a pre-allocated array of mutable
 * {@link XrunSnapshot} slots. The audio thread calls one of the
 * {@code publish*} methods, which fills the next slot in place and
 * advances the tail — never constructing a record on the audio thread.
 * A consumer calls {@link #poll()} to observe the next snapshot, then
 * {@link #release(XrunSnapshot)} to hand it back for reuse.</p>
 *
 * <p>This is the allocation-free equivalent of
 * {@code Flow.Publisher<XrunEvent>} for the audio callback path.</p>
 */
@RealTimeSafe
public final class XrunEventRingBuffer {

    /**
     * The variant of an {@link XrunEvent} carried by a snapshot.
     * Maps one-to-one onto the sealed {@link XrunEvent} subtypes.
     */
    public enum Kind {
        /** Maps to {@link XrunEvent.BufferLate}. */
        BUFFER_LATE,
        /** Maps to {@link XrunEvent.BufferDropped}. */
        BUFFER_DROPPED,
        /** Maps to {@link XrunEvent.GraphOverload}. */
        GRAPH_OVERLOAD
    }

    /**
     * A mutable snapshot of an {@link XrunEvent}. The fields are a
     * flat union of every variant; only those relevant to
     * {@link #kind()} are meaningful. Always use {@link #toImmutable()}
     * (off the audio thread) to build a proper {@link XrunEvent}.
     */
    @RealTimeSafe
    public static final class XrunSnapshot {
        private Kind kind;
        private long frameIndex;
        private long deadlineMissNanos;
        private String offendingNodeId;
        private double cpuFraction;

        @RealTimeSafe public Kind kind() { return kind; }
        @RealTimeSafe public long frameIndex() { return frameIndex; }
        @RealTimeSafe public long deadlineMissNanos() { return deadlineMissNanos; }
        public String offendingNodeId() { return offendingNodeId; }
        @RealTimeSafe public double cpuFraction() { return cpuFraction; }

        /** Allocates an immutable {@link XrunEvent}; do not call on the audio thread. */
        public XrunEvent toImmutable() {
            return switch (kind) {
                case BUFFER_LATE -> new XrunEvent.BufferLate(
                        frameIndex, Duration.ofNanos(deadlineMissNanos));
                case BUFFER_DROPPED -> new XrunEvent.BufferDropped(frameIndex);
                case GRAPH_OVERLOAD -> new XrunEvent.GraphOverload(offendingNodeId, cpuFraction);
            };
        }
    }

    private final XrunSnapshot[] slots;
    private final int mask;
    private final int capacity;
    private final AtomicLong head = new AtomicLong(0); // consumer
    private final AtomicLong tail = new AtomicLong(0); // producer

    /**
     * Creates a ring buffer with at least the specified capacity. Capacity
     * is rounded up to the next power of two.
     */
    public XrunEventRingBuffer(int requestedCapacity) {
        if (requestedCapacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be positive: " + requestedCapacity);
        }
        int cap = 1;
        while (cap < requestedCapacity) {
            cap <<= 1;
        }
        this.capacity = cap;
        this.mask = cap - 1;
        this.slots = new XrunSnapshot[cap];
        for (int i = 0; i < cap; i++) {
            slots[i] = new XrunSnapshot();
        }
    }

    /** Publishes a {@link XrunEvent.BufferLate} event. Returns {@code false} if full. */
    @RealTimeSafe
    public boolean publishBufferLate(long frameIndex, long deadlineMissNanos) {
        if (deadlineMissNanos < 0) {
            return false;
        }
        XrunSnapshot slot = claim();
        if (slot == null) return false;
        slot.kind = Kind.BUFFER_LATE;
        slot.frameIndex = frameIndex;
        slot.deadlineMissNanos = deadlineMissNanos;
        slot.offendingNodeId = null;
        slot.cpuFraction = 0.0;
        commit();
        return true;
    }

    /** Publishes a {@link XrunEvent.BufferDropped} event. Returns {@code false} if full. */
    @RealTimeSafe
    public boolean publishBufferDropped(long frameIndex) {
        XrunSnapshot slot = claim();
        if (slot == null) return false;
        slot.kind = Kind.BUFFER_DROPPED;
        slot.frameIndex = frameIndex;
        slot.deadlineMissNanos = 0L;
        slot.offendingNodeId = null;
        slot.cpuFraction = 0.0;
        commit();
        return true;
    }

    /**
     * Publishes a {@link XrunEvent.GraphOverload} event. The {@code nodeId}
     * is stored by reference; callers must pass an interned or pooled
     * string (never a freshly-concatenated one) to stay allocation-free.
     * Returns {@code false} if full.
     */
    @RealTimeSafe
    public boolean publishGraphOverload(String nodeId, double cpuFraction) {
        if (nodeId == null || Double.isNaN(cpuFraction) || cpuFraction < 0.0) {
            return false;
        }
        XrunSnapshot slot = claim();
        if (slot == null) return false;
        slot.kind = Kind.GRAPH_OVERLOAD;
        slot.frameIndex = -1L;
        slot.deadlineMissNanos = 0L;
        slot.offendingNodeId = nodeId;
        slot.cpuFraction = cpuFraction;
        commit();
        return true;
    }

    /**
     * Returns the next snapshot, or {@code null} if the buffer is empty.
     * After reading, the caller MUST return the snapshot via
     * {@link #release(XrunSnapshot)} once it is no longer in use.
     */
    public XrunSnapshot poll() {
        long h = head.get();
        if (h >= tail.get()) {
            return null;
        }
        XrunSnapshot slot = slots[(int) (h & mask)];
        head.set(h + 1);
        return slot;
    }

    /**
     * Returns a previously-polled snapshot to the buffer for reuse. No-op
     * in the current implementation (slots are owned by the buffer), but
     * kept for API symmetry with pooled transports.
     */
    public void release(XrunSnapshot snapshot) {
        // Slots are statically owned by the ring; no action required.
    }

    public int capacity() { return capacity; }

    public int size() {
        long h = head.get();
        long t = tail.get();
        return (int) Math.max(0, t - h);
    }

    @RealTimeSafe
    private XrunSnapshot claim() {
        long t = tail.get();
        long h = head.get();
        if (t - h >= capacity) {
            return null; // full
        }
        return slots[(int) (t & mask)];
    }

    @RealTimeSafe
    private void commit() {
        tail.set(tail.get() + 1);
    }
}
