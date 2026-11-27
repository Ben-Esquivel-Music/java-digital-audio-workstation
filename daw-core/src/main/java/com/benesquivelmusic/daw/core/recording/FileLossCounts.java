package com.benesquivelmusic.daw.core.recording;

/**
 * The take's ring-overflow and truncation losses judged as the take's files
 * see them: only what reaches into the punch region counts. With no punch
 * region every loss a line records counts. The {@code gap=} lines and the
 * {@code truncated-frames} total of the manifest are not affected; they are
 * written as the losses happened.
 *
 * <p>A run of blocks a ring dropped right before a block the take kept is
 * judged by that block's punch region and loop window (ruling E273): its
 * extent runs from its {@code gap=} line, at the end of the block the ring
 * delivered before the run, to the start frame of the block after it; across
 * the loop wrap, to the loop end and on from the loop start. A line past the
 * loop end, at the end of a block across the wrap, starts where the transport
 * continued after the wrap. When that block starts at or before the line with
 * no loop wrap between them, as the take counts wraps, the transport was
 * moved back or stood still, and the run reaches the region when the frames
 * its blocks would have held forward from its line, or back from that block's
 * start, do (ruling E285). A run some of whose blocks do not count — the
 * silence after an input's declared stop, or input-starved blocks whose
 * starved run went on into that stop where it flags every track of the
 * input, or was still open at a device-loss seal — is judged by the extent of
 * the blocks it counts, which lead it (ruling E301); one
 * whose silence-only blocks came before its audio, by the frames to the next
 * block (rulings E277, E281 and E287). A run no block the take kept follows
 * has the extent its {@code gap=} line gives: its start frame plus its blocks
 * times the take's block length, split at the loop wrap the same way; that
 * the take looped adds nothing (rulings E104 and E275). Blocks a ring
 * received after the take's final sweep are such a run too (ruling E213). A
 * run a lap long or longer is judged by that extent too, whatever block
 * follows it, since its end points cannot tell which frames it passed in
 * between: the frames the transport passed from its start, followed across
 * every wrap, and it counts when any of them lies in the region (rulings E180
 * and E182). It passed the whole loop range only when its frames from the
 * loop start on number a lap or more; frames of lap 1's pre-roll count toward
 * no lap. Truncated frames lie after the kept frames of their block and count
 * where they lie inside the region; an episode counts once one of its frames
 * does.</p>
 *
 * <p>Written by the flush thread only, which judges a run and then counts it;
 * the counts are read from any thread.</p>
 */
final class FileLossCounts {

    private final boolean[] regionKnown;
    private final long[] regionStart;
    private final long[] regionEnd;
    private final long[] loopStart;
    private final long[] loopEnd;
    private final boolean[] episodeCounted;

    private volatile long overflowBlocks;
    private volatile long truncatedFrames;
    private volatile long truncationEpisodes;

    /** A source's punch region and loop window, those of the last block of it received (ruling E282). */
    record Window(long regionStart, long regionEnd, long loopStart, long loopEnd) {

        /** Whether the window has a punch region. */
        boolean hasPunchRegion() {
            return regionStart != FrameSpans.UNBOUNDED_START || regionEnd != FrameSpans.UNBOUNDED_END;
        }
    }

    /** @param sources the number of input sources */
    FileLossCounts(int sources) {
        regionKnown = new boolean[sources];
        regionStart = new long[sources];
        regionEnd = new long[sources];
        loopStart = new long[sources];
        loopEnd = new long[sources];
        episodeCounted = new boolean[sources];
    }

    /**
     * A block of {@code source} was received: its punch region and loop window are the ones the runs of that
     * source are judged by until the next block.
     *
     * @param regionFrom the punch-in frame, or {@link FrameSpans#UNBOUNDED_START} with no punch region
     * @param regionTo   the punch-out frame, or {@link FrameSpans#UNBOUNDED_END} with no punch region
     * @param loopFrom   the block's loop start frame
     * @param loopTo     the block's loop end frame; not after {@code loopFrom} when the take does not loop
     */
    void blockReceived(int source, long regionFrom, long regionTo, long loopFrom, long loopTo) {
        regionKnown[source] = true;
        regionStart[source] = regionFrom;
        regionEnd[source] = regionTo;
        loopStart[source] = loopFrom;
        loopEnd[source] = loopTo;
    }

