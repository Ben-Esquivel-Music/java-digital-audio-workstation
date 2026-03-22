package com.benesquivelmusic.daw.core.plugin.clap;

/**
 * Thrown when a CLAP plugin operation fails.
 */
public final class ClapException extends RuntimeException {

    public ClapException(String message) {
        super(message);
    }

    public ClapException(String message, Throwable cause) {
        super(message, cause);
    }
}
