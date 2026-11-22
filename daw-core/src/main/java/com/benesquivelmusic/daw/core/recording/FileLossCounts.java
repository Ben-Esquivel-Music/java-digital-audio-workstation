package com.benesquivelmusic.daw.core.recording;

/**
 * The take's ring-overflow and truncation losses counted as the take's files
 * see them: only what reaches into the punch region counts. With no punch
 * region every loss counts, exactly as the raw ring counts do. The
 * {@code gap=} lines and the {@code truncated-frames} total of the manifest
 * are not affected; they are written as the losses happened.
 *
 * <p>A ring-overflow gap runs from its start frame to the start frame of the
 * next block the take received from that ring; across the loop wrap, to the
 * loop end and on from the loop start. All of its blocks count when that
 * extent overlaps the punch region, none otherwise. A gap no block follows
 * has the extent its {@code gap=} line gives: its start frame plus its blocks
 * times the take's block length, split at the loop wrap the same way; that the
 * take looped adds nothing (ruling E104). Truncated frames
 * lie after the kept frames of their block and count where they lie inside
 * the region; an episode counts once one of its frames does.</p>
 *
 * <p>Written by the flush thread only; the counts are read from any
 * thread.</p>
 */
final class FileLossCounts {

    private final boolean[] regionKnown;
    private final long[] regionStart;
    private final long[] regionEnd;
    private final long[] loopStart;
    private final long[] loopEnd;
    private final boolean[] gapPending;
    private final long[] gapStart;
    private final long[] gapBlocks;
    private final int[] gapBlockFrames;
    private final boolean[] episodeCounted;

    private volatile long overflowBlocks;
    private volatile long truncatedFrames;
    private volatile long truncationEpisodes;

    /** @param sources the number of input sources */
    FileLossCounts(int sources) {
        regionKnown = new boolean[sources];
        regionStart = new long[sources];
        regionEnd = new long[sources];
        loopStart = new long[sources];
        loopEnd = new long[sources];
        gapPending = new boolean[sources];
        gapStart = new long[sources];
        gapBlocks = new long[sources];
        gapBlockFrames = new int[sources];
        episodeCounted = new boolean[sources];
    }

    /**
     * A block of {@code source} was received: it ends the extent of a gap
     * waiting for it, and its punch region and loop window are the ones a
     * later gap of that source is judged by until the next block.
     *
     * @param blockStart  the block's start frame
     * @param regionFrom  the punch-in frame, or {@link FrameSpans#UNBOUNDED_START} with no punch region
     * @param regionTo    the punch-out frame, or {@link FrameSpans#UNBOUNDED_END} with no punch region
     * @param loopFrom    the block's loop start frame
     * @param loopTo      the block's loop end frame; not after {@code loopFrom} when the take does not loop
     */
    void blockReceived(int source, long blockStart, long regionFrom, long regionTo, long loopFrom, long loopTo) {
        regionKnown[source] = true;
        regionStart[source] = regionFrom;
        regionEnd[source] = regionTo;
        loopStart[source] = loopFrom;
        loopEnd[source] = loopTo;
        if (gapPending[source]) {
            gapPending[source] = false;
            if (extentOverlapsTheRegion(source, gapStart[source], blockStart)) {
                overflowBlocks = overflowBlocks + gapBlocks[source];
            }
        }
    }

    /**
     * The ring of {@code source} dropped {@code dropped} blocks after
     * {@code start}. Counted at once with no punch region; otherwise when the
     * next block (or the seal) closes its extent. Gaps noticed before that
     * block arrives join the first one.
     *
     * @param blockFrames the take's block length, the frames each dropped block held
     */
    void gapNoticed(int source, long start, long dropped, int blockFrames) {
        if (regionKnown[source] && !hasPunchRegion(source) && !gapPending[source]) {
            overflowBlocks = overflowBlocks + dropped;
            return;
        }
        if (gapPending[source]) {
            gapBlocks[source] += dropped;
            return;
        }
        gapPending[source] = true;
        gapStart[source] = start;
        gapBlocks[source] = dropped;
        gapBlockFrames[source] = blockFrames;
    }

    /** The take is sealed: every gap still waiting for a next block is judged by its own extent. */
    void takeSealed() {
        for (int source = 0; source < gapPending.length; source++) {
            if (!gapPending[source]) {
                continue;
            }
            gapPending[source] = false;
            boolean counted = !regionKnown[source] || !hasPunchRegion(source)
                    || FrameSpans.framesInsideAcrossTheWrap(gapStart[source],
                            gapBlocks[source] * gapBlockFrames[source], loopStart[source], loopEnd[source],
                            regionStart[source], regionEnd[source]) > 0L;
            if (counted) {
                overflowBlocks = overflowBlocks + gapBlocks[source];
            }
        }
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

    /** Ring-overflow blocks whose gap reaches into the punch region. Any thread. */
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

    private boolean extentOverlapsTheRegion(int source, long start, long next) {
        long from = regionStart[source];
        long to = regionEnd[source];
        if (next > start) {
            return FrameSpans.overlaps(start, next, from, to);
        }
        if (next == start) {
            return FrameSpans.overlaps(start, start + 1, from, to);
        }
        boolean looped = FrameSpans.looped(loopStart[source], loopEnd[source]);
        long wrapEnd = looped ? loopEnd[source] : FrameSpans.UNBOUNDED_END;
        long wrapStart = looped ? loopStart[source] : FrameSpans.UNBOUNDED_START;
        return FrameSpans.overlaps(start, wrapEnd, from, to) || FrameSpans.overlaps(wrapStart, next, from, to);
    }
}
