package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry;
import com.benesquivelmusic.daw.sdk.audio.LatencyTelemetry.NodeKind;

import java.util.concurrent.atomic.AtomicLong;

/**
 * An allocation-free, single-producer/single-consumer ring buffer that
 * carries {@link LatencyTelemetry} snapshots from the audio thread to a
 * non-real-time consumer.
 *
 * <p>Like {@link XrunEventRingBuffer}, this class owns a pre-allocated
 * array of mutable {@link LatencySnapshot} slots; publishing fills a
 * slot in place and advances the tail without ever constructing a
 * {@link LatencyTelemetry} record on the audio thread.</p>
 */
@RealTimeSafe
public final class LatencyTelemetryRingBuffer {

    /**
     * A mutable snapshot of a {@link LatencyTelemetry} record. Always use
     * {@link #toImmutable()} (off the audio thread) to build the public
     * record.
     */
    @RealTimeSafe
    public static final class LatencySnapshot {
        private String nodeId;
        private NodeKind kind;
        private int samples;
        private String reportedBy;

        public String nodeId() { return nodeId; }
        @RealTimeSafe public NodeKind kind() { return kind; }
        @RealTimeSafe public int samples() { return samples; }
        public String reportedBy() { return reportedBy; }

        /** Allocates an immutable {@link LatencyTelemetry}; not real-time safe. */
        public LatencyTelemetry toImmutable() {
            return new LatencyTelemetry(nodeId, kind, samples, reportedBy);
        }
    }

    private final LatencySnapshot[] slots;
    private final int mask;
    private final int capacity;
    private final AtomicLong head = new AtomicLong(0);
    private final AtomicLong tail = new AtomicLong(0);

    public LatencyTelemetryRingBuffer(int requestedCapacity) {
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
        this.slots = new LatencySnapshot[cap];
        for (int i = 0; i < cap; i++) {
            slots[i] = new LatencySnapshot();
        }
    }

    /**
     * Publishes a latency telemetry record. {@code nodeId} and
     * {@code reportedBy} are stored by reference; callers must pass
     * interned or pooled strings to stay allocation-free.
     *
     * @return {@code false} if the buffer is full or any argument is invalid
     */
    @RealTimeSafe
    public boolean publish(String nodeId, NodeKind kind, int samples, String reportedBy) {
        if (nodeId == null || kind == null || reportedBy == null || samples < 0) {
            return false;
        }
        long t = tail.get();
        long h = head.get();
        if (t - h >= capacity) {
            return false;
        }
        LatencySnapshot slot = slots[(int) (t & mask)];
        slot.nodeId = nodeId;
        slot.kind = kind;
        slot.samples = samples;
        slot.reportedBy = reportedBy;
        // lazySet (release store) is sufficient for SPSC and avoids the
        // StoreLoad fence a plain volatile set would impose on the audio
        // thread; slot writes above happen-before this store.
        tail.lazySet(t + 1);
        return true;
    }

    /** Returns the next snapshot, or {@code null} if the buffer is empty. */
    public LatencySnapshot poll() {
        long h = head.get();
        if (h >= tail.get()) {
            return null;
        }
        LatencySnapshot slot = slots[(int) (h & mask)];
        head.lazySet(h + 1);
        return slot;
    }

    /** Returns a polled snapshot to the buffer. Slots are statically owned. */
    public void release(LatencySnapshot snapshot) {
        // no-op: slots are owned by the ring
    }

    public int capacity() { return capacity; }

    public int size() {
        long h = head.get();
        long t = tail.get();
        return (int) Math.max(0, t - h);
    }
}
