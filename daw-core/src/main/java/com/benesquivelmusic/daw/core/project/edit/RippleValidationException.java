package com.benesquivelmusic.daw.core.project.edit;

/**
 * Thrown when a ripple-edit computation determines that applying the shift
 * would cause destructive overlaps on one or more tracks.
 *
 * <p>Rather than silently losing data by shifting clips on top of each other,
 * {@link RippleEditService} rejects the edit up-front and lets the caller
 * surface a clear error to the user.</p>
 */
public final class RippleValidationException extends RuntimeException {

    /**
     * Creates a new validation exception with the given message.
     *
     * @param message a human-readable description of the overlap
     */
    public RippleValidationException(String message) {
        super(message);
    }
}
