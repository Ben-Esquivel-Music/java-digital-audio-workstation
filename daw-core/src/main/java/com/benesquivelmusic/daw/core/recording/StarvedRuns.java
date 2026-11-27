package com.benesquivelmusic.daw.core.recording;

import java.util.ArrayList;
import java.util.List;

/**
 * Each input source's open run of input-starved blocks, kept as the
 * {@code input-silence} lines it becomes when the run ends. Flush thread
 * only.
 *
 * <p>A starved block adds only its frames inside the punch region; a block
 * with none adds nothing and is not counted, so a run wholly outside the
 * region writes nothing and counts nothing. A block's frames that run past
 * its loop end are cut there and continue from the loop start, so no line
 * runs past the loop end. Frames that follow on from the last line extend
 * it; any others (after the take passed the loop end, a seek, or a re-entry
 * into the region) start a new line. The block count is one per block,
 * whatever the lap.</p>
 */
final class StarvedRuns {

    /** One {@code input-silence} line: a start frame and a positive frame count. */
    record Line(long startFrame, long frames) {
    }

    /** A run that ended: its lines, the blocks that gave it frames, and the frames it covers. */
    record Run(List<Line> lines, long blocks, long frames) {
        static final Run NONE = new Run(List.of(), 0L, 0L);
    }

    private static final class Span {
        final long start;
        long end;

        Span(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private final List<List<Span>> spans;
    private final long[] blocks;
    private final long[] frames;

    /** @param sources the number of input sources */
    StarvedRuns(int sources) {
        spans = new ArrayList<>(sources);
        for (int source = 0; source < sources; source++) {
            spans.add(new ArrayList<>());
        }
        blocks = new long[sources];
        frames = new long[sources];
    }

    /**
     * Adds one starved block's frames inside the punch region,
     * {@code [sliceStart, sliceEnd)}; an empty slice adds nothing.
     *
     * @param loopStart the block's loop start frame
     * @param loopEnd   the block's loop end frame; not after {@code loopStart} when the take does not loop
     */
    void addBlock(int source, long sliceStart, long sliceEnd, long loopStart, long loopEnd) {
        if (sliceEnd <= sliceStart) {
            return;
        }
        List<Span> run = spans.get(source);
        long from = sliceStart;
        long to = sliceEnd;
        if (FrameSpans.looped(loopStart, loopEnd)) {
            long length = loopEnd - loopStart;
            while (to > loopEnd) {
                if (from < loopEnd) {
                    append(run, from, loopEnd);
                }
                from = Math.max(from, loopEnd) - length;
                to -= length;
            }
        }
        append(run, from, to);
        blocks[source]++;
        frames[source] += sliceEnd - sliceStart;
    }

    /** Whether {@code source} has a run with frames in the file. */
    boolean isOpen(int source) {
        return !spans.get(source).isEmpty();
    }

    /** Ends {@code source}'s run and returns it; {@link Run#NONE} when it had no frames. */
    Run end(int source) {
        List<Span> run = spans.get(source);
        if (run.isEmpty()) {
            return Run.NONE;
        }
        List<Line> lines = new ArrayList<>(run.size());
        for (Span span : run) {
            lines.add(new Line(span.start, span.end - span.start));
        }
        Run ended = new Run(List.copyOf(lines), blocks[source], frames[source]);
        run.clear();
        blocks[source] = 0L;
        frames[source] = 0L;
        return ended;
    }

    private static void append(List<Span> run, long from, long to) {
        if (to <= from) {
            return;
        }
        Span last = run.isEmpty() ? null : run.getLast();
        if (last != null && last.end == from) {
            last.end = to;
        } else {
            run.add(new Span(from, to));
        }
    }
}
