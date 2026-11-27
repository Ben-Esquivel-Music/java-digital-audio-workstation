package com.benesquivelmusic.daw.app.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * The transport frames of a recording take, sampled with
 * {@link System#nanoTime()} on each FX pulse over the last
 * {@link #SPAN_NANOS} (story 338, amendment A11, ruling E62), so a clock
 * event can be dated at the frame the transport was at when its evidence was
 * seen, which may be a second or two before the event is declared. An unlock
 * and a resync are dated at the newest sample at or before their evidence, a
 * relock at the oldest sample at or after it (ruling E221), so the region a
 * take is marked unlocked is never narrower than the evidence.
 *
 * <p>A mark is never dated before the take's start-frame (rulings E177 and
 * E183). The take's reach sample is remembered for the whole take: for a
 * take anchored at a punch-in, its first sample at or past the start-frame,
 * so its pre-roll never reaches; for any other take, its first sample,
 * whatever frame it reads. Evidence from before the reach, or unknown
 * evidence while it has not been taken, is dated at the start-frame, and so
 * is evidence after the reach whose newest sample at or before it is no
 * longer kept. After the reach any sampled frame dates evidence, one below
 * the start-frame included: a later lap of a loop take, or a loop the
 * transport wrapped into before the take's first sample.</p>
 *
 * <p>Beside the span it keeps each sample it gives up, at the span's edge or
 * at its capacity, that is the newest at or before a date a resync mark may
 * still be owed at, read at each sample from {@link #keepSamplesFor}: the
 * arrivals the xrun feed holds and the marks the journal has not handed to
 * the take. So a resync carried through a standstill of any length is dated
 * at the newest frame sampled at or before its own arrival, never at the
 * start-frame for want of a sample. A kept sample dates only evidence before
 * the sample that followed it, and goes once no date it is the newest at or
 * before is owed (ruling E291).</p>
 *
 * <p>FX thread only; not thread-safe.</p>
 */
final class TakeFrameHistory {

    /**
     * How far back samples are kept, and the newest sample before that span: longer than an unlock's two
     * one-second polls.
     */
    static final long SPAN_NANOS = 4_000_000_000L;

    /** Room for {@link #SPAN_NANOS} of pulses at up to 256 a second; older samples give way. */
    private static final int CAPACITY = 1024;

    private static final long[] NO_DATES = new long[0];

    /** A sample given up and kept, with the time of the sample that followed it. */
    private record Kept(long nanos, long frame, long nextNanos) {
    }

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
    /** Read at each sample: the dates a resync mark may still be owed at, as nanoTime readings (ruling E291). */
    private Supplier<long[]> owedDates = () -> NO_DATES;
    /** The samples given up and kept for an owed date, oldest first. */
    private final Deque<Kept> kept = new ArrayDeque<>();

    /** Forgets every sample, the kept ones included, the take's start-frame and its reach. */
    void clear() {
        oldest = 0;
        size = 0;
        kept.clear();
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
     * Where each {@link #sample} reads the dates a resync mark may still be owed at (ruling E291).
     *
     * @param dates read on the FX thread at each sample, as {@link System#nanoTime()} readings in any order; never
     *              {@code null}, nor what it answers
     */
    void keepSamplesFor(Supplier<long[]> dates) {
        owedDates = Objects.requireNonNull(dates, "dates must not be null");
    }

    /**
     * Records that the transport was at {@code frame} at {@code nowNanos}, and gives up samples older than the span,
     * keeping each an owed date still needs (ruling E291).
     *
     * @param nowNanos a {@link System#nanoTime()} reading, not earlier than the previous sample's
     * @param frame    the transport frame then, at the take's rate
     */
    void sample(long nowNanos, long frame) {
        if (started && !reached && (!punchIn || frame >= startFrame)) {
            reached = true;
            reachNanos = nowNanos;
        }
        long[] owed = owedDates.get();
        if (size == CAPACITY) {
            giveUpTheOldest(owed);
        }
        int slot = (oldest + size) % CAPACITY;
        nanos[slot] = nowNanos;
        frames[slot] = frame;
        size++;
        // The newest sample at or before the span's edge stays, so evidence older than the span, back to that sample,
        // is dated at it rather than after the evidence; older evidence is dated at a sample kept for an owed date
        // when that is the newest at or before it, and otherwise at the take's start-frame.
        while (size > 1 && nowNanos - nanos[(oldest + 1) % CAPACITY] > SPAN_NANOS) {
            giveUpTheOldest(owed);
        }
        if (!kept.isEmpty()) {
            kept.removeIf(sample -> !anyBetween(owed, sample.nanos(), sample.nextNanos()));
        }
    }

    private void giveUpTheOldest(long[] owed) {
        long next = nanos[(oldest + 1) % CAPACITY];
        if (anyBetween(owed, nanos[oldest], next)) {
            kept.addLast(new Kept(nanos[oldest], frames[oldest], next));
        }
        oldest = (oldest + 1) % CAPACITY;
        size--;
    }

    /** Whether a date of {@code dates} lies at or after {@code from} and before {@code to}. */
    private static boolean anyBetween(long[] dates, long from, long to) {
        for (long date : dates) {
            if (date - from >= 0L && date - to < 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * The frame an unlock or a resync with {@code evidenceNanos} is dated at.
     *
     * @param evidenceNanos the {@link System#nanoTime()} of the evidence, or {@code 0} when it is unknown
     * @param currentFrame  the transport frame now
     * @return the take's start-frame when the evidence comes before the reach sample, or is unknown before it was
     *         taken, or its newest sample at or before it is no longer kept; otherwise the frame of the newest sample
     *         taken at or before the evidence; {@code currentFrame} only for unknown evidence after the reach, or with
     *         no take started
     */
    long frameAt(long evidenceNanos, long currentFrame) {
        if (beforeTheReach(evidenceNanos)) {
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
        Iterator<Kept> newestFirst = kept.descendingIterator();
        while (newestFirst.hasNext()) {
            Kept sample = newestFirst.next();
            if (sample.nanos() - evidenceNanos <= 0L) {
                if (evidenceNanos - sample.nextNanos() < 0L) {
                    return sample.frame();
                }
                break;
            }
        }
        return started ? startFrame : currentFrame;
    }

    /**
     * The frame a relock with {@code evidenceNanos} is dated at (ruling E221).
     *
     * @param evidenceNanos the {@link System#nanoTime()} of the evidence, or {@code 0} when it is unknown
     * @param currentFrame  the transport frame now
     * @return the take's start-frame when the evidence comes before the reach sample, or is unknown before it was
     *         taken; {@code currentFrame} for unknown evidence after the reach; otherwise the frame of the oldest
     *         sample taken at or after the evidence, or empty when no sample was taken at or after it yet
     */
    OptionalLong relockFrameAt(long evidenceNanos, long currentFrame) {
        if (beforeTheReach(evidenceNanos)) {
            return OptionalLong.of(startFrame);
        }
        if (evidenceNanos == 0L) {
            return OptionalLong.of(currentFrame);
        }
        for (int i = 0; i < size; i++) {
            int slot = (oldest + i) % CAPACITY;
            if (nanos[slot] - evidenceNanos >= 0L) {
                return OptionalLong.of(frames[slot]);
            }
        }
        return OptionalLong.empty();
    }

    private boolean beforeTheReach(long evidenceNanos) {
        return started && (!reached || (evidenceNanos != 0L && reachNanos - evidenceNanos > 0L));
    }
}
