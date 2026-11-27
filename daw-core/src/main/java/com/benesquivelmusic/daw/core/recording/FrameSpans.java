package com.benesquivelmusic.daw.core.recording;

/**
 * Frame arithmetic for the take's marks and its file-relative loss counts:
 * which part of a span of transport frames lies inside the punch region, and
 * where a span that runs past the loop end continues. Spans are half-open,
 * {@code [start, end)}, in frames at the take's rate.
 *
 * <p>A region with no bounds is written {@code [Long.MIN_VALUE,
 * Long.MAX_VALUE)}: everything lies inside it. A loop window whose end is not
 * after its start is no loop: nothing wraps.</p>
 */
final class FrameSpans {

    /** The region of a take with no punch region: every frame is inside it. */
    static final long UNBOUNDED_START = Long.MIN_VALUE;

    /** See {@link #UNBOUNDED_START}. */
    static final long UNBOUNDED_END = Long.MAX_VALUE;

    private FrameSpans() {
    }

    /**
     * The frames of {@code [start, end)} that lie inside {@code [regionStart, regionEnd)}.
     *
     * @return the overlap's length; {@code 0} when they do not overlap
     */
    static long framesInside(long start, long end, long regionStart, long regionEnd) {
        long from = Math.max(start, regionStart);
        long to = Math.min(end, regionEnd);
        return to > from ? to - from : 0L;
    }

    /** Whether {@code [start, end)} and {@code [regionStart, regionEnd)} share a frame. */
    static boolean overlaps(long start, long end, long regionStart, long regionEnd) {
        return framesInside(start, end, regionStart, regionEnd) > 0L;
    }

    /** The first frame of {@code [start, end)} inside the region; meaningful only when they overlap. */
    static long clipStart(long start, long regionStart) {
        return Math.max(start, regionStart);
    }

    /** The end of {@code [start, end)} clipped to the region; meaningful only when they overlap. */
    static long clipEnd(long end, long regionEnd) {
        return Math.min(end, regionEnd);
    }

    /** Whether the loop window wraps at all. */
    static boolean looped(long loopStart, long loopEnd) {
        return loopEnd > loopStart;
    }

    /**
     * Where the end of a span lies once the span is wrapped at the loop end:
     * a point past the loop end moves to where the transport continued after
     * the wrap, into {@code (loopStart, loopEnd]}, so a point exactly on a
     * loop boundary is the loop end however many laps it passed (ruling
     * E176). A point at or before the loop end, and any point when the
     * window does not loop, stays where it is.
     */
    static long intoLoop(long frame, long loopStart, long loopEnd) {
        if (!looped(loopStart, loopEnd) || frame <= loopEnd) {
            return frame;
        }
        long overshoot = (frame - loopEnd) % (loopEnd - loopStart);
        return overshoot == 0L ? loopEnd : loopStart + overshoot;
    }

    /**
     * How many wraps a span crosses before its end once it is wrapped at the
     * loop end: an end past the loop end lies that many laps after the lap the
     * span started in, and an end exactly on a loop boundary stays in the lap
     * it completed (ruling E176). None when the window does not loop.
     */
    static long wrapsBeforeTheEnd(long end, long loopStart, long loopEnd) {
        if (!looped(loopStart, loopEnd) || end <= loopEnd) {
            return 0L;
        }
        long length = loopEnd - loopStart;
        return (end - loopEnd + length - 1) / length;
    }

    /**
     * The frames of {@code [start, start + frames)} inside the region once the
     * span is split at the loop end and its remainder continues from the loop
     * start.
     */
    static long framesInsideAcrossTheWrap(long start, long frames, long loopStart, long loopEnd,
                                          long regionStart, long regionEnd) {
        long inside = 0L;
        long from = start;
        long to = start + frames;
        if (looped(loopStart, loopEnd)) {
            long length = loopEnd - loopStart;
            while (to > loopEnd) {
                if (from < loopEnd) {
                    inside += framesInside(from, loopEnd, regionStart, regionEnd);
                }
                from = Math.max(from, loopEnd) - length;
                to -= length;
            }
        }
        return inside + framesInside(from, to, regionStart, regionEnd);
    }

    /**
     * How many wraps {@code [start, start + frames)}, split at the loop end and continued from the loop start,
     * crosses before its first frame inside the region; {@code -1} when none of its frames is (ruling E282).
     */
    static long wrapsBeforeTheRegion(long start, long frames, long loopStart, long loopEnd,
                                     long regionStart, long regionEnd) {
        long from = start;
        long to = start + frames;
        long wraps = 0L;
        if (looped(loopStart, loopEnd)) {
            long length = loopEnd - loopStart;
            while (to > loopEnd) {
                if (from < loopEnd && overlaps(from, loopEnd, regionStart, regionEnd)) {
                    return wraps;
                }
                from = Math.max(from, loopEnd) - length;
                to -= length;
                wraps++;
            }
        }
        return overlaps(from, to, regionStart, regionEnd) ? wraps : -1L;
    }
}
