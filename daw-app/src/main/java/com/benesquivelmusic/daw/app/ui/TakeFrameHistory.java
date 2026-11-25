package com.benesquivelmusic.daw.app.ui;

/**
 * The transport frames of a recording take, sampled with
 * {@link System#nanoTime()} on each FX pulse over the last
 * {@link #SPAN_NANOS} (story 338, amendment A11, ruling E62), so a clock
 * event can be dated at the frame the transport was at when its evidence was
 * seen, which may be a second or two before the event is declared.
 *
 * <p>A mark is never dated before the take's start-frame (rulings E177 and
 * E183). The take's reach sample is remembered for the whole take: for a
 * take anchored at a punch-in, its first sample at or past the start-frame,
 * so its pre-roll never reaches; for any other take, its first sample,
 * whatever frame it reads. Evidence from before the reach, or unknown
 * evidence while it has not been taken, is dated at the start-frame. After
 * the reach any sampled frame dates evidence, one below the start-frame
 * included: a later lap of a loop take, or a loop the transport wrapped into
 * before the take's first sample.</p>
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
    /** Whether a take's start-frame is known since the last {@link #clear()}. */
    private boolean started;
    private long startFrame;
    /** Whether the start-frame is a punch-in: only then is a frame below it, before the reach, the pre-roll. */
    private boolean punchIn;
    /** Whether the reach sample was taken; its time is kept when the ring gives the sample up. */
    private boolean reached;
    private long reachNanos;

    /** Forgets every sample, the take's start-frame and its reach. */
    void clear() {
        oldest = 0;
        size = 0;
        started = false;
        reached = false;
    }

    /**
     * The take records from {@code startFrame}, the manifest's {@code start-frame}: given before its first sample.
     *
     * @param startFrame the take's start-frame, at the take's rate
     * @param punchIn    whether {@code startFrame} is a punch-in: then the take's first sample at or past it is the
     *                   reach, otherwise the take's first sample
     */
    void takeStartsAt(long startFrame, boolean punchIn) {
        this.startFrame = startFrame;
        this.punchIn = punchIn;
        started = true;
        reached = false;
    }

    /**
     * Records that the transport was at {@code frame} at {@code nowNanos}, and drops samples older than the span.
     *
     * @param nowNanos a {@link System#nanoTime()} reading, not earlier than the previous sample's
     * @param frame    the transport frame then, at the take's rate
     */
    void sample(long nowNanos, long frame) {
        if (started && !reached && (!punchIn || frame >= startFrame)) {
            reached = true;
            reachNanos = nowNanos;
        }
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
     * The frame a mark with {@code evidenceNanos} is dated at.
     *
     * @param evidenceNanos the {@link System#nanoTime()} of the evidence, or {@code 0} when it is unknown
     * @param currentFrame  the transport frame now
     * @return the take's start-frame when the evidence comes before the reach sample, or is unknown before it was
     *         taken; otherwise the frame of the newest sample taken at or before the evidence, or
     *         {@code currentFrame} when the evidence is unknown or older than every sample kept
     */
    long frameAt(long evidenceNanos, long currentFrame) {
        if (started && (!reached || (evidenceNanos != 0L && reachNanos - evidenceNanos > 0L))) {
            return startFrame;
        }
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
