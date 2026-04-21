package com.benesquivelmusic.daw.sdk.audio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A clip-local gain envelope expressed as an ordered sequence of dB
 * breakpoints with per-segment interpolation curves.
 *
 * <p>Each breakpoint is anchored to an absolute frame offset from the
 * start of the clip's source audio (not the timeline). Between two
 * consecutive breakpoints, the gain is interpolated in dB using the
 * {@link CurveShape} stored on the left (earlier) breakpoint. Before
 * the first breakpoint, the gain equals the first breakpoint's dB value;
 * after the last breakpoint, it equals the last breakpoint's dB value.</p>
 *
 * <p>Envelopes are <em>immutable value objects</em>: the breakpoint list
 * is defensively copied and sorted by frame offset at construction time.
 * Mutations should be expressed by building a new envelope (see
 * {@link #withBreakpoint(BreakpointDb)} and {@link #withoutBreakpoint(int)}).
 * Edits at the session level are captured as undoable actions that swap
 * the envelope reference on an {@code AudioClip}.</p>
 *
 * @param breakpoints the non-empty, sorted-by-frame-offset list of breakpoints
 */
public record ClipGainEnvelope(List<BreakpointDb> breakpoints) {

    public ClipGainEnvelope {
        Objects.requireNonNull(breakpoints, "breakpoints must not be null");
        if (breakpoints.isEmpty()) {
            throw new IllegalArgumentException("breakpoints must not be empty");
        }
        var copy = new ArrayList<>(breakpoints);
        for (BreakpointDb bp : copy) {
            Objects.requireNonNull(bp, "breakpoint must not be null");
        }
        copy.sort(Comparator.comparingLong(BreakpointDb::frameOffsetInClip));
        // Coalesce duplicates at the same frame offset by keeping the last
        // occurrence (stable with respect to input order) so that callers
        // observing a binary-searched segment never hit a zero-span segment
        // and the UI never has to render an ambiguous vertical jump.
        var deduped = new ArrayList<BreakpointDb>(copy.size());
        for (BreakpointDb bp : copy) {
            if (!deduped.isEmpty()
                    && deduped.getLast().frameOffsetInClip() == bp.frameOffsetInClip()) {
                deduped.set(deduped.size() - 1, bp);
            } else {
                deduped.add(bp);
            }
        }
        breakpoints = List.copyOf(deduped);
    }

    /**
     * Convenience constructor producing a single-breakpoint envelope
     * at frame offset zero &mdash; the lazy-migration shape for the
     * legacy scalar {@code clipGain}.
     *
     * @param dbGain the constant gain in dB
     * @return a new envelope with one breakpoint at frame {@code 0}
     */
    public static ClipGainEnvelope constant(double dbGain) {
        return new ClipGainEnvelope(List.of(
                new BreakpointDb(0L, dbGain, CurveShape.LINEAR)));
    }

    /**
     * Evaluates the envelope at the given clip-local frame position and
     * returns the resulting dB value.
     *
     * <p>Positions before the first breakpoint clamp to the first
     * breakpoint's value; positions after the last breakpoint clamp to
     * the last breakpoint's value.</p>
     *
     * @param frameInClip frame offset from the start of the clip source
     * @return the dB value at {@code frameInClip}
     */
    public double dbAtFrame(long frameInClip) {
        BreakpointDb first = breakpoints.getFirst();
        if (frameInClip <= first.frameOffsetInClip()) {
            return first.dbGain();
        }
        BreakpointDb last = breakpoints.getLast();
        if (frameInClip >= last.frameOffsetInClip()) {
            return last.dbGain();
        }
        // Binary search for the segment containing frameInClip.
        int lo = 0;
        int hi = breakpoints.size() - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (breakpoints.get(mid).frameOffsetInClip() <= frameInClip) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        BreakpointDb a = breakpoints.get(lo);
        BreakpointDb b = breakpoints.get(lo + 1);
        long span = b.frameOffsetInClip() - a.frameOffsetInClip();
        if (span <= 0L) {
            return b.dbGain();
        }
        double t = (double) (frameInClip - a.frameOffsetInClip()) / (double) span;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;
        double w = a.curve().weight(t);
        return a.dbGain() + (b.dbGain() - a.dbGain()) * w;
    }

    /**
     * Evaluates the envelope at the given clip-local frame position and
     * returns the resulting linear amplitude scalar
     * ({@code 10^(db/20)}).
     *
     * @param frameInClip frame offset from the start of the clip source
     * @return the linear amplitude scalar at {@code frameInClip}
     */
    public double linearAtFrame(long frameInClip) {
        return Math.pow(10.0, dbAtFrame(frameInClip) / 20.0);
    }

    /**
     * Fills a caller-supplied scratch buffer with linear amplitude gains
     * for a contiguous range of clip-local source frames, using a single
     * forward pass over the breakpoint list (segment-walk).
     *
     * <p>This is the real-time-safe evaluation path used by the render
     * pipeline: it does zero allocations, replaces the per-sample binary
     * search in {@link #dbAtFrame(long)} with a cursor that advances at
     * most {@code breakpoints.size()} times over the whole range, and
     * leaves the caller in control of buffer lifetime.</p>
     *
     * <p>{@code Math.pow} is still invoked per frame to preserve the dB
     * semantics of the curve shapes; call sites that don't need dB-exact
     * evaluation may interpolate {@code gains} further themselves.</p>
     *
     * @param startFrame the clip-local frame offset of {@code gains[0]}
     * @param gains      the caller-owned buffer to fill; only indices
     *                   {@code [0, count)} are written
     * @param count      the number of gains to write (must be {@code >= 0}
     *                   and {@code <= gains.length})
     */
    public void fillLinearGains(long startFrame, float[] gains, int count) {
        Objects.requireNonNull(gains, "gains must not be null");
        if (count < 0 || count > gains.length) {
            throw new IllegalArgumentException("invalid count: " + count);
        }
        if (count == 0) {
            return;
        }

        int size = breakpoints.size();
        BreakpointDb first = breakpoints.getFirst();
        BreakpointDb last = breakpoints.getLast();
        long firstOffset = first.frameOffsetInClip();
        long lastOffset = last.frameOffsetInClip();
        double firstLin = Math.pow(10.0, first.dbGain() / 20.0);
        double lastLin = Math.pow(10.0, last.dbGain() / 20.0);

        // Locate the starting segment via one binary search, then walk
        // forward with a cursor `i` pointing at the left breakpoint of the
        // current segment. Segments are [breakpoints[i], breakpoints[i+1]).
        int i = 0;
        if (size >= 2 && startFrame > firstOffset) {
            int lo = 0;
            int hi = size - 1;
            while (lo + 1 < hi) {
                int mid = (lo + hi) >>> 1;
                if (breakpoints.get(mid).frameOffsetInClip() <= startFrame) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            i = lo;
        }

        for (int f = 0; f < count; f++) {
            long frame = startFrame + f;

            if (frame <= firstOffset) {
                gains[f] = (float) firstLin;
                continue;
            }
            if (frame >= lastOffset) {
                gains[f] = (float) lastLin;
                continue;
            }

            // Advance cursor so that breakpoints[i+1].frameOffset > frame.
            while (i + 1 < size - 1
                    && breakpoints.get(i + 1).frameOffsetInClip() <= frame) {
                i++;
            }

            BreakpointDb a = breakpoints.get(i);
            BreakpointDb b = breakpoints.get(i + 1);
            long span = b.frameOffsetInClip() - a.frameOffsetInClip();
            if (span <= 0L) {
                gains[f] = (float) Math.pow(10.0, b.dbGain() / 20.0);
                continue;
            }
            double t = (double) (frame - a.frameOffsetInClip()) / (double) span;
            double w = a.curve().weight(t);
            double db = a.dbGain() + (b.dbGain() - a.dbGain()) * w;
            gains[f] = (float) Math.pow(10.0, db / 20.0);
        }
    }

    /**
     * Returns a copy of this envelope with the given breakpoint added
     * (or replacing any existing breakpoint at the same frame offset).
     *
     * @param bp the breakpoint to insert
     * @return a new envelope containing {@code bp}
     */
    public ClipGainEnvelope withBreakpoint(BreakpointDb bp) {
        Objects.requireNonNull(bp, "bp must not be null");
        var next = new ArrayList<BreakpointDb>(breakpoints.size() + 1);
        boolean replaced = false;
        for (BreakpointDb existing : breakpoints) {
            if (existing.frameOffsetInClip() == bp.frameOffsetInClip()) {
                next.add(bp);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(bp);
        }
        return new ClipGainEnvelope(next);
    }

    /**
     * Returns a copy of this envelope with the breakpoint at the given
     * index removed. The resulting envelope must retain at least one
     * breakpoint.
     *
     * @param index the index of the breakpoint to remove
     * @return a new envelope with the breakpoint removed
     * @throws IllegalStateException if removal would leave the envelope empty
     */
    public ClipGainEnvelope withoutBreakpoint(int index) {
        if (breakpoints.size() <= 1) {
            throw new IllegalStateException("cannot remove the last breakpoint");
        }
        var next = new ArrayList<>(breakpoints);
        next.remove(index);
        return new ClipGainEnvelope(next);
    }

    /**
     * A single breakpoint within a {@link ClipGainEnvelope}.
     *
     * @param frameOffsetInClip the clip-local frame offset (non-negative)
     * @param dbGain            the gain at this breakpoint, in dB
     * @param curve             the interpolation shape used from this
     *                          breakpoint to the next
     */
    public record BreakpointDb(long frameOffsetInClip, double dbGain, CurveShape curve) {

        public BreakpointDb {
            if (frameOffsetInClip < 0L) {
                throw new IllegalArgumentException(
                        "frameOffsetInClip must not be negative: " + frameOffsetInClip);
            }
            Objects.requireNonNull(curve, "curve must not be null");
        }
    }
}
