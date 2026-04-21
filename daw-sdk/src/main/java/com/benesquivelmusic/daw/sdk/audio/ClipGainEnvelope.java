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
        breakpoints = List.copyOf(copy);
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
