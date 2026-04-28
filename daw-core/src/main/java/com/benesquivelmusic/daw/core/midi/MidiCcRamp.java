package com.benesquivelmusic.daw.core.midi;

import java.util.ArrayList;
import java.util.List;

/**
 * Math-only helper that generates a linear ramp of {@link MidiCcEvent}
 * breakpoints between two endpoints at a configurable density.
 *
 * <p>Implements the "select two breakpoints, press {@code R}" feature of
 * the piano-roll CC lane: given a left and right breakpoint, produce the
 * intermediate breakpoints along the straight line between them, sampled
 * once every {@code stepColumns} columns. Endpoints are <b>not</b>
 * included in the result — the caller already has them.</p>
 *
 * <p>The default density is one point per grid step (one column).</p>
 */
public final class MidiCcRamp {

    private MidiCcRamp() {
        // utility
    }

    /**
     * Produces intermediate breakpoints between {@code left} and {@code right}
     * with one event every {@code stepColumns} columns.
     *
     * <p>If {@code stepColumns >= (right.column - left.column)} the result
     * is empty. Endpoints are not duplicated.</p>
     *
     * @param left        the earlier endpoint
     * @param right       the later endpoint (must have {@code column >= left.column})
     * @param stepColumns spacing between generated breakpoints, ≥ 1
     * @return a new list of intermediate breakpoints (may be empty)
     */
    public static List<MidiCcEvent> generate(MidiCcEvent left, MidiCcEvent right,
                                              int stepColumns) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("endpoints must not be null");
        }
        if (stepColumns < 1) {
            throw new IllegalArgumentException(
                    "stepColumns must be >= 1: " + stepColumns);
        }
        if (right.column() < left.column()) {
            throw new IllegalArgumentException(
                    "right.column must be >= left.column");
        }
        List<MidiCcEvent> out = new ArrayList<>();
        int span = right.column() - left.column();
        if (span <= stepColumns) {
            return out;
        }
        int valueSpan = right.value() - left.value();
        for (int c = left.column() + stepColumns; c < right.column(); c += stepColumns) {
            double t = (c - left.column()) / (double) span;
            int v = (int) Math.round(left.value() + t * valueSpan);
            out.add(new MidiCcEvent(c, v));
        }
        return out;
    }
}
