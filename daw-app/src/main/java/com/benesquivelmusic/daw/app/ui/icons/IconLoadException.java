package com.benesquivelmusic.daw.app.ui.icons;

/**
 * Thrown when an SVG icon resource cannot be loaded or parsed.
 */
public class IconLoadException extends RuntimeException {

    public IconLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
