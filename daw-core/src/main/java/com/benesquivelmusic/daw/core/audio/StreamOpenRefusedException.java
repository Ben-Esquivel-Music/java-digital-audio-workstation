package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;

import java.util.Objects;

/**
 * An open the engine refused for a reason it can name (story 338, ruling
 * E112): the application tells the person why by {@link #reason()}, never by
 * the message, which is for the log and may change.
 */
public final class StreamOpenRefusedException extends AudioBackendException {

    /** Why the engine refused the open. */
    public enum Reason {
        /** The previous stream has not let go: its render pump has not exited, or its handle is still held. */
        PREVIOUS_STREAM_NOT_RELEASED,
        /** The device opened with an audio format the engine cannot render. */
        UNUSABLE_FORMAT,
        /** No device can be opened: none is configured, or its backend cannot stream on this host. */
        NOT_AVAILABLE
    }

    private final Reason reason;

    /**
     * @param reason  why the open was refused
     * @param message the log's wording
     */
    public StreamOpenRefusedException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * @param reason  why the open was refused
     * @param message the log's wording
     * @param cause   the failure behind the refusal
     */
    public StreamOpenRefusedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /** @return why the open was refused */
    public Reason reason() {
        return reason;
    }
}
