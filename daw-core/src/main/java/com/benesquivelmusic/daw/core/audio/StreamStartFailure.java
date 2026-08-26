package com.benesquivelmusic.daw.core.audio;

import java.util.Objects;

/**
 * Immutable attribution for one failed audio-stream start invocation.
 *
 * <p>The engine captures the endpoint and operation while holding its
 * lifecycle lock, then binds that snapshot to the exact throwable propagated
 * by the invocation. {@link AudioEngine#takeFailedStreamStart(Throwable)}
 * returns the value once for immediate reporting. It is diagnostic history,
 * not a live assertion that the endpoint is still open, paused, requested, or
 * configured when the caller consumes it.</p>
 *
 * @param endpoint  endpoint this invocation actually tried to start or resume
 * @param operation failed lifecycle operation
 */
public record StreamStartFailure(StreamStartEndpoint endpoint, Operation operation) {

    /** Whether the failed invocation was a fresh start or a paused resume. */
    public enum Operation {
        START,
        RESUME
    }

    /** Validates the complete failure attribution at the core/app boundary. */
    public StreamStartFailure {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
    }
}
