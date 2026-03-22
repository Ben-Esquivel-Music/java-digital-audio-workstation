package com.benesquivelmusic.daw.core.audio.portaudio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;

/**
 * Exception thrown when a PortAudio native operation fails.
 *
 * <p>Wraps PortAudio error codes into Java exceptions. Error codes
 * correspond to the {@code PaError} enum from the PortAudio C API.</p>
 */
public class PortAudioException extends AudioBackendException {

    private final int errorCode;

    /**
     * Creates a new PortAudio exception.
     *
     * @param message   the error message
     * @param errorCode the PortAudio error code
     */
    public PortAudioException(String message, int errorCode) {
        super(message + " (PortAudio error code: " + errorCode + ")");
        this.errorCode = errorCode;
    }

    /**
     * Creates a new PortAudio exception with a cause.
     *
     * @param message   the error message
     * @param errorCode the PortAudio error code
     * @param cause     the underlying cause
     */
    public PortAudioException(String message, int errorCode, Throwable cause) {
        super(message + " (PortAudio error code: " + errorCode + ")", cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the PortAudio error code.
     *
     * @return the error code
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * Checks if a PortAudio return code indicates an error, and throws if so.
     *
     * @param errorCode the PortAudio error code
     * @param operation description of the operation that was attempted
     * @throws PortAudioException if errorCode is negative (indicating an error)
     */
    public static void checkError(int errorCode, String operation) {
        if (errorCode < 0) {
            throw new PortAudioException(operation + " failed", errorCode);
        }
    }
}
