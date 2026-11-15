package com.benesquivelmusic.daw.app.diagnostics;

/**
 * What the transport was doing when the render drive halted (story 337,
 * rulings E11 and E45): it chooses the context sentence of the halt notice
 * ({@link FailureCopy#audioHalted}, {@link FailureCopy#audioHaltedFatal}).
 */
public enum HaltContext {

    /** The transport was rolling: "Playback was stopped." */
    PLAYBACK,

    /** A take was in flight: "Recording was stopped; the take keeps what was recorded before the audio failed." */
    RECORDING,

    /** The engine was only auditioning while the transport stood still: no context sentence. */
    IDLE
}