    /**
     * Whether {@code frames} frames of a run of {@code source}'s ring from its line at {@code start} reach into
     * the punch region by the frames to {@code next}, the start of the block just received after the run
     * (rulings E86, E180, E182, E273 and E285). Called after that block's {@link #blockReceived}.
     *
     * @param wrapped whether the take counted a loop wrap between the block before the run and that block
     */
    boolean runReachesTheRegion(int source, long start, long frames, long next, boolean wrapped) {
        long from = regionStart[source];
        long to = regionEnd[source];
        long loopFrom = loopStart[source];
        long loopTo = loopEnd[source];
        boolean looped = FrameSpans.looped(loopFrom, loopTo);
        if (looped && frames >= loopTo - loopFrom) {
            // A lap or more: its end points cannot tell which frames it passed in between.
            return FrameSpans.framesInsideAcrossTheWrap(start, frames, loopFrom, loopTo, from, to) > 0L;
        }
        long first = FrameSpans.intoLoop(start, loopFrom, loopTo);
        if (next > first) {
            return FrameSpans.overlaps(first, next, from, to);
        }
        if (!wrapped || !looped) {
            // Moved back or standing still: the blocks lay in one of these two extents, which is not known.
            return FrameSpans.overlaps(first, first + frames, from, to)
                    || FrameSpans.overlaps(next - frames, next, from, to);
        }
        return FrameSpans.overlaps(first, loopTo, from, to) || FrameSpans.overlaps(loopFrom, next, from, to);
    }

    /**
     * Whether {@code frames} frames of {@code source}'s from {@code start}, split at the loop wrap, reach into the
     * punch region: a run judged by its own extent, which no kept block follows or some of whose blocks do not
     * count (rulings E104, E213, E275, E277 and E281); always with no punch region or no block received.
     */
    boolean reachesTheRegionByItsOwnExtent(int source, long start, long frames) {
        return !regionKnown[source] || !hasPunchRegion(source)
                || FrameSpans.framesInsideAcrossTheWrap(start, frames, loopStart[source], loopEnd[source],
                        regionStart[source], regionEnd[source]) > 0L;
    }

    /** Counts {@code blocks} lined blocks whose run reaches into the punch region. Flush thread. */
    void countOverflowBlocks(long blocks) {
        overflowBlocks = overflowBlocks + blocks;
    }

    /** {@code source}'s {@link Window}, or {@code null} before a block of it was received. */
    Window windowOf(int source) {
        return regionKnown[source]
                ? new Window(regionStart[source], regionEnd[source], loopStart[source], loopEnd[source])
                : null;
    }

    /**
     * One block of an over-long run lost {@code frames} frames after
     * {@code keptEnd}, its kept frames' end.
     *
     * @param opensEpisode whether this block is the first of its run
     */
    void truncated(int source, boolean opensEpisode, long keptEnd, long frames,
                   long regionFrom, long regionTo, long loopFrom, long loopTo) {
        if (opensEpisode) {
            episodeCounted[source] = false;
        }
        long inside = FrameSpans.framesInsideAcrossTheWrap(keptEnd, frames, loopFrom, loopTo, regionFrom, regionTo);
        if (inside <= 0L) {
            return;
        }
        truncatedFrames = truncatedFrames + inside;
        if (!episodeCounted[source]) {
            episodeCounted[source] = true;
            truncationEpisodes = truncationEpisodes + 1;
        }
    }

    /**
     * Lined ring-overflow blocks whose run reaches into the punch region, and blocks a ring received after the
     * take's final sweep, judged the same way; every one with no punch region (rulings E86, E104, E213, E273 and
     * E281). Any thread.
     */
    long overflowBlocks() {
        return overflowBlocks;
    }

    /** Truncated frames inside the punch region. Any thread. */
    long truncatedFrames() {
        return truncatedFrames;
    }

    /** Truncation episodes with at least one frame inside the punch region. Any thread. */
    long truncationEpisodes() {
        return truncationEpisodes;
    }

    private boolean hasPunchRegion(int source) {
        return regionStart[source] != FrameSpans.UNBOUNDED_START || regionEnd[source] != FrameSpans.UNBOUNDED_END;
    }
}
