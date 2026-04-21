package com.benesquivelmusic.daw.core.audio.performance;

import java.lang.management.ManagementFactory;

/**
 * Debug-only instrument that detects heap allocation on the real-time
 * audio thread.
 *
 * <p>Uses {@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes(long)}
 * to read the JVM's per-thread allocation counter. The audio callback
 * bookends its processing with {@link #begin()} and {@link #end()}; any
 * non-zero delta is reported to a consumer (log, JFR event, UI bug banner).
 * The detector is designed to be a no-op in release builds — construct a
 * single shared instance with {@link #enabled(boolean)} and disable it in
 * production.</p>
 *
 * <p>This class is intentionally <em>not</em> {@code @RealTimeSafe}: the
 * {@link #begin()} / {@link #end()} calls take zero locks and, after
 * the first call on a given thread, allocate zero bytes. <b>The first</b>
 * {@code begin()} or {@code end()} call from a new thread does allocate
 * a one-element {@code long[]} (initial value of the per-thread
 * {@link ThreadLocal}), so before enabling instrumentation on the audio
 * thread callers should invoke {@link #warmUp()} from that thread (or
 * let a dry callback run with the detector disabled) to pre-populate
 * the {@code ThreadLocal}. {@link #begin()} and {@link #end()} also
 * invoke a JVM intrinsic whose cost is small but non-zero. Disable the
 * detector in release builds.</p>
 */
public final class RealtimeAllocationDetector {

    /**
     * Callback invoked when an allocation is observed on an instrumented
     * thread. Implementations should not allocate, log, or otherwise
     * block the audio thread — instead they typically set a flag or push
     * a fixed-size event to a ring buffer for an off-thread logger.
     */
    @FunctionalInterface
    public interface AllocationListener {
        /**
         * Called when a non-zero allocation delta was observed between
         * {@link RealtimeAllocationDetector#begin()} and
         * {@link RealtimeAllocationDetector#end()}.
         *
         * @param threadId          the audio thread's id
         * @param bytesAllocated    the delta in bytes (always &gt; 0)
         */
        void onAllocation(long threadId, long bytesAllocated);
    }

    private final com.sun.management.ThreadMXBean mx;
    private final AllocationListener listener;
    private volatile boolean enabled;

    // Per-thread starting allocation counter; set in begin(), consumed in end().
    // Stored on a pooled ThreadLocal so instrumentation stays allocation-free
    // after the first call from a given thread.
    private final ThreadLocal<long[]> start = ThreadLocal.withInitial(() -> new long[]{-1L});

    public RealtimeAllocationDetector(AllocationListener listener) {
        this(listener, false);
    }

    public RealtimeAllocationDetector(AllocationListener listener, boolean initiallyEnabled) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        this.listener = listener;
        this.enabled = initiallyEnabled;
        com.sun.management.ThreadMXBean bean = null;
        try {
            java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
            if (base instanceof com.sun.management.ThreadMXBean sun) {
                bean = sun;
            }
        } catch (Throwable t) { // defensive; bean is optional
            bean = null;
        }
        this.mx = bean;
    }

    /** Enables or disables instrumentation. Safe to call from any thread. */
    public void enabled(boolean value) {
        this.enabled = value;
    }

    /** Reports whether instrumentation is currently enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Reports whether allocation instrumentation is supported on this
     * JVM. The Sun extension is present on HotSpot/OpenJDK builds.
     */
    public boolean isSupported() {
        return mx != null && mx.isThreadAllocatedMemorySupported();
    }

    /**
     * Forces the per-thread {@link ThreadLocal} {@code long[]} to be
     * materialized on the calling thread, so that the first subsequent
     * {@link #begin()} / {@link #end()} call does not allocate. Call
     * this once from the audio thread during engine startup before
     * enabling instrumentation.
     */
    public void warmUp() {
        start.get();
    }

    /** Records the audio thread's allocation counter. Call at callback start. */
    public void begin() {
        if (!enabled || !isSupported()) {
            return;
        }
        start.get()[0] = mx.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    /**
     * Compares the current allocation counter against the value recorded
     * in {@link #begin()} and fires the listener if the delta is non-zero.
     * Call at callback end.
     */
    public void end() {
        if (!enabled || !isSupported()) {
            return;
        }
        long[] startSlot = start.get();
        long began = startSlot[0];
        if (began < 0) {
            return; // begin() was not called in this scope
        }
        long now = mx.getThreadAllocatedBytes(Thread.currentThread().threadId());
        startSlot[0] = -1L;
        long delta = now - began;
        if (delta > 0L) {
            listener.onAllocation(Thread.currentThread().threadId(), delta);
        }
    }
}
