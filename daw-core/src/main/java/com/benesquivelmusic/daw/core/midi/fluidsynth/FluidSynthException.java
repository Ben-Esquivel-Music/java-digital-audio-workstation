package com.benesquivelmusic.daw.core.midi.fluidsynth;

import com.benesquivelmusic.daw.sdk.midi.SoundFontRendererException;

/**
 * Exception thrown when a FluidSynth native operation fails.
 *
 * <p>Wraps FluidSynth error codes and messages into Java exceptions.
 * FluidSynth functions typically return {@code FLUID_OK} (0) on success
 * or {@code FLUID_FAILED} (-1) on failure.</p>
 */
public class FluidSynthException extends SoundFontRendererException {

    private final int errorCode;

    /**
     * Creates a new FluidSynth exception.
     *
     * @param message   the error message
     * @param errorCode the FluidSynth error code
     */
    public FluidSynthException(String message, int errorCode) {
        super(message + " (FluidSynth error code: " + errorCode + ")");
        this.errorCode = errorCode;
    }

    /**
     * Creates a new FluidSynth exception with a cause.
     *
     * @param message   the error message
     * @param errorCode the FluidSynth error code
     * @param cause     the underlying cause
     */
    public FluidSynthException(String message, int errorCode, Throwable cause) {
        super(message + " (FluidSynth error code: " + errorCode + ")", cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the FluidSynth error code.
     *
     * @return the error code
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * Checks if a FluidSynth return code indicates failure, and throws if so.
     *
     * @param result    the FluidSynth return code
     * @param operation description of the operation that was attempted
     * @throws FluidSynthException if result equals {@link FluidSynthBindings#FLUID_FAILED}
     */
    public static void checkResult(int result, String operation) {
        if (result == FluidSynthBindings.FLUID_FAILED) {
            throw new FluidSynthException(operation + " failed", result);
        }
    }
}
