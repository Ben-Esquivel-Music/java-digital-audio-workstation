package com.benesquivelmusic.daw.core.project.edit;

/**
 * The unit of measurement used by a {@link NudgeSettings} value.
 *
 * <p>Every DAW exposes a "nudge" command that moves the current
 * selection by a small, user-configurable amount. The amount is
 * meaningful only together with a unit — nudging by {@code 1} means
 * one frame, one millisecond, one grid step, or one bar fraction
 * depending on which value of this enum is selected.</p>
 *
 * <p>Java {@code enum} types are implicitly sealed — no classes outside
 * this file can extend this type — so this also fulfils the
 * "sealed enum" requirement from the user story.</p>
 *
 * <p>Story — Nudge Clips and Selections by Grid and by Sample.</p>
 */
public enum NudgeUnit {

    /** Nudge by an integral number of audio frames (samples). */
    FRAMES,

    /** Nudge by a number of milliseconds. Converted using the project tempo. */
    MILLISECONDS,

    /**
     * Nudge by a number of grid steps. The grid step size is supplied by
     * the caller (typically the editor's current grid resolution, e.g.
     * 1/16 note).
     */
    GRID_STEPS,

    /**
     * Nudge by a fraction of a bar. For example {@code amount = 0.25}
     * with this unit means a quarter-bar nudge.
     */
    BAR_FRACTION
}
