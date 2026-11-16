package com.benesquivelmusic.daw.app.diagnostics;

/**
 * What the halt's own Stop did when the render drive halted (story 337,
 * rulings E11, E45 and E101): it chooses the context sentence of the halt
 * notice ({@link FailureCopy#audioHalted}, {@link FailureCopy#audioHaltedFatal}),
 * which "says only what the halt's own Stop did" (ruling E101).
 * {@link #STREAM_REPLACED} says instead that the halt response acted on
 * nothing, because the halted stream was no longer the open one (story 337,
 * rulings E69, E70 and E72).
 */
public enum HaltContext {

    /**
     * The Stop ended no started take and stopped a playing transport, "a
     * post-roll tail included, and that holds when the same Stop also
     * cancelled a take still being prepared": "Playback was stopped."
     */
    PLAYBACK,

    /**
     * The Stop "ended a take that had started recording, meaning the take was
     * past its preparation and any count-in": "Recording was stopped; the take
     * keeps what was recorded before the audio failed."
     */
    RECORDING,

    /**
     * The Stop "only cancelled a take still being prepared over a stopped
     * transport, or cancelled a count-in (audio, mixed or MIDI-only)", or no
     * Stop ran: no context sentence.
     */
    IDLE,

    /**
     * The halted stream was already reaped, replaced, paused, closed or being
     * reopened when the drain saw the halt: nothing was stopped or closed and
     * no close is owed; no context sentence (story 337, rulings E70 and E72).
     */
    STREAM_REPLACED
}
