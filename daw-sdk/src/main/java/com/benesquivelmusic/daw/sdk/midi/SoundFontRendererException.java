package com.benesquivelmusic.daw.sdk.midi;

/**
 * Exception thrown when a SoundFont rendering operation fails.
 *
 * <p>This is the base exception for all SoundFont renderer errors,
 * including SoundFont loading failures, MIDI routing errors, and
 * audio rendering failures.</p>
 */
public class SoundFontRendererException extends RuntimeException {

    /**
     * Creates an exception with the given message.
     *
     * @param message the error message
     */
    public SoundFontRendererException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public SoundFontRendererException(String message, Throwable cause) {
        super(message, cause);
    }
}
