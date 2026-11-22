package com.benesquivelmusic.daw.app.ui;

/**
 * The transport frames of a recording take, sampled with
 * {@link System#nanoTime()} on each FX pulse over the last
 * {@link #SPAN_NANOS} (story 338, amendment A11, ruling E62), so a clock
 * event can be dated at the frame the transport was at when its evidence was
 * seen, which may be a second or two before the event is declared.
 *
 * <p>FX thread only; not thread-safe.</p>
 */
final class TakeFrameHistory {

    /** How far back samples are kept: longer than an unlock's two one-second polls. */
    static final long SPAN_NANOS = 4_000_000_000L;

    /** Room for {@link #SPAN_NANOS} of pulses at up to 256 a second; older samples give way. */
    private static final int CAPACITY = 1024;

    private final long[] nanos = new long[CAPACITY];
    private final long[] frames = new long[CAPACITY];
    /** Index of the oldest sample. */
    private int oldest;
    private int size;

    /** Forgets every sample. */
    void clear() {
        oldest = 0;
        size = 0;
    }

    /**
     * Records that the transport was at {@code frame} at {@code nowNanos}, and drops samples older than the span.
     *
     * @param nowNanos a {@link System#nanoTime()} reading, not earlier than the previous sample's
     * @param frame    the transport frame then, at the take's rate
     */
    void sample(long nowNanos, long frame) {
        if (size == CAPACITY) {
            oldest = (oldest + 1) % CAPACITY;
            size--;
        }
        int slot = (oldest + size) % CAPACITY;
        nanos[slot] = nowNanos;
        frames[slot] = frame;
        size++;
        while (size > 1 && nowNanos - nanos[oldest] > SPAN_NANOS) {
            oldest = (oldest + 1) % CAPACITY;
            size--;
        }
    }

    /**
     * The frame of the newest sample taken at or before {@code evidenceNanos}.
     *
     * @param evidenceNanos the {@link System#nanoTime()} of the evidence, or {@code 0} when it is unknown
     * @param currentFrame  the transport frame now
     * @return that sample's frame; {@code currentFrame} when the evidence is unknown or older than every sample
     */
    long frameAt(long evidenceNanos, long currentFrame) {
        if (evidenceNanos == 0L) {
            return currentFrame;
        }
        for (int i = size - 1; i >= 0; i--) {
            int slot = (oldest + i) % CAPACITY;
            if (nanos[slot] - evidenceNanos <= 0L) {
                return frames[slot];
            }
        }
        return currentFrame;
    }
}
