package com.benesquivelmusic.daw.core.audio;

/**
 * How a capture input stalled, as {@link AudioEngine#declareInputStalled}
 * is told (story 338, rulings E11, E15 and E52).
 */
public enum InputStallKind {

    /**
     * The input delivered blocks, then stopped: an input halt, counted in
     * the engine's health record with its fault.
     */
    STOPPED,

    /**
     * The input delivered no block within the start-up allowance of a take:
     * its tracks are flagged and warned, and no input halt is counted.
     */
    NEVER_DELIVERED
}
