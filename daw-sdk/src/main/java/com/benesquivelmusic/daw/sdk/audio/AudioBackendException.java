package com.benesquivelmusic.daw.sdk.audio;

/**
 * Exception thrown when an audio backend operation fails.
 */
public class AudioBackendException extends RuntimeException {

    /**
     * Creates an exception with the given message.
     *
     * @param message the error message
     */
    public AudioBackendException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public AudioBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
